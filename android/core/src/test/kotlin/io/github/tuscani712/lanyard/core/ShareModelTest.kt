package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShareModelTest {
    private fun peer(fp: String, push: Boolean = true) = PairedPeer(
        fingerprint = fp, name = "peer", host = "10.0.0.2", port = 47800,
        browse = true, push = push, pairedAt = 0L,
    )

    @Test
    fun acceptsContentUrisWithAProvider() {
        assertTrue(ShareValidation.validateUri("content", "com.android.providers.media.documents", true).accepted)
    }

    @Test
    fun rejectsContentUrisWithoutAProvider() {
        assertFalse(ShareValidation.validateUri("content", null, true).accepted)
        assertFalse(ShareValidation.validateUri("content", "", true).accepted)
    }

    @Test
    fun rejectsContentUrisThatCannotBeOpened() {
        assertFalse(ShareValidation.validateUri("content", "com.example.provider", false).accepted)
    }

    @Test
    fun rejectsEveryFileUri() {
        // `file:` shares are hostile by default: even a path that resolves into
        // our own storage via the /data/data alias must be refused. The scheme is
        // the only input, so this covers every file: URI.
        assertFalse(ShareValidation.validateUri("file", null, true).accepted)
        assertFalse(ShareValidation.validateUri("file", "anything", true).accepted)
        assertFalse(ShareValidation.validateUri("FILE", null, true).accepted)
    }

    @Test
    fun refusesUnknownSchemes() {
        assertFalse(ShareValidation.validateUri("http", "example.com", true).accepted)
        assertFalse(ShareValidation.validateUri("javascript", null, true).accepted)
        assertFalse(ShareValidation.validateUri(null, null, true).accepted)
    }

    @Test
    fun capsItemCountAt100() {
        assertEquals(3, ShareValidation.capItemCount(3))
        assertEquals(100, ShareValidation.capItemCount(100))
        assertEquals(100, ShareValidation.capItemCount(101))
        assertEquals(100, ShareValidation.capItemCount(5000))
    }

    @Test
    fun snippetCapBoundaryIsExact() {
        val exact = "a".repeat(64 * 1024)
        assertFalse(ShareValidation.textExceedsSnippet(exact))
        assertTrue(ShareValidation.textExceedsSnippet(exact + "a"))
        // The cap is measured in bytes, not characters.
        assertTrue(ShareValidation.textExceedsSnippet("é".repeat(64 * 1024)))
    }

    @Test
    fun safeNameKeepsTheLastPathSegment() {
        assertEquals("report.pdf", ShareValidation.safeShareName("report.pdf"))
        assertEquals("report.pdf", ShareValidation.safeShareName("/path/to/report.pdf"))
    }

    @Test
    fun safeNameRejectsDangerousNames() {
        assertEquals("shared-file", ShareValidation.safeShareName(null))
        assertEquals("shared-file", ShareValidation.safeShareName(""))
        assertEquals("shared-file", ShareValidation.safeShareName("."))
        assertEquals("shared-file", ShareValidation.safeShareName(".."))
        assertEquals("shared-file", ShareValidation.safeShareName("a\\b.txt"))
        assertEquals("shared-file", ShareValidation.safeShareName("a:b.txt"))
        assertEquals("shared-file", ShareValidation.safeShareName("a\u0000b.txt"))
    }

    @Test
    fun staleSpoolFilesUsesTheTtl() {
        val now = 10_000_000L
        val ttl = ShareValidation.SPOOL_TTL_MILLIS
        val entries = listOf(
            SpoolEntry("fresh.tmp", now - 1000),
            SpoolEntry("at-edge.tmp", now - ttl),
            SpoolEntry("stale.tmp", now - ttl - 1),
            SpoolEntry("ancient.tmp", 0L),
        )
        assertEquals(listOf("stale.tmp", "ancient.tmp"), ShareValidation.staleSpoolFiles(entries, now, ttl))
    }

    @Test
    fun pickerDisablesPeersWithoutPushPermission() {
        val rows = ShareValidation.shareTargets(listOf(peer("aa".repeat(32), push = false)), setOf("aa".repeat(32)))
        assertEquals(1, rows.size)
        assertFalse(rows[0].enabled)
        assertEquals("Has not allowed files from you", rows[0].reason)
    }

    @Test
    fun pickerDisablesOfflinePeers() {
        val rows = ShareValidation.shareTargets(listOf(peer("bb".repeat(32))), emptySet())
        assertFalse(rows[0].enabled)
        assertEquals("Offline", rows[0].reason)
    }

    @Test
    fun pickerEnablesOnlinePermittedPeers() {
        val rows = ShareValidation.shareTargets(listOf(peer("CC".repeat(32))), setOf("cc".repeat(32)))
        assertTrue(rows[0].enabled)
        assertNull(rows[0].reason)
    }

    @Test
    fun pushPermissionIsCheckedBeforeOnline() {
        val rows = ShareValidation.shareTargets(listOf(peer("dd".repeat(32), push = false)), emptySet())
        assertEquals("Has not allowed files from you", rows[0].reason)
    }
}
