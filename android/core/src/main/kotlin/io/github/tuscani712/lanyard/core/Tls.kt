package io.github.tuscani712.lanyard.core

import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
import java.net.InetAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.Provider
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * TLS for the LANyard peer protocol: TLS 1.3 with certificates on both sides.
 *
 * Provider note: Conscrypt (BoringSSL) was tried first, but it could not present
 * an Ed25519 client certificate against the Go server — the handshake aborted
 * with `SSLHandshakeException: ... SSLv3_ALERT_HANDSHAKE_FAILURE /
 * HANDSHAKE_FAILURE_ON_CLIENT_HELLO` (see the spike report). BouncyCastle's JSSE
 * provider (`bctls`) does complete the Ed25519 handshake, so it is used here.
 *
 * The server certificate is never blindly trusted. [FingerprintTrustManager]
 * computes the presented certificate's Device ID and compares it with the one
 * we expect; a mismatch aborts the handshake, so nothing is ever read from or
 * written to an impersonating peer.
 */
object Tls {
    /** The BouncyCastle JSSE provider, with the full BC provider installed. */
    val provider: Provider by lazy {
        installBouncyCastle()
        BouncyCastleJsseProvider()
    }

    fun sslContext(identity: Identity, expectedFingerprint: String): SSLContext {
        val context = SSLContext.getInstance("TLS", provider)
        context.init(
            arrayOf(IdentityKeyManager(identity)),
            arrayOf(FingerprintTrustManager(expectedFingerprint)),
            SecureRandom(),
        )
        return context
    }

    /** An [SSLSocketFactory] that offers only TLS 1.3, like the Go peer requires. */
    fun socketFactory(identity: Identity, expectedFingerprint: String): SSLSocketFactory =
        Tls13SocketFactory(sslContext(identity, expectedFingerprint).socketFactory)

    /**
     * A factory that accepts **any** server certificate and records the one that
     * was actually presented. Use this *only* for the `GET /api/v1/hello`
     * discovery probe, to learn who is at an address; everything that reads or
     * writes real data must go through [socketFactory], which pins the expected
     * fingerprint. [ProbeClient] is the only caller and exposes no data methods.
     */
    fun probeSocketFactory(identity: Identity, recorder: FingerprintRecorder): SSLSocketFactory {
        val context = SSLContext.getInstance("TLS", provider)
        context.init(
            arrayOf(IdentityKeyManager(identity)),
            arrayOf(RecordingTrustManager(recorder)),
            SecureRandom(),
        )
        return Tls13SocketFactory(context.socketFactory)
    }
}

/** Holds the fingerprint most recently presented during a probe handshake. */
class FingerprintRecorder {
    @Volatile
    var fingerprint: String? = null
        internal set
}

/** Delegates to a real factory but pins every socket to TLS 1.3. */
private class Tls13SocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(): Socket = tune(delegate.createSocket())

    override fun createSocket(host: String, port: Int): Socket = tune(delegate.createSocket(host, port))

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        tune(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket = tune(delegate.createSocket(host, port))

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        tune(delegate.createSocket(address, port, localAddress, localPort))

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        tune(delegate.createSocket(s, host, port, autoClose))

    private fun tune(socket: Socket): Socket {
        if (socket is SSLSocket) socket.enabledProtocols = arrayOf("TLSv1.3")
        return socket
    }
}

/** Presents our own Ed25519 certificate as the client certificate. */
private class IdentityKeyManager(private val identity: Identity) : X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        if (matches(keyType)) arrayOf(ALIAS) else null

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
        keyType?.firstOrNull { matches(it) }?.let { ALIAS }

    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
        keyType?.firstOrNull { matches(it) }?.let { ALIAS }

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = null

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == ALIAS) arrayOf(identity.certificate) else null

    override fun getPrivateKey(alias: String?): PrivateKey? =
        if (alias == ALIAS) identity.privateKey else null

    private fun matches(keyType: String?): Boolean =
        keyType != null && keyType.equals("Ed25519", ignoreCase = true)

    private companion object {
        const val ALIAS = "lanyard"
    }
}

/**
 * Accepts any server certificate but requires its Device ID to equal the
 * expected fingerprint. An empty expected fingerprint is rejected: this class
 * never trusts a peer unconditionally.
 */
class FingerprintTrustManager(private val expectedFingerprint: String) : X509ExtendedTrustManager() {
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = verify(chain)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) = verify(chain)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = verify(chain)

    // We do not validate client certificates here; the peer service does not ask us to.
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) = Unit

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun verify(chain: Array<X509Certificate>) {
        if (chain.isEmpty()) {
            throw CertificateException("the peer presented no certificate")
        }
        if (expectedFingerprint.isBlank()) {
            throw CertificateException("no expected peer fingerprint was provided")
        }
        val presented = Identity.fingerprintOf(chain[0])
        if (!presented.equals(expectedFingerprint, ignoreCase = true)) {
            throw CertificateException(
                "peer fingerprint ${presented.take(8)}… does not match the expected ${expectedFingerprint.take(8)}…",
            )
        }
    }
}

/**
 * Accepts any server certificate and records its Device ID for the caller to
 * inspect. This performs **no** trust decision and must never guard a request
 * that reads or writes data — see [Tls.probeSocketFactory].
 */
private class RecordingTrustManager(private val recorder: FingerprintRecorder) : X509ExtendedTrustManager() {
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = record(chain)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) = record(chain)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = record(chain)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) = Unit

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun record(chain: Array<X509Certificate>) {
        if (chain.isNotEmpty()) recorder.fingerprint = Identity.fingerprintOf(chain[0])
    }
}
