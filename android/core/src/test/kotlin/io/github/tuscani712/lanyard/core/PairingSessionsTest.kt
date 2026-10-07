package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** The responder-side pairing state machine. */
class PairingSessionsTest {
    private val trustFile = File.createTempFile("lanyard-trust", ".json").also { it.delete() }
    private val trust: TrustStore = JsonFileTrustStore(trustFile)
    private var now = 1_000_000L
    private val self = "aa".repeat(32)

    private fun sessions() = PairingSessions(selfFp = { self }, trust = trust, clock = { now })
    private fun nonce() = "11".repeat(16)

    @Test
    fun acceptThenConfirmWritesTrust() {
        val s = sessions()
        val peer = "bb".repeat(32)
        val v = s.createIncoming("pair", peer, "Bob", "bob-dev", "10.0.0.5", nonce(), Permissions(browse = true, push = true))
        assertEquals(PairingSessions.STATUS_PENDING, v.status)
        assertEquals(1, s.pending().size)
        assertTrue(s.accept(v.id))
        assertNull(trust.find(peer), "no trust entry before the initiator confirms")
        val active = s.confirm(v.id, peer)
        assertEquals(PairingSessions.STATUS_ACTIVE, active.status)
        val stored = trust.find(peer)
        assertNotNull(stored)
        assertTrue(stored!!.browse && stored.push)
    }

    @Test
    fun declineLeavesNothingAndRefusesConfirm() {
        val s = sessions()
        val peer = "cc".repeat(32)
        val v = s.createIncoming("pair", peer, "Cy", "cy", "h", nonce(), Permissions())
        s.decline(v.id)
        assertTrue(s.pending().isEmpty())
        assertNull(trust.find(peer))
        assertThrows(PeerHttpException::class.java) { s.confirm(v.id, peer) }
    }

    @Test
    fun confirmRequiresAcceptance() {
        val s = sessions()
        val peer = "dd".repeat(32)
        val v = s.createIncoming("pair", peer, "D", "d", "h", nonce(), Permissions())
        assertThrows(PeerHttpException::class.java) { s.confirm(v.id, peer) }
    }

    @Test
    fun replayConfirmRefused() {
        val s = sessions()
        val peer = "ee".repeat(32)
        val v = s.createIncoming("pair", peer, "E", "e", "h", nonce(), Permissions())
        s.accept(v.id)
        s.confirm(v.id, peer)
        assertThrows(PeerHttpException::class.java) { s.confirm(v.id, peer) }
    }

    @Test
    fun wrongPeerRefused() {
        val s = sessions()
        val v = s.createIncoming("pair", "ff".repeat(32), "F", "f", "h", nonce(), Permissions())
        val ex = assertThrows(PeerHttpException::class.java) { s.statusFor(v.id, "00".repeat(32)) }
        assertEquals(403, ex.code)
    }

    @Test
    fun onePendingPerPeer() {
        val s = sessions()
        val peer = "22".repeat(32)
        s.createIncoming("pair", peer, "P", "p", "h", nonce(), Permissions())
        val ex = assertThrows(PeerHttpException::class.java) {
            s.createIncoming("pair", peer, "P", "p", "h", nonce(), Permissions())
        }
        assertEquals(409, ex.code)
    }

    @Test
    fun threePendingTotalCap() {
        val s = sessions()
        repeat(3) { i ->
            s.createIncoming("connect", "%02x".format(i).repeat(32), "P$i", "p", "h", nonce(), Permissions())
        }
        val ex = assertThrows(PeerHttpException::class.java) {
            s.createIncoming("connect", "99".repeat(32), "P", "p", "h", nonce(), Permissions())
        }
        assertEquals(429, ex.code)
    }

    @Test
    fun pendingExpires() {
        val s = sessions()
        val peer = "33".repeat(32)
        val v = s.createIncoming("pair", peer, "P", "p", "h", nonce(), Permissions())
        now += PairingSessions.PAIRING_TTL_MS + 1
        assertTrue(s.pending().isEmpty())
        assertEquals(PairingSessions.STATUS_EXPIRED, s.statusFor(v.id, peer).status)
    }

    @Test
    fun sasMatchesTheAlgorithm() {
        val s = sessions()
        val peer = "44".repeat(32)
        val peerNonce = nonce()
        val v = s.createIncoming("pair", peer, "P", "p", "h", peerNonce, Permissions())
        assertEquals(Sas.code(self, peer, v.nonce, peerNonce), v.sas)
    }

    private val invites = PairInvites()

    @Test
    fun terminalSessionsArePurgedSoTheCapDoesNotFill() {
        val s = sessions()
        repeat(PairingSessions.MAX_SESSIONS) { i ->
            val peer = i.toString(16).padStart(64, '0')
            val v = s.createIncoming("connect", peer, "P", "p", "h", nonce(), Permissions())
            s.decline(v.id)
        }
        assertEquals(PairingSessions.MAX_SESSIONS, s.count())
        // Without the purge these terminals would fill the cap and this would 503.
        now += PairingSessions.TERMINAL_TTL_MS + 1
        val v = s.createIncoming("connect", "ff".repeat(32), "P", "p", "h", nonce(), Permissions())
        assertNotNull(v)
        assertTrue(s.count() < PairingSessions.MAX_SESSIONS)
    }

    @Test
    fun acceptedButUnconfirmedExpiresAndIsThenPurged() {
        val s = sessions()
        val peer = "12".repeat(32)
        val v = s.createIncoming("pair", peer, "P", "p", "h", nonce(), Permissions())
        assertTrue(s.accept(v.id))
        now += PairingSessions.PAIRING_TTL_MS + 1
        assertEquals(PairingSessions.STATUS_EXPIRED, s.statusFor(v.id, peer).status)
        now += PairingSessions.TERMINAL_TTL_MS + 1
        val ex = assertThrows(PeerHttpException::class.java) { s.statusFor(v.id, peer) }
        assertEquals(404, ex.code)
    }

    @Test
    fun rejectedRequestLeavesTheInviteUsable() {
        val s = sessions()
        repeat(3) { i ->
            s.createIncoming("connect", "%02x".format(i).repeat(32), "P$i", "p", "h", nonce(), Permissions())
        }
        val invite = invites.mint().token
        val ex = assertThrows(PeerHttpException::class.java) {
            s.createIncoming(
                "pair", "ab".repeat(32), "P", "p", "h", nonce(), Permissions(),
                consumeInvite = { invites.consume(invite) },
            )
        }
        assertEquals(429, ex.code)
        assertTrue(invites.consume(invite), "a cap rejection must not spend the one-time code")
    }

    @Test
    fun badInviteIsForbiddenAndNeverFallsBackToSas() {
        val s = sessions()
        val ex = assertThrows(PeerHttpException::class.java) {
            s.createIncoming(
                "pair", "cd".repeat(32), "P", "p", "h", nonce(), Permissions(),
                consumeInvite = { invites.consume("deadbeef") },
            )
        }
        assertEquals(403, ex.code)
    }
}
