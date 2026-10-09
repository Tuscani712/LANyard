package io.github.tuscani712.lanyard.core

/**
 * Honest send progress.
 *
 * A file's bytes are counted as soon as they are written to the socket's
 * buffered stream, which happens before the receiver has accepted the file. The
 * upload can still be rejected after that point (a checksum mismatch, an
 * unwritable inbox, or a connection dropped mid-body), so showing 100% at the
 * moment the last byte is written overstates progress.
 *
 * [whileSending] therefore holds the displayed count strictly below [total]; the
 * true total is reported by [PushSession] only once the receiver's response to
 * the file has been read and is a 2xx success.
 */
object SendProgress {
    /**
     * The byte count to display while [sent] bytes have been written but the
     * receiver has not acknowledged the file yet. Always below [total] when
     * [total] is positive, so a file in flight never reads as finished.
     */
    fun whileSending(sent: Long, total: Long): Long {
        if (total <= 0) return 0
        if (sent < total) return sent
        return total - 1
    }
}
