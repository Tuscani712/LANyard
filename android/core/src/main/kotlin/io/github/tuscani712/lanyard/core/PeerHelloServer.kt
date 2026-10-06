package io.github.tuscani712.lanyard.core

import com.google.gson.JsonObject
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.Socket
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
 */
class PeerHelloServer(private val hello: (port: Int) -> JsonObject) {
    private var server: SSLServerSocket? = null
    private var thread: Thread? = null

    /** The port the server is listening on, or 0 when stopped. */
    @Volatile
    var port: Int = 0
        private set

    /** Binds an ephemeral port and starts the accept loop; returns the port. */
    fun start(identity: Identity): Int {
        stop()
        val ctx = Tls.serverContext(identity)
        val ss = ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        ss.needClientAuth = true
        ss.enabledProtocols = arrayOf("TLSv1.3")
        server = ss
        port = ss.localPort
        val t = Thread({ acceptLoop(ss) }, "lanyard-hello")
        t.isDaemon = true
        thread = t
        t.start()
        return port
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        thread = null
        port = 0
    }

    private fun acceptLoop(ss: SSLServerSocket) {
        while (!ss.isClosed) {
            val socket = try {
                ss.accept()
            } catch (_: Exception) {
                return // closed
            }
            Thread({ handle(socket) }, "lanyard-hello-conn").apply { isDaemon = true }.start()
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
            // handshake or read failure: the connection is simply dropped
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
            if (sb.length > 16 * 1024) return null
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
}
