package io.github.tuscani712.lanyard.core

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * The device's long-term identity: an Ed25519 key and a self-signed X.509
 * certificate, matching `internal/identity/identity.go`. The Device ID is the
 * lowercase hex SHA-256 of the certificate's SubjectPublicKeyInfo (64 chars),
 * the same value the Go side computes.
 */
class Identity(
    val certificate: X509Certificate,
    val privateKey: PrivateKey,
) {
    /** Lowercase hex SHA-256 of the certificate's SubjectPublicKeyInfo. */
    val deviceId: String = fingerprintOf(certificate)

    /** DER-encoded certificate. */
    fun certificateDer(): ByteArray = certificate.encoded

    /** PKCS#8-encoded private key. */
    fun privateKeyPkcs8(): ByteArray = privateKey.encoded

    companion object {
        private val PROVIDER: Provider = ensureBouncyCastle()

        /** Generates a new identity; the subject mirrors the Go implementation. */
        fun generate(deviceName: String): Identity {
            val generator = KeyPairGenerator.getInstance("Ed25519", PROVIDER)
            val keyPair = generator.generateKeyPair()

            val now = System.currentTimeMillis()
            val notBefore = Date(now - 3_600_000L) // 1 hour in the past, for clock skew
            val notAfter = Date(now + 10L * 365 * 24 * 3600 * 1000) // 10 years
            val serial = BigInteger(120, SecureRandom())

            val subject: X500Name = X500NameBuilder(BCStyle.INSTANCE)
                .addRDN(BCStyle.CN, "LANyard $deviceName")
                .build()

            val builder = JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, keyPair.public,
            )
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
            builder.addExtension(
                Extension.extendedKeyUsage, false,
                ExtendedKeyUsage(arrayOf(KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth)),
            )
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))

            val signer = JcaContentSignerBuilder("Ed25519").setProvider(PROVIDER).build(keyPair.private)
            val certificate = JcaX509CertificateConverter().setProvider(PROVIDER)
                .getCertificate(builder.build(signer))
            return Identity(certificate, keyPair.private)
        }

        /** Rebuilds an identity from a PKCS#8 key and a DER certificate. */
        fun fromEncoded(privateKeyPkcs8: ByteArray, certificateDer: ByteArray): Identity {
            val keyFactory = KeyFactory.getInstance("Ed25519")
            val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKeyPkcs8))
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(certificateDer.inputStream()) as X509Certificate
            return Identity(certificate, privateKey)
        }

        /** Lowercase hex SHA-256 of a certificate's SubjectPublicKeyInfo. */
        fun fingerprintOf(certificate: X509Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(certificate.publicKey.encoded)
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun ensureBouncyCastle(): Provider {
            Security.getProvider("BC")?.let { return it }
            val provider = BouncyCastleProvider()
            Security.addProvider(provider)
            return provider
        }
    }
}

/**
 * Persists an identity. Backed by files for now; the Android Keystore can
 * implement this later without touching callers.
 */
interface IdentityStore {
    fun load(): Identity?
    fun save(identity: Identity)
}

/** A simple on-disk store writing DER files, like the Go data directory. */
class FileIdentityStore(private val dir: File) : IdentityStore {
    private val certFile = File(dir, "identity.crt")
    private val keyFile = File(dir, "identity.key")

    override fun load(): Identity? {
        if (!certFile.isFile || !keyFile.isFile) return null
        return Identity.fromEncoded(keyFile.readBytes(), certFile.readBytes())
    }

    override fun save(identity: Identity) {
        dir.mkdirs()
        keyFile.writeBytes(identity.privateKeyPkcs8())
        certFile.writeBytes(identity.certificateDer())
    }
}
