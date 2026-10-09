package io.github.tuscani712.lanyard.core

import kotlin.math.ceil

/**
 * The one shared definition of transfer progress timing, in :core so the
 * Transfers row and the ongoing notification cannot drift apart: both read the
 * same window and refresh constants.
 */
object TransferTuning {
    /** The rolling window the shown rate is averaged over (raised from 3 s). */
    const val WINDOW_MS = 5_000L

    /**
     * A gap this long since the last progress callback is a stall: the shown
     * rate goes blank rather than spanning the pause. Equal to the window.
     */
    const val STALL_AFTER_MS = WINDOW_MS

    /** The shown rate/ETA text is recomputed at most this often (~1 update/s). */
    const val DISPLAY_REFRESH_MS = 1_000L
}

/**
 * The time left, in whole seconds, at the smoothed [bytesPerSecond] rate, or
 * null when there is no honest estimate (no rate yet, a stall, or the transfer
 * is already complete).
 *
 * It is always `remaining / smoothed rate`, never `remaining / instantaneous`,
 * so a burst that arrives after a pause cannot make the ETA jump.
 */
fun estimateEtaSeconds(remainingBytes: Long, bytesPerSecond: Double): Long? {
    if (bytesPerSecond <= 0.0 || bytesPerSecond.isNaN() || bytesPerSecond.isInfinite()) return null
    if (remainingBytes <= 0L) return null
    val seconds = ceil(remainingBytes / bytesPerSecond)
    return if (seconds.isInfinite() || seconds >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else seconds.toLong()
}

/**
 * The honest ETA for a multi-file transfer: the larger of the byte-based
 * estimate and the file-based estimate.
 *
 * A transfer can be "fast on bytes" (a few large files) yet still have many
 * tiny files to open, hash and place; or byte-slow but file-quick. Taking the
 * max of the two keeps the estimate from under-promising either way. Either
 * side may be null (no honest rate yet); the other is then used, and both null
 * yields null so the UI stays blank rather than inventing a number.
 */
fun estimateCombinedEtaSeconds(
    remainingBytes: Long,
    bytesPerSecond: Double,
    remainingFiles: Long,
    filesPerSecond: Double,
): Long? {
    val byteEta = estimateEtaSeconds(remainingBytes, bytesPerSecond)
    val fileEta = estimateEtaSeconds(remainingFiles, filesPerSecond)
    return when {
        byteEta == null -> fileEta
        fileEta == null -> byteEta
        else -> maxOf(byteEta, fileEta)
    }
}

/**
 * A rolling files-per-second estimate, mirroring [SpeedMeter] but sampling a
 * cumulative count of completed files instead of bytes. [SpeedMeter] already
 * implements the window/reset/stall semantics we need, so this is a thin,
 * separately-documented wrapper: the two rates stay independent, so a byte
 * stall (which blanks the byte rate) need not blank a file-count ETA that is
 * still meaningful.
 */
class FileRateMeter(
    windowMillis: Long = TransferTuning.WINDOW_MS,
    stallAfterMillis: Long = TransferTuning.STALL_AFTER_MS,
) {
    private val meter = SpeedMeter(windowMillis = windowMillis, stallAfterMillis = stallAfterMillis)

    /** See [SpeedMeter.sample]; [cumulativeFiles] is the count of finished files. */
    @Synchronized
    fun sample(nowMillis: Long, cumulativeFiles: Long): Double? = meter.sample(nowMillis, cumulativeFiles)

    /** Forgets every sample; the next [sample] behaves like a fresh start. */
    @Synchronized
    fun reset() = meter.reset()
}

/**
 * A compact remaining-time string: `45s`, `2m 14s`, or `1h 2m` (seconds are
 * dropped once an hour is on the clock).
 */
fun formatEta(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val secs = s % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${secs}s"
        else -> "${secs}s"
    }
}

/**
 * The shared "rate · ETA" text, blank when there is no honest rate, so a fresh
 * start or a resume stays empty instead of showing a bogus value.
 */
fun formatRateAndEta(bytesPerSecond: Double, etaSeconds: Long?, unit: SpeedUnit): String {
    if (bytesPerSecond <= 0.0) return ""
    val speed = formatSpeed(bytesPerSecond, unit)
    return if (etaSeconds != null) "$speed · ETA ${formatEta(etaSeconds)}" else speed
}

/**
 * A compact human size (`512 B`, `3.4 KB`, `412.3 MB`), shared by the transfer
 * row and the ongoing notification so the "Finishing… · <size>" text cannot
 * drift between them.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return "%.1f %s".format(value, units[unit])
}

/**
 * The pure rate+ETA shown for one transfer. It owns a [SpeedMeter] and feeds it
 * every progress callback (the sampling rate is unchanged), but only recomputes
 * the shown rate and ETA once per [TransferTuning.DISPLAY_REFRESH_MS], so the
 * row and the notification redraw about once a second instead of ~20 times.
 *
 * The ETA is always `remaining / shownRate`: the same smoothed rate the UI
 * shows, never an instantaneous sample. A stall therefore blanks both, and a
 * burst after a pause cannot make the ETA jump.
 *
 * Synchronized because one instance is kept per transfer, and every file body
 * of a concurrent receive samples it from its own server thread.
 */
class RateEtaDisplay(
    windowMillis: Long = TransferTuning.WINDOW_MS,
    private val refreshMillis: Long = TransferTuning.DISPLAY_REFRESH_MS,
) {
    private val meter = SpeedMeter(windowMillis = windowMillis, stallAfterMillis = windowMillis)
    private val fileMeter = FileRateMeter(windowMillis = windowMillis, stallAfterMillis = windowMillis)
    private var shownAt: Long? = null
    private var snapshot = Snapshot(0.0, null)

    /**
     * Feeds one progress callback. The meter is sampled every call; the returned
     * [Snapshot] only changes when at least [refreshMillis] have passed, so a
     * caller that renders it redraws at most ~once a second.
     *
     * [doneFiles]/[totalFiles] are optional (0 means "unknown"): when known, a
     * second rolling files-per-second rate feeds a file-based ETA, and the shown
     * ETA is the larger of the byte-based and file-based estimates.
     */
    @Synchronized
    fun sample(nowMillis: Long, done: Long, total: Long, doneFiles: Int = 0, totalFiles: Int = 0): Snapshot {
        val rate = meter.sample(nowMillis, done)
        val fileRate = if (totalFiles > 0) fileMeter.sample(nowMillis, doneFiles.toLong()) else null
        val due = shownAt?.let { nowMillis - it >= refreshMillis } ?: true
        if (due) {
            shownAt = nowMillis
            snapshot = Snapshot(
                bytesPerSecond = rate ?: 0.0,
                etaSeconds = estimateCombinedEtaSeconds(
                    remainingBytes = (total - done).coerceAtLeast(0L),
                    bytesPerSecond = rate ?: 0.0,
                    remainingFiles = (totalFiles - doneFiles).coerceAtLeast(0).toLong(),
                    filesPerSecond = fileRate ?: 0.0,
                ),
            )
        }
        return snapshot
    }

    /** Forgets every sample; the next [sample] behaves like a fresh start. */
    @Synchronized
    fun reset() {
        meter.reset()
        fileMeter.reset()
        shownAt = null
        snapshot = Snapshot(0.0, null)
    }

    /** The rate and ETA last shown (throttled to one update per refresh interval). */
    data class Snapshot(val bytesPerSecond: Double, val etaSeconds: Long?) {
        fun text(unit: SpeedUnit): String = formatRateAndEta(bytesPerSecond, etaSeconds, unit)
    }
}
