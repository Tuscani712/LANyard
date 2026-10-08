package io.github.tuscani712.lanyard.core

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A device we unpaired locally but could not tell to drop us, because it was
 * offline. The address is the last known one and may be empty; discovery can
 * still supply a live address before the retry runs.
 *
 * This mirrors the desktop's `trust.Entry`-keyed pending-unpair map: the phone
 * keeps the record so an unpair is eventually delivered to the other side.
 */
data class PendingUnpair(
    val fingerprint: String,
    val name: String,
    val host: String,
    val port: Int,
    val queuedAt: Long,
)

/** Persists pending-unpair notifications, keyed by fingerprint (lowercase hex). */
interface PendingUnpairStore {
    fun list(): List<PendingUnpair>
    fun find(fingerprint: String): PendingUnpair?
    fun upsert(entry: PendingUnpair)
    fun remove(fingerprint: String)
    fun clear()
}

/** A [PendingUnpairStore] backed by one JSON file, written atomically. */
class JsonFilePendingUnpairStore(private val file: File) : PendingUnpairStore {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val type = object : TypeToken<MutableMap<String, PendingUnpair>>() {}.type

    @Synchronized
    override fun list(): List<PendingUnpair> = read().values.toList()

    @Synchronized
    override fun find(fingerprint: String): PendingUnpair? = read()[key(fingerprint)]

    @Synchronized
    override fun upsert(entry: PendingUnpair) {
        if (entry.fingerprint.isBlank()) return
        val peers = read()
        peers[key(entry.fingerprint)] = entry.copy(fingerprint = key(entry.fingerprint))
        write(peers)
    }

    @Synchronized
    override fun remove(fingerprint: String) {
        val peers = read()
        if (peers.remove(key(fingerprint)) != null) write(peers)
    }

    @Synchronized
    override fun clear() = write(LinkedHashMap())

    private fun key(fingerprint: String): String = fingerprint.lowercase()

    private fun read(): MutableMap<String, PendingUnpair> {
        if (!file.isFile) return LinkedHashMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            (gson.fromJson(file.readText(), type) as MutableMap<String, PendingUnpair>?) ?: LinkedHashMap()
        } catch (_: JsonSyntaxException) {
            LinkedHashMap()
        }
    }

    private fun write(peers: Map<String, PendingUnpair>) {
        val dir = file.absoluteFile.parentFile
        dir?.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(gson.toJson(peers, type))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * Delivers pending-unpair notifications. For each record that has a usable
 * address, the peer is told to drop us; a success (or a 403 meaning it already
 * did) removes the record, while an unreachable peer keeps it for the next time
 * that device is seen.
 */
object UnpairRetry {
    fun retry(
        store: PendingUnpairStore,
        identity: Identity?,
        diag: (String) -> Unit = {},
    ): List<String> {
        if (identity == null) return emptyList()
        val delivered = ArrayList<String>()
        for (p in store.list()) {
            if (p.host.isBlank() || p.port !in 1..65535) continue
            val ok = PeerClient(p.host, p.port, identity, p.fingerprint).revokeTrustIdempotent()
            val short = Display.shortFp(p.fingerprint)
            if (ok) {
                store.remove(p.fingerprint)
                delivered.add(p.fingerprint)
                diag("[pairing] peer=$short unpair-retry result=ok")
            } else {
                diag("[pairing] peer=$short unpair-retry result=unreachable")
            }
        }
        return delivered
    }

    /**
     * The pending record whose fingerprint starts with [shortId], if any. Used
     * to attach a freshly discovered address before a retry.
     */
    fun matchByShortId(store: PendingUnpairStore, shortId: String): PendingUnpair? {
        if (shortId.isBlank()) return null
        return store.list().firstOrNull {
            SelfFilter.ownShortId(it.fingerprint).equals(shortId, ignoreCase = true)
        }
    }
}
