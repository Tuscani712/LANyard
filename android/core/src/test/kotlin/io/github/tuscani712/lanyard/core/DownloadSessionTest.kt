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
    @Timeout(120)
    fun resumesFromExistingHalf() {
        assumeTrue(bin != null)
        GoPeer(bin!!).use { peer ->
            val (identity, fingerprint) = pairPeer(peer)
            val shareDir = Files.createTempDirectory("share").toFile()
            val bytes = ByteArray(6 * 1024 * 1024 + 3).also { java.util.Random(9).nextBytes(it) }
            File(shareDir, "half.bin").writeBytes(bytes)
            val shareId = peer.addShare(shareDir.absolutePath, "resume").str("share_id")

            val out = File(Files.createTempDirectory("dl").toFile(), "half.bin")
            out.writeBytes(bytes.copyOfRange(0, bytes.size / 2)) // the first half is already on disk

            val client = PeerClient("127.0.0.1", peer.peerPort, identity, fingerprint)
            val result = DownloadSession(client).download(
                shareId, "",
                targetFor = {
                    DownloadTarget(
                        existingSize = out.length(),
                        openAt = { FileOutputStream(out, true) },        // append the remainder
                        openExisting = { java.io.FileInputStream(out) }, // hash the present prefix
                    )
                },
            )

            assertTrue(result is DownloadResult.Done, "expected Done, got $result")
            assertEquals(sha256Hex(bytes), sha256Hex(out.readBytes()), "the resumed file must match")
        }
    }

    @Test
    fun successRenamesThePartToTheFinalName() {
        val dir = Files.createTempDirectory("dl").toFile()
        val bytes = "hello world\n".toByteArray()
        val result = DownloadSession(InMemoryReader("a.txt", bytes)).download("s", "", { partTarget(dir, "a.txt") })

        assertTrue(result is DownloadResult.Done, "expected Done, got $result")
        assertEquals("hello world\n", File(dir, "a.txt").readText())
        assertFalse(File(dir, "a.txt.part").exists(), "the temporary .part must be gone after commit")
    }

    @Test
    fun cancelDeletesThePartAndLeavesNoFinalFile() {
        val dir = Files.createTempDirectory("dl").toFile()
        val cancel = AtomicBoolean(false)
        val result = DownloadSession(EndlessReader("big.bin", 8L * 1024 * 1024)).download(
            "s", "",
            targetFor = { partTarget(dir, "big.bin") },
            onProgress = { _, _, received, _ -> if (received > 512 * 1024) cancel.set(true) },
            isCancelled = { cancel.get() },
        )

        assertTrue(result is DownloadResult.Cancelled, "expected Cancelled, got $result")
        assertFalse(File(dir, "big.bin.part").exists(), "a cancel must delete the partial")
        assertFalse(File(dir, "big.bin").exists(), "a cancel must never leave a normal-looking file")
    }

    @Test
    fun aFailedPullKeepsThePartButNoFinalFile() {
        val dir = Files.createTempDirectory("dl").toFile()
        val reader = InMemoryReader("a.bin", "real\n".toByteArray(), reportedHash = "00".repeat(32))
        val result = DownloadSession(reader).download("s", "", { partTarget(dir, "a.bin") })

        assertTrue(result is DownloadResult.HashMismatch, "expected HashMismatch, got $result")
        assertTrue(File(dir, "a.bin.part").isFile, "a failed pull keeps a clearly-named .part")
        assertFalse(File(dir, "a.bin").exists(), "a failed pull must not leave a final-named file")
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

    /** A destination that writes `<name>.part` and renames it on commit. */
    private fun partTarget(dir: File, name: String): DownloadTarget {
        val part = File(dir, "$name.part")
        val final = File(dir, name)
        return DownloadTarget(
            openAt = { FileOutputStream(part, false) },
            commit = { if (!part.renameTo(final)) throw java.io.IOException("rename failed") },
            discard = { part.delete() },
        )
    }

    /** A reader serving one in-memory file, with an optionally wrong digest. */
    private class InMemoryReader(
        private val rel: String,
        private val bytes: ByteArray,
        private val reportedHash: String = sha256Hex(bytes),
    ) : ShareReader {
        override fun manifestFiles(shareId: String, path: String): JsonObject = oneFile(rel, bytes.size.toLong())
        override fun openFileStream(shareId: String, path: String, rangeFrom: Long): InputStream {
            val from = rangeFrom.coerceIn(0L, bytes.size.toLong()).toInt()
            return ByteArrayInputStream(bytes.copyOfRange(from, bytes.size))
        }
        override fun wholeFileHash(shareId: String, path: String): String = reportedHash
        override fun reportComplete(shareId: String, verified: List<Pair<String, String>>): Boolean = true
    }

    /** A reader whose body never ends, for exercising a mid-transfer cancel. */
    private class EndlessReader(private val rel: String, private val size: Long) : ShareReader {
        override fun manifestFiles(shareId: String, path: String): JsonObject = oneFile(rel, size)
        override fun openFileStream(shareId: String, path: String, rangeFrom: Long): InputStream =
            object : InputStream() {
                override fun read(): Int = 0
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    java.util.Arrays.fill(b, off, off + len, 0)
                    return len
                }
            }
        override fun wholeFileHash(shareId: String, path: String): String = ""
        override fun reportComplete(shareId: String, verified: List<Pair<String, String>>): Boolean = true
    }

    private companion object {
        fun oneFile(path: String, size: Long): JsonObject = JsonObject().apply {
            add("files", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("path", path)
                    addProperty("name", path)
                    addProperty("size", size)
                    addProperty("etag", "")
                })
            })
            addProperty("total_bytes", size)
            addProperty("count", 1)
        }
    }

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
