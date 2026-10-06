package io.github.tuscani712.lanyard.core

import com.google.gson.JsonObject
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** One file in a share's manifest, with a path relative to the share root. */
data class ManifestFile(
    val path: String,
    val name: String,
    val size: Long,
    val etag: String,
)

/**
 * The destination for one downloaded file. [existingSize] is how many bytes are
 * already present (for resume); [openAt] returns a stream positioned to receive
 * bytes starting at [offset], and [openExisting] (optional) re-reads the present
 * bytes so a resumed file's digest still covers the whole file.
 */
data class DownloadTarget(
    val existingSize: Long = 0,
    val openAt: (offset: Long) -> OutputStream,
    val openExisting: (() -> InputStream)? = null,
)

/** The outcome of a download. */
sealed class DownloadResult {
    data class Done(val files: Int, val bytes: Long) : DownloadResult()

    /** We cancelled it locally. */
    object Cancelled : DownloadResult()

    /** HTTP 410: the sender stopped or expired the share. */
    object ShareEnded : DownloadResult()

    /** The peer could not be reached or answered unexpectedly. */
    object PeerUnreachable : DownloadResult()

    /** A downloaded file's SHA-256 did not match the peer's. */
    data class HashMismatch(val path: String) : DownloadResult()

    /** A manifest path was absolute, traversing, or otherwise unsafe. */
    object UnsafePath : DownloadResult()

    data class Failed(val message: String) : DownloadResult()
}

/**
 * The share operations a download needs. [PeerClient] implements it; tests can
 * wrap a real one to inject corrupt bytes. Taking this seam (rather than a
 * concrete client) is what lets the hash-mismatch path be tested.
 */
interface ShareReader {
    fun manifestFiles(shareId: String, path: String): JsonObject
    fun openFileStream(shareId: String, path: String, rangeFrom: Long): InputStream
    fun wholeFileHash(shareId: String, path: String): String
    fun reportComplete(shareId: String, verified: List<Pair<String, String>>): Boolean
}

/**
 * Downloads a share (or a subpath of one) into caller-provided [DownloadTarget]s:
 * fetch the manifest, then stream each file, verifying its SHA-256 against the
 * peer's whole-file digest. Nothing is ever written outside the caller's folder
 * because every manifest path is validated first. Blocking; call off the main
 * thread.
 */
class DownloadSession(private val reader: ShareReader) {

    fun download(
        shareId: String,
        path: String,
        targetFor: (ManifestFile) -> DownloadTarget,
        onProgress: (index: Int, file: ManifestFile, received: Long, total: Long) -> Unit = { _, _, _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): DownloadResult {
        val manifest = try {
            reader.manifestFiles(shareId, path)
        } catch (e: PeerStatusException) {
            return if (e.code == 410) DownloadResult.ShareEnded else DownloadResult.PeerUnreachable
        } catch (_: Exception) {
            return DownloadResult.PeerUnreachable
        }

        val files = parseManifest(manifest)
        for (file in files) {
            if (!isSafeRelPath(file.path)) return DownloadResult.UnsafePath
        }

        var totalBytes = 0L
        val verified = ArrayList<Pair<String, String>>()

        files.forEachIndexed { index, file ->
            val target = targetFor(file)
            var offset = target.existingSize.coerceIn(0L, file.size)
            val digest = MessageDigest.getInstance("SHA-256")

            try {
                if (offset > 0) {
                    val existing = target.openExisting?.invoke()
                    if (existing == null) {
                        offset = 0L // cannot hash the prefix; start over
                    } else {
                        existing.use { hashInto(digest, it) }
                    }
                }

                val received = transfer(
                    reader = reader,
                    shareId = shareId,
                    file = file,
                    offset = offset,
                    target = target,
                    digest = digest,
                    index = index,
                    onProgress = onProgress,
                    isCancelled = isCancelled,
                ) ?: return DownloadResult.Cancelled
                totalBytes += received

                val got = digest.digest().joinToString("") { "%02x".format(it) }
                val want = try {
                    reader.wholeFileHash(shareId, file.path)
                } catch (e: PeerStatusException) {
                    return if (e.code == 410) DownloadResult.ShareEnded else DownloadResult.PeerUnreachable
                } catch (_: Exception) {
                    return DownloadResult.PeerUnreachable
                }
                if (!got.equals(want, ignoreCase = true)) return DownloadResult.HashMismatch(file.path)
                verified.add(file.path to got)
            } catch (e: PeerStatusException) {
                return if (e.code == 410) DownloadResult.ShareEnded else DownloadResult.PeerUnreachable
            } catch (_: Exception) {
                return DownloadResult.PeerUnreachable
            }
        }

        try {
            reader.reportComplete(shareId, verified)
        } catch (_: Exception) {
            // A failed "complete" only affects one-time shares; the bytes are already down.
        }
        return DownloadResult.Done(files.size, totalBytes)
    }

    /** Streams one file; returns bytes written, or null if cancelled. */
    private fun transfer(
        reader: ShareReader,
        shareId: String,
        file: ManifestFile,
        offset: Long,
        target: DownloadTarget,
        digest: MessageDigest,
        index: Int,
        onProgress: (Int, ManifestFile, Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ): Long? {
        val input = reader.openFileStream(shareId, file.path, offset)
        val output = target.openAt(offset)
        var received = 0L
        input.use { ins ->
            output.use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    if (isCancelled()) return null
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    received += n
                    onProgress(index, file, offset + received, file.size)
                }
            }
        }
        return received
    }

    private fun hashInto(digest: MessageDigest, source: InputStream) {
        source.use { ins ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
    }

    private fun parseManifest(manifest: JsonObject): List<ManifestFile> {
        val out = ArrayList<ManifestFile>()
        manifest.getAsJsonArray("files")?.forEach { element ->
            val f = element.asJsonObject
            out.add(
                ManifestFile(
                    path = f.str("path"),
                    name = f.str("name"),
                    size = f.get("size")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                    etag = f.str("etag"),
                ),
            )
        }
        return out
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString ?: ""

    companion object {
        /**
         * A manifest path is safe when it is relative, uses forward slashes, has
         * no `..`, `.`, empty or drive/ADS (`:`) segments, and no backslashes.
         * The empty string is allowed: it is a single-file share's own entry.
         */
        fun isSafeRelPath(path: String): Boolean {
            if (path.isEmpty()) return true
            if (path.startsWith("/")) return false
            if (path.contains('\\')) return false
            return path.split('/').none { it.isEmpty() || it == "." || it == ".." || it.contains(':') }
        }
    }
}
