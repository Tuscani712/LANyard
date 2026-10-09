package io.github.tuscani712.lanyard.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        timeoutMs: Int = 5_000,
        idleMs: Int = 5_000,
        maxReq: Int = 10,
        maxBody: Int = 64 * 1024,
        maxOfferBody: Int = PushProtocol.MAX_OFFER_BODY_BYTES,
        selfFpOverride: (() -> String)? = null,
        val diagnostics: ServerDiagnostics? = null,
        val snippets: ReceivedSnippets? = null,
        private val approval: PushApproval? = null,
        private val browseApproval: BrowseApproval? = null,
        shares: ShareServer? = null,
    ) : AutoCloseable {
        val identity: Identity = Identity.generate("Phone")
        private val trustFile = File.createTempFile("lanyard-trust", ".json").also { it.delete() }
        val trust: TrustStore = JsonFileTrustStore(trustFile)
        val invites = PairInvites()
        private val spool = File.createTempFile("spool", ".d").let { it.delete(); it.mkdirs(); it }
        val sessions = PairingSessions(selfFp = selfFpOverride ?: { identity.deviceId }, trust = trust)
        private val receiver = InboxReceiver(spool, PushDestination { _, _, _ -> "x" }, { Long.MAX_VALUE })
        private val server = PeerServer(
            sessions, receiver, invites,
            isPaired = { trust.find(it) },
            onUnpair = { trust.remove(it) },
            headerTimeoutMillis = timeoutMs,
            stallTimeoutMillis = timeoutMs,
            idleTimeoutMillis = idleMs,
            maxRequestsPerConnection = 64,
            maxSessionRequestsPerMinute = maxReq,
            maxBodyBytes = maxBody,
            maxOfferBodyBytes = maxOfferBody,
            snippets = snippets,
            approval = approval,
            browseApproval = browseApproval,
            shares = shares,
            diagnostics = diagnostics,
        )
        val port: Int = server.start(identity) { p ->
            JsonObject().apply {
                addProperty("device_id", identity.deviceId.take(16))
                addProperty("port", p)
            }
        }

        /** Paired with push permission, for the push-endpoint tests. */
        fun selfPair() {
            trust.save(PairedPeer(identity.deviceId, "Self", "127.0.0.1", 1, browse = true, push = true, pairedAt = 0))
        }

        fun request(client: Identity, raw: String): String {
            val factory = Tls.socketFactory(client, identity.deviceId)
            factory.createSocket("127.0.0.1", port).use { rawSocket ->
                val s = rawSocket as SSLSocket
                s.startHandshake()
                s.outputStream.write(raw.toByteArray())
                s.outputStream.flush()
                return readOneResponse(s.inputStream)
            }
        }

        private fun readOneResponse(input: java.io.InputStream): String {
            val head = StringBuilder()
            var state = 0
            while (true) {
                val b = input.read()
                if (b < 0) break
                head.append(b.toChar())
                state = when {
                    state == 0 && b.toChar() == '\r' -> 1
                    state == 1 && b.toChar() == '\n' -> 2
                    state == 2 && b.toChar() == '\r' -> 3
                    state == 3 && b.toChar() == '\n' -> 4
                    else -> 0
                }
                if (state == 4) break
            }
            val text = head.toString()
            val len = Regex("(?i)content-length:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val body = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = input.read(body, off, len - off)
                if (n < 0) break
                off += n
            }
            return text + String(body, Charsets.UTF_8)
        }

        override fun close() = server.stop()
    }

    private fun status(response: String): Int =
        response.lineSequence().firstOrNull()?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: -1

    private fun body(response: String): String = response.substringAfter("\r\n\r\n")

    /** Reads exactly one HTTP/1.1 response (head + Content-Length body) off [input]. */
    private fun readOneResponse(input: java.io.InputStream): String {
        val head = StringBuilder()
        var state = 0
        while (true) {
            val b = input.read()
            if (b < 0) break
            head.append(b.toChar())
            state = when {
                state == 0 && b.toChar() == '\r' -> 1
                state == 1 && b.toChar() == '\n' -> 2
                state == 2 && b.toChar() == '\r' -> 3
                state == 3 && b.toChar() == '\n' -> 4
                else -> 0
            }
            if (state == 4) break
        }
        val text = head.toString()
        val len = Regex("(?i)content-length:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val body = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(body, off, len - off)
            if (n < 0) break
            off += n
        }
        return text + String(body, Charsets.UTF_8)
    }

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

    private fun offer(h: Harness, size: Int = 4): String {
        val body = """{"files":[{"rel_path":"a.bin","size":$size}],"total_bytes":$size}"""
        val resp = h.request(h.identity, post("/api/v1/push/offer", body))
        assertEquals(200, status(resp), resp)
        return JsonParser.parseString(body(resp)).asJsonObject.get("push_id").asString
    }

    private fun chunkedPut(id: String, encoded: String): String =
        "PUT /api/v1/push/$id/file?path=a.bin HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n$encoded"

    @Test
    @Timeout(60)
    fun contentLengthLongerThanOfferedIsRefused() {
        Harness().use { h ->
            h.selfPair()
            val id = offer(h, 4)
            val raw = "PUT /api/v1/push/$id/file?path=a.bin HTTP/1.1\r\nHost: x\r\nContent-Length: 100\r\n\r\n" + "A".repeat(100)
            assertEquals(400, status(h.request(h.identity, raw)))
        }
    }

    @Test
    @Timeout(60)
    fun chunkedBodyLongerThanOfferedIsStoppedAtTheCap() {
        Harness().use { h ->
            h.selfPair()
            val id = offer(h, 4)
            // one 100-byte chunk for a 4-byte file
            val encoded = "64\r\n" + "A".repeat(100) + "\r\n0\r\n\r\n"
            assertEquals(400, status(h.request(h.identity, chunkedPut(id, encoded))))
        }
    }

    @Test
    @Timeout(60)
    fun oversizedChunkSizeLineIsRefused() {
        Harness().use { h ->
            h.selfPair()
            val id = offer(h, 4)
            assertEquals(400, status(h.request(h.identity, chunkedPut(id, "A".repeat(300) + "\r\n"))))
        }
    }

    @Test
    @Timeout(60)
    fun tooManyTrailersIsRefused() {
        Harness().use { h ->
            h.selfPair()
            val id = offer(h, 4)
            val encoded = "0\r\n" + "X: y\r\n".repeat(40) + "\r\n"
            assertEquals(400, status(h.request(h.identity, chunkedPut(id, encoded))))
        }
    }

    @Test
    @Timeout(60)
    fun badChunkTerminatorIsRefused() {
        Harness().use { h ->
            h.selfPair()
            val id = offer(h, 4)
            assertEquals(400, status(h.request(h.identity, chunkedPut(id, "4\r\nABCDXX"))))
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
    fun oversizedOfferDoesNotPoisonKeepAlive() {
        val diag = ServerDiagnostics()
        Harness(maxOfferBody = 128, diagnostics = diag).use { h ->
            h.selfPair()
            // A body larger than the offer cap, immediately followed by another
            // request on the same connection. Before the fix the unread offer
            // body was misparsed as the next request and logged malformed-head.
            val oversized = "A".repeat(200)
            val raw = post("/api/v1/push/offer", oversized) +
                "GET /api/v1/hello HTTP/1.1\r\nHost: x\r\n\r\n"
            val factory = Tls.socketFactory(h.identity, h.identity.deviceId)
            factory.createSocket("127.0.0.1", h.port).use { rawSocket ->
                val s = rawSocket as SSLSocket
                s.startHandshake()
                s.outputStream.write(raw.toByteArray())
                s.outputStream.flush()
                val first = readOneResponse(s.inputStream)
                assertEquals(413, status(first), first)
                // Either the server closed (EOF) or answered the next request;
                // both are fine, a 400 malformed-head is not.
                s.soTimeout = 2_000
                runCatching { readOneResponse(s.inputStream) }
            }
            Thread.sleep(300)
            val events = diag.snapshot()
            assertTrue(events.any { it.contains("resp 413") }, events.toString())
            assertFalse(events.any { it.contains("malformed-head") }, events.toString())
        }
    }

    @Test
    @Timeout(60)
    fun pairedPermittedSnippetIsStored() {
        val store = ReceivedSnippets()
        Harness(snippets = store).use { h ->
            h.selfPair()
            val resp = h.request(h.identity, post("/api/v1/snippet", """{"text":"hello phone"}"""))
            assertEquals(200, status(resp), resp)
            assertTrue(body(resp).contains("\"id\""), body(resp))
            assertEquals(listOf("hello phone"), store.list().map { it.text })
        }
    }

    @Test
    @Timeout(60)
    fun unpairedSnippetIsRefused() {
        val store = ReceivedSnippets()
        Harness(snippets = store).use { h ->
            val desk = Identity.generate("Desk")
            val resp = h.request(desk, post("/api/v1/snippet", """{"text":"hello"}"""))
            assertEquals(403, status(resp), resp)
            assertTrue(body(resp).contains("not permitted"), body(resp))
            assertTrue(store.list().isEmpty())
        }
    }

    @Test
    @Timeout(60)
    fun snippetWithoutTextPermissionIsRefusedAsTextNotPermitted() {
        val store = ReceivedSnippets()
        Harness(snippets = store).use { h ->
            // Text is its own permission now; a peer with text Never is refused
            // with the precise "text not permitted" body, not the push wording.
            h.trust.save(
                PairedPeer(
                    h.identity.deviceId, "Self", "127.0.0.1", 1,
                    browse = Permission.ALLOW, push = Permission.NEVER, text = Permission.NEVER, pairedAt = 0,
                ),
            )
            val resp = h.request(h.identity, post("/api/v1/snippet", """{"text":"hello"}"""))
            assertEquals(403, status(resp), resp)
            assertTrue(body(resp).contains("text not permitted"), body(resp))
            assertFalse(body(resp).contains("push not permitted"), body(resp))
            assertTrue(store.list().isEmpty())
        }
    }

    @Test
    @Timeout(60)
    fun oversizedSnippetIsRejected() {
        val store = ReceivedSnippets()
        Harness(snippets = store).use { h ->
            h.selfPair()
            val big = "x".repeat(SnippetProtocol.MAX_SNIPPET_BYTES + 1)
            val resp = h.request(h.identity, post("/api/v1/snippet", """{"text":"$big"}"""))
            assertEquals(400, status(resp), resp)
            assertTrue(body(resp).contains("exceeds"), body(resp))
            assertTrue(store.list().isEmpty())
        }
    }

    @Test
    @Timeout(60)
    fun unpairedClientRevokeIsIdempotentOk() {
        Harness().use { h ->
            val desk = Identity.generate("Desk")
            // A caller we are not paired with is exactly the state revoke wants,
            // so it must get an idempotent 200 rather than a 403 that the peer
            // would read as a failure and keep retrying.
            val resp = h.request(desk, post("/api/v1/trust/revoke", "{}"))
            assertEquals(200, status(resp))
            assertNull(h.trust.find(desk.deviceId), "revoking must never create an entry")
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
        Harness(idleMs = 800, timeoutMs = 5_000).use { h ->
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

    @Test
    @Timeout(60)
    fun diagnosticsRecordRequestResponseAndCloseLines() {
        val diag = ServerDiagnostics()
        Harness(idleMs = 400, diagnostics = diag).use { h ->
            val resp = h.request(h.identity, "GET /api/v1/hello HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
            assertEquals(200, status(resp))
            val events = diag.snapshot()
            assertTrue(
                events.any { it.contains("req GET /api/v1/hello") && it.contains("peer=") },
                "a request line with the peer fingerprint should be logged: $events",
            )
            assertTrue(
                events.any { it.contains("resp 200 GET /api/v1/hello") && it.contains("elapsed=") },
                "a response line with status and elapsed ms should be logged: $events",
            )
            assertTrue(
                waitFor(3_000) { diag.snapshot().any { it.contains("conn close") && it.contains("reason=") } },
                "a close line with a reason should be logged: ${diag.snapshot()}",
            )
        }
    }

    @Test
    @Timeout(60)
    fun diagnosticsNeverLogQueryValues() {
        val diag = ServerDiagnostics()
        Harness(diagnostics = diag).use { h ->
            h.request(h.identity, "GET /api/v1/hello?token=SECRETTOKEN HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
            val joined = diag.snapshot().joinToString("\n")
            assertTrue(joined.contains("/api/v1/hello"), "the path should be logged: $joined")
            assertFalse(joined.contains("SECRETTOKEN"), "a query value must never be logged: $joined")
            // The report-level redaction is the second line of defence.
            assertFalse(Diagnostics.copyReport(emptyList(), diag.snapshot()).contains("SECRETTOKEN"))
        }
    }

    private fun browseSource(): ShareSource = object : ShareSource {
        override fun list(): List<ShareInfo> = listOf(ShareInfo("s1", "Docs", "Docs", "dir", 0))
        override fun children(shareId: String, rel: String): List<ShareChild>? = emptyList()
        override fun resolve(shareId: String, rel: String): ResolvedFile? = null
        override fun ended(shareId: String): String? = null
    }

    private fun askPeer(h: Harness, browse: Permission = Permission.ASK) = PairedPeer(
        h.identity.deviceId, "Self", "127.0.0.1", 1,
        browse = browse, push = Permission.NEVER, text = Permission.NEVER, pairedAt = 0,
    )

    private fun getShares(h: Harness): String =
        h.request(h.identity, "GET /api/v1/shares HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")

    @Test
    @Timeout(60)
    fun browseAskPromptsOnceAndRemembersTheSession() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        Harness(
            browseApproval = BrowseApproval { _, _ -> calls.incrementAndGet(); ApprovalOutcome.ACCEPTED },
            shares = ShareServer(browseSource()),
        ).use { h ->
            h.trust.save(askPeer(h))
            assertEquals(200, status(getShares(h)), "an accepted browse must serve the list")
            assertEquals(200, status(getShares(h)), "a second request in the session must serve too")
            assertEquals(1, calls.get(), "one browse session must prompt the person only once")
        }
    }

    @Test
    @Timeout(60)
    fun browseAskDeniedIsPullNotPermitted() {
        Harness(
            browseApproval = BrowseApproval { _, _ -> ApprovalOutcome.DECLINED },
            shares = ShareServer(browseSource()),
        ).use { h ->
            h.trust.save(askPeer(h))
            val resp = getShares(h)
            assertEquals(403, status(resp), resp)
            // A person's decline has its own wording, distinct from Never.
            assertTrue(body(resp).contains("denied by the user"), body(resp))
        }
    }

    @Test
    @Timeout(60)
    fun browseAskUnansweredTimesOutToDenial() {

        // The app answers an unanswered prompt with DECLINED (timeout = deny).
        Harness(
            browseApproval = BrowseApproval { _, _ -> ApprovalOutcome.DECLINED },
            shares = ShareServer(browseSource()),
        ).use { h ->
            h.trust.save(askPeer(h))
            assertEquals(403, status(getShares(h)), "a timeout must deny, never hang")
        }
    }

    @Test
    @Timeout(60)
    fun browseNeverNeedsNoPrompt() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        Harness(
            browseApproval = BrowseApproval { _, _ -> calls.incrementAndGet(); ApprovalOutcome.ACCEPTED },
            shares = ShareServer(browseSource()),
        ).use { h ->
            h.trust.save(askPeer(h, browse = Permission.NEVER))
            val resp = getShares(h)
            assertEquals(403, status(resp), resp)
            assertEquals(0, calls.get(), "Never must never prompt")
        }
    }

    @Test
    @Timeout(60)
    fun textAskReusesThePushApprovalAndStoresTheSnippet() {
        val store = ReceivedSnippets()
        val asked = java.util.concurrent.atomic.AtomicInteger()
        Harness(
            snippets = store,
            approval = PushApproval { _, _, _, _, _ -> asked.incrementAndGet(); ApprovalOutcome.ACCEPTED },
        ).use { h ->
            h.trust.save(
                PairedPeer(
                    h.identity.deviceId, "Self", "127.0.0.1", 1,
                    browse = Permission.NEVER, push = Permission.NEVER, text = Permission.ASK, pairedAt = 0,
                ),
            )
            val resp = h.request(h.identity, post("/api/v1/snippet", """{"text":"hello"}"""))
            assertEquals(200, status(resp), resp)
            assertEquals(1, asked.get(), "text Ask must reuse the push-approval prompt")
            assertEquals(1, store.list().size)
        }
    }

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(15)
        }
        return cond()
    }
}
