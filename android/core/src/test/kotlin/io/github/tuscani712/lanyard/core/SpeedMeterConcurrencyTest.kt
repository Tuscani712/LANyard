package io.github.tuscani712.lanyard.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeedMeterConcurrencyTest {
    /** Three bodies of one push share a meter; sampling it concurrently must never throw. */
    @Test
    fun threadsSharingOneMeterNeverThrow() {
        val meter = SpeedMeter()
        val failures = AtomicInteger()
        val pool = Executors.newFixedThreadPool(3)
        val start = CountDownLatch(1)
        val done = CountDownLatch(3)
        repeat(3) { t ->
            pool.execute {
                start.await()
                try {
                    var bytes = 0L
                    for (i in 0 until 200_000) {
                        bytes += 1000
                        meter.sample(i.toLong() * (t + 1), bytes)
                        if (i % 5_000 == 0) meter.reset()
                    }
                } catch (e: Throwable) {
                    failures.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        done.await(60, TimeUnit.SECONDS)
        pool.shutdownNow()
        assertEquals(0, failures.get())
    }
}
