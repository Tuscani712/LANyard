package io.github.tuscani712.lanyard.core

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The reachability steps against the real Go peer: TCP, TLS and a fingerprint
 * that either matches or has changed. Skipped unless `LANYARD_BIN` is set.
 */
class DiagnosticsLiveTest {
    private val bin: String? = System.getenv("LANYARD_BIN")?.takeIf { it.isNotBlank() }

    @Test
    @Timeout(60)
    fun reportsEachReachabilityStep() {
        assumeTrue(bin != null)
        GoPeer(bin!!).use { peer ->
            val identity = Identity.generate("Android")
            val fingerprint = peer.pairPayload().str("fp")
            val env = LiveEnv(identity)

            env.target = DiagTarget("desk", "127.0.0.1", peer.peerPort, fingerprint)
            val good = Diagnostics.run(env).associateBy { it.id }
            assertEquals(CheckStatus.Ok, good.getValue("reach-tcp").status)
            assertEquals(CheckStatus.Ok, good.getValue("reach-tls").status)
            assertEquals(CheckStatus.Ok, good.getValue("reach-fp").status)

            env.target = DiagTarget("desk", "127.0.0.1", peer.peerPort, "00".repeat(32))
            val changed = Diagnostics.run(env).associateBy { it.id }
            assertEquals(CheckStatus.Ok, changed.getValue("reach-tcp").status)
            assertEquals(CheckStatus.Ok, changed.getValue("reach-tls").status)
            assertEquals(CheckStatus.Failed, changed.getValue("reach-fp").status)
        }
    }

    private class LiveEnv(private val identity: Identity) : DiagEnv {
        var target: DiagTarget? = null

        override fun networkConnected() = true
        override fun networkMetered() = false
        override fun networkRestricted() = false
        override fun wifiOnly() = true
        override fun localAddresses() = listOf("127.0.0.1")
        override fun multicastLockHeld() = true
        override fun mdnsDevicesSeen(timeoutMillis: Long) = 0
        override fun reachTarget() = target
        override fun downloadFolderSelected() = false
        override fun downloadFolderFreeBytes() = -1L
        override fun notificationsGranted() = true
        override fun ignoringBatteryOptimizations() = true

        override fun probeTarget(host: String, port: Int, expectedFingerprint: String): Reachability {
            val tcp = try {
                Socket().use { it.connect(InetSocketAddress(host, port), 3000) }
                true
            } catch (_: Exception) {
                false
            }
            if (!tcp) return Reachability(tcp = false, tls = false)
            return try {
                val probe = ProbeClient(host, port, identity)
                probe.hello()
                val presented = probe.observedFingerprint()
                Reachability(true, true, presented, presented.equals(expectedFingerprint, ignoreCase = true))
            } catch (_: Exception) {
                Reachability(tcp = true, tls = false)
            }
        }
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString ?: ""
}
