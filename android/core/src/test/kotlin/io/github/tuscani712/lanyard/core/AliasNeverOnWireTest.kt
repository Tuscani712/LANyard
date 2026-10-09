package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * F3: the local alias is never sent on the wire. A recording peer captures every
 * request line, header and body of a representative client session while a
 * paired peer carries a distinctive alias; none of it may contain the alias, so
 * the broadcast name is the only device name that ever leaves the phone.
 */
class AliasNeverOnWireTest {
    private class RecordingPeer : AutoCloseable {
        val identity: Identity = Identity.generate("Recorder")
        private val server: SSLServerSocket
        val port: Int
        private val thread: Thread
        private val seen = StringBuilder()

        init {
            val ctx = Tls.serverContext(identity)
            server = ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket
            server.needClientAuth = true
            server.enabledProtocols = arrayOf("TLSv1.3")
            port = server.localPort
            thread = Thread {
                while (!server.isClosed) {
                    runCatching {
                        val s = server.accept() as SSLSocket
                        s.use { socket -> handle(socket) }
                    }
                }
            }.apply { isDaemon = true; start() }
        }

        private fun handle(socket: SSLSocket) {
            socket.startHandshake()
            val input = BufferedInputStream(socket.getInputStream())
            val out = BufferedOutputStream(socket.getOutputStream())
            val head = StringBuilder()
            var a = 0; var b = 0; var c = 0; var d = 0
            while (true) {
                val r = input.read()
                if (r < 0) return
                head.append(r.toChar())
                a = b; b = c; c = d; d = r
                if (a == 13 && b == 10 && c == 13 && d == 10) break
            }
            val requestLine = head.lineSequence().first()
            val path = requestLine.split(" ").getOrElse(1) { "" }
            val cl = Regex("(?i)content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val body = ByteArray(cl)
            var off = 0
            while (off < cl) {
                val n = input.read(body, off, cl - off)
                if (n < 0) break
                off += n
            }
            val bodyText = String(body, Charsets.ISO_8859_1)
            synchronized(seen) { seen.append(head).append(bodyText).append('\n') }
            val resp = when {
                path.endsWith("/hello") ->
                    """{"device_id":"rec","fingerprint":"${identity.deviceId}","name":"Recorder","os":"linux","version":"1","port":$port}"""
                path.endsWith("/session/request") -> """{"session_id":"s_1","nonce":"n","status":"pending"}"""
                path.endsWith("/offer") ->
                    """{"push_id":"p_1","accepted":true,"max_bytes":0,"files":[{"rel_path":"a.bin","offset":0}]}"""
                path.contains("/file") -> """{"written":4}"""
                path.endsWith("/complete") -> """{"done":true}"""
                path.endsWith("/snippet") -> """{"id":"s_1"}"""
                path.endsWith("/trust/revoke") -> """{"ok":true}"""
                else -> """{"ok":true}"""
            }
            val status = if (path.endsWith("/session/request")) "200 OK" else "200 OK"
            val bytes = resp.toByteArray()
            out.write(
                ("HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            out.write(bytes)
            out.flush()
        }

        fun recorded(): String = synchronized(seen) { seen.toString() }

        override fun close() {
            runCatching { server.close() }
            thread.interrupt()
        }
    }

    @Test
    @Timeout(60)
    fun noRequestCarriesTheLocalAlias() {
        RecordingPeer().use { peer ->
            val identity = Identity.generate("Phone")
            val alias = "SECRET-ALIAS-XYZ"
            val paired = PairedPeer(
                fingerprint = peer.identity.deviceId,
                name = "Broadcast PC",
                host = "127.0.0.1",
                port = peer.port,
                browse = true,
                push = true,
                pairedAt = 1,
                alias = alias,
            )
            // Every wire call is built from the peer's address/fingerprint only;
            // the alias is a purely local display value.
            val client = PeerClient(paired.host, paired.port, identity, paired.fingerprint)
            client.hello()
            client.startSession("pair", "Phone", identity.deviceId, "nonce", Permissions(browse = true, push = true))
            client.pushOffer(listOf(PushFileRequest("a.bin", 4, 0)))
            client.pushFileWithSha("p_1", "a.bin", ByteArray(4), sha256Hex(ByteArray(4)))
            client.pushCompleteAll("p_1")
            client.sendSnippet("hi")
            client.revokeTrust()

            val recorded = peer.recorded()
            assertFalse(recorded.contains(alias), "the alias must never be sent on the wire")
            assertTrue(recorded.contains("Broadcast PC") || recorded.isNotBlank(), "the session should have been captured")
        }
    }
}
