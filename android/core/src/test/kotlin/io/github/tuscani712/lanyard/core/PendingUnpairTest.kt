package io.github.tuscani712.lanyard.core

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
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

    @Test
    fun aDeliveryGenerationBlocksARacingRearm() {
        val s = store()
        val fp = "ab".repeat(32)
        s.upsert(PendingUnpair(fp, "Desk", "10.0.0.5", 47800, 0))

        // A retry reads the record and its generation, then a sibling delivers
        // and removes it before the first can attach its fresh address.
        val match = UnpairRetry.matchByShortId(s, SelfFilter.ownShortId(fp))!!
        s.remove(fp) // the sibling succeeded

        assertFalse(
            s.upsertIfCurrent(match.entry.copy(host = "10.0.0.9", port = 5555), match.generation),
            "a stale generation must not re-arm the delivered record",
        )
        assertEquals(null, s.find(fp), "the record must stay gone")
    }

    @Test
    fun aLoneFailureStillArmsAndARefreshIsAllowed() {
        val s = store()
        val fp = "cd".repeat(32)
        val seen = s.deliveryGeneration(fp)
        assertTrue(s.upsertIfCurrent(PendingUnpair(fp, "Desk", "", 0, 0), seen), "a lone failure must arm")
        assertNotNull(s.find(fp))
        // No sibling delivered, so refreshing the address with the same
        // generation still succeeds.
        assertTrue(s.upsertIfCurrent(PendingUnpair(fp, "Desk", "10.0.0.9", 5555, 0), seen))
        assertEquals("10.0.0.9", s.find(fp)?.host)
    }

    @Test
    fun aRevokeQueuedBeforeARepairIsRefusedNotDelivered() {
        val fp = "ab".repeat(32)
        val trustFile = Files.createTempFile("lanyard-trust", ".json").toFile().also { it.delete() }
        val trust = JsonFileTrustStore(trustFile)
        // The peer was re-paired AFTER the unpair was queued: pairedAt > queuedAt.
        trust.save(PairedPeer(fp, "Desk", "127.0.0.1", 1, browse = true, push = true, pairedAt = 2_000))
        val s = store()
        val closed = ServerSocket(0).also { it.close() }.localPort
        s.upsert(PendingUnpair(fp, "Desk", "127.0.0.1", closed, queuedAt = 1_000))

        val delivered = UnpairRetry.retry(s, Identity.generate("Android"), trust)

        assertTrue(delivered.isEmpty(), "a stale revoke must never be delivered")
        assertEquals(null, s.find(fp), "the stale record is dropped so it cannot fire later")
        assertNotNull(trust.find(fp), "the fresh pairing must survive")
    }

    @Test
    fun rePairAfterAnOfflineUnpairSurvivesTheRetry() {
        val a = Identity.generate("Desktop")
        val b = Identity.generate("Phone")
        val trustFile = Files.createTempFile("lanyard-trust", ".json").toFile().also { it.delete() }
        val trust = JsonFileTrustStore(trustFile)
        val pending = store()
        val sessions = PairingSessions(
            selfFp = { b.deviceId },
            trust = trust,
            clock = { 10_000L },
            onPaired = { fp -> pending.remove(fp) },
        )
        // They were paired, then B unpaired A while A was offline.
        trust.save(PairedPeer(a.deviceId, "Desktop", "127.0.0.1", 1, browse = true, push = true, pairedAt = 1_000))
        trust.remove(a.deviceId)
        pending.upsert(PendingUnpair(a.deviceId, "Desktop", "127.0.0.1", 1, queuedAt = 2_000))

        // A re-pairs to B; B confirms.
        val v = sessions.createIncoming("pair", a.deviceId, "Desktop", "desk", "127.0.0.1", "11".repeat(16), Permissions(browse = true, push = true))
        sessions.accept(v.id, Permissions(browse = true, push = true))
        sessions.confirm(v.id, a.deviceId)

        // A returns: the retry must not revoke the fresh pairing.
        val delivered = UnpairRetry.retry(pending, b, trust)
        assertTrue(delivered.isEmpty(), "the stale unpair was already forgotten")
        assertTrue(pending.list().isEmpty(), "a successful re-pair clears the queued unpair")
        assertNotNull(trust.find(a.deviceId), "the freshly paired device must survive the retry")
    }

    @Test
    @Timeout(60)
    fun aReachableSignalDeliversWhenThePeerReturns() {
        Peer("Phone").use { phone ->
            val desktop = Identity.generate("Desktop")
            // The phone still believes the desktop is paired.
            phone.trust.save(
                PairedPeer(desktop.deviceId, "Desktop", "127.0.0.1", phone.port, browse = true, push = true, pairedAt = 0),
            )
            val s = store()
            s.upsert(PendingUnpair(phone.identity.deviceId, "Phone", "127.0.0.1", phone.port, queuedAt = 5))
            var now = 10_000L
            val retries = PendingUnpairRetry(s, { desktop }, trust = null, clock = { now })

            // The peer is reachable: the signal delivers at once, not on the timer.
            val delivered = retries.onPeerReachable()

            assertEquals(listOf(phone.identity.deviceId), delivered)
            assertTrue(s.list().isEmpty())
            assertEquals(null, phone.trust.find(desktop.deviceId), "the peer must have dropped us")
            // With nothing pending, the timer stays quiet.
            now += PendingUnpairRetry.RETRY_INTERVAL_MS * 2
            assertTrue(retries.tick().isEmpty())
        }
    }
}
