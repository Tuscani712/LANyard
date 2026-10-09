package io.github.tuscani712.lanyard.core

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Files

/**
 * Unpairing against the real Go peer: the phone's `/trust/revoke` call must drop
 * it from the desktop's trust store, and a later pull must be refused. Skipped
 * unless `LANYARD_BIN` is set.
 */
class UnpairTest {
    private val bin: String? = System.getenv("LANYARD_BIN")?.takeIf { it.isNotBlank() }

    @Test
    @Timeout(60)
    fun unpairRevokesOnThePeerAndRefusesLaterPulls() {
        assumeTrue(bin != null)
        GoPeer(bin!!).use { peer ->
            val identity = Identity.generate("Android")
            val store = JsonFileTrustStore(File(Files.createTempDirectory("trust").toFile(), "peers.json"))
            val payload = peer.pairPayload()
            val fingerprint = payload.str("fp")
            val link = peer.buildLink(fingerprint, listOf("127.0.0.1:${peer.peerPort}"), payload.str("nonce"))
            val acceptor = Thread { peer.acceptPending(identity.deviceId) }
            acceptor.start()
            val result = PairingFlow.pair(link, identity, "Android test", store)
            acceptor.join()
            assertTrue(result is PairResult.Paired, "pairing failed: $result")

            // The desktop lists the phone as paired.
            assertEquals(1, peer.pairedDevices().size, "the desktop should list the phone after pairing")

            val client = PeerClient("127.0.0.1", peer.peerPort, identity, fingerprint)
            client.listShares() // reachable while paired

            // Unpair on the phone: revoke on the peer, then drop the local entry.
            client.revokeTrust()
            store.remove(fingerprint)

            assertTrue(store.list().isEmpty(), "the phone must drop its local entry")
            assertEquals(0, peer.pairedDevices().size, "the desktop must no longer list the phone")

            val refused = try {
                client.listShares()
                null
            } catch (e: PeerStatusException) {
                e.code
            }
            assertTrue(refused == 403 || refused == 401, "a later pull must be refused, got $refused")
        }
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString ?: ""
}
