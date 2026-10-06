package io.github.tuscani712.lanyard.core

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The pairing link carried by a QR code: `lanyard://pair?fp=&name=&addr=&n=`.
 * Mirrors `internal/pairlink/pairlink.go`: length and address caps, only the
 * known parameters, hex checks on the fingerprint and nonce, and IP-literal
 * host:port addresses. `build` leaves ':' and ',' unescaped in `addr` (both are
 * legal in a query value and keep the code short); `parse` accepts both that
 * form and a percent-encoded one.
 */
object PairLink {
    const val SCHEME = "lanyard"
    const val HOST = "pair"
    const val MAX_LEN = 2048
    const val MAX_ADDRS = 16

    private const val MAX_ADDR_LEN = 255
    private const val MAX_NAME_LEN = 200
    private const val MIN_NONCE_LEN = 32 // 128 bits, hex-encoded
    private const val MAX_NONCE_LEN = 128
    private val KEYS = setOf("fp", "name", "addr", "n")

    class ParseException(message: String) : IllegalArgumentException(message)

    data class Payload(
        val fingerprint: String,
        val name: String,
        val addrs: List<String>,
        val nonce: String,
    )

    fun build(p: Payload): String = buildString {
        append("$SCHEME://$HOST?fp=").append(p.fingerprint)
        if (p.name.isNotEmpty()) {
            append("&name=").append(URLEncoder.encode(p.name, Charsets.UTF_8))
        }
        append("&addr=").append(p.addrs.joinToString(","))
        append("&n=").append(p.nonce)
    }

    fun parse(raw: String): Payload {
        if (raw.isEmpty() || raw.length > MAX_LEN) throw ParseException("malformed pairing link")
        val prefix = "$SCHEME://$HOST?"
        if (!raw.startsWith(prefix)) throw ParseException("not a LANyard pairing link")

        val params = LinkedHashMap<String, MutableList<String>>()
        for (part in raw.substring(prefix.length).split("&")) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            if (eq < 0) throw ParseException("malformed pairing link")
            val key = part.substring(0, eq)
            val value = URLDecoder.decode(part.substring(eq + 1), Charsets.UTF_8)
            params.getOrPut(key) { mutableListOf() }.add(value)
        }
        for (key in params.keys) if (key !in KEYS) throw ParseException("unexpected parameter $key")
        for (key in listOf("fp", "addr", "n")) {
            if (params[key]?.size != 1) throw ParseException("missing or duplicate $key")
        }
        if ((params["name"]?.size ?: 0) > 1) throw ParseException("duplicate name")

        val fingerprint = params.getValue("fp")[0]
        if (!isHex(fingerprint, 64, 64)) throw ParseException("bad fingerprint")
        val nonce = params.getValue("n")[0]
        if (!isHex(nonce, MIN_NONCE_LEN, MAX_NONCE_LEN)) throw ParseException("bad nonce")

        val name = (params["name"]?.get(0) ?: "").let {
            if (it.length > MAX_NAME_LEN) it.substring(0, MAX_NAME_LEN) else it
        }

        val addrs = ArrayList<String>()
        for (candidate in params.getValue("addr")[0].split(",")) {
            val addr = candidate.trim()
            if (addr.isEmpty()) continue
            if (!validAddr(addr)) throw ParseException("bad address")
            if (addrs.size >= MAX_ADDRS) throw ParseException("too many addresses")
            addrs.add(addr)
        }
        if (addrs.isEmpty()) throw ParseException("no addresses")

        return Payload(fingerprint, name, addrs, nonce)
    }

    private fun validAddr(addr: String): Boolean {
        if (addr.length > MAX_ADDR_LEN) return false
        val host: String
        val portText: String
        if (addr.startsWith("[")) {
            val end = addr.indexOf(']')
            if (end < 0) return false
            host = addr.substring(1, end)
            if (addr.length <= end + 1 || addr[end + 1] != ':') return false
            portText = addr.substring(end + 2)
            if (!isIpv6(host)) return false
        } else {
            val colon = addr.lastIndexOf(':')
            if (colon <= 0) return false
            host = addr.substring(0, colon)
            portText = addr.substring(colon + 1)
            if (!isIpv4(host)) return false
        }
        val port = portText.toIntOrNull() ?: return false
        return port in 1..65535
    }

    private fun isIpv4(host: String): Boolean {
        val parts = host.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } &&
                (part.toIntOrNull()?.let { it in 0..255 } ?: false)
        }
    }

    private fun isIpv6(host: String): Boolean {
        if (!host.contains(':')) return false
        return host.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
    }

    private fun isHex(s: String, min: Int, max: Int): Boolean {
        if (s.length < min || s.length > max || s.length % 2 != 0) return false
        return s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
    }
}
