package io.github.tuscani712.lanyard.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * The spike: does an Ed25519 mTLS client (Conscrypt) interoperate with the real
 * Go peer service, and can it complete the pairing and push flows?
 *
 * Set `LANYARD_BIN` to a built `lanyard` binary (scripts/test-core.sh does).
 * The test is skipped when it is not set, so a plain `gradlew test` still runs.
 */
class SpikeTest {

    @Test
    @Timeout(90)
    fun ed25519MtlsInterop() {
        val bin = System.getenv("LANYARD_BIN")?.takeIf { it.isNotBlank() }
        assumeTrue(bin != null, "LANYARD_BIN is not set; skipping the live spike")

        val dataDir = Files.createTempDirectory("lanyard-core").toFile()
        val peerPort = freePort()
        val uiPort = freePort()
        val process = ProcessBuilder(
            bin, "--data-dir", dataDir.absolutePath,
            "--no-browser", "--no-tray", "--port", "$peerPort", "--ui-port", "$uiPort",
        ).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(File(dataDir, "process.log")))
            .start()

        try {
            val runInfo = awaitRunInfo(dataDir)
            val base = runInfo.str("base")
            val token = URI(runInfo.str("ui_url")).rawQuery
                .split("&").map { it.split("=", limit = 2) }
                .first { it[0] == "t" }[1]
            val ui = UiClient(base, token)

            // The QR invite carries the peer's fingerprint and a one-time nonce.
            val payload = ui.get("/api/pair/payload").asJsonObject
            val peerFingerprint = payload.str("fp")
            val invite = payload.str("nonce")
            assertTrue(peerFingerprint.length == 64, "the payload must carry a full fingerprint")

            val identity = Identity.generate("Android test")

            // 1. mTLS handshake + /hello returns the expected fingerprint.
            val client = PeerClient("127.0.0.1", peerPort, identity, peerFingerprint)
            val hello = client.hello()
            assertEquals(peerFingerprint, hello.fingerprint, "hello() must return the peer's fingerprint")

            // 2. A wrong expected fingerprint is refused at the handshake.
            val wrongClient = PeerClient("127.0.0.1", peerPort, identity, "00".repeat(32))
            assertFingerprintRejected { wrongClient.hello() }

            // 3. Pair through the QR invite, accepted on the Go side via the UI API.
            val session = client.startSession(
                mode = "pair", name = "Android test", deviceId = "android-dev",
                nonce = randomHex(16), requested = Permissions(browse = true), invite = invite,
            )
            val remoteId = session.str("session_id")
            val incoming = ui.get("/api/sessions").asJsonArray
                .map { it.asJsonObject }
                .first { it.str("peer_fp") == identity.deviceId && it.str("status") == "pending" }
            val goSessionId = incoming.str("id")
            ui.post(
                "/api/sessions/$goSessionId/accept",
                """{"permissions":{"browse":true,"push":true},"keep_connected":false}""",
            )
            awaitStatus(client, remoteId, "accepted")
            client.confirmSession(remoteId)
            awaitStatus(client, remoteId, "active")

            val paired = ui.get("/api/sessions").asJsonArray.map { it.asJsonObject }
                .first { it.str("id") == goSessionId }
            assertTrue(paired.get("via_qr")?.asBoolean == true, "the Go session must be marked via_qr")

            // 4a. A bad invite is a hard 403.
            assertPeerStatus(403) {
                client.startSession("pair", "x", "y", randomHex(16), Permissions(), invite = "ab".repeat(32))
            }
            // 4b. A reused invite is a hard 403.
            assertPeerStatus(403) {
                client.startSession("pair", "x", "y", randomHex(16), Permissions(), invite = invite)
            }

            // 5. Push a small file; the Go Inbox must contain it with a matching digest.
            val fileBytes = "hello from android\n".toByteArray()
            val offer = client.pushOffer(listOf(PushFileRequest("hello.txt", fileBytes.size.toLong(), 0)))
            assertTrue(offer.accepted, "the push offer must be accepted")
            client.pushFileWithSha(offer.pushId, "hello.txt", fileBytes, sha256Hex(fileBytes))
            client.pushCompleteAll(offer.pushId)
            val received = File(dataDir, "Inbox/hello.txt")
            awaitFile(received)
            assertEquals(sha256Hex(fileBytes), sha256Hex(received.readBytes()), "pushed file digest must match")

            // 6. A snippet arrives.
            client.sendSnippet("<b>hi</b> from android")
            val snippets = ui.get("/api/snippets").asJsonArray.map { it.asJsonObject }
            assertTrue(
                snippets.any { it.str("text").contains("from android") },
                "the snippet must reach the Go inbox",
            )
        } finally {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    // --- helpers ---

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun randomHex(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }

    private fun awaitRunInfo(dir: File, timeoutMs: Long = 20_000): JsonObject {
        val runFile = File(dir, "run.json")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runFile.isFile) {
                try {
                    val json = JsonParser.parseString(runFile.readText()).asJsonObject
                    if (json.str("base").isNotEmpty()) return json
                } catch (_: Exception) {
                    // still being written
                }
            }
            Thread.sleep(100)
        }
        error("the Go server did not write run.json; see ${File(dir, "process.log")}")
    }

    private fun awaitStatus(client: PeerClient, sessionId: String, want: String, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            last = client.sessionStatus(sessionId).str("status")
            if (last == want) return
            Thread.sleep(100)
        }
        error("session $sessionId never reached $want (last: $last)")
    }

    private fun awaitFile(file: File, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (file.isFile && file.length() > 0) return
            Thread.sleep(100)
        }
        error("${file.absolutePath} never appeared")
    }

    private fun assertFingerprintRejected(block: () -> Unit) {
        try {
            block()
            error("a wrong expected fingerprint must be rejected")
        } catch (e: Exception) {
            val text = generateSequence<Throwable>(e) { it.cause }.joinToString(" | ") { it.message ?: "" }
            assertTrue(
                text.contains("fingerprint", ignoreCase = true),
                "expected a fingerprint mismatch, got: ${e::class.java.name}: $text",
            )
        }
    }

    private fun assertPeerStatus(expected: Int, block: () -> Unit) {
        try {
            block()
            error("expected HTTP $expected")
        } catch (e: PeerStatusException) {
            assertEquals(expected, e.code, "expected HTTP $expected, got ${e.code}: ${e.body}")
        }
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString ?: ""
}

/** A minimal client for the loopback UI API used to accept the pairing. */
private class UiClient(private val base: String, private val token: String) {
    fun get(path: String): JsonElement = send("GET", path, null)

    fun post(path: String, body: String): JsonElement = send("POST", path, body)

    private fun send(method: String, path: String, body: String?): JsonElement {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("Cookie", "lany=$token")
        if (method != "GET") {
            conn.setRequestProperty("Origin", base)
            conn.setRequestProperty("Content-Type", "application/json")
        }
        conn.connectTimeout = 5_000
        conn.readTimeout = 15_000
        if (body != null) {
            val bytes = body.toByteArray()
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
        }
        val status = conn.responseCode
        val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        check(status in 200..299) { "UI $method $path -> $status: $text" }
        return JsonParser.parseString(text)
    }
}
