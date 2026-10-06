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
 */
class PushSession(private val client: PeerClient) {

    fun push(
        sources: List<PushSource>,
        onProgress: (fileIndex: Int, sent: Long, fileTotal: Long) -> Unit = { _, _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): PushResult {
        if (sources.isEmpty()) return PushResult.Failed("nothing to send")

        val offer = try {
            client.pushOffer(sources.map { PushFileRequest(it.relPath, it.size, it.mtimeMillis) })
        } catch (e: PeerStatusException) {
            return statusResult(e)
        } catch (e: Exception) {
            return PushResult.Failed(e.message ?: "the offer failed")
        }
        if (!offer.accepted) return PushResult.Refused

        var overall = 0L
        return try {
            sources.forEachIndexed { index, source ->
                if (isCancelled()) throw PushCancelledException()
                val offset = offer.offsets[source.relPath] ?: 0L
                val result = client.pushFileStream(
                    pushId = offer.pushId,
                    relPath = source.relPath,
                    offset = offset,
                    total = source.size,
                    source = source.open(),
                    onBytes = { sent -> onProgress(index, sent, source.size) },
                    isCancelled = isCancelled,
                )
                client.pushCompleteFile(offer.pushId, source.relPath, result.sha256)
                overall += result.bytes
                onProgress(index, source.size, source.size)
            }
            client.pushCompleteAll(offer.pushId)
            PushResult.Sent(sources.size, overall)
        } catch (_: PushCancelledException) {
            PushResult.Cancelled
        } catch (e: PeerStatusException) {
            statusResult(e)
        } catch (e: Exception) {
            PushResult.Failed(e.message ?: "the push failed")
        }
    }

    private fun statusResult(e: PeerStatusException): PushResult = when (e.code) {
        403 -> PushResult.Refused
        410 -> PushResult.CancelledByReceiver
        else -> PushResult.Failed("HTTP ${e.code}")
    }
}
