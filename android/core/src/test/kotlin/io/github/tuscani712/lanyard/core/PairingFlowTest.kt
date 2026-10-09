package io.github.tuscani712.lanyard.core

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom

/**
 * [PairingFlow] against the real Go peer. Skipped unless `LANYARD_BIN` is set
 * (scripts/test-core.sh sets it).
 */
class PairingFlowTest {
    private val bin: String? = System.getenv("LANYARD_BIN")?.takeIf { it.isNotBlank() }

    private fun store(): TrustStore {
        val dir = Files.createTempDirectory("trust").toFile()
        return JsonFileTrustStore(File(dir, "peers.json"))
    }

    @Test
    @Timeout(120)
    fun pairsSuccessfully() {
        assumeTrue(bin != null)
        GoPeer(bin!!).use { peer ->
            val identity = Identity.generate("Android")
            val store = store()
            val payload = peer.pairPayload()
            val fp = payload.str("fp")
            val link = peer.buildLink(fp, listOf("127.0.0.1:${peer.peerPort}"), payload.str("nonce"))

            val acceptor = Thread { peer.acceptPending(identity.deviceId) }
            acceptor.start()
            val result = PairingFlow.pair(link, identity, "Android test", store)
            acceptor.join()

            assertTrue(result is PairResult.Paired, "expected Paired, got $result")
            val record = store.find(fp)
            assertNotNull(record, "the pairing must be saved to the trust store")
            assertEquals(fp.lowercase(), record!!.fingerprint)
            assertEquals("127.0.0.1", record.host)
            assertEquals(peer.peerPort, record.port)
            assertTrue(record.allowBrowse, "the peer must have allowed browsing")
        }
    }

    @Test
    @Timeout(60)
    fun wrongFingerprintInLinkFailsClosed() {
        assumeTrue(bin != null)
        GoPeer(bin!!).use { peer ->
            val identity = Identity.generate("Android")
            val store = store()
            val link = peer.buildLink("00".repeat(32), listOf("127.0.0.1:${peer.peerPort}"), randomHex(16))

            val result = PairingFlow.pair(link, identity, "Android test", store)

            assertTrue(result is PairResult.FingerprintMismatch, "expected FingerprintMismatch, got $result")
            assertTrue(store.list().isEmpty(), "nothing may be saved on a mismatch")
        }
    }

    @Test
    @Timeout(60)
    fun badInviteIsRefused() {
        assumeTrue(bin != null)
        GoPeer(bin!!).use { peer ->
            val identity = Identity.generate("Android")
            val store = store()
            val fp = peer.pairPayload().str("fp")
            val link = peer.buildLink(fp, listOf("127.0.0.1:${peer.peerPort}"), randomHex(16))

            val result = PairingFlow.pair(link, identity, "Android test", store)

            assertTrue(result is PairResult.Refused, "expected Refused, got $result")
            assertTrue(store.list().isEmpty())
        }
    }

    @Test
    @Timeout(60)
    fun unreachableAddressReportsUnreachable() {
        assumeTrue(bin != null)
        GoPeer(bin!!).use { peer ->
            val identity = Identity.generate("Android")
            val store = store()
            val closed = GoPeer.closedPort()
            val link = peer.buildLink("ab".repeat(32), listOf("127.0.0.1:$closed"), randomHex(16))

            val result = PairingFlow.pair(link, identity, "Android test", store, timeoutMs = 5_000)

            assertTrue(result is PairResult.Unreachable, "expected Unreachable, got $result")
        }
    }

    private fun randomHex(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString ?: ""
}
