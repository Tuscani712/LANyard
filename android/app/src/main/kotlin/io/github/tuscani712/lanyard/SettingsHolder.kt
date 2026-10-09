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

    /**
     * The message from the last failed save, or null once a save succeeds or
     * the error has been dismissed. Settings still apply in memory: a failure
     * means the change may not survive a restart, not that it was rejected.
     */
    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    fun init(context: Context) {
        if (store != null) return
        val s = JsonFileSettingsStore(File(context.applicationContext.filesDir, "settings.json"))
        store = s
        _settings.value = s.load()
    }

    /**
     * Applies [block] to the current settings, updates the in-memory flow, and
     * persists the result. The new settings are visible immediately; [saveError]
     * carries any storage failure. Auto-save is unchanged.
     */
    fun update(block: (AppSettings) -> AppSettings): Result<Unit> {
        val next = block(_settings.value)
        _settings.value = next
        val result = store?.save(next) ?: Result.success(Unit)
        _saveError.value = result.exceptionOrNull()?.let { error ->
            "Couldn't save settings: ${error.message ?: error::class.simpleName}"
        }
        return result
    }
}
