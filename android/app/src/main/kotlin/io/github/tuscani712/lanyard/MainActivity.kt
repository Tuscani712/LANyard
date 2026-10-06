package io.github.tuscani712.lanyard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.tuscani712.lanyard.ui.LanyardApp
import io.github.tuscani712.lanyard.ui.theme.LanyardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        IdentityHolder.init(applicationContext)
        setContent {
            LanyardTheme {
                LanyardApp()
            }
        }
    }
}
