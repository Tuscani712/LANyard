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
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * The phone-side peer API (`/api/v1/...`) over mutual TLS 1.3. In C1 it answers
 * the discovery probe and the responder side of pairing, so a desktop can pair
 * *to* the phone and then see it online.
 *
 * Hardening (all reachable by anyone on the Wi-Fi):
 *  - a fixed pool of [maxConnections] workers; an over-cap connection is closed;
 *  - a per-read [connectionTimeoutMillis] and a whole-connection
 *    [connectionDeadlineMillis] watchdog, so a dribbling client can't hold a
 *    worker;
 *  - a [MAX_HEADER_BYTES] header cap and a body cap; `Content-Length` is
 *    required, chunked encoding is refused;
 *  - a global request limit ([maxSessionRequestsPerMinute]) against session
 *    requests, because a hostile device can present a fresh certificate each
 *    time;
 *  - the caller's identity always comes from the TLS certificate, never the body.
 *
 * [stop] closes the listener, live sockets, the pool and the scheduler, and
 * frees the port.
 */
class PeerServer(
    private val sessions: PairingSessions,
    private val trust: TrustStore,
    private val invites: PairInvites,
    private val connectionTimeoutMillis: Int = 10_000,
    private val connectionDeadlineMillis: Long = 15_000,
    private val maxConnections: Int = 8,
    private val maxSessionRequestsPerMinute: Int = 10,
    private val maxBodyBytes: Int = 64 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var server: SSLServerSocket? = null
    private var pool: ThreadPoolExecutor? = null
    private var scheduler: ScheduledExecutorService? = null
    private val live: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    private val requestTimes = ArrayDeque<Long>()
    private var hello: ((Int) -> JsonObject)? = null

    @Volatile
    var port: Int = 0
        private set

    /** How many workers are currently handling a connection. */
    fun activeConnections(): Int = pool?.activeCount ?: 0

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
        val executor = ThreadPoolExecutor(
            maxConnections, maxConnections, 30L, TimeUnit.SECONDS, SynchronousQueue(), factory,
        )
        val sched = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "lanyard-peer-watchdog").apply { isDaemon = true }
        }

        server = ss
        pool = executor
        scheduler = sched
        port = ss.localPort
        val t = Thread({ acceptLoop(ss, executor, sched) }, "lanyard-peer-accept")
        t.isDaemon = true
        t.start()
        return port
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        pool?.shutdownNow()
        pool = null
        scheduler?.shutdownNow()
        scheduler = null
        hello = null
        for (socket in live) runCatching { socket.close() }
        live.clear()
        synchronized(requestTimes) { requestTimes.clear() }
        port = 0
    }

    private fun acceptLoop(ss: SSLServerSocket, executor: ThreadPoolExecutor, sched: ScheduledExecutorService) {
        while (!ss.isClosed) {
            val socket = try {
                ss.accept()
            } catch (_: Exception) {
                return
            }
            runCatching { socket.soTimeout = connectionTimeoutMillis }
            live.add(socket)
            // Whole-connection deadline: closes a client that dribbles bytes
            // slowly enough to keep every per-read timeout happy.
            val watchdog = runCatching {
                sched.schedule({ runCatching { socket.close() } }, connectionDeadlineMillis, TimeUnit.MILLISECONDS)
            }.getOrNull()
            try {
                executor.execute {
                    try {
                        handle(socket)
                    } finally {
                        watchdog?.cancel(false)
                    }
                }
            } catch (_: RejectedExecutionException) {
                watchdog?.cancel(false)
                live.remove(socket)
                runCatching { socket.close() }
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            val ssl = socket as SSLSocket
            ssl.startHandshake()
            val fp = peerFingerprint(ssl) ?: throw PeerHttpException(401, "client certificate required")
            val out = BufferedOutputStream(ssl.getOutputStream())
            val req = try {
                readRequest(BufferedInputStream(ssl.getInputStream()))
            } catch (e: PeerHttpException) {
                respond(out, e.code, errorJson(e.message))
                return
            }
            val (code, body) = route(req, fp, ssl)
            respond(out, code, body)
        } catch (e: PeerHttpException) {
            runCatching { respond(BufferedOutputStream(socket.getOutputStream()), e.code, errorJson(e.message)) }
        } catch (_: Exception) {
            // handshake/read failure or the watchdog closing us: simply drop
        } finally {
            live.remove(socket)
            runCatching { socket.close() }
        }
    }

    // --- routing ---

    private fun route(req: Request, fp: String, socket: SSLSocket): Pair<Int, String> {
        val path = req.path
        return when {
            req.method == "GET" && path == "/api/v1/hello" ->
                200 to (hello?.invoke(port)?.toString() ?: "{}")
            req.method == "POST" && path == "/api/v1/session/request" ->
                200 to handleSessionRequest(req, fp, socket)
            req.method == "POST" && path == "/api/v1/trust/revoke" ->
                200 to handleTrustRevoke(fp)
            path.startsWith("/api/v1/session/") -> handleSession(req, fp, path)
            else -> 404 to errorJson("not found")
        }
    }

    private fun handleSessionRequest(req: Request, fp: String, socket: SSLSocket): String {
        if (!allowGlobalRequest()) throw PeerHttpException(429, "too many requests")
        val json = parseObject(req.body)
        // The certificate is the identity; a body cannot claim to be someone else.
        val claimed = json.str("fingerprint")
        if (claimed.isNotEmpty() && !claimed.equals(fp, ignoreCase = true)) {
            throw PeerHttpException(400, "fingerprint does not match the certificate")
        }
        // device_id is a display label in the protocol, but a caller must not be
        // able to smuggle a different fingerprint in it either.
        val claimedId = json.str("device_id")
        if (claimedId.length == 64 && claimedId.all { isHex(it) } && !claimedId.equals(fp, ignoreCase = true)) {
            throw PeerHttpException(400, "device id does not match the certificate")
        }
        val mode = json.str("mode")
        val nonce = json.str("nonce")
        val invite = json.str("invite")
        // The invite is only spent inside createIncoming, after the caps pass, so
        // a request rejected for a cap cannot waste a valid code. A bad or reused
        // invite is still a hard 403 with no SAS fallback.
        val view = sessions.createIncoming(
            mode = mode,
            peerFp = fp,
            peerName = json.str("name"),
            peerDevice = json.str("device_id"),
            peerHost = socket.inetAddress?.hostAddress ?: "",
            peerNonce = nonce,
            requested = json.permissions("requested_permissions"),
            consumeInvite = if (invite.isEmpty()) null else ({ invites.consume(invite) }),
        )
        return """{"session_id":${jsonStr(view.id)},"nonce":${jsonStr(view.nonce)},"status":${jsonStr(view.status)}}"""
    }

    private fun handleSession(req: Request, fp: String, path: String): Pair<Int, String> {
        val rest = path.removePrefix("/api/v1/session/")
        return when {
            req.method == "GET" && '/' !in rest -> {
                val v = sessions.statusFor(rest, fp)
                200 to statusJson(v)
            }
            req.method == "POST" && rest.endsWith("/confirm") -> {
                val v = sessions.confirm(rest.removeSuffix("/confirm"), fp)
                200 to """{"status":${jsonStr(v.status)}}"""
            }
            req.method == "POST" && rest.endsWith("/close") -> {
                sessions.close(rest.removeSuffix("/close"), fp)
                200 to """{"closed":true}"""
            }
            else -> 404 to errorJson("not found")
        }
    }

    private fun handleTrustRevoke(fp: String): String {
        // Only the caller's own entry, and only if it is actually paired.
        if (trust.find(fp) == null) throw PeerHttpException(403, "not paired")
        trust.remove(fp)
        return """{"ok":true}"""
    }

    private fun allowGlobalRequest(): Boolean {
        synchronized(requestTimes) {
            val now = clock()
            while (requestTimes.isNotEmpty() && now - requestTimes.first() > 60_000) requestTimes.removeFirst()
            if (requestTimes.size >= maxSessionRequestsPerMinute) return false
            requestTimes.addLast(now)
            return true
        }
    }

    // --- HTTP ---

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private fun readRequest(input: BufferedInputStream): Request {
        val headerBytes = readHeaders(input) ?: throw PeerHttpException(400, "bad request")
        val text = String(headerBytes, Charsets.ISO_8859_1)
        val lines = text.split("\r\n")
        val parts = lines.firstOrNull()?.trim().orEmpty().split(" ")
        if (parts.size < 2) throw PeerHttpException(400, "bad request line")
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?')
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val te = headers["transfer-encoding"]
        if (te != null && te.contains("chunked", ignoreCase = true)) {
            throw PeerHttpException(400, "chunked encoding is not supported")
        }
        val body = if (method == "GET" || method == "HEAD") {
            ByteArray(0)
        } else {
            val cl = headers["content-length"] ?: throw PeerHttpException(411, "content-length required")
            val n = cl.toLongOrNull() ?: throw PeerHttpException(400, "bad content-length")
            if (n < 0) throw PeerHttpException(400, "bad content-length")
            if (n > maxBodyBytes) throw PeerHttpException(413, "body too large")
            readExactly(input, n.toInt())
        }
        return Request(method, path, headers, body)
    }

    /** Reads up to the blank line ending the headers; null if the cap is hit. */
    private fun readHeaders(input: InputStream): ByteArray? {
        val buf = java.io.ByteArrayOutputStream()
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
            if (buf.size() > MAX_HEADER_BYTES) return null
        }
        return buf.toByteArray()
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

    private fun respond(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code ${reason(code)}\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    // --- helpers ---

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

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString ?: ""

    private fun isHex(ch: Char): Boolean = ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'

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

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        409 -> "Conflict"
        411 -> "Length Required"
        413 -> "Payload Too Large"
        429 -> "Too Many Requests"
        503 -> "Service Unavailable"
        else -> "Error"
    }

    companion object {
        const val MAX_HEADER_BYTES = 16 * 1024
    }
}
