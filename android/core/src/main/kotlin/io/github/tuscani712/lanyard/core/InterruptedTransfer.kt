package io.github.tuscani712.lanyard.core

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One transfer that stopped before it finished, with enough to resume it later.
 * A push receives this from the peer's retry; a pull (download) records it so
 * the partial `.part` can be continued. [payload] is an opaque, app-owned string
 * the app uses to rebuild the work (e.g. a download's share id and path); the
 * store never interprets it. It carries no secrets and no file contents.
 */
data class InterruptedTransfer(
    val id: String,
    val direction: String, // "send" | "receive"
    val peerFingerprint: String,
    val peerName: String,
    val label: String,
    val total: Long,
    val done: Long,
    val queuedAt: Long,
    val payload: String = "",
) {
    /** True when some bytes are already on disk, so a resume starts from them. */
    val hasPartial: Boolean get() = done > 0L
}

/** Persists interrupted-transfer descriptors so they survive a process death. */
interface InterruptedTransferStore {
    fun list(): List<InterruptedTransfer>
    fun upsert(entry: InterruptedTransfer)
    /** Removes [id], reporting whether a record was present. */
    fun remove(id: String): Boolean
    fun clear()
}

/** An [InterruptedTransferStore] backed by one JSON file, written atomically. */
class JsonFileInterruptedTransferStore(private val file: File) : InterruptedTransferStore {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val type = object : TypeToken<MutableMap<String, InterruptedTransfer>>() {}.type

    @Synchronized
    override fun list(): List<InterruptedTransfer> = read().values.toList()

    @Synchronized
    override fun upsert(entry: InterruptedTransfer) {
        if (entry.id.isBlank()) return
        val entries = read()
        entries[entry.id] = entry
        write(entries)
    }

    @Synchronized
    override fun remove(id: String): Boolean {
        val entries = read()
        val present = entries.remove(id) != null
        if (present) write(entries)
        return present
    }

    @Synchronized
    override fun clear() = write(LinkedHashMap())

    private fun read(): MutableMap<String, InterruptedTransfer> {
        if (!file.isFile) return LinkedHashMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            (gson.fromJson(file.readText(), type) as MutableMap<String, InterruptedTransfer>?) ?: LinkedHashMap()
        } catch (_: JsonSyntaxException) {
            LinkedHashMap()
        }
    }

    private fun write(entries: Map<String, InterruptedTransfer>) {
        val dir = file.absoluteFile.parentFile
        dir?.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(gson.toJson(entries, type))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * Decides which interrupted transfers to resume, mirroring [PendingUnpairRetry]:
 * every "peer is reachable" signal (a successful hello either way, an inbound
 * handshake, mDNS sighting, or the app returning to the foreground) triggers an
 * immediate attempt, and a [RETRY_INTERVAL_MS] timer is a safety net for a peer
 * that comes back without one. The clock is injected so the policy is testable
 * without real time.
 *
 * The planner is pure: it selects descriptors and reports them; the caller does
 * the actual re-enqueue and calls [onResumed] so the record is cleared.
 */
class InterruptedTransferRetry(
    private val store: InterruptedTransferStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /** How often the timer retries while anything is still pending. */
        const val RETRY_INTERVAL_MS = 30_000L
    }

    /** The last attempt's clock value; [Long.MIN_VALUE] so the first tick fires. */
    private var lastAttemptAt = Long.MIN_VALUE

    /** True while any interrupted transfer is still waiting to be resumed. */
    fun hasPending(): Boolean = store.list().isNotEmpty()

    /**
     * A peer became reachable. When it matches a pending record, that record is
     * resumed now (never waiting for the timer). Passing no fingerprint means
     * "the app is back and any known peer may be reachable": every record is
     * offered, and the caller's resume attempt is a no-op if the peer is down.
     */
    fun onPeerReachable(shortId: String? = null): List<InterruptedTransfer> {
        val all = store.list()
        val match = if (shortId.isNullOrBlank()) all else all.filter {
            SelfFilter.ownShortId(it.peerFingerprint).equals(shortId, ignoreCase = true) ||
                it.peerFingerprint.startsWith(shortId, ignoreCase = true)
        }
        lastAttemptAt = clock()
        return resume(match)
    }

    /**
     * The periodic nudge: resumes at most once per [RETRY_INTERVAL_MS] while
     * something is pending, so a peer that never returns is not hammered.
     */
    fun tick(now: Long = clock()): List<InterruptedTransfer> {
        if (!hasPending()) return emptyList()
        if (lastAttemptAt != Long.MIN_VALUE && now - lastAttemptAt < RETRY_INTERVAL_MS) return emptyList()
        lastAttemptAt = now
        return resume(store.list())
    }

    private fun resume(candidates: List<InterruptedTransfer>): List<InterruptedTransfer> {
        // Selection only: the caller performs the resume and removes the record
        // once the work is actually re-enqueued, so a descriptor is never
        // dropped for a peer that turned out to be unreachable after all.
        return candidates
    }
}
