package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The pure receive/send rate meter. These cover the three cases the UI cares
 * about: a steady rate, a stall followed by a resume (which must go blank
 * instead of spiking), and a fresh start (which has no rate yet).
 */
class SpeedMeterTest {

    /** [SpeedMeter.sample] that fails the test rather than silently returning null. */
    private fun rate(meter: SpeedMeter, now: Long, bytes: Long): Double =
        meter.sample(now, bytes) ?: error("expected a rate at t=${now}ms")

    @Test
    fun steadyRateIsSmoothedOverTheRollingWindow() {
        val meter = SpeedMeter(windowMillis = 3_000)
        assertNull(meter.sample(0, 0), "the first sample has no elapsed time")
        assertEquals(1_000_000.0, rate(meter, 1_000, 1_000_000), 1.0)
        assertEquals(1_000_000.0, rate(meter, 2_000, 2_000_000), 1.0)
        assertEquals(1_000_000.0, rate(meter, 3_000, 3_000_000), 1.0)
        // At t=4s the t=0s sample leaves the window, but a steady stream holds.
        assertEquals(1_000_000.0, rate(meter, 4_000, 4_000_000), 1.0)
        assertEquals(1_000_000.0, rate(meter, 5_000, 5_000_000), 1.0)
    }

    @Test
    fun pauseThenResumeGoesBlankInsteadOfSpiking() {
        val meter = SpeedMeter(windowMillis = 3_000)
        meter.sample(0, 0)
        meter.sample(1_000, 1_000_000)
        meter.sample(2_000, 2_000_000)
        assertEquals(1_000_000.0, rate(meter, 3_000, 3_000_000), 1.0)

        // Nothing for 30s, then a large backlog lands at once. Averaging across
        // the pause would invent a spike; the resume must report nothing.
        assertNull(meter.sample(33_000, 43_000_000), "a resume must be blank, not a spike")

        // Only once fresh bytes flow does a rate come back, and it reflects the
        // post-resume period rather than the backlog.
        val resumed = rate(meter, 34_000, 44_000_000)
        assertTrue(resumed <= 1_500_000.0, "rate after resume must not spike: $resumed")
        assertEquals(1_000_000.0, resumed, 1.0)
    }

    @Test
    fun freshStartHasNoRateUntilTimePasses() {
        val meter = SpeedMeter()
        assertNull(meter.sample(10_000, 0), "a fresh start has no rate")
        // The same instant still has no elapsed time to divide by.
        assertNull(meter.sample(10_000, 0), "no elapsed time means no rate")
        assertEquals(500_000.0, rate(meter, 11_000, 500_000), 1.0)
    }

    @Test
    fun aCounterResetReanchorsInsteadOfGoingNegative() {
        val meter = SpeedMeter(windowMillis = 3_000)
        meter.sample(0, 0)
        meter.sample(1_000, 1_000_000)
        // A new file starts at zero: the meter re-anchors rather than reporting
        // a bogus rate from the drop.
        assertNull(meter.sample(2_000, 0), "a reset counter has no honest rate")
        assertEquals(2_000_000.0, rate(meter, 3_000, 2_000_000), 1.0)
    }

    @Test
    fun resetMakesTheNextSampleAFreshStart() {
        val meter = SpeedMeter()
        meter.sample(0, 0)
        meter.sample(1_000, 1_000_000)
        meter.reset()
        assertNull(meter.sample(2_000, 2_000_000), "a reset meter has no history")
    }
}
