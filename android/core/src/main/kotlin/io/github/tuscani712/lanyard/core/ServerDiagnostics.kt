package io.github.tuscani712.lanyard.core

/**
 * How serious one diagnostics event is. Mirrors the desktop's `xferlog` levels
 * (Batch 6d): routine, healthy chatter is [Debug] and is kept out of the durable
 * log, while anything a person may need to diagnose stays at [Info] or above.
 */
enum class DiagLevel { Debug, Info, Warn, Error }

/**
 * A small in-memory ring buffer of server events, so a person can paste what
 * the phone saw without adb (Task 30). It keeps the last [capacity] events,
 * oldest first. Events are deliberately low-detail: no file contents, no full
 * fingerprints (only a short prefix), no tokens, no secrets. The report is
 * redacted again on the way out by [Redaction].
 *
 * A [RotatingWriter] can be attached (Batch 4F): every event is then also
 * redacted and appended to a durable, rotating file, so the log survives a
 * restart. The in-memory ring stays the fast, current-run view.
 *
 * Levels (Batch 6d) keep the durable log calm. A routine successful probe or a
 * re-announced peer is recorded at [DiagLevel.Debug]: it stays in the ring (so
 * the copied diagnostics report remains complete) but is never written to the
 * durable, rotating file. Genuine failures stay at [DiagLevel.Warn]/[DiagLevel.Error]
 * and a normal eviction at [DiagLevel.Info], so all of them remain visible.
 */
class ServerDiagnostics(
    private val capacity: Int = 1000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val events = ArrayDeque<String>()
    private val lock = Any()

    /**
     * An optional sink (used by the app to mirror events to logcat), told the
     * level so routine chatter can be demoted to verbose. Never a secret.
     */
    @Volatile
    var onRecord: ((DiagLevel, String) -> Unit)? = null

    /** An optional durable, rotating file. Lines are redacted before they hit it. */
    @Volatile
    var file: RotatingWriter? = null

    fun record(event: String, level: DiagLevel = DiagLevel.Info) {
        val line = timestamp() + " " + event
        synchronized(lock) {
            if (events.size >= capacity) events.removeFirst()
            events.addLast(line)
        }
        onRecord?.invoke(level, line)
        // Debug is routine, healthy chatter (a successful probe, a re-announced
        // peer). It is kept in the ring above for the copied report, but the
        // durable log only carries Info and above so it does not fill up.
        if (level != DiagLevel.Debug) file?.append(Redaction.redact(line))
    }

    /** The events oldest-first. */
    fun snapshot(): List<String> = synchronized(lock) { events.toList() }

    fun clear() = synchronized(lock) { events.clear() }

    /**
     * Marks the start of this run in the durable log so restarts are visible.
     * No-op when no file is attached.
     */
    fun markAppStart(epochMillis: Long = clock()) {
        file?.append(Diagnostics.appStartDivider(epochMillis))
    }

    /**
     * The durable log oldest-first, previous runs included, or the empty string
     * when no file is attached. Already redacted at write time.
     */
    fun persistedLog(): String = file?.readAll().orEmpty()

    private fun timestamp(): String {
        val ms = clock()
        val s = (ms / 1000) % 86400
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return "%02d:%02d:%02d.%03d".format(h, m, sec, ms % 1000)
    }
}
