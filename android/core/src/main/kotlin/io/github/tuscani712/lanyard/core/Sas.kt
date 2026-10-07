package io.github.tuscani712.lanyard.core

import java.security.MessageDigest

/**
 * The 6-digit Short Authentication String both devices show so a person can
 * confirm the pairing. Mirrors `identity.SAS` on the desktop exactly:
 *
 *  1. order the two fingerprints lexicographically (and their nonces with them),
 *  2. SHA-256 over `fpA || 0 || fpB || 0 || nonceA || 0 || nonceB`,
 *  3. the first four bytes, big-endian, modulo 1e6, zero-padded to six digits.
 *
 * Both sides may pass their own fingerprint/nonce first; the result is the same.
 */
object Sas {
    fun code(fpA: String, fpB: String, nonceA: String, nonceB: String): String {
        var a = fpA
        var b = fpB
        var na = nonceA
        var nb = nonceB
        if (a > b) {
            a = fpB
            b = fpA
            na = nonceB
            nb = nonceA
        }
        val md = MessageDigest.getInstance("SHA-256")
        md.update(a.toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(b.toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(na.toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(nb.toByteArray(Charsets.UTF_8))
        val sum = md.digest()
        val n = ((sum[0].toLong() and 0xff) shl 24) or
            ((sum[1].toLong() and 0xff) shl 16) or
            ((sum[2].toLong() and 0xff) shl 8) or
            (sum[3].toLong() and 0xff)
        return "%06d".format(n % 1_000_000)
    }
}
