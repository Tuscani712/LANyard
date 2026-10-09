package io.github.tuscani712.lanyard.core

/** A pace control for a streaming loop: call [pace] after emitting [bytes]. */
fun interface Throttle {
    fun pace(bytes: Int)
}

/** A [Throttle] that does nothing (unlimited). */
object NoThrottle : Throttle {
    override fun pace(bytes: Int) {}
}

object Bandwidth {
    /** Anything above this is treated as unlimited, so a bad setting can't hang. */
    const val MAX_MBPS = 10_000

    fun clampMBps(mbps: Int): Int = mbps.coerceIn(0, MAX_MBPS)

    fun bytesPerSecond(mbps: Int): Long = clampMBps(mbps).toLong() * 1024 * 1024
}

/**
 * A token-bucket [Throttle]. Tokens refill at [rateBytesPerSecond] up to a small
 * burst capacity; [pace] sleeps until the emitted bytes are paid for, so a
 * streaming loop runs at roughly the requested rate. The clock and sleep are
 * injectable so a test can measure the achieved rate without real waiting.
 */
class RateThrottle(
    private val rateBytesPerSecond: Long,
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleep: (Long) -> Unit = { ms -> Thread.sleep(ms) },
) : Throttle {
    private val capacity = minOf(maxOf(rateBytesPerSecond / 8, 1L), 64L * 1024)
    private var tokens = capacity.toDouble()
    private var lastNanos = 0L
    private var started = false

    override fun pace(bytes: Int) {
        if (rateBytesPerSecond <= 0) return
        val now = nanoTime()
        if (!started) {
            lastNanos = now
            started = true
        }
        tokens = minOf(capacity.toDouble(), tokens + (now - lastNanos) / 1e9 * rateBytesPerSecond)
        lastNanos = now

        tokens -= bytes
        while (tokens < 0) {
            val waitNanos = (-tokens / rateBytesPerSecond * 1e9).toLong().coerceAtLeast(1_000_000L)
            sleep(waitNanos / 1_000_000L)
            val after = nanoTime()
            tokens = minOf(capacity.toDouble(), tokens + (after - lastNanos) / 1e9 * rateBytesPerSecond)
            lastNanos = after
        }
    }

    companion object {
        /** Builds a throttle for a MB/s setting; 0 or below yields [NoThrottle]. */
        fun fromMBps(
            mbps: Int,
            nanoTime: () -> Long = System::nanoTime,
            sleep: (Long) -> Unit = { ms -> Thread.sleep(ms) },
        ): Throttle {
            val bps = Bandwidth.bytesPerSecond(mbps)
            return if (bps <= 0) NoThrottle else RateThrottle(bps, nanoTime, sleep)
        }
    }
}
