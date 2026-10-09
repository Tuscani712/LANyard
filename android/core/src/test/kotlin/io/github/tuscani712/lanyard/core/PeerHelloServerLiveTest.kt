package io.github.tuscani712.lanyard.core

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * The real interop test: a JVM-side [PeerHelloServer] is added to a live Go
 * desktop as a nearby device by address. The desktop must verify it (the
 * certificate it presents is the one it announces) and show its name; a wrong
 * pinned fingerprint must be refused. Skipped unless `LANYARD_BIN` is set.
 */
class PeerHelloServerLiveTest {
    private val bin: String? = System.getenv("LANYARD_BIN")?.takeIf { it.isNotBlank() }

    @Test
    @Timeout(60)
    fun desktopListsThePhoneAsVerifiedAndRefusesAWrongFingerprint() {
        assumeTrue(bin != null)
        GoPeer(bin!!).use { peer ->
            val phone = Identity.generate("Pixel 8 Pro")
            val server = PeerHelloServer(hello = { port ->
                JsonObject().apply {
                    addProperty("device_id", phone.deviceId.take(16))
                    addProperty("fingerprint", phone.deviceId)
                    addProperty("name", "Pixel 8 Pro")
                    addProperty("os", "android")
                    addProperty("version", "0.1.0-beta.3")
                    addProperty("port", port)
                }
            })
            try {
                val port = server.start(phone)
                val listed = peer.addPeer("127.0.0.1:$port")
                assertTrue(listed.get("verified").asBoolean, "desktop did not verify the phone: $listed")
                assertEquals("Pixel 8 Pro", listed.get("name").asString)
                assertEquals(phone.deviceId, listed.get("device_id").asString)

                // A wrong pinned fingerprint is refused before the peer is recorded.
                val refused = runCatching { peer.addPeer("127.0.0.1:$port", "00".repeat(32)) }
                assertTrue(refused.isFailure, "add with a wrong fingerprint should fail, got ${refused.getOrNull()}")
            } finally {
                server.stop()
            }
        }
    }
}
