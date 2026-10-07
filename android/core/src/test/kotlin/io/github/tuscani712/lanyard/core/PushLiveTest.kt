package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * Live: a real Go desktop pushes files to a JVM [PeerServer] (the phone), which
 * spools, verifies and places them. Skipped unless `LANYARD_BIN` is set.
 */
class PushLiveTest {
    private val bin: String? = System.getenv("LANYARD_BIN")?.takeIf { it.isNotBlank() }

    private class Phone(
        deskFp: String,
        askOver: Long = 0,
        approval: PushApproval = PushApproval { _, _, _, _, _ -> ApprovalOutcome.ACCEPTED },
    ) : AutoCloseable {
        val identity: Identity = Identity.generate("Pixel 8 Pro")
        private val trustFile = File.createTempFile("lanyard-trust", ".json").also { it.delete() }
        val trust: TrustStore = JsonFileTrustStore(trustFile)
        val dest: File = Files.createTempDirectory("lanyard-dest").toFile()
        val placed = ConcurrentHashMap<String, Long>()
        val spool = Files.createTempDirectory("lanyard-spool").toFile()
        val sessions = PairingSessions(selfFp = { identity.deviceId }, trust = trust)
        val receiver = InboxReceiver(
            spoolRoot = spool,
            destination = PushDestination { rel, f, _ -> placed[rel] = f.length(); rel.substringAfterLast('/') },
            freeBytes = { 1L shl 40 },
        )
        private val server = PeerServer(
            sessions = sessions,
            receiver = receiver,
            invites = PairInvites(),
            isPaired = { trust.find(it) },
            onUnpair = { trust.remove(it) },
            approval = approval,
        )
        val port: Int = server.start(identity) { p ->
            com.google.gson.JsonObject().apply {
                addProperty("device_id", identity.deviceId.take(16))
                addProperty("fingerprint", identity.deviceId)
                addProperty("name", "Pixel 8 Pro")
                addProperty("os", "android")
                addProperty("version", "test")
                addProperty("port", p)
            }
        }
        val handshakeCount: Long get() = server.handshakes.get()

        init {
            trust.save(PairedPeer(deskFp, "Desktop", "127.0.0.1", 0, browse = true, push = true, pairedAt = 1, askOver = askOver))
        }

        override fun close() = server.stop()
    }

    private fun writeDir(files: Map<String, Int>): File {
        val dir = Files.createTempDirectory("lanyard-src").toFile()
        files.forEach { (name, size) ->
            val f = File(dir, name)
            f.parentFile?.mkdirs()
            f.writeBytes(ByteArray(size) { (it % 251).toByte() })
        }
        return dir
    }

    @Test
    @Timeout(180)
    fun desktopPushesSmallAndLargeFiles() {
        assumeTrue(bin != null)
        GoPeer(bin!!).use { desk ->
            Phone(desk.fingerprint()).use { phone ->
                desk.addPeer("127.0.0.1:${phone.port}")
                val src = writeDir(mapOf("small1.bin" to 10_240, "small2.bin" to 10_240, "big.bin" to 2_000_000))
                val job = desk.push(phone.identity.deviceId, listOf(src.absolutePath))
                assertEquals("Done", desk.waitPush(job.get("id").asString))
                val dirName = src.name
                assertTrue(phone.placed.containsKey("$dirName/small1.bin"))
                assertTrue(phone.placed.containsKey("$dirName/big.bin"))
                assertEquals(2_000_000L, phone.placed["$dirName/big.bin"])
            }
        }
    }

    @Test
    @Timeout(240)
    fun manySmallFilesReuseOneConnection() {
        assumeTrue(bin != null)
        GoPeer(bin!!).use { desk ->
            Phone(desk.fingerprint()).use { phone ->
                desk.addPeer("127.0.0.1:${phone.port}")
                val files = (1..1000).associate { "f%04d.bin".format(it) to 1024 }
                val src = writeDir(files)
                val job = desk.push(phone.identity.deviceId, listOf(src.absolutePath))
                assertEquals("Done", desk.waitPush(job.get("id").asString))
                assertEquals(1000, phone.placed.size)
                assertTrue(
                    phone.handshakeCount in 1..64,
                    "expected <=64 connections for 1000 files, got ${phone.handshakeCount}",
                )
            }
        }
    }

    @Test
    @Timeout(60)
    fun unpairedOfferIsRefused() {
        assumeTrue(bin != null)
        GoPeer(bin!!).use { desk ->
            Phone(desk.fingerprint()).use { phone ->
                // A fresh identity that is not in the phone's trust store.
                val stranger = Identity.generate("Stranger")
                val body = """{"files":[{"rel_path":"x.bin","size":1}],"total_bytes":1}"""
                val raw = "POST /api/v1/push/offer HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${body.toByteArray().size}\r\n\r\n$body"
                val factory = Tls.socketFactory(stranger, phone.identity.deviceId)
                val resp = factory.createSocket("127.0.0.1", phone.port).use { s ->
                    (s as javax.net.ssl.SSLSocket).startHandshake()
                    s.outputStream.write(raw.toByteArray())
                    s.outputStream.flush()
                    s.inputStream.bufferedReader().readLine()
                }
                assertTrue(resp.contains("403"), resp)
            }
        }
    }

    @Test
    @Timeout(60)
    fun overAskOverIsRefusedQuicklyWhenNoPromptCanShow() {
        assumeTrue(bin != null)
        GoPeer(bin!!).use { desk ->
            Phone(desk.fingerprint(), askOver = 1, approval = PushApproval { _, _, _, _, _ -> ApprovalOutcome.BUSY })
                .use { phone ->
                    desk.addPeer("127.0.0.1:${phone.port}")
                    val src = writeDir(mapOf("a.bin" to 10_240))
                    val job = desk.push(phone.identity.deviceId, listOf(src.absolutePath))
                    val state = desk.waitPush(job.get("id").asString, timeoutMs = 20_000)
                    assertTrue(state.startsWith("Failed") && state.contains("429"), state)
                    assertEquals(0, phone.placed.size)
                }
        }
    }
}
