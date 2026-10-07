package io.github.tuscani712.lanyard.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/** A share as the peer sees it (mirrors `shares.Summary`). */
data class ShareInfo(
    val id: String,
    val label: String,
    val name: String,
    val kind: String,
    val size: Long,
    val lifetime: String = "until_stopped",
)

/** One directory entry while building a manifest/tree. */
data class ShareChild(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val mtimeMillis: Long,
)

/** A resolved, readable file inside a share. */
data class ResolvedFile(
    val name: String,
    val size: Long,
    val mtimeMillis: Long,
    /** A stream positioned at [offset] already. */
    val openAt: (offset: Long) -> InputStream,
)

/**
 * The phone's shares, backed by the SAF tree in the app and by a temp dir in
 * tests. Implementations MUST reject an unsafe [rel] (see [SafePath]) and MUST
 * only ever resolve a document inside the picked share root.
 */
interface ShareSource {
    fun list(): List<ShareInfo>

    /** Children of [rel] ("" = the root), or null if the share/path is missing. */
    fun children(shareId: String, rel: String): List<ShareChild>?

    /** The file at [rel], or null if missing/unsafe. */
    fun resolve(shareId: String, rel: String): ResolvedFile?

    /** A reason string when the share ended/was stopped, else null. */
    fun ended(shareId: String): String?
}

/** Pure path rules for a manifest/file request against a share root. */
object SafePath {
    const val MAX_SEGMENT = 255

    fun validSegment(s: String): Boolean =
        s.isNotEmpty() && s != "." && s != ".." && s.length <= MAX_SEGMENT &&
            s.none { it == '/' || it == '\\' || it == '\u0000' || it == ':' || it.isISOControl() }

    /** A safe relative path: "" (the root/own file) or valid segments joined by '/'. */
    fun validRel(rel: String): Boolean {
        if (rel.isEmpty()) return true
        if (rel.startsWith("/") || rel.startsWith("\\")) return false
        return rel.split('/').all { validSegment(it) }
    }

    fun childPath(base: String, name: String): String = if (base.isEmpty()) name else "$base/$name"
}

/**
 * Serves shares to a paired peer (pull), matching the Go server's shapes and
 * status codes. Full file bodies carry `Content-Length` and **no** SHA trailer;
 * the Go client then falls back to `/hash` (`internal/transfer/transfer.go:1501`),
 * which is served from a digest cached during the stream. Ranged bodies carry
 * `Content-Length` too.
 *
 * Concurrency: [maxConcurrent] file/hash operations per peer and in total;
 * beyond that a 503 with `Retry-After`, never queued, so a stalled reader cannot
 * hold a worker.
 */
class ShareServer(
    private val source: ShareSource,
    private val maxManifestEntries: Int = 50_000,
    private val maxManifestDepth: Int = 32,
    private val maxManifestMillis: Long = 20_000,
    private val maxConcurrent: Int = 4,
    private val stallTimeoutMillis: Long = 30_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val global = Semaphore(maxConcurrent)
    private val perPeer = ConcurrentHashMap<String, Semaphore>()
    // Bounded LRU of whole-file digests: a peer asking for many hashes must not
    // grow memory without bound. Keyed by share/path/etag.
    private val hashes = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > HASH_CACHE_MAX
    }

    /** Current digest-cache size (exposed for tests). */
    @Synchronized
    fun hashCacheSize(): Int = hashes.size

    @Synchronized
    private fun getHash(key: String): String? = hashes[key]

    @Synchronized
    private fun putHash(key: String, sum: String) { hashes[key] = sum }
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    /** Cancels any in-flight stream for [shareId]; later requests get 410. */
    fun cancel(shareId: String) { cancelled.add(shareId) }

    /** Handles any `/api/v1/shares...`; [peer] already has browse permission. */
    fun handle(
        out: OutputStream,
        method: String,
        path: String,
        query: Map<String, String>,
        rangeHeader: String?,
        ifRange: String?,
        body: ByteArray,
        peer: PairedPeer,
        progress: () -> Unit,
    ) {
        val rest = path.removePrefix("/api/v1/shares").trimStart('/')
        when {
            path == "/api/v1/shares" && method == "GET" -> writeJson(out, 200, listJson())
            rest.endsWith("/manifest") && method == "GET" ->
                manifestResponse(out, rest.removeSuffix("/manifest"), query["path"] ?: "")
            rest.endsWith("/tree") && method == "GET" ->
                treeResponse(out, rest.removeSuffix("/tree"), query["path"] ?: "")
            rest.endsWith("/file") && (method == "GET" || method == "HEAD") ->
                fileResponse(out, rest.removeSuffix("/file"), query["path"] ?: "", method, rangeHeader, ifRange, peer, progress)
            rest.endsWith("/hash") && method == "GET" ->
                hashResponse(out, rest.removeSuffix("/hash"), query["path"] ?: "", peer)
            rest.endsWith("/complete") && method == "POST" ->
                writeJson(out, 200, """{"consumed":false}""")
            else -> writeJson(out, 404, errorJson("not found"))
        }
    }

    private fun listJson(): String {
        val arr = JsonArray()
        for (s in source.list()) {
            arr.add(JsonObject().apply {
                addProperty("share_id", s.id)
                addProperty("label", s.label)
                if (s.name.isNotEmpty()) addProperty("name", s.name)
                addProperty("kind", s.kind)
                if (s.size > 0) addProperty("size", s.size)
                addProperty("lifetime", s.lifetime)
            })
        }
        return arr.toString()
    }

    private fun manifestResponse(out: OutputStream, shareId: String, rel: String) {
        if (!shareGate(out, shareId)) return
        if (!SafePath.validRel(rel)) {
            writeJson(out, 400, errorJson("bad path"))
            return
        }
        val started = clock()
        val files = JsonArray()
        var total = 0L
        var count = 0
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(rel to 0)
        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            if (depth > maxManifestDepth) {
                writeJson(out, 413, errorJson("folder is too deep to list"))
                return
            }
            val kids = source.children(shareId, dir) ?: run { writeJson(out, 404, errorJson("not found")); return }
            for (c in kids) {
                if (!SafePath.validSegment(c.name)) continue // never serve a bad name
                if (c.isDir) {
                    queue.add(c.path to depth + 1)
                } else {
                    if (count >= maxManifestEntries || clock() - started > maxManifestMillis) {
                        writeJson(out, 413, errorJson("too many files to list"))
                        return
                    }
                    files.add(JsonObject().apply {
                        addProperty("path", c.path)
                        addProperty("name", c.name)
                        addProperty("size", c.size)
                        addProperty("mtime", rfc3339(c.mtimeMillis))
                        addProperty("etag", validator(c.size, c.mtimeMillis))
                    })
                    total += c.size
                    count++
                }
            }
        }
        val root = JsonObject().apply {
            add("files", files)
            addProperty("total_bytes", total)
            addProperty("count", count)
            addProperty("lifetime", "until_stopped")
        }
        writeJson(out, 200, root.toString())
    }

    private fun treeResponse(out: OutputStream, shareId: String, rel: String) {
        if (!shareGate(out, shareId)) return
        if (!SafePath.validRel(rel)) {
            writeJson(out, 400, errorJson("bad path"))
            return
        }
        val kids = source.children(shareId, rel) ?: run { writeJson(out, 404, errorJson("not found")); return }
        val arr = JsonArray()
        for (c in kids) {
            if (!SafePath.validSegment(c.name)) continue
            arr.add(JsonObject().apply {
                addProperty("name", c.name)
                addProperty("path", c.path)
                addProperty("is_dir", c.isDir)
                addProperty("size", c.size)
                addProperty("mtime", rfc3339(c.mtimeMillis))
                if (!c.isDir) addProperty("etag", validator(c.size, c.mtimeMillis))
            })
        }
        writeJson(out, 200, arr.toString())
    }

    private fun fileResponse(
        out: OutputStream,
        shareId: String,
        rel: String,
        method: String,
        rangeHeader: String?,
        ifRange: String?,
        peer: PairedPeer,
        progress: () -> Unit,
    ) {
        if (!shareGate(out, shareId)) return
        if (!SafePath.validRel(rel) || rel.isEmpty()) {
            writeJson(out, 400, errorJson("bad path"))
            return
        }
        val file = source.resolve(shareId, rel) ?: run { writeJson(out, 404, errorJson("not found")); return }
        val etag = validator(file.size, file.mtimeMillis)
        if (!acquire(peer.fingerprint)) {
            retryLater(out)
            return
        }
        try {
            // If-Range: only honour a range when the validator still matches.
            val range = if (ifRange != null && ifRange.trim('"', ' ') != etag) null else rangeHeader
            val parsed = parseRange(range, file.size)
            if (parsed == Range.Bad) {
                val h = mapOf("Content-Range" to "bytes */${file.size}")
                writeJson(out, 416, errorJson("range not satisfiable"), h)
                return
            }
            val start = parsed.start
            val length = parsed.length
            val headers = HashMap<String, String>()
            headers["ETag"] = "\"$etag\""
            headers["Accept-Ranges"] = "bytes"
            headers["Content-Type"] = "application/octet-stream"
            val code = if (parsed.partial) 206 else 200
            if (parsed.partial) headers["Content-Range"] = "bytes $start-${start + length - 1}/${file.size}"
            writeHead(out, code, length, headers)
            if (method == "GET") streamWithDeadline(out, file, start, length, etag, progress, shareId)
        } finally {
            release(peer.fingerprint)
        }
    }

    /**
     * Streams on a worker and abandons the handler if the client stops reading,
     * releasing its concurrency slot. (Java cannot interrupt a blocked TCP
     * write, so the handler must not wait on it; PeerServer closes the socket,
     * which lets the worker die.)
     */
    private fun streamWithDeadline(out: OutputStream, file: ResolvedFile, start: Long, length: Long, etag: String, progress: () -> Unit, shareId: String) {
        val last = java.util.concurrent.atomic.AtomicLong(clock())
        val done = java.util.concurrent.CountDownLatch(1)
        val worker = Thread({
            try {
                streamFile(out, file, start, length, etag, { last.set(clock()); progress() }, shareId)
            } catch (_: Exception) {
                // socket closed / abandoned
            } finally {
                done.countDown()
            }
        }, "lanyard-share-stream").apply { isDaemon = true }
        worker.start()
        while (!done.await(50, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            if (clock() - last.get() > stallTimeoutMillis) return // abandon; PeerServer closes the socket
        }
    }

    private fun streamFile(out: OutputStream, file: ResolvedFile, start: Long, length: Long, etag: String, progress: () -> Unit, shareId: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        var sent = 0L
        file.openAt(start).use { input ->
            val buf = ByteArray(256 * 1024)
            var remaining = length
            while (remaining > 0) {
                if (cancelled.contains(shareId)) return
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                digest.update(buf, 0, n)
                sent += n
                remaining -= n
                progress()
            }
        }
        out.flush()
        if (start == 0L && sent == file.size) {
            putHash(hashKey(file.name, etag), digest.digest().joinToString("") { "%02x".format(it) })
        }
    }

    private fun hashResponse(out: OutputStream, shareId: String, rel: String, peer: PairedPeer) {
        if (!shareGate(out, shareId)) return
        if (!SafePath.validRel(rel) || rel.isEmpty()) {
            writeJson(out, 400, errorJson("bad path"))
            return
        }
        val file = source.resolve(shareId, rel) ?: run { writeJson(out, 404, errorJson("not found")); return }
        val etag = validator(file.size, file.mtimeMillis)
        if (!acquire(peer.fingerprint)) {
            retryLater(out)
            return
        }
        try {
            val sum = getHash(hashKey(file.name, etag)) ?: run {
                val md = MessageDigest.getInstance("SHA-256")
                file.openAt(0).use { ins ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (cancelled.contains(shareId)) break
                        val n = ins.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n)
                    }
                }
                md.digest().joinToString("") { "%02x".format(it) }.also { putHash(hashKey(file.name, etag), it) }
            }
            val o = JsonObject().apply {
                addProperty("sha256", sum)
                addProperty("size", file.size)
                addProperty("etag", etag)
            }
            writeJson(out, 200, o.toString())
        } finally {
            release(peer.fingerprint)
        }
    }

    /** True when the share exists and has not ended; otherwise writes the error. */
    private fun shareGate(out: OutputStream, shareId: String): Boolean {
        val reason = if (cancelled.contains(shareId)) "stopped" else source.ended(shareId)
        if (reason != null) {
            val msg = when (reason) {
                "expired" -> "This share has expired."
                "completed" -> "This one-time share has already been downloaded."
                else -> "The sender stopped this share."
            }
            writeJson(out, 410, errorJson(msg))
            return false
        }
        if (source.list().none { it.id == shareId }) {
            writeJson(out, 404, errorJson("share not found"))
            return false
        }
        return true
    }

    private class Range(val start: Long, val length: Long, val partial: Boolean) {
        companion object {
            val Full = Range(0, 0, false)
            val Bad = Range(-1, 0, false)
        }
    }

    /** Parses a single "bytes=START-END"; null/absent = full. Bad = 416. */
    private fun parseRange(header: String?, size: Long): Range {
        if (header.isNullOrBlank()) return Range(0, size, false)
        val h = header.trim()
        if (!h.startsWith("bytes=")) return Range.Bad
        val spec = h.removePrefix("bytes=")
        if (spec.contains(',')) return Range.Bad
        val dash = spec.indexOf('-')
        if (dash < 0) return Range.Bad
        val loText = spec.substring(0, dash).trim()
        val hiText = spec.substring(dash + 1).trim()
        val lo = loText.toLongOrNull() ?: return Range.Bad
        if (lo < 0 || lo >= size) return Range.Bad
        val hi = if (hiText.isEmpty()) size - 1 else (hiText.toLongOrNull() ?: return Range.Bad)
        if (hi < lo) return Range.Bad
        val end = minOf(hi, size - 1)
        return Range(lo, end - lo + 1, true)
    }

    private fun acquire(fp: String): Boolean {
        if (!global.tryAcquire()) return false
        val p = perPeer.computeIfAbsent(fp.lowercase()) { Semaphore(maxConcurrent) }
        if (!p.tryAcquire()) {
            global.release()
            return false
        }
        return true
    }

    private fun release(fp: String) {
        global.release()
        perPeer[fp.lowercase()]?.release()
    }

    private fun retryLater(out: OutputStream) {
        writeJson(out, 503, errorJson("too many transfers in progress"), mapOf("Retry-After" to "5"))
    }

    private fun hashKey(name: String, etag: String) = "$name|$etag"

    private fun validator(size: Long, mtimeMillis: Long): String = "%x-%x".format(size, mtimeMillis)

    private fun rfc3339(millis: Long): String = java.time.Instant.ofEpochMilli(millis).toString()

    private fun writeHead(out: OutputStream, code: Int, contentLength: Long, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $code ${reason(code)}\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("Content-Length: ").append(contentLength).append("\r\n")
        sb.append("Connection: keep-alive\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun writeJson(out: OutputStream, code: Int, json: String, headers: Map<String, String> = emptyMap()) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder("HTTP/1.1 $code ${reason(code)}\r\n")
        sb.append("Content-Type: application/json\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("Content-Length: ").append(bytes.size).append("\r\n")
        sb.append("Connection: keep-alive\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun errorJson(message: String): String = JsonObject().apply { addProperty("error", message) }.toString()

    private companion object {
        const val HASH_CACHE_MAX = 256
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        206 -> "Partial Content"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        410 -> "Gone"
        413 -> "Payload Too Large"
        416 -> "Range Not Satisfiable"
        503 -> "Service Unavailable"
        else -> "Error"
    }
}
