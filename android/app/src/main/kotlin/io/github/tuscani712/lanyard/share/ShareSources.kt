package io.github.tuscani712.lanyard.share

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import io.github.tuscani712.lanyard.core.PushSource
import io.github.tuscani712.lanyard.core.ShareValidation
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.SecureRandom

/** The outcome of preparing one shared URI: a spooled source, or why it failed. */
sealed interface SourceResult {
    data class Ok(val source: PushSource, val spool: File) : SourceResult
    data class Failed(val reason: String) : SourceResult
}

/** Keep this much cache space free beyond the item's own size. */
private const val SPOOL_HEADROOM_BYTES = 1L * 1024 * 1024

/**
 * Copies one shared item into `cacheDir/share/` and returns a [PushSource] that
 * reads from the copy. Spooling before the activity finishes is required: a
 * content-URI read grant is revoked when the activity is destroyed, but the push
 * runs later in the foreground service (proven: the delayed push failed with a
 * SecurityException without this). The copy is deleted by the caller when the
 * transfer ends, and any leftovers are swept at app start.
 *
 * [usedNames] keeps the offered names unique within one share.
 */
fun spoolShare(context: Context, uri: Uri, usedNames: MutableSet<String>): SourceResult {
    val resolver = context.contentResolver
    var rawName: String? = null
    var size = -1L
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) rawName = c.getString(nameIndex)
                val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !c.isNull(sizeIndex)) size = c.getLong(sizeIndex)
            }
        }
    }
    val name = uniqueName(ShareValidation.safeShareName(rawName), usedNames)

    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    if (size >= 0) {
        val free = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (free < size + SPOOL_HEADROOM_BYTES) return SourceResult.Failed("Not enough space to prepare the item.")
    }

    val nonce = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    val spool = File(dir, "$nonce.tmp")
    return try {
        val copied = resolver.openInputStream(uri)?.use { input ->
            spool.outputStream().use { out -> input.copyTo(out) }
        } ?: return SourceResult.Failed("The item could not be opened.")
        SourceResult.Ok(PushSource(name, copied, System.currentTimeMillis()) { FileInputStream(spool) }, spool)
    } catch (_: Exception) {
        spool.delete()
        SourceResult.Failed("The item could not be read.")
    }
}

private fun uniqueName(name: String, used: MutableSet<String>): String {
    if (used.add(name.lowercase())) return name
    val base = name.substringBeforeLast('.', name)
    val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
    var n = 1
    while (!used.add("$base ($n)$ext".lowercase())) n++
    return "$base ($n)$ext"
}
