package io.github.tuscani712.lanyard.core

/**
 * The state of one transfer row. [Preparing] is the short window before a send
 * is queued, while its picked files are still being copied into the spool.
 */
enum class TransferState { Preparing, Queued, Running, Done, Failed, Cancelled }

/**
 * True while a row still represents active work the foreground service must
 * protect and that Cancel may end: preparing (spooling), waiting, or running.
 * A finished row (Done/Failed/Cancelled) is never live.
 */
val TransferState.isLive: Boolean
    get() = this == TransferState.Preparing || this == TransferState.Running || this == TransferState.Queued

/**
 * One row on the Transfers screen. Pure data: no Android types, so the row
 * lifecycle is unit-testable. [lastProgressAt] drives the no-progress aging.
 */
data class TransferRecord(
    val id: String,
    val direction: String, // "send" | "receive" (a download is a "receive")
    val peerName: String,
    val peerFingerprint: String = "",
    val label: String,
    val total: Long,
    val done: Long,
    val state: TransferState,
    val message: String? = null,
    val speed: Double = 0.0, // bytes per second, smoothed live rate
    val startedAt: Long,
    val lastProgressAt: Long = startedAt,
    val averageSpeed: Double = 0.0, // bytes per second, whole-transfer average (set when Done)
    val etaSeconds: Long? = null, // seconds left at the smoothed rate, or null
    // Where a finished receive landed (the destination folder's label), for the
    // "Done" row. Null for sends or when the destination cannot name itself.
    val destinationFolder: String? = null,
    // An openable location for [destinationFolder] (a SAF tree URI or a
    // filesystem path), for the "Open folder" action. Null when the destination
    // has no location (or for sends), so the action is simply left off.
    val destinationUri: String? = null,
    // The total number of files this row covers (the picked selection for a
    // send, the offer's file list for a receive), so the device screen can say
    // "Preparing N file(s)" for a batch and Finishing can be judged per push.
    // 0 when not known.
    val fileCount: Int = 0,
    // How many of [fileCount] files have fully arrived (receive) or been written
    // and acknowledged (send). Drives the push-level Finishing window.
    val filesDone: Int = 0,
    // Set while a live row is in its final window: every file of the push has
    // arrived/written, but the transfer is still hashing, copying the spool into
    // the destination, or waiting for the receiver's confirmation. Non-null
    // means "Finishing…"; the value is the size in bytes being finalized, shown
    // next to the label. Always cleared when the row ends
    // (Done/Failed/Cancelled), on cancel, and on any fresh byte.
    val finishingBytes: Long? = null,
) {
    /** True while this row is in its "Finishing…" window (set, not yet ended). */
    val finishing: Boolean get() = finishingBytes != null
}

/**
 * The pure lifecycle of the transfer list: insertion, progress, cancellation and
 * no-progress aging. [TransferManager] owns the network/SAF side and delegates
 * every state transition here, so cancel/aging can be tested without Android.
 *
 * Cancelling always ends a live row: it is the single source of truth for
 * "Cancel must do something". A row that is already Done/Failed/Cancelled is
 * left alone (Cancel is idempotent, never resurrects a finished row).
 */
class TransferBoard(
    private val historyCap: Int = HISTORY_CAP,
    private val stalledAfterMillis: Long = 10 * 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /** How many rows the list — and the persisted history — keeps. */
        const val HISTORY_CAP = 100
    }

    private var records: List<TransferRecord> = emptyList()

    fun snapshot(): List<TransferRecord> = records

    fun add(record: TransferRecord) {
        records = (listOf(record) + records).take(historyCap)
    }

    fun update(id: String, block: (TransferRecord) -> TransferRecord) {
        records = records.map { if (it.id == id) block(it) else it }
    }

    fun firstOrNull(id: String): TransferRecord? = records.firstOrNull { it.id == id }

    /** True while a row is still live (Preparing, Running or Queued). */
    fun isLive(id: String): Boolean =
        records.firstOrNull { it.id == id }?.state?.isLive ?: false

    /** Rows that still count as active for the foreground service. */
    fun running(): List<TransferRecord> =
        records.filter { it.state.isLive }

    /**
     * Records progress and resets the no-progress deadline, so a slow but live
     * transfer is never aged out.
     */
    fun progress(id: String, done: Long, total: Long, speed: Double, etaSeconds: Long? = null) = update(id) {
        it.copy(
            done = maxOf(done, it.done),
            total = maxOf(total, it.total, done),
            speed = speed,
            etaSeconds = etaSeconds,
            lastProgressAt = clock(),
            // A fresh byte means the body is moving again (e.g. the next file of
            // a multi-file send), so the previous file's finishing window is over.
            finishingBytes = null,
        )
    }

    /**
     * Records that [completedFiles] of a push's [fileCount] files are fully
     * done, and opens the "Finishing…" window only once the whole push is done.
     *
     * Finishing is deliberately push-level: the receive path reports each file
     * as it arrives, so a single tiny file finishing must never flip a
     * multi-file row to "Finishing…" while the rest are still streaming. The
     * window opens only when the last file is done; [bytes] is the size shown
     * next to the label. Null (and a no-op) when the row is absent or already
     * finished, so a late callback cannot revive a row.
     */
    fun markFinishing(id: String, completedFiles: Int, fileCount: Int, bytes: Long): TransferRecord? {
        val row = records.firstOrNull { it.id == id } ?: return null
        if (row.state != TransferState.Running && row.state != TransferState.Queued) return null
        val total = if (fileCount > 0) fileCount else row.fileCount
        val done = maxOf(row.filesDone, completedFiles)
        update(id) { it.copy(filesDone = done) }
        if (total <= 0 || done < total) return row
        update(id) { it.copy(finishingBytes = bytes) }
        return row
    }

    /**
     * Marks a live row Cancelled and returns it. Returns null when the row is
     * absent or already finished, so callers do not free state for a row that
     * some other path already ended (and Cancel stays a no-op on a Done row).
     */
    fun cancel(id: String): TransferRecord? {
        val row = records.firstOrNull { it.id == id } ?: return null
        if (!row.state.isLive) return null
        update(id) {
            it.copy(state = TransferState.Cancelled, message = "Cancelled", speed = 0.0, etaSeconds = null, finishingBytes = null)
        }
        return row
    }

    /**
     * Ends a live row with [state] (Done/Failed/Cancelled). Null when not live.
     * [destinationFolder] and [destinationUri] are recorded only for a completed
     * receive, so a Done row can show (and open) where the files landed.
     */
    fun end(
        id: String,
        state: TransferState,
        message: String,
        destinationFolder: String? = null,
        destinationUri: String? = null,
    ): TransferRecord? {
        val row = records.firstOrNull { it.id == id } ?: return null
        if (!row.state.isLive) return null
        val finishedAt = clock()
        update(id) {
            it.copy(
                state = state,
                message = message,
                speed = 0.0,
                etaSeconds = null,
                finishingBytes = null,
                averageSpeed = if (state == TransferState.Done && finishedAt > it.startedAt) {
                    it.total * 1000.0 / (finishedAt - it.startedAt)
                } else {
                    0.0
                },
                done = if (state == TransferState.Done) it.total else it.done,
                destinationFolder = if (state == TransferState.Done) {
                    destinationFolder ?: it.destinationFolder
                } else {
                    it.destinationFolder
                },
                destinationUri = if (state == TransferState.Done) {
                    destinationUri ?: it.destinationUri
                } else {
                    it.destinationUri
                },
            )
        }
        return row
    }

    /** Keeps only the live rows (the screen's "Clear finished"). */
    fun clearFinished() {
        records = records.filter { it.state.isLive }
    }

    fun dismiss(id: String) {
        records = records.filterNot { it.id == id }
    }

    /**
     * Fails every Running row that has made no progress for [stalledAfterMillis].
     * Returns the rows that aged out, so the caller can stop their live work.
     */
    fun age(now: Long = clock()): List<TransferRecord> {
        if (stalledAfterMillis <= 0) return emptyList()
        val aged = records.filter {
            it.state == TransferState.Running && now - it.lastProgressAt > stalledAfterMillis
        }
        if (aged.isEmpty()) return emptyList()
        val ids = aged.map { it.id }.toSet()
        records = records.map {
            if (it.id in ids) {
                it.copy(state = TransferState.Failed, message = "No progress", speed = 0.0, etaSeconds = null, finishingBytes = null)
            } else {
                it
            }
        }
        return aged
    }

    /**
     * On restart a row that was Running/Queued has no live work: fail it, but
     * with the "will resume" text, since the `.part`/`.lanpart` partial is kept
     * for the automatic resume.
     */
    fun failInterrupted(message: String = ForegroundTransferPolicy.INTERRUPTED_MESSAGE): List<TransferRecord> {
        val interrupted = records.filter { it.state.isLive }
        if (interrupted.isEmpty()) return emptyList()
        records = records.map {
            if (it.state.isLive) {
                it.copy(state = TransferState.Failed, message = message, speed = 0.0, etaSeconds = null, finishingBytes = null)
            } else {
                it
            }
        }
        return interrupted
    }

    /** Replaces the whole list (used when loading persisted history). */
    fun replaceAll(loaded: List<TransferRecord>) {
        records = loaded.take(historyCap)
    }
}

/**
 * Files being prepared per peer (lowercased fingerprint), summed over every
 * Preparing row. A batch is one row covering many files, so this sums each
 * row's [TransferRecord.fileCount] (at least 1 per row) instead of counting rows.
 */
fun preparingFilesByPeer(rows: List<TransferRecord>): Map<String, Int> =
    rows.asSequence()
        .filter { it.state == TransferState.Preparing }
        .groupBy { it.peerFingerprint.lowercase() }
        .mapValues { (_, list) -> list.sumOf { it.fileCount.coerceAtLeast(1) } }
