package io.github.tuscani712.lanyard.core

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Provider
import java.security.Security

/**
 * Returns a BouncyCastle provider that actually supports Ed25519.
 *
 * On the desktop JVM this registers the bundled `BouncyCastleProvider` under the
 * name "BC". On Android, the platform ships its own *stripped* provider also
 * named "BC" that has no Ed25519, so `getInstance("Ed25519", "BC")` throws
 * `NoSuchAlgorithmException`. We detect that and replace it with the full one.
 */
internal fun installBouncyCastle(): Provider {
    Security.getProvider("BC")?.let { existing ->
        if (existing.getService("KeyPairGenerator", "Ed25519") != null) return existing
    }
    Security.removeProvider("BC")
    val provider = BouncyCastleProvider()
    Security.insertProviderAt(provider, 1)
    return provider
}
