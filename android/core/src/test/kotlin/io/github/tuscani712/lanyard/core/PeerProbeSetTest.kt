package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The probe-scheduling policy for paired peers: a peer is probed the moment it
 * becomes known (or its address changes) without waiting for a refresh, and the
 * periodic [PeerProbeSet.tick] re-probes the set no more often than the interval.
 * Both the probe and the clock are injected, so the policy is tested without a
 * live peer or real time.
 */
class PeerProbeSetTest {

    private fun peer(fp: String, host: String = "10.0.0.5", port: Int = 47800) = PairedPeer(
        fingerprint = fp,
        name = "Desk",
        host = host,
        port = port,
        browse = true,
        push = true,
        pairedAt = 1L,
    )

    private val fp = "0123abcd" + "ff".repeat(28)

    @Test
    fun peerAddedAfterStartIsProbedImmediatelyWithoutARefresh() {
        var now = 1_000L
        val probed = ArrayList<String>()
        val set = PeerProbeSet(probe = { probed.add(it.fingerprint); true }, clock = { now })

        // Nothing is known yet; no probe has happened and no tick was called.
        assertTrue(probed.isEmpty(), "an unknown peer is not probed")

        // The peer appears (a desktop just paired *to* us): it is probed at once,
        // without any refresh/tick driving the schedule.
        set.add(peer(fp))

        assertEquals(listOf(fp), probed, "a newly known peer is probed immediately")

        // A tick inside the interval does not probe it a second time.
        now += 1
        set.tick(now)
        assertEquals(1, probed.size, "the immediate probe also arms the interval")
    }

    @Test
    fun addressChangeReprobesImmediately() {
        var now = 1_000L
        val probed = ArrayList<String>()
        val set = PeerProbeSet(probe = { probed.add("${it.host}:${it.port}"); true }, clock = { now })

        set.add(peer(fp, host = "10.0.0.5", port = 47800))
        assertEquals(listOf("10.0.0.5:47800"), probed)

        // Same address: not re-probed.
        set.add(peer(fp, host = "10.0.0.5", port = 47800))
        assertEquals(1, probed.size, "an unchanged peer is not re-probed by add")

        // A new port (or host) re-probes at once.
        now += 1
        set.add(peer(fp, host = "10.0.0.5", port = 47801))
        assertEquals(listOf("10.0.0.5:47800", "10.0.0.5:47801"), probed)
    }

    @Test
    fun unchangedPeerIsNotReprobedOnTickBeforeTheInterval() {
        var now = 1_000L
        var probes = 0
        val set = PeerProbeSet(probe = { probes++; true }, clock = { now })

        set.add(peer(fp))
        assertEquals(1, probes)

        now += PeerProbeSet.DEFAULT_INTERVAL_MS - 1
        set.tick(now)
        assertEquals(1, probes, "a tick inside the interval is skipped")

        now += 1
        set.tick(now)
        assertEquals(2, probes, "the next interval re-probes")
    }

    @Test
    fun onResultReceivesOnlineAndOffline() {
        var online = true
        val results = ArrayList<Pair<String, Boolean>>()
        val set = PeerProbeSet(
            probe = { online },
            onResult = { p, ok -> results.add(p.fingerprint to ok) },
        )

        set.add(peer(fp))
        assertEquals(listOf(fp to true), results, "an online probe is reported")

        online = false
        set.add(peer(fp, host = "10.0.0.9"))
        assertEquals(listOf(fp to true, fp to false), results, "an offline probe is reported")
    }

    @Test
    fun monitorPicksUpAPeerThatAppearsAfterStart() {
        var now = 1_000L
        val source = ArrayList<PairedPeer>()
        val probed = ArrayList<String>()
        val monitor = PeerProbeMonitor(
            peers = { source.toList() },
            probe = { probed.add(it.fingerprint); true },
            clock = { now },
        )

        // The monitor is "started": the trust list is still empty.
        monitor.tick(now)
        assertTrue(probed.isEmpty(), "no peers means no probes")

        // A pairing lands in the trust list after start; the periodic tick reads
        // it and probes the new peer immediately — no external refresh call.
        source.add(peer(fp))
        monitor.tick(now)
        assertEquals(listOf(fp), probed, "a peer appearing after start is probed by the periodic tick")
    }
}
