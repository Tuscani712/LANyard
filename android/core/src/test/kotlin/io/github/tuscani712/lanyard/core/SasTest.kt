package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The SAS must match the desktop's `identity.SAS` byte for byte. The vectors
 * were produced by the Go function.
 */
class SasTest {
    @Test
    fun matchesGoVectors() {
        assertEquals(
            "223062",
            Sas.code("aaaa", "bbbb", "11111111111111111111111111111111", "22222222222222222222222222222222"),
        )
        assertEquals(
            "459339",
            Sas.code("deadbeef", "cafebabe", "0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f", "f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0"),
        )
    }

    @Test
    fun isOrderIndependent() {
        assertEquals(
            Sas.code("aaaa", "bbbb", "11", "22"),
            Sas.code("bbbb", "aaaa", "22", "11"),
        )
    }

    @Test
    fun isSixDigits() {
        val code = Sas.code("a".repeat(64), "b".repeat(64), "1".repeat(32), "2".repeat(32))
        assertEquals(6, code.length)
    }
}
