package io.github.tuscani712.lanyard.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IdentityTest {
    @Test
    fun generatesAStableDeviceId() {
        val id = Identity.generate("test")
        assertEquals(64, id.deviceId.length)
        assertTrue(id.deviceId.all { it.isDigit() || it in 'a'..'f' }, "device id must be lowercase hex")
        // The Device ID is the fingerprint of the certificate's public key.
        assertEquals(Identity.fingerprintOf(id.certificate), id.deviceId)
    }

    @Test
    fun twoIdentitiesDiffer() {
        assertTrue(Identity.generate("a").deviceId != Identity.generate("b").deviceId)
    }

    @Test
    fun saveAndLoadRoundTrips() {
        val dir = Files.createTempDirectory("lanyard-id").toFile()
        val store = FileIdentityStore(dir)
        val original = Identity.generate("roundtrip")
        store.save(original)

        val loaded = assertNotNull(store.load())
        assertEquals(original.deviceId, loaded.deviceId)
        assertEquals(
            Identity.fingerprintOf(original.certificate),
            Identity.fingerprintOf(loaded.certificate),
        )
    }
}
