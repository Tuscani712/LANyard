package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Attacker-controlled names and identifiers shown in the pairing dialog. */
class DisplayTest {
    @Test
    fun stripsControlCharacters() {
        assertEquals("HackerX", Display.safeName("Ha\u0000ck\u0007erX"))
        assertEquals("line1line2", Display.safeName("line1\nline2"))
    }

    @Test
    fun stripsBidiOverrides() {
        // U+202E RIGHT-TO-LEFT OVERRIDE can reorder the rest of the dialog line.
        assertEquals("evil.exe", Display.safeName("evil.exe\u202e"))
        assertEquals("abc", Display.safeName("\u202aabc\u2069"))
        assertEquals("", Display.safeName("\u200e\u200f"))
    }

    @Test
    fun capsLength() {
        assertEquals(Display.MAX_NAME, Display.safeName("a".repeat(500)).length)
    }

    @Test
    fun groupsFingerprintInFours() {
        assertEquals("4D63 5A83 F403 3D53", Display.groupedHex("4d635a83f4033d53"))
        assertEquals("4D63 5A83", Display.groupedHex("4d635a83"))
    }
}
