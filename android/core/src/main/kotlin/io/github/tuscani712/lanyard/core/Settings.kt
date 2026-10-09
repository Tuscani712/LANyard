package io.github.tuscani712.lanyard.core

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Which color scheme the app renders, or the system default. */
enum class ThemeMode { System, Light, Dark }

/** How transfer speeds are shown. */
enum class SpeedUnit { MBps, Mbps }

/** User-facing preferences that persist across restarts. */
data class AppSettings(
    val theme: ThemeMode = ThemeMode.System,
    val speedUnit: SpeedUnit = SpeedUnit.MBps,
    val notifications: Boolean = true,
    val soundOnComplete: Boolean = false,
    /** Refuse transfers on a metered or mobile connection. */
    val wifiOnly: Boolean = true,
    /**
     * Hold the screen awake while any transfer is live, so a long send, pull or
     * receive is not interrupted by the display turning off. Default on.
     */
    val keepScreenOn: Boolean = true,
    /** Persisted SAF tree URI for downloads, or null to ask each time. */
    val downloadFolder: String? = null,
    /** Bandwidth cap in MB/s; 0 means unlimited. */
    val bandwidthLimitMBps: Int = 0,
    /**
     * The port the peer listener bound last time, tried first on the next
     * launch so a paired desktop's stored address stays valid. 0 means no
     * preference (use an ephemeral port).
     */
    val preferredPort: Int = 0,
)

/** Persists [AppSettings]. Implementations must never throw on read. */
interface SettingsStore {
    fun load(): AppSettings

    /**
     * Persists [settings]. Returns a failed [Result] rather than throwing so a
     * caller can surface "couldn't save" without crashing the UI.
     */
    fun save(settings: AppSettings): Result<Unit>
}

/**
 * A [SettingsStore] backed by a single JSON file. Writes go to a sibling temp
 * file and are atomically renamed into place. A missing, unreadable or partial
 * file reads as [AppSettings] defaults, so a bad file can never keep the app
 * from starting.
 */
class JsonFileSettingsStore(private val file: File) : SettingsStore {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    @Synchronized
    override fun load(): AppSettings {
        if (!file.isFile) return AppSettings()
        return try {
            gson.fromJson(file.readText(), AppSettings::class.java) ?: AppSettings()
        } catch (_: JsonSyntaxException) {
            AppSettings()
        }
    }

    @Synchronized
    override fun save(settings: AppSettings): Result<Unit> = runCatching {
        val dir = file.absoluteFile.parentFile
        dir?.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(gson.toJson(settings))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * Formats a byte-per-second rate for display, in the chosen unit.
 *
 * The byte-rate formatter scales the unit (B/s, KB/s, MB/s, GB/s) so a transfer
 * that is genuinely moving never reads as the misleading "0.0 MB/s": a slow but
 * live receive shows e.g. "812 B/s" or "3.4 KB/s" instead. The bit-rate formatter
 * scales the same way (bps, Kbps, Mbps). Both are shared by the Transfers row,
 * the notification and the diagnostics, so no surface can drift.
 */
fun formatSpeed(bytesPerSecond: Double, unit: SpeedUnit): String = when (unit) {
    SpeedUnit.MBps -> formatByteRate(bytesPerSecond)
    SpeedUnit.Mbps -> formatBitRate(bytesPerSecond)
}

/** A byte rate scaled to the largest unit that keeps it at or above 1.0. */
fun formatByteRate(bytesPerSecond: Double): String {
    val v = bytesPerSecond.coerceAtLeast(0.0)
    if (v.isNaN() || v.isInfinite()) return "0 B/s"
    return when {
        v < 1_024.0 -> "%.0f B/s".format(v)
        v < 1_048_576.0 -> "%.1f KB/s".format(v / 1_024.0)
        v < 1_073_741_824.0 -> "%.1f MB/s".format(v / 1_048_576.0)
        else -> "%.1f GB/s".format(v / 1_073_741_824.0)
    }
}

/** A bit rate (from bytes/second) scaled to the largest sensible unit. */
fun formatBitRate(bytesPerSecond: Double): String {
    val bits = (bytesPerSecond.coerceAtLeast(0.0)) * 8.0
    if (bits.isNaN() || bits.isInfinite()) return "0 bps"
    return when {
        bits < 1_000.0 -> "%.0f bps".format(bits)
        bits < 1_000_000.0 -> "%.1f Kbps".format(bits / 1_000.0)
        bits < 1_000_000_000.0 -> "%.1f Mbps".format(bits / 1_000_000.0)
        else -> "%.1f Gbps".format(bits / 1_000_000_000.0)
    }
}
