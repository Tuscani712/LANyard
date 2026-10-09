package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Files

/**
 * Live: a real Go desktop pulls a share from a JVM [PeerServer] (the phone).
 * Exercises list -> manifest -> file (verified via /hash, since we send no
 * trailer) -> complete. Skipped unless `LANYARD_BIN` is set.
 */
class ShareServerLiveTest {
    private val bin: String? = System.getenv("LANYARD_BIN")?.takeIf { it.isNotBlank() }

    private class Phone(deskFp: String, root: File) : AutoCloseable {
        val identity: Identity = Identity.generate("Pixel 8 Pro")
        private val trustFile = File.createTempFile("lanyard-trust", ".json").also { it.delete() }
        val trust: TrustStore = JsonFileTrustStore(trustFile)
        val spool = Files.createTempDirectory("lanyard-spool").toFile()
        val sessions = PairingSessions(selfFp = { identity.deviceId }, trust = trust)
        private val receiver = InboxReceiver(spool, PushDestination { rel, _, _ -> rel }, { 1L shl 40 })
        val shareServer = ShareServer(DirShareSource("s1", root))
        private val server = PeerServer(
            sessions = sessions,
            receiver = receiver,
            invites = PairInvites(),
            isPaired = { trust.find(it) },
            onUnpair = { trust.remove(it) },
            shares = shareServer,
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

        init {
            trust.save(PairedPeer(deskFp, "Desktop", "127.0.0.1", 0, browse = true, push = true, pairedAt = 1))
        }

        override fun close() = server.stop()
    }

    @Test
    @Timeout(120)
    fun desktopPullsAFileSetFromThePhone() {
        assumeTrue(bin != null)
        val root = Files.createTempDirectory("lanyard-src-share").toFile()
        File(root, "small.bin").writeBytes(ByteArray(10_240) { (it % 251).toByte() })
        File(root, "dir").mkdirs()
        File(root, "dir/large.bin").writeBytes(ByteArray(3_000_000) { (it % 251).toByte() })

        GoPeer(bin!!).use { desk ->
            Phone(desk.fingerprint(), root).use { phone ->
                desk.addPeer("127.0.0.1:${phone.port}")
                val shares = desk.remoteShares(phone.identity.deviceId)
                assertTrue(shares.any { it.asJsonObject.get("share_id").asString == "s1" }, shares.toString())

                val dest = Files.createTempDirectory("lanyard-dest-share").toFile()
                val job = desk.download(phone.identity.deviceId, "s1", listOf(""), dest.absolutePath)
                assertEquals("Done", desk.waitPush(job.get("id").asString))
                assertEquals(10_240L, File(dest, "small.bin").length())
                assertEquals(3_000_000L, File(dest, "dir/large.bin").length())
            }
        }
    }

    @Test
    @Timeout(60)
    fun unpairedClientIsRefused() {
        assumeTrue(bin != null)
        val root = Files.createTempDirectory("lanyard-src-share2").toFile()
        File(root, "a.bin").writeBytes(ByteArray(4))
        GoPeer(bin!!).use { desk ->
            Phone(desk.fingerprint(), root).use { phone ->
                val stranger = Identity.generate("Stranger")
                val factory = Tls.socketFactory(stranger, phone.identity.deviceId)
                val resp = factory.createSocket("127.0.0.1", phone.port).use { s ->
                    (s as javax.net.ssl.SSLSocket).startHandshake()
                    s.outputStream.write("GET /api/v1/shares HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                    s.outputStream.flush()
                    s.inputStream.bufferedReader().readLine()
                }
                assertTrue(resp.contains("403"), resp)
            }
        }
    }
}
