package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * The probe client is the one place an unpinned TLS connection is allowed, so it
 * must be incapable of anything but the `GET /hello` probe. This guards that
 * boundary: if someone adds a method that sends data, this fails.
 */
class ProbeClientTest {

    @Test
    fun exposesOnlyTheHelloProbe() {
        val dataMethods = ProbeClient::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) }
            .map { it.name }
            .filterNot { it.startsWith("access$") || it == "toString" || it == "hashCode" || it == "equals" }

        // allowed: the probe itself and the fingerprint read-back.
        val allowed = setOf("hello", "observedFingerprint", "getObservedFingerprint")
        val unexpected = dataMethods.filterNot { it in allowed }
        assertTrue(unexpected.isEmpty(), "ProbeClient exposes unexpected public methods: $unexpected")
    }

    @Test
    fun hasNoDataSendingMethodNames() {
        val suspect = Regex("push|send|post|put|delete|request|session|confirm|close|upload|share", RegexOption.IGNORE_CASE)
        val offenders = ProbeClient::class.java.declaredMethods
            .map { it.name }
            .filter { suspect.containsMatchIn(it) }
        assertTrue(offenders.isEmpty(), "ProbeClient has data-sending methods: $offenders")
    }
}
