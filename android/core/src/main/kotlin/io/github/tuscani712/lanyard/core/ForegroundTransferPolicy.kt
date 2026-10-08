package io.github.tuscani712.lanyard.core

/**
 * A lock the transfer foreground service must hold while work is active. A
 * transfer needs a partial [WAKE] lock (the CPU must keep running with the
 * screen off) and a [WIFI] lock (the radio must not sleep between packets), so
 * that second the screen turns off a long transfer keeps moving.
 */
enum class TransferLock { WAKE, WIFI }

/** The app-lifecycle moments that could have torn a transfer down. */
enum class TransferLifecycleEvent {
    /** The system Back gesture/button while the Transfers screen is showing. */
    BACK,

    /** The task was removed from Recents (the app was swiped away). */
    SWIPE_AWAY,

    /** `onStop`: the app simply left the foreground. */
    BACKGROUNDED,
}

/**
 * What the foreground service should be doing for the current active set:
 * whether it must run, which locks to hold, and the ongoing notification's
 * content. Kept pure (no Android types) so the lock/service/notification policy
 * is unit-tested without an emulator.
 */
data class ForegroundTransferState(
    val serviceRunning: Boolean,
    val locks: Set<TransferLock>,
    val title: String,
    val text: String,
    val sending: Boolean,
    val done: Long,
    val total: Long,
)

/**
 * What a lifecycle event does to the transfers that are still active. Back and
 * a swipe-away must never silently cancel a transfer, and a swipe-away while a
 * transfer runs must leave the foreground service (and its locks) up.
 */
data class TransferLifecycleDecision(
    val cancelTransfers: Boolean,
    val keepService: Boolean,
)

/**
 * Whether an interrupted row may be resumed, and whether the resume starts from
 * the bytes already on disk (the `.part`/`.lanpart` partial) rather than zero.
 */
data class ResumeDecision(
    val resume: Boolean,
    val fromPartial: Boolean,
    val text: String,
)

/**
 * The pure half of the background-transfer guarantee. Given the transfer rows,
 * it answers the three questions the Android side asks:
 *
 *  1. [decide] — must [TransferService] be foreground, which locks must it
 *     hold, and what does the ongoing notification say? (locks are acquired for
 *     the first active transfer and released when the last one ends)
 *  2. [lifecycle] — does Back / a swipe-away cancel, and does the service stay
 *     up? (never cancel; stay up while anything is active)
 *  3. [resumeDecision] — may an interrupted row resume now, and from a partial?
 *
 * Every direction (send, pull/download and receive) is a row with a live state,
 * so all three are treated identically: one active row of any direction means
 * the service runs and both locks are held.
 */
class ForegroundTransferPolicy {

    /** True while [record] is still active work the service must protect. */
    private fun TransferRecord.live(): Boolean =
        state == TransferState.Running || state == TransferState.Queued

    /**
     * The service/notification state for [records]. Only live rows count; a
     * finished row contributes nothing (so a drained list stops the service and
     * drops both locks).
     */
    fun decide(records: List<TransferRecord>, unit: SpeedUnit = SpeedUnit.MBps): ForegroundTransferState {
        val active = records.filter { it.live() }
        if (active.isEmpty()) {
            return ForegroundTransferState(
                serviceRunning = false,
                locks = emptySet(),
                title = "LANyard",
                text = "Preparing…",
                sending = false,
                done = 0L,
                total = 0L,
            )
        }
        val done = active.sumOf { it.done }
        val total = active.sumOf { it.total }
        val sending = active.any { it.direction == "send" }
        val text = when {
            active.size == 1 -> {
                val a = active[0]
                if (a.finishing) {
                    val size = formatBytes(a.finishingBytes ?: a.total)
                    "Finishing… " + a.label + " · " + size
                } else {
                    val rate = formatRateAndEta(a.speed, a.etaSeconds, unit)
                    val suffix = if (rate.isNotEmpty()) " · $rate" else ""
                    (if (sending) "Sending " else "Receiving ") + a.label + suffix
                }
            }
            else -> {
                val combined = active.sumOf { it.speed }
                val remaining = active.sumOf { (it.total - it.done).coerceAtLeast(0L) }
                val rate = formatRateAndEta(combined, estimateEtaSeconds(remaining, combined), unit)
                val suffix = if (rate.isNotEmpty()) " · $rate" else ""
                val finishing = if (active.any { it.finishing }) " · Finishing…" else ""
                "${active.size} transfers$suffix$finishing"
            }
        }
        return ForegroundTransferState(
            serviceRunning = true,
            locks = setOf(TransferLock.WAKE, TransferLock.WIFI),
            title = "LANyard",
            text = text,
            sending = sending,
            done = done,
            total = total,
        )
    }

    /**
     * The transfer effect of a lifecycle event. Back and a swipe-away never
     * cancel a transfer, and the service stays up while anything is active —
     * a swiped-away app with a running transfer keeps transferring.
     */
    fun lifecycle(event: TransferLifecycleEvent, records: List<TransferRecord>): TransferLifecycleDecision {
        val active = records.any { it.live() }
        // The event is intentionally not allowed to change the answer: no user
        // navigation may silently cancel a transfer. It is a parameter so the
        // rule is exercised for each event in tests.
        @Suppress("UNUSED_EXPRESSION")
        event
        return TransferLifecycleDecision(cancelTransfers = false, keepService = active)
    }

    /**
     * Whether an [interrupted](INTERRUPTED_MESSAGE) row should resume now. A row
     * that is not an interrupted one never resumes. When the peer is reachable
     * the resume may run; with bytes already spooled/`.part`-written it resumes
     * from the partial, otherwise from the beginning.
     */
    fun resumeDecision(
        record: TransferRecord?,
        peerReachable: Boolean,
        partialBytes: Long = 0L,
    ): ResumeDecision {
        val interrupted = record != null &&
            record.state == TransferState.Failed &&
            record.message == INTERRUPTED_MESSAGE
        if (!interrupted) return ResumeDecision(resume = false, fromPartial = false, text = record?.message ?: "")
        return ResumeDecision(
            resume = peerReachable,
            fromPartial = peerReachable && partialBytes > 0L,
            text = INTERRUPTED_MESSAGE,
        )
    }

    companion object {
        /**
         * The row text for a transfer that stopped because the process died, the
         * network changed, or the app left the foreground. It is a `Failed` row
         * so it is clearly not running, but the message says it is not lost: the
         * partial file is kept and the transfer resumes automatically.
         */
        const val INTERRUPTED_MESSAGE = "Interrupted – will resume"
    }
}
