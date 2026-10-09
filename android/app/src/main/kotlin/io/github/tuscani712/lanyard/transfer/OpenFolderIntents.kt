package io.github.tuscani712.lanyard.transfer

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import io.github.tuscani712.lanyard.core.OpenFolder

/**
 * Builds and launches the "Open folder" `ACTION_VIEW` intent. The pure
 * [OpenFolder] helper picks the URI and MIME; this is the Android half.
 */
object OpenFolderIntents {
    fun intent(storedFolder: String?): Intent? {
        val target = OpenFolder.targetFor(storedFolder) ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(target.uri), target.mimeType)
            // A SAF tree is opened with the persisted read grant; NEW_TASK lets a
            // notification action (no task of its own) launch it.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Opens [storedFolder]. Returns false when there is no location to open or no
     * app can handle it, so the caller can show the "Saved to <path>" fallback
     * instead of a dead tap.
     */
    fun open(context: Context, storedFolder: String?): Boolean {
        val view = intent(storedFolder) ?: return false
        return try {
            context.startActivity(view)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: Exception) {
            // A stale grant or an unexposed file URI must not crash the app; the
            // folder is still on disk, so the caller tells the person where.
            false
        }
    }
}

/**
 * Handles the completion notification's "Open folder" action. It opens the
 * folder or, when nothing can, shows the same "Saved to <path>" message the
 * in-app button falls back to.
 */
class TransferOpenFolderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != ACTION_OPEN_FOLDER) return
        val folder = intent.getStringExtra(EXTRA_FOLDER)
        val label = intent.getStringExtra(EXTRA_LABEL)
        if (!OpenFolderIntents.open(context, folder)) {
            Toast.makeText(context, "Saved to ${label ?: "the download folder"}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val ACTION_OPEN_FOLDER = "io.github.tuscani712.lanyard.OPEN_FOLDER"
        const val EXTRA_FOLDER = "folder"
        const val EXTRA_LABEL = "label"
    }
}
