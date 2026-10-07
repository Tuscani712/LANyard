package io.github.tuscani712.lanyard.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URL
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection

/**
 * The discovery probe: it performs exactly one kind of request — a
 * `GET /api/v1/hello` — to learn who is at an address, then reads the
 * fingerprint of the certificate that answered.
 *
 * It deliberately exposes no method that sends data. This is the only place an
 * unpinned TLS connection is allowed (see [Tls.probeSocketFactory]); the caller
 * compares [observedFingerprint] with what it expects and must refuse a
 * mismatch. Everything that reads or writes real data uses [PeerClient], whose
 * trust manager pins the expected fingerprint.
 */
class ProbeClient(
    host: String,
    port: Int,
    identity: Identity,
    private val connectTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
) {
    private val url = "https://$host:$port/api/v1/hello"
    private val recorder = FingerprintRecorder()
    private val socketFactory = Tls.probeSocketFactory(identity, recorder)

    /** The Device ID of the certificate the peer presented on the last probe. */
    fun observedFingerprint(): String = recorder.fingerprint ?: ""

    fun hello(): PeerHello {
        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.sslSocketFactory = socketFactory
        conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        conn.requestMethod = "GET"
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        val status = conn.responseCode
        val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        if (status !in 200..299) throw PeerStatusException(status, text)
        val json = JsonParser.parseString(text).asJsonObject
        return PeerHello(
            deviceIdLabel = json.str("device_id"),
            fingerprint = json.str("fingerprint"),
            name = json.str("name"),
            os = json.str("os"),
            version = json.str("version"),
            port = json.int("port"),
        )
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString ?: ""

    private fun JsonObject.int(key: String): Int =
        get(key)?.takeIf { !it.isJsonNull }?.asInt ?: 0

    companion object {
        /** Connect and read timeout for one probe attempt, in milliseconds. */
        const val DEFAULT_TIMEOUT_MS = 5_000
    }
}
