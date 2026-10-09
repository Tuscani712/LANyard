package io.github.tuscani712.lanyard.transfer

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.github.tuscani712.lanyard.IdentityHolder
import io.github.tuscani712.lanyard.core.Display
import io.github.tuscani712.lanyard.core.DownloadResult
import io.github.tuscani712.lanyard.core.DownloadSession
import io.github.tuscani712.lanyard.core.DownloadTarget
import io.github.tuscani712.lanyard.core.ForegroundTransferPolicy
import io.github.tuscani712.lanyard.core.InterruptedTransfer
import io.github.tuscani712.lanyard.core.InterruptedTransferRetry
import io.github.tuscani712.lanyard.core.InterruptedTransferStore
import io.github.tuscani712.lanyard.core.JsonFileTransferHistoryStore
import io.github.tuscani712.lanyard.core.JsonFileInterruptedTransferStore
import io.github.tuscani712.lanyard.core.MeteredNetwork
import io.github.tuscani712.lanyard.core.PairedPeer
import io.github.tuscani712.lanyard.core.PeerClient
import io.github.tuscani712.lanyard.core.PeerErrors
import io.github.tuscani712.lanyard.core.PushResult
import io.github.tuscani712.lanyard.core.PushSession
import io.github.tuscani712.lanyard.core.PushSource
import io.github.tuscani712.lanyard.core.RateEtaDisplay
import io.github.tuscani712.lanyard.core.RateThrottle
import io.github.tuscani712.lanyard.core.ShareValidation
import io.github.tuscani712.lanyard.core.SpoolEntry
import io.github.tuscani712.lanyard.core.Throttle
import io.github.tuscani712.lanyard.core.TransferBoard
import io.github.tuscani712.lanyard.core.TransferHistoryStore
import io.github.tuscani712.lanyard.core.TransferPolicy
import io.github.tuscani712.lanyard.core.TransferRecord
import io.github.tuscani712.lanyard.core.TransferState
import io.github.tuscani712.lanyard.core.TransferTuning
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
    /** How many rows the in-memory list and the persisted history keep. */
    private const val HISTORY_CAP = TransferBoard.HISTORY_CAP

    /** A running row with no progress for this long is failed as "No progress". */
    const val STALLED_AFTER_MS = 10 * 60_000L

    /** How often the aging sweep runs. */
    private const val AGE_SWEEP_MS = 60_000L

    private lateinit var app: Application
    private var initialized = false
    private var meter: MeteredNetwork = MeteredNetwork { false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The persisted history. Wired on [init]; every list change is mirrored here,
     * so a finished row survives an app restart.
     */
    private var history: TransferHistoryStore? = null

    private val board = TransferBoard(historyCap = HISTORY_CAP, stalledAfterMillis = STALLED_AFTER_MS)

    private val _state = MutableStateFlow<List<TransferRecord>>(emptyList())
    val state: StateFlow<List<TransferRecord>> = _state.asStateFlow()

    private val cancels = HashMap<String, AtomicBoolean>()
    private val pushReceives = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val rateDisplays = java.util.concurrent.ConcurrentHashMap<String, RateEtaDisplay>()

    /**
     * The persisted descriptors for transfers that stopped early. They survive a
     * process death so the row can still be read ("Interrupted – will resume")
     * and a pull can be rebuilt and continued from its `.part`.
     */
    private var interrupted: InterruptedTransferStore? = null
    private var interruptedRetry: InterruptedTransferRetry? = null

    /**
     * In-process resume handles. A push (send) holds its [PushSource]s here; a
     * download holds its share id/path/tree. A network change or a backgrounded
     * app keeps this map, so the transfer can be re-run without the person
     * re-selecting anything. Wired in [init].
     */
    private val resumables = java.util.concurrent.ConcurrentHashMap<String, Resumable>()

    /** Rebuilds the live address of a paired peer by fingerprint (wired by PeerService). */
    @Volatile
    private var peerResolver: ((String) -> PairedPeer?)? = null

    /** One resumable transfer's rebuild recipe, kept only while it is live. */
    private class Resumable(
        val peer: PairedPeer,
        val kind: String, // "send" | "download"
        val sources: List<PushSource> = emptyList(),
        val shareId: String = "",
        val path: String = "",
        val tree: Uri? = null,
    )

    /** When the last live progress update was published, for the ~1/s gate. */
    private var lastProgressPublishAt = 0L
    private val cleanups = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    /**
     * Where one-line transfer events go: the phone's [ServerDiagnostics] ring
     * buffer. Wired by [io.github.tuscani712.lanyard.PeerService]. The finish
     * log (average speed) is written here.
     */
    @Volatile
    private var diagnostic: ((String) -> Unit)? = null

    fun onDiagnostic(sink: (String) -> Unit) {
        diagnostic = sink
    }

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
        // Guard against a second Activity re-seeding the board from disk and
        // wiping rows this process already holds (e.g. one that just finished).
        if (initialized) return
        initialized = true
        app = application
        meter = meteredNetwork
        history = JsonFileTransferHistoryStore(File(application.filesDir, "transfers.json"), cap = HISTORY_CAP)
        interrupted = JsonFileInterruptedTransferStore(File(application.filesDir, "transfers-interrupted.json"))
        interruptedRetry = InterruptedTransferRetry(interrupted!!)
        board.replaceAll(history?.load() ?: emptyList())
        // A row left Running/Queued has no live work after a restart: mark it
        // "Interrupted – will resume" and remember the descriptor so the partial
        // can be continued when the peer is reachable again.
        board.failInterrupted().forEach { rememberInterrupted(it) }
        publish()
        persist()
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
        rateDisplays.remove(row.id)
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

    // --- interrupted / resume ---

    /** Wires the paired-peer lookup used to re-run a persisted download. */
    fun onPeerResolver(resolve: (String) -> PairedPeer?) {
        peerResolver = resolve
    }

    /**
     * The app is back in the foreground: any interrupted transfer whose peer is
     * reachable may resume now. Called from `PeerService.start`.
     */
    fun onAppForeground() {
        resumeInterrupted(null)
    }

    /**
     * A peer was seen (a hello, an inbound handshake, an mDNS sighting): resume
     * any interrupted transfer to it immediately, without waiting for the timer.
     */
    fun onPeerReachable(shortId: String? = null) {
        resumeInterrupted(shortId)
    }

    /**
     * Re-runs every interrupted transfer named by [shortId] (or all of them when
     * null), from its partial where one exists. A row is only reset to Queued
     * once the work is actually re-enqueued, so an unreachable peer simply
     * leaves the row marked for a later attempt.
     */
    fun resumeInterrupted(shortId: String? = null) {
        val candidates = interruptedRetry?.onPeerReachable(shortId) ?: emptyList()
        for (entry in candidates) {
            val row = board.firstOrNull(entry.id)
            if (row == null) {
                interrupted?.remove(entry.id)
                continue
            }
            if (row.state != TransferState.Failed || row.message != ForegroundTransferPolicy.INTERRUPTED_MESSAGE) {
                // The row was dismissed or ended another way: drop the stale descriptor.
                if (row.state != TransferState.Failed) interrupted?.remove(entry.id)
                continue
            }
            if (resumeOne(entry)) interrupted?.remove(entry.id)
        }
    }

    /** Rebuilds and relaunches one interrupted transfer; true when it was started. */
    private fun resumeOne(entry: InterruptedTransfer): Boolean {
        val live = resumables[entry.id]
        // Decide the recipe before touching the board: a receive with no stored
        // action (a push the receiver cannot restart by itself) must stay marked
        // for resume, not be flipped to Queued with nothing driving it.
        val download = live?.kind == "download" || (live == null && entry.direction == "receive" && entry.payload.startsWith("download"))
        val share = if (download) {
            when {
                live?.tree != null -> Triple(live.shareId, live.path, live.tree)
                else -> parseDownloadPayload(entry.payload) ?: return false
            }
        } else {
            null
        }
        if (live?.kind != "send" && !download) return false
        val peer = live?.peer ?: peerResolver?.invoke(entry.peerFingerprint) ?: return false

        // Reset the row so the service shows it as live again and Cancel works.
        board.update(entry.id) {
            it.copy(
                state = TransferState.Queued,
                message = null,
                speed = 0.0,
                etaSeconds = null,
                finishingBytes = null,
                done = if (download) it.done else 0L,
            )
        }
        publish()
        persist()
        TransferService.start(app)
        val throttle = throttle()
        when {
            live?.kind == "send" -> scope.launch { runPush(entry.id, peer, live.sources, throttle) }
            download && share != null ->
                scope.launch { runDownload(entry.id, peer, share.first, share.second, share.third, throttle, resume = true) }
            else -> return false
        }
        return true
    }

    /** Records [row] as interrupted so the descriptor survives a restart. */
    private fun rememberInterrupted(row: TransferRecord) {
        val live = resumables[row.id]
        val existing = interrupted?.list()?.firstOrNull { it.id == row.id }?.payload.orEmpty()
        val payload = when {
            live?.kind == "download" && live.tree != null -> downloadPayload(live.shareId, live.path, live.tree)
            else -> existing
        }
        interrupted?.upsert(
            InterruptedTransfer(
                id = row.id,
                direction = row.direction,
                peerFingerprint = row.peerFingerprint,
                peerName = row.peerName,
                label = row.label,
                total = row.total,
                done = row.done,
                queuedAt = now(),
                payload = payload,
            ),
        )
    }

    /**
     * Marks a live row interrupted: it is `Failed` for the UI but carries the
     * "will resume" text and keeps its `.part`/`.lanpart`, and its descriptor is
     * persisted for the automatic resume.
     */
    private fun markInterrupted(id: String) {
        val row = board.end(id, TransferState.Failed, ForegroundTransferPolicy.INTERRUPTED_MESSAGE) ?: return
        freeFor(row)
        rememberInterrupted(board.firstOrNull(id) ?: row)
        publish()
        persist()
    }

    private fun downloadPayload(shareId: String, path: String, tree: Uri): String =
        "download\n$shareId\n$path\n$tree"

    private fun parseDownloadPayload(payload: String): Triple<String, String, Uri>? {
        val parts = payload.split('\n')
        if (parts.size < 4 || parts[0] != "download") return null
        return Triple(parts[1], parts[2], Uri.parse(parts[3]))
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
        resumables[id] = Resumable(peer, "send", sources = sources)
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
        resumables[id] = Resumable(peer, "download", shareId = shareId, path = path, tree = tree)
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
        // A fresh push from this peer supersedes any row still waiting to resume
        // for it: the peer is retrying, so the old interrupted row is stale. Its
        // `.lanpart` is shared by peer+path, so the new session resumes from it.
        board.snapshot()
            .filter {
                it.direction == "receive" &&
                    it.message == ForegroundTransferPolicy.INTERRUPTED_MESSAGE &&
                    it.peerFingerprint.equals(peerFp, ignoreCase = true)
            }
            .forEach {
                board.dismiss(it.id)
                interrupted?.remove(it.id)
            }
        pushReceives.add(id)
        add(TransferRecord(id, "receive", peerName, peerFp, label, total, 0, TransferState.Running, null, 0.0, now()))
        // An incoming push has no enqueue* call, so start the foreground service
        // here too: the ongoing notification mirrors its progress and keeps it
        // alive; the service stops itself once nothing is running or queued.
        TransferService.start(app)
    }

    fun noteReceiveProgress(id: String, done: Long, total: Long) {
        if (!board.isLive(id)) return
        val snap = sampleRate(id, done, total)
        board.progress(id, done, total, snap.bytesPerSecond, snap.etaSeconds)
        publishProgress()
    }

    /**
     * Enters the "Finishing…" window for a live row: every byte has arrived
     * (receive) or been written (send), and the row is now hashing, copying the
     * spool into the destination, or waiting for the receiver's confirmation.
     * [bytes] is the size being finalized. Published immediately (not throttled)
     * so the state appears as soon as the last byte lands, and cleared by the
     * next [noteReceiveProgress] byte or when the row ends.
     */
    fun noteFinishing(id: String, bytes: Long) {
        if (board.markFinishing(id, bytes) == null) return
        publish()
    }

    fun noteReceiveDone(id: String, message: String, folder: String? = null, folderUri: String? = null) {
        pushReceives.remove(id)
        if (!board.isLive(id)) return
        end(id, TransferState.Done, message, folder, folderUri)
    }

    /** Marks a push this phone abandoned (declined or cut off) as failed. */
    fun noteReceiveFailed(id: String, reason: String) {
        pushReceives.remove(id)
        if (!board.isLive(id)) return
        end(id, TransferState.Failed, reason)
    }

    /**
     * Marks every push still being received as interrupted. Called when the peer
     * server stops (the app was backgrounded): a push cannot continue without
     * it, so the row must not sit at Running until the next restart. The
     * `.lanpart` spool is deliberately kept, so a re-offer from the sender
     * resumes from the partial. Pull downloads are not touched, since those keep
     * running in the background.
     */
    fun failPushReceives(reason: String) {
        for (id in pushReceives.toList()) {
            if (board.isLive(id)) {
                board.end(id, TransferState.Failed, ForegroundTransferPolicy.INTERRUPTED_MESSAGE)?.let { freeFor(it) }
                board.firstOrNull(id)?.let { rememberInterrupted(it) }
                publish()
                persist()
            }
        }
        pushReceives.clear()
    }

    /** Removes a finished (Done/Failed/Cancelled) row from the list. */
    fun dismiss(id: String) {
        pushReceives.remove(id)
        resumables.remove(id)
        interrupted?.remove(id)
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
            PeerErrors.userMessage(e)
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
            val client = PeerClient(peer.host, peer.port, identity, peer.fingerprint)
            // Learn the receiver's advertised offer caps so a large send is split
            // into batches the peer can accept. A hello failure is not fatal: the
            // offer itself reports reachability, and PushBatching falls back to
            // this phone's own conservative limits.
            val hello = runCatching { client.hello() }.getOrNull()
            val result = try {
                PushSession(
                    client,
                    throttle,
                    maxOfferBytes = hello?.maxOfferBytes ?: 0,
                    maxOfferFiles = hello?.maxOfferFiles ?: 0,
                ).push(
                    sources = sources,
                    onProgress = { index, bytes, _ ->
                        sent[index] = bytes
                        val done = sent.sum()
                        if (board.isLive(id)) {
                            val total = maxOf(board.firstOrNull(id)?.total ?: 0, done)
                            val snap = sampleRate(id, done, total)
                            board.progress(id, done, total, snap.bytesPerSecond, snap.etaSeconds)
                            publishProgress()
                        }
                    },
                    onFinishing = { _, bytes -> noteFinishing(id, bytes) },
                    isCancelled = { cancel.get() },
                )
            } catch (e: Exception) {
                PushResult.Failed(PeerErrors.userMessage(e))
            }
            cancels.remove(id)
            finish(id, result)
        } finally {
            cleanups.remove(id)?.invoke()
        }
    }

    private suspend fun runDownload(
        id: String,
        peer: PairedPeer,
        shareId: String,
        path: String,
        tree: Uri,
        throttle: Throttle,
        resume: Boolean = false,
    ) {
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
                    docTarget(root, file.path.ifEmpty { file.name }, resume)
                        ?: throw IllegalStateException("cannot create ${file.name}")
                },
                onProgress = { index, file, received, total ->
                    sent[index] = received
                    val done = sent.values.sum()
                    if (board.isLive(id)) {
                        val bounded = maxOf(total, done)
                        val snap = sampleRate(id, done, bounded)
                        board.progress(id, done, bounded, snap.bytesPerSecond, snap.etaSeconds)
                        publishProgress()
                    }
                },
                isCancelled = { cancel.get() },
            )
        } catch (e: Exception) {
            DownloadResult.Failed(PeerErrors.userMessage(e))
        }
        cancels.remove(id)
        finishDownload(id, result, DocumentFile.fromTreeUri(app, tree)?.name, tree.toString())
    }

    /**
     * The destination for one downloaded file. The `.part` name is reused, not
     * re-created, so a resume continues the file already on disk: [resume] reads
     * its present length as the starting offset and [DownloadTarget.openAt]
     * appends past it. A fresh download truncates the `.part`. Only a fully
     * verified file is renamed to its real name.
     */
    private fun docTarget(root: DocumentFile, rel: String, resume: Boolean): DownloadTarget? {
        val dir = ensureDirs(root, rel.substringBeforeLast('/', "")) ?: return null
        val name = rel.substringAfterLast('/').ifEmpty { "download" }
        val part = dir.findFile("$name.part") ?: dir.createFile("application/octet-stream", "$name.part") ?: return null
        return DownloadTarget(
            existingSize = if (resume) part.length() else 0L,
            openAt = { offset -> app.contentResolver.openOutputStream(part.uri, if (offset > 0) "wa" else "wt") ?: error("cannot open output") },
            openExisting = { app.contentResolver.openInputStream(part.uri) ?: error("cannot read output") },
            commit = {
                val finalName = if (dir.findFile(name) == null) name else uniqueName(dir, name)
                if (part.name != finalName && !part.renameTo(finalName)) {
                    throw java.io.IOException("could not finalize the download")
                }
            },
            discard = { runCatching { part.delete() } },
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

    /**
     * Publishes a live progress update at most once per
     * [TransferTuning.DISPLAY_REFRESH_MS]. The board is still updated on every
     * callback (sampling is unchanged); only the UI/notification emission is
     * throttled, so the screen redraws about once a second instead of ~20.
     */
    @Synchronized
    private fun publishProgress() {
        val t = now()
        if (t - lastProgressPublishAt < TransferTuning.DISPLAY_REFRESH_MS) return
        lastProgressPublishAt = t
        publish()
    }

    private fun finish(id: String, result: PushResult) {
        when (result) {
            is PushResult.Sent -> end(id, TransferState.Done, "Sent ${result.files} file(s)")
            PushResult.Cancelled -> end(id, TransferState.Cancelled, "Cancelled")
            PushResult.CancelledByReceiver -> end(id, TransferState.Failed, "The other device cancelled")
            PushResult.Refused -> end(id, TransferState.Failed, "The other device is not accepting files")
            is PushResult.Failed -> if (isTransportFailure(result.message)) markInterrupted(id) else end(id, TransferState.Failed, result.message)
        }
    }

    private fun finishDownload(id: String, result: DownloadResult, folder: String? = null, folderUri: String? = null) {
        when (result) {
            is DownloadResult.Done -> end(id, TransferState.Done, "Received ${result.files} file(s)", folder, folderUri)
            DownloadResult.Cancelled -> end(id, TransferState.Cancelled, "Cancelled")
            DownloadResult.ShareEnded -> end(id, TransferState.Failed, "The sender stopped this share")
            DownloadResult.PeerUnreachable -> markInterrupted(id)
            is DownloadResult.HashMismatch -> end(id, TransferState.Failed, "A file failed its checksum")
            DownloadResult.UnsafePath -> end(id, TransferState.Failed, "The share contained an unsafe path")
            is DownloadResult.Failed -> if (isTransportFailure(result.message)) markInterrupted(id) else end(id, TransferState.Failed, result.message)
        }
    }

    /**
     * Whether a failure message describes a transport loss (the connection
     * dropped, the peer went away, the network changed) rather than a definitive
     * refusal. Only a transport loss is treated as "interrupted – will resume";
     * a checksum mismatch or an unpair is final.
     */
    private fun isTransportFailure(message: String): Boolean {
        val m = message.lowercase()
        return listOf(
            "reach", "connect", "connection", "timeout", "timed out", "reset", "closed",
            "network", "socket", "refused", "unreachable", "stalled", "no route", "host",
            "broken pipe", "eof",
        ).any { m.contains(it) }
    }

    /** Ends a live row; a row already ended (e.g. by Cancel) is left alone. */
    private fun end(
        id: String,
        state: TransferState,
        message: String,
        folder: String? = null,
        folderUri: String? = null,
    ) {
        if (board.end(id, state, message, folder, folderUri) == null) return
        pushReceives.remove(id)
        cleanups.remove(id)?.invoke()
        cancels.remove(id)
        rateDisplays.remove(id)
        resumables.remove(id)
        interrupted?.remove(id)
        publish()
        persist()
        if (state == TransferState.Done) {
            board.firstOrNull(id)?.let { logFinished(it) }
            notifyCompletion(id)
        }
    }

    /**
     * One diagnostics line when a transfer finishes, carrying its average speed
     * (whole-transfer bytes over elapsed time). Never a file name or a full
     * fingerprint.
     */
    private fun logFinished(row: TransferRecord) {
        val sink = diagnostic ?: return
        val elapsed = (now() - row.startedAt).coerceAtLeast(0)
        sink(
            "[transfer] peer=${Display.shortFp(row.peerFingerprint)} direction=${row.direction} " +
                "id=${row.id} bytes=${row.total} avg_bps=${"%.0f".format(row.averageSpeed)} elapsed_ms=$elapsed",
        )
    }

    /** Posts a finished/failed notification, honoring the user's setting. */
    private fun notifyCompletion(id: String) {
        val record = board.firstOrNull(id) ?: return
        runCatching { TransferNotifications.completion(app, record) }
    }

    /**
     * The smoothed live rate and ETA for [id]. A stall followed by a resume
     * yields 0.0 / null, so the row and notification go blank rather than
     * flashing a spike; the ETA always comes from that same smoothed rate.
     */
    private fun sampleRate(id: String, done: Long, total: Long): RateEtaDisplay.Snapshot =
        rateDisplays.getOrPut(id) { RateEtaDisplay() }.sample(now(), done, total)

    private fun newId(): String {
        val buf = ByteArray(6)
        SecureRandom().nextBytes(buf)
        return "t_" + buf.joinToString("") { "%02x".format(it) }
    }

    private fun now(): Long = System.currentTimeMillis()

    // --- persistence ---

    /** Mirrors the whole list to private storage; the newest [HISTORY_CAP] rows. */
    private fun persist() {
        runCatching { history?.save(board.snapshot()) }
    }
}
