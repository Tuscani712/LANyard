package io.github.tuscani712.lanyard.core

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

/**
 * The listener keeps a stable port across launches so a paired desktop's stored
 * address stays valid, and only falls back to an ephemeral port when the
 * preferred one is taken.
 */
class PeerServerPreferredPortTest {

    private fun server(label: String): PeerServer {
        val identity = Identity.generate(label)
        val trustFile = Files.createTempFile("lanyard-trust", ".json").toFile().also { it.delete() }
        val trust: TrustStore = JsonFileTrustStore(trustFile)
        val sessions = PairingSessions(selfFp = { identity.deviceId }, trust = trust)
        val spool = Files.createTempDirectory("lanyard-spool").toFile()
        val receiver = InboxReceiver(spool, PushDestination { rel, _, _ -> rel }, { 1L shl 40 })
        val srv = PeerServer(sessions = sessions, receiver = receiver, invites = PairInvites(), isPaired = { trust.find(it) })
        srv.start(identity, preferredPort = preferred) { p ->
            JsonObject().apply {
                addProperty("device_id", identity.deviceId.take(16))
                addProperty("fingerprint", identity.deviceId)
                addProperty("name", label)
                addProperty("os", "android")
                addProperty("version", "test")
                addProperty("port", p)
            }
        }
        return srv
    }

    private val preferred = ServerSocket(0).use { it.localPort }

    @Test
    @Timeout(30)
    fun thePreferredPortIsUsedWhenFree() {
        val srv = server("Phone")
        try {
            assertEquals(preferred, srv.port, "a free preferred port must be bound")
        } finally {
            srv.stop()
        }
    }

    @Test
    @Timeout(30)
    fun aTakenPreferredPortFallsBackToAnEphemeralOne() {
        val first = server("Phone A")
        val second = server("Phone B")
        try {
            assertEquals(preferred, first.port)
            assertNotEquals(preferred, second.port, "the second listener must fall back, not fail to start")
            assertTrue(second.port in 1..65535)
        } finally {
            first.stop()
            second.stop()
        }
    }
}
