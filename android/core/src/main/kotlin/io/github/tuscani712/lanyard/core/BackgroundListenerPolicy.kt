package io.github.tuscani712.lanyard.core

/**
 * Decides when the peer listener (server + mDNS advertisement) may be torn down
 * after the app is backgrounded.
 *
 * The Android app starts the peer listener while foregrounded and normally stops
 * it in `onStop`. While a transfer is running the listener must survive
 * backgrounding, so a phone whose app is not in front can still be reached.
 * Once the last transfer finishes, and the app is still in the background, the
 * listener is stopped exactly as before.
 *
 * This is the race-sensitive part of that lifetime, kept free of Android types
 * so it can be unit-tested:
 *  - backgrounded with transfers active -> defer teardown;
 *  - transfers drain while still backgrounded -> stop now;
 *  - foregrounded again before the drain -> cancel the deferred teardown.
 */
class BackgroundListenerPolicy {
    private var deferred = false

    /** The app came to the foreground: cancel any deferred teardown. */
    @Synchronized
    fun foreground() {
        deferred = false
    }

    /**
     * The app was backgrounded. Returns true when teardown must be deferred
     * because [activeTransfers] is true, false when it may stop immediately.
     */
    @Synchronized
    fun background(activeTransfers: Boolean): Boolean {
        deferred = activeTransfers
        return deferred
    }

    /** True when a deferred teardown should now run because nothing is active. */
    @Synchronized
    fun shouldStop(activeTransfers: Boolean): Boolean = deferred && !activeTransfers

    /** True while a teardown is waiting for transfers to drain. */
    @Synchronized
    fun deferred(): Boolean = deferred
}
