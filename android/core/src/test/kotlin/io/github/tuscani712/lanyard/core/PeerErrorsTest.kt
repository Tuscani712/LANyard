package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
    fun permissionAndGeneric403sNeverAutoUnpairButReadAsNotPaired() {
        // Permission refusals are the dangerous ones: the phone can deny pull or
        // push without unparing, so none of these may trigger a local removal.
        val displayOnly = listOf("not permitted", "push not permitted", "pull not permitted", "forbidden")
        for (body in displayOnly) {
            val e = PeerStatusException(403, """{"error":"$body"}""")
            assertFalse(PeerErrors.isNotPaired(e), "body=$body must leave the pairing intact")
            assertEquals(PeerErrors.NOT_PAIRED, PeerErrors.userMessage(e), "body=$body")
        }
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
        assertEquals(PeerErrors.NOT_PAIRED, PeerErrors.userMessage(e))
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
    fun transportFailureIsNotReportedAsNotPaired() {
        val e = RuntimeException("connect: connection refused")
        assertFalse(PeerErrors.isNotPaired(e))
        assertEquals("connect: connection refused", PeerErrors.userMessage(e))
    }
}
