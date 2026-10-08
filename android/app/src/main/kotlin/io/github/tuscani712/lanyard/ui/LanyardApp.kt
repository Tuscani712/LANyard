package io.github.tuscani712.lanyard.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tuscani712.lanyard.DevicesViewModel
import io.github.tuscani712.lanyard.PeerService
import io.github.tuscani712.lanyard.PushApprovalRequest
import io.github.tuscani712.lanyard.core.Display
import io.github.tuscani712.lanyard.core.IncomingRequest
import io.github.tuscani712.lanyard.core.PairingSessions
import io.github.tuscani712.lanyard.core.Permissions

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
    val pending by PeerService.pending.collectAsStateWithLifecycle()
    val approval by PeerService.approval.collectAsStateWithLifecycle()
    val interrupted by PeerService.interrupted.collectAsStateWithLifecycle()
    val showingPairing = pending.isNotEmpty()

    // One prompt at a time, on any screen: pairing first, then a received-files
    // approval, then a note that a transfer was interrupted.
    pending.firstOrNull()?.let { request ->
        IncomingPairDialog(
            request = request,
            onAccept = { granted -> PeerService.accept(request.id, granted) },
            onDecline = { PeerService.decline(request.id) },
        )
    }
    if (!showingPairing) {
        approval?.let { req ->
            PushApprovalDialog(
                request = req,
                onAccept = { PeerService.answerApproval(true) },
                onDecline = { PeerService.answerApproval(false) },
            )
        }
    }
    if (!showingPairing && approval == null && interrupted) {
        AlertDialog(
            onDismissRequest = { PeerService.dismissInterrupted() },
            title = { Text("Transfer interrupted") },
            text = { Text("A file transfer was interrupted when the app left the foreground. The sender can resume it.") },
            confirmButton = { TextButton(onClick = { PeerService.dismissInterrupted() }) { Text("OK") } },
        )
    }

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

/** The accept/decline prompt for files pushed to this phone. */
@Composable
private fun PushApprovalDialog(request: PushApprovalRequest, onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Receive files?") },
        text = {
            Column {
                Text(request.peerName.ifEmpty { "A paired device" }, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text("${request.files} file${if (request.files == 1) "" else "s"} · ${request.total} bytes")
                if (request.names.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    request.names.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                    if (request.files > request.names.size) Text("…", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("Accept") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("Decline") } },
    )
}

/** The accept/decline prompt for an incoming Connect/Pair request, on any screen. */
@Composable
private fun IncomingPairDialog(
    request: IncomingRequest,
    onAccept: (Permissions) -> Unit,
    onDecline: () -> Unit,
) {
    val pairing = request.mode == PairingSessions.MODE_PAIR
    // Browse follows the request; push is off unless the person turns it on.
    var browse by remember(request.id) { mutableStateOf(request.requested.browse) }
    var push by remember(request.id) { mutableStateOf(false) }
    val anyPermission = request.requested.browse || request.requested.push
    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (pairing) "Pair request" else "Connect request") },
        text = {
            Column {
                Text(request.peerName.ifEmpty { "A device" }, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    Display.groupedHex(request.peerFp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                if (request.sas.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Both devices must show the same code:", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        request.sas.chunked(3).joinToString(" "),
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (anyPermission) {
                    Spacer(Modifier.height(12.dp))
                    Text("Allow this device to:", style = MaterialTheme.typography.bodyMedium)
                    PermissionToggle("Browse shared files", browse && request.requested.browse, request.requested.browse) { browse = it }
                    PermissionToggle("Send files to this device", push && request.requested.push, request.requested.push) { push = it }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAccept(Permissions(browse = browse, push = push)) }) { Text("Accept") }
        },
        dismissButton = { TextButton(onClick = onDecline) { Text("Decline") } },
    )
}

@Composable
private fun PermissionToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
