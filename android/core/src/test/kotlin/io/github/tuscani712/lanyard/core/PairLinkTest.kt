package io.github.tuscani712.lanyard.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Ported from `internal/pairlink/pairlink_test.go`. */
class PairLinkTest {
    private val testFp = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val testNonce = "00112233445566778899aabbccddeeff"

    @Test
    fun roundTrip() {
        val input = PairLink.Payload(
            fingerprint = testFp,
            name = "Kitchen PC",
            addrs = listOf("192.168.1.20:47800", "10.0.0.5:47800"),
            nonce = testNonce,
        )
        val out = PairLink.parse(PairLink.build(input))
        assertEquals(input, out)
    }

    @Test
    fun roundTripWithoutName() {
        val out = PairLink.parse(
            PairLink.build(PairLink.Payload(testFp, "", listOf("192.168.1.20:47800"), testNonce)),
        )
        assertEquals("", out.name)
    }

    @Test
    fun buildLeavesAddrUnescaped() {
        val uri = PairLink.build(
            PairLink.Payload(
                testFp, "Kitchen PC",
                listOf("192.168.1.20:47800", "10.0.0.5:47800"), testNonce,
            ),
        )
        assertTrue(!uri.contains("%3A", true) && !uri.contains("%2C", true), "addr must stay unescaped: $uri")
        assertTrue(uri.contains("addr=192.168.1.20:47800,10.0.0.5:47800"), "unexpected addr: $uri")
        assertTrue(!uri.contains("name=Kitchen PC"), "name must be escaped: $uri")
    }

    @Test
    fun parseAcceptsEncodedAndUnescapedAddr() {
        val unescaped = "lanyard://pair?fp=$testFp&addr=192.168.1.20:47800&n=$testNonce"
        val encoded = "lanyard://pair?fp=$testFp&addr=192.168.1.20%3A47800&n=$testNonce"
        for ((name, raw) in mapOf("unescaped" to unescaped, "encoded" to encoded)) {
            val p = PairLink.parse(raw)
            assertEquals(listOf("192.168.1.20:47800"), p.addrs, "$name addresses")
        }
    }

    @Test
    fun parseRejectsMalformed() {
        val good = PairLink.build(PairLink.Payload(testFp, "", listOf("192.168.1.20:47800"), testNonce))
        val cases = mapOf(
            "empty" to "",
            "wrong scheme" to good.replace("lanyard://", "http://"),
            "wrong host" to good.replace("lanyard://pair", "lanyard://other"),
            "missing fp" to "lanyard://pair?addr=192.168.1.20:47800&n=$testNonce",
            "missing addr" to "lanyard://pair?fp=$testFp&n=$testNonce",
            "missing nonce" to "lanyard://pair?fp=$testFp&addr=192.168.1.20:47800",
            "extra param" to "$good&evil=1",
            "duplicate fp" to "lanyard://pair?fp=$testFp&fp=$testFp&addr=192.168.1.20:47800&n=$testNonce",
            "short fp" to "lanyard://pair?fp=abcd&addr=192.168.1.20:47800&n=$testNonce",
            "nonhex fp" to "lanyard://pair?fp=${"z".repeat(64)}&addr=192.168.1.20:47800&n=$testNonce",
            "short nonce" to "lanyard://pair?fp=$testFp&addr=192.168.1.20:47800&n=abcd",
            "bad host name" to "lanyard://pair?fp=$testFp&addr=example.com:47800&n=$testNonce",
            "bad port" to "lanyard://pair?fp=$testFp&addr=192.168.1.20:99999&n=$testNonce",
            "no port" to "lanyard://pair?fp=$testFp&addr=192.168.1.20&n=$testNonce",
        )
        for ((name, raw) in cases) {
            assertFailsWith<PairLink.ParseException>("$name should be rejected") { PairLink.parse(raw) }
        }
    }

    @Test
    fun parseCapsAddrs() {
        val addrs = List(PairLink.MAX_ADDRS + 1) { "192.168.1.20:47800" }
        assertFailsWith<PairLink.ParseException> {
            PairLink.parse(PairLink.build(PairLink.Payload(testFp, "", addrs, testNonce)))
        }
    }

    @Test
    fun parseCapsLength() {
        val long = "lanyard://pair?fp=$testFp&addr=192.168.1.20:47800&n=$testNonce&name=" + "a".repeat(PairLink.MAX_LEN)
        assertFailsWith<PairLink.ParseException> { PairLink.parse(long) }
    }

    @Test
    fun parseCapsName() {
        val out = PairLink.parse(
            PairLink.build(PairLink.Payload(testFp, "n".repeat(250), listOf("192.168.1.20:47800"), testNonce)),
        )
        assertEquals(200, out.name.length)
    }
}
