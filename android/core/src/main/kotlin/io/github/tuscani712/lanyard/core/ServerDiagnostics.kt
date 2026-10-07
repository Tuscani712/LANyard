package io.github.tuscani712.lanyard.core

/**
 * A small in-memory ring buffer of server events, so a person can paste what
 * the phone saw without adb (Task 30). It keeps the last [capacity] events,
 * oldest first. Events are deliberately low-detail: no file contents, no full
 * fingerprints (only a short prefix), no tokens, no secrets. The report is
 * redacted again on the way out by [Redaction].
 *
 * Not persisted: it is diagnostic breadcrumbs for the current app run only.
 */
class ServerDiagnostics(
    private val capacity: Int = 200,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val events = ArrayDeque<String>()
    private val lock = Any()

    /** An optional sink (used by the app to mirror events to logcat). Never a secret. */
    @Volatile
    var onRecord: ((String) -> Unit)? = null

    fun record(event: String) {
        val line = timestamp() + " " + event
        synchronized(lock) {
            if (events.size >= capacity) events.removeFirst()
            events.addLast(line)
        }
        onRecord?.invoke(line)
    }

    /** The events oldest-first. */
    fun snapshot(): List<String> = synchronized(lock) { events.toList() }

    fun clear() = synchronized(lock) { events.clear() }

    private fun timestamp(): String {
        val ms = clock()
        val s = (ms / 1000) % 86400
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return "%02d:%02d:%02d.%03d".format(h, m, sec, ms % 1000)
    }
}
