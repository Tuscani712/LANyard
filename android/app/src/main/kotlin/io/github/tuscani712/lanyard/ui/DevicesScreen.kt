package io.github.tuscani712.lanyard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tuscani712.lanyard.DevicesViewModel
import io.github.tuscani712.lanyard.PairedStatus
import io.github.tuscani712.lanyard.PairingStatus
import io.github.tuscani712.lanyard.net.NearbyDevice

private val OnlineGreen = Color(0xFF3FB950)

fun shortFingerprint(fp: String): String =
    if (fp.length >= 16) fp.take(16).chunked(4).joinToString(" ") else fp

@Composable
fun DevicesScreen(padding: PaddingValues, vm: DevicesViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var explain by remember { mutableStateOf<NearbyDevice?>(null) }

    if (state.detail != null) {
        PeerDetailScreen(padding, vm)
        return
    }

    DisposableEffect(Unit) {
        vm.refreshPaired()
        vm.startNearby()
        onDispose { vm.stopNearby() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        state.pairing?.let { PairingCard(it, onDismiss = vm::dismissPairing) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader("Paired")
            Button(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add device")
            }
        }

        Spacer(Modifier.height(8.dp))
        if (state.paired.isEmpty()) {
            Text(
                "No connected devices",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.paired.forEach { PairedRow(it, onClick = { vm.openPeer(it.peer) }) }
        }

        Spacer(Modifier.height(24.dp))
        SectionHeader("Nearby")
        Spacer(Modifier.height(8.dp))
        if (state.nearby.isEmpty()) {
            Text(
                "No devices nearby",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.nearby.forEach { device ->
                val paired = state.paired.any { it.peer.host == device.host && it.peer.port == device.port }
                NearbyRow(device, paired) { explain = device }
            }
        }
    }

    if (showAdd) {
        AddDeviceDialog(
            onDismiss = { showAdd = false },
            onPair = { link ->
                showAdd = false
                vm.pair(link)
            },
        )
    }

    explain?.let { device ->
        AlertDialog(
            onDismissRequest = { explain = null },
            title = { Text("Pair with ${device.name}") },
            text = {
                Text(
                    "Pairing needs a link from the other device (or its QR code). " +
                        "Open LANyard on ${device.name}, choose \"Pair this device\", and " +
                        "either scan its code or paste its link here.",
                )
            },
            confirmButton = { TextButton(onClick = { explain = null }) { Text("Got it") } },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun PairingCard(status: PairingStatus, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (status is PairingStatus.Running) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Pairing…")
            } else if (status is PairingStatus.Done) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (status.ok) "Paired" else "Could not pair", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(2.dp))
                    Text(status.message, style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun PairedRow(status: PairedStatus, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(status.online)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(status.peer.name.ifEmpty { "Unnamed device" }, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    shortFingerprint(status.peer.fingerprint),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (status.online) "Online" else "Offline",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NearbyRow(device: NearbyDevice, paired: Boolean, onPair: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Devices, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name.ifEmpty { "Unknown device" }, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${device.host}:${device.port}" + if (device.os.isNotEmpty()) " · ${device.os}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (paired) {
                Text("Paired", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                OutlinedButton(onClick = onPair) { Text("Pair") }
            }
        }
    }
}

@Composable
private fun StatusDot(online: Boolean) {
    Spacer(
        modifier = Modifier
            .size(10.dp)
            .background(if (online) OnlineGreen else MaterialTheme.colorScheme.outline, CircleShape),
    )
}

@Composable
private fun AddDeviceDialog(onDismiss: () -> Unit, onPair: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var link by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add device") },
        text = {
            Column {
                Text(
                    "Paste the pairing link from the other device.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    placeholder = { Text("lanyard://pair?…") },
                    singleLine = false,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { clipboard.getText()?.text?.let { link = it } }) {
                    Text("Paste from clipboard")
                }
            }
        },
        confirmButton = {
            Button(onClick = { onPair(link) }, enabled = link.isNotBlank()) { Text("Pair") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
