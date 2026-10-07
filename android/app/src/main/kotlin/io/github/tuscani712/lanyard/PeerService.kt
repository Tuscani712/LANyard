package io.github.tuscani712.lanyard

import android.content.Context
import com.google.gson.JsonObject
import io.github.tuscani712.lanyard.core.Identity
import io.github.tuscani712.lanyard.core.IncomingRequest
import io.github.tuscani712.lanyard.core.JsonFileTrustStore
import io.github.tuscani712.lanyard.core.PairInvites
import io.github.tuscani712.lanyard.core.PairLink
import io.github.tuscani712.lanyard.core.PairingSessions
import io.github.tuscani712.lanyard.core.PeerServer
import io.github.tuscani712.lanyard.core.TrustStore
import io.github.tuscani712.lanyard.net.NetAddrs
import io.github.tuscani712.lanyard.net.NsdAdvertiser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The app-scoped home of the phone-side peer server and the shared trust store.
 *
 * Lifetime: [start] is called while the app is in the foreground (any screen)
 * and [stop] when it is backgrounded, so the phone is discoverable and online
 * to a desktop on any screen, and offline when the app is not showing. There is
 * deliberately no background service in this milestone.
 *
 * It also owns the one-time QR invites and the incoming pairing prompts, and
 * exposes them as [pending] for the global accept/decline dialog.
 */
object PeerService {
    private var initialized = false
    private lateinit var trustStore: TrustStore
    private lateinit var sessionStore: PairingSessions
    private lateinit var inviteStore: PairInvites
    private var server: PeerServer? = null
    private var advertiser: NsdAdvertiser? = null
    private var appVersion: String = ""
    private var currentInvite: PairInvites.Invite? = null

    private val _pending = MutableStateFlow<List<IncomingRequest>>(emptyList())
    val pending: StateFlow<List<IncomingRequest>> = _pending.asStateFlow()

    /** The shared paired-peer store (also used by the Devices screen). */
    val trust: TrustStore get() = trustStore

    fun init(context: Context) {
        if (initialized) return
        trustStore = JsonFileTrustStore(File(context.filesDir, "trust/peers.json"))
        sessionStore = PairingSessions(
            selfFp = { IdentityHolder.identity?.deviceId.orEmpty() },
            trust = trustStore,
            onChange = { publish() },
        )
        inviteStore = PairInvites()
        appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
        initialized = true
    }

    /** Starts advertising and the peer server; safe to call on every foreground. */
    fun start(context: Context) {
        if (!initialized) init(context)
        if (server != null) return
        val id = IdentityHolder.identity ?: return
        sessionStore.clear() // fresh prompts for this foreground session
        val srv = PeerServer(sessionStore, trustStore, inviteStore)
        val port = try {
            srv.start(id) { boundPort -> hello(id, boundPort) }
        } catch (_: Exception) {
            return
        }
        server = srv
        advertiser = NsdAdvertiser(context).also {
            it.start(id.deviceId.take(16), txt(id, port), port)
        }
    }

    fun stop() {
        server?.stop()
        server = null
        advertiser?.stop()
        advertiser = null
    }

    fun accept(id: String) {
        sessionStore.accept(id)
        publish()
    }

    fun decline(id: String) {
        sessionStore.decline(id)
        publish()
    }

    /**
     * The `lanyard://pair?...` link for this device's QR code, or null until the
     * server is listening. The one-time invite is reused while still valid so the
     * code does not change on every refresh.
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
