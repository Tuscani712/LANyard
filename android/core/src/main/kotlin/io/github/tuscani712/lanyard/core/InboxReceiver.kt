package io.github.tuscani712.lanyard.core

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Places a verified spooled file into the user's destination (the SAF download
 * folder in the app). The implementation copies the file and returns the name it
 * was saved under; it throws on failure. Called one file at a time.
 */
fun interface PushDestination {
    fun place(relPath: String, spool: File, size: Long): String
}

/** One file in an accepted push. */
data class PushFileState(
    val relPath: String,
    val size: Long,
    val mtimeMillis: Long,
    var done: Long = 0,
    var placedName: String? = null,
) {
    internal lateinit var part: File
}

/** One push being received, for the UI. */
data class IncomingPush(
    val id: String,
    val peerFp: String,
    val peerName: String,
    val files: Int,
    val total: Long,
    val done: Long,
)

/**
 * The phone-side Inbox: validates and spools a push, verifies each file's
 * SHA-256, hands it to the [PushDestination], and deletes the spool file
 * immediately. The spool lives under the app's private files directory (not
 * cache, which Android may clear under storage pressure), so an interrupted
 * receive can resume.
 *
 * All file writes stream through a fixed buffer; no file is ever held in memory.
 */
class InboxReceiver(
    private val spoolRoot: File,
    private val destination: PushDestination,
    private val freeBytes: () -> Long,
    private val onChange: () -> Unit = {},
    private val onOffer: (pushId: String, peerFp: String, files: Int, total: Long) -> Unit = { _, _, _, _ -> },
    private val onProgress: (pushId: String, done: Long, total: Long) -> Unit = { _, _, _ -> },
    private val onDone: (pushId: String, peerFp: String, files: Int, total: Long) -> Unit = { _, _, _, _ -> },
    private val onCancelled: (pushId: String, reason: String) -> Unit = { _, _ -> },
    // Fired when an in-flight receive dies on its own (a file body threw, or the
    // connection was closed mid-body) rather than finishing or being declined.
    // It is mutually exclusive with [onDone] and [onCancelled].
    private val onFailed: (pushId: String, reason: String) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
    // Whether a verified file can actually be saved right now (a download folder
    // is set and writable). When false, an offer is refused up front with a clear
    // reason instead of being accepted and failing later mid-transfer (Task 30).
    private val destinationReady: () -> Boolean = { true },
) {
    class Session(
        val id: String,
        val peerFp: String,
        val peerName: String,
        val files: LinkedHashMap<String, PushFileState>,
        var total: Long,
        val createdAt: Long,
    )

    private val sessions = LinkedHashMap<String, Session>()
    private val cancelled = HashSet<String>()
    private val placeLock = Any()

    @Synchronized
    fun offer(
        peerFp: String,
        peerName: String,
        reqs: List<PushFileRequest>,
        totalBytes: Long,
        maxBytes: Long,
    ): PushOffer {
        if (reqs.isEmpty()) throw PeerHttpException(400, "no files")
        if (!destinationReady()) {
            throw PeerHttpException(503, "This device cannot save received files. Choose a writable download folder in Settings.")
        }
        if (reqs.size > PushProtocol.MAX_OFFER_FILES) {
            throw PeerHttpException(413, "too many files in one push")
        }
        var total = 0L
        var largest = 0L
        val files = LinkedHashMap<String, PushFileState>()
        for (r in reqs) {
            if (r.size < 0) throw PeerHttpException(400, "negative size")
            val rel = PushProtocol.sanitizeRel(r.relPath) ?: throw PeerHttpException(400, "bad file name")
            if (files.containsKey(rel)) throw PeerHttpException(400, "duplicate file")
            files[rel] = PushFileState(rel, r.size, r.mtimeMillis)
            total += r.size
            if (r.size > largest) largest = r.size
        }
        val claimed = if (totalBytes > 0) totalBytes else total
        if (claimed > total) total = claimed
        if (maxBytes > 0 && total > maxBytes) {
            throw PeerHttpException(413, "push exceeds the size this device allows")
        }
        val need = PushProtocol.requiredFreeSpace(total, largest)
        val free = freeBytes()
        if (free in 1 until need) throw PeerHttpException(507, "insufficient storage")

        // One in-flight offer per peer and relative path: two peers (or two
        // pushes) sharing a spool file would corrupt each other's data.
        for (existing in sessions.values) {
            if (existing.peerFp.equals(peerFp, ignoreCase = true) && existing.files.keys.any { it in files.keys }) {
                throw PeerHttpException(409, "a push with that name is already in progress")
            }
        }

        val id = "p_" + randHex(6)
        // Parts are keyed by peer, then relative path, so different peers using
        // the same name never share a spool file, and a retry resumes.
        val peerDir = File(spoolRoot, peerFp.take(16).lowercase())
        peerDir.mkdirs()
        for ((rel, st) in files) {
            st.part = File(peerDir, rel + ".lanpart")
            st.part.parentFile?.mkdirs()
            if (st.part.isFile) st.done = minOf(st.part.length(), st.size)
        }
        val sess = Session(id, peerFp, Display.safeName(peerName), files, total, clock())
        sessions[id] = sess
        onChange()
        onOffer(id, peerFp, files.size, total)
        val offsets = files.mapValues { it.value.done }
        return PushOffer(id, true, if (maxBytes > 0) maxBytes else 0, offsets)
    }

    @Synchronized
    private fun session(id: String, peerFp: String): Session {
        if (cancelled.contains(id)) throw PeerHttpException(410, "cancelled by the receiver")
        val s = sessions[id] ?: throw PeerHttpException(404, "no such push")
        if (!s.peerFp.equals(peerFp, ignoreCase = true)) throw PeerHttpException(403, "not your push")
        return s
    }

    /** Appends bytes at [offset] to a file's spool part. Streams; returns bytes written. */
    fun writeChunk(id: String, peerFp: String, rel: String, offset: Long, input: InputStream): Long {
        val s = session(id, peerFp)
        val st = s.files[rel] ?: throw PeerHttpException(404, "no such file in push")
        return try {
            writeStream(s, st, offset, input, null)
        } catch (e: Exception) {
            fail(s.id, reasonFor(e))
            throw e
        }
    }

    /** Whole-file fast path: one request carries the bytes and the digest. */
    fun receiveWhole(id: String, peerFp: String, rel: String, sha256: String, input: InputStream): Long {
        val s = session(id, peerFp)
        val st = s.files[rel] ?: throw PeerHttpException(404, "no such file in push")
        if (st.done != 0L) throw PeerHttpException(409, "file already partly received")
        return try {
            writeStream(s, st, 0, input, sha256)
            st.placedName = place(st)
            synchronized(this) { st.done = st.size }
            onChange()
            st.size
        } catch (e: Exception) {
            fail(s.id, reasonFor(e))
            throw e
        }
    }

    /** A person-readable reason for a receive that died mid-body. */
    private fun reasonFor(e: Exception): String = when {
        e is java.io.IOException -> "Connection lost"
        e is PushCancelledException -> "The transfer was cancelled"
        !e.message.isNullOrBlank() ->
            if (e.message!!.contains("shorter", ignoreCase = true)) "Connection lost" else e.message!!
        else -> "Connection lost"
    }

    /** Verifies a streamed part against [sha256] and places it. */
    fun complete(id: String, peerFp: String, rel: String, sha256: String): PushFileState {
        val s = session(id, peerFp)
        val st = s.files[rel] ?: throw PeerHttpException(404, "no such file in push")
        if (st.part.length() != st.size) {
            throw PeerHttpException(409, "size mismatch: have ${st.part.length()}, expected ${st.size}")
        }
        val got = hashFile(st.part)
        if (!got.equals(sha256, ignoreCase = true)) throw PeerHttpException(409, "checksum mismatch")
        st.placedName = place(st)
        synchronized(this) { st.done = st.size }
        onChange()
        return st
    }

    /** Finishes a push (the job-level "all done"). */
    @Synchronized
    fun finish(id: String, peerFp: String): Boolean {
        val s = sessions[id] ?: return false
        if (!s.peerFp.equals(peerFp, ignoreCase = true)) return false
        sessions.remove(id)
        val peerDir = File(spoolRoot, s.peerFp.take(16).lowercase())
        for (rel in s.files.keys) File(peerDir, rel + ".lanpart").delete()
        onChange()
        onDone(id, peerFp, s.files.size, s.total)
        return true
    }

    @Synchronized
    fun cancel(id: String, peerFp: String): Boolean {
        val s = sessions[id] ?: return false
        if (!s.peerFp.equals(peerFp, ignoreCase = true)) return false
        cancelled.add(id)
        sessions.remove(id)
        val peerDir = File(spoolRoot, s.peerFp.take(16).lowercase())
        for (rel in s.files.keys) File(peerDir, rel + ".lanpart").delete()
        onChange()
        onCancelled(id, "The transfer was cancelled")
        return true
    }

    /**
     * Removes an in-flight session that died on its own and reports it failed
     * exactly once. The `.lanpart` spool is intentionally KEPT: a dropped
     * connection is not a decline, so a re-offer for the same peer/file resumes
     * from the existing partial's size. Only an explicit [cancel] deletes the
     * spool. Returns false when the session was already gone (finished or
     * explicitly cancelled), so [onDone]/[onCancelled]/[onFailed] never double.
     */
    @Synchronized
    fun fail(id: String, reason: String): Boolean {
        if (sessions.remove(id) == null) return false
        onChange()
        onFailed(id, reason)
        return true
    }

    @Synchronized
    fun wasCancelled(id: String): Boolean = cancelled.contains(id)

    @Synchronized
    fun incoming(): List<IncomingPush> = sessions.values.map {
        IncomingPush(it.id, it.peerFp, it.peerName, it.files.size, it.total, it.files.values.sumOf { f -> f.done })
    }

    @Synchronized
    fun count(): Int = sessions.size

    /** True when a partial spool file exists (an interrupted receive to resume). */
    fun hasPartialSpool(): Boolean =
        spoolRoot.isDirectory && spoolRoot.walkTopDown().any { it.isFile && it.name.endsWith(".lanpart") }

    /**
     * Deletes spool parts that belong to no in-memory push (leftovers from a
     * previous run). A push still in progress in this process is kept, so
     * acknowledging an interruption never corrupts a live transfer. Returns how
     * many were removed.
     */
    @Synchronized
    fun clearAbandonedSpool(): Int {
        if (!spoolRoot.isDirectory) return 0
        val active = sessions.values
            .flatMap { s -> s.files.values.map { it.part.absolutePath } }
            .toSet()
        val abandoned = spoolRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".lanpart") && it.absolutePath !in active }
            .toList()
        abandoned.forEach { it.delete() }
        return abandoned.size
    }

    /** Deletes spool files older than the TTL (an abandoned push). */
    fun sweepStale(ttlMillis: Long = 24 * 60 * 60 * 1000) {
        if (!spoolRoot.isDirectory) return
        val cutoff = clock() - ttlMillis
        spoolRoot.walkTopDown().filter { it.isFile && it.name.endsWith(".lanpart") }
            .forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    /** Streams [input] into the spool part, hashing while it goes. */
    private fun writeStream(
        s: Session,
        st: PushFileState,
        offset: Long,
        input: InputStream,
        wantSha: String?,
    ): Long {
        if (offset < 0 || offset > st.size) throw PeerHttpException(400, "bad offset")
        st.part.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        if (offset > 0) hashPrefix(st.part, offset, digest)
        val out = java.io.FileOutputStream(st.part, offset > 0)
        val limit = st.size - offset
        var written = 0L
        try {
            val buf = ByteArray(256 * 1024)
            // Cap while reading: a client (or a chunked stream) must never write
            // more than the offered size, or it could fill the disk first and be
            // rejected only afterwards.
            while (written < limit) {
                if (wasCancelled(s.id)) throw PushCancelledException()
                val want = minOf(buf.size.toLong(), limit - written).toInt()
                val n = input.read(buf, 0, want)
                if (n < 0) break
                out.write(buf, 0, n)
                digest.update(buf, 0, n)
                written += n
                synchronized(this) { st.done = offset + written }
                onProgress(s.id, offset + written, st.size)
            }
            if (written >= limit && input.read() >= 0) {
                // One byte past the offered size: truncate the part and refuse.
                out.close()
                st.part.delete()
                st.done = 0
                throw PeerHttpException(400, "file longer than offered size")
            }
            out.fd.sync()
        } finally {
            runCatching { out.close() }
        }
        if (offset == 0L && st.size > 0 && st.part.length() != st.size && wantSha != null) {
            throw PeerHttpException(409, "size mismatch")
        }
        if (wantSha != null) {
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (!got.equals(wantSha, ignoreCase = true)) throw PeerHttpException(409, "checksum mismatch")
        }
        return st.part.length()
    }

    private fun hashPrefix(file: File, offset: Long, digest: MessageDigest) {
        if (!file.isFile) return
        file.inputStream().use { ins ->
            val buf = ByteArray(256 * 1024)
            var left = minOf(offset, file.length())
            while (left > 0) {
                val n = ins.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                if (n < 0) break
                digest.update(buf, 0, n)
                left -= n
            }
        }
    }

    /** Places one verified file, one at a time, then deletes the spool part. */
    private fun place(st: PushFileState): String {
        synchronized(placeLock) {
            val name = destination.place(st.relPath, st.part, st.size)
            st.part.delete()
            return name
        }
    }

    private fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun randHex(bytes: Int): String {
        val b = ByteArray(bytes)
        java.security.SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }
}
