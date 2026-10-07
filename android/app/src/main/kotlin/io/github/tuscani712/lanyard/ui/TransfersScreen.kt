package io.github.tuscani712.lanyard.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tuscani712.lanyard.SettingsHolder
import io.github.tuscani712.lanyard.core.SpeedUnit
import io.github.tuscani712.lanyard.core.formatSpeed
import io.github.tuscani712.lanyard.transfer.TransferManager
import io.github.tuscani712.lanyard.transfer.TransferRecord
import io.github.tuscani712.lanyard.transfer.TransferState

@Composable
fun TransfersScreen(padding: PaddingValues) {
    val records by TransferManager.state.collectAsStateWithLifecycle()
    val settings by SettingsHolder.settings.collectAsStateWithLifecycle()

    if (records.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("No transfers.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
        items(records, key = { it.id }) { record ->
            TransferRow(
                record,
                settings.speedUnit,
                onCancel = { TransferManager.cancel(record.id) },
                onDismiss = { TransferManager.dismiss(record.id) },
            )
        }
    }
}

@Composable
private fun TransferRow(record: TransferRecord, speedUnit: SpeedUnit, onCancel: () -> Unit, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (record.direction == "send") Icons.Filled.Upload else Icons.Filled.Download,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(record.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        record.peerName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stateLabel(record),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (record.total > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (record.done.toFloat() / record.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                val speed = if (record.state == TransferState.Running && record.speed > 0) {
                    " · " + formatSpeed(record.speed, speedUnit)
                } else {
                    ""
                }
                Text(
                    "${humanSize(record.done)} / ${humanSize(record.total)}$speed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            record.message?.takeIf { record.state != TransferState.Running }?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            if (record.state == TransferState.Running || record.state == TransferState.Queued) {
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
                }
            }
        }
    }
}

private fun stateLabel(record: TransferRecord): String = when (record.state) {
    TransferState.Queued -> "Waiting"
    TransferState.Running -> if (record.direction == "send") "Sending" else "Receiving"
    TransferState.Done -> "Done"
    TransferState.Failed -> "Failed"
    TransferState.Cancelled -> "Cancelled"
}

private fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return "%.1f %s".format(value, units[unit])
}
