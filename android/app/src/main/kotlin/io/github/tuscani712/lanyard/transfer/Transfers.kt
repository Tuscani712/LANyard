package io.github.tuscani712.lanyard.transfer

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import io.github.tuscani712.lanyard.IdentityHolder
import io.github.tuscani712.lanyard.core.DownloadResult
import io.github.tuscani712.lanyard.core.DownloadSession
import io.github.tuscani712.lanyard.core.DownloadTarget
import io.github.tuscani712.lanyard.core.PairedPeer
import io.github.tuscani712.lanyard.core.PeerClient
import io.github.tuscani712.lanyard.core.PushResult
import io.github.tuscani712.lanyard.core.PushSession
import io.github.tuscani712.lanyard.core.PushSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

enum class TransferState { Queued, Running, Done, Failed, Cancelled }

/** One row on the Transfers screen. */
data class TransferRecord(
    val id: String,
    val direction: String, // "send" | "receive"
    val peerName: String,
    val label: String,
    val total: Long,
    val done: Long,
    val state: TransferState,
    val message: String? = null,
    val startedAt: Long,
)

/**
 * The single owner of transfers. Both the send and receive paths run here on
 * [Dispatchers.IO]; the Transfers screen observes [state], and [TransferService]
 * mirrors it into a notification. The last 100 records persist to app-private
 * storage.
 */
object TransferManager {
    private const val PREFS = "transfers"
    private const val KEY_TREE = "download_tree"
    private const val HISTORY_CAP = 100

    private lateinit var app: Application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson: Gson = GsonBuilder().create()
    private val historyType = object : TypeToken<MutableList<TransferRecord>>() {}.type

    private val _state = MutableStateFlow<List<TransferRecord>>(emptyList())
    val state: StateFlow<List<TransferRecord>> = _state.asStateFlow()

    private val cancels = HashMap<String, AtomicBoolean>()

    fun init(application: Application) {
        app = application
        _state.value = loadHistory()
    }

    fun running(): List<TransferRecord> = _state.value.filter { it.state == TransferState.Running || it.state == TransferState.Queued }

    fun cancel(id: String) {
        cancels[id]?.set(true)
    }

    fun cancelAllRunning() {
        cancels.values.forEach { it.set(true) }
    }

    fun clearFinished() {
        _state.value = _state.value.filter { it.state == TransferState.Running || it.state == TransferState.Queued }
        persist()
    }

    // --- destination folder (SAF) ---

    fun rememberedTree(): Uri? = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE, null)?.let(Uri::parse)

    fun rememberTree(uri: Uri) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TREE, uri.toString()).apply()
    }

    // --- enqueue ---

    fun enqueuePush(peer: PairedPeer, sources: List<PushSource>, label: String): String {
        val id = newId()
        add(TransferRecord(id, "send", peer.name, label, sources.sumOf { it.size }, 0, TransferState.Queued, null, now()))
        TransferService.start(app)
        scope.launch { runPush(id, peer, sources) }
        return id
    }

    fun enqueueDownload(peer: PairedPeer, shareId: String, path: String, label: String, tree: Uri): String {
        val id = newId()
        add(TransferRecord(id, "receive", peer.name, label, 0, 0, TransferState.Queued, null, now()))
        TransferService.start(app)
        scope.launch { runDownload(id, peer, shareId, path, tree) }
        return id
    }

    private suspend fun runPush(id: String, peer: PairedPeer, sources: List<PushSource>) {
        val identity = IdentityHolder.identity ?: return fail(id, "no identity on this device")
        val cancel = AtomicBoolean(false)
        cancels[id] = cancel
        setState(id, TransferState.Running)
        val sent = LongArray(sources.size)
        val result = try {
            PushSession(PeerClient(peer.host, peer.port, identity, peer.fingerprint)).push(
                sources = sources,
                onProgress = { index, bytes, _ ->
                    sent[index] = bytes
                    update(id) { it.copy(done = sent.sum()) }
                },
                isCancelled = { cancel.get() },
            )
        } catch (e: Exception) {
            PushResult.Failed(e.message ?: "send failed")
        }
        cancels.remove(id)
        finish(id, result)
    }

    private suspend fun runDownload(id: String, peer: PairedPeer, shareId: String, path: String, tree: Uri) {
        val identity = IdentityHolder.identity ?: return fail(id, "no identity on this device")
        val root = DocumentFile.fromTreeUri(app, tree) ?: return fail(id, "cannot open the destination folder")
        val cancel = AtomicBoolean(false)
        cancels[id] = cancel
        setState(id, TransferState.Running)
        val sent = HashMap<Int, Long>()
        val result = try {
            DownloadSession(PeerClient(peer.host, peer.port, identity, peer.fingerprint)).download(
                shareId = shareId,
                path = path,
                targetFor = { file ->
                    docTarget(root, file.path.ifEmpty { file.name })
                        ?: throw IllegalStateException("cannot create ${file.name}")
                },
                onProgress = { index, file, received, total ->
                    sent[index] = received
                    update(id) { it.copy(done = sent.values.sum(), total = maxOf(it.total, total)) }
                },
                isCancelled = { cancel.get() },
            )
        } catch (e: Exception) {
            DownloadResult.Failed(e.message ?: "download failed")
        }
        cancels.remove(id)
        finishDownload(id, result)
    }

    private fun docTarget(root: DocumentFile, rel: String): DownloadTarget? {
        val dir = ensureDirs(root, rel.substringBeforeLast('/', "")) ?: return null
        val name = rel.substringAfterLast('/')
        val existing = dir.findFile(name)
        val target = if (existing == null) {
            dir.createFile("application/octet-stream", name)
        } else {
            dir.createFile("application/octet-stream", uniqueName(dir, name))
        } ?: return null
        return DownloadTarget(
            existingSize = 0,
            openAt = { app.contentResolver.openOutputStream(target.uri, "wt") ?: error("cannot open output") },
        )
    }

    private fun ensureDirs(root: DocumentFile, path: String): DocumentFile? {
        if (path.isEmpty()) return root
        var current = root
        for (segment in path.split('/')) {
            if (segment.isEmpty()) continue
            current = current.findFile(segment) ?: current.createDirectory(segment) ?: return null
            if (!current.isDirectory) return null
        }
        return current
    }

    private fun uniqueName(dir: DocumentFile, name: String): String {
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var n = 1
        while (dir.findFile("$base ($n)$ext") != null) n++
        return "$base ($n)$ext"
    }

    // --- state plumbing ---

    private fun add(record: TransferRecord) {
        _state.value = (listOf(record) + _state.value).take(HISTORY_CAP)
        persist()
    }

    private fun setState(id: String, state: TransferState) = update(id) { it.copy(state = state) }

    private fun update(id: String, block: (TransferRecord) -> TransferRecord) {
        _state.value = _state.value.map { if (it.id == id) block(it) else it }
    }

    private fun finish(id: String, result: PushResult) {
        when (result) {
            is PushResult.Sent -> complete(id, "Sent ${result.files} file(s)")
            PushResult.Cancelled -> complete(id, "Cancelled", TransferState.Cancelled)
            PushResult.CancelledByReceiver -> fail(id, "The other device cancelled")
            PushResult.Refused -> fail(id, "The other device is not accepting files")
            is PushResult.Failed -> fail(id, result.message)
        }
    }

    private fun finishDownload(id: String, result: DownloadResult) {
        when (result) {
            is DownloadResult.Done -> complete(id, "Received ${result.files} file(s)")
            DownloadResult.Cancelled -> complete(id, "Cancelled", TransferState.Cancelled)
            DownloadResult.ShareEnded -> fail(id, "The sender stopped this share")
            DownloadResult.PeerUnreachable -> fail(id, "The other device is unreachable")
            is DownloadResult.HashMismatch -> fail(id, "A file failed its checksum")
            DownloadResult.UnsafePath -> fail(id, "The share contained an unsafe path")
            is DownloadResult.Failed -> fail(id, result.message)
        }
    }

    private fun complete(id: String, message: String, state: TransferState = TransferState.Done) {
        update(id) { it.copy(state = state, message = message, done = if (state == TransferState.Done) it.total else it.done) }
        persist()
    }

    private fun fail(id: String, message: String) {
        update(id) { it.copy(state = TransferState.Failed, message = message) }
        persist()
    }

    private fun newId(): String {
        val buf = ByteArray(6)
        SecureRandom().nextBytes(buf)
        return "t_" + buf.joinToString("") { "%02x".format(it) }
    }

    private fun now(): Long = System.currentTimeMillis()

    // --- persistence ---

    private fun historyFile(): File = File(app.filesDir, "transfers.json")

    private fun loadHistory(): List<TransferRecord> = try {
        val f = historyFile()
        if (!f.isFile) emptyList()
        else (gson.fromJson<MutableList<TransferRecord>>(f.readText(), historyType) ?: mutableListOf())
            .map { if (it.state == TransferState.Running || it.state == TransferState.Queued) it.copy(state = TransferState.Failed, message = "Interrupted") else it }
    } catch (_: Exception) {
        emptyList()
    }

    private fun persist() {
        runCatching {
            historyFile().writeText(gson.toJson(_state.value.take(HISTORY_CAP), historyType))
        }
    }
}
