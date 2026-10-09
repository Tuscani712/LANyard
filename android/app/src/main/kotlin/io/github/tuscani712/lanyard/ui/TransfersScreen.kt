package io.github.tuscani712.lanyard.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tuscani712.lanyard.PeerService
import io.github.tuscani712.lanyard.SettingsHolder
import io.github.tuscani712.lanyard.core.Display
import io.github.tuscani712.lanyard.core.OpenFolder
import io.github.tuscani712.lanyard.core.ReceivedSnippet
import io.github.tuscani712.lanyard.core.SpeedUnit
import io.github.tuscani712.lanyard.core.TransferRecord
import io.github.tuscani712.lanyard.core.TransferState
import io.github.tuscani712.lanyard.core.formatBytes
import io.github.tuscani712.lanyard.core.formatRateAndEta
import io.github.tuscani712.lanyard.core.formatSpeed
import io.github.tuscani712.lanyard.core.isLive
import io.github.tuscani712.lanyard.transfer.OpenFolderIntents
import io.github.tuscani712.lanyard.transfer.TransferManager

@Composable
fun TransfersScreen(padding: PaddingValues) {
    val records by TransferManager.state.collectAsStateWithLifecycle()
    val settings by SettingsHolder.settings.collectAsStateWithLifecycle()
    val receivedText by PeerService.receivedText.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    // Back never cancels a transfer: while anything is running, Back leaves the
    // transfers alone (the foreground service keeps them going). Nothing here
    // calls TransferManager.cancel on a navigation event.
    val active = records.any { it.state.isLive }
    BackHandler(enabled = active) { /* keep transfers running; do not cancel */ }

    if (records.isEmpty() && receivedText.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("No transfers.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        return
    }

    val finished = records.count { !it.state.isLive }

    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        if (finished > 0) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(onClick = { TransferManager.clearFinished() }) {
                    Text("Clear finished")
                }
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize().weight(1f)) {
            if (receivedText.isNotEmpty()) {
                item(key = "received-text-header") {
                    Text(
                        "Received text",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(receivedText, key = { "text_${it.id}" }) { snippet ->
                    ReceivedTextRow(
                        snippet,
                        onCopy = { text -> clipboard.setText(AnnotatedString(text)) },
                        onDismiss = { PeerService.dismissReceivedText(snippet.id) },
                    )
                }
            }
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
}

@Composable
private fun ReceivedTextRow(snippet: ReceivedSnippet, onCopy: (String) -> Unit, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Text", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "from ${Display.shortFp(snippet.peerFingerprint)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                snippet.text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = { onCopy(snippet.text) }) { Text("Copy") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun TransferRow(record: TransferRecord, speedUnit: SpeedUnit, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // "Open folder" is only for a finished receive that knows where it landed.
    val openable = record.state == TransferState.Done &&
        record.direction == "receive" &&
        OpenFolder.targetFor(record.destinationUri) != null
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
            if (record.state == TransferState.Preparing) {
                // Spooling picked files: no byte counts yet, just a spinner so the
                // window between the picker and the transfer row is never blank.
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Preparing…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (record.total > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (record.done.toFloat() / record.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                // Live speed + ETA while running (blank when the meter has no
                // honest rate, e.g. just after a resume); the whole-transfer
                // average once finished, so a receive row shows its speed too.
                // In the finishing window the size is called out first; the live
                // rate/ETA is still appended when the meter has one, so neither
                // the size nor the speed display is lost.
                val rate = when {
                    record.state == TransferState.Running && record.finishing -> {
                        val live = formatRateAndEta(record.speed, record.etaSeconds, speedUnit)
                        "Finishing… · " + formatBytes(record.finishingBytes ?: record.total) +
                            if (live.isNotEmpty()) " · $live" else ""
                    }
                    record.state == TransferState.Running ->
                        formatRateAndEta(record.speed, record.etaSeconds, speedUnit)
                    record.state == TransferState.Done && record.averageSpeed > 0 ->
                        "avg " + formatSpeed(record.averageSpeed, speedUnit)
                    else -> ""
                }
                Text(
                    "${formatBytes(record.done)} / ${formatBytes(record.total)}" +
                        if (rate.isNotEmpty()) " · $rate" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            record.message?.takeIf { record.state != TransferState.Running }?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            // A finished receive names the folder it landed in, so the files are
            // findable after the transfer has left the notification.
            record.destinationFolder?.takeIf {
                record.state == TransferState.Done && record.direction == "receive" && it.isNotBlank()
            }?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    "Saved to $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (record.state.isLive) {
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (openable) {
                        OutlinedButton(
                            onClick = {
                                if (!OpenFolderIntents.open(context, record.destinationUri)) {
                                    Toast.makeText(
                                        context,
                                        "Saved to ${record.destinationFolder ?: "the download folder"}",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            },
                        ) { Text("Open folder") }
                        Spacer(Modifier.width(8.dp))
                    }
                    OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
                }
            }
        }
    }
}

private fun stateLabel(record: TransferRecord): String = when (record.state) {
    TransferState.Preparing -> "Preparing…"
    TransferState.Queued -> "Waiting"
    TransferState.Running -> when {
        record.finishing -> "Finishing…"
        record.direction == "send" -> "Sending"
        else -> "Receiving"
    }
    TransferState.Done -> "Done"
    TransferState.Failed -> "Failed"
    TransferState.Cancelled -> "Cancelled"
}
