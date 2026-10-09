package io.github.tuscani712.lanyard

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.gson.JsonObject
import io.github.tuscani712.lanyard.core.ApprovalOutcome
import io.github.tuscani712.lanyard.core.BackgroundListenerPolicy
import io.github.tuscani712.lanyard.core.DiagLevel
import io.github.tuscani712.lanyard.core.Display
import io.github.tuscani712.lanyard.core.Identity
import io.github.tuscani712.lanyard.core.InboxReceiver
import io.github.tuscani712.lanyard.core.IncomingRequest
import io.github.tuscani712.lanyard.core.JsonFileTrustStore
import io.github.tuscani712.lanyard.core.PairInvites
import io.github.tuscani712.lanyard.core.PairLink
import io.github.tuscani712.lanyard.core.PairingSessions
import io.github.tuscani712.lanyard.core.PairedPeer
import io.github.tuscani712.lanyard.core.PeerClient
import io.github.tuscani712.lanyard.core.PeerServer
import io.github.tuscani712.lanyard.core.PendingUnpair
import io.github.tuscani712.lanyard.core.PendingUnpairRetry
import io.github.tuscani712.lanyard.core.PendingUnpairStore
import io.github.tuscani712.lanyard.core.JsonFilePendingUnpairStore
import io.github.tuscani712.lanyard.core.Permissions
import io.github.tuscani712.lanyard.core.PushApproval
import io.github.tuscani712.lanyard.core.PushDestination
import io.github.tuscani712.lanyard.core.PushProtocol
import io.github.tuscani712.lanyard.core.ReceivedSnippet
import io.github.tuscani712.lanyard.core.ReceivedSnippets
import io.github.tuscani712.lanyard.core.RotatingWriter
import io.github.tuscani712.lanyard.core.ServerDiagnostics
import io.github.tuscani712.lanyard.core.ShareServer
import io.github.tuscani712.lanyard.core.SwitchingDestination
import io.github.tuscani712.lanyard.core.TrustStore
import io.github.tuscani712.lanyard.core.Unpair
import io.github.tuscani712.lanyard.net.AndroidMeteredNetwork
import io.github.tuscani712.lanyard.net.NetAddrs
import io.github.tuscani712.lanyard.net.NsdAdvertiser
import io.github.tuscani712.lanyard.transfer.TransferManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

/** A push from a paired peer waiting for the person to accept or decline. */
data class PushApprovalRequest(
    val id: String,
    val peerName: String,
    val files: Int,
    val total: Long,
    val names: List<String>,
)

/**
 * A peer that just proved it is online (a successful hello either direction, an
 * inbound handshake, or an mDNS sighting). [host]/[port] are absent when the
 * signal came from an inbound handshake, which carries no address.
 */
data class PeerReachable(val shortId: String, val host: String?, val port: Int?)

/**
 * The app-scoped home of the phone-side peer server and the shared trust store.
 *
 * Lifetime: [start] while the app is foregrounded (any screen), [stop] when it is
 * backgrounded. It owns the `/hello` responder, pairing (C1) and the receive
 * side of pushes (C2a), plus the one-time QR invites and the pending prompts.
 */
object PeerService {
    private var initialized = false
    private lateinit var trustStore: TrustStore
    private lateinit var pendingUnpairs: PendingUnpairStore
    private lateinit var unpairRetries: PendingUnpairRetry
    private lateinit var sessionStore: PairingSessions
    private lateinit var inviteStore: PairInvites
    private lateinit var receiver: InboxReceiver
    private lateinit var snippets: ReceivedSnippets
    private lateinit var safDestination: SafInboxDestination
    private lateinit var defaultDestination: PushDestination
    private lateinit var shareStore: ShareStore
    private lateinit var shareSource: SafShareSource
    private lateinit var spoolDir: File
    private var server: PeerServer? = null
    private var shareServer: ShareServer? = null
    private var advertiser: NsdAdvertiser? = null
    private var appVersion: String = ""
    private var currentInvite: PairInvites.Invite? = null
    private var metered: AndroidMeteredNetwork? = null
    private var logWriter: RotatingWriter? = null

    /**
     * How the listener lifetime survives backgrounding while a transfer runs.
     * A dedicated scope observes [TransferManager.state] so a deferred teardown
     * fires as soon as the last transfer drains.
     */
    private val lifetimePolicy = BackgroundListenerPolicy()
    private val lifetimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchingLifetime = false

    private val _pending = MutableStateFlow<List<IncomingRequest>>(emptyList())
    val pending: StateFlow<List<IncomingRequest>> = _pending.asStateFlow()

    private val _approval = MutableStateFlow<PushApprovalRequest?>(null)
    val approval: StateFlow<PushApprovalRequest?> = _approval.asStateFlow()

    private val _interrupted = MutableStateFlow(false)
    val interrupted: StateFlow<Boolean> = _interrupted.asStateFlow()

    private val _shares = MutableStateFlow<List<AppShare>>(emptyList())
    val shares: StateFlow<List<AppShare>> = _shares.asStateFlow()

    /**
     * Text snippets received from paired peers. The phone had no landing spot
     * for inbound text before, so a desktop's `POST /snippet` was answered 404
     * and dropped. Newest first; the Transfers screen shows each with Copy.
     */
    private val _receivedText = MutableStateFlow<List<ReceivedSnippet>>(emptyList())
    val receivedText: StateFlow<List<ReceivedSnippet>> = _receivedText.asStateFlow()

    /**
     * A peer we still had paired answered a request with 403 exactly "not
     * paired": it unpaired us. A permission refusal does not count. The message
     * is shown once and cleared by the UI.
     */
    private val _unpairedByPeer = MutableStateFlow<String?>(null)
    val unpairedByPeer: StateFlow<String?> = _unpairedByPeer.asStateFlow()

    /**
     * The fingerprint of a peer that just finished pairing *to* this phone (an
     * inbound pairing: the desktop initiated and this phone confirmed). The
     * Devices screen watches this so a freshly paired desktop is listed and
     * probed at once instead of showing "paired but offline". Cleared by
     * [acknowledgePairingConfirmed].
     */
    private val _pairingConfirmed = MutableStateFlow<String?>(null)
    val pairingConfirmed: StateFlow<String?> = _pairingConfirmed.asStateFlow()

    /**
     * Every "peer is reachable" signal that carries an address or a short id. A
     * successful inbound handshake is proof the peer is online, so the Devices
     * screen marks it so without waiting for the next probe.
     */
    private val _reachable = MutableSharedFlow<PeerReachable>(extraBufferCapacity = 16)
    val reachable: SharedFlow<PeerReachable> = _reachable.asSharedFlow()

    private val approvalLock = Any()
    private var approvalWaiter: CompletableDeferred<Boolean>? = null

    /**
     * Recent server events (connection, handshake, request, response, close),
     * surfaced in the troubleshoot report so a person can paste what the phone
     * saw. Never contains file contents, full fingerprints or secrets.
     */
    val diagnostics = ServerDiagnostics().apply {
        onRecord = { level, line ->
            when (level) {
                DiagLevel.Debug -> android.util.Log.v("lanyard-diag", line)
                DiagLevel.Info -> android.util.Log.d("lanyard-diag", line)
                DiagLevel.Warn -> android.util.Log.w("lanyard-diag", line)
                DiagLevel.Error -> android.util.Log.e("lanyard-diag", line)
            }
        }
    }

    /** The durable diagnostics file, or null until [init] ran. */
    fun logFile(): File? = logWriter?.file()

    /** The shared paired-peer store (also used by the Devices screen). */
    val trust: TrustStore get() = trustStore

    /**
     * Invoked (from a background thread) by [PeerClient] when a peer verified by
     * its pinned certificate answers a 403 whose reason is exactly "not paired":
     * it has unpaired us. The stale entry is dropped, transfers to it are
     * cancelled, and the person is told. [PeerClient] only fires this after a
     * pinned mTLS handshake, and we additionally require the peer to be in the
     * local trust store, so an unknown device can never delete a pairing.
     */
    private fun onPeerRefusedPairing(fp: String) {
        if (!initialized) return
        val peer = trustStore.find(fp) ?: return
        trustStore.remove(fp)
        pendingUnpairs.remove(fp)
        TransferManager.cancelForPeer(fp)
        diagnostics.record("[pairing] peer=${peer.fingerprint.take(8)} unpair source=peer result=removed")
        _unpairedByPeer.value = Unpair.UNPAIRED_BY_PEER
    }

    fun acknowledgeUnpairedByPeer() {
        _unpairedByPeer.value = null
    }

    /** Clears [pairingConfirmed] once the Devices screen has handled it. */
    fun acknowledgePairingConfirmed() {
        _pairingConfirmed.value = null
    }

    /** Records an unpair notification that could not be delivered, for retry. */
    fun rememberPendingUnpair(peer: PairedPeer, generation: Long) {
        if (!initialized) return
        val stored = pendingUnpairs.upsertIfCurrent(
            PendingUnpair(peer.fingerprint, peer.name, peer.host, peer.port, System.currentTimeMillis()),
            generation,
        )
        if (stored) {
            diagnostics.record("[pairing] peer=${peer.fingerprint.take(8)} unpair pending retry")
        } else {
            diagnostics.record("[pairing] peer=${peer.fingerprint.take(8)} unpair retry ignored; already notified")
        }
    }

    /** The delivery generation for a pending unpair, captured before a notify attempt. */
    fun pendingUnpairGeneration(fingerprint: String): Long =
        if (initialized) pendingUnpairs.deliveryGeneration(fingerprint) else 0L

    fun clearPendingUnpair(fingerprint: String) {
        if (initialized) pendingUnpairs.remove(fingerprint)
    }

    /** Retries every undelivered unpair notification now. */
    fun retryPendingUnpairs() {
        if (!initialized) return
        unpairRetries.onPeerReachable()
    }

    /**
     * A device with a pending unpair was just seen at [host]:[port]; attach the
     * live address and retry the notification.
     */
    fun retryPendingUnpairFor(shortId: String, host: String, port: Int) {
        if (!initialized) return
        unpairRetries.onPeerReachable(shortId, host, port)
    }

    /**
     * One of the "peer reachable" signals fired (a successful hello either way,
     * an inbound handshake, an mDNS sighting). Retries pending unpairs off the
     * caller's thread, since the notify is blocking TLS.
     */
    fun onPeerReachable(shortId: String? = null, host: String? = null, port: Int? = null) {
        if (!initialized) return
        // Proof the peer is online right now: let the Devices screen mark it so
        // without a probe (a successful inbound handshake carries no address).
        if (shortId != null) _reachable.tryEmit(PeerReachable(shortId, host, port))
        // An interrupted transfer to this peer may now be continued.
        TransferManager.onPeerReachable(shortId)
        lifetimeScope.launch(Dispatchers.IO) { unpairRetries.onPeerReachable(shortId, host, port) }
    }

    /** The app came to the foreground: pending unpairs may now be deliverable. */
    fun onAppForeground() {
        if (!initialized) return
        // And any interrupted transfer whose peer is reachable may resume.
        TransferManager.onAppForeground()
        lifetimeScope.launch(Dispatchers.IO) { unpairRetries.onPeerReachable() }
    }

    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        // Durable diagnostics: every event is redacted and appended to a
        // rotating file (about 1 MB x 3 rotations) in private storage, and a
        // divider marks each app start so restarts are visible in "Copy log".
        val writer = RotatingWriter(File(app.filesDir, "logs/lanyard.log"))
        logWriter = writer
        diagnostics.file = writer
        diagnostics.markAppStart()
        trustStore = JsonFileTrustStore(File(app.filesDir, "trust/peers.json"))
        // Lets an interrupted download resolve the peer's current address and
        // resume from its partial when the peer is reachable again.
        TransferManager.onPeerResolver { fp -> trustStore.find(fp) }
        pendingUnpairs = JsonFilePendingUnpairStore(File(app.filesDir, "trust/pending-unpair.json"))
        // Every delivery consults the current pairing, so a revoke queued before
        // a re-pair is refused instead of unpairing the fresh device.
        unpairRetries = PendingUnpairRetry(
            store = pendingUnpairs,
            identity = { IdentityHolder.identity },
            trust = trustStore,
            diag = { msg, level -> diagnostics.record(msg, level) },
        )
        // Any pinned client that sees a generic 403 means that peer dropped us,
        // so the stale local pairing is removed and the person is told.
        PeerClient.onNotPaired = { fp -> onPeerRefusedPairing(fp) }
        sessionStore = PairingSessions(
            selfFp = { IdentityHolder.identity?.deviceId.orEmpty() },
            trust = trustStore,
            onChange = { publish() },
            diag = { diagnostics.record(it) },
            // A successful (re-)pair forgets any unpair queued for that peer,
            // and tells the Devices screen so a desktop that paired *to* the
            // phone is listed and probed without waiting for mDNS.
            onPaired = { fp ->
                pendingUnpairs.remove(fp)
                _pairingConfirmed.value = fp
            },
        )
        inviteStore = PairInvites()
        spoolDir = File(app.filesDir, "spool").apply { mkdirs() }
        snippets = ReceivedSnippets()
        safDestination = SafInboxDestination(app) {
            SettingsHolder.settings.value.downloadFolder?.let { Uri.parse(it) }
        }
        defaultDestination = defaultInboxDestination(app)
        // A folder chosen in Settings wins; otherwise every install can receive
        // straight away into Downloads/LANyard. Forwards folder()/folderLocation()
        // too, so a finished receive knows where it landed (Open folder).
        val destination = SwitchingDestination {
            if (SettingsHolder.settings.value.downloadFolder != null) safDestination else defaultDestination
        }
        receiver = InboxReceiver(
            spoolRoot = spoolDir,
            destination = destination,
            freeBytes = { spoolDir.usableSpace },
            onChange = { publish() },
            onOffer = { pushId, fp, files, total ->
                TransferManager.noteReceiveStarted(
                    pushId,
                    trustStore.find(fp)?.name?.takeIf { it.isNotBlank() } ?: "A device",
                    fp,
                    "Inbox",
                    total,
                    files,
                )
            },
            onProgress = { pushId, done, total, filesDone, filesTotal ->
                TransferManager.noteReceiveProgress(pushId, done, total, filesDone, filesTotal)
            },
            onFinishing = { pushId, completed, files, bytes -> TransferManager.noteFinishing(pushId, completed, files, bytes) },
            onDone = { pushId, _, files, _ ->
                val folder = receiver.destinationFolder().takeIf { it.isNotBlank() }
                val folderUri = receiver.destinationFolderLocation().takeIf { it.isNotBlank() }
                TransferManager.noteReceiveDone(pushId, "Received $files file(s)", folder, folderUri)
            },
            onCancelled = { pushId, reason -> TransferManager.noteReceiveFailed(pushId, reason) },
            onCancelledBySender = { pushId, reason -> TransferManager.noteReceiveCancelled(pushId, reason) },
            onFailed = { pushId, reason -> TransferManager.noteReceiveFailed(pushId, reason) },
            destinationReady = ready@{
                val uri = SettingsHolder.settings.value.downloadFolder?.let { Uri.parse(it) } ?: return@ready true
                runCatching { DocumentFile.fromTreeUri(app, uri)?.canWrite() == true }.getOrDefault(false)
            },
            diag = { diagnostics.record(it) },
        )
        // The Transfers screen's Cancel on a receive row must reach the peer
        // server that owns the live push session.
        TransferManager.onReceiveCancel { id -> receiver.cancelLocal(id) }
        // Transfer finish lines (with average speed) join the same diagnostics
        // ring the server uses, so one paste shows the whole story.
        TransferManager.onDiagnostic { diagnostics.record(it) }
        shareStore = ShareStore(app)
        shareSource = SafShareSource(app, shareStore)
        _shares.value = shareStore.list()
        metered = AndroidMeteredNetwork(app)
        appVersion = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull().orEmpty()
        initialized = true
        watchLifetime()
        startUnpairRetryTimer()
    }

    /**
     * While any pending-unpair record exists, retry it every
     * [PendingUnpairRetry.RETRY_INTERVAL_MS]. The timer is a safety net for a
     * peer that returns without any hello, handshake or mDNS signal.
     */
    private fun startUnpairRetryTimer() {
        lifetimeScope.launch {
            while (isActive) {
                delay(PendingUnpairRetry.RETRY_INTERVAL_MS)
                if (unpairRetries.hasPending()) {
                    runCatching { unpairRetries.tick() }
                }
            }
        }
    }

    /**
     * While a teardown is deferred (the app is backgrounded with a transfer in
     * flight), stop the listener the moment the last transfer finishes. A
     * transfer that is cancelled or fails counts too, since [TransferManager]
     * drops it from its active set.
     */
    private fun watchLifetime() {
        if (watchingLifetime) return
        watchingLifetime = true
        lifetimeScope.launch {
            TransferManager.state.collect {
                if (lifetimePolicy.shouldStop(TransferManager.running().isNotEmpty())) {
                    diagnostics.record("[discovery] listener stopped transfers-drained")
                    stop()
                }
            }
        }
    }

    /** Starts advertising and the peer server; safe to call on every foreground. */
    fun start(context: Context) {
        if (!initialized) init(context)
        // Foregrounded again before a deferred stop ran: keep listening.
        lifetimePolicy.foreground()
        // The app coming forward is itself a "peer reachable" moment: a peer
        // unpaired while offline may be back now. Off the main thread.
        onAppForeground()
        if (server != null) return
        val id = IdentityHolder.identity ?: return
        val app = context.applicationContext
        sessionStore.clear()
        receiver.sweepStale()
        _interrupted.value = receiver.hasPartialSpool()
        val srv = PeerServer(
            sessions = sessionStore,
            receiver = receiver,
            invites = inviteStore,
            isPaired = { trustStore.find(it) },
            metered = { metered?.isMetered() ?: false },
            wifiOnly = { SettingsHolder.settings.value.wifiOnly },
            approval = pushApproval,
            onUnpair = { trustStore.remove(it) },
            // A peer that just handshook with us is reachable: retry its unpair.
            onPeerReachable = { fp -> onPeerReachable(fp.take(16)) },
            shares = ShareServer(shareSource, diag = { diagnostics.record(it) }).also { shareServer = it },
            snippets = snippets,
            onSnippetsChanged = { _receivedText.value = snippets.list() },
            diagnostics = diagnostics,
        )
        val port = try {
            srv.start(id, preferredPort = SettingsHolder.settings.value.preferredPort) { boundPort -> hello(id, boundPort) }
        } catch (_: Exception) {
            return
        }
        server = srv
        // Remember the port actually bound (the preferred one when it was free),
        // so the desktop's stored address stays valid across launches.
        if (SettingsHolder.settings.value.preferredPort != port) {
            SettingsHolder.update { it.copy(preferredPort = port) }
        }
        diagnostics.record("[discovery] peer-server started port=$port")
        advertiser = NsdAdvertiser(app, diag = { msg, level -> diagnostics.record(msg, level) })
            .also { it.start(id.deviceId.take(16), txt(id, port), port, trigger = "lifecycle") }
    }

    /**
     * The app was backgrounded (`onStop`). The listener stays up, served by the
     * transfer foreground service, while any transfer is running (sending or
     * receiving) so the phone remains reachable; otherwise it stops now, exactly
     * as before. When deferred, [watchLifetime] stops it once the last transfer
     * drains. No transfer ever means no extra notification.
     */
    fun onAppBackgrounded() {
        if (lifetimePolicy.background(TransferManager.running().isNotEmpty())) {
            diagnostics.record("[discovery] keep-listener transfer-active")
        } else {
            stop()
        }
    }

    fun stop() {
        lifetimePolicy.foreground()
        server?.stop()
        server = null
        advertiser?.stop()
        advertiser = null
        // A push cannot continue without the server; fail any still-running
        // receive now rather than leave it Running until the next launch.
        TransferManager.failPushReceives("Interrupted")
    }

    fun accept(id: String, granted: Permissions) {
        val short = sessionStore.pending().firstOrNull { it.id == id }?.peerFp?.take(8) ?: "?"
        diagnostics.record("[pairing] peer=$short accept source=phone id=$id granted=browse:${granted.browse},push:${granted.push} result=ok")
        sessionStore.accept(id, granted)
        publish()
    }

    fun decline(id: String) {
        val short = sessionStore.pending().firstOrNull { it.id == id }?.peerFp?.take(8) ?: "?"
        diagnostics.record("[pairing] peer=$short refuse source=phone id=$id reason=declined")
        sessionStore.decline(id)
        publish()
    }

    fun answerApproval(accepted: Boolean) {
        val waiter = synchronized(approvalLock) { approvalWaiter }
        waiter?.complete(accepted)
    }

    fun dismissInterrupted() {
        // Acknowledge the interruption: drop the abandoned spool so the dialog
        // does not return on the next launch. Parts of a push still running in
        // this process are kept.
        receiver.clearAbandonedSpool()
        _interrupted.value = false
    }

    /** Removes one received text snippet from the Transfers surface. */
    fun dismissReceivedText(id: String) {
        if (!initialized) return
        snippets.dismiss(id)
        _receivedText.value = snippets.list()
    }

    /**
     * The `lanyard://pair?...` link for this device's QR code, or null until the
     * server is listening. The one-time invite is reused while still valid.
     */
    fun pairingLink(): String? {
        val id = IdentityHolder.identity ?: return null
        val port = server?.port ?: 0
        if (port == 0) return null
        val addrs = NetAddrs.localIPv4().map { "$it:$port" }
        if (addrs.isEmpty()) return null
        val invite = currentInvite?.takeIf { inviteStore.valid(it.token) != null }
            ?: inviteStore.mint().also { currentInvite = it }
        return PairLink.build(
            PairLink.Payload(
                fingerprint = id.deviceId,
                name = IdentityHolder.deviceName,
                addrs = addrs,
                nonce = invite.token,
            ),
        )
    }

    private fun publish() {
        _pending.value = sessionStore.pending()
    }

    /**
     * Asks the person before accepting a push. Answers quickly when no prompt can
     * be shown (another prompt is up, or nothing is foreground), so the sender is
     * refused at once rather than waiting on an offer that can never be answered.
     */
    private val pushApproval = PushApproval { _, name, files, total, names ->
        val waiter = CompletableDeferred<Boolean>()
        synchronized(approvalLock) {
            if (approvalWaiter != null || sessionStore.pending().isNotEmpty()) {
                return@PushApproval ApprovalOutcome.BUSY
            }
            if (server == null) return@PushApproval ApprovalOutcome.UNAVAILABLE
            approvalWaiter = waiter
            _approval.value = PushApprovalRequest(UUID.randomUUID().toString(), Display.safeName(name), files, total, names)
        }
        val accepted = runBlocking { withTimeoutOrNull(120_000) { waiter.await() } } ?: false
        synchronized(approvalLock) {
            approvalWaiter = null
            _approval.value = null
        }
        if (accepted) ApprovalOutcome.ACCEPTED else ApprovalOutcome.DECLINED
    }

    fun addFolderShare(label: String, uri: android.net.Uri) {
        shareSource.addFolder(label, uri)
        _shares.value = shareStore.list()
    }

    fun stopShare(id: String) {
        shareServer?.cancel(id)
        shareSource.stop(id)
        _shares.value = shareStore.list()
    }

    fun shareSource(): io.github.tuscani712.lanyard.core.ShareSource = shareSource

    private fun hello(id: Identity, port: Int): JsonObject = JsonObject().apply {
        addProperty("device_id", id.deviceId.take(16))
        addProperty("fingerprint", id.deviceId)
        addProperty("name", IdentityHolder.deviceName)
        addProperty("os", "android")
        addProperty("version", appVersion)
        addProperty("port", port)
        // Advertise what this phone will accept, so a desktop can batch a large
        // offer instead of guessing (and tripping a 413). See PushProtocol.
        addProperty("max_offer_bytes", PushProtocol.MAX_OFFER_BODY_BYTES)
        addProperty("max_offer_files", PushProtocol.MAX_OFFER_FILES)
    }

    private fun txt(id: Identity, port: Int): Map<String, String> = mapOf(
        "v" to "2",
        "id" to id.deviceId.take(16),
        "did" to "",
        "n" to IdentityHolder.deviceName,
        "os" to "android",
        "p" to port.toString(),
    )
}
