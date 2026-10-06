package io.github.tuscani712.lanyard.share

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.tuscani712.lanyard.core.ShareTarget
import io.github.tuscani712.lanyard.ui.shortFingerprint

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharePickerScreen(
    summary: String,
    warning: String?,
    fatal: String?,
    targets: List<ShareTarget>,
    preparing: Boolean,
    loading: Boolean,
    onSend: (ShareTarget) -> Unit,
    onOpenApp: () -> Unit,
    onClose: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Send to…") }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(summary, style = MaterialTheme.typography.bodyLarge)
            warning?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))

            when {
                fatal != null -> {
                    Text(fatal, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onClose) { Text("Close") }
                }
                preparing -> BusyRow("Preparing…")
                loading -> BusyRow("Looking for devices…")
                targets.isEmpty() -> {
                    Text("No paired devices.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Pair a device first, then share again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onOpenApp) { Text("Open LANyard") }
                }
                else -> LazyColumn(modifier = Modifier.weight(1f)) {
                    items(targets, key = { it.peer.fingerprint }) { target ->
                        TargetRow(target, onSend)
                    }
                }
            }

            if (fatal == null) {
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun BusyRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text)
    }
}

@Composable
private fun TargetRow(target: ShareTarget, onSend: (ShareTarget) -> Unit) {
    val peer = target.peer
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .then(if (target.enabled) Modifier.clickable { onSend(target) } else Modifier),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Devices,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (target.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    peer.name.ifEmpty { "Unnamed device" },
                    style = MaterialTheme.typography.titleSmall,
                    color = if (target.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    shortFingerprint(peer.fingerprint),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                target.reason?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (target.enabled) {
                Text("Send", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
