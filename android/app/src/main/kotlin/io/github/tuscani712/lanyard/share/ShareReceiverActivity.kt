package io.github.tuscani712.lanyard.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tuscani712.lanyard.IdentityHolder
import io.github.tuscani712.lanyard.MainActivity
import io.github.tuscani712.lanyard.SettingsHolder
import io.github.tuscani712.lanyard.core.JsonFileTrustStore
import io.github.tuscani712.lanyard.core.Identity
import io.github.tuscani712.lanyard.core.PairedPeer
import io.github.tuscani712.lanyard.core.ProbeClient
import io.github.tuscani712.lanyard.core.PushSource
import io.github.tuscani712.lanyard.core.ShareTarget
import io.github.tuscani712.lanyard.core.ShareValidation
import io.github.tuscani712.lanyard.core.ThemeMode
import io.github.tuscani712.lanyard.transfer.TransferManager
import io.github.tuscani712.lanyard.ui.theme.LanyardTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Receives `ACTION_SEND`/`ACTION_SEND_MULTIPLE` from other apps, shows a small
 * picker of paired devices, and hands the contents to [TransferManager].
 *
 * All provider I/O (opening a URI, spooling it) runs on [Dispatchers.IO]. Files
 * are copied into `cacheDir/share/` before this activity finishes, because a
 * content-URI read grant is revoked when the activity is destroyed while the
 * push runs later in the service. It never requests a storage permission and
 * never logs the shared URIs, names or text.
 */
class ShareReceiverActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var targets by mutableStateOf<List<ShareTarget>>(emptyList())
    private var preparing by mutableStateOf(true)
    private var loading by mutableStateOf(false)
    private var sending by mutableStateOf(false)
    private var summary by mutableStateOf("")
    private var warning by mutableStateOf<String?>(null)
    private var fatal by mutableStateOf<String?>(null)
    private var share: Resolved? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        IdentityHolder.init(applicationContext)
        SettingsHolder.init(applicationContext)
        TransferManager.init(application)

        val received = intent
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { classify(received) }
            share = outcome.resolved
            summary = outcome.summary
            warning = outcome.warning
            fatal = outcome.fatal
            preparing = false
            if (outcome.resolved != null) {
                loading = true
                targets = withContext(Dispatchers.IO) { loadTargets() }
                loading = false
            }
        }

        setContent {
            val settings by SettingsHolder.settings.collectAsStateWithLifecycle()
            val dark = when (settings.theme) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            LanyardTheme(darkTheme = dark) {
                SharePickerScreen(
                    summary = summary,
                    warning = warning,
                    fatal = fatal,
                    targets = targets,
                    preparing = preparing || sending,
                    loading = loading,
                    onSend = ::send,
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    },
                    onClose = { finish() },
                )
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun send(target: ShareTarget) {
        val resolved = share
        if (resolved == null || sending) return
        sending = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { enqueue(resolved, target.peer) }
            if (!ok) {
                sending = false
                warning = "The shared items could not be read."
                return@launch
            }
            Toast.makeText(this@ShareReceiverActivity, "Sending…", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /** Spools and queues the share. Runs on IO; returns false if nothing could be read. */
    private fun enqueue(resolved: Resolved, peer: PairedPeer): Boolean = when (resolved) {
        is Resolved.Text -> {
            if (ShareValidation.textExceedsSnippet(resolved.text)) {
                TransferManager.enqueuePush(peer, listOf(textSource(resolved.text)), "Text.txt")
            } else {
                TransferManager.enqueueSnippet(peer, resolved.text)
            }
            true
        }
        is Resolved.Files -> {
            val used = HashSet<String>()
            val oks = resolved.uris.map { spoolShare(this, it, used) }.filterIsInstance<SourceResult.Ok>()
            if (oks.isEmpty()) {
                false
            } else {
                val sources = oks.map { it.source }
                val spools = oks.map { it.spool }
                val label = sources.first().relPath + if (sources.size > 1) " +${sources.size - 1}" else ""
                TransferManager.enqueuePush(peer, sources, label) { spools.forEach { it.delete() } }
                true
            }
        }
    }

    /** Parses and validates the intent off the main thread. */
    private fun classify(intent: Intent?): Outcome {
        val action = intent?.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            return Outcome(fatal = "This app was opened without anything to share.")
        }

        val rawUris = when (action) {
            Intent.ACTION_SEND -> listOfNotNull(parcelableUri(intent, Intent.EXTRA_STREAM) ?: intent.data)
            else -> parcelableUris(intent)
        }

        // Cap before validating: a hostile intent with thousands of URIs must not
        // make us open each one.
        val batch = rawUris.take(ShareValidation.MAX_ITEMS)
        var rejected = 0
        val accepted = ArrayList<Uri>(batch.size)
        for (uri in batch) {
            val readable = runCatching {
                contentResolver.openInputStream(uri)?.use { } != null
            }.getOrDefault(false)
            if (ShareValidation.validateUri(uri.scheme, uri.authority, readable).accepted) accepted.add(uri) else rejected++
        }

        if (accepted.isNotEmpty()) {
            val dropped = rejected + (rawUris.size - batch.size)
            return Outcome(
                resolved = Resolved.Files(accepted),
                summary = if (accepted.size == 1) "1 file" else "${accepted.size} files",
                warning = if (dropped > 0) "$dropped item(s) were skipped." else null,
            )
        }
        if (rawUris.isNotEmpty()) return Outcome(fatal = "The shared item(s) could not be sent.")

        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (action == Intent.ACTION_SEND && !text.isNullOrBlank()) {
            return Outcome(resolved = Resolved.Text(text), summary = "Text")
        }
        return Outcome(fatal = "Nothing to share.")
    }

    private fun loadTargets(): List<ShareTarget> {
        val identity = IdentityHolder.identity
        val peers = JsonFileTrustStore(File(filesDir, "trust/peers.json")).list()
        val online = peers.filter { peer -> identity != null && isOnline(peer, identity) }
            .mapTo(HashSet()) { it.fingerprint.lowercase() }
        return ShareValidation.shareTargets(peers, online)
    }

    private fun isOnline(peer: PairedPeer, identity: Identity): Boolean = try {
        val probe = ProbeClient(peer.host, peer.port, identity)
        probe.hello()
        probe.observedFingerprint().equals(peer.fingerprint, ignoreCase = true)
    } catch (_: Exception) {
        false
    }

    private fun textSource(text: String): PushSource {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return PushSource("Text.txt", bytes.size.toLong(), System.currentTimeMillis()) { ByteArrayInputStream(bytes) }
    }

    @Suppress("DEPRECATION")
    private fun parcelableUri(intent: Intent, key: String): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(key, Uri::class.java)
        else intent.getParcelableExtra(key)

    @Suppress("DEPRECATION")
    private fun parcelableUris(intent: Intent): List<Uri> {
        val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        }
        return list?.filterNotNull() ?: emptyList()
    }

    private data class Outcome(
        val resolved: Resolved? = null,
        val summary: String = "",
        val warning: String? = null,
        val fatal: String? = null,
    )

    private sealed interface Resolved {
        data class Text(val text: String) : Resolved
        data class Files(val uris: List<Uri>) : Resolved
    }
}
