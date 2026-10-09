package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * The peer-error mapping shared with the desktop (403 is never "unreachable").
 *
 * The auto-unpair trigger is deliberately narrower than the display wording: a
 * peer can deny pull/push (a permission prompt exists now), and that refusal
 * must never delete a good pairing.
 */
class PeerErrorsTest {

    @Test
    fun exactNotPaired403IsTheOnlyAutoUnpairTrigger() {
        for (body in listOf("not paired", "NOT PAIRED", "Not Paired")) {
            val e = PeerStatusException(403, """{"error":"$body"}""")
            assertTrue(PeerErrors.isNotPaired(e), "body=$body must mean the peer dropped us")
            assertEquals(PeerErrors.NOT_PAIRED, PeerErrors.userMessage(e))
        }
    }

    @Test
    fun permissionAndGeneric403sNeverAutoUnpairAndHaveTheirOwnWording() {
        // G3: each exact refusal maps to its own line and never to "Not paired".
        // None may trigger a local removal (the phone can deny pull/push without
        // unparing).
        val expected = mapOf(
            "push not permitted" to PeerErrors.PUSH_NOT_PERMITTED,
            "pull not permitted" to PeerErrors.PULL_NOT_PERMITTED,
            "text not permitted" to PeerErrors.TEXT_NOT_PERMITTED,
            "not permitted" to PeerErrors.NOT_PERMITTED,
            "denied by the user" to PeerErrors.DENIED_BY_USER,
        )
        for ((body, message) in expected) {
            val e = PeerStatusException(403, """{"error":"$body"}""")
            assertFalse(PeerErrors.isNotPaired(e), "body=$body must leave the pairing intact")
            assertEquals(message, PeerErrors.userMessage(e), "body=$body")
            assertFalse(PeerErrors.userMessage(e).contains("Not paired"), "body=$body must not read as Not paired")
        }
        // An unknown 403 word ("forbidden") keeps its own text rather than
        // masquerading as an unpair.
        val forbidden = PeerStatusException(403, """{"error":"forbidden"}""")
        assertFalse(PeerErrors.isNotPaired(forbidden))
        assertEquals("forbidden", PeerErrors.userMessage(forbidden))
        // An empty body is a generic refusal, never an unpair.
        val empty = PeerStatusException(403, "")
        assertFalse(PeerErrors.isNotPaired(empty), "an empty 403 body must leave the pairing intact")
        assertEquals(PeerErrors.NOT_PAIRED, PeerErrors.userMessage(empty))
        // An odd/unparseable body that is not the exact phrase is not an unpair.
        val odd = PeerStatusException(403, """{"error":""}""")
        assertFalse(PeerErrors.isNotPaired(odd), "an odd 403 body must leave the pairing intact")
        assertEquals(PeerErrors.NOT_PAIRED, PeerErrors.userMessage(odd))
    }

    @Test
    fun plainTextPermission403IsNotAnUnpair() {
        val e = PeerStatusException(403, "pull not permitted")
        assertFalse(PeerErrors.isNotPaired(e), "a raw permission refusal must leave the pairing intact")
        assertEquals(PeerErrors.PULL_NOT_PERMITTED, PeerErrors.userMessage(e))
    }

    @Test
    fun textNotPermittedHasItsOwnWordingAndIsNotAnUnpair() {
        val e = PeerStatusException(403, """{"error":"text not permitted"}""")
        assertFalse(PeerErrors.isNotPaired(e), "a text refusal must leave the pairing intact")
        assertEquals(PeerErrors.TEXT_NOT_PERMITTED, PeerErrors.userMessage(e))
        assertFalse(PeerErrors.userMessage(e).contains("Not paired"))
    }

    @Test
    fun everyPermissionRefusalNeverReadsAsNotPaired() {
        // The strict auto-unpair predicate is exact: no permission refusal body
        // (in any casing/whitespace) may ever fire it.
        for (body in listOf(
            "push not permitted", "pull not permitted", "text not permitted", "not permitted",
            "denied by the user", "the transfer was declined", "the message was declined",
        )) {
            val e = PeerStatusException(403, """{"error":"$body"}""")
            assertFalse(PeerErrors.isNotPaired(e), "body=$body")
        }
    }

    @Test
    fun specific403KeepsThePeersOwnReasonAndIsNotUnpaired() {
        val e = PeerStatusException(403, """{"error":"The other device declined the transfer."}""")
        assertFalse(PeerErrors.isNotPaired(e), "a specific 403 leaves the pairing intact")
        assertEquals("The other device declined the transfer.", PeerErrors.userMessage(e))
    }

    @Test
    fun phrasesContainingPairedDoNotMatch() {
        for (body in listOf("already paired", "this device is not paired yet", "paired")) {
            val e = PeerStatusException(403, """{"error":"$body"}""")
            assertFalse(PeerErrors.isNotPaired(e), "body=$body must not be read as an unpair")
        }
    }

    @Test
    fun non403SurfacesTheErrorReason() {
        val e = PeerStatusException(500, """{"error":"could not write the file"}""")
        assertFalse(PeerErrors.isNotPaired(e))
        assertEquals("could not write the file", PeerErrors.userMessage(e))
    }

    @Test
    fun payloadTooLargeReadsAsAnActionableMessage() {
        val e = PeerStatusException(413, """{"error":"body too large"}""")
        assertFalse(PeerErrors.isNotPaired(e))
        assertEquals(PeerErrors.TOO_LARGE, PeerErrors.userMessage(e))
    }

    @Test
    fun transportFailureIsNotReportedAsNotPaired() {
        val e = RuntimeException("connect: connection refused")
        assertFalse(PeerErrors.isNotPaired(e))
        assertEquals("connect: connection refused", PeerErrors.userMessage(e))
    }

    @Test
    fun offlineReasonDistinguishesTimeoutFromRefusal() {
        assertEquals(
            PeerErrors.OFFLINE_TIMEOUT,
            PeerErrors.offlineReason(SocketTimeoutException("Read timed out")),
            "a timed-out probe points at a firewall",
        )
        assertEquals(
            PeerErrors.OFFLINE_REFUSED,
            PeerErrors.offlineReason(ConnectException("Connection refused")),
            "a refused connect means the app is likely closed",
        )
        assertEquals(
            PeerErrors.OFFLINE_UNREACHABLE,
            PeerErrors.offlineReason(IOException("no route to host")),
            "any other transport error is neutral",
        )
        assertEquals(
            PeerErrors.OFFLINE_UNREACHABLE,
            PeerErrors.offlineReason(null),
            "a missing throwable is neutral, never a crash",
        )
    }

    @Test
    fun offlineReasonCodeIsAShortGreppableToken() {
        assertEquals("timeout", PeerErrors.offlineReasonCode(SocketTimeoutException()))
        assertEquals("refused", PeerErrors.offlineReasonCode(ConnectException()))
        assertEquals("unreachable", PeerErrors.offlineReasonCode(IOException("boom")))
    }
}
