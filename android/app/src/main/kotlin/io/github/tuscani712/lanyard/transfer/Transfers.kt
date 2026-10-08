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
import io.github.tuscani712.lanyard.core.TransferBoard
import io.github.tuscani712.lanyard.core.TransferPolicy
import io.github.tuscani712.lanyard.core.TransferRecord
import io.github.tuscani712.lanyard.core.TransferState
import io.github.tuscani712.lanyard.SettingsHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single owner of transfers. Both the send and receive paths run here on
 * [Dispatchers.IO]; the Transfers screen observes [state], and [TransferService]
 * mirrors it into a notification. The last 100 records persist to app-private
 * storage.
 *
 * All row state transitions go through [TransferBoard] (pure, in :core), so the
 * cancel and no-progress-aging rules are testable without Android. This object
 * adds the side effects: stopping the network work, deleting the send spool, and
 * cancelling a live receive on the peer server.
 */
object TransferManager {
    private const val HISTORY_CAP = 100

    /** A running row with no progress for this long is failed as "No progress". */
    const val STALLED_AFTER_MS = 10 * 60_000L

    /** How often the aging sweep runs. */
    private const val AGE_SWEEP_MS = 60_000L

    private lateinit var app: Application
    private var meter: MeteredNetwork = MeteredNetwork { false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson: Gson = GsonBuilder().create()
    private val historyType = object : TypeToken<MutableList<TransferRecord>>() {}.type

    private val board = TransferBoard(stalledAfterMillis = STALLED_AFTER_MS)

    private val _state = MutableStateFlow<List<TransferRecord>>(emptyList())
    val state: StateFlow<List<TransferRecord>> = _state.asStateFlow()

    private val cancels = HashMap<String, AtomicBoolean>()
    private val pushReceives = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val speedSamples = java.util.concurrent.ConcurrentHashMap<String, SpeedSample>()
    private val cleanups = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    /**
     * Cancels a live receive on the peer server (deletes its `.lanpart` spool and
     * clears the stale-session guard). Wired by [io.github.tuscani712.lanyard.PeerService],
     * which owns the [io.github.tuscani712.lanyard.core.InboxReceiver].
     */
    @Volatile
    private var receiveCanceller: ((String) -> Unit)? = null

    fun onReceiveCancel(action: (String) -> Unit) {
        receiveCanceller = action
    }

    fun init(application: Application, meteredNetwork: MeteredNetwork = MeteredNetwork { false }) {
        app = application
        meter = meteredNetwork
        board.replaceAll(loadHistory())
        board.failInterrupted("Interrupted")
        publish()
        sweepSpool()
        scope.launch {
            while (isActive) {
                delay(AGE_SWEEP_MS)
                ageNow()
            }
        }
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

    fun running(): List<TransferRecord> = board.running()

    /**
     * Cancels a row. Always ends a live row as Cancelled and frees everything it
     * held: the send spool (via the enqueue cleanup), a live push receive on the
     * peer server (spool + stale-session guard), and the cancel flag. A row that
     * already finished is left alone.
     */
    fun cancel(id: String) {
        cancels[id]?.set(true)
        val row = board.cancel(id) ?: run { cancels.remove(id); return }
        freeFor(row)
        publish()
        persist()
    }

    /** Stops live work and releases resources for a row that just ended. */
    private fun freeFor(row: TransferRecord) {
        if (pushReceives.remove(row.id)) receiveCanceller?.invoke(row.id)
        cleanups.remove(row.id)?.invoke()
        cancels.remove(row.id)
        speedSamples.remove(row.id)
    }

    fun cancelAllRunning() {
        board.running().map { it.id }.forEach { cancel(it) }
    }

    /** Cancels every running or queued transfer to the peer with [fingerprint]. */
    fun cancelForPeer(fingerprint: String) {
        board.running()
            .filter { it.peerFingerprint.equals(fingerprint, ignoreCase = true) }
            .forEach { cancel(it.id) }
    }

    fun clearFinished() {
        board.clearFinished()
        publish()
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

    // --- received pushes (this phone is the receiver) ---
    // A push from a peer is recorded here so it shows on the Transfers screen
    // like any other transfer. A receive left Running when the app restarts is
    // turned into a Failed "Interrupted" row by loadHistory().

    fun noteReceiveStarted(id: String, peerName: String, peerFp: String, label: String, total: Long) {
        if (board.firstOrNull(id) != null) return
        pushReceives.add(id)
        add(TransferRecord(id, "receive", peerName, peerFp, label, total, 0, TransferState.Running, null, 0.0, now()))
        // An incoming push has no enqueue* call, so start the foreground service
        // here too: the ongoing notification mirrors its progress and keeps it
        // alive; the service stops itself once nothing is running or queued.
        TransferService.start(app)
    }

    fun noteReceiveProgress(id: String, done: Long, total: Long) {
        if (!board.isLive(id)) return
        board.progress(id, done, total, sampleSpeed(id, done))
        publish()
    }

    fun noteReceiveDone(id: String, message: String) {
        pushReceives.remove(id)
        if (!board.isLive(id)) return
        end(id, TransferState.Done, message)
    }

    /** Marks a push this phone abandoned (declined or cut off) as failed. */
    fun noteReceiveFailed(id: String, reason: String) {
        pushReceives.remove(id)
        if (!board.isLive(id)) return
        end(id, TransferState.Failed, reason)
    }

    /**
     * Fails every push still being received. Called when the peer server stops
     * (the app was backgrounded): a push cannot continue without it, so the row
     * should not sit at Running until the next restart. Pull downloads are not
     * touched, since those keep running in the background.
     */
    fun failPushReceives(reason: String) {
        for (id in pushReceives.toList()) {
            if (board.isLive(id)) end(id, TransferState.Failed, reason)
        }
        pushReceives.clear()
    }

    /** Removes a finished (Done/Failed/Cancelled) row from the list. */
    fun dismiss(id: String) {
        pushReceives.remove(id)
        board.dismiss(id)
        publish()
        persist()
    }

    /**
     * Fails Running rows with no progress for [STALLED_AFTER_MS]. This is what
     * unsticks a row whose connection died: it ends as Failed("No progress") and
     * its live work is stopped. Runs on a timer ([AGE_SWEEP_MS]); exposed for
     * tests.
     */
    fun ageNow() {
        val aged = board.age()
        if (aged.isEmpty()) return
        for (row in aged) {
            freeFor(row)
            notifyCompletion(row.id)
        }
        publish()
        persist()
    }

    private suspend fun runSnippet(id: String, peer: PairedPeer, text: String) {
        val identity = IdentityHolder.identity ?: return end(id, TransferState.Failed, "no identity on this device")
        setState(id, TransferState.Running)
        val failure = try {
            PeerClient(peer.host, peer.port, identity, peer.fingerprint).sendSnippet(text)
            null
        } catch (e: Exception) {
            e.message ?: "could not send text"
        }
        if (failure == null) end(id, TransferState.Done, "Text sent") else end(id, TransferState.Failed, failure)
    }

    private suspend fun runPush(id: String, peer: PairedPeer, sources: List<PushSource>, throttle: Throttle) {
        try {
            val identity = IdentityHolder.identity ?: return end(id, TransferState.Failed, "no identity on this device")
            val cancel = AtomicBoolean(false)
            cancels[id] = cancel
            if (!board.isLive(id)) { cancels.remove(id); return } // cancelled before it started
            setState(id, TransferState.Running)
            val sent = LongArray(sources.size)
            val result = try {
                PushSession(PeerClient(peer.host, peer.port, identity, peer.fingerprint), throttle).push(
                    sources = sources,
                    onProgress = { index, bytes, _ ->
                        sent[index] = bytes
                        val done = sent.sum()
                        if (board.isLive(id)) {
                            board.progress(id, done, maxOf(board.firstOrNull(id)?.total ?: 0, done), sampleSpeed(id, done))
                            publish()
                        }
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
        val identity = IdentityHolder.identity ?: return end(id, TransferState.Failed, "no identity on this device")
        val root = DocumentFile.fromTreeUri(app, tree) ?: return end(id, TransferState.Failed, "cannot open the destination folder")
        val cancel = AtomicBoolean(false)
        cancels[id] = cancel
        if (!board.isLive(id)) { cancels.remove(id); return }
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
                    if (board.isLive(id)) {
                        board.progress(id, done, maxOf(total, done), sampleSpeed(id, done))
                        publish()
                    }
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
        board.add(record)
        publish()
        persist()
    }

    private fun setState(id: String, state: TransferState) {
        board.update(id) { it.copy(state = state) }
        publish()
    }

    private fun publish() {
        _state.value = board.snapshot()
    }

    private fun finish(id: String, result: PushResult) {
        when (result) {
            is PushResult.Sent -> end(id, TransferState.Done, "Sent ${result.files} file(s)")
            PushResult.Cancelled -> end(id, TransferState.Cancelled, "Cancelled")
            PushResult.CancelledByReceiver -> end(id, TransferState.Failed, "The other device cancelled")
            PushResult.Refused -> end(id, TransferState.Failed, "The other device is not accepting files")
            is PushResult.Failed -> end(id, TransferState.Failed, result.message)
        }
    }

    private fun finishDownload(id: String, result: DownloadResult) {
        when (result) {
            is DownloadResult.Done -> end(id, TransferState.Done, "Received ${result.files} file(s)")
            DownloadResult.Cancelled -> end(id, TransferState.Cancelled, "Cancelled")
            DownloadResult.ShareEnded -> end(id, TransferState.Failed, "The sender stopped this share")
            DownloadResult.PeerUnreachable -> end(id, TransferState.Failed, "The other device is unreachable")
            is DownloadResult.HashMismatch -> end(id, TransferState.Failed, "A file failed its checksum")
            DownloadResult.UnsafePath -> end(id, TransferState.Failed, "The share contained an unsafe path")
            is DownloadResult.Failed -> end(id, TransferState.Failed, result.message)
        }
    }

    /** Ends a live row; a row already ended (e.g. by Cancel) is left alone. */
    private fun end(id: String, state: TransferState, message: String) {
        if (board.end(id, state, message) == null) return
        pushReceives.remove(id)
        cleanups.remove(id)?.invoke()
        cancels.remove(id)
        speedSamples.remove(id)
        publish()
        persist()
        if (state == TransferState.Done) notifyCompletion(id)
    }

    /** Posts a finished/failed notification, honoring the user's setting. */
    private fun notifyCompletion(id: String) {
        val record = board.firstOrNull(id) ?: return
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
        else gson.fromJson<MutableList<TransferRecord>>(f.readText(), historyType) ?: mutableListOf()
    } catch (_: Exception) {
        emptyList()
    }

    private fun persist() {
        runCatching {
            historyFile().writeText(gson.toJson(board.snapshot().take(HISTORY_CAP), historyType))
        }
    }
}
