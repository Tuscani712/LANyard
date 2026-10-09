package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The tri-state permission, its store migration and its wire encoding.
 *
 * Store migration and wire fallback are deliberately different:
 *  - store: an old `false` becomes Ask (never silently Never);
 *  - wire:  an old `false` is a plain denial (Never), since an old peer cannot
 *           ask and must not be left waiting for a prompt.
 */
class PermissionTest {
    @Test
    fun wireStringRoundTripsCaseInsetsensitively() {
        assertEquals(Permission.ALLOW, Permission.fromString("allow"))
        assertEquals(Permission.ALLOW, Permission.fromString("ALLOW"))
        assertEquals(Permission.ASK, Permission.fromString(" ask "))
        assertEquals(Permission.NEVER, Permission.fromString("Never"))
        assertNull(Permission.fromString("sometimes"))
        assertNull(Permission.fromString(null))
    }

    @Test
    fun storeMigrationMapsFalseToAskNotNever() {
        assertEquals(Permission.ALLOW, Permission.fromStoredBool(true))
        assertEquals(Permission.ASK, Permission.fromStoredBool(false))
        assertEquals(Permission.ASK, Permission.fromStoredBool(null))
    }

    @Test
    fun wireFallbackMapsFalseToNever() {
        assertEquals(Permission.ALLOW, Permission.fromWireBool(true))
        assertEquals(Permission.NEVER, Permission.fromWireBool(false))
        assertEquals(Permission.NEVER, Permission.fromWireBool(null))
    }

    @Test
    fun wireBoolIsAllowElseDeny() {
        assertEquals(true, Permission.wireBool(Permission.ALLOW))
        assertEquals(false, Permission.wireBool(Permission.ASK))
        assertEquals(false, Permission.wireBool(Permission.NEVER))
    }

    @Test
    fun permitsAndAsks() {
        assertTrue(Permission.ALLOW.permits)
        assertTrue(Permission.ASK.permits)
        assertFalse(Permission.NEVER.permits)
        assertTrue(Permission.ASK.asks)
        assertFalse(Permission.ALLOW.asks)
        assertFalse(Permission.NEVER.asks)
    }

    @Test
    fun capabilityTokenIsStable() {
        assertEquals("perm3", Permission.CAPABILITY)
    }

    @Test
    fun newPeerPermissionsCarryModesOldPeerDoesNot() {
        val tri = Permissions(browse = true, push = false, pushMode = Permission.ASK, textMode = Permission.ASK)
        val json = tri.toJson(tristate = true)
        assertEquals("ask", json.get("push_mode").asString)
        assertEquals("ask", json.get("text_mode").asString)
        // The booleans are always present as the fallback.
        assertEquals(true, json.get("browse").asBoolean)

        val old = tri.toJson(tristate = false)
        assertFalse(old.has("push_mode"), "an old peer must never receive a mode key")
        assertEquals(true, old.get("browse").asBoolean)
    }
}
