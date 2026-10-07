package io.github.tuscani712.lanyard.core

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * One-time QR pairing invites minted by this device, mirroring the desktop's
 * `trust.Store` invite handling:
 *
 *  - 128 bits of randomness, hex-encoded;
 *  - valid for [ttlMillis] (2 minutes by default);
 *  - consumed on the first use, valid or not, so a reused or expired invite can
 *    never be retried or downgraded to the SAS path;
 *  - compared in constant time.
 *
 * [valid] is the non-consuming lookup used by the QR screen so it can keep
 * showing the same code until it expires or is used.
 */
class PairInvites(
    private val ttlMillis: Long = 2 * 60 * 1000,
    private val clock: () -> Long = System::currentTimeMillis,
    random: SecureRandom = SecureRandom(),
) {
    data class Invite(val token: String, val expiresAt: Long)

    private val invites = HashMap<String, Long>()
    private val random = random

    @Synchronized
    fun mint(): Invite {
        sweep()
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        val expiresAt = clock() + ttlMillis
        invites[token] = expiresAt
        return Invite(token, expiresAt)
    }

    /** The expiry of a still-valid invite, or null if unknown, used or expired. */
    @Synchronized
    fun valid(token: String): Long? {
        if (token.isEmpty()) return null
        sweep()
        val exp = invites[token] ?: return null
        if (clock() < exp) return exp
        invites.remove(token)
        return null
    }

    /** Consumes [token] on the first attempt; true only if it was valid. */
    @Synchronized
    fun consume(token: String): Boolean {
        if (token.isEmpty()) return false
        sweep()
        var found: String? = null
        var exp = 0L
        val want = token.toByteArray(Charsets.UTF_8)
        for ((t, e) in invites) {
            if (MessageDigest.isEqual(t.toByteArray(Charsets.UTF_8), want)) {
                found = t
                exp = e
            }
        }
        if (found == null) return false
        invites.remove(found)
        return clock() < exp
    }

    private fun sweep() {
        val now = clock()
        invites.entries.removeAll { now >= it.value }
    }
}
