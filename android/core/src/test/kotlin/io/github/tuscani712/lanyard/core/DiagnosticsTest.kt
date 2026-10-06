package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagnosticsTest {
    private class FakeEnv : DiagEnv {
        var connected = true
        var metered = false
        var restricted = false
        var wifiOnly = true
        var addresses = listOf("10.0.0.5")
        var multicast = true
        var mdnsSeen = 2
        var target: DiagTarget? = null
        var reach = Reachability(tcp = true, tls = true, presentedFingerprint = "fp", match = true)
        var folderSelected = true
        var freeBytes = 500L * 1024 * 1024
        var notifications = true
        var ignoringBattery = true

        override fun networkConnected() = connected
        override fun networkMetered() = metered
        override fun networkRestricted() = restricted
        override fun wifiOnly() = wifiOnly
        override fun localAddresses() = addresses
        override fun multicastLockHeld() = multicast
        override fun mdnsDevicesSeen(timeoutMillis: Long) = mdnsSeen
        override fun reachTarget() = target
        override fun probeTarget(host: String, port: Int, expectedFingerprint: String) = reach
        override fun downloadFolderSelected() = folderSelected
        override fun downloadFolderFreeBytes() = freeBytes
        override fun notificationsGranted() = notifications
        override fun ignoringBatteryOptimizations() = ignoringBattery
    }

    private fun status(env: FakeEnv, id: String): CheckStatus =
        Diagnostics.run(env).first { it.id == id }.status

    private fun detail(env: FakeEnv, id: String): String =
        Diagnostics.run(env).first { it.id == id }.detail

    @Test
    fun healthyEnvironmentIsAllGreen() {
        val env = FakeEnv().apply { target = DiagTarget("Desk", "10.0.0.9", 47800, "ff".repeat(32)) }
        val results = Diagnostics.run(env)
        for (r in results) {
            assertEquals(CheckStatus.Ok, r.status, "check ${r.id}: ${r.detail}")
        }
        assertFalse(results.any { it.id == "peer-port" || it.id == "firewall" || it.id == "clock" })
    }

    @Test
    fun disconnectedNetworkFails() {
        assertEquals(CheckStatus.Failed, status(FakeEnv().apply { connected = false }, "network"))
    }

    @Test
    fun meteredWithWifiOnlyWarnsAboutMobileData() {
        val env = FakeEnv().apply { metered = true; wifiOnly = true }
        assertEquals(CheckStatus.Warning, status(env, "network"))
        assertTrue(detail(env, "network").contains("Wi-Fi only is on and you are on mobile data"))
    }

    @Test
    fun meteredWithoutWifiOnlyWarnsAboutMetered() {
        val env = FakeEnv().apply { metered = true; wifiOnly = false }
        assertEquals(CheckStatus.Warning, status(env, "network"))
        assertTrue(detail(env, "network").contains("metered or mobile"))
    }

    @Test
    fun restrictedNetworkWarns() {
        assertEquals(CheckStatus.Warning, status(FakeEnv().apply { restricted = true }, "network"))
    }

    @Test
    fun missingAddressesFail() {
        assertEquals(CheckStatus.Failed, status(FakeEnv().apply { addresses = emptyList() }, "addresses"))
    }

    @Test
    fun multicastUnavailableWarns() {
        assertEquals(CheckStatus.Warning, status(FakeEnv().apply { multicast = false }, "multicast"))
    }

    @Test
    fun noDiscoveredDevicesWarns() {
        assertEquals(CheckStatus.Warning, status(FakeEnv().apply { mdnsSeen = 0 }, "mdns"))
    }

    @Test
    fun reachabilityGoodPath() {
        val env = FakeEnv().apply { target = DiagTarget("Desk", "10.0.0.9", 47800, "ff".repeat(32)) }
        assertEquals(CheckStatus.Ok, status(env, "reach-tcp"))
        assertEquals(CheckStatus.Ok, status(env, "reach-tls"))
        assertEquals(CheckStatus.Ok, status(env, "reach-fp"))
    }

    @Test
    fun reachabilityTcpFailureSkipsLaterSteps() {
        val env = FakeEnv().apply {
            target = DiagTarget("Desk", "10.0.0.9", 47800, "ff".repeat(32))
            reach = Reachability(tcp = false, tls = false)
        }
        assertEquals(CheckStatus.Failed, status(env, "reach-tcp"))
        assertEquals(CheckStatus.Skipped, status(env, "reach-tls"))
        assertEquals(CheckStatus.Skipped, status(env, "reach-fp"))
    }

    @Test
    fun reachabilityTlsFailureSkipsIdentity() {
        val env = FakeEnv().apply {
            target = DiagTarget("Desk", "10.0.0.9", 47800, "ff".repeat(32))
            reach = Reachability(tcp = true, tls = false)
        }
        assertEquals(CheckStatus.Ok, status(env, "reach-tcp"))
        assertEquals(CheckStatus.Failed, status(env, "reach-tls"))
        assertEquals(CheckStatus.Skipped, status(env, "reach-fp"))
    }

    @Test
    fun identityMismatchFailsAndOffersNoTrustShortcut() {
        val env = FakeEnv().apply {
            target = DiagTarget("Desk", "10.0.0.9", 47800, "ff".repeat(32))
            reach = Reachability(tcp = true, tls = true, presentedFingerprint = "00".repeat(32), match = false)
        }
        val row = Diagnostics.run(env).first { it.id == "reach-fp" }
        assertEquals(CheckStatus.Failed, row.status)
        assertTrue(row.detail.contains("identity changed"))
        assertTrue(row.fix.contains("pair again"))
        assertFalse(row.fix.contains("trust it", ignoreCase = true))
    }

    @Test
    fun reachabilitySkippedWithoutATarget() {
        assertEquals(CheckStatus.Skipped, status(FakeEnv().apply { target = null }, "reach-tcp"))
    }

    @Test
    fun diskSkippedWhenNoFolderSet() {
        assertEquals(CheckStatus.Skipped, status(FakeEnv().apply { folderSelected = false }, "disk"))
    }

    @Test
    fun lowDiskSpaceWarns() {
        assertEquals(CheckStatus.Warning, status(FakeEnv().apply { freeBytes = 1024L }, "disk"))
    }

    @Test
    fun unreadableDiskWarns() {
        assertEquals(CheckStatus.Warning, status(FakeEnv().apply { freeBytes = -1L }, "disk"))
    }

    @Test
    fun notificationsBlockedWarns() {
        assertEquals(CheckStatus.Warning, status(FakeEnv().apply { notifications = false }, "notifications"))
    }

    @Test
    fun batteryRestrictedWarns() {
        assertEquals(CheckStatus.Warning, status(FakeEnv().apply { ignoringBattery = false }, "battery"))
    }

    @Test
    fun redactionRemovesSecretsButKeepsShortFingerprint() {
        val fingerprint = "ab12cd34".repeat(8)
        val nonce = "0123456789abcdef0123456789abcdef"
        val token = "deadbeefdeadbeefdeadbeefdeadbeef"
        val link = "lanyard://pair?fp=$fingerprint&addr=192.168.1.5:47800&n=$nonce"
        val out = Redaction.redact("link $link token ?t=$token fingerprint $fingerprint ip 10.0.0.9")

        assertFalse(out.contains(fingerprint))
        assertFalse(out.contains(nonce))
        assertFalse(out.contains(token))
        assertFalse(out.contains("192.168.1.5"))
        assertFalse(out.contains("10.0.0.9"))
        assertTrue(out.contains(fingerprint.take(8)))
    }

    @Test
    fun reportOmitsDeviceNameAndAddresses() {
        val env = FakeEnv().apply {
            target = DiagTarget("SecretLaptop", "192.168.1.9", 47800, "fa".repeat(32))
        }
        val report = Diagnostics.copyReport(Diagnostics.run(env))
        assertFalse(report.contains("SecretLaptop"))
        assertFalse(report.contains("192.168.1.9"))
        assertFalse(report.contains("fa".repeat(32)))
    }
}
