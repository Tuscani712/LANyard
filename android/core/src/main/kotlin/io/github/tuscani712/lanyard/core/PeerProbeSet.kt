package io.github.tuscani712.lanyard.core

/**
 * Schedules reachability probes for the set of known paired peers.
 *
 * A peer is probed **immediately** when it first becomes known or its host/port
 * changes (the result is fed to [onResult]), and a periodic [tick] re-probes the
 * set so a peer that comes back — or goes away — is noticed without a screen
 * visit. The probe function is injected, so the scheduling policy is
 * unit-testable without a live peer. This is what lets a desktop that paired
 * *to* the phone be probed the moment its trust entry appears, rather than
 * waiting for mDNS, a restart or a phone-initiated refresh.
 *
 * Keyed by fingerprint (case-insensitive). Synchronized because the peer server
 * can [sync] a freshly paired peer while the screen's timer [tick]s.
 */
class PeerProbeSet(
    private val probe: (PairedPeer) -> Boolean,
    private val onResult: (PairedPeer, Boolean) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    companion object {
        /** How often [tick] re-probes a peer whose result may have gone stale. */
        const val DEFAULT_INTERVAL_MS = 15_000L
    }

    private val known = LinkedHashMap<String, PairedPeer>()
    private val probedAt = HashMap<String, Long>()

    /**
     * Registers every peer in [peers], probing any that are new or re-addressed
     * at once, and forgetting any that are no longer present.
     */
    @Synchronized
    fun sync(peers: List<PairedPeer>) {
        val wanted = LinkedHashMap<String, PairedPeer>()
        for (peer in peers) {
            val key = key(peer.fingerprint)
            if (key.isNotBlank()) wanted[key] = peer
        }
        known.keys.retainAll(wanted.keys)
        probedAt.keys.retainAll(wanted.keys)
        for (peer in wanted.values) add(peer)
    }

    /**
     * Adds [peer]. A peer that is new, or whose host/port changed, is probed now
     * and its result fed to [onResult]; an unchanged peer is left for [tick].
     */
    @Synchronized
    fun add(peer: PairedPeer) {
        val key = key(peer.fingerprint)
        if (key.isBlank()) return
        val previous = known[key]
        if (previous != null && previous.host == peer.host && previous.port == peer.port) return
        val normalized = peer.copy(fingerprint = key)
        known[key] = normalized
        report(key, normalized, clock())
    }

    /** Forgets a peer, so [tick] stops probing it. */
    @Synchronized
    fun remove(fingerprint: String) {
        val key = key(fingerprint)
        known.remove(key)
        probedAt.remove(key)
    }

    /**
     * Re-probes every known peer whose last probe is at least [intervalMs] old.
     * [now] is injectable so the interval policy is testable without real time.
     */
    @Synchronized
    fun tick(now: Long = clock()) {
        for ((key, peer) in known) {
            val last = probedAt[key]
            if (last != null && now - last < intervalMs) continue
            report(key, peer, now)
        }
    }

    /** How many peers are currently known (for diagnostics and tests). */
    @Synchronized
    fun size(): Int = known.size

    private fun report(key: String, peer: PairedPeer, now: Long) {
        probedAt[key] = now
        onResult(peer, probe(peer))
    }

    private fun key(fingerprint: String): String = fingerprint.trim().lowercase()
}

/**
 * Keeps a [PeerProbeSet] in step with the current trust list. [sync] re-reads
 * the list and immediately probes any peer that is new or re-addressed; [tick]
 * does the same and then re-probes the peers whose interval elapsed. The list
 * source is injected, so a peer that appears after the monitor is started is
 * picked up without any external refresh call.
 */
class PeerProbeMonitor(
    private val peers: () -> List<PairedPeer>,
    private val probe: (PairedPeer) -> Boolean,
    private val onResult: (PairedPeer, Boolean) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
    intervalMs: Long = PeerProbeSet.DEFAULT_INTERVAL_MS,
) {
    private val set = PeerProbeSet(probe, onResult, clock, intervalMs)

    /** Re-reads the trust list, probing new/changed peers now. Returns what it saw. */
    @Synchronized
    fun sync(): List<PairedPeer> {
        val current = peers()
        set.sync(current)
        return current
    }

    /** The periodic nudge: sync first, then re-probe peers whose interval elapsed. */
    @Synchronized
    fun tick(now: Long = clock()) {
        set.sync(peers())
        set.tick(now)
    }

    /** Forgets a peer so a later [tick] does not probe it. */
    fun remove(fingerprint: String) = set.remove(fingerprint)

    /** How many peers are currently known (for diagnostics and tests). */
    fun size(): Int = set.size()
}
