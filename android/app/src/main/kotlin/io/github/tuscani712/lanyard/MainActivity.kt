package io.github.tuscani712.lanyard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tuscani712.lanyard.core.ThemeMode
import io.github.tuscani712.lanyard.net.AndroidMeteredNetwork
import io.github.tuscani712.lanyard.ui.LanyardApp
import io.github.tuscani712.lanyard.ui.theme.LanyardTheme
import io.github.tuscani712.lanyard.transfer.TransferManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        IdentityHolder.init(applicationContext)
        SettingsHolder.init(applicationContext)
        TransferManager.init(application, AndroidMeteredNetwork(applicationContext))
        PeerService.init(applicationContext)
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

    override fun onStart() {
        super.onStart()
        // The phone-side peer server runs while the app is in the foreground on
        // any screen. If it is backgrounded during a transfer, PeerService keeps
        // it up (see onStop) until the transfer finishes.
        PeerService.start(applicationContext)
    }

    override fun onStop() {
        // Backgrounded: keep the peer listener and mDNS advertisement up while a
        // transfer is running (the transfer foreground service keeps the process
        // alive), so the phone is still reachable. PeerService stops it once the
        // last transfer drains, or immediately when nothing is running.
        PeerService.onAppBackgrounded()
        super.onStop()
    }
}
