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
}
