package io.github.tuscani712.lanyard.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tuscani712.lanyard.DevicesViewModel
import io.github.tuscani712.lanyard.IdentityHolder
import io.github.tuscani712.lanyard.PairedStatus
import io.github.tuscani712.lanyard.SettingsHolder
import io.github.tuscani712.lanyard.core.Bandwidth
import io.github.tuscani712.lanyard.core.PairedPeer
import io.github.tuscani712.lanyard.core.SpeedUnit
import io.github.tuscani712.lanyard.core.ThemeMode
import io.github.tuscani712.lanyard.transfer.TransferManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val REPO_URL = "https://github.com/Tuscani712/LANyard"

@Composable
fun SettingsScreen(padding: PaddingValues, vm: DevicesViewModel) {
    val context = LocalContext.current
    val settings by SettingsHolder.settings.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf(IdentityHolder.deviceName) }
    var unpairTarget by remember { mutableStateOf<PairedPeer?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var showTroubleshoot by remember { mutableStateOf(false) }

    val folderUri = settings.downloadFolder?.let(Uri::parse)
    val folderName = remember(folderUri) { folderUri?.let { DocumentFile.fromTreeUri(context, it)?.name } }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            vm.rememberTree(uri)
        }
    }

    DisposableEffect(Unit) { vm.refreshPaired(); onDispose { } }

    if (showLicenses) {
        LicensesScreen(padding) { showLicenses = false }
        return
    }
    if (showTroubleshoot) {
        TroubleshootScreen(padding, vm) { showTroubleshoot = false }
        return
    }

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
        val deviceId = IdentityHolder.identity?.deviceId
        IdRow("Device ID", deviceId ?: "—")
        IdRow("Fingerprint", deviceId?.chunked(4)?.joinToString(" ") ?: "—")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                if (deviceId != null) copyToClipboard(context, "LANyard identity", deviceId)
            }, enabled = deviceId != null) { Text("Copy") }
            OutlinedButton(onClick = {
                if (deviceId != null) shareText(context, "LANyard Device ID", "$deviceId")
            }, enabled = deviceId != null) { Text("Share") }
        }

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

        SectionLabel("Transfers")
        SwitchRow(
            title = "Wi-Fi only",
            subtitle = "Refuse transfers on a metered or mobile connection",
            checked = settings.wifiOnly,
            onCheckedChange = { on -> SettingsHolder.update { it.copy(wifiOnly = on) } },
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Default download folder", style = MaterialTheme.typography.bodyLarge)
                Text(
                    folderName ?: "Not set",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (folderUri != null) {
                TextButton(onClick = {
                    SettingsHolder.update { it.copy(downloadFolder = null) }
                }) { Text("Clear") }
            }
            OutlinedButton(onClick = { folderPicker.launch(folderUri) }) {
                Text(if (folderUri == null) "Choose" else "Change")
            }
        }
        BandwidthRow(settings.bandwidthLimitMBps) { mbps ->
            SettingsHolder.update { it.copy(bandwidthLimitMBps = mbps) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { confirmClear = true }) { Text("Clear history") }
            OutlinedButton(onClick = { confirmCancel = true }) { Text("Cancel all") }
        }

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
        AboutRow()
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { showTroubleshoot = true }) { Text("Troubleshoot") }
        TextButton(onClick = { showLicenses = true }) { Text("Third-party licenses") }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Clear transfer history?",
            message = "Finished transfers are removed. Transfers still running are kept.",
            confirmLabel = "Clear",
            onConfirm = { TransferManager.clearFinished(); confirmClear = false },
            onDismiss = { confirmClear = false },
        )
    }
    if (confirmCancel) {
        ConfirmDialog(
            title = "Cancel all transfers?",
            message = "Every running or queued transfer is stopped. History is kept.",
            confirmLabel = "Cancel all",
            onConfirm = { TransferManager.cancelAllRunning(); confirmCancel = false },
            onDismiss = { confirmCancel = false },
        )
    }
    unpairTarget?.let { peer ->
        ConfirmDialog(
            title = "Unpair ${peer.name.ifEmpty { "this device" }}?",
            message = "This removes the pairing on both devices. Any transfer to it in progress is cancelled.",
            confirmLabel = "Unpair",
            onConfirm = {
                vm.unpair(peer)
                unpairTarget = null
            },
            onDismiss = { unpairTarget = null },
        )
    }
}

@Composable
fun LicensesScreen(padding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    val text by produceState("Loading…") {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("THIRD_PARTY.md").bufferedReader().use { it.readText() }
            }.getOrDefault("Third-party notices are unavailable.")
        }
    }
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to settings")
            }
            Text("Third-party licenses", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            text,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun AboutRow() {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "—"
    }
    Column {
        Text("Version $version", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Updates come from GitHub Releases. This app has no in-app updater.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "License: AGPL-3.0 (GNU Affero General Public License v3.0).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))) }
        }) { Text(REPO_URL) }
    }
}

@Composable
private fun IdRow(label: String, value: String) {
    Spacer(Modifier.height(8.dp))
    Text(label, style = MaterialTheme.typography.bodyMedium)
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun BandwidthRow(current: Int, onChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Bandwidth limit", style = MaterialTheme.typography.bodyLarge)
            Text(
                "0 = unlimited",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                val digits = raw.filter { it.isDigit() }.take(6)
                text = digits
                onChange(Bandwidth.clampMBps(digits.toIntOrNull() ?: 0))
            },
            singleLine = true,
            suffix = { Text("MB/s") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(150.dp),
        )
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

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { Button(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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

private fun copyToClipboard(context: Context, label: String, value: String) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    manager.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun shareText(context: Context, title: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(intent, title)) }
}
