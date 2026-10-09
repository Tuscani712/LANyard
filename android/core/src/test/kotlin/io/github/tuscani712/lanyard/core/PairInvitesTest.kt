package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The phone's one-time QR pairing invites. */
class PairInvitesTest {
    private var now = 1_000_000L
    private fun invites() = PairInvites(ttlMillis = 1000, clock = { now })

    @Test
    fun tokenIs128BitHex() {
        val t = invites().mint().token
        assertEquals(32, t.length)
        assertTrue(t.all { it in "0123456789abcdef" })
    }

    @Test
    fun consumedOnFirstUseOnly() {
        val p = invites()
        val inv = p.mint()
        assertTrue(p.consume(inv.token), "first use should succeed")
        assertFalse(p.consume(inv.token), "a reused invite must fail")
    }

    @Test
    fun expiredIsRejected() {
        val p = invites()
        val inv = p.mint()
        now += 2000
        assertFalse(p.consume(inv.token))
    }

    @Test
    fun validUntilExpiryWithoutConsuming() {
        val p = invites()
        val inv = p.mint()
        assertNotNull(p.valid(inv.token))
        assertNotNull(p.valid(inv.token), "valid must not consume")
        now += 1001
        assertNull(p.valid(inv.token))
    }

    @Test
    fun unknownTokenRejected() {
        val p = invites()
        assertFalse(p.consume("deadbeef"))
        assertNull(p.valid("deadbeef"))
    }
}
