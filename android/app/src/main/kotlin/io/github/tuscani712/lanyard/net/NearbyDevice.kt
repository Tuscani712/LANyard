package io.github.tuscani712.lanyard.net

/** A device seen over mDNS, decoded from the `_lanyard._tcp` TXT records. */
data class NearbyDevice(
    val shortId: String,
    val deviceLabel: String,
    val name: String,
    val host: String,
    val port: Int,
    val os: String,
)
