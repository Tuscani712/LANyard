package io.github.tuscani712.lanyard.core

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Sender-side cancel propagation: the sender asks the receiver to
 * cancel via `POST /api/v1/push/{id}/cancel`; the receiver ends the push as
 * Cancelled ("Cancelled by the sender"), frees its spool and leaves
 * Receiving/Finishing at once — all within the one request.
 */
class PushCancelTest {

    private class Phone : AutoCloseable {
        val identity: Identity = Identity.generate("Pixel")
        private val trustFile = Files.createTempFile("lanyard-trust", ".json").toFile().also { it.delete() }
        val trust: TrustStore = JsonFileTrustStore(trustFile)
        private val sessions = PairingSessions(selfFp = { identity.deviceId }, trust = trust)
        val spool: java.io.File = Files.createTempDirectory("lanyard-spool-cancel").toFile()
        val board = TransferBoard(stalledAfterMillis = 0)
        val senderCancels = CopyOnWriteArrayList<Pair<String, String>>()
        private val diagnostics = ServerDiagnostics()

        val receiver = InboxReceiver(
            spoolRoot = spool,
            destination = PushDestination { rel, _, _ -> rel.substringAfterLast('/') },
            freeBytes = { 1L shl 40 },
            onOffer = { id, fp, files, total ->
                board.add(
                    TransferRecord(id, "receive", "Desktop", fp, "Inbox", total, 0, TransferState.Running, null, 0.0, 0, fileCount = files),
                )
            },
            onCancelledBySender = { id, reason ->
                senderCancels.add(id to reason)
                // Exactly what TransferManager.noteReceiveCancelled does: end the
                // row Cancelled, not Failed, and clear Finishing at once.
                if (board.isLive(id)) board.end(id, TransferState.Cancelled, reason)
            },
        )
        private val server = PeerServer(
            sessions = sessions,
            receiver = receiver,
            invites = PairInvites(),
            isPaired = { trust.find(it) },
            diagnostics = diagnostics,
        )
        val port: Int = server.start(identity) { p ->
            JsonObject().apply {
                addProperty("device_id", identity.deviceId.take(16))
                addProperty("fingerprint", identity.deviceId)
                addProperty("name", "Pixel")
                addProperty("os", "android")
                addProperty("version", "test")
                addProperty("port", p)
            }
        }

        fun pair(clientFp: String) {
            trust.save(PairedPeer(clientFp, "Desktop", "127.0.0.1", 0, browse = true, push = true, pairedAt = 1))
        }

        fun diagnostics(): List<String> = diagnostics.snapshot()

        override fun close() = server.stop()
    }

    private fun clientFor(phone: Phone, client: Identity): PeerClient =
        PeerClient("127.0.0.1", phone.port, client, phone.identity.deviceId)

    private fun offer(phone: Phone, client: Identity, rel: String = "a.bin", size: Long = 10): String {
        val c = clientFor(phone, client)
        val o = c.pushOffer(listOf(PushFileRequest(rel, size, 0)))
        assertTrue(o.accepted, "the offer must be accepted")
        return o.pushId
    }

    @Test
    @Timeout(60)
    fun receiverEndsTheRowCancelledWithinTheOneCancelRequest() {
        Phone().use { phone ->
            val client = Identity.generate("Desktop")
            phone.pair(client.deviceId)
            val id = offer(phone, client)
            // A live receive, with a live row.
            assertEquals(TransferState.Running, phone.board.firstOrNull(id)?.state)

            // One request: the sender cancels.
            assertTrue(clientFor(phone, client).pushCancel(id), "the receiver must acknowledge the cancel")

            assertEquals(SENDER_CANCELLED_REASON, phone.senderCancels.singleOrNull()?.second)
            assertEquals(id, phone.senderCancels.single().first)
            // The row is Cancelled within that one request, not left Running.
            assertEquals(TransferState.Cancelled, phone.board.firstOrNull(id)?.state)
            assertEquals(SENDER_CANCELLED_REASON, phone.board.firstOrNull(id)?.message)
        }
    }

    @Test
    @Timeout(60)
    fun cancelFreesTheSpoolAndTheStaleSessionGuard() {
        Phone().use { phone ->
            val client = Identity.generate("Desktop")
            phone.pair(client.deviceId)
            val id = offer(phone, client, rel = "photo.jpg", size = 100)
            // A partial body on disk, as a paused/interrupted receive would have.
            val c = clientFor(phone, client)
            val part = java.io.File(java.io.File(phone.spool, client.deviceId.take(16).lowercase()), "photo.jpg.lanpart")
            part.parentFile?.mkdirs()
            part.writeBytes(ByteArray(40))

            assertTrue(c.pushCancel(id))

            assertFalse(part.exists(), "cancel must free the .lanpart spool")
            // The stale-session guard is gone: the same peer may offer again.
            val again = offer(phone, client, rel = "photo.jpg", size = 100)
            assertTrue(again != id, "a re-offer after cancel must get a fresh session")
        }
    }

    @Test
    @Timeout(60)
    fun cancelIsIdempotentForAKnownId() {
        Phone().use { phone ->
            val client = Identity.generate("Desktop")
            phone.pair(client.deviceId)
            val id = offer(phone, client)
            assertTrue(clientFor(phone, client).pushCancel(id))
            // A repeat (the sender retried) is a harmless success.
            assertTrue(clientFor(phone, client).pushCancel(id), "a repeated cancel must still be 200")
            assertEquals(1, phone.senderCancels.size, "the row must be ended once, not re-ended")
        }
    }

    @Test
    @Timeout(60)
    fun unknownIdIsHarmless() {
        Phone().use { phone ->
            val client = Identity.generate("Desktop")
            phone.pair(client.deviceId)
            // A push we never saw is answered 200 so a retry is not an error.
            assertTrue(clientFor(phone, client).pushCancel("p_does_not_exist"))
            assertTrue(phone.senderCancels.isEmpty(), "an unknown id must not end any row")
        }
    }

    @Test
    @Timeout(60)
    fun onlyTheOwnerMayCancelAPush() {
        Phone().use { phone ->
            val owner = Identity.generate("Desktop")
            val stranger = Identity.generate("Other")
            phone.pair(owner.deviceId)
            phone.pair(stranger.deviceId)
            val id = offer(phone, owner)

            val ex = assertThrows(PeerStatusException::class.java) {
                clientFor(phone, stranger).pushCancel(id)
            }
            assertEquals(403, ex.code, "a non-owner must be refused")
            assertEquals(TransferState.Running, phone.board.firstOrNull(id)?.state, "the owner's push must survive")
            assertTrue(phone.senderCancels.isEmpty())
        }
    }

    @Test
    @Timeout(60)
    fun unpairedPeerCannotCancel() {
        Phone().use { phone ->
            val stranger = Identity.generate("Stranger")
            // Never paired: the push endpoint refuses with 403 before anything.
            val ex = assertThrows(PeerStatusException::class.java) {
                clientFor(phone, stranger).pushCancel("p_anything")
            }
            assertEquals(403, ex.code)
        }
    }

    @Test
    @Timeout(60)
    fun senderPushSessionNotifiesThePeerWhenCancelled() {
        Phone().use { phone ->
            val client = Identity.generate("Desktop")
            phone.pair(client.deviceId)
            // 2 MB forces several buffers, so cancelling from onProgress is seen
            // on the next loop check while the offer is still live.
            val total = 2L * 1024 * 1024
            val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
            val result = PushSession(clientFor(phone, client)).push(
                listOf(PushSource("big.bin", total, 0) { ZeroInput(total) }),
                onProgress = { _, _, _ -> cancelled.set(true) },
                isCancelled = { cancelled.get() },
            )
            assertEquals(PushResult.Cancelled, result)
            assertTrue(
                phone.diagnostics().any { it.contains("POST /api/v1/push/") && it.contains("/cancel") },
                "the sender must call the peer's cancel route: ${phone.diagnostics()}",
            )
        }
    }

    @Test
    @Timeout(60)
    fun olderPeerWithoutTheRouteIsIgnored() {
        // A peer that predates the route answers 404 to everything on the path.
        LegacyPeer().use { legacy ->
            val client = Identity.generate("Desktop")
            val pc = PeerClient("127.0.0.1", legacy.port, client, legacy.identity.deviceId)
            assertFalse(pc.pushCancel("p_whatever"), "a 404 from an older peer must be ignored, not thrown")
        }
    }

    /**
     * Receiver cancel: once the phone's receiving person cancels the push,
     * the next file `PUT` is answered HTTP 410 with the "cancelled by the
     * receiver" body, so the sender ends Cancelled rather than Failed.
     */
    @Test
    @Timeout(60)
    fun receiverCancelAnswersTheNextFilePutWith410() {
        Phone().use { phone ->
            val client = Identity.generate("Desktop")
            phone.pair(client.deviceId)
            val id = offer(phone, client, rel = "a.bin", size = 0)
            phone.receiver.cancelLocal(id)

            val ex = assertThrows(PeerStatusException::class.java) {
                clientFor(phone, client).pushFileWithSha(id, "a.bin", ByteArray(0), sha256Hex(ByteArray(0)))
            }
            assertEquals(410, ex.code, "a cancelled push must answer 410 Gone")
            assertTrue(
                ex.body.contains("cancelled by the receiver"),
                "the 410 body must name the receiver cancel: ${ex.body}",
            )
        }
    }

    /**
     * Receiver cancel: the receiver's own contract for an in-flight body is
     * a 410, not a generic error, so the server can map it to the wire 410.
     */
    @Test
    @Timeout(60)
    fun receiverCancelMidBodyIsA410NotAnError() {
        Phone().use { phone ->
            val client = Identity.generate("Desktop")
            phone.pair(client.deviceId)
            val id = offer(phone, client, rel = "a.bin", size = 1_000_000)
            phone.receiver.cancelLocal(id)

            val ex = assertThrows(PeerHttpException::class.java) {
                phone.receiver.writeChunk(id, client.deviceId, "a.bin", 0, java.io.ByteArrayInputStream(ByteArray(10)))
            }
            assertEquals(410, ex.code)
            assertTrue(ex.message!!.contains("cancelled by the receiver"), ex.message)
        }
    }

    /**
     * Sender side: a phone pushing to a peer that answers 410 for the file
     * body maps the response to [PushResult.CancelledByReceiver], never a generic
     * failure.
     */
    @Test
    @Timeout(60)
    fun phoneSenderMapsAReceiver410ToCancelledByReceiver() {
        GonePeer().use { peer ->
            val client = Identity.generate("Phone")
            val pc = PeerClient("127.0.0.1", peer.port, client, peer.identity.deviceId)
            val result = PushSession(pc).push(
                listOf(PushSource("a.bin", 4, 0) { java.io.ByteArrayInputStream(ByteArray(4)) }),
            )
            assertEquals(PushResult.CancelledByReceiver, result)
        }
    }

    /**
     * Sender side, complete step: a peer that answers 410 Gone for the
     * final `/complete` (after a good file body) must also map to
     * CancelledByReceiver, not a generic failure.
     */
    @Test
    @Timeout(60)
    fun phoneSenderMapsACompleteStep410ToCancelledByReceiver() {
        GonePeer(goneAtComplete = true).use { peer ->
            val client = Identity.generate("Phone")
            val pc = PeerClient("127.0.0.1", peer.port, client, peer.identity.deviceId)
            val result = PushSession(pc).push(
                listOf(PushSource("a.bin", 4, 0) { java.io.ByteArrayInputStream(ByteArray(4)) }),
            )
            assertEquals(PushResult.CancelledByReceiver, result)
        }
    }

    /** A peer that accepts the offer but answers 410 Gone at [goneAtComplete]. */
    private class GonePeer(private val goneAtComplete: Boolean = false) : AutoCloseable {
        val identity: Identity = Identity.generate("Gone")
        private val server: SSLServerSocket
        val port: Int
        private val thread: Thread

        init {
            val ctx = Tls.serverContext(identity)
            server = ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket
            server.needClientAuth = true
            server.enabledProtocols = arrayOf("TLSv1.3")
            port = server.localPort
            thread = Thread {
                while (!server.isClosed) {
                    runCatching {
                        val s = server.accept() as SSLSocket
                        s.use { socket ->
                            socket.startHandshake()
                            val input = BufferedInputStream(socket.getInputStream())
                            val out = BufferedOutputStream(socket.getOutputStream())
                            val head = StringBuilder()
                            var a = 0; var b = 0; var c = 0; var d = 0
                            while (true) {
                                val r = input.read()
                                if (r < 0) return@use
                                head.append(r.toChar())
                                a = b; b = c; c = d; d = r
                                if (a == 13 && b == 10 && c == 13 && d == 10) break
                            }
                            val requestLine = head.lineSequence().first()
                            val path = requestLine.split(" ").getOrElse(1) { "" }
                            val cl = Regex("(?i)content-length:\\s*(\\d+)")
                                .find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            val body = ByteArray(cl)
                            var off = 0
                            while (off < cl) {
                                val n = input.read(body, off, cl - off)
                                if (n < 0) break
                                off += n
                            }
                            val (code, resp) = when {
                                path.endsWith("/offer") ->
                                    200 to """{"push_id":"p_gone","accepted":true,"max_bytes":0,"files":[{"rel_path":"a.bin","offset":0}]}"""
                                path.contains("/file") && !goneAtComplete ->
                                    410 to """{"error":"cancelled by the receiver"}"""
                                path.contains("/file") ->
                                    200 to """{"written":4,"offset":4,"done":true}"""
                                path.endsWith("/complete") && goneAtComplete ->
                                    410 to """{"error":"cancelled by the receiver"}"""
                                path.endsWith("/complete") -> 200 to """{"done":true}"""
                                else -> 404 to """{"error":"not found"}"""
                            }
                            val bytes = resp.toByteArray()
                            out.write(
                                ("HTTP/1.1 $code Status\r\nContent-Type: application/json\r\n" +
                                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(),
                            )
                            out.write(bytes)
                            out.flush()
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
        }

        override fun close() {
            runCatching { server.close() }
            thread.interrupt()
        }
    }

    /** Zero-filled generator, so the test never materializes a 2 MB array. */
    private class ZeroInput(private var left: Long) : java.io.InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            left--
            return 0
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = minOf(len.toLong(), left).toInt()
            for (i in 0 until n) b[off + i] = 0
            left -= n
            return n
        }
    }

    /** A minimal TLS peer that answers every request with a 404 (an older build). */
    private class LegacyPeer : AutoCloseable {
        val identity: Identity = Identity.generate("Legacy")
        private val server: SSLServerSocket
        val port: Int
        private val thread: Thread

        init {
            val ctx = Tls.serverContext(identity)
            server = ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket
            server.needClientAuth = true
            server.enabledProtocols = arrayOf("TLSv1.3")
            port = server.localPort
            thread = Thread {
                runCatching {
                    val s = server.accept() as SSLSocket
                    s.use { socket ->
                        socket.startHandshake()
                        val input = BufferedInputStream(socket.getInputStream())
                        val out = BufferedOutputStream(socket.getOutputStream())
                        // Read the request head (until CRLFCRLF) then answer 404.
                        var a = 0; var b = 0; var c = 0; var d = 0
                        while (true) {
                            val r = input.read()
                            if (r < 0) break
                            a = b; b = c; c = d; d = r
                            if (a == 13 && b == 10 && c == 13 && d == 10) break
                        }
                        val body = """{"error":"not found"}"""
                        out.write(
                            ("HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray(),
                        )
                        out.flush()
                    }
                }
            }.apply { isDaemon = true; start() }
        }

        override fun close() {
            runCatching { server.close() }
            thread.interrupt()
        }
    }
}
