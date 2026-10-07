package io.github.tuscani712.lanyard.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The `/api/v1/hello` responder, tested against a real server: the probe round
 * trip, the exact JSON keys the Go side decodes, and the hostile cases a peer on
 * the same Wi-Fi can throw at it (idle connections, garbage, oversized headers).
 */
class PeerHelloServerTest {

    private fun hello(name: String) = { port: Int ->
        JsonObject().apply {
            addProperty("device_id", "0123456789abcdef")
            addProperty("fingerprint", "f".repeat(64))
            addProperty("name", name)
            addProperty("os", "android")
            addProperty("version", "9.9.9-test")
            addProperty("port", port)
        }
    }

    private fun tls(factory: SSLSocketFactory, port: Int, request: String): String {
        factory.createSocket("127.0.0.1", port).use { raw ->
            val s = raw as SSLSocket
            s.startHandshake()
            s.outputStream.write(request.toByteArray(Charsets.US_ASCII))
            s.outputStream.flush()
            return s.inputStream.readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun statusLine(response: String): String = response.lineSequence().firstOrNull() ?: ""

    @Test
    @Timeout(30)
    fun probeRoundTripExposesExactlyTheGoKeys() {
        val serverId = Identity.generate("Android")
        val server = PeerHelloServer(hello("Pixel"))
        try {
            val port = server.start(serverId)
            assertTrue(port > 0)

            val probeId = Identity.generate("Probe")
            val probe = ProbeClient("127.0.0.1", port, probeId)
            val h = probe.hello()
            assertEquals("Pixel", h.name)
            assertEquals("android", h.os)
            assertEquals("9.9.9-test", h.version)
            assertEquals(port, h.port)
            assertEquals(serverId.deviceId, probe.observedFingerprint())

            // The raw body carries exactly the keys the Go side decodes.
            val factory = Tls.socketFactory(probeId, serverId.deviceId)
            val body = tls(factory, port, "GET /api/v1/hello HTTP/1.1\r\nHost: x\r\n\r\n")
                .substringAfter("\r\n\r\n")
            val keys = JsonParser.parseString(body).asJsonObject.keySet()
            assertEquals(setOf("device_id", "fingerprint", "name", "os", "version", "port"), keys)
        } finally {
            server.stop()
        }
        assertEquals(0, server.port)
    }

    @Test
    @Timeout(30)
    fun answersOnlyGetHello() {
        val id = Identity.generate("Android")
        val server = PeerHelloServer(hello("Pixel"))
        try {
            val port = server.start(id)
            val factory = Tls.socketFactory(id, id.deviceId)

            val ok = tls(factory, port, "GET /api/v1/hello HTTP/1.1\r\nHost: x\r\n\r\n")
            assertTrue(statusLine(ok).startsWith("HTTP/1.1 200"), statusLine(ok))

            val post = tls(factory, port, "POST /api/v1/hello HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n")
            assertTrue(statusLine(post).startsWith("HTTP/1.1 404"), statusLine(post))

            val other = tls(factory, port, "GET /api/v1/other HTTP/1.1\r\nHost: x\r\n\r\n")
            assertTrue(statusLine(other).startsWith("HTTP/1.1 404"), statusLine(other))
        } finally {
            server.stop()
        }
    }

    @Test
    @Timeout(30)
    fun survivesGarbageAndOversizedHeaders() {
        val id = Identity.generate("Android")
        val server = PeerHelloServer(hello("Pixel"), connectionTimeoutMillis = 500)
        try {
            val port = server.start(id)

            // Plain, non-TLS bytes are dropped without a crash.
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 2000)
                runCatching {
                    s.getOutputStream().write("not tls at all\r\n\r\n".toByteArray())
                    s.getOutputStream().flush()
                }
            }

            // An oversized header (no CRLFCRLF within the cap) is dropped.
            runCatching {
                val factory = Tls.socketFactory(id, id.deviceId)
                factory.createSocket("127.0.0.1", port).use { raw ->
                    val s = raw as SSLSocket
                    s.startHandshake()
                    s.outputStream.write("GET /api/v1/hello HTTP/1.1\r\n".toByteArray())
                    s.outputStream.write(ByteArray(PeerHelloServer.MAX_HEADER_BYTES + 1024) { 'A'.code.toByte() })
                    s.outputStream.flush()
                    s.inputStream.read()
                }
            }

            // Still serving afterwards.
            val probe = ProbeClient("127.0.0.1", port, Identity.generate("Probe"))
            assertEquals("Pixel", probe.hello().name)
        } finally {
            server.stop()
        }
    }

    @Test
    @Timeout(40)
    fun connectionCapAndIdleTimeout() {
        val id = Identity.generate("Android")
        val server = PeerHelloServer(hello("Pixel"), connectionTimeoutMillis = 400, maxConnections = 8)
        val idle = ArrayList<Socket>()
        try {
            val port = server.start(id)
            repeat(50) {
                runCatching {
                    Socket().apply { connect(InetSocketAddress("127.0.0.1", port), 2000) }.also { idle.add(it) }
                }
            }
            // Never more than the cap is being worked on; the rest are dropped.
            assertTrue(server.activeConnections() <= 8, "active=${server.activeConnections()}")

            // Idle connections time out and free their workers.
            val freedBy = System.currentTimeMillis() + 5_000
            while (server.activeConnections() > 0 && System.currentTimeMillis() < freedBy) Thread.sleep(50)
            assertEquals(0, server.activeConnections())

            // A real request now succeeds.
            val probe = ProbeClient("127.0.0.1", port, Identity.generate("Probe"))
            assertEquals("Pixel", probe.hello().name)
        } finally {
            idle.forEach { runCatching { it.close() } }
            server.stop()
        }
    }

    @Test
    @Timeout(20)
    fun stopClosesLiveConnectionsAndFreesThePort() {
        val id = Identity.generate("Android")
        val server = PeerHelloServer(hello("Pixel"), connectionTimeoutMillis = 10_000)
        val port = server.start(id)
        val idle = Socket().apply { connect(InetSocketAddress("127.0.0.1", port), 2000) }
        Thread.sleep(200) // let the server accept it and block on the read

        server.stop()
        assertEquals(0, server.port)
        assertEquals(0, server.activeConnections())
        idle.use { s ->
            s.soTimeout = 2_000
            val r = runCatching { s.getInputStream().read() }
            assertTrue(r.isFailure || r.getOrNull() == -1, "live connection was not closed: $r")
        }

        // A fresh server binds again (the port was freed).
        val again = PeerHelloServer(hello("Pixel"))
        try {
            assertTrue(again.start(id) > 0)
        } finally {
            again.stop()
        }
    }
}
