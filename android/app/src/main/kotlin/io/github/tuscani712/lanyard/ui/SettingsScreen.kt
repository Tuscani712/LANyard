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
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import io.github.tuscani712.lanyard.DevicesViewModel
import io.github.tuscani712.lanyard.IdentityHolder
import io.github.tuscani712.lanyard.PairedStatus
import io.github.tuscani712.lanyard.PeerService
import io.github.tuscani712.lanyard.SettingsHolder
import io.github.tuscani712.lanyard.core.AppSettings
import io.github.tuscani712.lanyard.core.Bandwidth
import io.github.tuscani712.lanyard.core.Diagnostics
import io.github.tuscani712.lanyard.core.InboxPaths
import io.github.tuscani712.lanyard.core.PairedPeer
import io.github.tuscani712.lanyard.core.SettingsCategory
import io.github.tuscani712.lanyard.core.SpeedUnit
import io.github.tuscani712.lanyard.core.ThemeMode
import io.github.tuscani712.lanyard.transfer.TransferManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private const val REPO_URL = "https://github.com/Tuscani712/LANyard"

/** Applies a settings transform and persists it; shared by every tab. */
private typealias SettingsUpdate = ((AppSettings) -> AppSettings) -> Unit

@Composable
fun SettingsScreen(padding: PaddingValues, vm: DevicesViewModel) {
    val context = LocalContext.current
    val settings by SettingsHolder.settings.collectAsStateWithLifecycle()
    val saveError by SettingsHolder.saveError.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf(IdentityHolder.deviceName) }
    var unpairTarget by remember { mutableStateOf<PairedPeer?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var showTroubleshoot by remember { mutableStateOf(false) }

    // Hoisted above the Troubleshoot/Licenses early returns, so rotation or
    // process death keeps the tab and Back from a sub-screen lands on it.
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var savedTab by rememberSaveable { mutableIntStateOf(-1) }

    // Changing a persisted setting (or the device name) marks this tab saved.
    // The first composition is not a change.
    var lastSettings by remember { mutableStateOf(settings) }
    var lastName by remember { mutableStateOf(name) }
    LaunchedEffect(settings, name) {
        if (settings != lastSettings || name != lastName) {
            lastSettings = settings
            lastName = name
            savedTab = selectedTab
        }
    }

    val onSettings: SettingsUpdate = { block -> SettingsHolder.update(block) }
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

    SettingsTabs(
        selectedTab = selectedTab,
        onTabSelected = { selectedTab = it },
        savedTab = savedTab,
        saveError = saveError,
        modifier = Modifier.padding(padding),
    ) { category ->
        when (category) {
            SettingsCategory.GENERAL -> GeneralTab(
                name = name,
                onNameChange = {
                    name = it
                    IdentityHolder.setDeviceName(it)
                },
                settings = settings,
                onSettings = onSettings,
            )

            SettingsCategory.RECEIVING -> ReceivingTab(
                settings = settings,
                onSettings = onSettings,
                folderUri = folderUri,
                folderName = folderName,
                onPickFolder = { folderPicker.launch(folderUri) },
                onClearFolder = { onSettings { it.copy(downloadFolder = null) } },
                onClearHistory = { confirmClear = true },
                onCancelAll = { confirmCancel = true },
            )

            SettingsCategory.NETWORK_DISCOVERY -> NetworkDiscoveryTab(
                settings = settings,
                onSettings = onSettings,
            )

            SettingsCategory.NOTIFICATIONS -> NotificationsTab(
                settings = settings,
                onSettings = onSettings,
            )

            SettingsCategory.PAIRING_SECURITY -> PairingSecurityTab(
                paired = state.paired,
                onUnpair = { unpairTarget = it },
            )

            SettingsCategory.LOGS_DIAGNOSTICS -> LogsDiagnosticsTab(
                context = context,
                onOpenTroubleshoot = { showTroubleshoot = true },
            )

            SettingsCategory.ABOUT -> AboutTab(
                onOpenLicenses = { showLicenses = true },
            )
        }
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
    state.unpairNotice?.let { message ->
        AlertDialog(
            onDismissRequest = { vm.dismissUnpairNotice() },
            title = { Text("Unpaired") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { vm.dismissUnpairNotice() }) { Text("OK") } },
        )
    }
}

@Composable
private fun GeneralTab(
    name: String,
    onNameChange: (String) -> Unit,
    settings: AppSettings,
    onSettings: SettingsUpdate,
) {
    val context = LocalContext.current
    SectionLabel("Device")
    Text("Device name", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
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
        onSelect = { choice -> onSettings { it.copy(theme = choice) } },
    )
    Spacer(Modifier.height(12.dp))
    ChoiceRow(
        label = "Speed unit",
        options = SpeedUnit.entries,
        selected = settings.speedUnit,
        optionLabel = { if (it == SpeedUnit.MBps) "MB/s" else "Mbps" },
        onSelect = { choice -> onSettings { it.copy(speedUnit = choice) } },
    )
}

@Composable
private fun ReceivingTab(
    settings: AppSettings,
    onSettings: SettingsUpdate,
    folderUri: Uri?,
    folderName: String?,
    onPickFolder: () -> Unit,
    onClearFolder: () -> Unit,
    onClearHistory: () -> Unit,
    onCancelAll: () -> Unit,
) {
    SectionLabel("Downloads")
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Default download folder", style = MaterialTheme.typography.bodyLarge)
            Text(
                folderName ?: "${InboxPaths.DEFAULT_LABEL} (default)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (folderUri != null) {
            TextButton(onClick = onClearFolder) { Text("Use default") }
        }
        OutlinedButton(onClick = onPickFolder) {
            Text(if (folderUri == null) "Choose" else "Change")
        }
    }
    BandwidthRow(settings.bandwidthLimitMBps) { mbps ->
        onSettings { it.copy(bandwidthLimitMBps = mbps) }
    }

    SectionLabel("Transfer history")
    Text(
        "Finished transfers are kept until you clear them. Running transfers can always be stopped.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onClearHistory) { Text("Clear history") }
        OutlinedButton(onClick = onCancelAll) { Text("Cancel all") }
    }
}

@Composable
private fun NetworkDiscoveryTab(
    settings: AppSettings,
    onSettings: SettingsUpdate,
) {
    SectionLabel("Network")
    SwitchRow(
        title = "Wi-Fi only",
        subtitle = "Refuse transfers on a metered or mobile connection",
        checked = settings.wifiOnly,
        onCheckedChange = { on -> onSettings { it.copy(wifiOnly = on) } },
    )
    Spacer(Modifier.height(12.dp))
    PortRow(settings.preferredPort) { onSettings { it.copy(preferredPort = 0) } }

    SectionLabel("Discovery")
    Text(
        "Paired devices find this phone automatically on the local network (mDNS). " +
            "The listener port is remembered so a paired desktop's saved address keeps working.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PortRow(port: Int, onReset: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Listener port", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (port == 0) "Automatic" else "Preferred: $port",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (port != 0) {
            TextButton(onClick = onReset) { Text("Reset") }
        }
    }
}

@Composable
private fun NotificationsTab(
    settings: AppSettings,
    onSettings: SettingsUpdate,
) {
    SectionLabel("Notifications")
    SwitchRow(
        title = "Notifications",
        subtitle = "When a transfer finishes or fails",
        checked = settings.notifications,
        onCheckedChange = { on -> onSettings { it.copy(notifications = on) } },
    )
    SwitchRow(
        title = "Sound when a transfer finishes",
        subtitle = null,
        checked = settings.soundOnComplete,
        onCheckedChange = { on -> onSettings { it.copy(soundOnComplete = on) } },
    )
    NotificationPermissionRow()
}

@Composable
private fun PairingSecurityTab(
    paired: List<PairedStatus>,
    onUnpair: (PairedPeer) -> Unit,
) {
    SectionLabel("Paired devices")
    if (paired.isEmpty()) {
        Text(
            "No paired devices.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        paired.forEach { status ->
            PairedSettingRow(status, onUnpair = { onUnpair(status.peer) })
            HorizontalDivider()
        }
    }

    SectionLabel("Shared with paired devices")
    ShareSection()
}

@Composable
private fun LogsDiagnosticsTab(
    context: Context,
    onOpenTroubleshoot: () -> Unit,
) {
    SectionLabel("Diagnostics")
    Text(
        "A rolling log of connection, pairing and transfer events. It never contains " +
            "file contents, full fingerprints or secrets.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Button(onClick = onOpenTroubleshoot) { Text("Troubleshoot") }
    Spacer(Modifier.height(4.dp))
    Row {
        TextButton(onClick = {
            copyToClipboard(
                context,
                "LANyard log",
                Diagnostics.copyLog(
                    PeerService.diagnostics.snapshot(),
                    PeerService.diagnostics.persistedLog(),
                ),
            )
        }) { Text("Copy log") }
        TextButton(onClick = { shareLogFile(context) }) { Text("Share log") }
    }
}

@Composable
private fun AboutTab(onOpenLicenses: () -> Unit) {
    SectionLabel("About")
    AboutRow()
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onOpenLicenses) { Text("Third-party licenses") }
}

@Composable
fun LicensesScreen(padding: PaddingValues, onBack: () -> Unit) {
    BackHandler { onBack() }
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
internal fun ConfirmDialog(
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

/** Minimal share list: pick a folder, see what is shared, stop sharing. */
@Composable
private fun ShareSection() {
    val context = LocalContext.current
    val shares by PeerService.shares.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val label = DocumentFile.fromTreeUri(context, uri)?.name ?: "Shared folder"
            PeerService.addFolderShare(label, uri)
        }
    }
    Text(
        "Folders paired devices can pull into their own folder.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { picker.launch(null) }) { Text("Share a folder…") }
    if (shares.isEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("Nothing is shared.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        shares.forEach { s ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(s.label, style = MaterialTheme.typography.bodyLarge)
                    Text("until stopped", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { PeerService.stopShare(s.id) }) { Text("Stop") }
            }
        }
    }
}
