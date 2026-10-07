package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Pure receive-path rules: name sanitization and the free-space rule. */
class PushProtocolTest {
    @Test
    fun keepsSimpleAndNestedNames() {
        assertEquals("a.txt", PushProtocol.sanitizeRel("a.txt"))
        assertEquals("dir/sub/a.txt", PushProtocol.sanitizeRel("dir/sub/a.txt"))
        assertEquals("dir/a.txt", PushProtocol.sanitizeRel("dir//a.txt"))
    }

    @Test
    fun stripsControlAndTrailingDotsAndSpaces() {
        assertEquals("ab.txt", PushProtocol.sanitizeRel("a\u0001b.txt"))
        assertEquals("name", PushProtocol.sanitizeRel("name. . ."))
        assertEquals("_", PushProtocol.sanitizeRel("   "))
    }

    @Test
    fun rejectsTraversalAndUnsafeNames() {
        assertNull(PushProtocol.sanitizeRel(""))
        assertNull(PushProtocol.sanitizeRel("/etc/passwd"))
        assertNull(PushProtocol.sanitizeRel(".."))
        assertNull(PushProtocol.sanitizeRel("a/../b"))
        assertNull(PushProtocol.sanitizeRel("a/./b"))
        assertNull(PushProtocol.sanitizeRel("a\\b"))
        assertNull(PushProtocol.sanitizeRel("a\u0000b"))
        assertNull(PushProtocol.sanitizeRel("c:stream"))
        assertNull(PushProtocol.sanitizeRel("x/" + "a".repeat(256)))
    }

    @Test
    fun freeSpaceCountsBothCopies() {
        assertEquals(100L + 10L + PushProtocol.FREE_SPACE_MARGIN, PushProtocol.requiredFreeSpace(100, 10))
    }

    @Test
    fun safeManifestPath() {
        assertEquals(true, PushProtocol.isSafeRelPath(""))
        assertEquals(true, PushProtocol.isSafeRelPath("a/b.txt"))
        assertEquals(false, PushProtocol.isSafeRelPath("../x"))
        assertEquals(false, PushProtocol.isSafeRelPath("/x"))
    }
}
