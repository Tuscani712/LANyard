package io.github.tuscani712.lanyard.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager

/**
 * Browses for `_lanyard._tcp` services with [NsdManager], decoding the same TXT
 * records the Go side advertises (read `internal/discovery/mdns.go`): `v`, `id`,
 * `did`, `n`, `os`, `p`.
 *
 * A [WifiManager.MulticastLock] is held only while discovering, so the radio
 * can deliver multicast packets; it is always released by [stop].
 */
class NsdDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var lock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private val resolving = HashSet<String>()

    fun start(onFound: (NearbyDevice) -> Unit, onLost: (String) -> Unit) {
        if (listener != null) return
        lock = wifiManager.createMulticastLock("lanyard-mdns").apply {
            setReferenceCounted(true)
            acquire()
        }

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = stop()
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = stop()

            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType != SERVICE_TYPE) return
                if (!resolving.add(info.serviceName)) return
                try {
                    nsdManager.resolveService(info, resolver(onFound))
                } catch (_: IllegalArgumentException) {
                    resolving.remove(info.serviceName)
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                resolving.remove(info.serviceName)
                onLost(info.serviceName)
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
        }

        override fun onServiceResolved(info: NsdServiceInfo) {
            val host = info.host?.hostAddress ?: return
            val attrs = info.attributes.mapValues { String(it.value) }
            onFound(
                NearbyDevice(
                    shortId = attrs["id"] ?: info.serviceName,
                    deviceLabel = attrs["did"] ?: "",
                    name = attrs["n"] ?: info.serviceName,
                    host = host,
                    port = attrs["p"]?.toIntOrNull() ?: info.port,
                    os = attrs["os"] ?: "",
                ),
            )
        }
    }

    private companion object {
        const val SERVICE_TYPE = "_lanyard._tcp"
    }
}
