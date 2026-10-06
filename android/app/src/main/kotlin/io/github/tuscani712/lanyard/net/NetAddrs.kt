package io.github.tuscani712.lanyard.net

import java.net.Inet4Address
import java.net.NetworkInterface

/** Local site-local IPv4 addresses, for pairing links and the QR code. */
object NetAddrs {
    fun localIPv4(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())
}
