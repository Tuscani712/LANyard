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

/**
 * A pending record together with the delivery generation that was current when
 * it was read. Passing the generation back to [PendingUnpairStore.upsertIfCurrent]
 * lets a racing retry detect that a sibling already delivered the revoke.
 */
data class PendingUnpairSnapshot(val entry: PendingUnpair, val generation: Long)

/** Persists pending-unpair notifications, keyed by fingerprint (lowercase hex). */
interface PendingUnpairStore {
    fun list(): List<PendingUnpair>
    fun find(fingerprint: String): PendingUnpair?
    fun upsert(entry: PendingUnpair)
    /** Removes [fingerprint], reporting whether a record was present. */
    fun remove(fingerprint: String): Boolean
    fun clear()

    /**
     * A snapshot of every record with the delivery generation read atomically
     * alongside it, so a caller that goes on to re-arm the address can tell
     * whether a sibling delivered (and removed) the record in the meantime.
     */
    fun snapshot(): List<PendingUnpairSnapshot>

    /**
     * How many times a notification for [fingerprint] has been successfully
     * delivered (removed). Monotonic per fingerprint.
     */
    fun deliveryGeneration(fingerprint: String): Long

    /**
     * Persists [entry] only when no sibling delivery has succeeded since
     * [generation] was read. Returns true when the record was stored. This is
     * what stops a duplicate/racing retry from re-arming a revoke that already
     * succeeded.
     */
    fun upsertIfCurrent(entry: PendingUnpair, generation: Long): Boolean
}

/** A [PendingUnpairStore] backed by one JSON file, written atomically. */
class JsonFilePendingUnpairStore(private val file: File) : PendingUnpairStore {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val type = object : TypeToken<MutableMap<String, PendingUnpair>>() {}.type

    // In-memory delivery count per fingerprint. It only guards in-flight races,
    // so it need not survive a restart (no sibling is racing across one).
    private val delivered = HashMap<String, Long>()

    @Synchronized
    override fun list(): List<PendingUnpair> = read().values.toList()

    @Synchronized
    override fun find(fingerprint: String): PendingUnpair? = read()[key(fingerprint)]

    @Synchronized
    override fun snapshot(): List<PendingUnpairSnapshot> {
        val peers = read()
        return peers.values.map { PendingUnpairSnapshot(it, delivered[key(it.fingerprint)] ?: 0L) }
    }

    @Synchronized
    override fun deliveryGeneration(fingerprint: String): Long = delivered[key(fingerprint)] ?: 0L

    @Synchronized
    override fun upsert(entry: PendingUnpair) {
        if (entry.fingerprint.isBlank()) return
        val peers = read()
        peers[key(entry.fingerprint)] = entry.copy(fingerprint = key(entry.fingerprint))
        write(peers)
    }

    @Synchronized
    override fun upsertIfCurrent(entry: PendingUnpair, generation: Long): Boolean {
        if (entry.fingerprint.isBlank()) return false
        if (deliveryGeneration(entry.fingerprint) != generation) return false
        val peers = read()
        peers[key(entry.fingerprint)] = entry.copy(fingerprint = key(entry.fingerprint))
        write(peers)
        return true
    }

    @Synchronized
    override fun remove(fingerprint: String): Boolean {
        val k = key(fingerprint)
        val peers = read()
        val present = peers.remove(k) != null
        // Bump on every clear, present or not: a successful notification for an
        // initial unpair (which had no queued record yet) must still stop a
        // racing duplicate from arming one afterwards.
        delivered[k] = (delivered[k] ?: 0L) + 1
        if (present) write(peers)
        return present
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
        trust: TrustStore? = null,
        diag: (String) -> Unit = {},
    ): List<String> {
        if (identity == null) return emptyList()
        val delivered = ArrayList<String>()
        for (p in store.list()) {
            val short = Display.shortFp(p.fingerprint)
            // A revoke queued before the current pairing is stale: it belongs to
            // a previous life of this peer. Delivering it would unpair the
            // freshly paired device, so it is dropped instead.
            val current = trust?.find(p.fingerprint)
            if (current != null && current.pairedAt > p.queuedAt) {
                store.remove(p.fingerprint)
                diag("[pairing] peer=$short unpair-retry result=stale-dropped")
                continue
            }
            if (p.host.isBlank() || p.port !in 1..65535) continue
            val ok = PeerClient(p.host, p.port, identity, p.fingerprint).revokeTrustIdempotent()
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
     * The pending record whose fingerprint starts with [shortId], together with
     * the delivery generation read atomically alongside it, or null. The
     * generation lets a caller attach a fresh address without re-arming a record
     * a sibling already delivered.
     */
    fun matchByShortId(store: PendingUnpairStore, shortId: String): PendingUnpairSnapshot? {
        if (shortId.isBlank()) return null
        return store.snapshot().firstOrNull {
            SelfFilter.ownShortId(it.entry.fingerprint).equals(shortId, ignoreCase = true)
        }
    }
}

/**
 * Decides when a pending-unpair notification is retried. Every "peer is
 * reachable" signal (a successful hello in either direction, an inbound
 * handshake, an mDNS sighting, the app coming to the foreground) triggers an
 * immediate attempt, and a [RETRY_INTERVAL_MS] timer retries while any record
 * is still pending, so a peer that returns on its own is not missed. The clock
 * is injected, so the timer policy is testable without real time.
 */
class PendingUnpairRetry(
    private val store: PendingUnpairStore,
    private val identity: () -> Identity?,
    private val trust: TrustStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val diag: (String) -> Unit = {},
    // The delivery action, injectable so the trigger policy can be tested
    // without a live peer. Defaults to the real idempotent revoke.
    private val deliver: (PendingUnpairStore, Identity?, TrustStore?) -> List<String> =
        { s, i, t -> UnpairRetry.retry(s, i, t, diag) },
) {
    companion object {
        /** How often the timer retries while any record is pending. */
        const val RETRY_INTERVAL_MS = 30_000L
    }

    /** The last attempt's clock value; [Long.MIN_VALUE] so the first tick fires. */
    private var lastAttemptAt = Long.MIN_VALUE

    /** True while any unpair notification is still waiting to be delivered. */
    fun hasPending(): Boolean = store.list().isNotEmpty()

    /**
     * A peer became reachable. When [shortId]/[host]/[port] name it, a pending
     * record for that peer gets the fresh address first. Delivers immediately,
     * so a reconnection never waits for the timer.
     */
    fun onPeerReachable(shortId: String? = null, host: String? = null, port: Int? = null): List<String> {
        if (shortId != null && host != null && port != null && port in 1..65535) {
            UnpairRetry.matchByShortId(store, shortId)?.let { match ->
                // Re-arm the address only if no sibling delivered the record
                // between the snapshot and now; otherwise this would resurrect a
                // revoke that already succeeded.
                store.upsertIfCurrent(match.entry.copy(host = host, port = port), match.generation)
            }
        }
        return attempt()
    }

    /**
     * The periodic nudge: delivers at most once per [RETRY_INTERVAL_MS] while
     * something is pending, so a peer that never returns is not hammered.
     */
    fun tick(now: Long = clock()): List<String> {
        if (!hasPending()) return emptyList()
        if (lastAttemptAt != Long.MIN_VALUE && now - lastAttemptAt < RETRY_INTERVAL_MS) return emptyList()
        return attempt(now)
    }

    private fun attempt(now: Long = clock()): List<String> {
        lastAttemptAt = now
        return deliver(store, identity(), trust)
    }
}
