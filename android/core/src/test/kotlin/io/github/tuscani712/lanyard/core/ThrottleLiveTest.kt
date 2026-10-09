package io.github.tuscani712.lanyard.core

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

/**
 * Downloads 20 MB from the real Go peer through a [RateThrottle] and checks the
 * achieved rate is within 15% of the limit. Skipped unless `LANYARD_BIN` is set.
 */
class ThrottleLiveTest {
    private val bin: String? = System.getenv("LANYARD_BIN")?.takeIf { it.isNotBlank() }

    @Test
    @Timeout(120)
    fun downloadRunsAtTheBandwidthLimit() {
        assumeTrue(bin != null)
        GoPeer(bin!!).use { peer ->
            val identity = Identity.generate("Android")
            val store = JsonFileTrustStore(File(Files.createTempDirectory("trust").toFile(), "peers.json"))
            val payload = peer.pairPayload()
            val fingerprint = payload.str("fp")
            val link = peer.buildLink(fingerprint, listOf("127.0.0.1:${peer.peerPort}"), payload.str("nonce"))
            val acceptor = Thread { peer.acceptPending(identity.deviceId) }
            acceptor.start()
            val paired = PairingFlow.pair(link, identity, "Android test", store)
            acceptor.join()
            assertTrue(paired is PairResult.Paired, "pairing failed: $paired")

            val shareDir = Files.createTempDirectory("share").toFile()
            val bytes = ByteArray(20 * 1024 * 1024).also { java.util.Random(4).nextBytes(it) }
            File(shareDir, "big.bin").writeBytes(bytes)
            val shareId = peer.addShare(shareDir.absolutePath, "big").str("share_id")

            val outDir = Files.createTempDirectory("dl").toFile()
            val limit = 4L * 1024 * 1024
            val client = PeerClient("127.0.0.1", peer.peerPort, identity, fingerprint)
            val started = System.nanoTime()
            val result = DownloadSession(client, RateThrottle(limit)).download(shareId, "", { file ->
                val dest = File(outDir, file.name.ifEmpty { "file" })
                DownloadTarget(existingSize = 0, openAt = { FileOutputStream(dest, false) })
            })
            val seconds = (System.nanoTime() - started) / 1e9

            assertTrue(result is DownloadResult.Done, "expected Done, got $result")
            val achieved = (20.0 * 1024 * 1024) / seconds
            assertTrue(
                achieved in limit * 0.85..limit * 1.15,
                "achieved $achieved bytes/s in $seconds s, want ~$limit",
            )
        }
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString ?: ""
}
