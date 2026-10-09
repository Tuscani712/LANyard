package io.github.tuscani712.lanyard.core

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.io.InputStream
import javax.net.ssl.SSLSocket

/**
 * A client that opens a download and stops reading must be dropped by the stall
 * guard, freeing its concurrency slot.
 */
class ShareStallTest {
    private class StallSource : ShareSource {
        override fun list() = listOf(ShareInfo("s1", "x", "", "folder", 0))
        override fun ended(shareId: String): String? = null
        override fun children(shareId: String, rel: String) = emptyList<ShareChild>()
        override fun resolve(shareId: String, rel: String): ResolvedFile? = when (rel) {
            "big" -> ResolvedFile("big", 1L shl 30, 0) { _ -> InfiniteInput() }
            "small" -> ResolvedFile("small", 4, 0) { _ -> byteArrayOf(1, 2, 3, 4).inputStream() }
            else -> null
        }
    }

    private class InfiniteInput : InputStream() {
        override fun read(): Int = 65
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = minOf(len, 8192)
            for (i in 0 until n) b[off + i] = 65
            return n
        }
    }

    private val phone = Identity.generate("Phone")
    private val client = Identity.generate("Desk")
    private val trustFile = File.createTempFile("lanyard-trust", ".json").also { it.delete() }
    private val trust: TrustStore = JsonFileTrustStore(trustFile)
    private val spool = File.createTempFile("spool", ".d").let { it.delete(); it.mkdirs(); it }
    private val receiver = InboxReceiver(spool, PushDestination { r, _, _ -> r }, { 1L shl 40 })
    private val shareServer = ShareServer(StallSource(), maxConcurrent = 1, stallTimeoutMillis = 400)
    private val server = PeerServer(
        sessions = PairingSessions({ phone.deviceId }, trust),
        receiver = receiver,
        invites = PairInvites(),
        isPaired = { trust.find(it) },
        onUnpair = {},
        shares = shareServer,
        stallTimeoutMillis = 400,
        headerTimeoutMillis = 5_000,
        idleTimeoutMillis = 5_000,
    )
    private val port: Int = server.start(phone) { _: Int -> JsonObject() }

    private fun status(resp: String) = resp.lineSequence().first().split(" ").getOrNull(1)?.toIntOrNull() ?: -1

    private fun request(client: Identity, raw: String): String {
        val factory = Tls.socketFactory(client, phone.deviceId)
        factory.createSocket("127.0.0.1", port).use { rawSocket ->
            val s = rawSocket as SSLSocket
            s.startHandshake()
            s.outputStream.write(raw.toByteArray())
            s.outputStream.flush()
            val head = StringBuilder()
            var st = 0
            while (true) {
                val b = s.inputStream.read()
                if (b < 0) break
                head.append(b.toChar())
                st = when {
                    st == 0 && b.toChar() == '\r' -> 1
                    st == 1 && b.toChar() == '\n' -> 2
                    st == 2 && b.toChar() == '\r' -> 3
                    st == 3 && b.toChar() == '\n' -> 4
                    else -> 0
                }
                if (st == 4) break
            }
            val text = head.toString()
            val len = Regex("(?i)content-length:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val body = ByteArray(len); var off = 0
            while (off < len) { val n = s.inputStream.read(body, off, len - off); if (n < 0) break; off += n }
            return text + String(body, Charsets.UTF_8)
        }
    }

    private fun readHeaders(input: InputStream) {
        var st = 0
        while (true) {
            val b = input.read()
            if (b < 0) return
            st = when {
                st == 0 && b.toChar() == '\r' -> 1
                st == 1 && b.toChar() == '\n' -> 2
                st == 2 && b.toChar() == '\r' -> 3
                st == 3 && b.toChar() == '\n' -> 4
                else -> 0
            }
            if (st == 4) return
        }
    }

    @Test
    @Timeout(30)
    fun stalledReaderIsDroppedAndFreesTheSlot() {
        trust.save(PairedPeer(client.deviceId, "Desk", "127.0.0.1", 1, browse = true, push = false, pairedAt = 0))
        val f = Tls.socketFactory(client, phone.deviceId)
        val s1 = f.createSocket("127.0.0.1", port) as SSLSocket
        try {
            s1.startHandshake()
            s1.outputStream.write("GET /api/v1/shares/s1/file?path=big HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            s1.outputStream.flush()
            readHeaders(s1.inputStream) // read headers, then stop reading the body
            Thread.sleep(2500) // longer than the 400 ms stall timeout
            // The slot must be free now: a normal download is accepted, not 503.
            val resp = request(client, "GET /api/v1/shares/s1/file?path=small HTTP/1.1\r\nHost: x\r\n\r\n")
            assertEquals(200, status(resp), resp)
        } finally {
            runCatching { s1.close() }
            server.stop()
        }
    }
}
