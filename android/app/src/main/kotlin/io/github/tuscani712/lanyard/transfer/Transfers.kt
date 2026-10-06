package io.github.tuscani712.lanyard.transfer

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import io.github.tuscani712.lanyard.IdentityHolder
import io.github.tuscani712.lanyard.core.DownloadResult
import io.github.tuscani712.lanyard.core.DownloadSession
import io.github.tuscani712.lanyard.core.DownloadTarget
import io.github.tuscani712.lanyard.core.MeteredNetwork
import io.github.tuscani712.lanyard.core.PairedPeer
import io.github.tuscani712.lanyard.core.PeerClient
import io.github.tuscani712.lanyard.core.PushResult
import io.github.tuscani712.lanyard.core.PushSession
import io.github.tuscani712.lanyard.core.PushSource
import io.github.tuscani712.lanyard.core.RateThrottle
import io.github.tuscani712.lanyard.core.ShareValidation
import io.github.tuscani712.lanyard.core.SpoolEntry
import io.github.tuscani712.lanyard.core.Throttle
import io.github.tuscani712.lanyard.core.TransferPolicy
import io.github.tuscani712.lanyard.SettingsHolder
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
    val peerFingerprint: String = "",
    val label: String,
    val total: Long,
    val done: Long,
    val state: TransferState,
    val message: String? = null,
    val speed: Double = 0.0, // bytes per second, smoothed
    val startedAt: Long,
)

/**
 * The single owner of transfers. Both the send and receive paths run here on
 * [Dispatchers.IO]; the Transfers screen observes [state], and [TransferService]
 * mirrors it into a notification. The last 100 records persist to app-private
 * storage.
 */
object TransferManager {
    private const val HISTORY_CAP = 100

    private lateinit var app: Application
    private var meter: MeteredNetwork = MeteredNetwork { false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson: Gson = GsonBuilder().create()
    private val historyType = object : TypeToken<MutableList<TransferRecord>>() {}.type

    private val _state = MutableStateFlow<List<TransferRecord>>(emptyList())
    val state: StateFlow<List<TransferRecord>> = _state.asStateFlow()

    private val cancels = HashMap<String, AtomicBoolean>()
    private val speedSamples = java.util.concurrent.ConcurrentHashMap<String, SpeedSample>()
    private val cleanups = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    fun init(application: Application, meteredNetwork: MeteredNetwork = MeteredNetwork { false }) {
        app = application
        meter = meteredNetwork
        _state.value = loadHistory()
        sweepSpool()
    }

    /** The current Wi-Fi-only refusal, or null when a transfer may start. */
    fun refusal(): String? =
        TransferPolicy.wifiOnlyRefusal(SettingsHolder.settings.value.wifiOnly, meter.isMetered())

    private fun throttle(): Throttle = RateThrottle.fromMBps(SettingsHolder.settings.value.bandwidthLimitMBps)

    /** Deletes share-spool files left behind by a crash, on app start. */
    private fun sweepSpool() {
        scope.launch {
            val dir = File(app.cacheDir, "share")
            val entries = dir.listFiles()?.map { SpoolEntry(it.name, it.lastModified()) } ?: return@launch
            ShareValidation.staleSpoolFiles(entries, System.currentTimeMillis())
                .forEach { File(dir, it).delete() }
        }
    }

    fun running(): List<TransferRecord> = _state.value.filter { it.state == TransferState.Running || it.state == TransferState.Queued }

    fun cancel(id: String) {
        cancels[id]?.set(true)
    }

    fun cancelAllRunning() {
        cancels.values.forEach { it.set(true) }
    }

    /** Cancels every running or queued transfer to the peer with [fingerprint]. */
    fun cancelForPeer(fingerprint: String) {
        _state.value
            .filter { it.peerFingerprint.equals(fingerprint, ignoreCase = true) }
            .filter { it.state == TransferState.Running || it.state == TransferState.Queued }
            .forEach { cancels[it.id]?.set(true) }
    }

    fun clearFinished() {
        _state.value = _state.value.filter { it.state == TransferState.Running || it.state == TransferState.Queued }
        persist()
    }

    // --- destination folder (SAF) ---

    fun rememberedTree(): Uri? = SettingsHolder.settings.value.downloadFolder?.let(Uri::parse)

    fun rememberTree(uri: Uri) {
        SettingsHolder.update { it.copy(downloadFolder = uri.toString()) }
    }

    fun clearTree() {
        SettingsHolder.update { it.copy(downloadFolder = null) }
    }

    // --- enqueue ---

    fun enqueuePush(peer: PairedPeer, sources: List<PushSource>, label: String, onFinished: (() -> Unit)? = null): String {
        val id = newId()
        val blocked = refusal()
        if (blocked != null) {
            add(TransferRecord(id, "send", peer.name, peer.fingerprint, label, sources.sumOf { it.size }, 0, TransferState.Failed, blocked, 0.0, now()))
            onFinished?.invoke()
            return id
        }
        if (onFinished != null) cleanups[id] = onFinished
        add(TransferRecord(id, "send", peer.name, peer.fingerprint, label, sources.sumOf { it.size }, 0, TransferState.Queued, null, 0.0, now()))
        TransferService.start(app)
        val throttle = throttle()
        scope.launch { runPush(id, peer, sources, throttle) }
        return id
    }

    fun enqueueDownload(peer: PairedPeer, shareId: String, path: String, label: String, tree: Uri): String {
        val id = newId()
        val blocked = refusal()
        if (blocked != null) {
            add(TransferRecord(id, "receive", peer.name, peer.fingerprint, label, 0, 0, TransferState.Failed, blocked, 0.0, now()))
            return id
        }
        add(TransferRecord(id, "receive", peer.name, peer.fingerprint, label, 0, 0, TransferState.Queued, null, 0.0, now()))
        TransferService.start(app)
        val throttle = throttle()
        scope.launch { runDownload(id, peer, shareId, path, tree, throttle) }
        return id
    }

    /**
     * Sends a short text snippet. It shows as a transfer row so it is visible and
     * its completion notification follows the same setting as files.
     */
    fun enqueueSnippet(peer: PairedPeer, text: String): String {
        val id = newId()
        val size = text.toByteArray(Charsets.UTF_8).size.toLong()
        val blocked = refusal()
        if (blocked != null) {
            add(TransferRecord(id, "send", peer.name, peer.fingerprint, "Text", size, 0, TransferState.Failed, blocked, 0.0, now()))
            return id
        }
        add(TransferRecord(id, "send", peer.name, peer.fingerprint, "Text", size, 0, TransferState.Queued, null, 0.0, now()))
        scope.launch { runSnippet(id, peer, text) }
        return id
    }

    private suspend fun runSnippet(id: String, peer: PairedPeer, text: String) {
        val identity = IdentityHolder.identity ?: return fail(id, "no identity on this device")
        setState(id, TransferState.Running)
        val failure = try {
            PeerClient(peer.host, peer.port, identity, peer.fingerprint).sendSnippet(text)
            null
        } catch (e: Exception) {
            e.message ?: "could not send text"
        }
        if (failure == null) complete(id, "Text sent") else fail(id, failure)
    }

    private suspend fun runPush(id: String, peer: PairedPeer, sources: List<PushSource>, throttle: Throttle) {
        try {
            val identity = IdentityHolder.identity ?: return fail(id, "no identity on this device")
            val cancel = AtomicBoolean(false)
            cancels[id] = cancel
            setState(id, TransferState.Running)
            val sent = LongArray(sources.size)
            val result = try {
                PushSession(PeerClient(peer.host, peer.port, identity, peer.fingerprint), throttle).push(
                    sources = sources,
                    onProgress = { index, bytes, _ ->
                        sent[index] = bytes
                        val done = sent.sum()
                        update(id) { it.copy(done = done, total = maxOf(it.total, done), speed = sampleSpeed(id, done)) }
                    },
                    isCancelled = { cancel.get() },
                )
            } catch (e: Exception) {
                PushResult.Failed(e.message ?: "send failed")
            }
            cancels.remove(id)
            finish(id, result)
        } finally {
            cleanups.remove(id)?.invoke()
        }
    }

    private suspend fun runDownload(id: String, peer: PairedPeer, shareId: String, path: String, tree: Uri, throttle: Throttle) {
        val identity = IdentityHolder.identity ?: return fail(id, "no identity on this device")
        val root = DocumentFile.fromTreeUri(app, tree) ?: return fail(id, "cannot open the destination folder")
        val cancel = AtomicBoolean(false)
        cancels[id] = cancel
        setState(id, TransferState.Running)
        val sent = HashMap<Int, Long>()
        val result = try {
            DownloadSession(PeerClient(peer.host, peer.port, identity, peer.fingerprint), throttle).download(
                shareId = shareId,
                path = path,
                targetFor = { file ->
                    docTarget(root, file.path.ifEmpty { file.name })
                        ?: throw IllegalStateException("cannot create ${file.name}")
                },
                onProgress = { index, file, received, total ->
                    sent[index] = received
                    val done = sent.values.sum()
                    update(id) { it.copy(done = done, total = maxOf(it.total, total, done), speed = sampleSpeed(id, done)) }
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
        update(id) { it.copy(state = state, message = message, speed = 0.0, done = if (state == TransferState.Done) it.total else it.done) }
        persist()
        speedSamples.remove(id)
        if (state == TransferState.Done) notifyCompletion(id)
    }

    private fun fail(id: String, message: String) {
        update(id) { it.copy(state = TransferState.Failed, message = message, speed = 0.0) }
        persist()
        speedSamples.remove(id)
        notifyCompletion(id)
    }

    /** Posts a finished/failed notification, honoring the user's setting. */
    private fun notifyCompletion(id: String) {
        val record = _state.value.firstOrNull { it.id == id } ?: return
        runCatching { TransferNotifications.completion(app, record) }
    }

    private fun sampleSpeed(id: String, done: Long): Double {
        val at = now()
        val sample = speedSamples.getOrPut(id) { SpeedSample(done, at, 0.0) }
        val dt = (at - sample.at) / 1000.0
        if (dt >= 0.4) {
            val instant = (done - sample.bytes).coerceAtLeast(0) / dt
            sample.ema = if (sample.ema <= 0.0) instant else sample.ema * 0.6 + instant * 0.4
            sample.bytes = done
            sample.at = at
        }
        return sample.ema
    }

    private class SpeedSample(var bytes: Long, var at: Long, var ema: Double)

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
