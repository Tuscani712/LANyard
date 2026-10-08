package io.github.tuscani712.lanyard

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.gson.JsonObject
import io.github.tuscani712.lanyard.core.ApprovalOutcome
import io.github.tuscani712.lanyard.core.BackgroundListenerPolicy
import io.github.tuscani712.lanyard.core.Display
import io.github.tuscani712.lanyard.core.Identity
import io.github.tuscani712.lanyard.core.InboxReceiver
import io.github.tuscani712.lanyard.core.IncomingRequest
import io.github.tuscani712.lanyard.core.JsonFileTrustStore
import io.github.tuscani712.lanyard.core.PairInvites
import io.github.tuscani712.lanyard.core.PairLink
import io.github.tuscani712.lanyard.core.PairingSessions
import io.github.tuscani712.lanyard.core.PeerServer
import io.github.tuscani712.lanyard.core.Permissions
import io.github.tuscani712.lanyard.core.PushApproval
import io.github.tuscani712.lanyard.core.PushDestination
import io.github.tuscani712.lanyard.core.ServerDiagnostics
import io.github.tuscani712.lanyard.core.ShareServer
import io.github.tuscani712.lanyard.core.TrustStore
import io.github.tuscani712.lanyard.net.AndroidMeteredNetwork
import io.github.tuscani712.lanyard.net.NetAddrs
import io.github.tuscani712.lanyard.net.NsdAdvertiser
import io.github.tuscani712.lanyard.transfer.TransferManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
 * The app-scoped home of the phone-side peer server and the shared trust store.
 *
 * Lifetime: [start] while the app is foregrounded (any screen), [stop] when it is
 * backgrounded. It owns the `/hello` responder, pairing (C1) and the receive
 * side of pushes (C2a), plus the one-time QR invites and the pending prompts.
 */
object PeerService {
    private var initialized = false
    private lateinit var trustStore: TrustStore
    private lateinit var sessionStore: PairingSessions
    private lateinit var inviteStore: PairInvites
    private lateinit var receiver: InboxReceiver
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

    private val approvalLock = Any()
    private var approvalWaiter: CompletableDeferred<Boolean>? = null

    /**
     * Recent server events (connection, handshake, request, response, close),
     * surfaced in the troubleshoot report so a person can paste what the phone
     * saw. Never contains file contents, full fingerprints or secrets.
     */
    val diagnostics = ServerDiagnostics().apply {
        onRecord = { line -> android.util.Log.d("lanyard-diag", line) }
    }

    /** The shared paired-peer store (also used by the Devices screen). */
    val trust: TrustStore get() = trustStore

    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        trustStore = JsonFileTrustStore(File(app.filesDir, "trust/peers.json"))
        sessionStore = PairingSessions(
            selfFp = { IdentityHolder.identity?.deviceId.orEmpty() },
            trust = trustStore,
            onChange = { publish() },
            diag = { diagnostics.record(it) },
        )
        inviteStore = PairInvites()
        spoolDir = File(app.filesDir, "spool").apply { mkdirs() }
        safDestination = SafInboxDestination(app) {
            SettingsHolder.settings.value.downloadFolder?.let { Uri.parse(it) }
        }
        defaultDestination = defaultInboxDestination(app)
        val destination = PushDestination { rel, spool, size ->
            // A folder chosen in Settings wins; otherwise every install can
            // receive straight away into Downloads/LANyard.
            if (SettingsHolder.settings.value.downloadFolder != null) {
                safDestination.place(rel, spool, size)
            } else {
                defaultDestination.place(rel, spool, size)
            }
        }
        receiver = InboxReceiver(
            spoolRoot = spoolDir,
            destination = destination,
            freeBytes = { spoolDir.usableSpace },
            onChange = { publish() },
            onOffer = { pushId, fp, files, total ->
                TransferManager.noteReceiveStarted(pushId, trustStore.find(fp)?.name?.takeIf { it.isNotBlank() } ?: "A device", fp, "Inbox", total)
            },
            onProgress = { pushId, done, total -> TransferManager.noteReceiveProgress(pushId, done, total) },
            onDone = { pushId, _, files, _ -> TransferManager.noteReceiveDone(pushId, "Received $files file(s)") },
            onCancelled = { pushId, reason -> TransferManager.noteReceiveFailed(pushId, reason) },
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
        shareStore = ShareStore(app)
        shareSource = SafShareSource(app, shareStore)
        _shares.value = shareStore.list()
        metered = AndroidMeteredNetwork(app)
        appVersion = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull().orEmpty()
        initialized = true
        watchLifetime()
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
            shares = ShareServer(shareSource, diag = { diagnostics.record(it) }).also { shareServer = it },
            diagnostics = diagnostics,
        )
        val port = try {
            srv.start(id) { boundPort -> hello(id, boundPort) }
        } catch (_: Exception) {
            return
        }
        server = srv
        diagnostics.record("[discovery] peer-server started port=$port")
        advertiser = NsdAdvertiser(app, diag = { diagnostics.record(it) })
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
