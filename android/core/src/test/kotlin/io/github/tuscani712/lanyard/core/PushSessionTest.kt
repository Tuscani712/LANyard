package io.github.tuscani712.lanyard.core

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [PushSession] against the real Go peer. Skipped unless `LANYARD_BIN` is set.
 */
class PushSessionTest {
    private val bin: String? = System.getenv("LANYARD_BIN")?.takeIf { it.isNotBlank() }

    @Test
    @Timeout(120)
    fun streamsFileWithMatchingSha() {
        assumeTrue(bin != null)
        GoPeer(bin!!).use { peer ->
            val (identity, fingerprint) = pairPeer(peer)
            val bytes = ByteArray(3 * 1024 * 1024 + 17).also { Random(7).nextBytes(it) }
            val src = File(peer.dataDir, "source.bin").apply { writeBytes(bytes) }
            val client = PeerClient("127.0.0.1", peer.peerPort, identity, fingerprint)

            val result = PushSession(client).push(
                listOf(PushSource("big.bin", bytes.size.toLong(), src.lastModified()) { FileInputStream(src) }),
            )

            assertTrue(result is PushResult.Sent, "expected Sent, got $result")
            assertEquals(bytes.size.toLong(), (result as PushResult.Sent).bytes)
            val received = peer.inboxFile("big.bin")
            awaitFile(received)
            assertEquals(sha256Hex(bytes), sha256Hex(received.readBytes()), "the received digest must match")
        }
    }

    @Test
    @Timeout(60)
    fun pushWithoutPermissionIsRefused() {
        assumeTrue(bin != null)
        GoPeer(bin!!).use { peer ->
            val (identity, fingerprint) = pairPeer(peer, push = false)
            val client = PeerClient("127.0.0.1", peer.peerPort, identity, fingerprint)

            val result = PushSession(client).push(
                listOf(PushSource("x.txt", 5, 0) { ByteArrayInputStream("hello".toByteArray()) }),
            )

            assertTrue(result is PushResult.Refused, "expected Refused, got $result")
        }
    }

    @Test
    @Timeout(120)
    fun cancelMidTransferLeavesNoFinalizedFile() {
        assumeTrue(bin != null)
        GoPeer(bin!!).use { peer ->
            val (identity, fingerprint) = pairPeer(peer)
            val bytes = ByteArray(8 * 1024 * 1024).also { Random(3).nextBytes(it) }
            val src = File(peer.dataDir, "cancel-src.bin").apply { writeBytes(bytes) }
            val client = PeerClient("127.0.0.1", peer.peerPort, identity, fingerprint)
            val cancel = AtomicBoolean(false)

            val result = PushSession(client).push(
                listOf(PushSource("cancel.bin", bytes.size.toLong(), src.lastModified()) { FileInputStream(src) }),
                onProgress = { _, sent, _ -> if (sent > 1_000_000) cancel.set(true) },
                isCancelled = { cancel.get() },
            )

            assertTrue(result is PushResult.Cancelled, "expected Cancelled, got $result")
            Thread.sleep(500)
            assertFalse(peer.inboxFile("cancel.bin").exists(), "a cancelled push must not finalize a file")
        }
    }

    @Test
    @Timeout(60)
    fun snippetArrives() {
        assumeTrue(bin != null)
        GoPeer(bin!!).use { peer ->
            val (identity, fingerprint) = pairPeer(peer)
            val client = PeerClient("127.0.0.1", peer.peerPort, identity, fingerprint)

            client.sendSnippet("hello from the push test")

            val deadline = System.currentTimeMillis() + 10_000
            var found = false
            while (System.currentTimeMillis() < deadline && !found) {
                found = peer.ui.get("/api/snippets").asJsonArray.map { it.asJsonObject }
                    .any { it.str("text").contains("from the push test") }
                if (!found) Thread.sleep(200)
            }
            assertTrue(found, "the snippet must reach the Go inbox")
        }
    }

    private fun pairPeer(peer: GoPeer, browse: Boolean = true, push: Boolean = true): Pair<Identity, String> {
        val identity = Identity.generate("Android")
        val storeDir = Files.createTempDirectory("trust").toFile()
        val store = JsonFileTrustStore(File(storeDir, "peers.json"))
        val payload = peer.pairPayload()
        val fingerprint = payload.str("fp")
        val link = peer.buildLink(fingerprint, listOf("127.0.0.1:${peer.peerPort}"), payload.str("nonce"))

        val acceptor = Thread { peer.acceptPending(identity.deviceId, browse, push) }
        acceptor.start()
        val result = PairingFlow.pair(link, identity, "Android test", store)
        acceptor.join()

        assertTrue(result is PairResult.Paired, "pairing failed: $result")
        return identity to fingerprint
    }

    private fun awaitFile(file: File, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (file.isFile && file.length() > 0) return
            Thread.sleep(100)
        }
        error("${file.absolutePath} never appeared")
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString ?: ""
}
