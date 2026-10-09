package io.github.tuscani712.lanyard.core

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.ServerSocket
import java.nio.file.Files

/**
 * The listener keeps a stable port across launches so a paired desktop's stored
 * address stays valid, and only falls back to an ephemeral port when the
 * preferred one is taken.
 */
class PeerServerPreferredPortTest {

    private fun server(
        label: String,
        bindRetryMillis: Long = 0L,
        portHolder: (Int) -> String? = { null },
    ): PeerServer {
        val identity = Identity.generate(label)
        val trustFile = Files.createTempFile("lanyard-trust", ".json").toFile().also { it.delete() }
        val trust: TrustStore = JsonFileTrustStore(trustFile)
        val sessions = PairingSessions(selfFp = { identity.deviceId }, trust = trust)
        val spool = Files.createTempDirectory("lanyard-spool").toFile()
        val receiver = InboxReceiver(spool, PushDestination { rel, _, _ -> rel }, { 1L shl 40 })
        val srv = PeerServer(
            sessions = sessions,
            receiver = receiver,
            invites = PairInvites(),
            isPaired = { trust.find(it) },
            portHolder = portHolder,
        )
        srv.start(identity, preferredPort = preferred, bindRetryMillis = bindRetryMillis) { p ->
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
            assertFalse(srv.temporaryPort)
            assertNull(srv.portConflict)
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

    @Test
    @Timeout(30)
    fun aBusyPreferredPortReportsATemporaryFallback() {
        val first = server("Phone A")
        val second = server("Phone B", bindRetryMillis = 200)
        try {
            assertTrue(second.temporaryPort, "a busy configured port must be reported as temporary")
            assertEquals(preferred, second.portConflict?.configuredPort)
            assertNotEquals(preferred, second.port)
            assertEquals(
                "Using temporary port ${second.port} because $preferred is in use",
                second.portConflict?.message(second.port),
            )
        } finally {
            first.stop()
            second.stop()
        }
    }

    @Test
    @Timeout(30)
    fun aBusyPreferredPortIsRetriedBeforeFallingBack() {
        val first = server("Phone A")
        val waitMs = 500L
        val started = System.currentTimeMillis()
        val second = server("Phone B", bindRetryMillis = waitMs)
        val elapsed = System.currentTimeMillis() - started
        try {
            assertTrue(elapsed >= waitMs - 100, "the busy port should be retried for ~${waitMs}ms (took ${elapsed}ms)")
            assertTrue(second.temporaryPort)
        } finally {
            first.stop()
            second.stop()
        }
    }

    @Test
    @Timeout(30)
    fun theHoldingProcessIsReportedWhenResolvable() {
        val first = server("Phone A")
        val second = server("Phone B", bindRetryMillis = 0, portHolder = { "com.example.holder" })
        try {
            assertEquals("com.example.holder", second.portConflict?.holder)
            assertEquals(
                "Using temporary port ${second.port} because $preferred is in use (held by com.example.holder)",
                second.portConflict?.message(second.port),
            )
        } finally {
            first.stop()
            second.stop()
        }
    }

    @Test
    @Timeout(30)
    fun theNextStartRetriesTheConfiguredPort() {
        val first = server("Phone A")
        val busy = server("Phone B", bindRetryMillis = 0)
        try {
            assertEquals(preferred, first.port)
            assertTrue(busy.temporaryPort)
        } finally {
            busy.stop()
            first.stop()
        }
        // The holder is gone: the next start must try (and get) the configured
        // port again, never remembering the temporary one.
        val next = server("Phone C")
        try {
            assertEquals(preferred, next.port)
            assertFalse(next.temporaryPort)
        } finally {
            next.stop()
        }
    }
}
