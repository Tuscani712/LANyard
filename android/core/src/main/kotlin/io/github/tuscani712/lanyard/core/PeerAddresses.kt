package io.github.tuscani712.lanyard.core

/** A discovered mDNS service's address, used to fill a paired peer's port. */
data class DiscoveredAddr(val shortId: String, val host: String, val port: Int)

/**
 * Reconciles paired peers with what discovery sees. When a desktop pairs *to*
 * the phone, the phone records it with no port (the pairing request carries
 * none). The address is filled from mDNS once the desktop is discovered, so the
 * phone can then reach it and not show it permanently offline.
 */
object PeerAddresses {
    /**
     * The peers whose stored address should be updated: those with no port
     * (`port <= 0`) whose short id matches a discovered service. Peers that
     * already have a port are left alone. Returns only the peers that change.
     */
    fun fillFromDiscovery(peers: List<PairedPeer>, discovered: List<DiscoveredAddr>): List<PairedPeer> {
        if (discovered.isEmpty()) return emptyList()
        val byShort = HashMap<String, DiscoveredAddr>()
        for (d in discovered) {
            if (d.shortId.isBlank() || d.port <= 0 || d.port > 65535) continue
            byShort[short(d.shortId)] = d
        }
        if (byShort.isEmpty()) return emptyList()
        val updates = ArrayList<PairedPeer>()
        for (p in peers) {
            if (p.port > 0) continue
            val d = byShort[short(p.fingerprint)] ?: continue
            if (p.host == d.host && p.port == d.port) continue
            updates.add(p.copy(host = d.host, port = d.port))
        }
        return updates
    }

    private fun short(id: String): String = SelfFilter.ownShortId(id).lowercase()
}
