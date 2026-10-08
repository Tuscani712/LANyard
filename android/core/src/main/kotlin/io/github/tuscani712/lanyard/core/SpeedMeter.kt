package io.github.tuscani712.lanyard.core

/**
 * A smoothed transfer rate over a sliding ~[windowMillis] window.
 *
 * [sample] is fed the cumulative bytes moved and the wall-clock time of each
 * progress callback. It returns the average rate (bytes/second) across the
 * window, or null when no honest rate can be shown yet:
 *
 *  - on the very first sample (a fresh start has no elapsed time to divide by);
 *  - immediately after a stall: a gap of [stallAfterMillis] or more since the
 *    last callback is treated as a resume. The window is reset and this callback
 *    reports nothing, so a resumed transfer goes blank instead of flashing a
 *    spike built from bytes that arrived in a burst after the pause.
 *
 * A counter that moves backwards (a new file in a multi-file receive, or an
 * out-of-order callback) also re-anchors the window rather than reporting a
 * negative or bogus rate.
 *
 * Pure and dependency-free, so the send and receive paths share exactly one
 * tested implementation. Returning null is deliberate: the UI treats "no rate"
 * as blank, which is the required behaviour when a transfer resumes.
 */
class SpeedMeter(
    private val windowMillis: Long = DEFAULT_WINDOW_MS,
    private val stallAfterMillis: Long = DEFAULT_WINDOW_MS,
) {
    private val times = ArrayDeque<Long>()
    private val bytes = ArrayDeque<Long>()

    /**
     * Records [cumulativeBytes] moved at [nowMillis] and returns the smoothed
     * bytes/second over the window, or null when a rate must not be shown.
     */
    fun sample(nowMillis: Long, cumulativeBytes: Long): Double? {
        val lastAt = times.lastOrNull()
        if (lastAt != null) {
            if (nowMillis - lastAt >= stallAfterMillis) {
                // A pause long enough to be a stall. Forget the pre-pause window
                // so the resumed bytes are judged on their own, and show nothing
                // for this callback rather than a rate spanning the gap.
                clear()
            } else if (nowMillis <= lastAt || cumulativeBytes < bytes.last()) {
                // Out-of-order callback, or a counter that reset (a new file):
                // re-anchor so the rate never runs negative.
                clear()
            }
        }
        times.addLast(nowMillis)
        bytes.addLast(cumulativeBytes)

        // Keep only the samples inside the rolling window (always at least one).
        while (times.size > 1 && nowMillis - times.first() > windowMillis) {
            times.removeFirst()
            bytes.removeFirst()
        }
        if (times.size < 2) return null

        val elapsedSeconds = (nowMillis - times.first()) / 1000.0
        if (elapsedSeconds <= 0.0) return null
        val moved = (cumulativeBytes - bytes.first()).coerceAtLeast(0L)
        return moved / elapsedSeconds
    }

    /** Forgets every sample; the next [sample] behaves like a fresh start. */
    fun reset() = clear()

    private fun clear() {
        times.clear()
        bytes.clear()
    }

    companion object {
        /** The rolling window, ~3 seconds as required. */
        const val DEFAULT_WINDOW_MS = 3_000L
    }
}
