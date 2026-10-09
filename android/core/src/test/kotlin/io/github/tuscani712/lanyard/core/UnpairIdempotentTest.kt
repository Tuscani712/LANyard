package io.github.tuscani712.lanyard.core

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.ServerSocket
import java.nio.file.Files

/**
 * Unpair notification is idempotent: the phone answers 200 even when it has
 * already dropped us, which is the end state we wanted and must count as
 * success, not failure. A peer we cannot reach at all is the only case that
 * reports "not notified".
 */
class UnpairIdempotentTest {

    private class Peer(label: String) : AutoCloseable {
        val identity: Identity = Identity.generate(label)
        private val trustFile = Files.createTempFile("lanyard-trust", ".json").toFile().also { it.delete() }
        val trust: TrustStore = JsonFileTrustStore(trustFile)
        val invites = PairInvites()
        val sessions = PairingSessions(selfFp = { identity.deviceId }, trust = trust)
        val diagnostics = ServerDiagnostics()
        private val spool = Files.createTempDirectory("lanyard-spool").toFile()
        private val receiver = InboxReceiver(spool, PushDestination { rel, _, _ -> rel }, { 1L shl 40 })
        private val server = PeerServer(
            sessions = sessions,
            receiver = receiver,
            invites = invites,
            isPaired = { trust.find(it) },
            onUnpair = { trust.remove(it) },
            diagnostics = diagnostics,
        )
        val port: Int = server.start(identity) { p ->
            JsonObject().apply {
                addProperty("device_id", identity.deviceId.take(16))
                addProperty("fingerprint", identity.deviceId)
                addProperty("name", label)
                addProperty("os", "android")
                addProperty("version", "test")
                addProperty("port", p)
            }
        }

        override fun close() = server.stop()
    }

    @Test
    @Timeout(60)
    fun revokeIsIdempotentWhenThePeerAlreadyDroppedUs() {
        Peer("Phone").use { phone ->
            val desktop = Identity.generate("Desktop")
            // The desktop is paired with the phone (as after a pairing).
            phone.trust.save(
                PairedPeer(desktop.deviceId, "Desktop", "127.0.0.1", phone.port, browse = true, push = true, pairedAt = 0),
            )
            val client = PeerClient("127.0.0.1", phone.port, desktop, phone.identity.deviceId)

            assertTrue(client.revokeTrustIdempotent(), "the first revoke must succeed")
            assertNull(phone.trust.find(desktop.deviceId), "the phone must drop the desktop")

            // Repeating the notification: the phone now answers an idempotent
            // 200 with no exception, so revokeTrust (which throws on non-2xx)
            // must not throw on the repeat either.
            client.revokeTrust()
            assertNull(phone.trust.find(desktop.deviceId), "a repeat revoke leaves the peer unpaired")
            assertTrue(client.revokeTrustIdempotent(), "a repeat revoke must still count as success")
        }
    }

    @Test
    @Timeout(60)
    fun unreachablePeerReportsNotNotified() {
        val closed = ServerSocket(0).also { it.close() }.localPort
        val desktop = Identity.generate("Desktop")
        val client = PeerClient("127.0.0.1", closed, desktop, "ab".repeat(32))
        assertFalse(client.revokeTrustIdempotent(), "an unreachable peer must report that it was not notified")
    }
}
