package io.github.tuscani712.lanyard.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import io.github.tuscani712.lanyard.core.DiagLevel
import io.github.tuscani712.lanyard.core.Display

/**
 * Browses for `_lanyard._tcp` services with [NsdManager], decoding the same TXT
 * records the Go side advertises (read `internal/discovery/mdns.go`): `v`, `id`,
 * `did`, `n`, `os`, `p`.
 *
 * A [WifiManager.MulticastLock] is held only while discovering, so the radio
 * can deliver multicast packets; it is always released by [stop].
 */
class NsdDiscovery(
    context: Context,
    // One line per discovery event for the diagnostics report, with its level.
    private val diag: (String, DiagLevel) -> Unit = { _, _ -> },
) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var lock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private val resolving = HashSet<String>()
    private val shortByService = HashMap<String, String>()

    fun start(onFound: (NearbyDevice) -> Unit, onLost: (String) -> Unit, trigger: String = "screen") {
        if (listener != null) return
        lock = wifiManager.createMulticastLock("lanyard-mdns").apply {
            setReferenceCounted(true)
            acquire()
        }
        diag("[discovery] mdns browse start trigger=$trigger result=ok", DiagLevel.Info)

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                diag("[discovery] mdns browse start result=failed error=$errorCode", DiagLevel.Warn)
                stop()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = stop()

            override fun onServiceFound(info: NsdServiceInfo) {
                if (!sameServiceType(info.serviceType, SERVICE_TYPE)) return
                if (!resolving.add(info.serviceName)) return
                try {
                    nsdManager.resolveService(info, resolver(onFound))
                } catch (_: IllegalArgumentException) {
                    resolving.remove(info.serviceName)
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                resolving.remove(info.serviceName)
                onLost(shortByService.remove(info.serviceName) ?: info.serviceName)
            }
        }
        listener = discovery
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (_: Exception) {
            stop()
        }
    }

    fun stop() {
        listener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (_: Exception) {
                // already stopped
            }
        }
        if (listener != null) diag("[discovery] mdns browse stop result=ok", DiagLevel.Info)
        listener = null
        resolving.clear()
        lock?.let {
            try {
                if (it.isHeld) it.release()
            } catch (_: Exception) {
                // ignore
            }
        }
        lock = null
    }

    private fun resolver(onFound: (NearbyDevice) -> Unit) = object : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
            resolving.remove(info.serviceName)
            diag("[discovery] mdns resolve service=${info.serviceName} result=failed error=$errorCode", DiagLevel.Warn)
        }

        override fun onServiceResolved(info: NsdServiceInfo) {
            val host = info.host?.hostAddress ?: return
            val attrs = info.attributes.mapValues { String(it.value) }
            val shortId = attrs["id"] ?: info.serviceName
            shortByService[info.serviceName] = shortId
            diag("[discovery] mdns resolved id=${Display.shortFp(shortId)} addr=$host:${attrs["p"]?.toIntOrNull() ?: info.port} source=mdns result=ok", DiagLevel.Debug)
            onFound(
                NearbyDevice(
                    shortId = shortId,
                    deviceLabel = attrs["did"] ?: "",
                    name = attrs["n"] ?: info.serviceName,
                    host = host,
                    port = attrs["p"]?.toIntOrNull() ?: info.port,
                    os = attrs["os"] ?: "",
                ),
            )
        }
    }

    /**
     * Android reports the discovered service type inconsistently (with or
     * without the trailing dot, and in mixed case), so match leniently rather
     * than dropping every device on an exact comparison.
     */
    private fun sameServiceType(a: String?, b: String): Boolean {
        if (a == null) return false
        fun norm(s: String) = s.trim().trimEnd('.').lowercase()
        return norm(a) == norm(b)
    }

    private companion object {
        const val SERVICE_TYPE = "_lanyard._tcp."
    }
}
