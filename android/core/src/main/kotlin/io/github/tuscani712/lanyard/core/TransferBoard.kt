package io.github.tuscani712.lanyard.core

/** The state of one transfer row. */
enum class TransferState { Queued, Running, Done, Failed, Cancelled }

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
    val speed: Double = 0.0, // bytes per second, smoothed
    val startedAt: Long,
    val lastProgressAt: Long = startedAt,
)

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
    private val historyCap: Int = 100,
    private val stalledAfterMillis: Long = 10 * 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var records: List<TransferRecord> = emptyList()

    fun snapshot(): List<TransferRecord> = records

    fun add(record: TransferRecord) {
        records = (listOf(record) + records).take(historyCap)
    }

    fun update(id: String, block: (TransferRecord) -> TransferRecord) {
        records = records.map { if (it.id == id) block(it) else it }
    }

    fun firstOrNull(id: String): TransferRecord? = records.firstOrNull { it.id == id }

    /** True while a row is still live (Running or Queued). */
    fun isLive(id: String): Boolean =
        records.firstOrNull { it.id == id }?.let { it.state == TransferState.Running || it.state == TransferState.Queued } ?: false

    /** Rows that still count as active for the foreground service. */
    fun running(): List<TransferRecord> =
        records.filter { it.state == TransferState.Running || it.state == TransferState.Queued }

    /**
     * Records progress and resets the no-progress deadline, so a slow but live
     * transfer is never aged out.
     */
    fun progress(id: String, done: Long, total: Long, speed: Double) = update(id) {
        it.copy(
            done = maxOf(done, it.done),
            total = maxOf(total, it.total, done),
            speed = speed,
            lastProgressAt = clock(),
        )
    }

    /**
     * Marks a live row Cancelled and returns it. Returns null when the row is
     * absent or already finished, so callers do not free state for a row that
     * some other path already ended (and Cancel stays a no-op on a Done row).
     */
    fun cancel(id: String): TransferRecord? {
        val row = records.firstOrNull { it.id == id } ?: return null
        if (row.state != TransferState.Running && row.state != TransferState.Queued) return null
        update(id) { it.copy(state = TransferState.Cancelled, message = "Cancelled", speed = 0.0) }
        return row
    }

    /** Ends a live row with [state] (Done/Failed/Cancelled). Null when not live. */
    fun end(id: String, state: TransferState, message: String): TransferRecord? {
        val row = records.firstOrNull { it.id == id } ?: return null
        if (row.state != TransferState.Running && row.state != TransferState.Queued) return null
        update(id) {
            it.copy(
                state = state,
                message = message,
                speed = 0.0,
                done = if (state == TransferState.Done) it.total else it.done,
            )
        }
        return row
    }

    /** Keeps only the live rows (the screen's "Clear finished"). */
    fun clearFinished() {
        records = records.filter { it.state == TransferState.Running || it.state == TransferState.Queued }
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
            if (it.id in ids) it.copy(state = TransferState.Failed, message = "No progress", speed = 0.0) else it
        }
        return aged
    }

    /** On restart a row that was Running/Queued has no live work: fail it. */
    fun failInterrupted(message: String = "Interrupted"): List<TransferRecord> {
        val interrupted = records.filter { it.state == TransferState.Running || it.state == TransferState.Queued }
        if (interrupted.isEmpty()) return emptyList()
        records = records.map {
            if (it.state == TransferState.Running || it.state == TransferState.Queued) {
                it.copy(state = TransferState.Failed, message = message, speed = 0.0)
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
