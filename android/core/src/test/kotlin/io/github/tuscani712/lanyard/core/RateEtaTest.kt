package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The shared window/refresh constants and the pure rate+ETA display. The ETA is
 * derived from the same smoothed rate the row shows (never an instantaneous
 * sample), and the shown text refreshes at most once a second.
 */
class RateEtaTest {

    @Test
    fun windowIsFiveSecondsAndRefreshIsOneSecond() {
        assertEquals(5_000L, TransferTuning.WINDOW_MS)
        assertEquals(5_000L, TransferTuning.STALL_AFTER_MS)
        assertEquals(1_000L, TransferTuning.DISPLAY_REFRESH_MS)
        assertEquals(TransferTuning.WINDOW_MS, SpeedMeter.DEFAULT_WINDOW_MS)
    }

    @Test
    fun etaIsRemainingBytesOverTheSmoothedRate() {
        assertEquals(100L, estimateEtaSeconds(remainingBytes = 100_000_000, bytesPerSecond = 1_000_000.0))
        assertNull(estimateEtaSeconds(0, 1_000_000.0), "a finished transfer has no ETA")
        assertNull(estimateEtaSeconds(100, 0.0), "no rate means no ETA")
        assertNull(estimateEtaSeconds(100, -5.0))
    }

    @Test
    fun etaFormatsReadably() {
        assertEquals("45s", formatEta(45))
        assertEquals("2m 14s", formatEta(134))
        assertEquals("1h 2m", formatEta(3_720))
    }

    @Test
    fun rateAndEtaTextIsBlankWithoutAnHonestRate() {
        assertEquals("", formatRateAndEta(0.0, null, SpeedUnit.MBps))
        assertEquals("1.0 MB/s", formatRateAndEta(1_048_576.0, null, SpeedUnit.MBps))
        assertEquals("1.0 MB/s · ETA 2m 14s", formatRateAndEta(1_048_576.0, 134, SpeedUnit.MBps))
    }

    @Test
    fun burstAfterAPauseDoesNotMakeTheEtaJump() {
        val display = RateEtaDisplay(windowMillis = 3_000, refreshMillis = 1_000)
        val total = 1_000_000_000L // 1 GB; the burst stays well under it
        display.sample(0, 0, total)
        display.sample(1_000, 1_000_000, total)
        display.sample(2_000, 2_000_000, total)
        val steady = display.sample(3_000, 3_000_000, total)
        assertNotNull(steady.etaSeconds, "a steady 1 MB/s transfer has an ETA")
        // Remaining ~997 MB at 1 MB/s -> ~997 s.
        assertTrue(steady.etaSeconds!! in 900..1_100, "steady ETA is remaining/rate: ${steady.etaSeconds}")

        // A 30 s stall, then a 40 MB backlog lands in a single callback.
        val burst = display.sample(33_000, 43_000_000, total)
        assertNull(burst.etaSeconds, "a resume must not show an ETA built from the backlog")
        assertEquals(0.0, burst.bytesPerSecond, 0.0)

        // Once fresh bytes flow the ETA is rebuilt from the post-resume rate,
        // not from the burst: remaining ~956 MB at 1 MB/s.
        val resumed = display.sample(34_000, 44_000_000, total)
        assertNotNull(resumed.etaSeconds)
        assertTrue(
            resumed.etaSeconds!! > 100,
            "the ETA must not collapse to the instant after the burst: ${resumed.etaSeconds}",
        )
    }

    @Test
    fun displayTextChangesAtMostOncePerSecond() {
        val display = RateEtaDisplay(windowMillis = 3_000, refreshMillis = 1_000)
        val total = 1_000_000_000L
        var last = ""
        var changes = 0
        var t = 0L
        while (t <= 5_000) {
            val text = display.sample(t, t * 1_000, total).text(SpeedUnit.MBps) // 1 MB/s
            if (text != last) {
                changes++
                last = text
            }
            t += 100
        }
        assertTrue(changes <= 6, "the shown text changed $changes times over 5 s (must be ~1/s)")
    }
}
