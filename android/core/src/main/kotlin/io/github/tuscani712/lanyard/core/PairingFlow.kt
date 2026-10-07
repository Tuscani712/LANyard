package io.github.tuscani712.lanyard.core

import com.google.gson.JsonObject
import java.security.SecureRandom

/** The outcome of a pairing attempt, in plain terms the UI can show. */
sealed class PairResult {
    /** The peer accepted; the record was saved to the [TrustStore]. */
    data class Paired(val peer: PairedPeer) : PairResult()

    /** The peer (or its server) rejected the request. */
    object Refused : PairResult()

    /** The pairing window closed before the peer accepted. */
    object Expired : PairResult()

    /** No address in the link answered. */
    object Unreachable : PairResult()

    /** An address answered, but not with the certificate the link promised. */
    object FingerprintMismatch : PairResult()

    /** The pasted text was not a usable pairing link. */
    object InvalidLink : PairResult()
}

/**
 * Runs the client half of pairing: parse a [PairLink], find the peer among its
 * addresses, verify the certificate it presents really is the one the link
 * promised, then exchange the one-time invite for a trust entry.
 *
 * Blocking (like [PeerClient]); call it off the main thread. The fingerprint
 * check is fail-closed: a mismatch never proceeds to the session request.
 */
object PairingFlow {
    /**
     * The whole probe phase shares one deadline. A phone can advertise up to
     * [PairLink.MAX_ADDRS] addresses and each dead one used to burn its own
     * connect+read timeout, so an unreachable peer could stall pairing for
     * minutes. With this budget the phase always ends promptly.
     */
    const val PROBE_DEADLINE_MS = 12_000L

    /** Connect/read timeout for a single probe attempt (see [ProbeClient]). */
    private const val PROBE_ATTEMPT_TIMEOUT_MS = ProbeClient.DEFAULT_TIMEOUT_MS

    fun pair(
        link: String,
        identity: Identity,
        selfName: String,
        store: TrustStore,
        requested: Permissions = Permissions(browse = true, push = true),
        timeoutMs: Long = 30_000,
        probeDeadlineMs: Long = PROBE_DEADLINE_MS,
        probeAttemptTimeoutMs: Int = PROBE_ATTEMPT_TIMEOUT_MS,
        localAddresses: () -> List<String> = { defaultLocalAddresses() },
        clock: () -> Long = System::currentTimeMillis,
    ): PairResult {
        val payload = try {
            PairLink.parse(link)
        } catch (_: PairLink.ParseException) {
            return PairResult.InvalidLink
        }

        val outcome = probeAddresses(
            addrs = payload.addrs,
            expected = payload.fingerprint,
            identity = identity,
            localAddresses = localAddresses(),
            deadlineMs = probeDeadlineMs,
            perAttemptMs = probeAttemptTimeoutMs,
            clock = clock,
        )
        val match = outcome.match
            ?: return if (outcome.mismatch) PairResult.FingerprintMismatch else PairResult.Unreachable

        val client = PeerClient(match.host, match.port, identity, payload.fingerprint)

        val session = try {
            client.startSession(
                mode = "pair",
                name = selfName,
                deviceId = identity.deviceId,
                nonce = randomNonce(),
                requested = requested,
                invite = payload.nonce,
            )
        } catch (_: PeerStatusException) {
            return PairResult.Refused
        } catch (_: Exception) {
            return PairResult.Unreachable
        }

        val sessionId = session.str("session_id")
        if (sessionId.isEmpty()) return PairResult.Refused

        // A session we start but never finish must be ended here. Otherwise the
        // responder keeps a pending prompt for its 2-minute TTL, and
        // MAX_PENDING_PER_PEER (1) rejects the very next request from this same
        // phone with 409 "a request from this device is already waiting" — so a
        // second attempt could not pair. Closing is best effort: the peer may
        // already be gone.
        fun abort(result: PairResult): PairResult {
            runCatching { client.closeSession(sessionId) }
            return result
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val status = try {
                client.sessionStatus(sessionId)
            } catch (_: PeerStatusException) {
                return abort(PairResult.Refused)
            } catch (_: Exception) {
                return abort(PairResult.Unreachable)
            }
            when (status.str("status")) {
                "accepted" -> return finish(client, sessionId, status, match, payload, selfName, store)
                "rejected", "closed" -> return PairResult.Refused
                "expired" -> return PairResult.Expired
            }
            Thread.sleep(200)
        }
        return abort(PairResult.Expired)
    }

    private fun finish(
        client: PeerClient,
        sessionId: String,
        status: JsonObject,
        match: HostPort,
        payload: PairLink.Payload,
        selfName: String,
        store: TrustStore,
    ): PairResult {
        try {
            client.confirmSession(sessionId)
        } catch (_: PeerStatusException) {
            runCatching { client.closeSession(sessionId) }
            return PairResult.Refused
        } catch (_: Exception) {
            runCatching { client.closeSession(sessionId) }
            return PairResult.Unreachable
        }
        val granted = status.get("granted")?.takeIf { !it.isJsonNull }?.asJsonObject
        val name = try {
            client.hello().name.ifEmpty { payload.name.ifEmpty { selfName } }
        } catch (_: Exception) {
            payload.name
        }
        val peer = PairedPeer(
            fingerprint = payload.fingerprint.lowercase(),
            name = name.ifEmpty { payload.name },
            host = match.host,
            port = match.port,
            browse = granted?.get("browse")?.takeIf { !it.isJsonNull }?.asBoolean ?: true,
            push = granted?.get("push")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
            pairedAt = System.currentTimeMillis(),
        )
        store.save(peer)
        return PairResult.Paired(peer)
    }

    private data class HostPort(val host: String, val port: Int)

    private data class ProbeOutcome(val match: HostPort?, val mismatch: Boolean)

    private fun probeAddresses(
        addrs: List<String>,
        expected: String,
        identity: Identity,
        localAddresses: List<String>,
        deadlineMs: Long,
        perAttemptMs: Int,
        clock: () -> Long,
    ): ProbeOutcome {
        var mismatch = false
        val startedAt = clock()
        for (addr in orderForProbe(addrs, localAddresses)) {
            val hp = splitAddr(addr) ?: continue
            if (!isDialable(hp.host)) continue
            val remaining = deadlineMs - (clock() - startedAt)
            if (remaining <= 0 || remaining < perAttemptMs) break
            try {
                val probe = ProbeClient(hp.host, hp.port, identity, perAttemptMs, perAttemptMs)
                probe.hello()
                if (probe.observedFingerprint().equals(expected, ignoreCase = true)) {
                    return ProbeOutcome(hp, mismatch)
                }
                mismatch = true
            } catch (_: Exception) {
                // try the next address
            }
        }
        return ProbeOutcome(null, mismatch)
    }

    /**
     * Reorders link addresses so the ones on the same /24 as one of our own
     * interface addresses are tried first. The sort is stable, so the relative
     * order within each group is exactly what the link carried: deterministic.
     */
    internal fun orderForProbe(addrs: List<String>, localAddresses: List<String>): List<String> =
        addrs.sortedBy { if (onSameSubnet(it, localAddresses)) 0 else 1 }

    /**
     * A link address that is unusable from this phone is dropped rather than
     * probed: a link-local `169.254.x.x` (an unconfigured/adhoc address on the
     * *other* phone) and any IPv6 literal (the other phone's link-local IPv6 is
     * not dialable from here). Site-local IPv4 is kept.
     */
    internal fun isDialable(host: String): Boolean {
        if (host.contains(':')) return false // IPv6 literal from a link
        if (host.startsWith("169.254.")) return false // IPv4 link-local
        return true
    }

    private fun onSameSubnet(addr: String, localAddresses: List<String>): Boolean {
        val host = splitAddr(addr)?.host ?: return false
        val a = host.split('.')
        if (a.size != 4 || a.any { it.toIntOrNull() == null }) return false
        return localAddresses.any { loc ->
            val l = loc.split('.')
            l.size == 4 && l.all { it.toIntOrNull() != null } &&
                l[0] == a[0] && l[1] == a[1] && l[2] == a[2]
        }
    }

    /** This device's non-loopback, non-link-local addresses, for /24 ordering. */
    private fun defaultLocalAddresses(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    private fun splitAddr(addr: String): HostPort? {
        val host: String
        val portText: String
        if (addr.startsWith("[")) {
            val end = addr.indexOf(']')
            if (end < 0 || addr.length <= end + 1 || addr[end + 1] != ':') return null
            host = addr.substring(1, end)
            portText = addr.substring(end + 2)
        } else {
            val c = addr.lastIndexOf(':')
            if (c <= 0) return null
            host = addr.substring(0, c)
            portText = addr.substring(c + 1)
        }
        if (host.isEmpty()) return null
        val port = portText.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return HostPort(host, port)
    }

    private fun randomNonce(): String {
        val buf = ByteArray(16)
        SecureRandom().nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString ?: ""
}
