package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * F3: a local device alias lives only in the trust store, is never sent on the
 * wire, never overwrites the broadcast name, and is forgotten on unpair (a
 * re-pair starts fresh).
 */
class DeviceAliasTest {
    private fun store(): Pair<JsonFileTrustStore, File> {
        val dir = Files.createTempDirectory("alias-trust").toFile()
        return JsonFileTrustStore(File(dir, "peers.json")) to File(dir, "peers.json")
    }

    private fun peer(fp: String, name: String = "Broadcast PC", alias: String = "") = PairedPeer(
        fingerprint = fp, name = name, host = "10.0.0.2", port = 47800,
        browse = true, push = true, pairedAt = 1, alias = alias,
    )

    @Test
    fun aliasRoundTripsThroughTheStore() {
        val (store, file) = store()
        store.save(peer("aa".repeat(32), alias = "My Laptop"))

        val reopened = JsonFileTrustStore(file)
        assertEquals("My Laptop", reopened.find("aa".repeat(32))?.alias)
        assertEquals("Broadcast PC", reopened.find("aa".repeat(32))?.name)
    }

    @Test
    fun displayFallsBackToTheBroadcastName() {
        assertEquals("Alias", DeviceNames.display("Broadcast", "Alias"))
        assertEquals("Broadcast", DeviceNames.display("Broadcast", ""))
        assertEquals("Broadcast", DeviceNames.display("Broadcast", "   "))
        assertEquals("Unnamed device", DeviceNames.display("", ""))
    }

    @Test
    fun logLabelCarriesAliasAndShortFingerprint() {
        val fp = "0123456789abcdef".repeat(4)
        assertEquals("Alias (01234567)", DeviceNames.logLabel("Broadcast", "Alias", fp))
        assertEquals("Broadcast (01234567)", DeviceNames.logLabel("Broadcast", "", fp))
    }

    @Test
    fun clearingTheAliasRevertsToTheBroadcastName() {
        val (store, _) = store()
        val fp = "bb".repeat(32)
        store.save(peer(fp, alias = "Temporary"))
        store.save(store.find(fp)!!.copy(alias = ""))
        assertEquals("", store.find(fp)?.alias)
        assertEquals("Broadcast PC", DeviceNames.display(store.find(fp)!!))
    }

    @Test
    fun unpairForgetsTheAlias() {
        val (store, _) = store()
        val fp = "cc".repeat(32)
        store.save(peer(fp, alias = "Named"))
        store.remove(fp)
        assertNull(store.find(fp))
    }

    @Test
    fun aRepairStartsFreshUnlessTheAliasIsReset() {
        val (store, _) = store()
        val fp = "dd".repeat(32)
        store.save(peer(fp, alias = "Old Name"))
        // A fresh pairing writes a new entry for the same fingerprint with no
        // alias, so the old local name is not silently carried over.
        store.save(peer(fp, name = "Broadcast PC", alias = ""))
        assertEquals("", store.find(fp)?.alias)
    }

    @Test
    fun normalizeAliasTrimsAndBounds() {
        assertEquals("Tablet", DeviceNames.normalizeAlias("  Tablet  "))
        assertEquals("", DeviceNames.normalizeAlias("   "))
        assertTrue(DeviceNames.normalizeAlias("x".repeat(200)).length <= Display.MAX_NAME)
    }
}
