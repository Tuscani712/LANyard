package io.github.tuscani712.lanyard.core

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Files

/**
 * A pinned [PeerClient] that receives a generic 403 "not paired" notifies the
 * app (so the stale local pairing can be dropped). A revoke we initiated
 * ourselves must not, or a deliberate unpair would be misread as the peer
 * unparing us.
 */
class PeerClientNotPairedTest {

    private class Server(private val isPaired: (String) -> PairedPeer? = { null }) : AutoCloseable {
        val identity: Identity = Identity.generate("Phone")
        private val trustFile = Files.createTempFile("lanyard-trust", ".json").toFile().also { it.delete() }
        private val trust: TrustStore = JsonFileTrustStore(trustFile)
        private val invites = PairInvites()
        private val sessions = PairingSessions(selfFp = { identity.deviceId }, trust = trust)
        private val spool = Files.createTempDirectory("lanyard-spool").toFile()
        private val receiver = InboxReceiver(spool, PushDestination { rel, _, _ -> rel }, { 1L shl 40 })
        private val shares = ShareServer(object : ShareSource {
            override fun list(): List<ShareInfo> = emptyList()
            override fun children(shareId: String, rel: String): List<ShareChild>? = emptyList()
            override fun resolve(shareId: String, rel: String): ResolvedFile? = null
            override fun ended(shareId: String): String? = null
        })
        private val server = PeerServer(
            sessions = sessions,
            receiver = receiver,
            invites = invites,
            isPaired = isPaired, // by default nobody is paired: every data request is 403
            shares = shares,
        )
        val port: Int = server.start(identity) { p ->
            JsonObject().apply {
                addProperty("device_id", identity.deviceId.take(16))
                addProperty("fingerprint", identity.deviceId)
                addProperty("name", "Phone")
                addProperty("os", "android")
                addProperty("version", "test")
                addProperty("port", p)
            }
        }

        override fun close() = server.stop()
    }

    @Test
    @Timeout(60)
    fun genericNotPaired403NotifiesTheApp() {
        Server().use { server ->
            val client = Identity.generate("Desktop")
            val previous = PeerClient.onNotPaired
            var notified: String? = null
            PeerClient.onNotPaired = { notified = it }
            try {
                val ex = runCatching {
                    PeerClient("127.0.0.1", server.port, client, server.identity.deviceId).listShares()
                }.exceptionOrNull()
                check(ex is PeerStatusException && ex.code == 403) { "expected a 403, got $ex" }
                assertEquals(server.identity.deviceId, notified, "the app must be told which peer dropped us")
            } finally {
                PeerClient.onNotPaired = previous
            }
        }
    }

    @Test
    @Timeout(60)
    fun pullNotPermitted403DoesNotNotifyOrUnpair() {
        val client = Identity.generate("Desktop")
        // The peer is paired and pinned, but it denies pull. That refusal is a
        // permission answer, not an unpair, so the local pairing must survive.
        Server { fp ->
            PairedPeer(fp, "Phone", "127.0.0.1", 0, browse = false, push = false, pairedAt = 0)
        }.use { server ->
            val previous = PeerClient.onNotPaired
            var notified: String? = null
            PeerClient.onNotPaired = { notified = it }
            try {
                val ex = runCatching {
                    PeerClient("127.0.0.1", server.port, client, server.identity.deviceId).listShares()
                }.exceptionOrNull()
                check(ex is PeerStatusException && ex.code == 403) { "expected a 403, got $ex" }
                // The wording is generic for display, but the pairing is untouched.
                assertEquals(PeerErrors.NOT_PAIRED, PeerErrors.userMessage(ex))
                assertNull(notified, "a pull-permission refusal must not remove the pairing")
            } finally {
                PeerClient.onNotPaired = previous
            }
        }
    }

    @Test
    @Timeout(60)
    fun unpinnedFingerprintNeverNotifies() {
        Server().use { server ->
            val client = Identity.generate("Desktop")
            val previous = PeerClient.onNotPaired
            var notified: String? = null
            PeerClient.onNotPaired = { notified = it }
            try {
                // A fingerprint that does not match the peer's certificate fails
                // the mTLS handshake, so no 403 is ever seen from an unpinned id.
                runCatching {
                    PeerClient("127.0.0.1", server.port, client, "00".repeat(32)).listShares()
                }
                assertNull(notified, "an unverified, unpinned peer must never remove a pairing")
            } finally {
                PeerClient.onNotPaired = previous
            }
        }
    }

    @Test
    @Timeout(60)
    fun ourOwnRevokeDoesNotNotify() {
        Server().use { server ->
            val client = Identity.generate("Desktop")
            val previous = PeerClient.onNotPaired
            var notified: String? = null
            PeerClient.onNotPaired = { notified = it }
            try {
                // The server answers 403 "not paired"; the client treats it as a
                // successful idempotent revoke and must not report an unpair.
                PeerClient("127.0.0.1", server.port, client, server.identity.deviceId).revokeTrustIdempotent()
                assertNull(notified, "a revoke we initiated must not look like the peer unpaired us")
            } finally {
                PeerClient.onNotPaired = previous
            }
        }
    }
}
