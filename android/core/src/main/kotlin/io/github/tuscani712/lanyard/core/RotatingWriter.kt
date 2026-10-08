package io.github.tuscani712.lanyard.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Appends short diagnostic lines to a file, rotating once the file would grow
 * past [maxBytes] and keeping at most [maxRotations] previous files named
 * `<file>.1`, `<file>.2`, ... (oldest dropped first). This is the phone-side
 * take on the desktop's single renamable log file, with a bounded history so
 * private storage never grows without limit.
 *
 * Write-only and thread-safe; a failed write is swallowed so logging can never
 * take the app down. Redaction happens before [append] is ever called.
 */
class RotatingWriter(
    private val file: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxRotations: Int = DEFAULT_MAX_ROTATIONS,
) {
    private val lock = Any()

    /** Appends one line (a newline is added). Creates parent directories. */
    fun append(line: String) {
        synchronized(lock) {
            try {
                file.parentFile?.mkdirs()
                if (file.exists() && file.length() + line.length + 1 > maxBytes) rotate()
                FileOutputStream(file, true).use { out ->
                    out.write((line + "\n").toByteArray(Charsets.UTF_8))
                }
            } catch (_: IOException) {
                // Diagnostics must never crash the app.
            } catch (_: SecurityException) {
                // Same: e.g. storage not writable during a shutdown.
            }
        }
    }

    /** Shifts `file.(n)` to `file.(n+1)`, dropping the oldest, then starts fresh. */
    private fun rotate() {
        File(rotatedPath(maxRotations)).delete()
        for (i in maxRotations - 1 downTo 1) {
            val from = File(rotatedPath(i))
            if (from.exists()) from.renameTo(File(rotatedPath(i + 1)))
        }
        file.renameTo(File(rotatedPath(1)))
    }

    private fun rotatedPath(n: Int): String = file.path + "." + n

    /**
     * The whole history, oldest first: the oldest rotation, then newer ones,
     * then the live file. Missing files are skipped. Empty when nothing exists.
     */
    fun readAll(): String {
        synchronized(lock) {
            val parts = ArrayList<String>(maxRotations + 1)
            for (i in maxRotations downTo 1) {
                val f = File(rotatedPath(i))
                if (f.exists()) parts.add(f.readText(Charsets.UTF_8))
            }
            if (file.exists()) parts.add(file.readText(Charsets.UTF_8))
            return parts.joinToString("")
        }
    }

    /** The live file being appended to (may not exist until the first write). */
    fun file(): File = file

    companion object {
        /** About 1 MB per file. */
        const val DEFAULT_MAX_BYTES = 1L shl 20
        /** Keep the current file plus up to three rotations (about 4 MB total). */
        const val DEFAULT_MAX_ROTATIONS = 3
    }
}
