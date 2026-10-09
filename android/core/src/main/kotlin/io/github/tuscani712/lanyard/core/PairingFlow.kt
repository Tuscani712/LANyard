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
 * What the person is asked to confirm during a nearby (no-QR) pairing: the
 * short-authentication string both devices show, the peer's advertised name,
 * and the certificate fingerprint pinned from the probe. The fingerprint shown
 * is the one the TLS handshake actually presented — never the mDNS short id and
 * never the value from a hello body.
 */
data class Prompt(
    val sas: String,
    val peerName: String,
    val fingerprint: String,
)

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
     * [PairLink.MAX_ADDRS] addresses; probing each one with its own
     * connect+read timeout could stall pairing for minutes. With this budget
     * the phase always ends promptly.
     */
    const val PROBE_DEADLINE_MS = 12_000L

    /**
     * The default bound for a nearby (SAS) pairing: the responder's prompt lives
     * for [PairingSessions.PAIRING_TTL_MS] (2 minutes), so polling past that
     * could only ever see it expire.
     */
    const val NEARBY_TIMEOUT_MS = 2 * 60 * 1000L

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
        // One line per pairing event for the diagnostics report:
        // `[pairing] peer=<short> <event> ...`.
        diag: (String) -> Unit = {},
    ): PairResult {
        val payload = try {
            PairLink.parse(link)
        } catch (_: PairLink.ParseException) {
            return PairResult.InvalidLink
        }
        val short = Display.shortFp(payload.fingerprint)
        diag("[pairing] peer=$short link parsed addrs=${payload.addrs.size}")

        val outcome = probeAddresses(
            addrs = payload.addrs,
            expected = payload.fingerprint,
            identity = identity,
            localAddresses = localAddresses(),
            deadlineMs = probeDeadlineMs,
            perAttemptMs = probeAttemptTimeoutMs,
            clock = clock,
            short = short,
            diag = diag,
        )
        val match = outcome.match
            ?: run {
                diag("[pairing] peer=$short probe failed ${if (outcome.mismatch) "identity mismatch" else "unreachable"}")
                return if (outcome.mismatch) PairResult.FingerprintMismatch else PairResult.Unreachable
            }

        val client = PeerClient(match.host, match.port, identity, payload.fingerprint)
        diag("[pairing] peer=$short session request to ${match.host}:${match.port}")

        val session = try {
            client.startSession(
                mode = "pair",
                name = selfName,
                deviceId = identity.deviceId,
                nonce = randomNonce(),
                requested = requested.withModes(outcome.triState),
                invite = payload.nonce,
                tristate = outcome.triState,
            )
        } catch (_: PeerStatusException) {
            diag("[pairing] peer=$short session refused")
            return PairResult.Refused
        } catch (_: Exception) {
            diag("[pairing] peer=$short session unreachable")
            return PairResult.Unreachable
        }

        val sessionId = session.str("session_id")
        if (sessionId.isEmpty()) return PairResult.Refused

        return runSession(
            client = client,
            sessionId = sessionId,
            timeoutMs = timeoutMs,
            short = short,
            diag = diag,
            // A QR pairing needs no SAS compare: scanning the code already
            // proved the trust, so the person's confirmation is implied.
            approved = { true },
            persist = { status -> finish(client, sessionId, status, match, payload, selfName, store, diag) },
        )
    }

    /**
     * Pairs with a nearby device without a QR code. It probes [host]:[port],
     * pins the full certificate fingerprint actually presented by the TLS
     * handshake (never the mDNS short id and never the value a hello body
     * claims), asks the responder to start a session, and shows the person on
     * both devices the same [Prompt.sas]. Only when [awaitCodesMatch] returns
     * true is the session confirmed and the peer written to [store]; nothing is
     * persisted before that, and a declined prompt closes the session and leaves
     * both stores untouched.
     *
     * Blocking (the prompt wait included); call it off the main thread.
     */
    fun pairNearby(
        host: String,
        port: Int,
        identity: Identity,
        selfName: String,
        store: TrustStore,
        awaitCodesMatch: (Prompt) -> Boolean,
        timeoutMs: Long = NEARBY_TIMEOUT_MS,
        probeTimeoutMs: Int = PROBE_ATTEMPT_TIMEOUT_MS,
        diag: (String) -> Unit = {},
    ): PairResult {
        val hello: PeerHello
        val probe = ProbeClient(host, port, identity, probeTimeoutMs, probeTimeoutMs)
        try {
            hello = probe.hello()
        } catch (e: Exception) {
            diag("[pairing] addr=${host}:${port} nearby probe failed error=${e.javaClass.simpleName}")
            return PairResult.Unreachable
        }
        // The fingerprint to pin is whatever certificate answered the probe. A
        // blank one means no handshake happened, so there is nothing to trust.
        val observedFp = probe.observedFingerprint()
        if (observedFp.isBlank()) {
            diag("[pairing] addr=${host}:${port} nearby probe returned no fingerprint")
            return PairResult.Unreachable
        }
        val short = Display.shortFp(observedFp)
        // A nearby list can still surface this phone (mDNS race); pairing with
        // ourselves can only ever fail, so refuse before dialing.
        if (SelfFilter.isSelf(SelfFilter.ownShortId(observedFp), SelfFilter.ownShortId(identity.deviceId))) {
            diag("[pairing] peer=$short nearby probe rejected as self")
            return PairResult.Refused
        }
        diag("[pairing] peer=$short nearby probe ok addr=${host}:${port}")

        val client = PeerClient(host, port, identity, observedFp)
        val selfNonce = randomNonce()
        val session = try {
            client.startSession(
                mode = "pair",
                name = selfName,
                deviceId = identity.deviceId,
                nonce = selfNonce,
                requested = Permissions(browse = true, push = true).withModes(hello.supportsTriState),
                // No invite: this is the SAS path, confirmed by the person.
                invite = "",
                tristate = hello.supportsTriState,
            )
        } catch (_: PeerStatusException) {
            diag("[pairing] peer=$short nearby session refused")
            return PairResult.Refused
        } catch (_: Exception) {
            diag("[pairing] peer=$short nearby session unreachable")
            return PairResult.Unreachable
        }
        val sessionId = session.str("session_id")
        if (sessionId.isEmpty()) return PairResult.Refused
        // The start response carries the responder's nonce; both phones derive
        // the same SAS from the two fingerprints and the two nonces.
        val desktopNonce = session.str("nonce")
        val sas = Sas.code(identity.deviceId, observedFp, selfNonce, desktopNonce)
        diag("[pairing] peer=$short nearby session id=$sessionId")

        val peerName = hello.name
        return runSession(
            client = client,
            sessionId = sessionId,
            timeoutMs = timeoutMs,
            short = short,
            diag = diag,
            approved = { awaitCodesMatch(Prompt(sas, peerName, observedFp)) },
            persist = { status ->
                confirmAndStore(client, sessionId, status, host, port, observedFp, peerName, store, short, diag)
            },
        )
    }

    /**
     * The shared poll→confirm state machine used by both pairing paths: it waits
     * for the responder to accept the request, asks [approved] for the person's
     * confirmation (always true on the QR path, the SAS compare on the nearby
     * path), then [persist]s. The session is closed on every non-success path so
     * it never lingers and blocks a retry, and nothing is written before
     * [approved] says yes.
     */
    private fun runSession(
        client: PeerClient,
        sessionId: String,
        timeoutMs: Long,
        short: String,
        diag: (String) -> Unit,
        approved: () -> Boolean,
        persist: (JsonObject) -> PairResult,
    ): PairResult {
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
                "accepted" -> {
                    diag("[pairing] peer=$short accepted id=$sessionId")
                    if (!approved()) {
                        diag("[pairing] peer=$short declined by person id=$sessionId")
                        return abort(PairResult.Refused)
                    }
                    return persist(status)
                }
                "rejected", "closed" -> {
                    diag("[pairing] peer=$short refused id=$sessionId status=${status.str("status")}")
                    return PairResult.Refused
                }
                "expired" -> {
                    diag("[pairing] peer=$short expired id=$sessionId")
                    return PairResult.Expired
                }
            }
            Thread.sleep(200)
        }
        diag("[pairing] peer=$short expired id=$sessionId reason=timeout")
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
        diag: (String) -> Unit,
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
        val name = try {
            client.hello().name.ifEmpty { payload.name.ifEmpty { selfName } }
        } catch (_: Exception) {
            payload.name
        }
        val allow = grantedFrom(status)
        val peer = PairedPeer(
            fingerprint = payload.fingerprint.lowercase(),
            name = name.ifEmpty { payload.name },
            host = match.host,
            port = match.port,
            // Our own grant to the peer was never chosen here (the responder
            // only granted us), so it starts at the new Ask default.
            browse = Permission.ASK,
            push = Permission.ASK,
            text = Permission.ASK,
            pairedAt = System.currentTimeMillis(),
            allowBrowse = allow.browse,
            allowPush = allow.push,
            allowText = allow.text,
        )
        store.save(peer)
        diag("[pairing] peer=${Display.shortFp(peer.fingerprint)} confirmed id=$sessionId stored=true")
        return PairResult.Paired(peer)
    }

    /**
     * Confirms an already-accepted nearby session and stores the peer under the
     * fingerprint the probe pinned. Only called after the person confirmed the
     * SAS, so a failure here leaves no trust entry behind.
     */
    private fun confirmAndStore(
        client: PeerClient,
        sessionId: String,
        status: JsonObject,
        host: String,
        port: Int,
        fingerprint: String,
        peerName: String,
        store: TrustStore,
        short: String,
        diag: (String) -> Unit,
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
        val allow = grantedFrom(status)
        val peer = PairedPeer(
            // The full certificate fingerprint from the probe, normalized — the
            // only fingerprint we ever trust for a device found over mDNS.
            fingerprint = fingerprint.lowercase(),
            name = peerName,
            host = host,
            port = port,
            browse = Permission.ASK,
            push = Permission.ASK,
            text = Permission.ASK,
            pairedAt = System.currentTimeMillis(),
            allowBrowse = allow.browse,
            allowPush = allow.push,
            allowText = allow.text,
        )
        store.save(peer)
        diag("[pairing] peer=$short confirmed id=$sessionId stored=true")
        return PairResult.Paired(peer)
    }

    /** What the responder allows this device to do, from an accepted status poll. */
    private data class RemoteGrant(val browse: Boolean, val push: Boolean, val text: Boolean)

    /**
     * The responder's grant to us from an accepted status poll. A responder that
     * negotiated tri-state sends `*_mode`; anything it allows (Allow or Ask) is
     * an allowance from our side. A peer that did not sends only booleans, where
     * `true` is an allowance and `false` is a denial.
     */
    private fun grantedFrom(status: JsonObject): RemoteGrant {
        val granted = status.get("granted")?.takeIf { !it.isJsonNull }?.asJsonObject
        fun mode(key: String): Permission? =
            Permission.fromString(granted?.get(key)?.takeIf { !it.isJsonNull }?.asString)
        fun bool(key: String, default: Boolean): Boolean =
            granted?.get(key)?.takeIf { !it.isJsonNull }?.asBoolean ?: default
        fun allowed(key: String, boolKey: String, default: Boolean): Boolean {
            val m = mode(key)
            return if (m != null) m.permits else bool(boolKey, default)
        }
        return RemoteGrant(
            browse = allowed("browse_mode", "browse", true),
            push = allowed("push_mode", "push", false),
            // Text had no wire field before tri-state permissions; fall back to the push allowance.
            text = allowed("text_mode", "text", bool("push", false)),
        )
    }

    private data class HostPort(val host: String, val port: Int)

    private data class ProbeOutcome(val match: HostPort?, val mismatch: Boolean, val triState: Boolean = false)

    /**
     * Adds the tri-state mode fields to [this] request when [tristate]. The
     * booleans stay present as the fallback; only a peer that advertised the
     * capability gets the extra keys.
     */
    private fun Permissions.withModes(tristate: Boolean): Permissions =
        if (!tristate) this else copy(
            browseMode = Permission.ALLOW,
            pushMode = Permission.ALLOW,
            textMode = Permission.ALLOW,
        )

    private fun probeAddresses(
        addrs: List<String>,
        expected: String,
        identity: Identity,
        localAddresses: List<String>,
        deadlineMs: Long,
        perAttemptMs: Int,
        clock: () -> Long,
        short: String,
        diag: (String) -> Unit,
    ): ProbeOutcome {
        var mismatch = false
        val startedAt = clock()
        for (addr in orderForProbe(addrs, localAddresses)) {
            val hp = splitAddr(addr) ?: continue
            if (!isDialable(hp.host)) continue
            val remaining = deadlineMs - (clock() - startedAt)
            if (remaining <= 0 || remaining < perAttemptMs) break
            val attemptStart = clock()
            try {
                val probe = ProbeClient(hp.host, hp.port, identity, perAttemptMs, perAttemptMs)
                val hello = probe.hello()
                val elapsed = clock() - attemptStart
                if (probe.observedFingerprint().equals(expected, ignoreCase = true)) {
                    diag("[pairing] peer=$short probe ok addr=${hp.host}:${hp.port} elapsed=${elapsed}ms")
                    return ProbeOutcome(hp, mismatch, triState = hello.supportsTriState)
                }
                diag("[pairing] peer=$short probe mismatch addr=${hp.host}:${hp.port} elapsed=${elapsed}ms")
                mismatch = true
            } catch (e: Exception) {
                diag("[pairing] peer=$short probe failed addr=${hp.host}:${hp.port} elapsed=${clock() - attemptStart}ms error=${e.javaClass.simpleName}")
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
