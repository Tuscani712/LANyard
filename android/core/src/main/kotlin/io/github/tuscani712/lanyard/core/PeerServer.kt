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
 * Asks the phone's person, once per browse session, whether a peer may browse
 * the shared folders. The app reuses the push-approval prompt. Must answer
 * quickly; [ApprovalOutcome.UNAVAILABLE]/[ApprovalOutcome.BUSY] refuse at once,
 * and the app's own wait timeout answers [ApprovalOutcome.DECLINED], so a
 * browse that is never answered is denied rather than left hanging.
 */
fun interface BrowseApproval {
    fun ask(peerFp: String, peerName: String): ApprovalOutcome
}

/**
 * The phone-side peer API over mutual TLS 1.3: discovery, pairing and
 * receiving pushes.
 *
 * Hardening:
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
    // The browse-session approval. Null (core tests) means an Ask browses
    // cannot be confirmed, so it is refused.
    private val browseApproval: BrowseApproval? = null,
    // How long one accepted browse approval is remembered for subsequent share
    // requests from the same peer — one "browse session".
    private val browseSessionTtlMillis: Long = BROWSE_SESSION_TTL_MS,
    private val onUnpair: (String) -> Unit = {},
    // A verified peer finished the TLS handshake with us: it is reachable right
    // now. The app uses this to retry a pending-unpair notification promptly.
    private val onPeerReachable: (String) -> Unit = {},
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
    // The offer JSON body cap. Kept as a field (not the constant directly) so a
    // test can lower it to exercise the 413 path without an 8 MB body. The
    // advertised `/hello` value stays [PushProtocol.MAX_OFFER_BODY_BYTES].
    private val maxOfferBodyBytes: Int = PushProtocol.MAX_OFFER_BODY_BYTES,
    // Received text snippets. When null the snippet route answers 503, since
    // there is nowhere for the text to land.
    private val snippets: ReceivedSnippets? = null,
    private val onSnippetsChanged: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val diagnostics: ServerDiagnostics? = null,
    // Best-effort name of the process holding the configured port, for the
    // "held by …" part of the temporary-port banner. Null when undeterminable.
    private val portHolder: (Int) -> String? = PortHolder::find,
) {
    private fun diag(event: String) = diagnostics?.record(event)

    private var server: SSLServerSocket? = null
    private var pool: ThreadPoolExecutor? = null
    private val live: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    private val requestTimes = ArrayDeque<Long>()
    // fingerprint -> epoch millis until which a browse approval is remembered.
    private val browseGrants = ConcurrentHashMap<String, Long>()
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

    /**
     * True when a configured port was tried and could not be bound, so this run
     * fell back to an ephemeral one. Never persisted (see [PeerPort.toPersist]).
     */
    @Volatile
    var temporaryPort: Boolean = false
        private set

    /** Why this run is on a temporary port, or null when it is not. */
    @Volatile
    var portConflict: PortConflict? = null
        private set

    fun activeConnections(): Int = pool?.activeCount ?: 0

    /** Unpaired connections currently parked waiting for their next request. */
    fun idleUnpairedConnections(): Int = idleUnpaired.get()

    fun start(
        identity: Identity,
        preferredPort: Int = 0,
        bindRetryMillis: Long = DEFAULT_BIND_RETRY_MS,
        hello: (Int) -> JsonObject,
    ): Int {
        stop()
        this.hello = hello
        val ctx = Tls.serverContext(identity)
        val bound = bindServerSocket(ctx, preferredPort, bindRetryMillis)
        val ss = bound.socket
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
        temporaryPort = bound.temporary
        portConflict = bound.conflict
        Thread({ acceptLoop(ss, executor) }, "lanyard-peer-accept").apply { isDaemon = true }.start()
        return port
    }

    private class Bound(
        val socket: SSLServerSocket,
        val temporary: Boolean,
        val conflict: PortConflict?,
    )

    /**
     * Binds [preferredPort] when it is a usable port and still free, else an
     * ephemeral one. A stable port means a desktop's stored address stays valid
     * across phone launches; an ephemeral fallback keeps the app starting when
     * the port is taken.
     *
     * A busy configured port is retried for up to [retryMillis] (~5 s in
     * production) in case whoever held it just released it, then it falls back
     * to an ephemeral port for this run only, and reports the conflict.
     */
    private fun bindServerSocket(
        ctx: javax.net.ssl.SSLContext,
        preferredPort: Int,
        retryMillis: Long,
    ): Bound {
        val factory = ctx.serverSocketFactory
        if (preferredPort in 1..65535) {
            val deadline = System.currentTimeMillis() + retryMillis.coerceAtLeast(0)
            while (true) {
                try {
                    return Bound(factory.createServerSocket(preferredPort) as SSLServerSocket, false, null)
                } catch (_: Exception) {
                    if (System.currentTimeMillis() >= deadline) break
                    try {
                        Thread.sleep(BIND_RETRY_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
            val holder = runCatching { portHolder(preferredPort) }.getOrNull()
            return Bound(
                factory.createServerSocket(0) as SSLServerSocket,
                temporary = true,
                conflict = PortConflict(preferredPort, holder),
            )
        }
        return Bound(factory.createServerSocket(0) as SSLServerSocket, false, null)
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
        temporaryPort = false
        portConflict = null
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
        var peerShort = "?"
        diag("[conn] conn open")
        try {
            ssl.startHandshake()
            handshakes.incrementAndGet()
            val fp = peerFingerprint(ssl) ?: run { reason = "no-cert"; return }
            peerShort = fp.take(8)
            diag("[conn] handshake ok peer=$peerShort paired=${if (isPaired(fp) != null) "yes" else "no"}")
            // An inbound handshake means this peer is reachable; a pending
            // unpair for it can be delivered now. Notify off this thread so a
            // slow revoke can never stall the request loop.
            runCatching { onPeerReachable(fp) }
            reason = requestLoop(ssl, fp)
        } catch (e: Exception) {
            // handshake/read failure or a timeout: classify for the report.
            reason = classifyError(e)
        } finally {
            diag("[conn] conn close peer=$peerShort reason=${closeReason(reason)}")
            live.remove(socket)
            runCatching { socket.close() }
        }
    }

    /** A category for an exception that ended a connection. */
    private fun classifyError(e: Exception): String = when {
        e is java.net.SocketTimeoutException -> "stall"
        e is java.net.SocketException -> "client-reset"
        e is java.io.IOException -> "client-reset"
        else -> "error:" + e.javaClass.simpleName
    }

    /**
     * The top stack frame of [e], so a logged failure names the exact line, not
     * only the message. The frame is a class/method/file:line triple; no file
     * contents or user paths are involved.
     */
    /** A person-readable reason for the diagnostics report. */
    private fun closeReason(reason: String): String = when {
        reason == "idle" || reason == "idle-unpaired-cap" -> "idle timeout"
        reason == "budget" -> "over budget"
        reason == "max-requests" -> "max requests per connection"
        reason == "handler-error" -> "handler error"
        reason == "stall" -> "stall"
        reason == "client-reset" -> "client reset"
        reason == "peer-closed" || reason == "close-requested" -> "client closed"
        reason == "header-timeout" -> "header timeout"
        reason == "body-error" -> "body error"
        reason == "client-error" -> "client error"
        reason == "no-cert" -> "no client certificate"
        reason.startsWith("error:") -> "error (" + reason.removePrefix("error:") + ")"
        else -> reason
    }

    /**
     * One diagnostics line per completed request: method, redacted path, the
     * peer's short fingerprint, the response status and how long it took. The
     * path keeps opaque ids but never a file name or query value (see
     * [reqLabel]).
     */
    private fun logResp(code: Int, head: Head, fp: String, started: Long, note: String = "") {
        val suffix = if (note.isEmpty()) "" else " ($note)"
        diag("${area(head.path)} resp $code ${head.method} ${reqLabel(head)} peer=${fp.take(8)} elapsed=${clock() - started}ms$suffix")
    }

    /** The diagnostics area tag for a request path. */
    private fun area(path: String): String = when {
        path.startsWith("/api/v1/session") || path.startsWith("/api/v1/trust") -> "[pairing]"
        path.startsWith("/api/v1/push") -> "[push]"
        path.startsWith("/api/v1/snippet") -> "[push]"
        path.startsWith("/api/v1/shares") -> "[pull]"
        path.startsWith("/api/v1/hello") -> "[discovery]"
        else -> "[conn]"
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
                diag("[conn] conn idle-unpaired over cap")
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
                diag("[conn] resp 400 malformed-head peer=${fp.take(8)}")
                return "client-error"
            }
            // Authorization is evaluated per request, at the moment it is read,
            // not once per connection: a keep-alive connection opened before
            // this peer paired (or after an unpair) must not carry a stale trust
            // decision, which would make a just-paired desktop's uploads fail on
            // a reused connection.
            val peer = isPaired(fp)
            diag("${area(head.path)} req ${head.method} ${reqLabel(head)} peer=${fp.take(8)}")
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
                logResp(503, head, fp, started, "connection budget")
                return "budget"
            }
            // Why this request will end the connection, if it will. Kept
            // distinct so the diagnostics report names the real reason rather
            // than a generic "close-requested".
            val closeWhy = when {
                head.close -> "close-requested"
                requests >= maxRequestsPerConnection -> "max-requests"
                overCap -> "idle-unpaired-cap"
                else -> null
            }
            val closing = closeWhy != null
            try {
                if (head.path == "/api/v1/shares" || head.path.startsWith("/api/v1/shares/")) {
                    val closeAfter = handleShares(out, head, peer, ssl, input, started)
                    if (closing || closeAfter) return closeWhy ?: "body-error"
                    continue
                }
                val fileBody = head.method == "PUT" && isFileBodyPath(head.path)
                try {
                    if (fileBody) {
                        ssl.soTimeout = stallTimeoutMillis
                        inFlightBody = runCatching { idBetween(head.path, "/api/v1/push/", "/file") }.getOrNull()
                        handleFileBody(out, head, fp, peer, input, closing, started)
                        inFlightBody = null
                    } else {
                        val bodyCap = bodyCap(head.path)
                        val body = readSmallBody(ssl, input, head, bodyCap, started)
                        val (code, text) = route(head, body, fp, peer, ssl)
                        respond(out, code, text, closing)
                        logResp(code, head, fp, started)
                    }
                } catch (e: PeerHttpException) {
                    // If a file body was being read, the rest of it is still on the
                    // wire, so the connection cannot be reused: close it. An
                    // oversized small body (413) sets closeConnection too, so its
                    // unread body is never misparsed as the next request.
                    val mustClose = closing || fileBody || e.closeConnection
                    respond(out, e.code, errorJson(e.message), mustClose)
                    logResp(e.code, head, fp, started, e.message ?: "")
                    if (mustClose) return "body-error"
                } catch (_: java.net.SocketTimeoutException) {
                    // A body made no progress within the stall timeout: the
                    // connection is dead. Report it as a stall, not a handler bug.
                    respond(out, 408, errorJson("the connection stalled"), true)
                    logResp(408, head, fp, started, "stall")
                    return "stall"
                } catch (_: java.net.SocketException) {
                    // The client reset the connection mid-request.
                    respond(out, 400, errorJson("connection reset"), true)
                    logResp(400, head, fp, started, "client reset")
                    return "client-reset"
                } catch (e: Exception) {
                    // An unexpected failure while handling the request (for example
                    // the save location could not be written). Never drop the
                    // connection silently: a bare EOF tells the sender nothing. A
                    // file body may be half-read, so that connection cannot be
                    // reused; a small request can.
                    val mustClose = closing || fileBody
                    respond(out, 500, errorJson("could not save the file on this device"), mustClose)
                    logResp(500, head, fp, started, "${e.javaClass.simpleName}: ${e.message?.take(160)} at ${topFrame(e)}")
                    if (mustClose) return "handler-error"
                }
                if (closing) return closeWhy!!
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
        if (n > cap) {
            // Refuse without reading: the body is still on the wire, so this
            // connection must not be reused. Closing after the 413 keeps an
            // oversized offer from being misparsed as the next request (the
            // spurious "malformed-head" bug behind issue #4).
            throw PeerHttpException(413, "body too large", closeConnection = true)
        }
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
            head.method == "POST" && path == "/api/v1/snippet" -> handleSnippet(body, fp, peer)
            path.startsWith("/api/v1/push/") && path.endsWith("/complete") ->
                handlePushComplete(body, fp, peer, path)
            path.startsWith("/api/v1/push/") && path.endsWith("/cancel") ->
                handlePushCancel(fp, peer, path)
            else -> 404 to errorJson("not found")
        }
    }

    private fun isFileBodyPath(path: String): Boolean =
        path.startsWith("/api/v1/push/") && path.endsWith("/file")

    private fun bodyCap(path: String): Int = when (path) {
        "/api/v1/push/offer" -> maxOfferBodyBytes
        // JSON escaping can inflate a text snippet; the desktop allows the same
        // `*2 + 4096` headroom, then validates the decoded size.
        "/api/v1/snippet" -> SnippetProtocol.MAX_BODY_BYTES
        else -> maxBodyBytes
    }

    /**
     * A push endpoint needs a paired fingerprint whose push permission is not
     * Never. Ask is allowed through here: it is enforced with a prompt at the
     * offer (see [handlePushOffer]); the later file/complete steps belong to a
     * push that was already accepted.
     */
    private fun requirePush(fp: String, peer: PairedPeer?): PairedPeer {
        val p = peer ?: throw PeerHttpException(403, "not paired")
        if (p.push == Permission.NEVER) throw PeerHttpException(403, "push not permitted")
        return p
    }

    /**
     * Whether [peer] may browse (pull) the shares right now. Allow passes;
     * Never is refused; Ask prompts the phone's person once and remembers the
     * answer for a browse session ([browseSessionTtlMillis]). A prompt that is
     * never answered (the app times out) comes back as a refusal, so a browse
     * that is not confirmed is denied — never left running.
     */
    private fun browseDenial(peer: PairedPeer): String? {
        when (peer.browse) {
            Permission.ALLOW -> return null
            Permission.NEVER -> return "pull not permitted"
            Permission.ASK -> {}
        }
        val now = clock()
        browseGrants[peer.fingerprint]?.let { if (now < it) return null }
        val outcome = browseApproval?.ask(peer.fingerprint, peer.name) ?: ApprovalOutcome.UNAVAILABLE
        return when (outcome) {
            ApprovalOutcome.ACCEPTED -> {
                browseGrants[peer.fingerprint] = now + browseSessionTtlMillis
                null
            }
            // A person's decline (or the app's timeout, which is a decline) has
            // its own wording, distinct from a Never permission.
            ApprovalOutcome.DECLINED -> "denied by the user"
            else -> "pull not permitted"
        }
    }

    /**
     * The text snippet gate: a paired peer whose text permission is not
     * Never. An Ask is allowed through here and enforced with the push-approval
     * prompt below. The desktop refuses an unpaired caller with `not permitted`
     * and a paired caller without the permission with `text not permitted`;
     * both contain "not permitted", which is what [PeerErrors] surfaces.
     */
    private fun requireSnippet(peer: PairedPeer?): PairedPeer {
        val p = peer ?: throw PeerHttpException(403, "not permitted")
        if (p.text == Permission.NEVER) throw PeerHttpException(403, "text not permitted")
        return p
    }

    private fun handleSnippet(body: ByteArray, fp: String, peer: PairedPeer?): Pair<Int, String> {
        val p = requireSnippet(peer)
        val store = snippets ?: throw PeerHttpException(503, "inbox unavailable")
        val json = parseObject(body)
        val text = json.str("text")
        SnippetProtocol.validate(text)?.let { throw PeerHttpException(400, it) }
        // Text reuses the existing push-approval prompt/notification.
        if (p.text == Permission.ASK) {
            val bytes = text.toByteArray(Charsets.UTF_8).size.toLong()
            val outcome = approval?.ask(p.fingerprint, p.name, 1, bytes, emptyList())
                ?: ApprovalOutcome.UNAVAILABLE
            when (outcome) {
                ApprovalOutcome.ACCEPTED -> {}
                ApprovalOutcome.BUSY -> throw PeerHttpException(429, "another request is waiting")
                else -> throw PeerHttpException(403, "the message was declined")
            }
        }
        val snippet = store.add(p.fingerprint, text)
        runCatching { onSnippetsChanged() }
        diag("[push] snippet received peer=${fp.take(8)} bytes=${text.toByteArray(Charsets.UTF_8).size}")
        // Matches the desktop's response shape: {"id":"s_..."}.
        return 200 to """{"id":${jsonStr(snippet.id)}}"""
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
        // Ask permission or ask_over (size threshold): a person must accept.
        val realTotal = if (total > 0) total else reqs.sumOf { it.size }
        if (p.push == Permission.ASK || (p.askOver > 0 && realTotal > p.askOver)) {
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

    /**
     * A sender ended an outgoing push and asks us to free its spool. Paired peer
     * with push permission, and only the peer that owns the push may cancel it.
     *
     * Idempotent and harmless: a known id the caller owns ends the push as
     * Cancelled with the sender's reason ("Cancelled by the sender"), leaving
     * Finishing/Receiving at once. A repeat request, an id we never saw, or one
     * already finished is answered `200` so an older/newer sender can retry
     * without error; an id owned by a *different* peer is refused `403`.
     */
    private fun handlePushCancel(fp: String, peer: PairedPeer?, path: String): Pair<Int, String> {
        requirePush(fp, peer)
        val id = idBetween(path, "/api/v1/push/", "/cancel")
        val owner = receiver.ownerFingerprint(id)
        if (owner != null && !owner.equals(fp, ignoreCase = true)) {
            diag("[push] peer=${fp.take(8)} cancel refused owner-mismatch id=$id")
            throw PeerHttpException(403, "not your push")
        }
        receiver.cancelBySender(id, fp)
        return 200 to """{"cancelled":true}"""
    }

    private fun handleFileBody(
        out: OutputStream,
        head: Head,
        fp: String,
        peer: PairedPeer?,
        input: InputStream,
        closing: Boolean,
        started: Long,
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
            receiver.fail(id, transferFailureReason(e))
            // A body that aborts because the receiving person cancelled the push
            // must answer 410 Gone ("cancelled by the receiver"), not a 500: the
            // sender ends its row as Cancelled with that reason. The connection
            // is closed either way, so the rest of the push is torn down.
            if (receiver.wasCancelled(id)) throw PeerHttpException(410, RECEIVER_CANCELLED_BODY)
            throw e
        }
        if (whole) {
            respond(out, 200, """{"written":$written,"offset":$written,"done":true}""", closing)
            logResp(200, head, fp, started)
        } else {
            respond(out, 200, """{"written":$written,"offset":${offset + written}}""", closing)
            logResp(200, head, fp, started, "wrote $written")
        }
    }

    /** A person-readable reason for a file body that ended abnormally. */
    private fun handleSessionRequest(body: ByteArray, fp: String, socket: SSLSocket): String {
        val short = fp.take(8)
        if (!allowSessionRequest()) {
            diag("[pairing] session refused peer=$short rate limited")
            throw PeerHttpException(429, "too many requests")
        }
        val json = parseObject(body)
        val claimed = json.str("fingerprint")
        if (claimed.isNotEmpty() && !claimed.equals(fp, ignoreCase = true)) {
            diag("[pairing] session refused peer=$short fingerprint mismatch")
            throw PeerHttpException(400, "fingerprint does not match the certificate")
        }
        val claimedId = json.str("device_id")
        if (claimedId.length == 64 && claimedId.all { it.isHex() } && !claimedId.equals(fp, ignoreCase = true)) {
            diag("[pairing] session refused peer=$short device id mismatch")
            throw PeerHttpException(400, "device id does not match the certificate")
        }
        val mode = json.str("mode")
        val viaQr = json.str("invite").isNotEmpty()
        diag("[pairing] session request peer=$short ${if (viaQr) "qr" else if (mode == "pair") "pair" else "connect"}")
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
            diag("[pairing] session refused peer=$short ${e.message ?: "refused"}")
            throw e
        }
        diag("[pairing] session ok peer=$short ${if (viaQr) "qr" else "sas"} $mode")
        return """{"session_id":${jsonStr(view.id)},"nonce":${jsonStr(view.nonce)},"status":${jsonStr(view.status)}}"""
    }

    private fun handleSession(head: Head, fp: String, path: String): Pair<Int, String> {
        val rest = path.removePrefix("/api/v1/session/")
        val short = fp.take(8)
        return when {
            head.method == "GET" && '/' !in rest -> {
                val v = sessions.statusFor(rest, fp)
                if (v.status == "accepted" || v.status == "rejected" || v.status == "expired" || v.status == "active") {
                    diag("[pairing] peer=$short status id=$rest status=${v.status}")
                }
                200 to statusJson(v)
            }
            head.method == "POST" && rest.endsWith("/confirm") -> {
                val id = rest.removeSuffix("/confirm")
                val v = sessions.confirm(id, fp)
                diag("[pairing] peer=$short confirm id=$id status=${v.status}")
                200 to """{"status":${jsonStr(v.status)}}"""
            }
            head.method == "POST" && rest.endsWith("/close") -> {
                val id = rest.removeSuffix("/close")
                sessions.close(id, fp)
                diag("[pairing] peer=$short close id=$id")
                200 to """{"closed":true}"""
            }
            else -> 404 to errorJson("not found")
        }
    }

    private fun handleTrustRevoke(fp: String): Pair<Int, String> {
        // Idempotent: revoking only ever drops the caller's own entry, so a
        // caller we already do not trust is exactly the end state we want and
        // must still get a 200. A racing duplicate retry therefore cannot make
        // the desktop see a spurious 403 "not paired" and re-arm/retry.
        val wasPaired = isPaired(fp) != null
        onUnpair(fp)
        diag("[pairing] peer=${fp.take(8)} unpair source=remote result=ok paired=$wasPaired")
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

    private fun handleShares(out: OutputStream, head: Head, peer: PairedPeer?, ssl: SSLSocket, input: InputStream, started: Long): Boolean {
        val fp = peer?.fingerprint ?: ""
        if (shares == null) { respond(out, 404, errorJson("not found")); logResp(404, head, fp, started, "shares unavailable"); return false }
        if (peer == null) { respond(out, 403, errorJson("not paired")); logResp(403, head, fp, started, "not paired"); return false }
        browseDenial(peer)?.let { reason ->
            respond(out, 403, errorJson(reason))
            logResp(403, head, fp, started, reason)
            return false
        }
        val isFileGet = (head.method == "GET" || head.method == "HEAD") && head.path.endsWith("/file")
        val guard = if (isFileGet) StallGuard(ssl, stallTimeoutMillis.toLong(), stallScheduler) else null
        // ShareServer writes its response directly, so watch the status line to
        // record it in the report.
        val watcher = StatusWatcher(out)
        try {
            // Drain a POST body so it cannot be misread as the next request.
            if (head.method == "POST") readSmallBody(ssl, input, head, maxBodyBytes, started)
            shares.handle(
                watcher, head.method, head.path, parseQuery(head.query),
                head.headers["range"], head.headers["if-range"], peer,
            ) { guard?.kick() }
            logResp(watcher.code, head, fp, started)
        } catch (e: PeerHttpException) {
            // An oversized share body leaves the rest on the wire: close it so it
            // is not misparsed as the next request.
            respond(out, e.code, errorJson(e.message), e.closeConnection)
            logResp(e.code, head, fp, started, e.message ?: "")
            return e.closeConnection
        } catch (e: Exception) {
            respond(out, 500, errorJson("could not read the shared file"))
            logResp(500, head, fp, started, e.javaClass.simpleName)
        } finally {
            guard?.stop()
        }
        return false
    }

    /** Wraps an [OutputStream] to observe the first response status code. */
    private class StatusWatcher(private val delegate: OutputStream) : OutputStream() {
        var code: Int = 0
            private set
        private val line = StringBuilder()
        private var captured = false

        private fun scan(c: Char) {
            if (captured) return
            if (c == '\n') {
                val parts = line.toString().trim().split(' ')
                if (parts.size >= 2) code = parts[1].toIntOrNull() ?: 0
                captured = true
            } else if (c != '\r') {
                line.append(c)
            }
        }

        override fun write(b: Int) {
            scan(b.toChar())
            delegate.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (!captured) for (i in off until off + len) scan(b[i].toInt().toChar())
            delegate.write(b, off, len)
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
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
        append(""","granted":""").append(permissionsJson(v.granted, v.triAware))
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

    private fun permissionsJson(p: Permissions, tristate: Boolean = true): String = buildString {
        append("""{"browse":""").append(p.browse)
        append(""","push":""").append(p.push)
        append(""","text":""").append(p.text)
        if (p.pushMaxBytes > 0) append(""","push_max_bytes":""").append(p.pushMaxBytes)
        if (p.askOver > 0) append(""","ask_over":""").append(p.askOver)
        // The `*_mode` keys are emitted only when the peer negotiated tri-state,
        // so an old peer sees the same body it always did.
        if (tristate) {
            p.browseMode?.let { append(""","browse_mode":""").append(it.wire).append('"') }
            p.pushMode?.let { append(""","push_mode":""").append(it.wire).append('"') }
            p.textMode?.let { append(""","text_mode":""").append(it.wire).append('"') }
        }
        append('}')
    }

    private fun JsonObject.permissions(key: String): Permissions {
        val o = get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: return Permissions()
        fun b(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asBoolean ?: false
        fun l(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asLong ?: 0L
        fun m(k: String) = Permission.fromString(o.get(k)?.takeIf { !it.isJsonNull }?.asString)
        return Permissions(
            browse = b("browse"),
            push = b("push"),
            pushMaxBytes = l("push_max_bytes"),
            askOver = l("ask_over"),
            text = b("text"),
            browseMode = m("browse_mode"),
            pushMode = m("push_mode"),
            textMode = m("text_mode"),
        )
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

        /**
         * How long one accepted browse approval is remembered, i.e. the length
         * of a "browse session". Five minutes covers a person browsing a
         * folder and pulling several files without a second prompt.
         */
        const val BROWSE_SESSION_TTL_MS = 5 * 60 * 1000L

        /**
         * How long a configured (user-owned) port is retried before falling back
         * to a temporary ephemeral port: about 5 s, in case whoever held it just
         * released it.
         */
        const val DEFAULT_BIND_RETRY_MS = 5_000L

        /** The pause between retries of a busy configured port. */
        const val BIND_RETRY_INTERVAL_MS = 100L
    }
}
