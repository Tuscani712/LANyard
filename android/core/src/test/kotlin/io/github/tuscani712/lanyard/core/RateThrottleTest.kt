package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RateThrottleTest {
    @Test
    fun paceRunsAtRoughlyTheRequestedRate() {
        var virtualNanos = 0L
        val throttle = RateThrottle(
            rateBytesPerSecond = 1_000_000,
            nanoTime = { virtualNanos },
            sleep = { ms -> virtualNanos += ms * 1_000_000 },
        )

        val chunk = 32 * 1024
        var sent = 0L
        repeat(64) {
            throttle.pace(chunk)
            sent += chunk
        }

        val seconds = virtualNanos / 1e9
        val achieved = sent / seconds
        assertTrue(achieved in 850_000.0..1_150_000.0, "achieved=$achieved bytes/s over $seconds s")
    }

    @Test
    fun zeroAndClamps() {
        assertSame(NoThrottle, RateThrottle.fromMbps(0))
        assertSame(NoThrottle, RateThrottle.fromMbps(-5))
        assertEquals(Bandwidth.MAX_MBPS, Bandwidth.clampMbps(99_999_999))
        assertEquals(0, Bandwidth.clampMbps(-1))
        assertEquals(5, Bandwidth.clampMbps(5))
    }
}
