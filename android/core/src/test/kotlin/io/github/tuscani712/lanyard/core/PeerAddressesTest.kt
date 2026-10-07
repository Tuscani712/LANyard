package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Filling a port-less paired peer's address from mDNS discovery. */
class PeerAddressesTest {
    private fun peer(fp: String, host: String = "", port: Int = 0) =
        PairedPeer(fingerprint = fp, name = "Desk", host = host, port = port, browse = true, push = true, pairedAt = 0)

    @Test
    fun fillsPortAndHostWhenThePeerHasNoPort() {
        val fp = "4d635a83f4033d53" + "a1b2c3d4e5f60718" // 32 chars; short id is first 16
        val updates = PeerAddresses.fillFromDiscovery(
            listOf(peer(fp)),
            listOf(DiscoveredAddr("4d635a83f4033d53", "192.168.1.20", 47800)),
        )
        assertEquals(1, updates.size)
        assertEquals("192.168.1.20", updates[0].host)
        assertEquals(47800, updates[0].port)
    }

    @Test
    fun leavesPeersThatAlreadyHaveAPort() {
        val fp = "4d635a83f4033d53" + "a1b2c3d4e5f60718"
        val updates = PeerAddresses.fillFromDiscovery(
            listOf(peer(fp, host = "10.0.0.5", port = 47800)),
            listOf(DiscoveredAddr("4d635a83f4033d53", "192.168.1.20", 55555)),
        )
        assertTrue(updates.isEmpty())
    }

    @Test
    fun noMatchMeansNoUpdate() {
        val updates = PeerAddresses.fillFromDiscovery(
            listOf(peer("aaaaaaaaaaaaaaaa" + "0000000000000000")),
            listOf(DiscoveredAddr("bbbbbbbbbbbbbbbb", "192.168.1.9", 47800)),
        )
        assertTrue(updates.isEmpty())
    }

    @Test
    fun matchingIgnoresCaseAndWhitespace() {
        val fp = "4D635A83F4033D53" + "0000000000000000"
        val updates = PeerAddresses.fillFromDiscovery(
            listOf(peer(fp)),
            listOf(DiscoveredAddr("4d635a83f4033d53", "192.168.1.20", 47800)),
        )
        assertEquals(1, updates.size)
        assertEquals(47800, updates[0].port)
    }

    @Test
    fun ignoresInvalidDiscoveredPorts() {
        val fp = "4d635a83f4033d53" + "0000000000000000"
        val updates = PeerAddresses.fillFromDiscovery(
            listOf(peer(fp)),
            listOf(DiscoveredAddr("4d635a83f4033d53", "192.168.1.20", 0)),
        )
        assertTrue(updates.isEmpty())
    }
}
