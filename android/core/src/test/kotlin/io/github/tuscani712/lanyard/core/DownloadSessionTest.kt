package io.github.tuscani712.lanyard.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [DownloadSession] against the real Go peer, plus fakes for the hash-mismatch
 * and path-traversal paths. Live tests skip unless `LANYARD_BIN` is set.
 */
class DownloadSessionTest {
    private val bin: String? = System.getenv("LANYARD_BIN")?.takeIf { it.isNotBlank() }

    @Test
    @Timeout(120)
    fun downloadsNestedShareWithCommaFilenameAndLargeFile() {
        assumeTrue(bin != null)
        GoPeer(bin!!).use { peer ->
            val (identity, fingerprint) = pairPeer(peer)
            val shareDir = Files.createTempDirectory("share").toFile()
            File(shareDir, "sub dir").mkdirs()
            File(shareDir, "sub dir/a, b.txt").writeText("comma file\n")
            File(shareDir, "top.txt").writeText("top\n")
            val big = ByteArray(4 * 1024 * 1024 + 5).also { java.util.Random(11).nextBytes(it) }
            File(shareDir, "sub dir/big.bin").writeBytes(big)
            val share = peer.addShare(shareDir.absolutePath, "nested")
            val shareId = share.str("share_id")

            val outDir = Files.createTempDirectory("dl").toFile()
            val client = PeerClient("127.0.0.1", peer.peerPort, identity, fingerprint)
            val result = DownloadSession(client).download(shareId, "", targetFor(outDir))

            assertTrue(result is DownloadResult.Done, "expected Done, got $result")
            assertEquals("comma file\n", File(outDir, "sub dir/a, b.txt").readText())
            assertEquals("top\n", File(outDir, "top.txt").readText())
            assertEquals(sha256Hex(big), sha256Hex(File(outDir, "sub dir/big.bin").readBytes()))
        }
    }

    @Test
    @Timeout(120)
    fun cancelMidTransferStops() {
        assumeTrue(bin != null)
        GoPeer(bin!!).use { peer ->
            val (identity, fingerprint) = pairPeer(peer)
            val shareDir = Files.createTempDirectory("share").toFile()
            val big = ByteArray(16 * 1024 * 1024).also { java.util.Random(5).nextBytes(it) }
            File(shareDir, "big.bin").writeBytes(big)
            val shareId = peer.addShare(shareDir.absolutePath, "big").str("share_id")

            val outDir = Files.createTempDirectory("dl").toFile()
            val client = PeerClient("127.0.0.1", peer.peerPort, identity, fingerprint)
            val cancel = AtomicBoolean(false)
            val result = DownloadSession(client).download(
                shareId, "",
                targetFor(outDir),
                onProgress = { _, _, received, _ -> if (received > 1_000_000) cancel.set(true) },
                isCancelled = { cancel.get() },
            )

            assertTrue(result is DownloadResult.Cancelled, "expected Cancelled, got $result")
        }
    }

    @Test
    @Timeout(60)
    fun hashMismatchIsDetected() {
        assumeTrue(bin != null)
        GoPeer(bin!!).use { peer ->
            val (identity, fingerprint) = pairPeer(peer)
            val shareDir = Files.createTempDirectory("share").toFile()
            File(shareDir, "hello.txt").writeText("the real contents\n")
            val shareId = peer.addShare(shareDir.absolutePath, "one").str("share_id")

            val real = PeerClient("127.0.0.1", peer.peerPort, identity, fingerprint)
            val corrupting = CorruptingReader(real, "hello.txt")
            val outDir = Files.createTempDirectory("dl").toFile()

            val result = DownloadSession(corrupting).download(shareId, "", targetFor(outDir))

            assertTrue(result is DownloadResult.HashMismatch, "expected HashMismatch, got $result")
        }
    }

    @Test
    fun rejectsPathTraversalFromManifest() {
        val reader = FakeReader(
            manifest = jsonManifest(listOf(Triple("../evil.txt", "evil.txt", 1L))),
        )
        val result = DownloadSession(reader).download("s", "", targetFor(Files.createTempDirectory("dl").toFile()))
        assertTrue(result is DownloadResult.UnsafePath, "expected UnsafePath, got $result")
    }

    @Test
    fun rejectsAbsoluteAndEmptySegmentPaths() {
        assertTrue(DownloadSession.isSafeRelPath(""))
        assertTrue(DownloadSession.isSafeRelPath("a/b.txt"))
        assertFalse(DownloadSession.isSafeRelPath("/abs.txt"))
        assertFalse(DownloadSession.isSafeRelPath("a//b.txt"))
        assertFalse(DownloadSession.isSafeRelPath("a/../b"))
        assertFalse(DownloadSession.isSafeRelPath("a/./b"))
        assertFalse(DownloadSession.isSafeRelPath("a\\b"))
        assertFalse(DownloadSession.isSafeRelPath("a/C:/b"))
    }

    // --- helpers ---

    private fun targetFor(dir: File): (ManifestFile) -> DownloadTarget = { file ->
        val dest = File(dir, file.path.ifEmpty { file.name })
        dest.parentFile?.mkdirs()
        val existing = if (dest.isFile) dest.length() else 0L
        DownloadTarget(
            existingSize = existing,
            openAt = { FileOutputStream(dest, false) },
        )
    }

    private fun pairPeer(peer: GoPeer): Pair<Identity, String> {
        val identity = Identity.generate("Android")
        val storeDir = Files.createTempDirectory("trust").toFile()
        val store = JsonFileTrustStore(File(storeDir, "peers.json"))
        val payload = peer.pairPayload()
        val fingerprint = payload.str("fp")
        val link = peer.buildLink(fingerprint, listOf("127.0.0.1:${peer.peerPort}"), payload.str("nonce"))
        val acceptor = Thread { peer.acceptPending(identity.deviceId) }
        acceptor.start()
        val result = PairingFlow.pair(link, identity, "Android test", store)
        acceptor.join()
        assertTrue(result is PairResult.Paired, "pairing failed: $result")
        return identity to fingerprint
    }

    private fun jsonManifest(files: List<Triple<String, String, Long>>): JsonObject {
        val arr = JsonArray()
        files.forEach { (path, name, size) ->
            arr.add(JsonObject().apply {
                addProperty("path", path)
                addProperty("name", name)
                addProperty("size", size)
                addProperty("etag", "")
            })
        }
        return JsonObject().apply {
            add("files", arr)
            addProperty("total_bytes", files.sumOf { it.third })
            addProperty("count", files.size)
        }
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString ?: ""

    /** Wraps a real client, corrupting the bytes of one file. */
    private class CorruptingReader(private val real: ShareReader, private val path: String) : ShareReader by real {
        override fun openFileStream(shareId: String, path: String, rangeFrom: Long): InputStream {
            val stream = real.openFileStream(shareId, path, rangeFrom)
            if (path != this.path) return stream
            val bytes = stream.use { it.readBytes() }
            if (bytes.isNotEmpty()) bytes[0] = (bytes[0].toInt() xor 0xFF).toByte()
            return ByteArrayInputStream(bytes)
        }
    }

    /** A reader that always returns one crafted manifest. */
    private class FakeReader(private val manifest: JsonObject) : ShareReader {
        override fun manifestFiles(shareId: String, path: String): JsonObject = manifest
        override fun openFileStream(shareId: String, path: String, rangeFrom: Long): InputStream =
            ByteArrayInputStream(ByteArray(0))
        override fun wholeFileHash(shareId: String, path: String): String = ""
        override fun reportComplete(shareId: String, verified: List<Pair<String, String>>): Boolean = false
    }
}
