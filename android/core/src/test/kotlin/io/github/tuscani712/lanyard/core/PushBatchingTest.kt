package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The sender-side batching that keeps every `/push/offer` body under the
 * receiver's advertised caps. Pure, so it is tested without a peer.
 */
class PushBatchingTest {

    private fun req(i: Int, pathLen: Int = 20) =
        PushFileRequest(("f%03d_".format(i) + "p".repeat(pathLen)).take(pathLen), 1000, 0)

    @Test
    fun fallsBackToAndroidDefaultsWhenPeerAdvertisesNothing() {
        assertEquals(8L * 1024 * 1024, PushBatching.effectiveMaxOfferBytes(0))
        assertEquals(50_000, PushBatching.effectiveMaxOfferFiles(0))
        // An advertised value wins over the fallback.
        assertEquals(128L * 1024 * 1024, PushBatching.effectiveMaxOfferBytes(128L * 1024 * 1024))
        assertEquals(500_000, PushBatching.effectiveMaxOfferFiles(500_000))
    }

    @Test
    fun emptyInputYieldsNoBatches() {
        assertTrue(PushBatching.batchIndices(emptyList(), 1_000_000, 10).isEmpty())
    }

    @Test
    fun respectsTheFileCap() {
        val files = (1..10).map { req(it) }
        val batches = PushBatching.batchIndices(files, maxOfferBytes = 10_000_000, maxOfferFiles = 3)
        assertEquals(listOf(3, 3, 3, 1), batches.map { it.size })
        // Order is preserved and the batches concatenate to the whole input.
        assertEquals((0 until 10).toList(), batches.flatten())
    }

    @Test
    fun respectsTheByteCapAndLeavesHeadroom() {
        // entries are roughly 180 bytes; a 2 KB budget fits a handful per batch.
        val files = (1..200).map { req(it, pathLen = 120) }
        val maxBytes = 8_000L
        val headroom = 2_000L
        val batches = PushBatching.batchIndices(files, maxOfferBytes = maxBytes, maxOfferFiles = 1000, headroom = headroom)
        assertTrue(batches.size > 1, "expected more than one batch, got ${batches.size}")
        for (batch in batches) {
            val body = pushOfferBody(batch.map { files[it] }).toByteArray(Charsets.UTF_8).size
            assertTrue(body <= maxBytes - headroom, "batch body $body exceeded the budget")
        }
        assertEquals((0 until 200).toList(), batches.flatten())
    }

    @Test
    fun singleEntryTooLargeStillFormsItsOwnBatch() {
        val files = listOf(req(1), req(2))
        // A negative budget leaves no room, but each entry must still be offered.
        val batches = PushBatching.batchIndices(files, maxOfferBytes = 10, maxOfferFiles = 10, headroom = 100)
        assertEquals(listOf(listOf(0), listOf(1)), batches.map { it })
    }
}
