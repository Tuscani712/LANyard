package io.github.tuscani712.lanyard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** Copies [value] to the clipboard under [label]; a no-op when unavailable. */
internal fun copyToClipboard(context: Context, label: String, value: String) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    manager.setPrimaryClip(ClipData.newPlainText(label, value))
}
