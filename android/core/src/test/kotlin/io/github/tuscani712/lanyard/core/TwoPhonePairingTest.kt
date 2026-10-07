package io.github.tuscani712.lanyard.core

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

/**
 * Two full Kotlin phone cores on loopback, driven through the same client code
 * paths the app uses ([PairingFlow] / [PeerClient]). Each phone owns its own
 * [Identity], [TrustStore], [PairingSessions], [PairInvites] and [PeerServer],
 * advertises with the same TXT shape and filters itself with [SelfFilter].
 *
 * This is the phone-to-phone pairing regression: mDNS discovery works, but the
 * two phones must also be able to pair. Phone-to-desktop pairing is covered by
 * the live Go tests; these are pure Kotlin so they run without the Go binary.
 */
class TwoPhonePairingTest {

    private class Phone(val label: String) : AutoCloseable {
        val identity: Identity = Identity.generate(label)
        private val trustFile = Files.createTempFile("lanyard-trust", ".json").toFile().also { it.delete() }
        val trust: TrustStore = JsonFileTrustStore(trustFile)
        val invites = PairInvites()
        val sessions = PairingSessions(selfFp = { identity.deviceId }, trust = trust)
        val diagnostics = ServerDiagnostics()
        private val spool = Files.createTempDirectory("lanyard-spool").toFile()
        private val receiver = InboxReceiver(spool, PushDestination { rel, _, _ -> rel }, { 1L shl 40 })
        private val server = PeerServer(
            sessions = sessions,
            receiver = receiver,
            invites = invites,
            isPaired = { trust.find(it) },
            diagnostics = diagnostics,
        )

        /** Advertised exactly like PeerService.hello: identical /hello shape to a desktop. */
        val port: Int = server.start(identity) { p ->
            JsonObject().apply {
                addProperty("device_id", identity.deviceId.take(16))
                addProperty("fingerprint", identity.deviceId)
                addProperty("name", label)
                addProperty("os", "android")
                addProperty("version", "test")
                addProperty("port", p)
            }
        }

        /** Completed TLS handshakes the server has served (mutual-TLS proof). */
        val handshakes: Long get() = server.handshakes.get()

        /** The phone's own mDNS short id, used to drop its own announcement. */
        val shortId: String get() = SelfFilter.ownShortId(identity.deviceId)

        /** Exactly what PeerService.pairingLink() builds: real address + bound port + fresh invite. */
        fun pairingLink(): String {
            val addrs = listOf("127.0.0.1:$port")
            val invite = invites.mint()
            return PairLink.build(PairLink.Payload(identity.deviceId, label, addrs, invite.token))
        }

        /** The person taps Accept on the next prompt. Returns the accepted session id. */
        fun acceptNextPending(timeoutMs: Long = 15_000): String? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val p = sessions.pending()
                if (p.isNotEmpty()) {
                    sessions.accept(p.first().id)
                    return p.first().id
                }
                Thread.sleep(25)
            }
            return null
        }

        override fun close() = server.stop()
    }

    private fun randomNonce(): String {
        val buf = ByteArray(16)
        SecureRandom().nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString ?: ""

    @Test
    @Timeout(60)
    fun discoveryProbeBurstDoesNotStarvePairing() {
        Phone("Pixel A").use { a ->
            Phone("Pixel B").use { b ->
                // Discovery/liveness probes each open a fresh TLS connection and
                // must not count against a budget that pairing then needs.
                repeat(20) {
                    ProbeClient("127.0.0.1", b.port, a.identity).hello()
                }
                val link = b.pairingLink()
                val acceptor = Thread { b.acceptNextPending() }.also { it.start() }
                val result = PairingFlow.pair(link, a.identity, a.label, a.trust, timeoutMs = 10_000)
                acceptor.join()
                assertTrue(result is PairResult.Paired, "probes must not starve pairing, got $result")
            }
        }
    }

    @Test
    @Timeout(60)
    fun unreachableFirstAddressInTheLinkIsSkipped() {
        Phone("Pixel A").use { a ->
            Phone("Pixel B").use { b ->
                // A phone with several site-local addresses puts them all in the
                // link; a dead one first must not stop the pair.
                val invite = b.invites.mint()
                val link = PairLink.build(
                    PairLink.Payload(
                        fingerprint = b.identity.deviceId,
                        name = b.label,
                        addrs = listOf("127.0.0.1:1", "127.0.0.1:2", "127.0.0.1:${b.port}"),
                        nonce = invite.token,
                    ),
                )
                val acceptor = Thread { b.acceptNextPending() }.also { it.start() }
                val result = PairingFlow.pair(link, a.identity, a.label, a.trust, timeoutMs = 15_000)
                acceptor.join()
                assertTrue(result is PairResult.Paired, "a dead first address must be skipped, got $result")
            }
        }
    }

    @Test
    @Timeout(60)
    fun secondAttemptWithTheSameInviteIsRefused() {
        Phone("Pixel A").use { a ->
            Phone("Pixel B").use { b ->
                val link = b.pairingLink()
                // First attempt consumes the one-time invite.
                val first = b.invites.consume(PairLink.parse(link).nonce)
                assertTrue(first, "the first attempt must consume the invite")
                // Retrying the SAME link is refused (the burned-QR case).
                val acceptor = Thread { b.acceptNextPending() }.also { it.start() }
                val result = PairingFlow.pair(link, a.identity, a.label, a.trust, timeoutMs = 5_000)
                acceptor.join()
                assertTrue(result is PairResult.Refused, "a burned invite must be refused, got $result")
            }
        }
    }

    @Test
    @Timeout(60)
    fun qrFlowOverALanAddressWritesTrustOnBothPhones() {
        val lan = java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }?.hostAddress
        org.junit.jupiter.api.Assumptions.assumeTrue(lan != null)
        Phone("Pixel A").use { a ->
            Phone("Pixel B").use { b ->
                val invite = b.invites.mint()
                val link = PairLink.build(
                    PairLink.Payload(b.identity.deviceId, b.label, listOf("$lan:${b.port}"), invite.token),
                )
                val acceptor = Thread { b.acceptNextPending() }.also { it.start() }
                val result = PairingFlow.pair(link, a.identity, a.label, a.trust)
                acceptor.join()
                assertTrue(result is PairResult.Paired, "LAN-address pairing failed: $result")
                assertNotNull(b.trust.find(a.identity.deviceId), "B must trust A")
            }
        }
    }

    @Test
    @Timeout(60)
    fun retryAfterAnAbandonedAttemptIsNotBlockedByTheStalePrompt() {
        Phone("Pixel A").use { a ->
            Phone("Pixel B").use { b ->
                // First attempt: B's person never answers the prompt, so A gives
                // up. A must not leave a pending session behind, or MAX_PENDING_
                // PER_PEER (1) rejects the retry with 409.
                val first = PairingFlow.pair(b.pairingLink(), a.identity, a.label, a.trust, timeoutMs = 1_000)
                assertTrue(first is PairResult.Expired, "the first attempt should time out, got $first")

                // The user tries again; this must be allowed.
                val acceptor = Thread { b.acceptNextPending() }.also { it.start() }
                val retry = PairingFlow.pair(b.pairingLink(), a.identity, a.label, a.trust, timeoutMs = 10_000)
                acceptor.join()
                assertTrue(retry is PairResult.Paired, "a retry from the same phone must not be blocked, got $retry")
            }
        }
    }

    @Test
    @Timeout(60)
    fun pairButtonFlowWritesTrustOnBothPhones() {
        Phone("Pixel A").use { a ->
            Phone("Pixel B").use { b ->
                // A dials B, SAS path (no invite): the "Pair" button path.
                val client = PeerClient("127.0.0.1", b.port, a.identity, b.identity.deviceId)
                val session = client.startSession(
                    mode = "pair",
                    name = a.label,
                    deviceId = a.identity.deviceId,
                    nonce = randomNonce(),
                    requested = Permissions(browse = true, push = true),
                )
                val sessionId = session.str("session_id")
                assertTrue(sessionId.isNotEmpty(), "B must open a session: $session")

                // B's person approves.
                val acceptor = Thread { b.acceptNextPending() }.also { it.start() }

                var status = ""
                val deadline = System.currentTimeMillis() + 15_000
                while (System.currentTimeMillis() < deadline) {
                    status = client.sessionStatus(sessionId).str("status")
                    if (status == "accepted") break
                    Thread.sleep(100)
                }
                acceptor.join()
                assertEquals("accepted", status, "B must accept the session")

                client.confirmSession(sessionId)
                assertNotNull(b.trust.find(a.identity.deviceId), "B must save A to its trust store")

                // A writes its side exactly like PairingFlow.finish does.
                a.trust.save(
                    PairedPeer(
                        fingerprint = b.identity.deviceId,
                        name = b.label,
                        host = "127.0.0.1",
                        port = b.port,
                        browse = true,
                        push = true,
                        pairedAt = System.currentTimeMillis(),
                    ),
                )
                assertNotNull(a.trust.find(b.identity.deviceId), "A must save B to its trust store")

                client.closeSession(sessionId)
            }
        }
    }

    @Test
    @Timeout(60)
    fun bothPhonesInitiateAtOnceThenBothAccept() {
        Phone("Pixel A").use { a ->
            Phone("Pixel B").use { b ->
                // A dials B and B dials A, at the same time (the "two prompts at once" case).
                val aToB = PeerClient("127.0.0.1", b.port, a.identity, b.identity.deviceId)
                    .startSession("pair", a.label, a.identity.deviceId, randomNonce(), Permissions(browse = true, push = true))
                val bToA = PeerClient("127.0.0.1", a.port, b.identity, a.identity.deviceId)
                    .startSession("pair", b.label, b.identity.deviceId, randomNonce(), Permissions(browse = true, push = true))
                assertTrue(aToB.str("session_id").isNotEmpty(), "A->B session: $aToB")
                assertTrue(bToA.str("session_id").isNotEmpty(), "B->A session: $bToA")

                // Each phone approves the prompt from the other.
                assertTrue(a.acceptNextPending() != null, "A must show B's prompt")
                assertTrue(b.acceptNextPending() != null, "B must show A's prompt")

                // Each initiator confirms its own outgoing session.
                val confirmA = PeerClient("127.0.0.1", b.port, a.identity, b.identity.deviceId)
                val confirmB = PeerClient("127.0.0.1", a.port, b.identity, a.identity.deviceId)
                awaitStatus(confirmA, aToB.str("session_id"))
                awaitStatus(confirmB, bToA.str("session_id"))
                confirmA.confirmSession(aToB.str("session_id"))
                confirmB.confirmSession(bToA.str("session_id"))

                assertNotNull(b.trust.find(a.identity.deviceId), "B must trust A")
                assertNotNull(a.trust.find(b.identity.deviceId), "A must trust B")
            }
        }
    }

    @Test
    @Timeout(60)
    fun unpairedConnectionCapDoesNotStarveASecondPhone() {
        Phone("Pixel A").use { a ->
            Phone("Pixel B").use { b ->
                // Simulate mDNS/discovery verification traffic: several unpaired
                // /hello connections held open on B (the JDK keep-alive cache).
                val held = ArrayList<java.net.Socket>()
                try {
                    repeat(4) {
                        val factory = Tls.socketFactory(a.identity, b.identity.deviceId)
                        val s = factory.createSocket("127.0.0.1", b.port)
                        (s as javax.net.ssl.SSLSocket).startHandshake()
                        s.getOutputStream().write("GET /api/v1/hello HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                        s.getOutputStream().flush()
                        s.getInputStream().read(ByteArray(16))
                        held.add(s)
                    }
                    // Now the real pairing on a fresh connection must not be refused.
                    val link = b.pairingLink()
                    val acceptor = Thread { b.acceptNextPending() }.also { it.start() }
                    val result = PairingFlow.pair(link, a.identity, a.label, a.trust, timeoutMs = 10_000)
                    acceptor.join()
                    assertTrue(result is PairResult.Paired, "pairing must survive discovery connections, got $result")
                } finally {
                    held.forEach { runCatching { it.close() } }
                }
            }
        }
    }

    /**
     * The point raised in review: an in-process test can accidentally pass with
     * a plain socket. This pins that the client half really speaks mutual TLS
     * 1.3 and presents an Ed25519 client certificate to the responder's
     * [PeerServer], whose `needClientAuth = true` refuses a certificate-less
     * client. A plain TCP socket cannot satisfy any assertion here.
     */
    @Test
    @Timeout(60)
    fun phonePresentsARealEd25519ClientCertificateOverTls() {
        Phone("Pixel A").use { a ->
            Phone("Pixel B").use { b ->
                assertEquals("Ed25519", a.identity.certificate.publicKey.algorithm,
                    "the client identity must be an Ed25519 key")
                assertEquals("Ed25519", b.identity.certificate.publicKey.algorithm,
                    "the server identity must be an Ed25519 key")

                val before = b.handshakes
                val raw = Tls.socketFactory(a.identity, b.identity.deviceId)
                    .createSocket("127.0.0.1", b.port)

                // Fails loudly (ClassCastException) if this were ever a plain socket.
                val ssl = raw as SSLSocket
                ssl.enabledProtocols = arrayOf("TLSv1.3")
                ssl.startHandshake()

                assertEquals("TLSv1.3", ssl.session.protocol, "the phones must negotiate TLS 1.3")
                val serverCert = ssl.session.peerCertificates.first() as X509Certificate
                assertEquals("Ed25519", serverCert.publicKey.algorithm,
                    "B must present an Ed25519 server certificate, not a plain socket")
                assertEquals(b.identity.deviceId, Identity.fingerprintOf(serverCert),
                    "the presented certificate must be exactly B's identity")

                // Wait for B's side of the exchange to settle, then confirm the
                // server actually accepted a client certificate (needClientAuth).
                awaitHandshakes(b, before + 1)
                assertEquals(before + 1, b.handshakes, "B must have completed one mutual-TLS handshake")
                val diag = b.diagnostics.snapshot().joinToString("\n")
                assertTrue(
                    diag.contains("handshake ok peer=${a.identity.deviceId.take(8)}"),
                    "B's log must show A's Ed25519 client cert fingerprint, got:\n$diag",
                )
            }
        }
    }

    /**
     * A first address that accepts TCP but never answers the TLS handshake (a
     * blackhole, like one phone's address while it is dozing or on another
     * subnet) must be abandoned within [ProbeClient]'s 5s read/connect timeout
     * and the next address tried. Pins the per-address probe timeout + fallback.
     */
    @Test
    @Timeout(60)
    fun hungFirstAddressIsAbandonedAndTheNextAddressIsTried() {
        val hung = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val held = ArrayList<java.net.Socket>()
        val accepter = Thread {
            runCatching {
                while (!hung.isClosed) {
                    val s = hung.accept()
                    held.add(s) // accept, then never speak TLS
                }
            }
        }.also { it.isDaemon = true; it.start() }
        try {
            Phone("Pixel A").use { a ->
                Phone("Pixel B").use { b ->
                    val invite = b.invites.mint()
                    val link = PairLink.build(
                        PairLink.Payload(
                            fingerprint = b.identity.deviceId,
                            name = b.label,
                            addrs = listOf("127.0.0.1:${hung.localPort}", "127.0.0.1:${b.port}"),
                            nonce = invite.token,
                        ),
                    )
                    val acceptor = Thread { b.acceptNextPending() }.also { it.start() }
                    val startedAt = System.currentTimeMillis()
                    val result = PairingFlow.pair(link, a.identity, a.label, a.trust, timeoutMs = 20_000)
                    val elapsed = System.currentTimeMillis() - startedAt
                    acceptor.join()
                    assertTrue(result is PairResult.Paired, "a hung first address must be skipped, got $result")
                    assertTrue(elapsed < 20_000, "the 5s probe timeout must bound the hung address, took ${elapsed}ms")
                }
            }
        } finally {
            runCatching { hung.close() }
            held.forEach { runCatching { it.close() } }
            accepter.interrupt()
        }
    }

    /**
     * Addresses are tried in the order carried by the link, and a fingerprint
     * mismatch on an earlier address does not stop the search: a decoy that
     * presents a valid but different certificate is skipped for the real peer.
     */
    @Test
    @Timeout(60)
    fun aDecoyAddressWithTheWrongFingerprintFallsBackToTheRealOne() {
        Phone("Pixel A").use { a ->
            Phone("Decoy").use { decoy ->
                Phone("Real B").use { b ->
                    val invite = b.invites.mint()
                    val link = PairLink.build(
                        PairLink.Payload(
                            fingerprint = b.identity.deviceId,
                            name = b.label,
                            addrs = listOf("127.0.0.1:${decoy.port}", "127.0.0.1:${b.port}"),
                            nonce = invite.token,
                        ),
                    )
                    val acceptor = Thread { b.acceptNextPending() }.also { it.start() }
                    val result = PairingFlow.pair(link, a.identity, a.label, a.trust, timeoutMs = 15_000)
                    acceptor.join()
                    assertTrue(result is PairResult.Paired, "the real peer after a decoy must still pair, got $result")
                }
            }
        }
    }

    private fun awaitHandshakes(phone: Phone, target: Long, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && phone.handshakes < target) Thread.sleep(20)
    }

    private fun awaitStatus(client: PeerClient, id: String, timeoutMs: Long = 10_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var status = ""
        while (System.currentTimeMillis() < deadline) {
            status = client.sessionStatus(id).str("status")
            if (status == "accepted") return status
            Thread.sleep(50)
        }
        return status
    }

    @Test
    @Timeout(60)
    fun qrFlowWritesTrustOnBothPhones() {
        Phone("Pixel A").use { a ->
            Phone("Pixel B").use { b ->
                // B shows its QR/link; A scans it and drives PairingFlow.
                val link = b.pairingLink()
                val acceptor = Thread { b.acceptNextPending() }.also { it.start() }

                val result = PairingFlow.pair(link, a.identity, a.label, a.trust)
                acceptor.join()

                assertTrue(result is PairResult.Paired, "A expected Paired, got $result")
                assertNotNull(a.trust.find(b.identity.deviceId), "A must save B to its trust store")
                assertNotNull(b.trust.find(a.identity.deviceId), "B must save A to its trust store")
            }
        }
    }
}
