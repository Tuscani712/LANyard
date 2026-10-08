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
    fun save(settings: AppSettings)
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
    override fun save(settings: AppSettings) {
        val dir = file.absoluteFile.parentFile
        dir?.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(gson.toJson(settings))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/** Formats a byte-per-second rate for display, in the chosen unit. */
fun formatSpeed(bytesPerSecond: Double, unit: SpeedUnit): String = when (unit) {
    SpeedUnit.MBps -> "%.1f MB/s".format(bytesPerSecond / 1_048_576.0)
    SpeedUnit.Mbps -> "%.1f Mbps".format(bytesPerSecond * 8.0 / 1_000_000.0)
}
