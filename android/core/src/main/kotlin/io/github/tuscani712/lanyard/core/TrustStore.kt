package io.github.tuscani712.lanyard.core

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import java.io.File
import java.lang.reflect.Type
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A peer this device has paired with.
 *
 * The tri-state fields ([browse], [push], [text]) are what **this** device
 * allows the peer to do to it — the editable "They can" direction (G1). The
 * `allow*` booleans are what the peer allows **this** device to do — the
 * read-only "They allow me" direction, learned at pairing.
 *
 * For peers written before G1, the historic `browse`/`push` booleans migrate
 * through [Permission.fromStoredBool]: `true -> ALLOW`, `false -> ASK`. The
 * [text] grant did not exist then and was governed by push, so a legacy entry
 * with no `text` field inherits its (migrated) push grant rather than defaulting
 * to [Permission.ASK] — a device that could already send text must not silently
 * start prompting after an upgrade. The secondary constructor keeps the many
 * existing call sites that pass booleans compiling, with the same migration rule.
 */
data class PairedPeer(
    val fingerprint: String,
    val name: String,
    val host: String,
    val port: Int,
    // They can (this device's grant to the peer), tri-state, editable.
    val browse: Permission,
    val push: Permission,
    val text: Permission,
    val pairedAt: Long,
    // Permissions for files we receive from this peer. Both default to 0 for
    // peers.json written before these fields existed; 0 means "no limit / no
    // ask", which is the safe, non-surprising default for an old entry.
    val pushMaxBytes: Long = 0,
    val askOver: Long = 0,
    // A local, person-chosen name for this device (F3). It is stored only here
    // and never sent on the wire; [name] (the broadcast name) stays the wire
    // value and the fallback whenever the alias is blank. Defaults to "" for
    // peers.json written before this field existed.
    val alias: String = "",
    // They allow me: the peer's grant to this device, learned at pairing and
    // read-only on this device. Kept as a boolean because a peer's "ask" cannot
    // be answered here — it is decided on the peer. Defaults to true: when the
    // peer's grant is genuinely unknown (this device was the pairing responder,
    // or an entry predates G1) an outgoing action stays available and the peer
    // enforces its own Ask/Never on arrival. An initiator overwrites this with
    // the responder's explicit grant at pairing.
    val allowBrowse: Boolean = true,
    val allowPush: Boolean = true,
    val allowText: Boolean = true,
) {
    /**
     * Legacy/companion constructor: booleans follow the store migration
     * (`true -> ALLOW`, `false -> ASK`), so an old call site keeps its meaning
     * and never silently becomes a Never.
     */
    constructor(
        fingerprint: String,
        name: String,
        host: String,
        port: Int,
        browse: Boolean,
        push: Boolean,
        pairedAt: Long,
        pushMaxBytes: Long = 0,
        askOver: Long = 0,
        // Text predicates on push when not given, matching the pre-G1 behavior
        // where snippets used the push permission.
        text: Boolean = push,
        alias: String = "",
        allowBrowse: Boolean = true,
        allowPush: Boolean = true,
        allowText: Boolean = true,
    ) : this(
        fingerprint = fingerprint,
        name = name,
        host = host,
        port = port,
        browse = Permission.fromStoredBool(browse),
        push = Permission.fromStoredBool(push),
        text = if (text) Permission.ALLOW else Permission.ASK,
        pairedAt = pairedAt,
        pushMaxBytes = pushMaxBytes,
        askOver = askOver,
        alias = alias,
        allowBrowse = allowBrowse,
        allowPush = allowPush,
        allowText = allowText,
    )
}

/** Persists paired peers. Keyed by certificate fingerprint (lowercase hex). */
interface TrustStore {
    fun list(): List<PairedPeer>
    fun find(fingerprint: String): PairedPeer?
    fun save(peer: PairedPeer)
    fun remove(fingerprint: String)
}

/**
 * The JSON shape of a [PairedPeer]. It serializes the tri-state fields as their
 * lowercase wire strings and reads either that string or the historic boolean
 * (migration `false -> ASK`). A hand-written adapter is used rather than the
 * reflective one so a field missing from an older file lands on its real Kotlin
 * default instead of `null`/`0`.
 */
class PairedPeerAdapter : JsonSerializer<PairedPeer>, JsonDeserializer<PairedPeer> {
    override fun serialize(src: PairedPeer, typeOfSrc: Type, context: JsonSerializationContext): JsonElement =
        JsonObject().apply {
            addProperty("fingerprint", src.fingerprint)
            addProperty("name", src.name)
            addProperty("host", src.host)
            addProperty("port", src.port)
            addProperty("browse", src.browse.wire)
            addProperty("push", src.push.wire)
            addProperty("text", src.text.wire)
            addProperty("pairedAt", src.pairedAt)
            addProperty("pushMaxBytes", src.pushMaxBytes)
            addProperty("askOver", src.askOver)
            addProperty("alias", src.alias)
            addProperty("allowBrowse", src.allowBrowse)
            addProperty("allowPush", src.allowPush)
            addProperty("allowText", src.allowText)
        }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): PairedPeer {
        val o = json.asJsonObject
        val push = o.permission("push")
        return PairedPeer(
            fingerprint = o.str("fingerprint"),
            name = o.str("name"),
            host = o.str("host"),
            port = o.int("port"),
            browse = o.permission("browse"),
            push = push,
            // Before G1 text had no field and was governed by push; an entry
            // without it inherits the push grant rather than defaulting to Ask.
            text = if (o.has("text")) o.permission("text") else push,
            pairedAt = o.long("pairedAt"),
            pushMaxBytes = o.long("pushMaxBytes"),
            askOver = o.long("askOver"),
            alias = o.str("alias"),
            allowBrowse = o.bool("allowBrowse", true),
            allowPush = o.bool("allowPush", true),
            allowText = o.bool("allowText", true),
        )
    }

    /** Reads a tri-state field: a wire string, an old boolean, else [ASK]. */
    private fun JsonObject.permission(key: String): Permission {
        val el = get(key) ?: return Permission.ASK
        if (el.isJsonPrimitive) {
            val p = el.asJsonPrimitive
            if (p.isBoolean) return Permission.fromStoredBool(p.asBoolean)
            Permission.fromString(p.asString)?.let { return it }
        }
        return Permission.ASK
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""

    private fun JsonObject.int(key: String): Int =
        get(key)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0

    private fun JsonObject.long(key: String): Long =
        get(key)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L

    private fun JsonObject.bool(key: String, default: Boolean): Boolean =
        get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: default
}

/**
 * A [TrustStore] backed by a single JSON file. Writes go to a sibling temp file
 * and are then atomically renamed into place, so a crash mid-write can never
 * leave a half-written store. A missing or unreadable file reads as empty.
 */
class JsonFileTrustStore(private val file: File) : TrustStore {
    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .registerTypeAdapter(PairedPeer::class.java, PairedPeerAdapter())
        .create()
    private val type = object : TypeToken<MutableMap<String, PairedPeer>>() {}.type

    @Synchronized
    override fun list(): List<PairedPeer> = read().values.toList()

    @Synchronized
    override fun find(fingerprint: String): PairedPeer? = read()[key(fingerprint)]

    @Synchronized
    override fun save(peer: PairedPeer) {
        val normalized = peer.copy(fingerprint = key(peer.fingerprint))
        val peers = read()
        peers[normalized.fingerprint] = normalized
        write(peers)
    }

    @Synchronized
    override fun remove(fingerprint: String) {
        val peers = read()
        if (peers.remove(key(fingerprint)) != null) write(peers)
    }

    private fun key(fingerprint: String): String = fingerprint.lowercase()

    private fun read(): MutableMap<String, PairedPeer> {
        if (!file.isFile) return LinkedHashMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            (gson.fromJson(file.readText(), type) as MutableMap<String, PairedPeer>?) ?: LinkedHashMap()
        } catch (_: JsonSyntaxException) {
            LinkedHashMap()
        }
    }

    private fun write(peers: Map<String, PairedPeer>) {
        val dir = file.absoluteFile.parentFile
        dir?.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(gson.toJson(peers, type))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
