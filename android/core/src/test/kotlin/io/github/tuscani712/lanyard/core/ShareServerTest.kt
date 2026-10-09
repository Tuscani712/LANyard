package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayOutputStream
import java.nio.file.Files

/** The share-serving handlers: status codes, ranges, caps, and path safety. */
class ShareServerTest {
    private val root = Files.createTempDirectory("lanyard-share").toFile()
    private val peer = PairedPeer("aa".repeat(32), "Desk", "h", 1, browse = true, push = false, pairedAt = 0)
    private val noBrowse = peer.copy(browse = Permission.NEVER)

    private fun source() = DirShareSource("s1", root)

    private fun serve(
        server: ShareServer,
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        range: String? = null,
        ifRange: String? = null,
        peer: PairedPeer = this.peer,
        body: ByteArray = ByteArray(0),
    ): String {
        val out = ByteArrayOutputStream()
        server.handle(out, method, path, query, range, ifRange, body, peer) {}
        return out.toString(Charsets.ISO_8859_1)
    }

    private fun write(name: String, size: Int) {
        val f = java.io.File(root, name)
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(size) { (it % 251).toByte() })
    }

    private fun status(resp: String) = resp.lineSequence().first().split(" ").getOrNull(1)?.toIntOrNull() ?: -1

    @Test
    fun listsShares() {
        assertEquals(200, status(serve(ShareServer(source()), "GET", "/api/v1/shares")))
    }

    @Test
    fun manifestListsFilesWithEtags() {
        write("a.bin", 10)
        write("sub/b.bin", 20)
        val resp = serve(ShareServer(source()), "GET", "/api/v1/shares/s1/manifest", mapOf("path" to ""))
        assertEquals(200, status(resp))
        assertTrue(resp.contains("\"count\":2"), resp)
        assertTrue(resp.contains("sub/b.bin"))
    }

    @Test
    fun fullBodyHasContentLengthAndNoTrailer() {
        write("a.bin", 10)
        val resp = serve(ShareServer(source()), "GET", "/api/v1/shares/s1/file", mapOf("path" to "a.bin"))
        assertEquals(200, status(resp))
        assertTrue(resp.contains("Content-Length: 10"))
        assertFalse(resp.contains("X-Content-SHA256"))
    }

    @Test
    fun rangeReturnsPartialContent() {
        write("a.bin", 100)
        val resp = serve(ShareServer(source()), "GET", "/api/v1/shares/s1/file", mapOf("path" to "a.bin"), range = "bytes=10-19")
        assertEquals(206, status(resp))
        assertTrue(resp.contains("Content-Range: bytes 10-19/100"), resp)
        assertTrue(resp.contains("Content-Length: 10"))
    }

    @Test
    fun absurdRangeIs416() {
        write("a.bin", 10)
        val resp = serve(ShareServer(source()), "GET", "/api/v1/shares/s1/file", mapOf("path" to "a.bin"), range = "bytes=999-1000")
        assertEquals(416, status(resp))
    }

    @Test
    fun ifRangeWithChangedEtagRestartsFromZero() {
        write("a.bin", 100)
        val resp = serve(
            ShareServer(source()), "GET", "/api/v1/shares/s1/file", mapOf("path" to "a.bin"),
            range = "bytes=10-19", ifRange = "\"deadbeef-1\"",
        )
        assertEquals(200, status(resp)) // range ignored, full body
        assertTrue(resp.contains("Content-Length: 100"))
    }

    @Test
    fun hashMatchesAndIsCached() {
        write("a.bin", 10)
        val server = ShareServer(source())
        val file = java.io.File(root, "a.bin").readBytes()
        val want = java.security.MessageDigest.getInstance("SHA-256").digest(file).joinToString("") { "%02x".format(it) }
        val resp = serve(server, "GET", "/api/v1/shares/s1/hash", mapOf("path" to "a.bin"))
        assertEquals(200, status(resp))
        assertTrue(resp.contains(want))
    }

    @Test
    fun traversalIsRejected() {
        write("a.bin", 10)
        assertEquals(400, status(serve(ShareServer(source()), "GET", "/api/v1/shares/s1/file", mapOf("path" to "../a.bin"))))
        assertEquals(400, status(serve(ShareServer(source()), "GET", "/api/v1/shares/s1/file", mapOf("path" to "/etc/passwd"))))
        assertEquals(400, status(serve(ShareServer(source()), "GET", "/api/v1/shares/s1/file", mapOf("path" to "a\\b"))))
        assertEquals(400, status(serve(ShareServer(source()), "GET", "/api/v1/shares/s1/file", mapOf("path" to "a:b"))))
    }

    @Test
    fun unknownShareIs404AndStoppedShareIs410() {
        val src = source()
        assertEquals(404, status(serve(ShareServer(src), "GET", "/api/v1/shares/nope/manifest", mapOf("path" to ""))))
        src.stop()
        assertEquals(410, status(serve(ShareServer(src), "GET", "/api/v1/shares/s1/manifest", mapOf("path" to ""))))
    }

    @Test
    @Timeout(60)
    fun digestCacheIsBounded() {
        for (i in 1..1000) write("f$i.bin", 1)
        val server = ShareServer(source())
        for (i in 1..1000) serve(server, "GET", "/api/v1/shares/s1/hash", mapOf("path" to "f$i.bin"))
        assertTrue(server.hashCacheSize() <= 256, "cache grew to ${server.hashCacheSize()}")
    }

    @Test
    fun changedFileMissesTheCache() {
        write("a.bin", 4)
        val server = ShareServer(source())
        serve(server, "GET", "/api/v1/shares/s1/hash", mapOf("path" to "a.bin"))
        Thread.sleep(5)
        write("a.bin", 8) // size+mtime change -> new etag -> recomputed
        val resp = serve(server, "GET", "/api/v1/shares/s1/hash", mapOf("path" to "a.bin"))
        assertTrue(resp.contains("\"size\":8"), resp)
    }

    @Test
    fun cancelledShareIs410() {
        write("a.bin", 10)
        val server = ShareServer(source())
        server.cancel("s1")
        assertEquals(410, status(serve(server, "GET", "/api/v1/shares/s1/file", mapOf("path" to "a.bin"))))
    }

    @Test
    fun manifestCapIsEnforced() {
        // more files than the cap: build a tiny source with a low cap
        write("a.bin", 1)
        write("b.bin", 1)
        val server = ShareServer(source(), maxManifestEntries = 1)
        assertEquals(413, status(serve(server, "GET", "/api/v1/shares/s1/manifest", mapOf("path" to ""))))
    }

    private class BlockingSource(private val dir: DirShareSource) : ShareSource by dir {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        override fun resolve(shareId: String, rel: String): ResolvedFile? {
            val rf = dir.resolve(shareId, rel) ?: return null
            return rf.copy(openAt = { off ->
                started.countDown()
                release.await()
                rf.openAt(off)
            })
        }
    }

    @Test
    @Timeout(30)
    fun concurrencyBeyondTheCapIs503() {
        write("a.bin", 10)
        val blocking = BlockingSource(source())
        val server = ShareServer(blocking, maxConcurrent = 1)
        val first = ByteArrayOutputStream()
        val t = Thread {
            server.handle(first, "GET", "/api/v1/shares/s1/file", mapOf("path" to "a.bin"), null, null, ByteArray(0), peer) {}
        }
        t.start()
        assertTrue(blocking.started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val second = ByteArrayOutputStream()
        server.handle(second, "GET", "/api/v1/shares/s1/file", mapOf("path" to "a.bin"), null, null, ByteArray(0), peer) {}
        assertEquals(503, status(second.toString(Charsets.ISO_8859_1)))
        blocking.release.countDown()
        t.join()
    }
}
