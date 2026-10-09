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
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import io.github.tuscani712.lanyard.core.DeviceAction
import io.github.tuscani712.lanyard.core.DeviceActionState
import io.github.tuscani712.lanyard.core.DeviceNames
import io.github.tuscani712.lanyard.core.DevicePage
import io.github.tuscani712.lanyard.core.PairedPeer
import io.github.tuscani712.lanyard.core.PeerDetailBody
import io.github.tuscani712.lanyard.core.Permission
import io.github.tuscani712.lanyard.core.UnpairPrompt
import io.github.tuscani712.lanyard.core.formatBytes
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
                Text(DeviceNames.display(detail.peer), style = MaterialTheme.typography.titleMedium)
                // The broadcast name stays visible as a secondary line whenever a
                // local alias is set, so the real device name is never hidden.
                if (detail.peer.alias.isNotBlank()) {
                    Text(
                        detail.peer.name.ifEmpty { "Unnamed device" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    shortFingerprint(detail.peer.fingerprint),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when (peerDetailBody(loading = detail.loading, openShare = detail.openShare != null)) {
            // Opening the page issues the first browse (listShares), which the
            // peer may answer with its browse prompt. Say so, rather than a bare
            // "Loading…", so a wait for a person on the other device is clear.
            PeerDetailBody.LOADING -> CenterMessage("Waiting for approval on ${DeviceNames.display(detail.peer)}…", spinner = true)
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

/**
 * The device page (F2), one scrolling column with sections in canonical order:
 * header, Send, Their shares, Permissions, Manage.
 */
@Composable
private fun DevicePage(detail: PeerDetail, vm: DevicesViewModel) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<ShareItem?>(null) }
    val downloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
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
    val sendFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            vm.sendFolder(detail.peer, uri)
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize().imePadding()) {
        item { DeviceHeader(detail) }
        detail.notice?.let { notice ->
            item { NoticeCard(notice, onDismiss = vm::dismissNotice) }
        }
        item {
            SectionTitle("Send")
            SendSection(detail, vm, onSendFolder = { sendFolderPicker.launch(null) })
        }
        item { SectionTitle("Their shares") }
        when {
            detail.error != null -> item { InlineMessage(detail.error) }
            detail.shares.isEmpty() -> item { InlineMessage("No shares yet. Share a file or folder from the explorer.") }
            else -> items(detail.shares, key = { it.id }) { share ->
                ShareRow(
                    share = share,
                    onOpen = { vm.openShare(share) },
                    onDownload = {
                        val folder = vm.validDownloadFolder()
                        if (folder != null) {
                            vm.downloadShare(detail.peer, share, folder)
                        } else {
                            pending = share
                            downloadPicker.launch(vm.rememberedTree())
                        }
                    },
                )
            }
        }
        item { PermissionsSection(detail, vm) }
        item { SectionTitle("Manage"); ManageSection(detail, vm) }
    }
}

/** The header: alias, broadcast name as a secondary line, status/reason, last
 * seen, address and short ID. */
@Composable
private fun DeviceHeader(detail: PeerDetail) {
    val header = DevicePage.header(
        online = detail.online,
        host = detail.peer.host,
        port = detail.peer.port,
        lastSeenMillis = detail.lastSeenMillis,
        fingerprint = detail.peer.fingerprint,
        offlineReason = detail.offlineReason,
    )
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(DeviceNames.display(detail.peer), style = MaterialTheme.typography.titleMedium)
            if (detail.peer.alias.isNotBlank()) {
                Text(
                    detail.peer.name.ifEmpty { "Unnamed device" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(header.status, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${header.lastSeen} · ${header.address} · ${header.shortId}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun InlineMessage(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
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

/** The Send section: send files, send folder, send text, browse their shares. */
@Composable
private fun SendSection(detail: PeerDetail, vm: DevicesViewModel, onSendFolder: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.sendFiles(detail.peer, uris)
    }
    val ensureNotifications = rememberNotificationPermission()
    var text by rememberSaveable { mutableStateOf("") }

    // Our outgoing actions are gated by what the peer allows us ("They allow me").
    val states = DevicePage.states(
        online = detail.online,
        canPush = detail.peer.allowPush,
        canBrowse = detail.peer.allowBrowse,
        preparingFiles = detail.preparing,
    ).associateBy { it.action }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        ActionButton(states[DeviceAction.SEND_FILES], label = DevicePage.label(DeviceAction.SEND_FILES)) {
            ensureNotifications()
            picker.launch(arrayOf("*/*"))
        }
        ActionButton(states[DeviceAction.SEND_FOLDER], label = DevicePage.label(DeviceAction.SEND_FOLDER)) { onSendFolder() }

        val textState = states[DeviceAction.SEND_TEXT]
        Text(DevicePage.label(DeviceAction.SEND_TEXT), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Send text…") },
                maxLines = 3,
                enabled = textState?.enabled == true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    vm.sendText(detail.peer, text)
                    text = ""
                },
                enabled = textState?.enabled == true && text.isNotBlank(),
            ) { Text("Send") }
        }
        textState?.takeIf { !it.enabled }?.let { ActionReason(it.reason) }

        ActionButton(states[DeviceAction.BROWSE_SHARES], label = DevicePage.label(DeviceAction.BROWSE_SHARES)) {
            val first = detail.shares.firstOrNull()
            if (first != null) vm.openShare(first) else vm.showNotice("This device is not sharing anything with you right now.")
        }
    }
}

/**
 * The Permissions section (F2/G1): the editable "They can" tri-state rows (the
 * same store as the Settings trust editor) and the read-only "They allow me".
 */
@Composable
private fun PermissionsSection(detail: PeerDetail, vm: DevicesViewModel) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("They can:", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            DevicePage.theyCan(detail.peer).forEach { row ->
                var expanded by remember(row.action) { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(row.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Box {
                        OutlinedButton(onClick = { expanded = true }) {
                            Text(DevicePage.permissionPhrase(row.value))
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            Permission.entries.forEach { value ->
                                DropdownMenuItem(
                                    text = { Text(DevicePage.permissionPhrase(value)) },
                                    onClick = {
                                        vm.setPermission(detail.peer, row.action, value)
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "They allow me: ${DevicePage.theyAllowText(detail.peer)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The Manage section: rename and unpair, plus their dialogs. */
@Composable
private fun ManageSection(detail: PeerDetail, vm: DevicesViewModel) {
    var prompt by remember { mutableStateOf(UnpairPrompt()) }
    var renaming by remember { mutableStateOf(false) }

    // Back closes the unpair confirm first (the dialog also consumes Back), and
    // otherwise returns to the Devices list rather than exiting the app.
    BackHandler(enabled = !prompt.isConfirming && !renaming) { vm.closePeer() }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        ActionButton(null, DevicePage.label(DeviceAction.RENAME)) { renaming = true }
        ActionButton(null, DevicePage.label(DeviceAction.UNPAIR)) { prompt = prompt.request(detail.peer) }
    }

    if (renaming) {
        RenameDialog(
            peer = detail.peer,
            onDismiss = { renaming = false },
            onRename = { alias ->
                vm.rename(detail.peer, alias)
                renaming = false
            },
        )
    }

    if (prompt.isConfirming) {
        ConfirmDialog(
            title = "Unpair ${DeviceNames.display(detail.peer)}?",
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

/** One canonical action control, disabled with its reason when not available. */
@Composable
private fun ActionButton(
    state: DeviceActionState?,
    label: String,
    onClick: () -> Unit,
) {
    val enabled = state?.enabled ?: true
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) { Text(label) }
    if (!enabled) ActionReason(state?.reason)
}

@Composable
private fun ActionReason(reason: String?) {
    if (reason.isNullOrBlank()) return
    Spacer(Modifier.height(2.dp))
    Text(
        reason,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Rename a device locally: the alias is never sent and never overwrites the
 * broadcast name. Clearing it reverts to the broadcast name. */
@Composable
private fun RenameDialog(peer: PairedPeer, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var alias by remember { mutableStateOf(peer.alias) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename device") },
        text = {
            Column {
                Text(
                    "This name is only on this phone. It is never sent to the device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    placeholder = { Text(peer.name.ifEmpty { "Device name" }) },
                    singleLine = true,
                    label = { Text("Local name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (alias.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Broadcast name: ${peer.name.ifEmpty { "Unnamed device" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { Button(onClick = { onRename(alias) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
private fun ShareRow(share: ShareItem, onOpen: () -> Unit, onDownload: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f).clickable { onOpen() }) {
            Text(share.label.ifEmpty { share.name.ifEmpty { "Share" } }, style = MaterialTheme.typography.titleSmall)
            Text(share.lifetime, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onDownload) { Text("Download") }
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
                        if (entry.isDir) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(entry.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    if (!entry.isDir) {
                        Text(formatBytes(entry.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
