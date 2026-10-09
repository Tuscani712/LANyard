package io.github.tuscani712.lanyard.core

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayInputStream
import java.nio.file.Files

/**
 * Fix 3: send progress must be honest. A file is not shown at 100% merely
 * because its bytes reached the socket's buffered stream; the true total is
 * reported only once the receiver's response to the file has been read and is a
 * 2xx success. Tests run against an in-process [PeerServer], no Go binary needed.
 */
class PushProgressTest {

    private class Phone(destination: PushDestination) : AutoCloseable {
        val identity: Identity = Identity.generate("Pixel")
        private val trustFile = Files.createTempFile("lanyard-trust", ".json").toFile().also { it.delete() }
        val trust: TrustStore = JsonFileTrustStore(trustFile)
        private val sessions = PairingSessions(selfFp = { identity.deviceId }, trust = trust)
        private val spool = Files.createTempDirectory("lanyard-spool").toFile()
        private val receiver = InboxReceiver(
            spoolRoot = spool,
            destination = destination,
            freeBytes = { 1L shl 40 },
        )
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
                addProperty("name", "Pixel")
                addProperty("os", "android")
                addProperty("version", "test")
                addProperty("port", p)
            }
        }

        fun pair(clientFp: String) {
            trust.save(PairedPeer(clientFp, "Desktop", "127.0.0.1", 0, browse = true, push = true, pairedAt = 1))
        }

        override fun close() = server.stop()
    }

    private fun clientFor(phone: Phone, client: Identity): PeerClient =
        PeerClient("127.0.0.1", phone.port, client, phone.identity.deviceId)

    private fun bytesOf(n: Int): ByteArray = ByteArray(n) { (it % 251).toByte() }

    @Test
    @Timeout(60)
    fun liveProgressStaysBelowFullUntilTheReceiverAccepts() {
        val placed = mutableListOf<String>()
        Phone(PushDestination { rel, f, _ -> placed.add("$rel:${f.length()}"); rel.substringAfterLast('/') }).use { phone ->
            val client = Identity.generate("Desktop")
            phone.pair(client.deviceId)
            val bytes = bytesOf(512 * 1024 + 7)
            val total = bytes.size.toLong()
            val events = mutableListOf<Pair<Long, Long>>()

            val result = PushSession(clientFor(phone, client)).push(
                listOf(PushSource("big.bin", total, 0) { ByteArrayInputStream(bytes) }),
                onProgress = { _, sent, fileTotal -> events.add(sent to fileTotal) },
            )

            assertTrue(result is PushResult.Sent, "expected Sent, got $result")
            val full = events.filter { it.first == total }
            assertEquals(1, full.size, "exactly one 100% report, after acceptance: $events")
            assertEquals(total to total, events.last(), "the 100% report must be the last one: $events")
            assertTrue(
                events.dropLast(1).all { it.first < total },
                "no 100% may be reported before the response is read: $events",
            )
            assertTrue(placed.any { it == "big.bin:$total" }, "the receiver must have placed the file: $placed")
        }
    }

    @Test
    @Timeout(60)
    fun finishingFiresAfterTheLastByteAndBeforeTheReceiverAccepts() {
        val placed = mutableListOf<String>()
        Phone(PushDestination { rel, f, _ -> placed.add("$rel:${f.length()}"); rel.substringAfterLast('/') }).use { phone ->
            val client = Identity.generate("Desktop")
            phone.pair(client.deviceId)
            val bytes = bytesOf(512 * 1024 + 7)
            val total = bytes.size.toLong()
            val events = mutableListOf<String>()

            val result = PushSession(clientFor(phone, client)).push(
                listOf(PushSource("big.bin", total, 0) { ByteArrayInputStream(bytes) }),
                onProgress = { _, sent, _ -> events.add("progress:$sent") },
                onFinishing = { _, _, size -> events.add("finishing:$size") },
            )

            assertTrue(result is PushResult.Sent, "expected Sent, got $result")
            val finishing = events.indexOf("finishing:$total")
            assertTrue(finishing >= 0, "the send must enter the Finishing window: $events")
            // Every byte-progress before the finishing mark is below 100%; the
            // single 100% report comes only after the finishing window.
            assertTrue(
                events.take(finishing).filter { it.startsWith("progress:") }
                    .all { it != "progress:$total" },
                "no 100% may be shown before the finishing window: $events",
            )
            val full = events.indices.filter { events[it] == "progress:$total" }
            assertEquals(1, full.size, "exactly one honest 100% report: $events")
            assertTrue(full.first() > finishing, "the 100% report must follow the finishing window: $events")
        }
    }

    @Test
    @Timeout(60)
    fun failedCompleteNeverReportsFull() {
        // The file body streams fine, but the receiver rejects it when placing
        // it (an unwritable inbox): the complete request is answered with 500.
        Phone(PushDestination { _, _, _ -> throw RuntimeException("save location unavailable") }).use { phone ->
            val client = Identity.generate("Desktop")
            phone.pair(client.deviceId)
            val bytes = bytesOf(64 * 1024)
            val total = bytes.size.toLong()
            val events = mutableListOf<Pair<Long, Long>>()

            val result = PushSession(clientFor(phone, client)).push(
                listOf(PushSource("big.bin", total, 0) { ByteArrayInputStream(bytes) }),
                onProgress = { _, sent, fileTotal -> events.add(sent to fileTotal) },
            )

            assertTrue(result is PushResult.Failed, "expected Failed, got $result")
            assertTrue(
                events.none { it.first == total },
                "a non-2xx response must never report 100%: $events",
            )
        }
    }

    @Test
    @Timeout(60)
    fun outgoingPushStepsAreLoggedRedacted() {
        // Task C(a): every outgoing step joins the diagnostics sink, redacted:
        // offer, per-file, complete. Never a file name, only a path class.
        Phone(PushDestination { rel, _, _ -> rel.substringAfterLast('/') }).use { phone ->
            val client = Identity.generate("Desktop")
            phone.pair(client.deviceId)
            val lines = mutableListOf<String>()
            val bytes = bytesOf(64 * 1024)

            val result = PushSession(clientFor(phone, client), diag = { lines.add(it) }).push(
                listOf(PushSource("dir/photo.jpg", bytes.size.toLong(), 0) { ByteArrayInputStream(bytes) }),
            )

            assertTrue(result is PushResult.Sent, "expected Sent, got $result")
            assertTrue(lines.any { it.startsWith("[push] offer") }, "the offer step must be logged: $lines")
            assertTrue(lines.any { it.startsWith("[push] file 1 of 1") }, "the per-file step must be logged: $lines")
            assertTrue(lines.any { it.startsWith("[push] complete") }, "the complete step must be logged: $lines")
            assertTrue(lines.any { it.contains("cls=*.jpg") }, "the path class must be logged: $lines")
            assertTrue(
                lines.none { it.contains("photo.jpg") },
                "the outgoing log must never carry a file name: $lines",
            )
        }
    }

    @Test
    fun unsentBytesAreReportedAsTheTrueFraction() {
        assertEquals(50L, SendProgress.whileSending(50, 100))
        assertEquals(0L, SendProgress.whileSending(0, 100))
    }

    @Test
    fun aFileInFlightIsNeverReportedAsComplete() {
        // Even once every byte is on the wire, the display holds one byte back
        // until the receiver acknowledges the file.
        assertEquals(99L, SendProgress.whileSending(100, 100))
        assertEquals(0L, SendProgress.whileSending(1, 1))
        assertEquals(0L, SendProgress.whileSending(0, 0))
    }
}
