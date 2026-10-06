package io.github.tuscani712.lanyard

import android.content.Context
import android.os.Build
import io.github.tuscani712.lanyard.core.FileIdentityStore
import io.github.tuscani712.lanyard.core.Identity
import io.github.tuscani712.lanyard.core.IdentityStore
import java.io.File

/**
 * Owns this device's long-term [Identity] for the app's lifetime.
 *
 * The key and certificate live in app-private storage (`filesDir`), which is not
 * readable by other apps, is excluded from backup (see `android:allowBackup`),
 * and is wiped on uninstall. Storage goes through the [IdentityStore] interface
 * so it can move to the Android Keystore later without touching callers.
 */
object IdentityHolder {
    private const val PREFS = "lanyard"
    private const val KEY_DEVICE_NAME = "device_name"

    private lateinit var appContext: Context
    private lateinit var store: IdentityStore
    private var initialized = false

    var identity: Identity? = null
        private set

    var deviceName: String = "Android"
        private set

    /** Loads the identity, generating and persisting one on first run. */
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        store = FileIdentityStore(File(appContext.filesDir, "identity"))

        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        deviceName = prefs.getString(KEY_DEVICE_NAME, null) ?: defaultName()

        identity = store.load() ?: Identity.generate(deviceName).also(store::save)
        initialized = true
    }

    fun setDeviceName(name: String) {
        val trimmed = name.trim().ifEmpty { defaultName() }
        deviceName = trimmed
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DEVICE_NAME, trimmed).apply()
    }

    /** Short, human-comparable form of the Device ID (first 16 hex, in groups). */
    fun shortFingerprint(): String =
        identity?.deviceId?.take(16)?.chunked(4)?.joinToString(" ") ?: "—"

    private fun defaultName(): String =
        Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
}
