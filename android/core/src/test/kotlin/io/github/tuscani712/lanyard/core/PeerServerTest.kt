package io.github.tuscani712.lanyard.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import javax.net.ssl.SSLSocket

/**
 * The hardened phone-side peer API: the HTTP rules (body handling, identity
 * from the certificate), the flood limits, the connection deadline and the
 * paired-only endpoint. These use a real TLS server and real client certs.
 */
class PeerServerTest {

    private class Harness(
        deadlineMs: Long = 15_000,
        timeoutMs: Int = 5_000,
        maxReq: Int = 10,
        maxBody: Int = 64 * 1024,
        selfFpOverride: (() -> String)? = null,
    ) : AutoCloseable {
        val identity: Identity = Identity.generate("Phone")
        private val trustFile = File.createTempFile("lanyard-trust", ".json").also { it.delete() }
        val trust: TrustStore = JsonFileTrustStore(trustFile)
        val invites = PairInvites()
        val sessions = PairingSessions(selfFp = selfFpOverride ?: { identity.deviceId }, trust = trust)
        private val server = PeerServer(
            sessions, trust, invites,
            connectionTimeoutMillis = timeoutMs,
            connectionDeadlineMillis = deadlineMs,
            maxSessionRequestsPerMinute = maxReq,
            maxBodyBytes = maxBody,
        )
        val port: Int = server.start(identity) { p ->
            JsonObject().apply {
                addProperty("device_id", identity.deviceId.take(16))
                addProperty("port", p)
            }
        }

        fun request(client: Identity, raw: String): String {
            val factory = Tls.socketFactory(client, identity.deviceId)
            factory.createSocket("127.0.0.1", port).use { rawSocket ->
                val s = rawSocket as SSLSocket
                s.startHandshake()
                s.outputStream.write(raw.toByteArray())
                s.outputStream.flush()
                return s.inputStream.readBytes().toString(Charsets.UTF_8)
            }
        }

        override fun close() = server.stop()
    }

    private fun status(response: String): Int =
        response.lineSequence().firstOrNull()?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: -1

    private fun body(response: String): String = response.substringAfter("\r\n\r\n")

    private fun sessionBody(
        mode: String = "pair",
        name: String = "Desk",
        nonce: String = "11".repeat(16),
        fingerprint: String? = null,
        invite: String? = null,
        deviceId: String = "desk",
    ): String {
        val o = JsonObject().apply {
            addProperty("mode", mode)
            addProperty("name", name)
            addProperty("device_id", deviceId)
            addProperty("nonce", nonce)
            add("requested_permissions", JsonObject().apply {
                addProperty("browse", true)
                addProperty("push", true)
            })
            if (fingerprint != null) addProperty("fingerprint", fingerprint)
            if (invite != null) addProperty("invite", invite)
        }
        return o.toString()
    }

    private fun post(path: String, body: String): String {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return "POST $path HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n\r\n$body"
    }

    @Test
    @Timeout(60)
    fun helloIsOpen() {
        Harness().use { h ->
            val resp = h.request(h.identity, "GET /api/v1/hello HTTP/1.1\r\nHost: x\r\n\r\n")
            assertEquals(200, status(resp))
            assertTrue(body(resp).contains("device_id"))
        }
    }

    @Test
    @Timeout(60)
    fun sessionRequestIsOpenAndShowsPendingWithSas() {
        Harness().use { h ->
            val desk = Identity.generate("Desk")
            val resp = h.request(desk, post("/api/v1/session/request", sessionBody()))
            assertEquals(200, status(resp))
            assertEquals(1, h.sessions.pending().size)
            val view = h.sessions.pending().first()
            assertTrue(view.sas.length == 6)
        }
    }

    @Test
    @Timeout(60)
    fun bodyFingerprintMustMatchTheCertificate() {
        Harness().use { h ->
            val desk = Identity.generate("Desk")
            val resp = h.request(desk, post("/api/v1/session/request", sessionBody(fingerprint = "00".repeat(32))))
            assertEquals(400, status(resp))
        }
    }

    @Test
    @Timeout(60)
    fun bodyDeviceIdThatLooksLikeAFingerprintMustMatchTheCertificate() {
        Harness().use { h ->
            val desk = Identity.generate("Desk")
            val resp = h.request(desk, post("/api/v1/session/request", sessionBody(deviceId = "00".repeat(32))))
            assertEquals(400, status(resp))
        }
    }

    @Test
    @Timeout(60)
    fun qrInviteIsConsumedAndBadInviteRejected() {
        Harness().use { h ->
            val invite = h.invites.mint().token
            val first = h.request(
                Identity.generate("Desk"),
                post("/api/v1/session/request", sessionBody(invite = invite)),
            )
            assertEquals(200, status(first))
            // A reused invite must be refused, and a bogus one must not fall
            // back to the SAS path.
            val reused = h.request(
                Identity.generate("Desk2"),
                post("/api/v1/session/request", sessionBody(invite = invite)),
            )
            assertEquals(403, status(reused))
            val bogus = h.request(
                Identity.generate("Desk3"),
                post("/api/v1/session/request", sessionBody(invite = "deadbeef")),
            )
            assertEquals(403, status(bogus))
        }
    }

    @Test
    @Timeout(60)
    fun requiresContentLength() {        Harness().use { h ->
            val raw = "POST /api/v1/session/request HTTP/1.1\r\nHost: x\r\n\r\n"
            assertEquals(411, status(h.request(h.identity, raw)))
        }
    }

    @Test
    @Timeout(60)
    fun rejectsChunkedEncoding() {
        Harness().use { h ->
            val raw = "POST /api/v1/session/request HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n"
            assertEquals(400, status(h.request(h.identity, raw)))
        }
    }

    @Test
    @Timeout(60)
    fun rejectsNegativeLength() {
        Harness().use { h ->
            val raw = "POST /api/v1/session/request HTTP/1.1\r\nHost: x\r\nContent-Length: -5\r\n\r\n"
            assertEquals(400, status(h.request(h.identity, raw)))
        }
    }

    @Test
    @Timeout(60)
    fun rejectsOversizedBody() {
        Harness(maxBody = 64).use { h ->
            val raw = "POST /api/v1/session/request HTTP/1.1\r\nHost: x\r\nContent-Length: 200\r\n\r\n"
            assertEquals(413, status(h.request(h.identity, raw)))
        }
    }

    @Test
    @Timeout(60)
    fun unpairedClientCannotRevoke() {
        Harness().use { h ->
            val desk = Identity.generate("Desk")
            val resp = h.request(desk, post("/api/v1/trust/revoke", "{}"))
            assertEquals(403, status(resp))
        }
    }

    @Test
    @Timeout(60)
    fun pairedClientCanRevokeItselfOnly() {
        Harness().use { h ->
            val desk = Identity.generate("Desk")
            val other = "ab".repeat(32)
            h.trust.save(PairedPeer(desk.deviceId, "Desk", "h", 1, true, false, 0))
            h.trust.save(PairedPeer(other, "Other", "h", 1, true, false, 0))
            val resp = h.request(desk, post("/api/v1/trust/revoke", "{}"))
            assertEquals(200, status(resp))
            assertNull(h.trust.find(desk.deviceId))
            assertNotNull(h.trust.find(other), "only the caller's own entry is removed")
        }
    }

    @Test
    @Timeout(60)
    fun otherPeerCannotReadYourSession() {
        Harness().use { h ->
            val desk = Identity.generate("Desk")
            h.request(desk, post("/api/v1/session/request", sessionBody()))
            val id = h.sessions.pending().first().id
            val stranger = Identity.generate("Stranger")
            val resp = h.request(stranger, "GET /api/v1/session/$id HTTP/1.1\r\nHost: x\r\n\r\n")
            assertEquals(403, status(resp))
        }
    }

    @Test
    @Timeout(60)
    fun failsClosedWhenIdentityNotReady() {
        Harness(selfFpOverride = { "" }).use { h ->
            val resp = h.request(h.identity, post("/api/v1/session/request", sessionBody()))
            assertEquals(503, status(resp))
        }
    }

    @Test
    @Timeout(120)
    fun rateLimitWithFiftyFreshCertificates() {
        Harness().use { h ->
            var allowed = 0
            var limited = 0
            repeat(50) {
                val fresh = Identity.generate("Fresh")
                val resp = h.request(fresh, post("/api/v1/session/request", sessionBody(mode = "connect")))
                when (status(resp)) {
                    200 -> {
                        allowed++
                        val id = JsonParser.parseString(body(resp)).asJsonObject.get("session_id").asString
                        h.sessions.decline(id) // keep the pending cap out of the way
                    }
                    429 -> limited++
                }
            }
            assertEquals(10, allowed)
            assertEquals(40, limited)
        }
    }

    @Test
    @Timeout(60)
    fun wholeConnectionDeadlineClosesADribbler() {
        Harness(deadlineMs = 800, timeoutMs = 5_000).use { h ->
            val start = System.currentTimeMillis()
            runCatching {
                val factory = Tls.socketFactory(h.identity, h.identity.deviceId)
                factory.createSocket("127.0.0.1", h.port).use { rawSocket ->
                    val s = rawSocket as SSLSocket
                    s.startHandshake()
                    // A partial request: the server is still waiting for the header
                    // terminator when the watchdog fires.
                    s.outputStream.write("GET /api/v1/hello HTTP/1.1\r\n".toByteArray())
                    s.outputStream.flush()
                    s.soTimeout = 4_000
                    s.inputStream.read() // returns -1 when the watchdog closes it
                }
            }
            val elapsed = System.currentTimeMillis() - start
            assertTrue(elapsed < 3_000, "connection was not closed by the deadline (took ${elapsed}ms)")
        }
    }
}
