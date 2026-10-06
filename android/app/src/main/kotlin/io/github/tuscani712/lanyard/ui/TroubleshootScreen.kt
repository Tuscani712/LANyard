package io.github.tuscani712.lanyard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tuscani712.lanyard.DevicesViewModel
import io.github.tuscani712.lanyard.core.CheckResult
import io.github.tuscani712.lanyard.core.CheckStatus
import io.github.tuscani712.lanyard.core.DiagTarget
import io.github.tuscani712.lanyard.core.Diagnostics
import io.github.tuscani712.lanyard.net.AndroidDiagEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val OkGreen = Color(0xFF3FB950)
private val WarnAmber = Color(0xFFD29922)
private val FailRed = Color(0xFFF85149)

@Composable
fun TroubleshootScreen(padding: PaddingValues, vm: DevicesViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    var selectedFingerprint by rememberSaveable { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<CheckResult>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) { vm.refreshPaired(); onDispose { } }

    val selected = state.paired.firstOrNull { it.peer.fingerprint == selectedFingerprint }?.peer
        ?: state.paired.firstOrNull()?.peer

    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to settings")
            }
            Text("Troubleshoot", style = MaterialTheme.typography.titleMedium)
        }

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Device to test", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            if (state.paired.isEmpty()) {
                Text(
                    "No paired devices to test.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.paired.forEach { paired ->
                    FilterChip(
                        selected = paired.peer.fingerprint == selected?.fingerprint,
                        onClick = { selectedFingerprint = paired.peer.fingerprint },
                        label = { Text(paired.peer.name.ifEmpty { "Unnamed device" }) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val target = selected?.let { DiagTarget(it.name, it.host, it.port, it.fingerprint) }
                    running = true
                    results = emptyList()
                    scope.launch {
                        results = withContext(Dispatchers.IO) {
                            Diagnostics.run(AndroidDiagEnv(context, target))
                        }
                        running = false
                    }
                },
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (running) "Running…" else "Run checks") }

            if (results.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                results.forEach { CheckRow(it) }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        copyToClipboard(context, "LANyard diagnostics", Diagnostics.copyReport(results))
                    }) { Text("Copy report") }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(result: CheckResult) {
    val (icon, tint) = statusVisual(result.status)
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Icon(icon, contentDescription = result.status.name, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(result.title, style = MaterialTheme.typography.titleSmall)
            Text(
                result.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (result.fix.isNotEmpty()) {
                Text(result.fix, style = MaterialTheme.typography.bodySmall, color = tint)
            }
        }
    }
}

private fun statusVisual(status: CheckStatus): Pair<ImageVector, Color> = when (status) {
    CheckStatus.Ok -> Icons.Filled.CheckCircle to OkGreen
    CheckStatus.Warning -> Icons.Filled.Warning to WarnAmber
    CheckStatus.Failed -> Icons.Filled.Cancel to FailRed
    CheckStatus.Skipped -> Icons.Filled.Info to Color(0xFF8B949E)
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    manager.setPrimaryClip(ClipData.newPlainText(label, value))
}
