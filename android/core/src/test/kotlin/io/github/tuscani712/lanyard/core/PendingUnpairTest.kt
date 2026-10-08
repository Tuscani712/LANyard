package io.github.tuscani712.lanyard.core

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

/**
 * A device we unpaired while it was offline is remembered and the notification
 * is retried when that device is next seen. Mirrors the desktop's pending-unpair.
 */
class PendingUnpairTest {

    private fun store(): PendingUnpairStore =
        JsonFilePendingUnpairStore(Files.createTempFile("lanyard-pending", ".json").toFile().also { it.delete() })

    private class Peer(val label: String) : AutoCloseable {
        val identity: Identity = Identity.generate(label)
        private val trustFile = Files.createTempFile("lanyard-trust", ".json").toFile().also { it.delete() }
        val trust: TrustStore = JsonFileTrustStore(trustFile)
        private val invites = PairInvites()
        private val sessions = PairingSessions(selfFp = { identity.deviceId }, trust = trust)
        private val spool = Files.createTempDirectory("lanyard-spool").toFile()
        private val receiver = InboxReceiver(spool, PushDestination { rel, _, _ -> rel }, { 1L shl 40 })
        private val server = PeerServer(
            sessions = sessions,
            receiver = receiver,
            invites = invites,
            isPaired = { trust.find(it) },
            onUnpair = { trust.remove(it) },
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
    fun recordIsPersistedAndRemoved() {
        val s = store()
        val entry = PendingUnpair("ab".repeat(32), "Desk", "10.0.0.5", 47800, 1L)
        s.upsert(entry)
        assertEquals(entry.copy(fingerprint = entry.fingerprint.lowercase()), s.find(entry.fingerprint.uppercase()))
        assertEquals(1, s.list().size)
        s.remove(entry.fingerprint.uppercase())
        assertEquals(null, s.find(entry.fingerprint))
    }

    @Test
    @Timeout(60)
    fun retryDeliversWhenThePeerIsReachable() {
        Peer("Phone").use { phone ->
            val desktop = Identity.generate("Desktop")
            // The phone still believes the desktop is paired.
            phone.trust.save(
                PairedPeer(desktop.deviceId, "Desktop", "127.0.0.1", phone.port, browse = true, push = true, pairedAt = 0),
            )
            val s = store()
            // We (the desktop) unpaired the phone while it was offline.
            s.upsert(PendingUnpair(phone.identity.deviceId, "Phone", "127.0.0.1", phone.port, 0))

            val delivered = UnpairRetry.retry(s, desktop)

            assertEquals(listOf(phone.identity.deviceId), delivered)
            assertTrue(s.list().isEmpty(), "a delivered notification must be removed")
            assertEquals(null, phone.trust.find(desktop.deviceId), "the peer must have dropped us")
        }
    }

    @Test
    @Timeout(60)
    fun retryKeepsTheRecordWhenThePeerIsOffline() {
        val closed = ServerSocket(0).also { it.close() }.localPort
        val desktop = Identity.generate("Desktop")
        val s = store()
        s.upsert(PendingUnpair("ab".repeat(32), "Desk", "127.0.0.1", closed, 0))

        val delivered = UnpairRetry.retry(s, desktop)

        assertTrue(delivered.isEmpty())
        assertNotNull(s.find("ab".repeat(32)), "an unreachable peer keeps the record for a later retry")
    }

    @Test
    fun matchByShortIdFindsThePendingRecord() {
        val s = store()
        val fp = "0123456789abcdef" + "ff".repeat(24)
        s.upsert(PendingUnpair(fp, "Desk", "", 0, 0))
        assertNotNull(UnpairRetry.matchByShortId(s, SelfFilter.ownShortId(fp)))
        assertEquals(null, UnpairRetry.matchByShortId(s, "ffffffffffffffff"))
    }
}
