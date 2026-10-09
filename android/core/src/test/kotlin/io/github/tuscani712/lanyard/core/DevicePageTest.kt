package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * F2: the device page's pure rules — the canonical action order/labels, the
 * enabled/disabled decision, the permissions block and the status block.
 */
class DevicePageTest {
    private fun peer(browse: Boolean = true, push: Boolean = true) = PairedPeer(
        fingerprint = "aa".repeat(32), name = "Desktop", host = "10.0.0.5", port = 47800,
        browse = browse, push = push, pairedAt = 1,
    )

    @Test
    fun canonicalOrderAndLabels() {
        assertEquals(
            listOf(
                DeviceAction.SEND_FILES,
                DeviceAction.SEND_FOLDER,
                DeviceAction.SEND_TEXT,
                DeviceAction.BROWSE_SHARES,
                DeviceAction.RENAME,
                DeviceAction.UNPAIR,
            ),
            DevicePage.ACTIONS,
        )
        assertEquals(
            listOf(
                "Send files…",
                "Send folder…",
                "Send text",
                "Browse their shares",
                "Rename…",
                "Unpair this device",
            ),
            DevicePage.ACTIONS.map { DevicePage.label(it) },
        )
    }

    @Test
    fun offlineDisablesEveryRemoteActionWithAReason() {
        val states = DevicePage.states(online = false, canPush = true, canBrowse = true)
        for (action in listOf(
            DeviceAction.SEND_FILES,
            DeviceAction.SEND_FOLDER,
            DeviceAction.SEND_TEXT,
            DeviceAction.BROWSE_SHARES,
        )) {
            val s = states.first { it.action == action }
            assertFalse(s.enabled, "$action must be disabled offline")
            assertEquals(DevicePage.OFFLINE_REASON, s.reason)
        }
        // Local actions stay available so the person can still rename or unpair.
        assertTrue(states.first { it.action == DeviceAction.RENAME }.enabled)
        assertTrue(states.first { it.action == DeviceAction.UNPAIR }.enabled)
    }

    @Test
    fun missingPushPermissionDisablesTheSendActions() {
        val states = DevicePage.states(online = true, canPush = false, canBrowse = true)
        for (action in listOf(DeviceAction.SEND_FILES, DeviceAction.SEND_FOLDER, DeviceAction.SEND_TEXT)) {
            val s = states.first { it.action == action }
            assertFalse(s.enabled)
            assertEquals(DevicePage.NO_PUSH_REASON, s.reason)
        }
        assertTrue(states.first { it.action == DeviceAction.BROWSE_SHARES }.enabled)
    }

    @Test
    fun missingBrowsePermissionDisablesBrowse() {
        val s = DevicePage.state(DeviceAction.BROWSE_SHARES, online = true, canPush = true, canBrowse = false)
        assertFalse(s.enabled)
        assertEquals(DevicePage.NO_BROWSE_REASON, s.reason)
    }

    @Test
    fun preparingDisablesFileSendsButNotText() {
        val files = DevicePage.state(DeviceAction.SEND_FILES, online = true, canPush = true, canBrowse = true, preparingFiles = 2)
        assertFalse(files.enabled)
        assertEquals(DevicePage.PREPARING_REASON, files.reason)
        assertFalse(DevicePage.state(DeviceAction.SEND_FOLDER, online = true, canPush = true, canBrowse = true, preparingFiles = 2).enabled)
        assertTrue(DevicePage.state(DeviceAction.SEND_TEXT, online = true, canPush = true, canBrowse = true, preparingFiles = 2).enabled)
    }

    @Test
    fun everythingEnabledWhenOnlineAndPermitted() {
        val states = DevicePage.states(online = true, canPush = true, canBrowse = true)
        assertTrue(states.all { it.enabled }, "all actions must be enabled: $states")
    }

    @Test
    fun theyCanRowsAreEditableAndInCanonicalOrder() {
        val p = PairedPeer(
            fingerprint = "aa".repeat(32), name = "Desktop", host = "h", port = 1,
            browse = Permission.ALLOW, push = Permission.ASK, text = Permission.NEVER, pairedAt = 1,
        )
        val rows = DevicePage.theyCan(p)
        assertEquals(
            listOf(PermissionAction.BROWSE, PermissionAction.PUSH, PermissionAction.TEXT),
            rows.map { it.action },
        )
        assertEquals(Permission.ALLOW, rows[0].value)
        assertEquals(Permission.ASK, rows[1].value)
        assertEquals(Permission.NEVER, rows[2].value)
        assertEquals("browse your shares", rows[0].label)
        assertEquals("send files to you", rows[1].label)
        assertEquals("send text to you", rows[2].label)
    }

    @Test
    fun theyAllowLinesAreReadOnlyAndFallBack() {
        val p = PairedPeer(
            fingerprint = "aa".repeat(32), name = "Desktop", host = "h", port = 1,
            browse = Permission.ASK, push = Permission.ASK, text = Permission.ASK, pairedAt = 1,
            allowBrowse = true, allowPush = false, allowText = true,
        )
        assertEquals(listOf("browse their shares", "send text to them"), DevicePage.theyAllow(p))
        assertEquals("browse their shares; send text to them", DevicePage.theyAllowText(p))
        val none = PairedPeer(
            fingerprint = "bb".repeat(32), name = "D", host = "h", port = 1,
            browse = Permission.ASK, push = Permission.ASK, text = Permission.ASK, pairedAt = 1,
            allowBrowse = false, allowPush = false, allowText = false,
        )
        assertEquals("nothing yet", DevicePage.theyAllowText(none))
    }

    @Test
    fun permissionWordsCoverAllThreeStates() {
        assertEquals("allow", DevicePage.permissionWord(Permission.ALLOW))
        assertEquals("ask", DevicePage.permissionWord(Permission.ASK))
        assertEquals("never", DevicePage.permissionWord(Permission.NEVER))
    }

    @Test
    fun statusTextIncludesStatusLastSeenAndAddress() {
        val online = DevicePage.statusText(online = true, host = "10.0.0.9", port = 47800, lastSeenMillis = 0)
        assertTrue(online.startsWith("Online"), online)
        assertTrue(online.contains("10.0.0.9:47800"), online)
        val offline = DevicePage.statusText(online = false, host = "10.0.0.9", port = 47800, lastSeenMillis = 0)
        assertTrue(offline.contains("Offline"), offline)
        assertTrue(offline.contains("not seen yet"), offline)
        val seen = DevicePage.statusText(online = false, host = "10.0.0.9", port = 47800, lastSeenMillis = 90_000, now = 100_000)
        assertTrue(seen.contains("last seen"), seen)
    }
}
