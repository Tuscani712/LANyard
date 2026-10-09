package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The user-owned peer port: validation boundaries, parse and persistence policy. */
class PeerPortTest {

    @Test
    fun acceptsTheBoundaries() {
        assertNull(PeerPort.error("1024"))
        assertNull(PeerPort.error("65535"))
        assertNull(PeerPort.error("47800"))
    }

    @Test
    fun rejectsValuesOutsideTheRange() {
        assertNotNull(PeerPort.error("1023"), "below the minimum must be refused")
        assertNotNull(PeerPort.error("65536"), "above the maximum must be refused")
        assertNotNull(PeerPort.error("0"), "0 is not a user port; blank means automatic")
        assertNotNull(PeerPort.error("999999"))
    }

    @Test
    fun rejectsNonNumbers() {
        assertNotNull(PeerPort.error("abc"))
        assertNotNull(PeerPort.error("47p800"))
    }

    @Test
    fun blankMeansAutomaticAndIsValid() {
        assertNull(PeerPort.error(""))
        assertNull(PeerPort.error("   "))
        assertEquals(PeerPort.UNSET, PeerPort.parse(""))
        assertEquals(PeerPort.UNSET, PeerPort.parse("  "))
    }

    @Test
    fun parseRoundTripsValidPortsAndRejectsTheRest() {
        assertEquals(1024, PeerPort.parse("1024"))
        assertEquals(65535, PeerPort.parse("65535"))
        assertEquals(47800, PeerPort.parse(" 47800 "))
        assertNull(PeerPort.parse("1023"))
        assertNull(PeerPort.parse("65536"))
        assertNull(PeerPort.parse("nope"))
    }

    @Test
    fun aUserSetPortIsNeverOverwritten() {
        // Even when a different port was actually bound (or a temporary one was
        // used), the person's choice stands.
        assertEquals(5200, PeerPort.toPersist(configured = 5200, boundPort = 40000, temporary = false))
        assertEquals(5200, PeerPort.toPersist(configured = 5200, boundPort = 40001, temporary = true))
    }

    @Test
    fun anUnsetPortIsFilledWithWhatBound() {
        assertEquals(40000, PeerPort.toPersist(configured = 0, boundPort = 40000, temporary = false))
    }

    @Test
    fun aTemporaryPortIsNeverPersisted() {
        assertEquals(0, PeerPort.toPersist(configured = 0, boundPort = 40000, temporary = true))
    }

    @Test
    fun theTemporaryPortMessageNamesBothPorts() {
        val message = PortConflict(47800).message(41234)
        assertEquals("Using temporary port 41234 because 47800 is in use", message)
    }

    @Test
    fun theTemporaryPortMessageIncludesTheHolderWhenKnown() {
        val message = PortConflict(47800, "com.example.holder").message(41234)
        assertEquals("Using temporary port 41234 because 47800 is in use (held by com.example.holder)", message)
    }

    @Test
    fun peerPortStatusOnlyExplainsATemporaryFallback() {
        assertNull(PeerPortStatus(47800, 47800, temporary = false).message())
        assertEquals(
            "Using temporary port 50000 because 47800 is in use",
            PeerPortStatus(50000, 47800, temporary = true).message(),
        )
    }
}
