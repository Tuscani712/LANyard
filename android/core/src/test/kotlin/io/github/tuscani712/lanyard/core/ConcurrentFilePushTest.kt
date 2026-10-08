package io.github.tuscani712.lanyard.core

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.URLEncoder
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import javax.net.ssl.SSLSocket

/**
 * Three file bodies of ONE push stream concurrently into the real [PeerServer] /
 * [InboxReceiver], with the callbacks wired the way the app wires them
 * (`TransferManager.noteReceiveProgress` / `sampleSpeed` / `TransferBoard`).
 *
 * This is the field crash: the phone threw
 * `Attempt to invoke virtual method 'long java.lang.Number.longValue()' on a
 * null object reference` on a progress callback, the handler answered 500 and
 * closed the connection. The root cause is [SpeedMeter]: one meter is kept per
 * push id, and every concurrent body's `onProgress` samples it, so its
 * non-thread-safe `ArrayDeque` is mutated by three server threads at once; a
 * corrupted slot unboxes to null in `SpeedMeter.sample`.
 */
class ConcurrentFilePushTest {

    private class Phone : AutoCloseable {
        val identity: Identity = Identity.generate("Pixel 8 Pro")
        private val trustFile = Files.createTempFile("lanyard-trust", ".json").toFile().also { it.delete() }
        val trust: TrustStore = JsonFileTrustStore(trustFile)
        private val sessions = PairingSessions(selfFp = { identity.deviceId }, trust = trust)
        val spool: java.io.File = Files.createTempDirectory("lanyard-spool").toFile()

        /** Exactly what [io.github.tuscani712.lanyard.transfer.TransferManager] uses. */
        val board = TransferBoard(stalledAfterMillis = 0)
        val speedMeters = ConcurrentHashMap<String, SpeedMeter>()
        val callbackErrors = CopyOnWriteArrayList<Throwable>()
        val placed = ConcurrentHashMap<String, Long>()

        private val receiver = InboxReceiver(
            spoolRoot = spool,
            destination = PushDestination { rel, f, _ -> placed[rel] = f.length(); rel.substringAfterLast('/') },
            freeBytes = { 1L shl 40 },
            onOffer = { id, fp, _, total ->
                board.add(
                    TransferRecord(id, "receive", "Desktop", fp, "Inbox", total, 0, TransferState.Running, null, 0.0, now()),
                )
            },
            // The app's receive-progress path, verbatim in shape: one shared
            // meter per push id, sampled from every concurrent callback.
            onProgress = { id, done, total -> appProgress(id, done, total) },
            onDone = { id, _, _, _ -> if (board.isLive(id)) board.end(id, TransferState.Done, "done") },
            onFailed = { id, _ -> if (board.isLive(id)) board.end(id, TransferState.Failed, "failed") },
            onCancelled = { id, _ -> if (board.isLive(id)) board.end(id, TransferState.Cancelled, "cancelled") },
        )

        private fun appProgress(id: String, done: Long, total: Long) {
            if (!board.isLive(id)) return
            try {
                val meter = speedMeters.computeIfAbsent(id) { SpeedMeter() }
                val speed = meter.sample(now(), done) ?: 0.0
                board.progress(id, done, total, speed)
            } catch (t: Throwable) {
                // The app never guards this; a throw here becomes a 500 and the
                // connection is closed. Record it so the test can see the crash.
                callbackErrors.add(t)
            }
        }

        private val server = PeerServer(
            sessions = sessions,
            receiver = receiver,
            invites = PairInvites(),
            isPaired = { trust.find(it) },
        )

        val port: Int = server.start(identity) { p ->
            JsonObject().apply {
                addProperty("device_id", identity.deviceId.take(16))
                addProperty("fingerprint", identity.deviceId)
                addProperty("name", "Pixel 8 Pro")
                addProperty("os", "android")
                addProperty("version", "test")
                addProperty("port", p)
            }
        }

        fun pair(clientFp: String) {
            trust.save(PairedPeer(clientFp, "Desktop", "127.0.0.1", 0, browse = true, push = true, pairedAt = 1))
        }

        override fun close() = server.stop()

        private fun now(): Long = System.currentTimeMillis()
    }

    private fun clientFor(phone: Phone, client: Identity): PeerClient =
        PeerClient("127.0.0.1", phone.port, client, phone.identity.deviceId)

    private fun bytesOf(n: Int): ByteArray = ByteArray(n) { (it % 251).toByte() }

    /**
     * Sends one file to [pushId] as an HTTP/1.1 chunked body, one small chunk at
     * a time, so the server calls `onProgress` many times per body. Returns the
     * response status code.
     */
    private fun chunkedPut(
        phone: Phone,
        client: Identity,
        pushId: String,
        rel: String,
        data: ByteArray,
        chunkSize: Int,
        started: CountDownLatch,
        go: CountDownLatch,
    ): Int {
        val socket = Tls.socketFactory(client, phone.identity.deviceId)
            .createSocket("127.0.0.1", phone.port) as SSLSocket
        socket.enabledProtocols = arrayOf("TLSv1.3")
        socket.startHandshake()
        try {
            val out = BufferedOutputStream(socket.getOutputStream())
            val path = "/api/v1/push/$pushId/file?path=${URLEncoder.encode(rel, "UTF-8")}"
            out.write(
                ("PUT $path HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                    "Transfer-Encoding: chunked\r\nContent-Type: application/octet-stream\r\n\r\n").toByteArray(),
            )
            out.flush()
            started.countDown()
            go.await()
            var off = 0
            while (off < data.size) {
                val n = minOf(chunkSize, data.size - off)
                out.write("%x\r\n".format(n).toByteArray(Charsets.US_ASCII))
                out.write(data, off, n)
                out.write("\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()
                off += n
            }
            out.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
            return readStatus(socket.getInputStream())
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readStatus(input: InputStream): Int {
        val line = readLine(input) ?: return -1
        return line.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().removeSuffix("\r")
            sb.append(b.toChar())
        }
    }

    @Test
    @Timeout(120)
    fun threeConcurrentChunkedBodiesInOnePushDoNotKillTheReceive() {
        Phone().use { phone ->
            val client = Identity.generate("Desktop")
            phone.pair(client.deviceId)

            val names = listOf("a.bin", "b.bin", "c.bin")
            val size = 512 * 1024
            val sources = names.map { PushSource(it, size.toLong(), 0) { bytesOf(size).inputStream() } }

            val offer = clientFor(phone, client).pushOffer(
                sources.map { PushFileRequest(it.relPath, it.size, it.mtimeMillis) },
            )
            assertTrue(offer.accepted, "the offer must be accepted")

            // The three bodies are held at the barrier and then released
            // together, so they stream into the server concurrently.
            val started = CountDownLatch(names.size)
            val go = CountDownLatch(1)
            val statuses = ConcurrentHashMap<String, Int>()
            val threads = names.map { name ->
                Thread {
                    val code = runCatching {
                        chunkedPut(phone, client, offer.pushId, name, bytesOf(size), chunkSize = 256, started, go)
                    }.getOrElse { -1 }
                    statuses[name] = code
                }.apply { isDaemon = true; start() }
            }
            // Wait until all three connections are open and their first request
            // bytes are on the wire, then release them together.
            started.await()
            go.countDown()
            threads.forEach { it.join(30_000) }

            // The field failure: a progress callback threw, so a body answered
            // 500 and was closed instead of completing.
            assertTrue(
                phone.callbackErrors.isEmpty(),
                "the progress/speed path must not throw: ${phone.callbackErrors.map { it }}",
            )
            assertEquals(
                names.associateWith { 200 }, statuses.toMap(),
                "every concurrent body must be answered 200, got $statuses",
            )

            // Finish the push and confirm every file was placed whole.
            sources.forEach { clientFor(phone, client).pushCompleteFile(offer.pushId, it.relPath, shaOf(bytesOf(size))) }
            clientFor(phone, client).pushCompleteAll(offer.pushId)
            names.forEach { assertEquals(size.toLong(), phone.placed[it], "the file must be placed whole: $it") }
        }
    }

    /**
     * A callback that throws must never take down the transfer: the body still
     * completes with a 2xx. This pins the callback-wrapping fix independently of
     * the SpeedMeter fix.
     */
    @Test
    @Timeout(60)
    fun aThrowingProgressCallbackDoesNotFailTheBody() {
        val spool = Files.createTempDirectory("lanyard-spool").toFile()
        val placed = ConcurrentHashMap<String, Long>()
        val receiver = InboxReceiver(
            spoolRoot = spool,
            destination = PushDestination { rel, f, _ -> placed[rel] = f.length(); rel },
            freeBytes = { 1L shl 40 },
            onChange = { },
            onProgress = { _, _, _ -> throw IllegalStateException("progress callback blew up") },
        )
        receiver.use { r ->
            val identity = Identity.generate("Pixel")
            val trustFile = Files.createTempFile("lanyard-trust", ".json").toFile().also { it.delete() }
            val trust = JsonFileTrustStore(trustFile)
            val sessions = PairingSessions(selfFp = { identity.deviceId }, trust = trust)
            val server = PeerServer(sessions = sessions, receiver = r, invites = PairInvites(), isPaired = { trust.find(it) })
            try {
                val port = server.start(identity) { p -> JsonObject().apply { addProperty("port", p) } }
                val client = Identity.generate("Desktop")
                trust.save(PairedPeer(client.deviceId, "Desktop", "127.0.0.1", 0, browse = true, push = true, pairedAt = 1))

                val pc = PeerClient("127.0.0.1", port, client, identity.deviceId)
                val data = bytesOf(4)
                val offer = pc.pushOffer(listOf(PushFileRequest("a.bin", data.size.toLong(), 0)))
                val code = chunkedPutOn(port, identity, client, offer.pushId, "a.bin", data, 2)
                assertEquals(200, code, "a throwing callback must not turn a body into a 500")
                pc.pushCompleteFile(offer.pushId, "a.bin", shaOf(data))
                assertEquals(4L, placed["a.bin"], "the body must still be received")
            } finally {
                server.stop()
            }
        }
    }

    private fun <T> InboxReceiver.use(block: (InboxReceiver) -> T): T =
        try { block(this) } finally { close() }

    private fun chunkedPutOn(
        port: Int,
        serverIdentity: Identity,
        client: Identity,
        pushId: String,
        rel: String,
        data: ByteArray,
        chunkSize: Int,
    ): Int {
        val socket = Tls.socketFactory(client, serverIdentity.deviceId)
            .createSocket("127.0.0.1", port) as SSLSocket
        socket.enabledProtocols = arrayOf("TLSv1.3")
        socket.startHandshake()
        try {
            val out = BufferedOutputStream(socket.getOutputStream())
            val path = "/api/v1/push/$pushId/file?path=${URLEncoder.encode(rel, "UTF-8")}"
            out.write(
                ("PUT $path HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                    "Transfer-Encoding: chunked\r\nContent-Type: application/octet-stream\r\n\r\n").toByteArray(),
            )
            var off = 0
            while (off < data.size) {
                val n = minOf(chunkSize, data.size - off)
                out.write("%x\r\n".format(n).toByteArray(Charsets.US_ASCII))
                out.write(data, off, n)
                out.write("\r\n".toByteArray(Charsets.US_ASCII))
                off += n
            }
            out.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
            return readStatus(socket.getInputStream())
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun shaOf(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * With several bodies in flight, one finishing must not clear the push's
     * in-flight state: the remaining body is still judged by the short stall
     * timeout, not the long idle timeout. A single boolean per push cleared by
     * whichever body finished first exposed the others to the 10-minute idle
     * timeout (field bug), so a genuinely dead body sat Running far too long.
     */
    @Test
    @Timeout(30)
    fun oneFinishedBodyDoesNotExposeTheOthersToTheIdleTimeout() {
        val failures = CopyOnWriteArrayList<Pair<String, String>>()
        val spool = Files.createTempDirectory("lanyard-spool").toFile()
        val release = CountDownLatch(1)
        val r = InboxReceiver(
            spoolRoot = spool,
            destination = PushDestination { rel, _, _ -> rel },
            freeBytes = { 1L shl 40 },
            onFailed = { id, reason -> failures.add(id to reason) },
            stallTimeoutMillis = 200,
            idleTimeoutMillis = 60_000,
        )
        try {
            val o = r.offer(
                "p", "P",
                listOf(PushFileRequest("a.bin", 100, 0), PushFileRequest("b.bin", 100, 0)),
                0, 0,
            )
            // b starts and then blocks mid-body...
            Thread { runCatching { r.writeChunk(o.pushId, "p", "b.bin", 0, BlockingInput(3, release)) } }
                .apply { isDaemon = true; start() }
            assertTrue(
                waitFor(2_000) { spool.walkTopDown().any { it.isFile && it.name == "b.bin.lanpart" && it.length() >= 3 } },
                "test setup: b's body must be in flight",
            )
            // ...then a starts and finishes while b is still in flight.
            val a = Thread { runCatching { r.writeChunk(o.pushId, "p", "a.bin", 0, ByteArray(100).inputStream()) } }
                .apply { isDaemon = true; start() }
            a.join(2_000)
            assertTrue(failures.isEmpty(), "a finished body must not make the push look idle: $failures")
            // b makes no further progress: the stall timeout must reap the push.
            assertTrue(
                waitFor(3_000) { failures.isNotEmpty() },
                "a push with another body still in flight must be judged by the stall timeout",
            )
            assertEquals(o.pushId, failures.single().first)
        } finally {
            release.countDown()
            r.close()
        }
    }

    /** The logged 500 must name the top stack frame, not only the message. */
    @Test
    @Timeout(60)
    fun the500LogNamesTheTopStackFrame() {
        val diag = ServerDiagnostics()
        val identity = Identity.generate("Pixel")
        val trustFile = Files.createTempFile("lanyard-trust", ".json").toFile().also { it.delete() }
        val trust = JsonFileTrustStore(trustFile)
        val sessions = PairingSessions(selfFp = { identity.deviceId }, trust = trust)
        val spool = Files.createTempDirectory("lanyard-spool").toFile()
        val receiver = InboxReceiver(
            spoolRoot = spool,
            destination = PushDestination { _, _, _ -> throw IllegalStateException("save location unavailable") },
            freeBytes = { 1L shl 40 },
            diag = { diag.record(it) },
        )
        val server = PeerServer(
            sessions = sessions,
            receiver = receiver,
            invites = PairInvites(),
            isPaired = { trust.find(it) },
            diagnostics = diag,
        )
        try {
            val port = server.start(identity) { p -> JsonObject().apply { addProperty("port", p) } }
            val client = Identity.generate("Desktop")
            trust.save(PairedPeer(client.deviceId, "Desktop", "127.0.0.1", 0, browse = true, push = true, pairedAt = 1))

            val pc = PeerClient("127.0.0.1", port, client, identity.deviceId)
            val data = bytesOf(8)
            val offer = pc.pushOffer(listOf(PushFileRequest("a.bin", data.size.toLong(), 0)))
            // The whole-file fast path places on the server thread, so the failing
            // destination throws there and the handler answers 500.
            runCatching { pc.pushFileWithSha(offer.pushId, "a.bin", data, shaOf(data)) }

            val line = diag.snapshot().firstOrNull { it.contains("resp 500") && it.contains("/file") }
            assertTrue(line != null, "the 500 must be logged: ${diag.snapshot()}")
            assertTrue(line!!.contains("IllegalStateException"), "the log must name the exception: $line")
            assertTrue(line.contains(" at "), "the log must include the top stack frame, not only the message: $line")
        } finally {
            server.stop()
        }
    }

    /** A byte source that yields [prefix] bytes, then blocks until [release]. */
    private class BlockingInput(private var prefix: Int, private val release: CountDownLatch) : InputStream() {
        override fun read(): Int {
            if (prefix > 0) { prefix--; return 7 }
            release.await()
            return -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (prefix > 0) {
                val n = minOf(len, prefix)
                for (i in 0 until n) b[off + i] = 7
                prefix -= n
                return n
            }
            release.await()
            return -1
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
