package io.github.tuscani712.lanyard.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * Advertises this device over mDNS as `_lanyard._tcp` with the same TXT records
 * the Go side publishes (`v`, `id`, `did`, `n`, `os`, `p`), so a desktop (or
 * another phone) can discover it. The record is removed in [stop].
 *
 * The instance name is the short Device ID; Android may rename it on a
 * conflict, which is fine because peers key on the `id` attribute.
 */
class NsdAdvertiser(context: Context) {
    private val nsdManager =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun start(instanceName: String, txt: Map<String, String>, port: Int) {
        stop()
        val info = NsdServiceInfo().apply {
            serviceName = instanceName
            serviceType = SERVICE_TYPE
            setPort(port)
            txt.forEach { (k, v) -> if (v.isNotEmpty()) setAttribute(k, v) }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                if (listener === this) listener = null
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        listener = l
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (_: Exception) {
            listener = null
        }
    }

    fun stop() {
        listener?.let { l -> runCatching { nsdManager.unregisterService(l) } }
        listener = null
    }

    companion object {
        // DNS-SD service types are conventionally written with the trailing dot.
        const val SERVICE_TYPE = "_lanyard._tcp."
    }
}
