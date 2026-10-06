package io.github.tuscani712.lanyard.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tuscani712.lanyard.DevicesViewModel

private data class Tab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("Devices", Icons.Filled.Devices),
    Tab("Transfers", Icons.Filled.SwapVert),
    Tab("Settings", Icons.Filled.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanyardApp(viewModel: DevicesViewModel = viewModel()) {
    var selected by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(TABS[selected].label) })
        },
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { inner ->
        when (selected) {
            0 -> DevicesScreen(inner, viewModel)
            1 -> TransfersScreen(inner)
            else -> SettingsScreen(inner, viewModel)
        }
    }
}
