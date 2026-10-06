package io.github.tuscani712.lanyard.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.tuscani712.lanyard.core.MeteredNetwork

/**
 * The real [MeteredNetwork]: metered when the active network is flagged metered
 * (mobile, or a metered Wi-Fi hotspot) or is cellular.
 */
class AndroidMeteredNetwork(private val context: Context) : MeteredNetwork {
    override fun isMetered(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        if (manager.isActiveNetworkMetered) return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }
}
