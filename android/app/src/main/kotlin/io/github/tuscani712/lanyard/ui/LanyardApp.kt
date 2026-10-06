package io.github.tuscani712.lanyard.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tuscani712.lanyard.DevicesViewModel
import io.github.tuscani712.lanyard.IdentityHolder
import io.github.tuscani712.lanyard.PairedStatus
import io.github.tuscani712.lanyard.SettingsHolder
import io.github.tuscani712.lanyard.core.PairedPeer
import io.github.tuscani712.lanyard.core.SpeedUnit
import io.github.tuscani712.lanyard.core.ThemeMode

private data class Tab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("Devices", Icons.Filled.Devices),
    Tab("Transfers", Icons.Filled.SwapVert),
    Tab("Settings", Icons.Filled.Settings),
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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

@Composable
private fun SettingsScreen(padding: PaddingValues, vm: DevicesViewModel) {
    val settings by SettingsHolder.settings.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf(IdentityHolder.deviceName) }
    var unpairTarget by remember { mutableStateOf<PairedPeer?>(null) }

    DisposableEffect(Unit) { vm.refreshPaired(); onDispose { } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
    ) {
        SectionLabel("Device")
        Text("Device name", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                IdentityHolder.setDeviceName(it)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text("This device's fingerprint", style = MaterialTheme.typography.bodyMedium)
        Text(
            IdentityHolder.shortFingerprint(),
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionLabel("Appearance")
        ChoiceRow(
            label = "Theme",
            options = ThemeMode.entries,
            selected = settings.theme,
            optionLabel = { it.name },
            onSelect = { choice -> SettingsHolder.update { it.copy(theme = choice) } },
        )
        Spacer(Modifier.height(12.dp))
        ChoiceRow(
            label = "Speed unit",
            options = SpeedUnit.entries,
            selected = settings.speedUnit,
            optionLabel = { if (it == SpeedUnit.MBps) "MB/s" else "Mbps" },
            onSelect = { choice -> SettingsHolder.update { it.copy(speedUnit = choice) } },
        )

        SectionLabel("Notifications")
        SwitchRow(
            title = "Notifications",
            subtitle = "When a transfer finishes or fails",
            checked = settings.notifications,
            onCheckedChange = { on -> SettingsHolder.update { it.copy(notifications = on) } },
        )
        SwitchRow(
            title = "Sound when a transfer finishes",
            subtitle = null,
            checked = settings.soundOnComplete,
            onCheckedChange = { on -> SettingsHolder.update { it.copy(soundOnComplete = on) } },
        )
        NotificationPermissionRow()

        SectionLabel("Paired devices")
        if (state.paired.isEmpty()) {
            Text(
                "No paired devices.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.paired.forEach { paired ->
                PairedSettingRow(paired, onUnpair = { unpairTarget = paired.peer })
                HorizontalDivider()
            }
        }

        SectionLabel("About")
        Text(
            "LANyard sends files and folders directly between devices on your local network.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Version ${Build.VERSION.RELEASE}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    unpairTarget?.let { peer ->
        AlertDialog(
            onDismissRequest = { unpairTarget = null },
            title = { Text("Unpair ${peer.name.ifEmpty { "this device" }}?") },
            text = { Text("This removes the pairing on both devices. Any transfer to it in progress is cancelled.") },
            confirmButton = {
                Button(onClick = {
                    vm.unpair(peer)
                    unpairTarget = null
                }) { Text("Unpair") }
            },
            dismissButton = { TextButton(onClick = { unpairTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(28.dp))
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun <T> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(optionLabel(option)) },
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun NotificationPermissionRow() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(notificationsGranted(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = notificationsGranted(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("System notifications", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (granted) "Granted" else "Not granted",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = { openNotificationSettings(context) }) { Text("Open settings") }
    }
}

@Composable
private fun PairedSettingRow(status: PairedStatus, onUnpair: () -> Unit) {
    val peer = status.peer
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Devices, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(peer.name.ifEmpty { "Unnamed device" }, style = MaterialTheme.typography.titleSmall)
            Text(
                shortFingerprint(peer.fingerprint),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val seen = if (status.online) "Online now" else "Offline"
            Text(
                "$seen · ${permissionsLabel(peer)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onUnpair) { Text("Unpair") }
    }
}

private fun permissionsLabel(peer: PairedPeer): String {
    val granted = buildList {
        if (peer.browse) add("browse")
        if (peer.push) add("push")
    }
    return if (granted.isEmpty()) "no permissions" else granted.joinToString(", ")
}

private fun notificationsGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
