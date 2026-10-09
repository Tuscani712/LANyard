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
        diag: (String, DiagLevel) -> Unit = { _, _ -> },
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
                diag("[pairing] peer=$short unpair-retry result=stale-dropped", DiagLevel.Info)
                continue
            }
            if (p.host.isBlank() || p.port !in 1..65535) continue
            val ok = PeerClient(p.host, p.port, identity, p.fingerprint).revokeTrustIdempotent()
            if (ok) {
                store.remove(p.fingerprint)
                delivered.add(p.fingerprint)
                diag("[pairing] peer=$short unpair-retry result=ok", DiagLevel.Info)
            } else {
                // A routine retry of an offline peer is healthy chatter, not a
                // fault: it stays in the in-memory report but out of the durable
                // log, so a burst of signals cannot fill the log (see the retry
                // gate in [PendingUnpairRetry]).
                diag("[pairing] peer=$short unpair-retry result=unreachable", DiagLevel.Debug)
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
 * A per-peer rate limit for pending-unpair retries. An unreachable peer used to
 * be retried on every "peer reachable" signal, which arrives in bursts (an mDNS
 * re-announcement, a probe, an inbound handshake): five to six attempts a
 * second, and five to six log lines each. This allows at most one attempt per
 * peer per interval, doubling the interval after each consecutive failure up to
 * a bounded maximum, so a peer that stays down is retried less and less while
 * one that returns is delivered at once. The clock is injected, so the policy
 * is tested without real time.
 */
class UnpairRetryGate(
    private val baseIntervalMs: Long = BASE_INTERVAL_MS,
    private val maxIntervalMs: Long = MAX_INTERVAL_MS,
) {
    private class Entry(var lastAttemptAt: Long = Long.MIN_VALUE, var failures: Int = 0)

    private val entries = HashMap<String, Entry>()

    /** True when [fingerprint] may be attempted at [now]: never tried, or past its backoff. */
    @Synchronized
    fun allow(fingerprint: String, now: Long): Boolean {
        val e = entries.getOrPut(fingerprint.lowercase()) { Entry() }
        if (e.lastAttemptAt == Long.MIN_VALUE) return true
        return now - e.lastAttemptAt >= intervalFor(e.failures)
    }

    /** Records that an attempt for [fingerprint] started at [now]. */
    @Synchronized
    fun recordAttempt(fingerprint: String, now: Long) {
        val e = entries.getOrPut(fingerprint.lowercase()) { Entry() }
        e.lastAttemptAt = now
    }

    /** Records the outcome: a delivery resets the backoff, a failure lengthens it. */
    @Synchronized
    fun recordResult(fingerprint: String, delivered: Boolean) {
        val e = entries.getOrPut(fingerprint.lowercase()) { Entry() }
        e.failures = if (delivered) 0 else (e.failures + 1).coerceAtMost(MAX_FAILURES)
    }

    /** The current interval for [fingerprint], for the diagnostics line and tests. */
    @Synchronized
    fun intervalFor(fingerprint: String): Long =
        intervalFor(entries[fingerprint.lowercase()]?.failures ?: 0)

    private fun intervalFor(failures: Int): Long {
        var ms = baseIntervalMs
        repeat(failures) {
            if (ms >= maxIntervalMs) return maxIntervalMs
            ms = (ms * 2).coerceAtMost(maxIntervalMs)
        }
        return ms
    }

    companion object {
        /** One attempt per peer per this interval before any backoff. */
        const val BASE_INTERVAL_MS = 3_000L

        /** The backoff never grows past this. */
        const val MAX_INTERVAL_MS = 60_000L

        /** The failure count at which the backoff is capped. */
        const val MAX_FAILURES = 6
    }
}

/**
 * Decides when a pending-unpair notification is retried. Every "peer is
 * reachable" signal (a successful hello in either direction, an inbound
 * handshake, an mDNS sighting, the app coming to the foreground) triggers an
 * attempt, but at most one attempt per peer per [UnpairRetryGate] interval with
 * a bounded backoff, so a burst of signals is not a burst of attempts. A
 * [RETRY_INTERVAL_MS] timer retries while any record is still pending. The clock
 * is injected, so the timer policy is testable without real time.
 */
class PendingUnpairRetry(
    private val store: PendingUnpairStore,
    private val identity: () -> Identity?,
    private val trust: TrustStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val diag: (String, DiagLevel) -> Unit = { _, _ -> },
    // The per-peer burst limiter shared by every reachable signal.
    private val gate: UnpairRetryGate = UnpairRetryGate(),
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
     * record for that peer gets the fresh address first, then it is delivered
     * unless the per-peer gate says a recent attempt already ran (a burst of
     * reachable signals for the same peer is one attempt, not many).
     */
    fun onPeerReachable(shortId: String? = null, host: String? = null, port: Int? = null): List<String> {
        var fingerprint: String? = null
        if (shortId != null && host != null && port != null && port in 1..65535) {
            UnpairRetry.matchByShortId(store, shortId)?.let { match ->
                // Re-arm the address only if no sibling delivered the record
                // between the snapshot and now; otherwise this would resurrect a
                // revoke that already succeeded.
                store.upsertIfCurrent(match.entry.copy(host = host, port = port), match.generation)
                fingerprint = match.entry.fingerprint
            }
        }
        val now = clock()
        if (fingerprint != null) {
            if (!gate.allow(fingerprint, now)) {
                diag(
                    "[pairing] peer=${Display.shortFp(fingerprint)} unpair-retry skipped reason=backoff " +
                        "retry_in=${gate.intervalFor(fingerprint)}ms",
                    DiagLevel.Debug,
                )
                return emptyList()
            }
            gate.recordAttempt(fingerprint, now)
        }
        val delivered = attempt(now)
        if (fingerprint != null) {
            gate.recordResult(fingerprint, delivered.any { it.equals(fingerprint, ignoreCase = true) })
        }
        return delivered
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
