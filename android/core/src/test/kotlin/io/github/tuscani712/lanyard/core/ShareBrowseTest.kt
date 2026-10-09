package io.github.tuscani712.lanyard.core

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Files

/**
 * After pairing, the client lists the peer's shares and browses a share's tree
 * against the real Go peer. Skipped unless `LANYARD_BIN` is set.
 */
class ShareBrowseTest {
    private val bin: String? = System.getenv("LANYARD_BIN")?.takeIf { it.isNotBlank() }

    @Test
    @Timeout(120)
    fun pairedPeerSeesGoShareAndTree() {
        assumeTrue(bin != null)
        GoPeer(bin!!).use { peer ->
            val identity = Identity.generate("Android")
            val dir = Files.createTempDirectory("trust").toFile()
            val store = JsonFileTrustStore(File(dir, "peers.json"))
            val payload = peer.pairPayload()
            val fp = payload.str("fp")
            val link = peer.buildLink(fp, listOf("127.0.0.1:${peer.peerPort}"), payload.str("nonce"))

            val acceptor = Thread { peer.acceptPending(identity.deviceId) }
            acceptor.start()
            val result = PairingFlow.pair(link, identity, "Android test", store)
            acceptor.join()
            assertTrue(result is PairResult.Paired, "expected Paired, got $result")

            // The Go side creates a share ...
            val shareDir = Files.createTempDirectory("share").toFile()
            File(shareDir, "hello.txt").writeText("shared from desktop\n")
            val share = peer.addShare(shareDir.absolutePath, "test-share")
            val shareId = share.str("share_id")
            assertTrue(shareId.isNotEmpty(), "the Go share must have an id")

            // ... and the paired peer (browse granted) sees it and its tree.
            val client = PeerClient("127.0.0.1", peer.peerPort, identity, fp)
            val shares = client.listShares()
            assertTrue(
                shares.any { it.str("share_id") == shareId },
                "the paired peer must see the share; got ${shares.map { it.str("label") }}",
            )
            val tree = client.tree(shareId, "")
            assertTrue(
                tree.any { it.str("name") == "hello.txt" },
                "the tree must list the shared file; got ${tree.map { it.str("name") }}",
            )
        }
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString ?: ""
}
