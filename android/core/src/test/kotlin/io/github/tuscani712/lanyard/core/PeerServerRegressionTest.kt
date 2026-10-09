package io.github.tuscani712.lanyard.core

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import javax.net.ssl.SSLSocket

/**
 * Regressions guarded here:
 *  1. authorization must be decided per request, not once per connection, so a
 *     keep-alive connection opened before a peer paired is not treated as
 *     unpaired for its whole life; and
 *  2. an unexpected handler failure (the save location could not be written)
 *     must answer with an HTTP status rather than closing with no response.
 */
class PeerServerRegressionTest {

    private class Phone(destination: PushDestination, destinationReady: () -> Boolean = { true }) : AutoCloseable {
        val identity: Identity = Identity.generate("Pixel 8 Pro")
        private val trustFile = Files.createTempFile("lanyard-trust", ".json").toFile().also { it.delete() }
        val trust: TrustStore = JsonFileTrustStore(trustFile)
        val sessions = PairingSessions(selfFp = { identity.deviceId }, trust = trust)
        private val spool = Files.createTempDirectory("lanyard-spool").toFile()
        /** (pushId, reason) for each in-flight receive the server reports failed. */
        val failures = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        private val receiver = InboxReceiver(
            spoolRoot = spool,
            destination = destination,
            freeBytes = { 1L shl 40 },
            onFailed = { id, reason -> failures.add(id to reason) },
            destinationReady = destinationReady,
        )
        private val server = PeerServer(
            sessions = sessions,
            receiver = receiver,
            invites = PairInvites(),
            isPaired = { trust.find(it) },
        )
        val port: Int = server.start(identity) { p ->
            com.google.gson.JsonObject().apply {
                addProperty("device_id", identity.deviceId.take(16))
                addProperty("fingerprint", identity.deviceId)
                addProperty("name", "Pixel 8 Pro")
                addProperty("port", p)
            }
        }
        fun pair(clientFp: String) {
            trust.save(PairedPeer(clientFp, "Desktop", "127.0.0.1", 0, browse = true, push = true, pairedAt = 1))
        }
        override fun close() = server.stop()
    }

    private class Http(socket: Socket) {
        private val out: OutputStream = socket.getOutputStream()
        private val input: InputStream = socket.getInputStream()

        fun send(method: String, path: String, headers: Map<String, String> = emptyMap(), body: ByteArray = ByteArray(0)): Response {
            val sb = StringBuilder("$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\n")
            if (body.isNotEmpty() && headers.keys.none { it.equals("content-length", true) }) {
                sb.append("Content-Length: ${body.size}\r\n")
            }
            headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
            sb.append("\r\n")
            out.write(sb.toString().toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.flush()
            return readResponse()
        }

        private fun readResponse(): Response {
            val statusLine = readLine() ?: error("connection closed before a status line")
            val code = statusLine.split(" ")[1].toInt()
            var length = -1
            while (true) {
                val line = readLine() ?: error("connection closed in headers")
                if (line.isEmpty()) break
                val c = line.indexOf(':')
                if (c > 0 && line.substring(0, c).trim().equals("content-length", true)) {
                    length = line.substring(c + 1).trim().toInt()
                }
            }
            val body = if (length > 0) readExactly(length).toString(Charsets.UTF_8) else ""
            return Response(code, body)
        }

        private fun readLine(): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().removeSuffix("\r")
                sb.append(b.toChar())
            }
        }

        private fun readExactly(n: Int): ByteArray {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r < 0) break
                off += r
            }
            return buf
        }
    }

    private data class Response(val code: Int, val body: String)

    private fun connect(client: Identity, phone: Phone): Http {
        val factory = Tls.socketFactory(client, phone.identity.deviceId)
        val socket = factory.createSocket("127.0.0.1", phone.port) as SSLSocket
        socket.enabledProtocols = arrayOf("TLSv1.3")
        socket.startHandshake()
        return Http(socket)
    }

    private fun offerBody(rel: String, size: Int): ByteArray =
        """{"files":[{"rel_path":"$rel","size":$size,"mtime":""}],"total_bytes":$size}""".toByteArray()

    @Test
    @Timeout(60)
    fun pairingOnAReusedConnectionIsHonoredPerRequest() {
        Phone(PushDestination { rel, _, _ -> rel }).use { phone ->
            val client = Identity.generate("Desktop")
            val http = connect(client, phone) // connection opened while unpaired

            val before = http.send("POST", "/api/v1/push/offer", mapOf("Content-Type" to "application/json"), offerBody("note.txt", 5))
            assertEquals(403, before.code, "an unpaired peer must be refused (got ${before.code}: ${before.body})")

            // The person pairs this desktop while the connection is idle.
            phone.pair(client.deviceId)
            assertTrue(phone.trust.find(client.deviceId) != null, "test setup: trust must contain ${client.deviceId.take(8)}")

            // The very same keep-alive connection must now be treated as paired.
            val after = http.send("POST", "/api/v1/push/offer", mapOf("Content-Type" to "application/json"), offerBody("note.txt", 5))
            assertEquals(200, after.code, "a just-paired peer must be accepted on the reused connection (got ${after.code}: ${after.body})")
            assertTrue(after.body.contains("push_id"))
        }
    }

    @Test
    @Timeout(60)
    fun unexpectedHandlerFailureAnswers500InsteadOfDroppingTheConnection() {
        // A destination that always fails, like an unwritable download folder.
        val failing = PushDestination { _, _, _ -> throw RuntimeException("save location unavailable") }
        Phone(failing).use { phone ->
            val client = Identity.generate("Desktop")
            phone.pair(client.deviceId)
            val http = connect(client, phone)

            val offer = http.send("POST", "/api/v1/push/offer", mapOf("Content-Type" to "application/json"), offerBody("note.txt", 5))
            assertEquals(200, offer.code)
            val pushId = JsonParser.parseString(offer.body).asJsonObject.get("push_id").asString

            val data = "hello".toByteArray()
            val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
            val put = http.send(
                "PUT",
                "/api/v1/push/$pushId/file?path=note.txt",
                mapOf(
                    "Content-Type" to "application/octet-stream",
                    "Content-Range" to "bytes 0-4/5",
                    "X-Lanyard-SHA256" to sha,
                ),
                data,
            )
            // Before the fix the server closed the socket here and the client saw
            // a bare EOF (error("connection closed before a status line")).
            assertEquals(500, put.code)
        }
    }

    @Test
    @Timeout(60)
    fun connectionDroppedMidBodyFailsTheReceive() {
        Phone(PushDestination { rel, _, _ -> rel }).use { phone ->
            val client = Identity.generate("Desktop")
            phone.pair(client.deviceId)
            val factory = Tls.socketFactory(client, phone.identity.deviceId)
            val socket = factory.createSocket("127.0.0.1", phone.port) as SSLSocket
            socket.enabledProtocols = arrayOf("TLSv1.3")
            socket.startHandshake()
            val http = Http(socket)

            val offer = http.send("POST", "/api/v1/push/offer", mapOf("Content-Type" to "application/json"), offerBody("note.txt", 10))
            assertEquals(200, offer.code)
            val pushId = JsonParser.parseString(offer.body).asJsonObject.get("push_id").asString

            // Declare 10 bytes, write 3, then drop the connection mid-body. The
            // session must be failed, not left Running forever.
            socket.outputStream.write(
                "PUT /api/v1/push/$pushId/file?path=note.txt HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 10\r\n\r\n".toByteArray(),
            )
            socket.outputStream.write(byteArrayOf(1, 2, 3))
            socket.outputStream.flush()
            socket.close()

            val deadline = System.currentTimeMillis() + 10_000
            while (phone.failures.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(25)
            assertEquals(listOf(pushId), phone.failures.map { it.first }, "a receive dropped mid-body must be failed")
            assertTrue(phone.failures.single().second.isNotBlank(), "the failure must carry a readable reason")
        }
    }

    @Test
    @Timeout(60)
    fun destinationUnavailableIsRefusedAtOfferWithAClearReason() {
        // The real cause on the phone: no usable download folder, so every save
        // would fail. The offer must be refused up front with a reason, not
        // accepted and then dropped mid-transfer.
        Phone(PushDestination { rel, _, _ -> rel }, destinationReady = { false }).use { phone ->
            val client = Identity.generate("Desktop")
            phone.pair(client.deviceId)
            val http = connect(client, phone)

            val offer = http.send("POST", "/api/v1/push/offer", mapOf("Content-Type" to "application/json"), offerBody("note.txt", 5))
            assertEquals(503, offer.code, "an offer that cannot be saved must be refused clearly (got ${offer.code}: ${offer.body})")
            assertTrue(offer.body.contains("download folder"), "the reason should mention the download folder: ${offer.body}")
        }
    }
}
