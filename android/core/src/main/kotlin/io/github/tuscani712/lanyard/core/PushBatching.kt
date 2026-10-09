package io.github.tuscani712.lanyard.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** The JSON object for one file in a push offer; shared with [PeerClient]. */
internal fun pushFileJson(f: PushFileRequest): JsonObject = JsonObject().apply {
    addProperty("rel_path", f.relPath)
    addProperty("size", f.size)
    // Go's inbox.FileReq.MTime is a time.Time (RFC3339), not a number.
    addProperty("mtime", java.time.Instant.ofEpochMilli(f.mtimeMillis).toString())
}

/** The serialized `POST /push/offer` body for [files]. */
internal fun pushOfferBody(files: List<PushFileRequest>): String {
    val arr = JsonArray()
    files.forEach { arr.add(pushFileJson(it)) }
    return JsonObject().apply { add("files", arr) }.toString()
}

/**
 * Splits an offered file list into batches that each fit the receiving peer's
 * advertised offer caps, so a phone → desktop send of a large library does not
 * trip the receiver's 413. The wire protocol has no multi-offer grouping: each
 * batch is its own offer (and its own `push_id`), so the sender models one
 * visible transfer row and sends several offers underneath it.
 *
 * A peer that advertised no limits (an older build) gets the conservative
 * Android defaults ([FALLBACK_MAX_OFFER_BYTES] / [FALLBACK_MAX_OFFER_FILES]),
 * which are what this phone itself would accept.
 */
object PushBatching {
    /** Fallback byte cap when a peer's hello advertised none (older peers). */
    const val FALLBACK_MAX_OFFER_BYTES: Long = 8L * 1024 * 1024

    /** Fallback file cap when a peer's hello advertised none (older peers). */
    const val FALLBACK_MAX_OFFER_FILES: Int = 50_000

    /** Smallest headroom kept below the receiver's byte cap. */
    const val MIN_HEADROOM_BYTES: Long = 64 * 1024

    /** Bytes of the `{"files":[]}` wrapper around the file array. */
    private const val WRAPPER_BYTES = 12L

    /** The receiver's byte cap, falling back when it advertised none. */
    fun effectiveMaxOfferBytes(advertised: Long): Long =
        if (advertised > 0) advertised else FALLBACK_MAX_OFFER_BYTES

    /** The receiver's file cap, falling back when it advertised none. */
    fun effectiveMaxOfferFiles(advertised: Int): Int =
        if (advertised > 0) advertised else FALLBACK_MAX_OFFER_FILES

    /** Headroom kept below a byte cap: 10% of it, or 64 KiB, whichever is larger. */
    fun headroom(maxOfferBytes: Long): Long = maxOf(MIN_HEADROOM_BYTES, maxOfferBytes / 10)

    /**
     * Groups the indices of [requests] into batches whose serialized offer body
     * stays under `maxOfferBytes - headroom` and whose size never exceeds
     * `maxOfferFiles`. Order is preserved; the batches concatenate to the whole
     * input. A single entry too large to fit still forms its own batch, so the
     * receiver's own 413 is the honest answer rather than a silent drop.
     */
    fun batchIndices(
        requests: List<PushFileRequest>,
        maxOfferBytes: Long,
        maxOfferFiles: Int,
        headroom: Long = headroom(maxOfferBytes),
    ): List<List<Int>> {
        if (requests.isEmpty()) return emptyList()
        val budget = (maxOfferBytes - headroom).coerceAtLeast(0L)
        val fileCap = maxOfferFiles.coerceAtLeast(1)
        val batches = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        var currentBytes = WRAPPER_BYTES
        requests.forEachIndexed { index, request ->
            val entry = pushFileJson(request).toString().toByteArray(Charsets.UTF_8).size.toLong()
            var comma = if (current.isEmpty()) 0L else 1L
            if (current.isNotEmpty() && (currentBytes + comma + entry > budget || current.size >= fileCap)) {
                batches.add(current)
                current = ArrayList()
                currentBytes = WRAPPER_BYTES
                comma = 0L
            }
            current.add(index)
            currentBytes += comma + entry
        }
        if (current.isNotEmpty()) batches.add(current)
        return batches
    }
}
