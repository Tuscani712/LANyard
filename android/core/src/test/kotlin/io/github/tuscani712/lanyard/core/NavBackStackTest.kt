package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The pure navigation semantics the app's Back handling is built on: a pop never
 * removes the root, so at the top screen [NavBackStack.back] returns null and the
 * caller lets the system exit. These mirror the manual checklist's sub-screen
 * cases without needing an emulator.
 */
class NavBackStackTest {

    @Test
    fun freshStackSitsAtRootAndCannotGoBack() {
        val nav = NavBackStack("root")
        assertEquals("root", nav.current)
        assertEquals(1, nav.depth)
        assertFalse(nav.canGoBack())
    }

    @Test
    fun backAtTheTopReturnsNullAndKeepsTheRoot() {
        val nav = NavBackStack("root")
        assertNull(nav.back(), "Back at the top screen must fall through to the OS, not be swallowed")
        assertEquals("root", nav.current)
        assertEquals(1, nav.depth)
    }

    @Test
    fun pushThenBackReturnsToThePreviousScreen() {
        val nav = NavBackStack("root")
        nav.push("detail")
        assertEquals("detail", nav.current)
        assertTrue(nav.canGoBack())
        assertEquals("root", nav.back())
        assertEquals("root", nav.current)
        assertFalse(nav.canGoBack())
    }

    @Test
    fun nestedScreensWalkBackOneLevelAtATime() {
        val nav = NavBackStack("root")
        nav.push("share")
        nav.push("share/folder")
        assertEquals("share/folder", nav.current)
        assertEquals("share", nav.back())
        assertEquals("root", nav.back())
        assertNull(nav.back(), "the top screen must not be popped")
    }

    @Test
    fun resetDiscardsEverythingAboveTheRoot() {
        val nav = NavBackStack("root")
        nav.push("a")
        nav.push("b")
        assertEquals("root", nav.reset())
        assertEquals(1, nav.depth)
        assertFalse(nav.canGoBack())
    }

    @Test
    fun toListIsRootFirstAndImmutable() {
        val nav = NavBackStack("root")
        nav.push("a")
        nav.push("b")
        val snapshot = nav.toList()
        assertEquals(listOf("root", "a", "b"), snapshot)
        nav.push("c")
        assertEquals(listOf("root", "a", "b"), snapshot, "the snapshot must not see later pushes")
    }
}
