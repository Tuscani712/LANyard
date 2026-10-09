package io.github.tuscani712.lanyard

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.tuscani712.lanyard.core.KeepScreenOn
import io.github.tuscani712.lanyard.core.ThemeMode
import io.github.tuscani712.lanyard.net.AndroidMeteredNetwork
import io.github.tuscani712.lanyard.ui.LanyardApp
import io.github.tuscani712.lanyard.ui.theme.LanyardTheme
import io.github.tuscani712.lanyard.transfer.TransferManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** The last applied keep-screen-on state, so only real changes are logged. */
    private var lastKeepScreenOn: Boolean? = null

    /** True between onStart and onStop: only then may the window flag be held. */
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        IdentityHolder.init(applicationContext)
        SettingsHolder.init(applicationContext)
        TransferManager.init(application, AndroidMeteredNetwork(applicationContext))
        PeerService.init(applicationContext)
        observeKeepScreenOn()
        setContent {
            val settings by SettingsHolder.settings.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val dark = when (settings.theme) {
                ThemeMode.System -> systemDark
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            LanyardTheme(darkTheme = dark) {
                LanyardApp()
            }
        }
    }

    /**
     * While the setting is on and any transfer is live (Preparing included), hold
     * [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON] so the display cannot sleep
     * mid-transfer. Combining the settings flow with the transfer list means the
     * flag follows drains and setting changes immediately; each real on/off change
     * is logged through the shared diagnostics sink.
     */
    private fun observeKeepScreenOn() {
        lifecycleScope.launch {
            combine(SettingsHolder.settings, TransferManager.state) { settings, transfers ->
                val enabled = settings.keepScreenOn
                val live = KeepScreenOn.anyLiveTransfer(transfers)
                KeepScreenOn.decide(enabled, live) to KeepScreenOn.reason(enabled, live)
            }.distinctUntilChanged().collect { (keepOn, reason) ->
                if (started) applyKeepScreenOn(keepOn, reason)
            }
        }
    }

    /** Re-evaluates the decision from the current flows (used on every onStart). */
    private fun refreshKeepScreenOn() {
        if (!started) return
        val settings = SettingsHolder.settings.value
        val live = KeepScreenOn.anyLiveTransfer(TransferManager.state.value)
        applyKeepScreenOn(
            KeepScreenOn.decide(settings.keepScreenOn, live),
            KeepScreenOn.reason(settings.keepScreenOn, live),
        )
    }

    /** Sets the window flag and logs the transition; a no-op when unchanged. */
    private fun applyKeepScreenOn(keepOn: Boolean, reason: String) {
        if (keepOn == lastKeepScreenOn) return
        lastKeepScreenOn = keepOn
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        PeerService.diagnostics.record(KeepScreenOn.logLine(keepOn, reason))
    }

    override fun onStart() {
        super.onStart()
        started = true
        // The phone-side peer server runs while the app is in the foreground on
        // any screen. If it is backgrounded during a transfer, PeerService keeps
        // it up (see onStop) until the transfer finishes.
        PeerService.start(applicationContext)
        // A transfer may still be live from before the activity stopped: re-hold
        // the screen flag the moment we are visible again.
        refreshKeepScreenOn()
    }

    override fun onStop() {
        started = false
        // Never hold the screen awake while the activity is not visible.
        applyKeepScreenOn(false, KeepScreenOn.REASON_ACTIVITY_STOP)
        // Backgrounded: keep the peer listener and mDNS advertisement up while a
        // transfer is running (the transfer foreground service keeps the process
        // alive), so the phone is still reachable. PeerService stops it once the
        // last transfer drains, or immediately when nothing is running.
        PeerService.onAppBackgrounded()
        super.onStop()
    }

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }
}
