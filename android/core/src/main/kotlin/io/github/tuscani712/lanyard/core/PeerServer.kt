package io.github.tuscani712.lanyard.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** How a push-approval prompt ended. */
enum class ApprovalOutcome { ACCEPTED, DECLINED, BUSY, UNAVAILABLE }

/**
 * Asks the person before accepting a push (a paired peer above its ask-over
 * limit). Must answer quickly; returning [ApprovalOutcome.UNAVAILABLE] or
 * [ApprovalOutcome.BUSY] makes the server refuse at once rather than hang the
 * sender.
 */
fun interface PushApproval {
    fun ask(peerFp: String, peerName: String, files: Int, total: Long, names: List<String>): ApprovalOutcome
}

/**
 * The phone-side peer API over mutual TLS 1.3: discovery, pairing (C1) and
 * receiving pushes (C2a).
 *
 * Hardening, and how it differs from C1:
 *  - **Keep-alive:** one TLS connection serves many HTTP/1.1 requests (up to
 *    [maxRequestsPerConnection]), so 1 000 small files are one handshake.
 *  - **Split timeouts:** [headerTimeoutMillis] covers the request line, headers
 *    and small JSON bodies; a file body instead gets a [stallTimeoutMillis] stall
 *    timeout (no progress closes it), and an idle keep-alive connection is
 *    closed after [idleTimeoutMillis].
 *  - **Connection budget by class:** an unpaired caller gets at most
 *    [maxUnpairedConnections]; a paired caller shares a pool of
 *    [maxPairedConnections] (the desktop opens up to 16 in parallel).
 *  - Identity is always the TLS certificate; push endpoints require a paired
 *    fingerprint with the `push` permission.
 */
class PeerServer(
    private val sessions: PairingSessions,
    private val receiver: InboxReceiver,
    private val invites: PairInvites,
    private val isPaired: (String) -> PairedPeer?,
    private val metered: () -> Boolean = { false },
    private val wifiOnly: () -> Boolean = { true },
    private val approval: PushApproval? = null,
    private val onUnpair: (String) -> Unit = {},
    private val shares: ShareServer? = null,
    private val headerTimeoutMillis: Int = 15_000,
    private val stallTimeoutMillis: Int = 30_000,
    private val idleTimeoutMillis: Int = 10_000,
    private val maxRequestsPerConnection: Int = 10_000,
    private val maxUnpairedConnections: Int = 4,
    private val maxPairedConnections: Int = 32,
    private val maxIdleUnpairedConnections: Int = MAX_IDLE_UNPAIRED_CONNECTIONS,
    private val idleUnpairedGraceMillis: Int = 1_000,
    private val maxSessionRequestsPerMinute: Int = 10,
    private val maxBodyBytes: Int = 64 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
    private val diagnostics: ServerDiagnostics? = null,
) {
    private fun diag(event: String) = diagnostics?.record(event)

    private var server: SSLServerSocket? = null
    private var pool: ThreadPoolExecutor? = null
    private val live: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    private val requestTimes = ArrayDeque<Long>()
    private var hello: ((Int) -> JsonObject)? = null
    private val unpairedActive = AtomicInteger()
    private val pairedActive = AtomicInteger()
    private val idleUnpaired = AtomicInteger()
    private var stallScheduler: java.util.concurrent.ScheduledExecutorService? = null

    /** Count of accepted TLS connections (used by the handshake-reuse test). */
    val handshakes = AtomicLong()

    @Volatile
    var port: Int = 0
        private set

    fun activeConnections(): Int = pool?.activeCount ?: 0

    /** Unpaired connections currently parked waiting for their next request. */
    fun idleUnpairedConnections(): Int = idleUnpaired.get()

    fun start(identity: Identity, hello: (Int) -> JsonObject): Int {
        stop()
        this.hello = hello
        val ctx = Tls.serverContext(identity)
        val ss = ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        ss.needClientAuth = true
        ss.enabledProtocols = arrayOf("TLSv1.3")

        val counter = AtomicInteger()
        val factory = ThreadFactory { r ->
            Thread(r, "lanyard-peer-${counter.incrementAndGet()}").apply { isDaemon = true }
        }
        val poolSize = maxUnpairedConnections + maxPairedConnections
        val executor = ThreadPoolExecutor(poolSize, poolSize, 30L, TimeUnit.SECONDS, SynchronousQueue(), factory)
        stallScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "lanyard-stall").apply { isDaemon = true } }

        server = ss
        pool = executor
        port = ss.localPort
        Thread({ acceptLoop(ss, executor) }, "lanyard-peer-accept").apply { isDaemon = true }.start()
        return port
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        pool?.shutdownNow()
        pool = null
        stallScheduler?.shutdownNow()
        stallScheduler = null
        hello = null
        for (socket in live) runCatching { socket.close() }
        live.clear()
        synchronized(requestTimes) { requestTimes.clear() }
        port = 0
    }

    private fun acceptLoop(ss: SSLServerSocket, executor: ThreadPoolExecutor) {
        while (!ss.isClosed) {
            val socket = try {
                ss.accept()
            } catch (_: Exception) {
                return
            }
            live.add(socket)
            try {
                executor.execute { handleConnection(socket) }
            } catch (_: RejectedExecutionException) {
                live.remove(socket)
                runCatching { socket.close() }
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        val ssl = socket as SSLSocket
        var reason = "error"
        diag("conn open")
        try {
            ssl.startHandshake()
            handshakes.incrementAndGet()
            val fp = peerFingerprint(ssl) ?: run { reason = "no-cert"; return }
            diag("handshake ok peer=${fp.take(8)} paired=${if (isPaired(fp) != null) "yes" else "no"}")
            reason = requestLoop(ssl, fp)
        } catch (e: Exception) {
            // handshake/read failure or a timeout: drop
            reason = "error:" + e.javaClass.simpleName
        } finally {
            diag("conn close $reason")
            live.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun acquireConnection(counter: AtomicInteger, cap: Int): Boolean {
        while (true) {
            val cur = counter.get()
            if (cur >= cap) return false
            if (counter.compareAndSet(cur, cur + 1)) return true
        }
    }

    // --- HTTP/1.1 request loop ---

    private class Head(val method: String, val path: String, val query: String, val headers: Map<String, String>, val close: Boolean)

    private fun requestLoop(ssl: SSLSocket, fp: String): String {
        val input = BufferedInputStream(ssl.getInputStream())
        val out = BufferedOutputStream(ssl.getOutputStream())
        var requests = 0
        // Set only while a file body is being read; cleared once it is fully
        // consumed. If the loop leaves (drop, timeout, stop) with it still set,
        // the receive session is failed so its row cannot stay Running.
        var inFlightBody: String? = null
        try {
        while (requests < maxRequestsPerConnection) {
            // An unpaired connection parked between requests holds a worker
            // thread (the pool is sized maxUnpaired + maxPaired). A flood of
            // idle unpaired sockets would otherwise occupy every thread and
            // starve a paired peer, so cap only the idle-unpaired ones. Paired
            // peers are never capped here.
            val idleUnpairedNow = isPaired(fp) == null
            val parked = idleUnpairedNow && acquireConnection(idleUnpaired, maxIdleUnpairedConnections)
            val overCap = idleUnpairedNow && !parked
            if (overCap) {
                // At the cap we never park. A request already on its way (a
                // discovery probe) is still answered, then the connection is
                // closed; a socket that stays silent is dropped after a short
                // grace so it cannot hold a worker thread.
                diag("conn idle-unpaired over cap")
                ssl.soTimeout = minOf(idleTimeoutMillis, idleUnpairedGraceMillis)
            } else {
                ssl.soTimeout = idleTimeoutMillis
            }
            val first = try {
                input.read()
            } catch (_: java.net.SocketTimeoutException) {
                return if (overCap) "idle-unpaired-cap" else "idle" // idle keep-alive expired
            } finally {
                if (parked) idleUnpaired.decrementAndGet()
            }
            if (first < 0) return if (overCap) "idle-unpaired-cap" else "peer-closed"
            requests++
            val started = clock()
            val head = try {
                readHead(ssl, input, first) ?: return "peer-closed"
            } catch (_: java.net.SocketTimeoutException) {
                return "header-timeout"
            } catch (_: PeerHttpException) {
                respond(out, 400, errorJson("bad request"))
                diag("resp 400 malformed-head")
                return "client-error"
            }
            // Authorization is evaluated per request, at the moment it is read,
            // not once per connection: a keep-alive connection opened before
            // this peer paired (or after an unpair) must not carry a stale trust
            // decision. That stale classification made a just-paired desktop's
            // uploads fail on a reused connection (Task 30).
            val peer = isPaired(fp)
            diag("req ${head.method} ${reqLabel(head)}")
            // The connection budget also counts only the request being served,
            // not the idle keep-alive time between requests. Otherwise a peer's
            // ordinary discovery/liveness probes (each a short /hello) pile up
            // as idle unpaired connections and exhaust maxUnpairedConnections,
            // so a phone-to-phone session request is refused with no response.
            val pairedNow = peer != null
            val counter = if (pairedNow) pairedActive else unpairedActive
            val cap = if (pairedNow) maxPairedConnections else maxUnpairedConnections
            if (!acquireConnection(counter, cap)) {
                respond(out, 503, errorJson("busy"), true)
                diag("resp 503 ${head.method} ${reqLabel(head)} (connection budget)")
                return "budget"
            }
            try {
                if (head.path == "/api/v1/shares" || head.path.startsWith("/api/v1/shares/")) {
                    handleShares(out, head, peer, ssl, input, started)
                    if (head.close || requests >= maxRequestsPerConnection || overCap) return "close-requested"
                    continue
                }
                val fileBody = head.method == "PUT" && isFileBodyPath(head.path)
                val closing = head.close || requests >= maxRequestsPerConnection || overCap
                try {
                    if (fileBody) {
                        ssl.soTimeout = stallTimeoutMillis
                        inFlightBody = runCatching { idBetween(head.path, "/api/v1/push/", "/file") }.getOrNull()
                        handleFileBody(out, head, fp, peer, input, closing)
                        inFlightBody = null
                    } else {
                        val bodyCap = bodyCap(head.path)
                        val body = readSmallBody(ssl, input, head, bodyCap, started)
                        val (code, text) = route(head, body, fp, peer, ssl)
                        respond(out, code, text, closing)
                        diag("resp $code ${head.method} ${reqLabel(head)}")
                    }
                } catch (e: PeerHttpException) {
                    // If a file body was being read, the rest of it is still on the
                    // wire, so the connection cannot be reused: close it.
                    val mustClose = closing || fileBody
                    respond(out, e.code, errorJson(e.message), mustClose)
                    diag("resp ${e.code} ${head.method} ${reqLabel(head)} (${e.message})")
                    if (mustClose) return "body-error"
                } catch (e: Exception) {
                    // An unexpected failure while handling the request (for example
                    // the save location could not be written). Never drop the
                    // connection silently: a bare EOF tells the sender nothing. A
                    // file body may be half-read, so that connection cannot be
                    // reused; a small request can.
                    val mustClose = closing || fileBody
                    respond(out, 500, errorJson("could not save the file on this device"), mustClose)
                    diag("resp 500 ${head.method} ${reqLabel(head)} (${e.javaClass.simpleName}: ${e.message?.take(160)})")
                    if (mustClose) return "handler-error"
                }
                if (closing) return "close-requested"
            } finally {
                counter.decrementAndGet()
            }
        }
        return "max-requests"
        } finally {
            inFlightBody?.let { receiver.fail(it, "Connection lost") }
        }
    }

    /** A request label for diagnostics: method path, query dropped, never a value. */
    private fun reqLabel(head: Head): String {
        val base = if (head.headers["content-range"] != null) {
            head.path + "?" + head.query.split('&').joinToString("&") { it.substringBefore('=') }
        } else {
            head.path
        }
        val te = head.headers["transfer-encoding"]
        val cl = head.headers["content-length"]
        val len = when {
            te != null -> " ch=$te"
            cl != null -> " len=$cl"
            else -> ""
        }
        return base + len
    }

    private fun readHead(ssl: SSLSocket, input: InputStream, first: Int): Head? {
        val buf = java.io.ByteArrayOutputStream()
        buf.write(first)
        var a = 0
        var b = 0
        var c = 0
        var d = 0
        while (true) {
            val r = input.read()
            if (r < 0) return null
            buf.write(r)
            a = b; b = c; c = d; d = r
            if (a == 13 && b == 10 && c == 13 && d == 10) break
            if (buf.size() > MAX_HEADER_BYTES) throw PeerHttpException(400, "header too large")
        }
        val lines = String(buf.toByteArray(), Charsets.ISO_8859_1).split("\r\n")
        val parts = lines.firstOrNull()?.trim().orEmpty().split(" ")
        if (parts.size < 2) throw PeerHttpException(400, "bad request line")
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val close = headers["connection"]?.contains("close", ignoreCase = true) == true
        val target = parts[1]
        return Head(parts[0].uppercase(), target.substringBefore('?'), target.substringAfter('?', ""), headers, close)
    }

    private fun readSmallBody(ssl: SSLSocket, input: InputStream, head: Head, cap: Int, started: Long): ByteArray {
        if (head.method == "GET" || head.method == "HEAD") return ByteArray(0)
        val te = head.headers["transfer-encoding"]
        if (te != null && te.contains("chunked", ignoreCase = true)) throw PeerHttpException(400, "chunked encoding is not supported")
        val cl = head.headers["content-length"] ?: throw PeerHttpException(411, "content-length required")
        val n = cl.toLongOrNull() ?: throw PeerHttpException(400, "bad content-length")
        if (n < 0) throw PeerHttpException(400, "bad content-length")
        if (n > cap) throw PeerHttpException(413, "body too large")
        ssl.soTimeout = remainingMillis(started, headerTimeoutMillis)
        return readExactly(input, n.toInt())
    }

    private fun remainingMillis(started: Long, budget: Int): Int {
        val left = budget - (clock() - started).toInt()
        if (left <= 0) throw PeerHttpException(408, "request timeout")
        return left
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val body = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(body, off, n - off)
            if (r < 0) throw PeerHttpException(400, "body shorter than content-length")
            off += r
        }
        return body
    }

    // --- routing ---

    private fun route(head: Head, body: ByteArray, fp: String, peer: PairedPeer?, ssl: SSLSocket): Pair<Int, String> {
        val path = head.path
        return when {
            head.method == "GET" && path == "/api/v1/hello" ->
                200 to (hello?.invoke(port)?.toString() ?: "{}")
            head.method == "POST" && path == "/api/v1/session/request" ->
                200 to handleSessionRequest(body, fp, ssl)
            head.method == "POST" && path == "/api/v1/trust/revoke" ->
                handleTrustRevoke(fp)
            path.startsWith("/api/v1/session/") -> handleSession(head, fp, path)
            head.method == "POST" && path == "/api/v1/push/offer" -> handlePushOffer(body, fp, peer)
            path.startsWith("/api/v1/push/") && path.endsWith("/complete") ->
                handlePushComplete(body, fp, peer, path)
            else -> 404 to errorJson("not found")
        }
    }

    private fun isFileBodyPath(path: String): Boolean =
        path.startsWith("/api/v1/push/") && path.endsWith("/file")

    private fun bodyCap(path: String): Int =
        if (path == "/api/v1/push/offer") PushProtocol.MAX_OFFER_BODY_BYTES else maxBodyBytes

    /** A push endpoint needs a paired fingerprint with the push permission. */
    private fun requirePush(fp: String, peer: PairedPeer?): PairedPeer {
        val p = peer ?: throw PeerHttpException(403, "not paired")
        if (!p.push) throw PeerHttpException(403, "push not permitted")
        return p
    }

    private fun handlePushOffer(body: ByteArray, fp: String, peer: PairedPeer?): Pair<Int, String> {
        val p = requirePush(fp, peer)
        if (wifiOnly() && metered()) throw PeerHttpException(403, "Wi-Fi only is on. Connect to Wi-Fi to receive files.")
        if (!allowSessionRequest()) throw PeerHttpException(429, "too many requests")
        val json = parseObject(body)
        val filesArr = json.getAsJsonArray("files") ?: throw PeerHttpException(400, "no files")
        val reqs = filesArr.map { el ->
            val o = el.asJsonObject
            PushFileRequest(o.str("rel_path"), o.long("size"), parseMtime(o.str("mtime")))
        }
        val total = json.long("total_bytes")
        // Validate here (caps, names, free space) before asking, so a refusal is
        // for a real reason and no spool is created for a rejected offer.
        val probe = receiver.offer(p.fingerprint, p.name, reqs, total, p.pushMaxBytes)
        // ask_over: a person must accept.
        val realTotal = if (total > 0) total else reqs.sumOf { it.size }
        if (p.askOver > 0 && realTotal > p.askOver) {
            val names = reqs.take(5).map { Display.safeName(it.relPath) }
            val outcome = approval?.ask(p.fingerprint, p.name, reqs.size, realTotal, names) ?: ApprovalOutcome.ACCEPTED
            when (outcome) {
                ApprovalOutcome.ACCEPTED -> {}
                ApprovalOutcome.DECLINED, ApprovalOutcome.UNAVAILABLE ->
                    { receiver.cancel(probe.pushId, p.fingerprint); throw PeerHttpException(403, "the transfer was declined") }
                ApprovalOutcome.BUSY ->
                    { receiver.cancel(probe.pushId, p.fingerprint); throw PeerHttpException(429, "another request is waiting") }
            }
        }
        return 200 to offerJson(probe)
    }

    private fun handlePushComplete(body: ByteArray, fp: String, peer: PairedPeer?, path: String): Pair<Int, String> {
        requirePush(fp, peer)
        val id = idBetween(path, "/api/v1/push/", "/complete")
        val json = parseObject(body)
        if (json.get("all")?.takeIf { !it.isJsonNull }?.asBoolean == true) {
            receiver.finish(id, fp)
            return 200 to """{"done":true}"""
        }
        val rel = json.str("rel_path")
        val sha = json.str("sha256")
        val st = receiver.complete(id, fp, rel, sha)
        return 200 to """{"rel_path":${jsonStr(st.relPath)},"done":true}"""
    }

    private fun handleFileBody(
        out: OutputStream,
        head: Head,
        fp: String,
        peer: PairedPeer?,
        input: InputStream,
        closing: Boolean,
    ) {
        requirePush(fp, peer)
        val id = idBetween(head.path, "/api/v1/push/", "/file")
        val rel = queryParam(head.query, "path") ?: throw PeerHttpException(400, "path required")
        val te = head.headers["transfer-encoding"]
        val chunked = te != null && te.contains("chunked", ignoreCase = true)
        // The Go client streams large files with chunked encoding (the body
        // length is not known up front); the small-file fast path carries
        // Content-Length. Both are accepted; our own framing stays HTTP/1.1.
        var limited: LimitedInputStream? = null
        val body: InputStream = if (chunked) {
            ChunkedInputStream(input)
        } else {
            val cl = head.headers["content-length"] ?: throw PeerHttpException(411, "content-length required")
            val n = cl.toLongOrNull() ?: throw PeerHttpException(400, "bad content-length")
            if (n < 0) throw PeerHttpException(400, "bad content-length")
            LimitedInputStream(input, n).also { limited = it }
        }
        val sha = head.headers["x-lanyard-sha256"]
        val offset = parseContentRange(head.headers["content-range"])
        val whole = !sha.isNullOrEmpty() && offset == 0L
        // Any exception out of the body write (a stream that throws, a checksum
        // or size mismatch, or a save that fails) fails the receive session so
        // its row cannot sit Running forever. fail() is once-only.
        val written = try {
            val w = if (whole) {
                receiver.receiveWhole(id, fp, rel, sha, body)
            } else {
                receiver.writeChunk(id, fp, rel, offset, body)
            }
            // A Content-Length body that stopped before its declared length means
            // the connection closed mid-file. Chunked bodies throw on truncation
            // inside ChunkedInputStream; this is the Content-Length equivalent.
            val lim = limited
            if (lim != null && lim.remaining > 0) {
                throw PeerHttpException(400, "body shorter than content-length")
            }
            w
        } catch (e: Exception) {
            receiver.fail(id, bodyFailureReason(e))
            throw e
        }
        if (whole) {
            respond(out, 200, """{"written":$written,"offset":$written,"done":true}""", closing)
            diag("resp 200 ${head.method} ${reqLabel(head)}")
        } else {
            respond(out, 200, """{"written":$written,"offset":${offset + written}}""", closing)
            diag("resp 200 ${head.method} ${reqLabel(head)} (wrote $written)")
        }
    }

    /** A person-readable reason for a file body that ended abnormally. */
    private fun bodyFailureReason(e: Exception): String = when {
        e is java.io.IOException -> "Connection lost"
        e is PushCancelledException -> "The transfer was cancelled"
        !e.message.isNullOrBlank() ->
            if (e.message!!.contains("shorter", ignoreCase = true)) "Connection lost" else e.message!!
        else -> "Connection lost"
    }

    private fun handleSessionRequest(body: ByteArray, fp: String, socket: SSLSocket): String {
        val short = fp.take(8)
        if (!allowSessionRequest()) {
            diag("session refused peer=$short rate limited")
            throw PeerHttpException(429, "too many requests")
        }
        val json = parseObject(body)
        val claimed = json.str("fingerprint")
        if (claimed.isNotEmpty() && !claimed.equals(fp, ignoreCase = true)) {
            diag("session refused peer=$short fingerprint mismatch")
            throw PeerHttpException(400, "fingerprint does not match the certificate")
        }
        val claimedId = json.str("device_id")
        if (claimedId.length == 64 && claimedId.all { it.isHex() } && !claimedId.equals(fp, ignoreCase = true)) {
            diag("session refused peer=$short device id mismatch")
            throw PeerHttpException(400, "device id does not match the certificate")
        }
        val mode = json.str("mode")
        val viaQr = json.str("invite").isNotEmpty()
        diag("session request peer=$short ${if (viaQr) "qr" else if (mode == "pair") "pair" else "connect"}")
        val view = try {
            sessions.createIncoming(
                mode = mode,
                peerFp = fp,
                peerName = json.str("name"),
                peerDevice = json.str("device_id"),
                peerHost = socket.inetAddress?.hostAddress ?: "",
                peerNonce = json.str("nonce"),
                requested = json.permissions("requested_permissions"),
                consumeInvite = if (json.str("invite").isEmpty()) null else ({ invites.consume(json.str("invite")) }),
            )
        } catch (e: PeerHttpException) {
            diag("session refused peer=$short ${e.message ?: "refused"}")
            throw e
        }
        diag("session ok peer=$short ${if (viaQr) "qr" else "sas"} $mode")
        return """{"session_id":${jsonStr(view.id)},"nonce":${jsonStr(view.nonce)},"status":${jsonStr(view.status)}}"""
    }

    private fun handleSession(head: Head, fp: String, path: String): Pair<Int, String> {
        val rest = path.removePrefix("/api/v1/session/")
        return when {
            head.method == "GET" && '/' !in rest -> {
                val v = sessions.statusFor(rest, fp)
                200 to statusJson(v)
            }
            head.method == "POST" && rest.endsWith("/confirm") -> {
                val v = sessions.confirm(rest.removeSuffix("/confirm"), fp)
                200 to """{"status":${jsonStr(v.status)}}"""
            }
            head.method == "POST" && rest.endsWith("/close") -> {
                sessions.close(rest.removeSuffix("/close"), fp)
                200 to """{"closed":true}"""
            }
            else -> 404 to errorJson("not found")
        }
    }

    private fun handleTrustRevoke(fp: String): Pair<Int, String> {
        if (isPaired(fp) == null) throw PeerHttpException(403, "not paired")
        onUnpair(fp) // only ever the caller's own entry
        return 200 to """{"ok":true}"""
    }

    private fun allowSessionRequest(): Boolean {
        synchronized(requestTimes) {
            val now = clock()
            while (requestTimes.isNotEmpty() && now - requestTimes.first() > 60_000) requestTimes.removeFirst()
            if (requestTimes.size >= maxSessionRequestsPerMinute) return false
            requestTimes.addLast(now)
            return true
        }
    }

    // --- helpers ---

    private class LimitedInputStream(private val src: InputStream, private var left: Long) : InputStream() {
        /** Bytes the declared Content-Length still promises but has not delivered. */
        val remaining: Long get() = left

        override fun read(): Int {
            if (left <= 0) return -1
            val b = src.read()
            if (b >= 0) left--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val want = minOf(len.toLong(), left).toInt()
            val n = src.read(b, off, want)
            if (n > 0) left -= n
            return n
        }
    }

    /**
     * Decodes HTTP/1.1 chunked transfer coding from [src]. Bounded so a hostile
     * client cannot grow memory: a chunk-size line is capped, the trailer block
     * is capped, malformed input is a 400, and the CRLF after each chunk's data
     * is validated.
     */
    private class ChunkedInputStream(private val src: InputStream) : InputStream() {
        private var remaining = 0L
        private var done = false
        private var trailerLines = 0
        private var trailerBytes = 0

        override fun read(): Int {
            if (done) return -1
            if (remaining == 0L) nextChunk()
            if (done) return -1
            val b = src.read()
            if (b < 0) throw PeerHttpException(400, "truncated chunked body")
            remaining--
            if (remaining == 0L) endChunkCrc()
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (done) return -1
            if (remaining == 0L) nextChunk()
            if (done) return -1
            val n = src.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n < 0) throw PeerHttpException(400, "truncated chunked body")
            remaining -= n
            if (remaining == 0L) endChunkCrc()
            return n
        }

        private fun nextChunk() {
            val line = readLine(MAX_CHUNK_LINE)
            val sizeText = line.substringBefore(';').trim()
            if (sizeText.isEmpty()) throw PeerHttpException(400, "malformed chunk size")
            val size = sizeText.toLongOrNull(16) ?: throw PeerHttpException(400, "malformed chunk size")
            if (size < 0) throw PeerHttpException(400, "malformed chunk size")
            if (size == 0L) {
                while (true) {
                    val t = readLine(MAX_TRAILER_LINE)
                    trailerLines++
                    trailerBytes += t.length + 2
                    if (trailerLines > MAX_TRAILER_LINES || trailerBytes > MAX_TRAILER_BYTES) {
                        throw PeerHttpException(400, "trailer too large")
                    }
                    if (t.isEmpty()) break
                }
                done = true
                return
            }
            remaining = size
        }

        private fun endChunkCrc() {
            val cr = src.read()
            val lf = src.read()
            if (cr != 13 || lf != 10) throw PeerHttpException(400, "malformed chunk terminator")
        }

        private fun readLine(max: Int): String {
            val sb = StringBuilder()
            while (true) {
                val b = src.read()
                if (b < 0) throw PeerHttpException(400, "truncated chunked body")
                val c = b.toChar()
                if (c == '\n') return sb.toString()
                if (c != '\r') sb.append(c)
                if (sb.length > max) throw PeerHttpException(400, "chunk line too long")
            }
        }

        private companion object {
            const val MAX_CHUNK_LINE = 256
            const val MAX_TRAILER_LINE = 1024
            const val MAX_TRAILER_LINES = 32
            const val MAX_TRAILER_BYTES = 8 * 1024
        }
    }


    private class StallGuard(socket: Socket, timeoutMs: Long, sched: java.util.concurrent.ScheduledExecutorService?) {
        private val last = AtomicLong(System.currentTimeMillis())
        @Volatile private var stopped = false
        private val task = sched?.scheduleWithFixedDelay({
            if (!stopped && System.currentTimeMillis() - last.get() > timeoutMs) {
                runCatching { socket.setSoLinger(true, 0) } // RST so a blocked send fails at once
                runCatching { socket.shutdownOutput() }
                runCatching { socket.close() }
            }
        }, timeoutMs, maxOf(timeoutMs / 2, 500), TimeUnit.MILLISECONDS)

        fun kick() { last.set(System.currentTimeMillis()) }
        fun stop() { stopped = true; task?.cancel(false) }
    }

    private fun handleShares(out: OutputStream, head: Head, peer: PairedPeer?, ssl: SSLSocket, input: InputStream, started: Long) {
        if (shares == null) { respond(out, 404, errorJson("not found")); diag("resp 404 shares unavailable"); return }
        if (peer == null) { respond(out, 403, errorJson("not paired")); diag("resp 403 share not paired"); return }
        if (!peer.browse) { respond(out, 403, errorJson("pull not permitted")); diag("resp 403 share no-browse"); return }
        val body = if (head.method == "POST") readSmallBody(ssl, input, head, maxBodyBytes, started) else ByteArray(0)
        val isFileGet = (head.method == "GET" || head.method == "HEAD") && head.path.endsWith("/file")
        val guard = if (isFileGet) StallGuard(ssl, stallTimeoutMillis.toLong(), stallScheduler) else null
        try {
            shares.handle(
                out, head.method, head.path, parseQuery(head.query),
                head.headers["range"], head.headers["if-range"], body, peer,
            ) { guard?.kick() }
        } catch (e: PeerHttpException) {
            respond(out, e.code, errorJson(e.message))
            diag("resp ${e.code} ${head.method} ${reqLabel(head)} (${e.message})")
        } catch (e: Exception) {
            respond(out, 500, errorJson("could not read the shared file"))
            diag("resp 500 ${head.method} ${reqLabel(head)} (${e.javaClass.simpleName})")
        } finally {
            guard?.stop()
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (part in query.split('&')) {
            if (part.isEmpty()) continue
            val i = part.indexOf('=')
            val k = if (i < 0) part else part.substring(0, i)
            val v = if (i < 0) "" else part.substring(i + 1)
            out[k] = try { java.net.URLDecoder.decode(v, Charsets.UTF_8) } catch (_: Exception) { v }
        }
        return out
    }

    private fun peerFingerprint(ssl: SSLSocket): String? {
        val leaf = ssl.session.peerCertificates.firstOrNull() as? X509Certificate ?: return null
        return Identity.fingerprintOf(leaf)
    }

    private fun parseObject(body: ByteArray): JsonObject = try {
        JsonParser.parseString(String(body, Charsets.UTF_8)).asJsonObject
    } catch (_: Exception) {
        throw PeerHttpException(400, "bad json body")
    }

    private fun statusJson(v: SessionView): String = buildString {
        append("""{"status":""").append(jsonStr(v.status))
        append(""","mode":""").append(jsonStr(v.mode))
        append(""","nonce":""").append(jsonStr(v.nonce))
        append(""","sas":""").append(jsonStr(v.sas))
        append(""","granted":""").append(permissionsJson(v.granted))
        append('}')
    }

    private fun offerJson(o: PushOffer): String = buildString {
        append("""{"push_id":""").append(jsonStr(o.pushId))
        append(""","accepted":true,"max_bytes":""").append(o.maxBytes)
        append(""","files":[""")
        o.offsets.entries.forEachIndexed { i, e ->
            if (i > 0) append(',')
            append("""{"rel_path":""").append(jsonStr(e.key)).append(""","offset":""").append(e.value).append('}')
        }
        append("]}")
    }

    private fun permissionsJson(p: Permissions): String = buildString {
        append("""{"browse":""").append(p.browse)
        append(""","push":""").append(p.push)
        if (p.pushMaxBytes > 0) append(""","push_max_bytes":""").append(p.pushMaxBytes)
        if (p.askOver > 0) append(""","ask_over":""").append(p.askOver)
        append('}')
    }

    private fun JsonObject.permissions(key: String): Permissions {
        val o = get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: return Permissions()
        fun b(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asBoolean ?: false
        fun l(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asLong ?: 0L
        return Permissions(browse = b("browse"), push = b("push"), pushMaxBytes = l("push_max_bytes"), askOver = l("ask_over"))
    }

    private fun JsonObject.str(key: String): String = get(key)?.takeIf { !it.isJsonNull }?.asString ?: ""

    private fun JsonObject.long(key: String): Long = get(key)?.takeIf { !it.isJsonNull }?.asLong ?: 0L

    private fun Char.isHex(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun parseMtime(s: String): Long = try {
        if (s.isEmpty()) 0L else java.time.Instant.parse(s).toEpochMilli()
    } catch (_: Exception) {
        0L
    }

    private fun idBetween(path: String, prefix: String, suffix: String): String {
        if (!path.startsWith(prefix) || !path.endsWith(suffix)) throw PeerHttpException(404, "not found")
        return path.removePrefix(prefix).removeSuffix(suffix)
    }

    private fun queryParam(query: String, name: String): String? {
        if (query.isEmpty()) return null
        val raw = query.split('&').map { it.split('=', limit = 2) }
            .firstOrNull { it[0] == name }?.getOrNull(1) ?: return null
        return try {
            java.net.URLDecoder.decode(raw, Charsets.UTF_8)
        } catch (_: Exception) {
            raw
        }
    }

    /** START from "bytes START-END/TOTAL"; 0 when absent. */
    private fun parseContentRange(h: String?): Long {
        if (h.isNullOrBlank()) return 0L
        val hh = h.trim()
        if (!hh.startsWith("bytes ")) throw PeerHttpException(400, "bad Content-Range")
        val range = hh.removePrefix("bytes ").substringBefore('/')
        val dash = range.indexOf('-')
        if (dash <= 0) throw PeerHttpException(400, "bad Content-Range")
        return range.substring(0, dash).trim().toLongOrNull()?.takeIf { it >= 0 }
            ?: throw PeerHttpException(400, "bad Content-Range")
    }

    private fun errorJson(message: String?): String = """{"error":${jsonStr(message ?: "error")}}"""

    private fun jsonStr(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun respond(out: OutputStream, code: Int, body: String, close: Boolean = false) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code ${reason(code)}\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: " + (if (close) "close" else "keep-alive") + "\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        408 -> "Request Timeout"
        409 -> "Conflict"
        411 -> "Length Required"
        413 -> "Payload Too Large"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        507 -> "Insufficient Storage"
        else -> "Error"
    }

    companion object {
        const val MAX_HEADER_BYTES = 16 * 1024

        /**
         * Most unpaired connections that may sit idle between requests. Beyond
         * this they are closed, so discovery/liveness floods cannot fill the
         * worker pool and starve a paired peer.
         */
        const val MAX_IDLE_UNPAIRED_CONNECTIONS = 16
    }
}
