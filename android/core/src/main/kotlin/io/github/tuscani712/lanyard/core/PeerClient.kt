package io.github.tuscani712.lanyard.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection

/** A peer answered with an unexpected HTTP status. */
class PeerStatusException(val code: Int, val body: String) :
    RuntimeException("HTTP $code: ${body.take(200)}")

/** A local cancel aborted an in-flight push before completion. */
class PushCancelledException : RuntimeException("the push was cancelled")

/** One file in a push offer. */
data class PushFileRequest(val relPath: String, val size: Long, val mtimeMillis: Long)

/** The peer's answer to a push offer. */
data class PushOffer(
    val pushId: String,
    val accepted: Boolean,
    val maxBytes: Long,
    val offsets: Map<String, Long>,
)

/** A streamed upload's whole-file digest and the number of bytes sent. */
data class StreamedFile(val sha256: String, val bytes: Long)

/** The `/hello` response. */
data class PeerHello(
    val deviceIdLabel: String,
    val fingerprint: String,
    val name: String,
    val os: String,
    val version: String,
    val port: Int,
    // The receiver's advertised offer caps, for batching a push. Zero means the
    // peer advertised none (an older build), so the sender falls back to its own
    // conservative limits (see [PushBatching]).
    val maxOfferBytes: Long = 0,
    val maxOfferFiles: Int = 0,
    // The peer's advertised capabilities. A peer that lists
    // [Permission.CAPABILITY] understands tri-state `*_mode` fields; one that
    // does not is sent booleans only.
    val caps: List<String> = emptyList(),
) {
    /** Whether this peer negotiated the tri-state permission encoding. */
    val supportsTriState: Boolean get() = caps.contains(Permission.CAPABILITY)
}

/**
 * A client for the LANyard peer API (`/api/v1/...`) over pinned mTLS.
 *
 * Every instance is bound to one expected peer fingerprint; the TLS handshake
 * fails if the peer presents anything else, so no request reaches an
 * impersonating device. Read `internal/peerapi/client.go` for the wire shapes.
 */
class PeerClient(
    private val host: String,
    private val port: Int,
    private val identity: Identity,
    private val expectedFingerprint: String,
) : ShareReader {
    private val base = "https://$host:$port/api/v1"
    private val socketFactory = Tls.socketFactory(identity, expectedFingerprint)

    /**
     * Whether this client pins the peer to a known certificate. [Tls.socketFactory]
     * refuses the handshake unless the peer's presented Device ID equals
     * [expectedFingerprint], so any HTTP response — and therefore any 403 seen
     * below — can only come from the verified, pinned peer. Never act on a
     * signal from an unpinned identity.
     */
    private val pinned: Boolean = expectedFingerprint.isNotBlank()

    companion object {
        /**
         * Called when a peer we pinned by its certificate answers a request with
         * a 403 whose reason is exactly "not paired": it has dropped the pairing
         * on its side, so the app removes the stale local entry and tells the
         * person. Wired by [io.github.tuscani712.lanyard.PeerService]. The
         * argument is the fingerprint of the peer that refused. A permission
         * refusal (e.g. "pull not permitted") or a revoke we initiated ourselves
         * does not fire this.
         */
        @Volatile
        var onNotPaired: ((String) -> Unit)? = null
    }

    private fun maybeNotifyNotPaired(code: Int, body: String) {
        if (code != 403) return
        // Gate the auto-unpair on the pinned, verified identity: the trust
        // manager already enforces this for the handshake, and we require it
        // again here so no unpinned path can ever delete a pairing.
        if (!pinned) return
        if (PeerErrors.isNotPaired(PeerStatusException(code, body))) {
            onNotPaired?.invoke(expectedFingerprint)
        }
    }

    fun hello(): PeerHello {
        val json = requestJson("GET", "/hello")
        return PeerHello(
            deviceIdLabel = json.str("device_id"),
            fingerprint = json.str("fingerprint"),
            name = json.str("name"),
            os = json.str("os"),
            version = json.str("version"),
            port = json.int("port"),
            maxOfferBytes = json.long("max_offer_bytes"),
            maxOfferFiles = json.int("max_offer_files"),
            caps = json.getAsJsonArray("caps")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString } ?: emptyList(),
        )
    }

    fun startSession(
        mode: String,
        name: String,
        deviceId: String,
        nonce: String,
        requested: Permissions,
        invite: String = "",
        // Whether the peer advertised the tri-state capability (from its hello);
        // only then is a `*_mode` field ever sent.
        tristate: Boolean = false,
    ): JsonObject {
        val body = JsonObject().apply {
            addProperty("mode", mode)
            addProperty("name", name)
            addProperty("device_id", deviceId)
            addProperty("nonce", nonce)
            add("requested_permissions", requested.toJson(tristate))
            if (invite.isNotEmpty()) addProperty("invite", invite)
        }
        return requestJson("POST", "/session/request", body.toString())
    }

    fun sessionStatus(sessionId: String): JsonObject =
        requestJson("GET", "/session/${encode(sessionId)}")

    fun confirmSession(sessionId: String): JsonObject =
        requestJson("POST", "/session/${encode(sessionId)}/confirm", "{}")

    fun closeSession(sessionId: String, reasonTransfer: Boolean = false): JsonObject {
        val path = "/session/${encode(sessionId)}/close" + if (reasonTransfer) "?reason=transfer" else ""
        return requestJson("POST", path, "{}")
    }

    fun pushOffer(files: List<PushFileRequest>): PushOffer {
        val json = requestJson("POST", "/push/offer", pushOfferBody(files))
        val offsets = HashMap<String, Long>()
        json.getAsJsonArray("files")?.forEach { element ->
            val e = element.asJsonObject
            offsets[e.str("rel_path")] = e.long("offset")
        }
        return PushOffer(
            pushId = json.str("push_id"),
            accepted = json.get("accepted")?.asBoolean ?: false,
            maxBytes = json.long("max_bytes"),
            offsets = offsets,
        )
    }

    /** Sends a whole small file and its digest in one request (the fast path). */
    fun pushFileWithSha(pushId: String, relPath: String, bytes: ByteArray, sha256Hex: String) {
        val size = bytes.size
        val end = if (size == 0) 0 else size - 1
        val headers = mapOf(
            "Content-Range" to "bytes 0-$end/$size",
            "X-Lanyard-SHA256" to sha256Hex,
        )
        request("PUT", "/push/${encode(pushId)}/file?path=${encodeQuery(relPath)}", bytes, headers)
    }

    fun pushCompleteAll(pushId: String) {
        request("POST", "/push/${encode(pushId)}/complete", "{\"all\":true}".toByteArray(), emptyMap())
    }

    /**
     * Asks the receiver to cancel the push [pushId] because this side cancelled
     * it. Best-effort with a short timeout, so a slow or wedged peer cannot hold
     * up our teardown. Returns true when the receiver acknowledged the cancel;
     * false when it has no such route (an older peer answers 404) or the id is
     * already gone (404). Any other status is an error the caller may ignore.
     */
    fun pushCancel(pushId: String, timeoutMillis: Int = 2_000): Boolean {
        val conn = open("POST", "/push/${encode(pushId)}/cancel", "{}".toByteArray(), emptyMap())
        conn.connectTimeout = timeoutMillis
        conn.readTimeout = timeoutMillis
        val status = conn.responseCode
        // A peer that closes the connection right after its status line can make
        // reading the (error) body throw; the status is all we need.
        val text = runCatching {
            (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
        }.getOrDefault("")
        return when {
            status in 200..299 -> true
            status == 404 -> false
            else -> throw PeerStatusException(status, text)
        }
    }

    /**
     * Streams one file's remaining bytes to the peer's inbox, hashing the whole
     * file as it goes. The body is read with a fixed buffer, so a whole file is
     * never held in memory. [onBytes] reports bytes written (after [offset]).
     * Throws [PushCancelledException] if [isCancelled] returns true mid-stream.
     */
    fun pushFileStream(
        pushId: String,
        relPath: String,
        offset: Long,
        total: Long,
        source: InputStream,
        onBytes: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        throttle: Throttle = NoThrottle,
    ): StreamedFile {
        val conn = URI.create(base + "/push/${encode(pushId)}/file?path=${encodeQuery(relPath)}").toURL().openConnection()
            as HttpsURLConnection
        conn.sslSocketFactory = socketFactory
        conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        conn.requestMethod = "PUT"
        conn.connectTimeout = 10_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        val end = if (total == 0L) 0L else total - 1
        conn.setRequestProperty("Content-Range", "bytes $offset-$end/$total")
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(total - offset)

        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(256 * 1024)
        var sent = 0L
        try {
            conn.outputStream.use { out ->
                // Hash the prefix already on the receiver so the digest covers the
                // whole file, then stream the remainder.
                var skip = offset
                while (skip > 0) {
                    val want = minOf(skip, buf.size.toLong()).toInt()
                    val r = source.read(buf, 0, want)
                    if (r < 0) throw PeerStatusException(-1, "the file ended before the resume offset")
                    digest.update(buf, 0, r)
                    skip -= r
                }
                while (true) {
                    if (isCancelled()) {
                        conn.disconnect()
                        throw PushCancelledException()
                    }
                    val r = source.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    digest.update(buf, 0, r)
                    sent += r
                    onBytes(sent)
                    throttle.pace(r)
                }
            }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (status !in 200..299) {
                maybeNotifyNotPaired(status, text)
                throw PeerStatusException(status, text)
            }
        } finally {
            runCatching { source.close() }
        }
        return StreamedFile(digest.digest().joinToString("") { "%02x".format(it) }, sent)
    }

    /** Reports a finished file with its whole-file digest for verification. */
    fun pushCompleteFile(pushId: String, relPath: String, sha256Hex: String) {
        val body = JsonObject().apply {
            addProperty("rel_path", relPath)
            addProperty("sha256", sha256Hex)
        }
        request("POST", "/push/${encode(pushId)}/complete", body.toString().toByteArray(), emptyMap())
    }

    fun sendSnippet(text: String): JsonObject {
        val body = JsonObject().apply { addProperty("text", text) }
        return requestJson("POST", "/snippet", body.toString())
    }

    /**
     * Asks the peer to drop us from its trust store, so an unpair on this phone
     * also removes it there. The peer only ever drops the caller's own entry, so
     * this needs no extra authorization and is safe to repeat.
     */
    fun revokeTrust(): JsonObject = requestJson("POST", "/trust/revoke", "{}", notifyNotPaired = false)

    /**
     * Notifies the peer of an unpair, idempotently. A `403`/"not paired" means
     * the peer already has no entry for us, which is the state we wanted, so a
     * repeated notification counts as success rather than an error. Returns true
     * when the peer no longer trusts us (removed now, or already gone); false
     * only when the peer could not be reached at all.
     */
    fun revokeTrustIdempotent(): Boolean = try {
        revokeTrust()
        true
    } catch (e: PeerStatusException) {
        e.code == 403
    } catch (_: Exception) {
        false
    }

    fun listShares(): List<JsonObject> = requestArray("GET", "/shares").map { it.asJsonObject }

    fun tree(shareId: String, path: String): List<JsonObject> =
        requestArray("GET", "/shares/${encode(shareId)}/tree?path=${encodeQuery(path)}").map { it.asJsonObject }

    fun manifest(shareId: String, path: String): JsonObject =
        requestJson("GET", "/shares/${encode(shareId)}/manifest?path=${encodeQuery(path)}")

    fun fileHash(shareId: String, path: String): JsonObject =
        requestJson("GET", "/shares/${encode(shareId)}/hash?path=${encodeQuery(path)}")

    fun openFile(shareId: String, path: String, rangeFrom: Long = 0): InputStream {
        val conn = open("GET", "/shares/${encode(shareId)}/file?path=${encodeQuery(path)}", null, emptyMap())
        if (rangeFrom > 0) conn.setRequestProperty("Range", "bytes=$rangeFrom-")
        return checkAndStream(conn)
    }

    // --- ShareReader: the download session's view of a share ---

    override fun manifestFiles(shareId: String, path: String): JsonObject = manifest(shareId, path)

    override fun openFileStream(shareId: String, path: String, rangeFrom: Long): InputStream =
        openFile(shareId, path, rangeFrom)

    override fun wholeFileHash(shareId: String, path: String): String = fileHash(shareId, path).str("sha256")

    override fun reportComplete(shareId: String, verified: List<Pair<String, String>>): Boolean {
        val arr = JsonArray()
        verified.forEach { (path, sha) ->
            arr.add(JsonObject().apply {
                addProperty("path", path)
                addProperty("sha256", sha)
            })
        }
        val body = JsonObject().apply { add("files", arr) }
        val json = requestJson("POST", "/shares/${encode(shareId)}/complete", body.toString())
        return json.get("consumed")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
    }

    // --- transport ---

    private fun requestJson(
        method: String,
        path: String,
        body: String? = null,
        notifyNotPaired: Boolean = true,
    ): JsonObject =
        JsonParser.parseString(request(method, path, body?.toByteArray(), emptyMap(), notifyNotPaired)).asJsonObject

    private fun requestArray(method: String, path: String): JsonArray =
        JsonParser.parseString(request(method, path, null, emptyMap())).asJsonArray

    private fun request(
        method: String,
        path: String,
        body: ByteArray?,
        headers: Map<String, String>,
        notifyNotPaired: Boolean = true,
    ): String {
        val conn = open(method, path, body, headers)
        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (status !in 200..299) {
            if (notifyNotPaired) maybeNotifyNotPaired(status, text)
            throw PeerStatusException(status, text)
        }
        return text
    }

    private fun open(
        method: String,
        path: String,
        body: ByteArray?,
        headers: Map<String, String>,
    ): HttpsURLConnection {
        val conn = URI.create(base + path).toURL().openConnection() as HttpsURLConnection
        conn.sslSocketFactory = socketFactory
        // Identity is pinned by fingerprint in the trust manager, not by hostname.
        conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        conn.requestMethod = method
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body != null) {
            conn.doOutput = true
            if (conn.getRequestProperty("Content-Type") == null && method != "PUT") {
                conn.setRequestProperty("Content-Type", "application/json")
            }
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
        }
        return conn
    }

    private fun checkAndStream(conn: HttpsURLConnection): InputStream {
        val status = conn.responseCode
        if (status !in 200..299) {
            val text = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            maybeNotifyNotPaired(status, text)
            throw PeerStatusException(status, text)
        }
        return conn.inputStream
    }

    private fun encode(s: String): String = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    private fun encodeQuery(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)

    private fun JsonObject.str(key: String): String = get(key)?.takeIf { !it.isJsonNull }?.asString ?: ""

    private fun JsonObject.int(key: String): Int = get(key)?.takeIf { !it.isJsonNull }?.asInt ?: 0

    private fun JsonObject.long(key: String): Long = get(key)?.takeIf { !it.isJsonNull }?.asLong ?: 0L
}

/**
 * Permissions one side allows the other; mirrors `trust.Permissions` and adds
 * the tri-state modes. The `browse`/`push` booleans are always present (an
 * old peer reads only them); the `*_mode` strings are present only when
 * [toJson] is asked for the tri-state form, i.e. the peer advertised
 * [Permission.CAPABILITY]. A null mode means "not negotiated — fall back to the
 * boolean".
 */
data class Permissions(
    val browse: Boolean = false,
    val push: Boolean = false,
    val pushMaxBytes: Long = 0,
    val askOver: Long = 0,
    // Text (snippets) is its own action; an old peer only ever had the
    // push permission, so [text] defaults to `false` and is carried separately.
    val text: Boolean = false,
    // Tri-state view of the same fields. Null until negotiated.
    val browseMode: Permission? = null,
    val pushMode: Permission? = null,
    val textMode: Permission? = null,
) {
    /**
     * The wire object. [tristate] gates the `*_mode` keys so an old peer never
     * receives a key it cannot parse; the booleans are always included as the
     * fallback ("allow else deny").
     */
    fun toJson(tristate: Boolean = false): JsonObject = JsonObject().apply {
        addProperty("browse", browse)
        addProperty("push", push)
        addProperty("text", text)
        if (pushMaxBytes > 0) addProperty("push_max_bytes", pushMaxBytes)
        if (askOver > 0) addProperty("ask_over", askOver)
        if (tristate) {
            browseMode?.let { addProperty("browse_mode", it.wire) }
            pushMode?.let { addProperty("push_mode", it.wire) }
            textMode?.let { addProperty("text_mode", it.wire) }
        }
    }
}

/** Lowercase hex SHA-256 of a byte array, matching the Go file digest. */
fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
