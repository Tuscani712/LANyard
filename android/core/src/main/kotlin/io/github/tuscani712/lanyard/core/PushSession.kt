package io.github.tuscani712.lanyard.core

import java.io.InputStream

/**
 * One file to push. [open] returns a fresh stream each call; [size] and
 * [mtimeMillis] describe the file (an [InputStream] cannot report either).
 */
data class PushSource(
    val relPath: String,
    val size: Long,
    val mtimeMillis: Long,
    val open: () -> InputStream,
)

/** The outcome of a push. */
sealed class PushResult {
    /** Everything was sent and verified by the receiver. */
    data class Sent(val files: Int, val bytes: Long) : PushResult()

    /** HTTP 403: the peer is not allowed to accept our push (or declined it). */
    object Refused : PushResult()

    /** HTTP 410: the receiver cancelled the transfer. */
    object CancelledByReceiver : PushResult()

    /** We cancelled it locally. */
    object Cancelled : PushResult()

    data class Failed(val message: String) : PushResult()
}

/**
 * Pushes files into a paired peer's Inbox through [PeerClient]: offer, stream
 * each file, then verify with its SHA-256. Files are streamed, never buffered
 * whole. [onProgress] reports per-file bytes as they go; [isCancelled] is polled
 * between buffers so a transfer can be stopped promptly.
 *
 * [maxOfferBytes]/[maxOfferFiles] are the receiver's advertised caps (from its
 * `/hello`). A list too large for one offer is split into successive offers,
 * each with its own `push_id`, so all files still arrive under one visible
 * transfer row. Zero means the peer advertised none and [PushBatching]'s
 * conservative fallback is used.
 */
class PushSession(
    private val client: PeerClient,
    private val throttle: Throttle = NoThrottle,
    private val maxOfferBytes: Long = 0,
    private val maxOfferFiles: Int = 0,
    // One redacted line per outgoing step, so the phone's diagnostics report
    // tells the same story as the receiver's: `[push] offer`, `[push] file n of
    // m`, `[push] complete`, `[push] failed reason=…`. Never a file name, only a
    // path class, and never a full fingerprint.
    private val diag: (String) -> Unit = {},
) {

    fun push(
        sources: List<PushSource>,
        onProgress: (fileIndex: Int, sent: Long, fileTotal: Long) -> Unit = { _, _, _ -> },
        // Fired after every byte of a file is on the wire but before the
        // receiver's complete response is read: the file is still being hashed
        // and placed there. Progress stays capped below 100% until that response
        // (see [SendProgress]), so without this the row would sit silently at the
        // cap. [bytes] is the file's full size being confirmed.
        onFinishing: (fileIndex: Int, bytes: Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): PushResult {
        if (sources.isEmpty()) return PushResult.Failed("nothing to send")

        val requests = sources.map { PushFileRequest(it.relPath, it.size, it.mtimeMillis) }
        val batches = PushBatching.batchIndices(
            requests,
            PushBatching.effectiveMaxOfferBytes(maxOfferBytes),
            PushBatching.effectiveMaxOfferFiles(maxOfferFiles),
        )

        var overall = 0L
        var sentFiles = 0
        return try {
            batches.forEachIndexed { batchNo, batch ->
                if (isCancelled()) throw PushCancelledException()
                val batchBytes = batch.sumOf { requests[it].size }
                val offer = try {
                    client.pushOffer(batch.map { requests[it] })
                } catch (e: PeerStatusException) {
                    return failed(statusResult(e))
                } catch (e: Exception) {
                    return failed(PushResult.Failed(e.message ?: "the offer failed"))
                }
                if (!offer.accepted) return failed(PushResult.Refused)
                diag("[push] offer files=${batch.size} bytes=$batchBytes batch=${batchNo + 1}/${batches.size}")

                for (index in batch) {
                    if (isCancelled()) throw PushCancelledException()
                    val source = sources[index]
                    diag(
                        "[push] file ${sentFiles + 1} of ${sources.size} " +
                            "cls=${Display.pathClass(source.relPath)} bytes=${source.size}",
                    )
                    val offset = offer.offsets[source.relPath] ?: 0L
                    val result = client.pushFileStream(
                        pushId = offer.pushId,
                        relPath = source.relPath,
                        offset = offset,
                        total = source.size,
                        source = source.open(),
                        // Live byte progress is held below 100% until the receiver
                        // has acknowledged the file: the bytes are on the wire, but
                        // the upload can still be refused. Honest 100% comes only
                        // after pushCompleteFile() returns a 2xx (below).
                        onBytes = { sent -> onProgress(index, SendProgress.whileSending(sent, source.size), source.size) },
                        isCancelled = isCancelled,
                        throttle = throttle,
                    )
                    // Every byte is written; the receiver still has to hash and place
                    // the file before it answers. That wait is the finishing window.
                    onFinishing(index, source.size)
                    client.pushCompleteFile(offer.pushId, source.relPath, result.sha256)
                    overall += result.bytes
                    sentFiles++
                    onProgress(index, source.size, source.size)
                }
                client.pushCompleteAll(offer.pushId)
            }
            diag("[push] complete files=$sentFiles bytes=$overall")
            PushResult.Sent(sentFiles, overall)
        } catch (_: PushCancelledException) {
            diag("[push] failed reason=cancelled")
            PushResult.Cancelled
        } catch (e: PeerStatusException) {
            failed(statusResult(e))
        } catch (e: Exception) {
            failed(PushResult.Failed(e.message ?: "the push failed"))
        }
    }

    /** Logs a redacted failure line once, then returns the same result. */
    private fun failed(result: PushResult): PushResult {
        val reason = when (result) {
            is PushResult.Failed -> result.message
            PushResult.Refused -> "the other device is not accepting files"
            PushResult.CancelledByReceiver -> "the other device cancelled"
            PushResult.Cancelled -> "cancelled"
            is PushResult.Sent -> "sent"
        }
        diag("[push] failed reason=${reason.take(80)}")
        return result
    }

    private fun statusResult(e: PeerStatusException): PushResult = when (e.code) {
        403 -> PushResult.Refused
        410 -> PushResult.CancelledByReceiver
        // A 413 (offer too large) and any other status become the peer-error
        // mapping, so the person reads an actionable line rather than "HTTP n".
        else -> PushResult.Failed(PeerErrors.userMessage(e))
    }
}
