package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** A peers.json written before the push-permission fields existed still loads. */
class PairedPeerMigrationTest {
    @Test
    fun oldEntryLoadsWithSafeDefaults() {
        val f = Files.createTempFile("peers", ".json").toFile()
        f.writeText(
            """{"ab":{"fingerprint":"ab","name":"Old","host":"10.0.0.1","port":47800,"browse":true,"push":true,"pairedAt":123}}""",
        )
        val store = JsonFileTrustStore(f)
        val p = store.find("ab")
        assertNotNull(p)
        assertEquals(0L, p!!.pushMaxBytes)
        assertEquals(0L, p.askOver)
        assertEquals(true, p.push)
    }

    @Test
    fun newFieldsRoundTrip() {
        val f = Files.createTempFile("peers", ".json").toFile().also { it.delete() }
        val store = JsonFileTrustStore(f)
        store.save(PairedPeer("cd", "New", "h", 1, true, true, 9, pushMaxBytes = 10, askOver = 5))
        val p = store.find("cd")!!
        assertEquals(10L, p.pushMaxBytes)
        assertEquals(5L, p.askOver)
    }
}
