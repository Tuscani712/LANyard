package io.github.tuscani712.lanyard

import android.content.Context
import io.github.tuscani712.lanyard.core.AppSettings
import io.github.tuscani712.lanyard.core.JsonFileSettingsStore
import io.github.tuscani712.lanyard.core.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Owns the user's [AppSettings] for the app's lifetime, mirroring each change to
 * app-private storage. The file lives in `filesDir`, so it is not readable by
 * other apps and is wiped on uninstall. Storage goes through [SettingsStore] so
 * it can be swapped out without touching callers.
 */
object SettingsHolder {
    private var store: SettingsStore? = null
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun init(context: Context) {
        if (store != null) return
        val s = JsonFileSettingsStore(File(context.applicationContext.filesDir, "settings.json"))
        store = s
        _settings.value = s.load()
    }

    /** Applies [block] to the current settings and persists the result. */
    fun update(block: (AppSettings) -> AppSettings) {
        val next = block(_settings.value)
        _settings.value = next
        store?.save(next)
    }
}
