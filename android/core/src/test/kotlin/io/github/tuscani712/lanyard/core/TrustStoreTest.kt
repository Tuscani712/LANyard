package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class TrustStoreTest {
    private fun store(): Pair<JsonFileTrustStore, File> {
        val dir = Files.createTempDirectory("trust").toFile()
        val file = File(dir, "peers.json")
        return JsonFileTrustStore(file) to file
    }

    private fun peer(fp: String, name: String = "peer") = PairedPeer(
        fingerprint = fp, name = name, host = "10.0.0.2", port = 47800,
        browse = true, push = false, pairedAt = 1_700_000_000_000L,
    )

    @Test
    fun savesFindsAndLists() {
        val (store, _) = store()
        store.save(peer("AA".repeat(32), "alpha"))
        store.save(peer("bb".repeat(32), "beta"))

        val found = store.find("AA".repeat(32))
        assertEquals("alpha", found?.name)
        // Fingerprints are normalized to lowercase on save and lookup.
        assertEquals("bb".repeat(32), store.find("BB".repeat(32))?.fingerprint)
        assertEquals(2, store.list().size)
    }

    @Test
    fun saveReplacesSameFingerprint() {
        val (store, _) = store()
        store.save(peer("cc".repeat(32), "first"))
        store.save(peer("CC".repeat(32), "second"))
        assertEquals(1, store.list().size)
        assertEquals("second", store.find("cc".repeat(32))?.name)
    }

    @Test
    fun removeDeletes() {
        val (store, _) = store()
        store.save(peer("dd".repeat(32)))
        store.remove("DD".repeat(32))
        assertNull(store.find("dd".repeat(32)))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun missingFileReadsEmpty() {
        val (store, _) = store()
        assertTrue(store.list().isEmpty())
        assertNull(store.find("ee".repeat(32)))
    }

    @Test
    fun corruptFileReadsEmpty() {
        val (store, file) = store()
        file.writeText("{ this is not json")
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun writesAtomicallyLeavingNoTempFile() {
        val (store, file) = store()
        store.save(peer("ff".repeat(32)))
        assertTrue(file.isFile)
        val leftovers = file.parentFile.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }
        assertTrue(leftovers.isEmpty(), "temp file(s) left behind: $leftovers")
    }

    @Test
    fun roundTripsAcrossInstances() {
        val dir = Files.createTempDirectory("trust2").toFile()
        val file = File(dir, "peers.json")
        JsonFileTrustStore(file).save(peer("11".repeat(32), "persisted"))

        val reopened = JsonFileTrustStore(file)
        assertEquals("persisted", reopened.find("11".repeat(32))?.name)
        assertFalse(reopened.list().isEmpty())
    }
}
