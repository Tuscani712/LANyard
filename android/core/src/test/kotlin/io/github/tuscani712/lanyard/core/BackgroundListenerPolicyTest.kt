package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The listener-lifetime decisions behind Fix 2: while a transfer is running the
 * peer server and its mDNS advertisement must survive backgrounding, and stop
 * as soon as the last transfer drains (with no transfer, stop at once).
 */
class BackgroundListenerPolicyTest {

    @Test
    fun backgroundedWithNoTransferStopsImmediately() {
        val p = BackgroundListenerPolicy()
        assertFalse(p.background(activeTransfers = false), "nothing running: teardown is not deferred")
        assertFalse(p.deferred())
    }

    @Test
    fun backgroundedDuringATransferIsDeferredUntilItDrains() {
        val p = BackgroundListenerPolicy()
        assertTrue(p.background(activeTransfers = true), "a running transfer defers the teardown")
        assertTrue(p.deferred())
        assertFalse(p.shouldStop(activeTransfers = true), "still active: keep listening")
        assertTrue(p.shouldStop(activeTransfers = false), "last transfer finished: stop now")
    }

    @Test
    fun returningToForegroundCancelsTheDeferredStop() {
        val p = BackgroundListenerPolicy()
        p.background(activeTransfers = true)
        // The app comes back before the transfer drains.
        p.foreground()
        assertFalse(p.deferred())
        assertFalse(p.shouldStop(activeTransfers = false), "foreground: the server must keep running")
    }

    @Test
    fun reBackgroundingAfterTheTransferEndedStopsImmediately() {
        val p = BackgroundListenerPolicy()
        p.background(activeTransfers = true)
        assertTrue(p.shouldStop(activeTransfers = false))
        // A fresh background with nothing in flight is the plain old behaviour.
        assertFalse(p.background(activeTransfers = false))
        assertEquals(false, p.deferred())
    }
}
