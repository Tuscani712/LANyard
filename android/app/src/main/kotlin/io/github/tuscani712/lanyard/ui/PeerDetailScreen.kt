package io.github.tuscani712.lanyard.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tuscani712.lanyard.DevicesViewModel
import io.github.tuscani712.lanyard.ShareItem
import io.github.tuscani712.lanyard.TreeItem

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

        when {
            detail.loading -> CenterMessage("Loading…", spinner = true)
            detail.error != null && detail.openShare == null -> CenterMessage(detail.error!!)
            detail.openShare == null -> SharesList(detail.shares, onOpen = vm::openShare)
            else -> TreeView(
                share = detail.openShare!!,
                path = detail.treePath,
                items = detail.tree,
                loading = detail.treeLoading,
                error = detail.error,
                onBack = vm::backToShares,
                onOpenPath = vm::openPath,
            )
        }
    }
}

@Composable
private fun SharesList(shares: List<ShareItem>, onOpen: (ShareItem) -> Unit) {
    if (shares.isEmpty()) {
        CenterMessage("No shares yet. Share a file or folder from the explorer.")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(shares, key = { it.id }) { share ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(share) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(share.label.ifEmpty { share.name.ifEmpty { "Share" } }, style = MaterialTheme.typography.titleSmall)
                    Text(share.lifetime, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
                            .clickable { onOpenPath(parentOf(path)) }
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

private fun parentOf(path: String): String = path.substringBeforeLast('/', "")

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
