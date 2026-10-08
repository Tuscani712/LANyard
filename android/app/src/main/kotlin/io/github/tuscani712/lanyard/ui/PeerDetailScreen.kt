package io.github.tuscani712.lanyard.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tuscani712.lanyard.DevicesViewModel
import io.github.tuscani712.lanyard.PeerDetail
import io.github.tuscani712.lanyard.ShareItem
import io.github.tuscani712.lanyard.TreeItem
import io.github.tuscani712.lanyard.core.PeerDetailBody
import io.github.tuscani712.lanyard.core.UnpairPrompt
import io.github.tuscani712.lanyard.core.peerDetailBody

@Composable
fun PeerDetailScreen(padding: PaddingValues, vm: DevicesViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val detail = state.detail ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.closePeer() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to devices")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(detail.peer.name.ifEmpty { "Unnamed device" }, style = MaterialTheme.typography.titleMedium)
                Text(
                    shortFingerprint(detail.peer.fingerprint),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when (peerDetailBody(loading = detail.loading, openShare = detail.openShare != null)) {
            PeerDetailBody.LOADING -> CenterMessage("Loading…", spinner = true)
            // The error/offline case is deliberately a normal peer page, not a
            // bare message: Unpair and its confirmation (and Back-to-list) must
            // stay reachable when a peer cannot be reached.
            PeerDetailBody.PEER_PAGE -> DevicePage(detail, vm)
            PeerDetailBody.SHARE_TREE -> {
                // Back inside a share walks up the folder hierarchy, then to the
                // share list, then closes the detail; it never exits the app.
                BackHandler { vm.detailBack() }
                TreeView(
                    share = detail.openShare!!,
                    path = detail.treePath,
                    items = detail.tree,
                    loading = detail.treeLoading,
                    error = detail.error,
                    onBack = vm::backToShares,
                    onUp = vm::treeUp,
                    onOpenPath = vm::openPath,
                )
            }
        }
    }
}

@Composable
private fun DevicePage(detail: PeerDetail, vm: DevicesViewModel) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<ShareItem?>(null) }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val share = pending
        pending = null
        if (uri != null && share != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            vm.rememberTree(uri)
            vm.downloadShare(detail.peer, share, uri)
        }
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        PeerActions(detail, vm)
        detail.notice?.let { NoticeCard(it, onDismiss = vm::dismissNotice) }
        Box(modifier = Modifier.weight(1f)) {
            if (detail.loading) {
                CenterMessage("Loading…", spinner = true)
            } else if (detail.error != null) {
                CenterMessage(detail.error)
            } else {
                SharesList(
                    shares = detail.shares,
                    onOpen = vm::openShare,
                    onDownload = { share ->
                        val folder = vm.validDownloadFolder()
                        if (folder != null) {
                            vm.downloadShare(detail.peer, share, folder)
                        } else {
                            pending = share
                            treePicker.launch(vm.rememberedTree())
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun rememberNotificationPermission(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun PeerActions(detail: PeerDetail, vm: DevicesViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.sendFiles(detail.peer, uris)
    }
    val ensureNotifications = rememberNotificationPermission()
    var text by rememberSaveable { mutableStateOf("") }
    var prompt by remember { mutableStateOf(UnpairPrompt()) }

    // Back closes the unpair confirm first (the dialog also consumes Back), and
    // otherwise returns to the Devices list rather than exiting the app.
    BackHandler(enabled = !prompt.isConfirming) { vm.closePeer() }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Button(
            onClick = {
                ensureNotifications()
                picker.launch(arrayOf("*/*"))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Send files")
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Send text…") },
                maxLines = 3,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    vm.sendText(detail.peer, text)
                    text = ""
                },
                enabled = text.isNotBlank(),
            ) { Text("Send") }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { prompt = prompt.request(detail.peer) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Unpair this device")
        }
    }

    if (prompt.isConfirming) {
        ConfirmDialog(
            title = "Unpair ${detail.peer.name.ifEmpty { "this device" }}?",
            message = "This removes the pairing on both devices. Any transfer to it in progress is cancelled.",
            confirmLabel = "Unpair",
            onConfirm = {
                prompt.confirmed()?.let(vm::unpair)
                prompt = prompt.confirm()
            },
            onDismiss = { prompt = prompt.cancel() },
        )
    }
}

@Composable
private fun NoticeCard(text: String, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun SharesList(shares: List<ShareItem>, onOpen: (ShareItem) -> Unit, onDownload: (ShareItem) -> Unit) {
    if (shares.isEmpty()) {
        CenterMessage("No shares yet. Share a file or folder from the explorer.")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(shares, key = { it.id }) { share ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f).clickable { onOpen(share) }) {
                    Text(share.label.ifEmpty { share.name.ifEmpty { "Share" } }, style = MaterialTheme.typography.titleSmall)
                    Text(share.lifetime, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { onDownload(share) }) { Text("Download") }
            }
        }
    }
}

@Composable
private fun TreeView(
    share: ShareItem,
    path: String,
    items: List<TreeItem>,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onUp: () -> Unit,
    onOpenPath: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to shares")
            }
            Text(share.label.ifEmpty { share.name.ifEmpty { "Share" } }, style = MaterialTheme.typography.titleSmall)
            if (path.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text("/ $path", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (loading) {
            CenterMessage("Loading…", spinner = true)
            return@Column
        }
        if (error != null) {
            CenterMessage(error)
            return@Column
        }
        if (items.isEmpty()) {
            CenterMessage("This folder is empty.")
            return@Column
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (path.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onUp() }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Up")
                    }
                }
            }
            items(items, key = { it.path }) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (entry.isDir) Modifier.clickable { onOpenPath(entry.path) } else Modifier,
                        )
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (entry.isDir) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(entry.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    if (!entry.isDir) {
                        Text(humanSize(entry.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterMessage(text: String, spinner: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (spinner) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            Spacer(Modifier.height(12.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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
