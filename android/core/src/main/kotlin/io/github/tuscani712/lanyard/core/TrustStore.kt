package io.github.tuscani712.lanyard.core

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A peer this device has paired with. */
data class PairedPeer(
    val fingerprint: String,
    val name: String,
    val host: String,
    val port: Int,
    val browse: Boolean,
    val push: Boolean,
    val pairedAt: Long,
)

/** Persists paired peers. Keyed by certificate fingerprint (lowercase hex). */
interface TrustStore {
    fun list(): List<PairedPeer>
    fun find(fingerprint: String): PairedPeer?
    fun save(peer: PairedPeer)
    fun remove(fingerprint: String)
}

/**
 * A [TrustStore] backed by a single JSON file. Writes go to a sibling temp file
 * and are then atomically renamed into place, so a crash mid-write can never
 * leave a half-written store. A missing or unreadable file reads as empty.
 */
class JsonFileTrustStore(private val file: File) : TrustStore {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
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
