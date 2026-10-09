package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The "is this me?" rule for discovery: the phone drops its own advertised
 * short id but keeps everyone else, and an unknown own id hides nothing.
 */
class SelfFilterTest {
    private val own = "4d635a83f4033d53"

    @Test
    fun dropsTheOwnId() {
        assertTrue(SelfFilter.isSelf(own, own))
    }

    @Test
    fun keepsOtherIds() {
        assertFalse(SelfFilter.isSelf("aaaaaaaaaaaaaaaa", own))
        assertFalse(SelfFilter.isSelf("4d635a83f4033d54", own))
    }

    @Test
    fun emptyOwnIdDropsNothing() {
        assertFalse(SelfFilter.isSelf(own, ""))
        assertFalse(SelfFilter.isSelf("", ""))
        assertFalse(SelfFilter.isSelf("bbbbbbbbbbbbbbbb", "   "))
    }

    @Test
    fun ownShortIdIsTrimmedToSixteenHex() {
        assertEquals(own, SelfFilter.ownShortId("  $own" + "4047c031c72aae11  "))
        assertEquals("", SelfFilter.ownShortId("   "))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertTrue(SelfFilter.isSelf("4D635A83F4033D53", own))
    }
}
