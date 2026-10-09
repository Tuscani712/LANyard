package io.github.tuscani712.lanyard

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import io.github.tuscani712.lanyard.core.NavBackStack
import io.github.tuscani712.lanyard.core.PairResult
import io.github.tuscani712.lanyard.core.PairedPeer
import io.github.tuscani712.lanyard.core.PairingFlow
import io.github.tuscani712.lanyard.core.PeerAddresses
import io.github.tuscani712.lanyard.core.DiagLevel
import io.github.tuscani712.lanyard.core.DiscoveredAddr
import io.github.tuscani712.lanyard.core.PeerClient
import io.github.tuscani712.lanyard.core.PeerErrors
import io.github.tuscani712.lanyard.core.PeerProbeMonitor
import io.github.tuscani712.lanyard.core.PeerProbeSet
import io.github.tuscani712.lanyard.core.PeerStatusException
import io.github.tuscani712.lanyard.core.ProbeClient
import io.github.tuscani712.lanyard.core.SelfFilter
import io.github.tuscani712.lanyard.core.TrustStore
import io.github.tuscani712.lanyard.core.Unpair
import io.github.tuscani712.lanyard.net.NearbyDevice
import io.github.tuscani712.lanyard.net.NsdDiscovery
import io.github.tuscani712.lanyard.share.SourceResult
import io.github.tuscani712.lanyard.share.spoolShare
import io.github.tuscani712.lanyard.transfer.TransferManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** How long a paired peer's reachability result is reused before re-probing. */
private const val ONLINE_PROBE_CACHE_MS = 5_000L

/** How often the Devices screen re-probes its paired peers while it is showing. */
private const val PROBE_INTERVAL_MS = PeerProbeSet.DEFAULT_INTERVAL_MS

/** A paired peer plus its last-known reachability. */
data class PairedStatus(
    val peer: PairedPeer,
    val online: Boolean,
    // Why the last probe failed (timeout vs refused vs other), for the row. Null
    // while online or before the first probe has finished.
    val offlineReason: String? = null,
)

/** The pairing attempt's state, for the Add-device dialog. */
sealed interface PairingStatus {
    data object Running : PairingStatus
    /**
     * A nearby (no-QR) pairing is waiting for the person to compare the SAS on
     * both devices. [fingerprint] is the pinned certificate fingerprint from the
     * probe (shown as a short form), never the mDNS id.
     */
    data class Confirming(val sas: String, val peerName: String, val fingerprint: String) : PairingStatus
    data class Done(val message: String, val ok: Boolean) : PairingStatus
}

/** One share a peer offers. */
data class ShareItem(
    val id: String,
    val label: String,
    val name: String,
    val kind: String,
    val size: Long,
    val lifetime: String,
)

/** One entry in a share's folder tree. */
data class TreeItem(val name: String, val path: String, val isDir: Boolean, val size: Long)

/** The detail view for one paired peer. */
data class PeerDetail(
    val peer: PairedPeer,
    val loading: Boolean = true,
    val shares: List<ShareItem> = emptyList(),
    val error: String? = null,
    val openShare: ShareItem? = null,
    val treePath: String = "",
    val tree: List<TreeItem> = emptyList(),
    val treeLoading: Boolean = false,
    val notice: String? = null,
)

data class DevicesUiState(
    val paired: List<PairedStatus> = emptyList(),
    val nearby: List<NearbyDevice> = emptyList(),
    val refreshing: Boolean = false,
    val pairing: PairingStatus? = null,
    val detail: PeerDetail? = null,
    // Shown when an unpair removed the local entry but the peer could not be told.
    val unpairNotice: String? = null,
)

/**
 * State and network work for the Devices tab. All blocking IO and TLS runs on
 * [Dispatchers.IO]; cancellation is tied to the ViewModel's lifetime.
 *
 * Discovery lifecycle: mDNS browsing ([discovery]) runs only while the Devices
 * screen is showing. Advertising and the phone-side peer server live in
 * [PeerService] for the whole foreground (any screen), so the phone stays
 * discoverable and online until the app is backgrounded.
 */
class DevicesViewModel(app: Application) : AndroidViewModel(app) {
    // The same trust store the peer server writes to when a desktop pairs to us.
    private val store: TrustStore get() = PeerService.trust
    private val discovery = NsdDiscovery(app, diag = { msg, level -> PeerService.diagnostics.record(msg, level) })

    // Last reachability probe per peer fingerprint, so a burst of mDNS events
    // does not re-probe (and re-log) the same peer. Carries the offline reason
    // so the row can say why, not just "offline". See [isOnline].
    private data class ProbeResult(val at: Long, val online: Boolean, val reason: String?)

    private val onlineCache = HashMap<String, ProbeResult>()

    // Schedules probes for the current paired set. A peer that becomes known
    // (including a desktop that paired *to* the phone) is probed at once, and a
    // periodic [runProbeLoop] re-probes the set while the screen is showing.
    private val probes = PeerProbeMonitor(
        peers = {
            val own = SelfFilter.ownShortId(IdentityHolder.identity?.deviceId.orEmpty())
            store.list()
                .filterNot { SelfFilter.isSelf(SelfFilter.ownShortId(it.fingerprint), own) }
        },
        probe = { isOnline(it) },
        onResult = { peer, online -> applyProbeResult(peer, online) },
        intervalMs = PROBE_INTERVAL_MS,
    )

    // The periodic probe loop, alive only while the Devices screen is started.
    private var probeJob: Job? = null

    // The nearby (SAS) pairing runs on Dispatchers.IO and blocks there until the
    // person confirms the code; this is the same waiter pattern PeerService uses
    // for a push approval. Guarded because the UI thread answers it.
    private val nearbyLock = Any()
    private var nearbyWaiter: CompletableDeferred<Boolean>? = null
    private var nearbyCancelled = false

    // Folder hierarchy inside one open share, so a system Back press walks up one
    // folder at a time (and then back to the share list). The root entry is the
    // share's own folder (`""`). See [treeUp] / [detailBack].
    private val treeStack = NavBackStack("")

    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()

    init {
        // A peer that refused a request with "not paired" removed us; surface it
        // in the same Unpaired dialog the deliberate unpair uses.
        viewModelScope.launch {
            PeerService.unpairedByPeer.collect { message ->
                if (message != null) _state.update { it.copy(unpairNotice = message) }
            }
        }
        // A desktop that paired *to* this phone lands in the trust store with no
        // mDNS/refresh of ours: refresh at once so it is not stuck "paired but
        // offline" until something else happens.
        viewModelScope.launch {
            PeerService.pairingConfirmed.collect { fingerprint ->
                if (fingerprint != null) {
                    refreshPaired()
                    PeerService.acknowledgePairingConfirmed()
                }
            }
        }
        // A successful inbound handshake (or outbound hello) is proof a peer is
        // online; mark it so without waiting for the next probe.
        viewModelScope.launch {
            PeerService.reachable.collect { event -> markReachable(event.shortId) }
        }
    }

    fun refreshPaired() {
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            val identity = IdentityHolder.identity
            val paired = withContext(Dispatchers.IO) {
                if (identity == null) return@withContext emptyList()
                // A device unpaired while offline may be reachable now.
                PeerService.retryPendingUnpairs()
                // A desktop that paired *to* this phone is stored without a port
                // (the request carries none). Fill it from mDNS once discovered.
                val discovered = _state.value.nearby.map { DiscoveredAddr(it.shortId, it.host, it.port) }
                PeerAddresses.fillFromDiscovery(store.list(), discovered).forEach { store.save(it) }
                // Register the current set: a peer that is new (including one
                // that just paired *to* us) or re-addressed is probed now; an
                // unchanged peer keeps its last result and is left to the loop.
                val peers = probes.sync()
                peers.map { peer ->
                    val online = isOnline(peer)
                    PairedStatus(peer, online, if (online) null else offlineReasonFor(peer.fingerprint))
                }
            }
            _state.update { it.copy(paired = paired, refreshing = false) }
        }
    }

    fun startNearby() {
        discovery.start(
            onFound = { device ->
                // Drop this phone's own mDNS advertisement: without this it
                // lists itself and invites a pairing that cannot work.
                val own = SelfFilter.ownShortId(IdentityHolder.identity?.deviceId.orEmpty())
                if (!SelfFilter.isSelf(device.shortId, own)) {
                    // A first sighting is worth one info line; the same peer
                    // re-announcing over mDNS is routine chatter and is kept out
                    // of the durable log.
                    val known = _state.value.nearby.any { it.shortId == device.shortId }
                    PeerService.diagnostics.record(
                        "[discovery] peer=${device.shortId.take(8)} seen addr=${device.host}:${device.port} source=mdns result=ok",
                        if (known) DiagLevel.Debug else DiagLevel.Info,
                    )
                    _state.update { current ->
                        current.copy(nearby = (current.nearby.filterNot { it.shortId == device.shortId } + device))
                    }
                    // A device unpaired while it was offline: deliver now that we
                    // can see its address.
                    viewModelScope.launch(Dispatchers.IO) {
                        PeerService.retryPendingUnpairFor(device.shortId, device.host, device.port)
                    }
                    // A newly seen device may supply the address of a paired peer.
                    refreshPaired()
                }
            },
            onLost = { shortId ->
                // A device that stopped advertising (or sent a goodbye) has
                // normally just gone to sleep or left: a normal eviction, not an
                // error, so it stays at info rather than filling the log.
                PeerService.diagnostics.record("[discovery] peer=${shortId.take(8)} left source=mdns result=ok", DiagLevel.Info)
                _state.update { current -> current.copy(nearby = current.nearby.filterNot { it.shortId == shortId }) }
            },
            trigger = "screen",
        )
        startProbeLoop()
    }

    /** Stops mDNS browsing (advertising and the peer server are app-scoped). */
    fun stopNearby() {
        discovery.stop()
        probeJob?.cancel()
        probeJob = null
    }

    /**
     * Re-probes the known paired set every [PROBE_INTERVAL_MS] while the Devices
     * screen is started, so a peer that comes back (or goes away) is noticed even
     * when no mDNS sighting or inbound request arrives. Runs on IO because each
     * probe is a blocking TLS hello, and stops with [stopNearby].
     */
    private fun startProbeLoop() {
        if (probeJob != null) return
        probeJob = viewModelScope.launch {
            while (isActive) {
                delay(PROBE_INTERVAL_MS)
                withContext(Dispatchers.IO) { probes.tick() }
            }
        }
    }

    /**
     * The `lanyard://pair?...` link for this device, to render as a QR code for
     * another device to scan. Comes from [PeerService], which owns the server
     * port and mints the one-time invite.
     */
    fun pairingLink(): String? = PeerService.pairingLink()

    fun pair(link: String) {
        if (link.isBlank()) {
            _state.update { it.copy(pairing = PairingStatus.Done("Paste a pairing link first.", false)) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(pairing = PairingStatus.Running) }
            val identity = IdentityHolder.identity
            if (identity == null) {
                _state.update { it.copy(pairing = PairingStatus.Done("This device has no identity yet.", false)) }
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                PairingFlow.pair(link.trim(), identity, IdentityHolder.deviceName, store, diag = { PeerService.diagnostics.record(it) })
            }
            _state.update {
                it.copy(pairing = PairingStatus.Done(resultMessage(result), result is PairResult.Paired))
            }
            if (result is PairResult.Paired) {
                // A fresh pairing supersedes any unpair we queued for this peer.
                PeerService.clearPendingUnpair(result.peer.fingerprint)
                refreshPaired()
            }
        }
    }

    fun dismissPairing() = _state.update { it.copy(pairing = null) }

    /**
     * Pairs with a nearby device without a QR code: the core probes it, pins the
     * certificate it presents, and blocks on [confirmNearby]/[cancelNearby]
     * until the person compares the SAS. Nothing is stored unless they confirm.
     */
    fun pairNearby(device: NearbyDevice) {
        viewModelScope.launch {
            val identity = IdentityHolder.identity
            if (identity == null) {
                _state.update { it.copy(pairing = PairingStatus.Done("This device has no identity yet.", false)) }
                return@launch
            }
            val waiter = CompletableDeferred<Boolean>()
            synchronized(nearbyLock) {
                nearbyWaiter = waiter
                nearbyCancelled = false
            }
            _state.update { it.copy(pairing = PairingStatus.Running) }
            val result = try {
                withContext(Dispatchers.IO) {
                    PairingFlow.pairNearby(
                        host = device.host,
                        port = device.port,
                        identity = identity,
                        selfName = IdentityHolder.deviceName,
                        store = store,
                        awaitCodesMatch = { prompt ->
                            _state.update {
                                it.copy(pairing = PairingStatus.Confirming(prompt.sas, prompt.peerName, prompt.fingerprint))
                            }
                            runBlocking { withTimeoutOrNull(120_000) { waiter.await() } } ?: false
                        },
                        diag = { PeerService.diagnostics.record(it) },
                    )
                }
            } finally {
                synchronized(nearbyLock) { nearbyWaiter = null }
            }
            val cancelled = synchronized(nearbyLock) { nearbyCancelled }
            _state.update {
                if (cancelled) {
                    it.copy(pairing = PairingStatus.Done("Pairing cancelled.", false))
                } else {
                    it.copy(pairing = PairingStatus.Done(resultMessage(result), result is PairResult.Paired))
                }
            }
            if (result is PairResult.Paired) {
                // A fresh pairing supersedes any unpair we queued for this peer.
                PeerService.clearPendingUnpair(result.peer.fingerprint)
                refreshPaired()
            }
        }
    }

    /** The person confirmed the SAS matches on both devices: commit the pairing. */
    fun confirmNearby() {
        val waiter = synchronized(nearbyLock) { nearbyWaiter }
        waiter?.complete(true)
    }

    /** The person cancelled (or dismissed) the SAS dialog: refuse and store nothing. */
    fun cancelNearby() {
        val waiter = synchronized(nearbyLock) {
            nearbyCancelled = true
            nearbyWaiter
        }
        waiter?.complete(false)
    }

    /**
     * Removes a pairing. The peer is told to drop us too (best effort, and
     * idempotent: a peer that already dropped us answers 403, which counts as
     * success), the local trust entry is always removed, running transfers to it
     * are cancelled, and the paired and open-detail state is refreshed. When the
     * peer could not be notified the person is told rather than left in silence.
     */
    fun unpair(peer: PairedPeer) {
        viewModelScope.launch {
            val identity = IdentityHolder.identity
            // Capture the pending-unpair generation before the attempt: if a
            // racing sibling notifies the peer first, this token stops a losing
            // retry from re-arming the record after the fact.
            val generation = PeerService.pendingUnpairGeneration(peer.fingerprint)
            // The same idempotent routine Settings uses: the peer is told (403
            // counts as success), the local entry is always removed, and an
            // offline peer is remembered for a retry.
            val outcome = withContext(Dispatchers.IO) {
                Unpair.perform(
                    notifyPeer = {
                        if (identity != null) {
                            PeerClient(peer.host, peer.port, identity, peer.fingerprint).revokeTrustIdempotent()
                        } else {
                            false
                        }
                    },
                    removeLocal = { store.remove(peer.fingerprint) },
                    cancelTransfers = { TransferManager.cancelForPeer(peer.fingerprint) },
                    onRemoteNotified = { PeerService.clearPendingUnpair(peer.fingerprint) },
                    onRemoteNotNotified = { PeerService.rememberPendingUnpair(peer, generation) },
                )
            }
            PeerService.diagnostics.record("[pairing] peer=${peer.fingerprint.take(8)} unpair source=phone remote=${outcome.remoteNotified} result=ok")
            _state.update { current ->
                val detail = current.detail
                val cleared = if (detail != null && detail.peer.fingerprint.equals(peer.fingerprint, ignoreCase = true)) {
                    current.copy(detail = null)
                } else {
                    current
                }
                cleared.copy(unpairNotice = outcome.notice)
            }
            refreshPaired()
        }
    }

    fun dismissUnpairNotice() {
        _state.update { it.copy(unpairNotice = null) }
        PeerService.acknowledgeUnpairedByPeer()
    }

    fun openPeer(peer: PairedPeer) {
        treeStack.reset()
        _state.update { it.copy(detail = PeerDetail(peer = peer, loading = true)) }
        viewModelScope.launch {
            val identity = IdentityHolder.identity ?: return@launch
            val detail = withContext(Dispatchers.IO) {
                try {
                    val client = PeerClient(peer.host, peer.port, identity, peer.fingerprint)
                    PeerDetail(peer = peer, loading = false, shares = client.listShares().map { it.toShareItem() })
                } catch (e: Exception) {
                    PeerDetail(peer = peer, loading = false, error = friendly(e))
                }
            }
            _state.update { it.copy(detail = detail) }
        }
    }

    fun openShare(share: ShareItem) {
        treeStack.reset()
        loadTree(share, "")
    }

    /** Descends into a folder of the open share. */
    fun openPath(path: String) {
        val share = _state.value.detail?.openShare ?: return
        treeStack.push(path)
        loadTree(share, path)
    }

    /** Moves up one folder inside the open share, or back to the share list. */
    fun treeUp() {
        val detail = _state.value.detail ?: return
        val share = detail.openShare ?: return
        val parent = treeStack.back()
        if (parent == null) {
            backToShares()
        } else {
            loadTree(share, parent)
        }
    }

    fun backToShares() {
        treeStack.reset()
        _state.update { current ->
            val detail = current.detail ?: return@update current
            current.copy(detail = detail.copy(openShare = null, tree = emptyList(), treePath = "", error = null))
        }
    }

    fun closePeer() {
        treeStack.reset()
        _state.update { it.copy(detail = null) }
    }

    /**
     * System Back while a peer's detail is open: up one folder, then back to the
     * share list, then close the detail. Never exits the app and never touches a
     * running transfer.
     */
    fun detailBack() {
        val detail = _state.value.detail ?: return
        if (detail.openShare == null) closePeer() else treeUp()
    }

    /** Sends a text snippet (the desktop caps these at 64 KB), through the same gate as files. */
    fun sendText(peer: PairedPeer, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val blocked = TransferManager.refusal()
        if (blocked != null) {
            setNotice(blocked)
            return
        }
        TransferManager.enqueueSnippet(peer, trimmed.take(64 * 1024))
        setNotice("Text sending. See Transfers.")
    }

    /** Spools the picked documents and pushes them to the peer's Inbox (see Transfers). */
    fun sendFiles(peer: PairedPeer, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val app = getApplication<Application>()
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                val used = HashSet<String>()
                uris.map { spoolShare(app, it, used) }
            }
            val sources = results.mapNotNull { (it as? SourceResult.Ok)?.source }
            val spools = results.mapNotNull { (it as? SourceResult.Ok)?.spool }
            if (sources.isEmpty()) {
                setNotice("Those files could not be opened.")
                return@launch
            }
            val label = sources.first().relPath + if (sources.size > 1) " +${sources.size - 1}" else ""
            TransferManager.enqueuePush(peer, sources, label) { spools.forEach { it.delete() } }
            setNotice("Sending ${sources.size} file(s). See Transfers.")
        }
    }

    /** Pulls a share into the chosen SAF folder (see Transfers). */
    fun downloadShare(peer: PairedPeer, share: ShareItem, tree: Uri) {
        val label = share.label.ifEmpty { share.name.ifEmpty { "share" } }
        TransferManager.enqueueDownload(peer, share.id, "", label, tree)
        setNotice("Receiving \"$label\". See Transfers.")
    }

    fun rememberTree(uri: Uri) = TransferManager.rememberTree(uri)

    fun rememberedTree(): Uri? = TransferManager.rememberedTree()

    /** The remembered download folder if its persisted permission is still held. */
    fun validDownloadFolder(): Uri? {
        val uri = TransferManager.rememberedTree() ?: return null
        val held = getApplication<Application>().contentResolver.persistedUriPermissions
            .any { it.uri == uri && it.isReadPermission && it.isWritePermission }
        return if (held) uri else null
    }

    private fun setNotice(text: String) = _state.update { current ->
        val detail = current.detail ?: return@update current
        current.copy(detail = detail.copy(notice = text))
    }

    fun dismissNotice() = _state.update { current ->
        val detail = current.detail ?: return@update current
        current.copy(detail = detail.copy(notice = null))
    }

    private fun loadTree(share: ShareItem, path: String) {
        val detail = _state.value.detail ?: return
        _state.update { it.copy(detail = detail.copy(openShare = share, treeLoading = true, treePath = path, error = null)) }
        viewModelScope.launch {
            val identity = IdentityHolder.identity ?: return@launch
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val client = PeerClient(detail.peer.host, detail.peer.port, identity, detail.peer.fingerprint)
                    Result.success(client.tree(share.id, path).map { it.toTreeItem() })
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            _state.update { current ->
                val d = current.detail ?: return@update current
                outcome.fold(
                    onSuccess = { current.copy(detail = d.copy(tree = it, treeLoading = false)) },
                    onFailure = { current.copy(detail = d.copy(treeLoading = false, error = friendly(it))) },
                )
            }
        }
    }

    /**
     * A probe finished while a paired peer's status is being tracked: update the
     * matching row when it is already listed (a freshly registered peer is added
     * by [refreshPaired] itself).
     */
    private fun applyProbeResult(peer: PairedPeer, online: Boolean) {
        val fp = peer.fingerprint.lowercase()
        val reason = if (online) null else offlineReasonFor(peer.fingerprint)
        _state.update { current ->
            if (current.paired.none { it.peer.fingerprint.equals(fp, ignoreCase = true) }) return@update current
            current.copy(paired = current.paired.map {
                if (it.peer.fingerprint.equals(fp, ignoreCase = true)) {
                    it.copy(online = online, offlineReason = reason)
                } else {
                    it
                }
            })
        }
    }

    /**
     * A peer proved it is online (an inbound handshake or a successful hello):
     * mark the matching paired row online and remember it, so a desktop that
     * paired *to* the phone is not shown offline. [shortId] is the first 16 hex
     * characters of the peer's fingerprint, as carried by the reachable event.
     */
    private fun markReachable(shortId: String) {
        val fp = _state.value.paired.map { it.peer }.firstOrNull {
            SelfFilter.ownShortId(it.fingerprint).equals(shortId, ignoreCase = true)
        }?.fingerprint ?: return
        synchronized(onlineCache) { onlineCache[fp] = ProbeResult(System.currentTimeMillis(), true, null) }
        _state.update { current ->
            current.copy(paired = current.paired.map {
                if (it.peer.fingerprint.equals(fp, ignoreCase = true)) it.copy(online = true) else it
            })
        }
    }

    private fun isOnline(peer: PairedPeer): Boolean {
        val identity = IdentityHolder.identity ?: return false
        val now = System.currentTimeMillis()
        // Probe at most once per window per peer (the desktop's discovery manager
        // throttles the same way): a repeated mDNS event must not re-probe and
        // re-log the same peer.
        synchronized(onlineCache) {
            onlineCache[peer.fingerprint]?.let { cached ->
                if (now - cached.at < ONLINE_PROBE_CACHE_MS) return cached.online
            }
        }
        val started = now
        val short = peer.fingerprint.take(8)
        // Only set when the probe throws: why it could not be reached, so the row
        // and the diagnostic line can distinguish a firewall from a closed app.
        var reason: String? = null
        val online = try {
            val probe = ProbeClient(peer.host, peer.port, identity)
            probe.hello()
            val match = probe.observedFingerprint().equals(peer.fingerprint, ignoreCase = true)
            if (match) {
                // A successful outbound hello means the peer is reachable right
                // now: let a pending unpair be delivered without waiting.
                PeerService.onPeerReachable(peer.fingerprint.take(16), peer.host, peer.port)
                // A routine, successful hello is healthy chatter: debug keeps it
                // in the copied report but out of the durable log.
                PeerService.diagnostics.record(
                    "[discovery] peer=$short hello addr=${peer.host}:${peer.port} result=online elapsed=${System.currentTimeMillis() - started}ms",
                    DiagLevel.Debug,
                )
            } else {
                // A changed identity is a genuine fault, so it stays visible.
                PeerService.diagnostics.record(
                    "[discovery] peer=$short hello addr=${peer.host}:${peer.port} result=mismatch elapsed=${System.currentTimeMillis() - started}ms",
                    DiagLevel.Warn,
                )
            }
            match
        } catch (e: Exception) {
            // A peer that is asleep or has left is a normal, recoverable event,
            // not an error: info stays visible without being alarming. The reason
            // separates a timeout (firewall) from a refusal (app closed).
            reason = PeerErrors.offlineReason(e)
            PeerService.diagnostics.record(
                "[discovery] peer=$short hello addr=${peer.host}:${peer.port} result=offline elapsed=${System.currentTimeMillis() - started}ms error=${e.javaClass.simpleName} reason=${PeerErrors.offlineReasonCode(e)}",
                DiagLevel.Info,
            )
            false
        }
        synchronized(onlineCache) { onlineCache[peer.fingerprint] = ProbeResult(System.currentTimeMillis(), online, reason) }
        return online
    }

    /** The last offline reason for [fingerprint], or null while online/unknown. */
    private fun offlineReasonFor(fingerprint: String): String? =
        synchronized(onlineCache) { onlineCache[fingerprint]?.reason }

    private fun resultMessage(result: PairResult): String = when (result) {
        is PairResult.Paired -> "Paired with ${result.peer.name.ifEmpty { "the device" }}."
        PairResult.Refused -> "The other device refused the request."
        PairResult.Expired -> "The pairing window expired. Ask for a new link."
        PairResult.Unreachable -> "Could not reach the device. Check it is on this network."
        PairResult.FingerprintMismatch -> "The device at that address is not the one the link promised."
        PairResult.InvalidLink -> "That is not a valid pairing link."
    }

    private fun friendly(t: Throwable): String = when {
        t is PeerStatusException -> PeerErrors.userMessage(t)
        // A transport failure reads with the same timeout/refused distinction the
        // Devices row shows, so an open-peer error is not a bare "offline".
        t is IOException -> PeerErrors.offlineReason(t)
        else -> t.message ?: PeerErrors.OFFLINE_UNREACHABLE
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString ?: ""

    private fun JsonObject.long(key: String): Long =
        get(key)?.takeIf { !it.isJsonNull }?.asLong ?: 0L

    private fun JsonObject.toShareItem(): ShareItem = ShareItem(
        id = str("share_id"),
        label = str("label"),
        name = str("name"),
        kind = str("kind"),
        size = long("size"),
        lifetime = str("lifetime"),
    )

    private fun JsonObject.toTreeItem(): TreeItem = TreeItem(
        name = str("name"),
        path = str("path"),
        isDir = get("is_dir")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
        size = long("size"),
    )

    override fun onCleared() {
        super.onCleared()
        stopNearby()
    }
}
