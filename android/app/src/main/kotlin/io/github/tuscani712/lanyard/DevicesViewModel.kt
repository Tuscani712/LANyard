package io.github.tuscani712.lanyard

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import io.github.tuscani712.lanyard.core.PairResult
import io.github.tuscani712.lanyard.core.PairedPeer
import io.github.tuscani712.lanyard.core.PairingFlow
import io.github.tuscani712.lanyard.core.PeerAddresses
import io.github.tuscani712.lanyard.core.DiscoveredAddr
import io.github.tuscani712.lanyard.core.PeerClient
import io.github.tuscani712.lanyard.core.PeerStatusException
import io.github.tuscani712.lanyard.core.ProbeClient
import io.github.tuscani712.lanyard.core.SelfFilter
import io.github.tuscani712.lanyard.core.TrustStore
import io.github.tuscani712.lanyard.net.NearbyDevice
import io.github.tuscani712.lanyard.net.NsdDiscovery
import io.github.tuscani712.lanyard.share.SourceResult
import io.github.tuscani712.lanyard.share.spoolShare
import io.github.tuscani712.lanyard.transfer.TransferManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A paired peer plus its last-known reachability. */
data class PairedStatus(val peer: PairedPeer, val online: Boolean)

/** The pairing attempt's state, for the Add-device dialog. */
sealed interface PairingStatus {
    data object Running : PairingStatus
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
    private val discovery = NsdDiscovery(app, diag = { PeerService.diagnostics.record(it) })

    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()

    fun refreshPaired() {
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            val identity = IdentityHolder.identity
            val own = SelfFilter.ownShortId(identity?.deviceId.orEmpty())
            val paired = withContext(Dispatchers.IO) {
                if (identity == null) return@withContext emptyList()
                // A desktop that paired *to* this phone is stored without a port
                // (the request carries none). Fill it from mDNS once discovered.
                val discovered = _state.value.nearby.map { DiscoveredAddr(it.shortId, it.host, it.port) }
                PeerAddresses.fillFromDiscovery(store.list(), discovered).forEach { store.save(it) }
                store.list()
                    // Never list this device as one of its own paired peers.
                    .filterNot { SelfFilter.isSelf(SelfFilter.ownShortId(it.fingerprint), own) }
                    .map { peer ->
                        PairedStatus(peer, isOnline(peer))
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
                    PeerService.diagnostics.record("[discovery] peer=${device.shortId.take(8)} seen addr=${device.host}:${device.port} source=mdns result=ok")
                    _state.update { current ->
                        current.copy(nearby = (current.nearby.filterNot { it.shortId == device.shortId } + device))
                    }
                    // A newly seen device may supply the address of a paired peer.
                    refreshPaired()
                }
            },
            onLost = { shortId ->
                PeerService.diagnostics.record("[discovery] peer=${shortId.take(8)} lost source=mdns result=evicted miss=1")
                _state.update { current -> current.copy(nearby = current.nearby.filterNot { it.shortId == shortId }) }
            },
            trigger = "screen",
        )
    }

    /** Stops mDNS browsing (advertising and the peer server are app-scoped). */
    fun stopNearby() {
        discovery.stop()
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
            if (result is PairResult.Paired) refreshPaired()
        }
    }

    fun dismissPairing() = _state.update { it.copy(pairing = null) }

    /**
     * Removes a pairing. The peer is told to drop us too (best effort: it may be
     * offline, and the local unpair still stands), the local trust entry is
     * always removed, running transfers to it are cancelled, and the paired and
     * open-detail state is refreshed.
     */
    fun unpair(peer: PairedPeer) {
        viewModelScope.launch {
            val identity = IdentityHolder.identity
            val remoteOk = withContext(Dispatchers.IO) {
                var ok = false
                if (identity != null) {
                    ok = runCatching {
                        PeerClient(peer.host, peer.port, identity, peer.fingerprint).revokeTrust()
                    }.isSuccess
                }
                store.remove(peer.fingerprint)
                TransferManager.cancelForPeer(peer.fingerprint)
                ok
            }
            PeerService.diagnostics.record("[pairing] peer=${peer.fingerprint.take(8)} unpair source=phone remote=$remoteOk result=ok")
            _state.update { current ->
                val detail = current.detail
                if (detail != null && detail.peer.fingerprint.equals(peer.fingerprint, ignoreCase = true)) {
                    current.copy(detail = null)
                } else {
                    current
                }
            }
            refreshPaired()
        }
    }

    fun openPeer(peer: PairedPeer) {
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

    fun openShare(share: ShareItem) = loadTree(share, "")

    fun openPath(path: String) {
        val share = _state.value.detail?.openShare ?: return
        loadTree(share, path)
    }

    fun backToShares() {
        _state.update { current ->
            val detail = current.detail ?: return@update current
            current.copy(detail = detail.copy(openShare = null, tree = emptyList(), treePath = "", error = null))
        }
    }

    fun closePeer() = _state.update { it.copy(detail = null) }

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

    private fun isOnline(peer: PairedPeer): Boolean {
        val identity = IdentityHolder.identity ?: return false
        val started = System.currentTimeMillis()
        val short = peer.fingerprint.take(8)
        return try {
            val probe = ProbeClient(peer.host, peer.port, identity)
            probe.hello()
            val match = probe.observedFingerprint().equals(peer.fingerprint, ignoreCase = true)
            PeerService.diagnostics.record("[discovery] peer=$short hello addr=${peer.host}:${peer.port} result=${if (match) "online" else "mismatch"} elapsed=${System.currentTimeMillis() - started}ms")
            match
        } catch (e: Exception) {
            PeerService.diagnostics.record("[discovery] peer=$short hello addr=${peer.host}:${peer.port} result=offline elapsed=${System.currentTimeMillis() - started}ms error=${e.javaClass.simpleName}")
            false
        }
    }

    private fun resultMessage(result: PairResult): String = when (result) {
        is PairResult.Paired -> "Paired with ${result.peer.name.ifEmpty { "the device" }}."
        PairResult.Refused -> "The other device refused the request."
        PairResult.Expired -> "The pairing window expired. Ask for a new link."
        PairResult.Unreachable -> "Could not reach the device. Check it is on this network."
        PairResult.FingerprintMismatch -> "The device at that address is not the one the link promised."
        PairResult.InvalidLink -> "That is not a valid pairing link."
    }

    private fun friendly(t: Throwable): String = when (t) {
        is PeerStatusException -> if (t.code == 403) "This device is not permitted to browse that peer." else "The peer answered with an error (HTTP ${t.code})."
        else -> "Could not reach the device."
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
