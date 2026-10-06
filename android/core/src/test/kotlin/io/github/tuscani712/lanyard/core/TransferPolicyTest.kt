package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TransferPolicyTest {
    @Test
    fun blocksWhenWifiOnlyAndMetered() {
        assertNotNull(TransferPolicy.wifiOnlyRefusal(wifiOnly = true, metered = true))
    }

    @Test
    fun allowsWhenWifiOnlyButUnmetered() {
        assertNull(TransferPolicy.wifiOnlyRefusal(wifiOnly = true, metered = false))
    }

    @Test
    fun allowsWhenWifiOnlyIsOff() {
        assertNull(TransferPolicy.wifiOnlyRefusal(wifiOnly = false, metered = true))
    }

    @Test
    fun fakeMeteredNetworkDrivesTheGate() {
        // The gate takes the boolean from the MeteredNetwork seam, so a fake can
        // stand in for the real ConnectivityManager in a unit test.
        val metered = MeteredNetwork { true }
        val unmetered = MeteredNetwork { false }
        assertNotNull(TransferPolicy.wifiOnlyRefusal(true, metered.isMetered()))
        assertNull(TransferPolicy.wifiOnlyRefusal(true, unmetered.isMetered()))
    }
}
