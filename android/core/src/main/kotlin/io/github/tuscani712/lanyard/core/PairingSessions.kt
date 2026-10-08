package io.github.tuscani712.lanyard.core

import java.security.SecureRandom

/** An HTTP-level failure a handler maps to a status code and a short message. */
class PeerHttpException(val code: Int, message: String) : RuntimeException(message)

/** What a status poll returns to the initiating device. */
data class SessionView(
    val id: String,
    val status: String,
    val mode: String,
    val nonce: String,
    val granted: Permissions,
    val sas: String,
    val error: String = "",
)

/** One incoming pairing request, as the phone's dialog shows it. */
data class IncomingRequest(
    val id: String,
    val peerFp: String,
    val peerName: String,
    val peerDevice: String,
    val mode: String,
    val requested: Permissions,
    val viaQr: Boolean,
    val sas: String,
    val createdAt: Long,
)

/**
 * The responder side of the pairing state machine, mirroring `internal/trust`:
 * a request is recorded as pending, the person accepts or declines, and only
 * when the initiator confirms is the pairing written to the [TrustStore]. A
 * cancelled or unconfirmed pairing therefore leaves nothing behind on either
 * side.
 *
 * All methods are synchronized; the server calls them from worker threads and
 * the UI from the main thread.
 */
class PairingSessions(
    private val selfFp: () -> String,
    private val trust: TrustStore,
    private val onChange: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    random: SecureRandom = SecureRandom(),
    // One line per pairing event for the diagnostics report:
    // `[pairing] peer=<short> <event> ...`.
    private val diag: (String) -> Unit = {},
    // Called with the peer's fingerprint once a pair-mode confirm has written
    // the trust entry. The app clears any pending-unpair record for that peer
    // here, so a revoke queued before this pairing can never undo it.
    private val onPaired: (String) -> Unit = {},
) {
    companion object {
        const val MODE_CONNECT = "connect"
        const val MODE_PAIR = "pair"

        const val STATUS_PENDING = "pending"
        const val STATUS_ACCEPTED = "accepted"
        const val STATUS_ACTIVE = "active"
        const val STATUS_REJECTED = "rejected"
        const val STATUS_CLOSED = "closed"
        const val STATUS_EXPIRED = "expired"

        const val PAIRING_TTL_MS = 2 * 60 * 1000L
        // Terminal sessions (rejected/closed/expired) are dropped this long after
        // their last update, so they cannot accumulate toward MAX_SESSIONS.
        const val TERMINAL_TTL_MS = 5 * 60 * 1000L
        // An active session nobody closes is closed after this long.
        const val SESSION_INACTIVITY_MS = 15 * 60 * 1000L
        const val MAX_SESSIONS = 64
        const val MAX_PENDING_PER_PEER = 1
        const val MAX_PENDING_TOTAL = 3
    }

    private class Session(
        val id: String,
        val mode: String,
        val peerFp: String,
        val peerName: String,
        val peerDevice: String,
        val peerHost: String,
        val selfNonce: String,
        val peerNonce: String,
        val requested: Permissions,
        val viaQr: Boolean,
        var status: String,
        var granted: Permissions,
        var error: String,
        val createdAt: Long,
        var updatedAt: Long,
    )

    private val random = random
    private val sessions = LinkedHashMap<String, Session>()

    /**
     * Records an incoming request. [consumeInvite] is called only after every
     * cap has passed, so a request rejected for a cap does not waste a valid QR
     * code. When it is present the request is a QR pairing: a false result is a
     * hard 403 and never falls back to the SAS path.
     */
    @Synchronized
    fun createIncoming(
        mode: String,
        peerFp: String,
        peerName: String,
        peerDevice: String,
        peerHost: String,
        peerNonce: String,
        requested: Permissions,
        consumeInvite: (() -> Boolean)? = null,
    ): SessionView {
        if (selfFp().isBlank()) throw PeerHttpException(503, "identity not ready")
        if (peerFp.isBlank()) throw PeerHttpException(400, "missing peer identity")
        if (mode != MODE_CONNECT && mode != MODE_PAIR) throw PeerHttpException(400, "mode must be connect or pair")
        if (peerNonce.length < 16 || peerNonce.length > 128) throw PeerHttpException(400, "nonce required")
        if (consumeInvite != null && mode != MODE_PAIR) throw PeerHttpException(400, "an invite requires mode pair")
        sweep()
        // Caps are checked before the one-time invite is spent.
        if (sessions.size >= MAX_SESSIONS) throw PeerHttpException(503, "too many sessions")
        // A fresh pair request replaces a leftover pending pairing from the same
        // device (for example a re-pair after a stale or abandoned handshake).
        // Refusing would trap the person: the old prompt may be long gone from
        // their screen while the session lingers. Connect requests keep the
        // flood cap.
        if (mode == MODE_PAIR) {
            val stale = sessions.values.filter {
                it.peerFp.equals(peerFp, ignoreCase = true) &&
                    it.status == STATUS_PENDING && it.mode == MODE_PAIR
            }
            for (s in stale) {
                sessions.remove(s.id)
                diag("[pairing] peer=${Display.shortFp(peerFp)} supersede id=${s.id} replaced by a newer pair request")
            }
        }
        val pendingFromPeer = sessions.values.count { it.peerFp == peerFp && it.status == STATUS_PENDING }
        if (pendingFromPeer >= MAX_PENDING_PER_PEER) {
            throw PeerHttpException(409, "a request from this device is already waiting")
        }
        val pendingTotal = sessions.values.count { it.status == STATUS_PENDING }
        if (pendingTotal >= MAX_PENDING_TOTAL) {
            throw PeerHttpException(429, "too many pending requests")
        }
        var viaQr = false
        if (consumeInvite != null) {
            if (!consumeInvite()) throw PeerHttpException(403, "invalid or used invite")
            viaQr = true
        }
        val now = clock()
        val sess = Session(
            id = "c_" + randomHex(6),
            mode = mode,
            peerFp = peerFp.lowercase(),
            peerName = Display.safeName(peerName),
            peerDevice = peerDevice,
            peerHost = peerHost,
            selfNonce = randomHex(16),
            peerNonce = peerNonce,
            requested = requested,
            viaQr = viaQr,
            status = STATUS_PENDING,
            granted = Permissions(),
            error = "",
            createdAt = now,
            updatedAt = now,
        )
        sessions[sess.id] = sess
        diag("[pairing] peer=${Display.shortFp(peerFp)} request id=${sess.id} mode=$mode ${if (viaQr) "qr" else "sas"} requested=browse:${requested.browse},push:${requested.push}")
        onChange()
        return view(sess)
    }

    /** The status poll, for the session's own peer only. */
    @Synchronized
    fun statusFor(id: String, callerFp: String): SessionView {
        val sess = requirePeer(id, callerFp)
        return view(sess)
    }

    /**
     * The phone's person accepts the prompt with the permissions they chose.
     * The grant may only narrow what the peer requested: each flag is
     * intersected with the request, and the push size limits are carried over
     * from the request only when push is actually granted.
     */
    @Synchronized
    fun accept(id: String, granted: Permissions): Boolean {
        val sess = sessions[id] ?: return false
        if (sess.status != STATUS_PENDING) return false
        val push = granted.push && sess.requested.push
        sess.granted = Permissions(
            browse = granted.browse && sess.requested.browse,
            push = push,
            pushMaxBytes = if (push) sess.requested.pushMaxBytes else 0,
            askOver = if (push) sess.requested.askOver else 0,
        )
        sess.status = STATUS_ACCEPTED
        sess.updatedAt = clock()
        diag("[pairing] peer=${Display.shortFp(sess.peerFp)} accepted id=$id granted=browse:${sess.granted.browse},push:${sess.granted.push}")
        onChange()
        return true
    }

    /** The phone's person declines the prompt. */
    @Synchronized
    fun decline(id: String): Boolean {
        val sess = sessions[id] ?: return false
        if (sess.status == STATUS_PENDING || sess.status == STATUS_ACCEPTED) {
            sess.status = STATUS_REJECTED
            sess.updatedAt = clock()
            diag("[pairing] peer=${Display.shortFp(sess.peerFp)} refused id=$id reason=declined")
            onChange()
        }
        return true
    }

    /**
     * The initiator confirms; only then is the pairing written to the trust
     * store, with the permissions this phone granted.
     */
    @Synchronized
    fun confirm(id: String, callerFp: String): SessionView {
        val sess = requirePeer(id, callerFp)
        // A replayed confirm is a no-op success: the session is already active,
        // the pairing was written once, so do not save or log a second time.
        if (sess.status == STATUS_ACTIVE) return view(sess)
        if (sess.status == STATUS_PENDING) throw PeerHttpException(409, "the request was not accepted")
        if (sess.status != STATUS_ACCEPTED) throw PeerHttpException(409, "session is ${sess.status}")
        sess.status = STATUS_ACTIVE
        sess.updatedAt = clock()
        if (sess.mode == MODE_PAIR) {
            trust.save(
                PairedPeer(
                    fingerprint = sess.peerFp,
                    name = sess.peerName.ifEmpty { Display.groupedHex(sess.peerFp) },
                    host = sess.peerHost,
                    port = 0,
                    browse = sess.granted.browse,
                    push = sess.granted.push,
                    pairedAt = clock(),
                    pushMaxBytes = sess.granted.pushMaxBytes,
                    askOver = sess.granted.askOver,
                ),
            )
            // A successful re-pair supersedes any unpair that was queued while
            // this peer was offline; forgetting it here prevents a later retry
            // from revoking the fresh pairing.
            onPaired(sess.peerFp)
        }
        diag("[pairing] peer=${Display.shortFp(sess.peerFp)} confirmed id=$id stored=${sess.mode == MODE_PAIR}")
        onChange()
        return view(sess)
    }

    /** Ends a session from either side. */
    @Synchronized
    fun close(id: String, callerFp: String): Boolean {
        val sess = requirePeer(id, callerFp)
        sess.status = STATUS_CLOSED
        sess.updatedAt = clock()
        diag("[pairing] peer=${Display.shortFp(sess.peerFp)} closed id=$id")
        onChange()
        return true
    }

    /** The prompts the UI should show, oldest first. */
    @Synchronized
    fun pending(): List<IncomingRequest> {
        sweep()
        return sessions.values
            .filter { it.status == STATUS_PENDING }
            .sortedBy { it.createdAt }
            .map {
                IncomingRequest(
                    id = it.id,
                    peerFp = it.peerFp,
                    peerName = it.peerName,
                    peerDevice = it.peerDevice,
                    mode = it.mode,
                    requested = it.requested,
                    viaQr = it.viaQr,
                    sas = sas(it),
                    createdAt = it.createdAt,
                )
            }
    }

    @Synchronized
    fun count(): Int = sessions.size

    @Synchronized
    fun clear() {
        sessions.clear()
        onChange()
    }

    private fun requirePeer(id: String, callerFp: String): Session {
        sweep()
        val sess = sessions[id] ?: throw PeerHttpException(404, "session not found")
        if (!sess.peerFp.equals(callerFp, ignoreCase = true)) {
            throw PeerHttpException(403, "not your session")
        }
        return sess
    }

    private fun view(sess: Session): SessionView = SessionView(
        id = sess.id,
        status = sess.status,
        mode = sess.mode,
        nonce = sess.selfNonce,
        granted = sess.granted,
        sas = sas(sess),
        error = sess.error,
    )

    /** The SAS is only meaningful once the peer's nonce is known. */
    private fun sas(sess: Session): String =
        if (sess.peerNonce.isEmpty()) "" else Sas.code(selfFp(), sess.peerFp, sess.selfNonce, sess.peerNonce)

    /**
     * Expires stale sessions and drops terminal ones, so they cannot accumulate
     * toward [MAX_SESSIONS]: pending and accepted-but-unconfirmed sessions expire
     * after [PAIRING_TTL_MS]; rejected/closed/expired sessions are removed
     * [TERMINAL_TTL_MS] after their last update; an untouched active session is
     * closed after [SESSION_INACTIVITY_MS].
     */
    private fun sweep() {
        val now = clock()
        var changed = false
        val remove = ArrayList<String>()
        for (s in sessions.values) {
            when (s.status) {
                STATUS_PENDING, STATUS_ACCEPTED ->
                    if (now - s.updatedAt > PAIRING_TTL_MS) {
                        s.status = STATUS_EXPIRED
                        s.updatedAt = now
                        diag("[pairing] peer=${Display.shortFp(s.peerFp)} expired id=${s.id}")
                        changed = true
                    }
                STATUS_ACTIVE ->
                    if (now - s.updatedAt > SESSION_INACTIVITY_MS) {
                        s.status = STATUS_CLOSED
                        s.updatedAt = now
                        diag("[pairing] peer=${Display.shortFp(s.peerFp)} closed id=${s.id} reason=inactive")
                        changed = true
                    }
                STATUS_REJECTED, STATUS_CLOSED, STATUS_EXPIRED ->
                    if (now - s.updatedAt > TERMINAL_TTL_MS) remove.add(s.id)
            }
        }
        if (remove.isNotEmpty()) {
            for (id in remove) sessions.remove(id)
            changed = true
        }
        if (changed) onChange()
    }

    private fun randomHex(bytes: Int): String {
        val buf = ByteArray(bytes)
        random.nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }
}
