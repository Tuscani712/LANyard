package io.github.tuscani712.lanyard.core

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Places a verified spooled file into the user's destination (the SAF download
 * folder in the app). The implementation copies the file and returns the name it
 * was saved under; it throws on failure. Called one file at a time.
 */
fun interface PushDestination {
    fun place(relPath: String, spool: File, size: Long): String

    /**
     * The folder this destination writes into, for the Transfers row and the
     * diagnostics log. Empty when the destination cannot name itself.
     */
    fun folder(): String = ""
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

    /**
     * Serializes the finalize (hash + place) of this one file, so two concurrent
     * complete requests for the same file cannot both place it.
     */
    internal val lock = Any()

    /**
     * Set once the file has been verified and handed to the destination. A
     * repeated complete/whole-file request for this file is then an idempotent
     * success instead of placing it a second time or erroring.
     */
    @Volatile
    internal var placed: Boolean = false
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
    // Fired once every body byte of a file has arrived and the receiver is now
    // hashing the spool part and copying it into the destination (or, on the
    // whole-file fast path, copying it). The bytes are all in but the file is
    // not placed yet, so the row shows "Finishing…" instead of a silent 100%.
    private val onFinishing: (pushId: String, bytes: Long) -> Unit = { _, _ -> },
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
    // How long a body that is actually being read may make no progress before
    // the reaper fails the session (mirrors the desktop). It only applies while
    // a body is in flight: a slow LAN transfer keeps resetting the deadline on
    // every read. Injectable for tests.
    private val stallTimeoutMillis: Long = 60_000,
    // How long a push with no body in flight may sit untouched before the
    // reaper fails it. Senders legitimately pause between files (hashing the
    // next one can take a while), so this is far more generous than the stall
    // timeout. Injectable for tests.
    private val idleTimeoutMillis: Long = 10 * 60_000,
    // One line per push event for the diagnostics report. Format:
    // `[push] peer=<short> <event> ...`. Never a file name, only a path class.
    private val diag: (String) -> Unit = {},
) {
    class Session(
        val id: String,
        val peerFp: String,
        val peerName: String,
        val files: LinkedHashMap<String, PushFileState>,
        var total: Long,
        val createdAt: Long,
    ) {
        // Which file bodies are actually being read right now. A push can have
        // several concurrent bodies (the desktop opens parallel PUTs), and one
        // body finishing must not make the others look idle: the reaper judges
        // a push by the short stall timeout only while at least one body is in
        // flight, and by the long idle timeout once they are all done.
        private val inFlightFiles: MutableSet<String> =
            java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** Number of file bodies currently being read for this push. */
        val inFlightCount: Int get() = inFlightFiles.size

        /** True while any body is actually being read for this push. */
        val inFlight: Boolean get() = inFlightFiles.isNotEmpty()

        /** Marks [rel]'s body as started so the reaper uses the stall timeout. */
        fun bodyStarted(rel: String) {
            inFlightFiles.add(rel)
        }

        /** Marks [rel]'s body as finished; other bodies keep the push in flight. */
        fun bodyFinished(rel: String) {
            inFlightFiles.remove(rel)
        }

        /** Set by the reaper so a read still blocked aborts on its next byte. */
        @Volatile var dead: Boolean = false

        /** The time of the last byte parsed, or the last body start/end. */
        @Volatile var lastProgress: Long = createdAt
    }

    private val sessions = LinkedHashMap<String, Session>()
    private val cancelled = HashSet<String>()
    private val placeLock = Any()

    // A daemon reaper sweeps stalled/idle pushes, mirroring the desktop. The
    // sweep interval tracks the shorter timeout so a test-sized timeout is
    // noticed promptly. Disabled when both timeouts are non-positive.
    private val reaper: ScheduledExecutorService? =
        if (stallTimeoutMillis > 0 || idleTimeoutMillis > 0) {
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "lanyard-inbox-reaper").apply { isDaemon = true }
            }
        } else {
            null
        }

    init {
        val reaper = this.reaper
        if (reaper != null) {
            val shorter = listOf(stallTimeoutMillis, idleTimeoutMillis).filter { it > 0 }.minOrNull() ?: 0L
            val interval = maxOf(shorter / 4, 10L)
            reaper.scheduleWithFixedDelay({ runCatching { reapStalled() } }, interval, interval, TimeUnit.MILLISECONDS)
        }
    }

    /** Stops the reaper. Safe to call more than once. */
    fun close() {
        reaper?.shutdownNow()
    }

    /**
     * Runs a UI/diagnostic callback so an unexpected exception inside it can
     * never escape back into the body read and turn a healthy transfer into a
     * 500 that closes the socket. The event is logged, with its top stack frame,
     * and then ignored. A callback is a notification, never a participant in the
     * transfer's correctness.
     */
    private fun safeCallback(event: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            runCatching {
                diag("[push] callback-failed event=$event ${t.javaClass.simpleName}: ${t.message?.take(160)} at ${topFrame(t)}")
            }
        }
    }

    /** The top stack frame of [t], for the diagnostics log. */
    private fun topFrame(t: Throwable): String {
        val f = t.stackTrace.firstOrNull() ?: return "unknown"
        return "${f.className.substringAfterLast('.')}.${f.methodName}(${f.fileName}:${f.lineNumber})"
    }

    /**
     * Fails every session whose body has made no progress for the short stall
     * timeout, or that has sat idle with no body in flight for the much longer
     * idle timeout. The `.lanpart` spool is deliberately kept so a re-offer can
     * resume. Returns how many were reaped.
     */
    fun reapStalled(now: Long = clock()): Int {
        val reaped = ArrayList<Triple<String, String, String>>()
        synchronized(this) {
            val it = sessions.entries.iterator()
            while (it.hasNext()) {
                val s = it.next().value
                val limit = if (s.inFlight) stallTimeoutMillis else idleTimeoutMillis
                if (limit <= 0) continue
                if (now - s.lastProgress > limit) {
                    it.remove()
                    s.dead = true
                    reaped.add(Triple(s.id, s.peerFp, "the connection stalled"))
                }
            }
        }
        if (reaped.isEmpty()) return 0
        for ((id, fp, reason) in reaped) {
            diag("[push] peer=${Display.shortFp(fp)} stalled id=$id reason=$reason")
        }
        safeCallback("change") { onChange() }
        for ((id, _, reason) in reaped) safeCallback("failed") { onFailed(id, reason) }
        return reaped.size
    }

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

        // A new offer from the same peer for the same file supersedes a stale
        // session instead of being refused: the peer is retrying after a dropped
        // connection, and the peer-keyed .lanpart spool is shared, so two live
        // writers would corrupt it. A session whose body is genuinely in flight
        // is still guarded (409), so a concurrent legitimate push cannot be
        // clobbered. The superseded session is failed (its .lanpart kept), so
        // the new one resumes from the partial size.
        val superseded = sessions.values.filter {
            it.peerFp.equals(peerFp, ignoreCase = true) && it.files.keys.any { k -> k in files.keys }
        }
        if (superseded.any { it.inFlight }) {
            for (old in superseded) {
                diag("[push] peer=${Display.shortFp(peerFp)} offer refused 409 conflict session=${old.id} age=${clock() - old.createdAt}ms inflight=${old.inFlight}")
            }
            throw PeerHttpException(409, "a push with that name is already in progress")
        }
        for (old in superseded) {
            if (sessions.remove(old.id) != null) {
                old.dead = true
                diag("[push] peer=${Display.shortFp(peerFp)} offer superseded session=${old.id} age=${clock() - old.createdAt}ms")
                safeCallback("change") { onChange() }
                safeCallback("failed") { onFailed(old.id, "replaced by a new offer from the same device") }
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
        diag("[push] peer=${Display.shortFp(peerFp)} offer accepted id=$id files=${files.size} bytes=$total resumed=${files.values.count { it.done > 0 }}")
        safeCallback("change") { onChange() }
        safeCallback("offer") { onOffer(id, peerFp, files.size, total) }
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
        if (st.placed) throw PeerHttpException(409, "file already complete")
        diag("[push] peer=${Display.shortFp(peerFp)} file id=$id cls=${Display.pathClass(rel)} offset=$offset size=${st.size} resume=${offset > 0}")
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
        synchronized(st.lock) {
            if (st.placed) {
                // Idempotent replay: drain the retried body so the connection
                // stays healthy, then report the same result without re-placing.
                input.copyTo(java.io.OutputStream.nullOutputStream())
                return st.size
            }
            if (st.done != 0L) throw PeerHttpException(409, "file already partly received")
            diag("[push] peer=${Display.shortFp(peerFp)} file id=$id cls=${Display.pathClass(rel)} offset=0 size=${st.size} whole=true")
            return try {
                writeStream(s, st, 0, input, sha256)
                safeCallback("finishing") { onFinishing(s.id, st.size) }
                st.placedName = place(st)
                synchronized(this) { st.done = st.size }
                safeCallback("change") { onChange() }
                st.size
            } catch (e: Exception) {
                fail(s.id, reasonFor(e))
                throw e
            }
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
        synchronized(st.lock) {
            // A replayed complete for a file already verified and placed is an
            // idempotent success: return the same result, place nothing again.
            if (st.placed) return st
            if (st.part.length() != st.size) {
                throw PeerHttpException(409, "size mismatch: have ${st.part.length()}, expected ${st.size}")
            }
            // All bytes are in: the file is about to be hashed and copied into
            // the destination, which for a large file takes seconds. Tell the UI
            // before the slow part, not after.
            safeCallback("finishing") { onFinishing(s.id, st.size) }
            val got = hashFile(st.part)
            if (!got.equals(sha256, ignoreCase = true)) throw PeerHttpException(409, "checksum mismatch")
            st.placedName = place(st)
            synchronized(this) { st.done = st.size }
        }
        diag("[push] peer=${Display.shortFp(peerFp)} file complete id=$id cls=${Display.pathClass(rel)} size=${st.size}")
        safeCallback("change") { onChange() }
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
        diag("[push] peer=${Display.shortFp(peerFp)} complete id=$id files=${s.files.size} bytes=${s.total}")
        safeCallback("change") { onChange() }
        safeCallback("done") { onDone(id, peerFp, s.files.size, s.total) }
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
        diag("[push] peer=${Display.shortFp(peerFp)} cancelled id=$id spool=deleted")
        safeCallback("change") { onChange() }
        safeCallback("cancelled") { onCancelled(id, "The transfer was cancelled") }
        return true
    }

    /**
     * Cancels a push from this device's own UI, whatever peer owns it. The
     * `.lanpart` spool is deleted and the session removed, so the stale-session
     * guard no longer blocks a fresh offer. An in-flight body is marked dead so
     * its read aborts on the next byte. Returns the peer fingerprint when a live
     * session was cancelled, else null.
     */
    @Synchronized
    fun cancelLocal(id: String): String? {
        val s = sessions.remove(id) ?: return null
        cancelled.add(id)
        s.dead = true
        val peerDir = File(spoolRoot, s.peerFp.take(16).lowercase())
        for (rel in s.files.keys) File(peerDir, rel + ".lanpart").delete()
        diag("[push] peer=${Display.shortFp(s.peerFp)} cancelled id=$id source=local spool=deleted")
        safeCallback("change") { onChange() }
        safeCallback("cancelled") { onCancelled(id, "The transfer was cancelled") }
        return s.peerFp
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
        val s = sessions.remove(id) ?: return false
        diag("[push] peer=${Display.shortFp(s.peerFp)} failed id=$id reason=$reason")
        safeCallback("change") { onChange() }
        safeCallback("failed") { onFailed(id, reason) }
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
        // Mark this body in flight so the reaper judges it by the short stall
        // timeout, and touch the deadline on every byte so a slow (but live)
        // transfer is never reaped. Tracking is per file: a push with several
        // concurrent bodies stays in flight until the last one finishes.
        s.bodyStarted(st.relPath)
        s.lastProgress = clock()
        try {
            val buf = ByteArray(256 * 1024)
            // Cap while reading: a client (or a chunked stream) must never write
            // more than the offered size, or it could fill the disk first and be
            // rejected only afterwards.
            while (written < limit) {
                if (s.dead) throw java.io.IOException("the connection stalled")
                if (wasCancelled(s.id)) throw PushCancelledException()
                val want = minOf(buf.size.toLong(), limit - written).toInt()
                val n = input.read(buf, 0, want)
                if (n < 0) break
                out.write(buf, 0, n)
                digest.update(buf, 0, n)
                written += n
                synchronized(this) { st.done = offset + written; s.lastProgress = clock() }
                safeCallback("progress") { onProgress(s.id, offset + written, st.size) }
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
            s.bodyFinished(st.relPath)
            s.lastProgress = clock()
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
            // Already placed by an earlier/duplicate request: return the same
            // name without writing or deleting anything again.
            if (st.placed) return st.placedName!!
            val name = destination.place(st.relPath, st.part, st.size)
            st.part.delete()
            st.placedName = name
            st.placed = true
            val folder = destination.folder()
            diag(
                "[push] placed cls=${Display.pathClass(st.relPath)} " +
                    "dest=${if (folder.isBlank()) "unknown" else folder}",
            )
            return name
        }
    }

    /** The folder received files are placed into, for the UI and the log. */
    fun destinationFolder(): String = destination.folder()

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
