package io.github.tuscani712.lanyard.core

import com.google.gson.JsonObject
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocket

/**
 * The discovery half of the peer server: a minimal TLS endpoint that answers
 * only `GET /api/v1/hello`.
 *
 * A desktop that hears this device over mDNS verifies the announcement by
 * dialling the advertised port and checking the certificate it presents
 * (`internal/discovery/manager.go`). Until this responder exists, the phone
 * flickers on the PC as an unverified peer and is then dropped. `/hello`
 * exposes only the device's public identity, so any client certificate is
 * accepted. Pairing (session) and transfers are not implemented here yet.
 *
 * Hardening: this endpoint is reachable by anyone on the same Wi-Fi, so it is
 * deliberately small and bounded rather than trusting. The accept loop only
 * ever hands a socket to a fixed pool of [maxConnections] workers; when every
 * worker is busy a new connection is closed immediately. Each accepted socket
 * gets a [connectionTimeoutMillis] read timeout that covers both the TLS
 * handshake and the request-header read, so a client that connects and then
 * says nothing cannot hold a worker forever. The request header is capped at
 * [MAX_HEADER_BYTES]. [stop] closes the listening socket, every live client
 * socket and the pool, and frees the port.
 */
class PeerHelloServer(
    private val hello: (port: Int) -> JsonObject,
    private val connectionTimeoutMillis: Int = 5_000,
    private val maxConnections: Int = 8,
) {
    private var server: SSLServerSocket? = null
    private var pool: ThreadPoolExecutor? = null
    private val live: MutableSet<Socket> = ConcurrentHashMap.newKeySet()

    /** The port the server is listening on, or 0 when stopped. */
    @Volatile
    var port: Int = 0
        private set

    /** How many workers are currently handling a connection (0 when stopped). */
    fun activeConnections(): Int = pool?.activeCount ?: 0

    /** Binds an ephemeral port and starts the accept loop; returns the port. */
    fun start(identity: Identity): Int {
        stop()
        val ctx = Tls.serverContext(identity)
        val ss = ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        ss.needClientAuth = true
        ss.enabledProtocols = arrayOf("TLSv1.3")

        // A fixed pool with a synchronous hand-off: the (max+1)th concurrent
        // connection is rejected rather than queued, and we close it at once.
        val counter = AtomicInteger()
        val factory = ThreadFactory { r ->
            Thread(r, "lanyard-hello-conn-${counter.incrementAndGet()}").apply { isDaemon = true }
        }
        val executor = ThreadPoolExecutor(
            maxConnections,
            maxConnections,
            30L,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            factory,
        )

        server = ss
        pool = executor
        port = ss.localPort
        val t = Thread({ acceptLoop(ss, executor) }, "lanyard-hello")
        t.isDaemon = true
        t.start()
        return port
    }

    /** Closes the listening socket, live client sockets and the worker pool. */
    fun stop() {
        runCatching { server?.close() }
        server = null
        pool?.shutdownNow()
        pool = null
        for (socket in live) runCatching { socket.close() }
        live.clear()
        port = 0
    }

    private fun acceptLoop(ss: SSLServerSocket, executor: ThreadPoolExecutor) {
        while (!ss.isClosed) {
            val socket = try {
                ss.accept()
            } catch (_: Exception) {
                return // closed
            }
            // Bounds both the TLS handshake (triggered by the first read) and
            // the header read that follows it.
            runCatching { socket.soTimeout = connectionTimeoutMillis }
            live.add(socket)
            try {
                executor.execute { handle(socket) }
            } catch (_: RejectedExecutionException) {
                // At the connection cap: drop the newcomer without touching the
                // workers already serving live clients.
                live.remove(socket)
                runCatching { socket.close() }
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                val input = BufferedInputStream(s.getInputStream())
                val requestLine = readRequestLine(input) ?: return
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: ""
                val path = parts.getOrNull(1)?.substringBefore('?') ?: ""
                if (method == "GET" && path == "/api/v1/hello") {
                    writeResponse(s.getOutputStream(), "200 OK", hello(port).toString())
                } else {
                    writeResponse(s.getOutputStream(), "404 Not Found", "{}")
                }
            }
        } catch (_: Exception) {
            // handshake or read failure (including the read timeout): dropped
        } finally {
            live.remove(socket)
        }
    }

    /** Reads up to the blank line ending the request headers and returns the request line. */
    private fun readRequestLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        var a = 0
        var b = 0
        var c = 0
        var d = 0
        while (true) {
            val r = input.read()
            if (r < 0) break
            sb.append(r.toChar())
            a = b; b = c; c = d; d = r
            if (a == 13 && b == 10 && c == 13 && d == 10) break // \r\n\r\n
            if (sb.length > MAX_HEADER_BYTES) return null
        }
        return sb.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun writeResponse(out: OutputStream, status: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    companion object {
        /** The request header cap; anything longer is dropped. */
        const val MAX_HEADER_BYTES = 16 * 1024
    }
}
