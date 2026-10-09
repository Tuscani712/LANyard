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
        assertTrue(s.accept(v.id, Permissions(browse = true, push = true)))
        assertNull(trust.find(peer), "no trust entry before the initiator confirms")
        val active = s.confirm(v.id, peer)
        assertEquals(PairingSessions.STATUS_ACTIVE, active.status)
        val stored = trust.find(peer)
        assertNotNull(stored)
        assertEquals(Permission.ALLOW, stored!!.browse)
        assertEquals(Permission.ALLOW, stored.push)
    }

    @Test
    fun browseOnlyAcceptStoresNoPush() {
        val s = sessions()
        val peer = "0a".repeat(32)
        val v = s.createIncoming(
            "pair", peer, "Desk", "desk", "10.0.0.5", nonce(),
            Permissions(browse = true, push = true, pushMaxBytes = 4096, askOver = 1024),
        )
        // The person allows browse but leaves push off.
        assertTrue(s.accept(v.id, Permissions(browse = true, push = false)))
        s.confirm(v.id, peer)
        val stored = trust.find(peer)!!
        assertEquals(Permission.ALLOW, stored.browse, "browse must be granted")
        // The person left push unchecked: the new default is Ask, not off.
        assertEquals(Permission.ASK, stored.push, "an unchecked push becomes Ask")
        assertEquals(0L, stored.pushMaxBytes, "no push limit when push is not granted")
        assertEquals(0L, stored.askOver, "no ask-over when push is not granted")
    }

    @Test
    fun acceptWithPushStoresTheRequestedSizeLimits() {
        val s = sessions()
        val peer = "0b".repeat(32)
        val v = s.createIncoming(
            "pair", peer, "Desk", "desk", "10.0.0.5", nonce(),
            Permissions(browse = true, push = true, pushMaxBytes = 8192, askOver = 2048),
        )
        assertTrue(s.accept(v.id, Permissions(browse = true, push = true)))
        s.confirm(v.id, peer)
        val stored = trust.find(peer)!!
        assertEquals(Permission.ALLOW, stored.browse)
        assertEquals(Permission.ALLOW, stored.push)
        assertEquals(8192L, stored.pushMaxBytes)
        assertEquals(2048L, stored.askOver)
    }

    @Test
    fun acceptCannotWidenWhatThePeerRequested() {
        val s = sessions()
        val peer = "0c".repeat(32)
        val v = s.createIncoming("pair", peer, "Desk", "desk", "h", nonce(), Permissions(browse = true, push = false))
        // The phone cannot grant a permission the peer never requested.
        s.accept(v.id, Permissions(browse = true, push = true))
        s.confirm(v.id, peer)
        val stored = trust.find(peer)!!
        assertEquals(Permission.ALLOW, stored.browse)
        assertEquals(Permission.NEVER, stored.push, "push must not be granted beyond the request")
    }

    @Test
    fun refuseStoresNothing() {
        val s = sessions()
        val peer = "0d".repeat(32)
        val v = s.createIncoming("pair", peer, "Desk", "desk", "h", nonce(), Permissions(browse = true, push = true))
        s.decline(v.id)
        assertNull(trust.find(peer), "a refused request must not be stored")
        assertEquals(PairingSessions.STATUS_REJECTED, s.statusFor(v.id, peer).status)
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
    fun replayConfirmIsNoOpSuccess() {
        val s = sessions()
        val peer = "ee".repeat(32)
        val v = s.createIncoming("pair", peer, "E", "e", "h", nonce(), Permissions())
        s.accept(v.id, Permissions())
        val first = s.confirm(v.id, peer)
        assertEquals(PairingSessions.STATUS_ACTIVE, first.status)
        assertNotNull(trust.find(peer))
        // A replayed confirm is a no-op success, not an error, and does not
        // write a second trust entry or change the active session.
        val replay = s.confirm(v.id, peer)
        assertEquals(PairingSessions.STATUS_ACTIVE, replay.status)
        assertNotNull(trust.find(peer))
    }

    @Test
    fun pairConfirmForgetsPendingUnpairAndConnectDoesNot() {
        val cleared = ArrayList<String>()
        val s = PairingSessions(
            selfFp = { self },
            trust = trust,
            clock = { now },
            onPaired = { cleared.add(it) },
        )
        val peer = "6a".repeat(32)
        val v = s.createIncoming("pair", peer, "P", "p", "h", nonce(), Permissions())
        s.accept(v.id, Permissions())
        s.confirm(v.id, peer)
        assertEquals(listOf(peer), cleared, "a successful pair confirm must forget a queued unpair")

        // A connect-mode confirm stores nothing and must not clear anything.
        val other = "6b".repeat(32)
        val c = s.createIncoming("connect", other, "C", "c", "h", nonce(), Permissions())
        s.accept(c.id, Permissions())
        s.confirm(c.id, other)
        assertEquals(listOf(peer), cleared, "a connect confirm is not a re-pair")
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
        // Connect requests keep the flood cap; only a fresh pair request supersedes.
        s.createIncoming("connect", peer, "P", "p", "h", nonce(), Permissions())
        val ex = assertThrows(PeerHttpException::class.java) {
            s.createIncoming("connect", peer, "P", "p", "h", nonce(), Permissions())
        }
        assertEquals(409, ex.code)
    }

    @Test
    fun rePairSupersedesALeftoverPendingRequest() {
        val s = sessions()
        val peer = "77".repeat(32)
        val first = s.createIncoming("pair", peer, "P", "p", "h", nonce(), Permissions())
        // A newer pair request from the same device replaces the stale one
        // instead of being refused, so a re-pair after an abandoned handshake
        // is never trapped by the old prompt.
        val second = s.createIncoming("pair", peer, "P", "p", "h", nonce(), Permissions())
        assertTrue(second.id != first.id, "the newer request must be a new session")
        val pendingForPeer = s.pending().count { it.peerFp == peer }
        assertEquals(1, pendingForPeer, "only the newer pair request remains pending")
    }

    @Test
    fun connectingWhileAPairRequestIsPendingIsStillCapped() {
        val s = sessions()
        val peer = "78".repeat(32)
        s.createIncoming("pair", peer, "P", "p", "h", nonce(), Permissions())
        // A connect request is not a re-pair and keeps the one-pending-per-peer rule.
        val ex = assertThrows(PeerHttpException::class.java) {
            s.createIncoming("connect", peer, "P", "p", "h", nonce(), Permissions())
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
        assertTrue(s.accept(v.id, Permissions()))
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
