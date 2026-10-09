package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ServerDiagnosticsLogTest {
    private fun writer(dir: Path) = RotatingWriter(dir.resolve("lanyard.log").toFile())

    @Test
    fun persistedLinesUseTheSameRedactionAsTheInMemoryRing(@TempDir dir: Path) {
        val d = ServerDiagnostics(clock = { 0 })
        d.file = writer(dir)
        val fullFp = "ab12cd34".repeat(8)
        val token = "deadbeefdeadbeefdeadbeefdeadbeef"

        d.record("secret fingerprint $fullFp token ?t=$token path /storage/emulated/0/Download/photo.jpg")

        val onDisk = d.persistedLog()
        assertFalse(onDisk.contains(fullFp))
        assertFalse(onDisk.contains(token))
        assertFalse(onDisk.contains("/storage/emulated"))
        assertFalse(onDisk.contains("photo.jpg"))
        // The short fingerprint survives, exactly as the ring's copy does.
        assertTrue(onDisk.contains("ab12cd34"))
        assertEquals(Redaction.redact(d.snapshot().first()), onDisk.trimEnd())
    }

    @Test
    fun appStartWritesADividerSoRestartsAreVisible(@TempDir dir: Path) {
        val first = ServerDiagnostics(clock = { 1_000L })
        first.file = writer(dir)
        first.markAppStart(1_000L)
        first.record("first-run event")

        // A second object on the same file stands in for a fresh process.
        val second = ServerDiagnostics(clock = { 2_000L })
        second.file = writer(dir)
        second.markAppStart(2_000L)
        second.record("second-run event")

        val log = second.persistedLog()
        assertTrue(log.contains("===== app start "))
        assertEquals(2, Regex("===== app start ").findAll(log).count())
        assertTrue(log.indexOf("first-run event") < log.indexOf("second-run event"))
    }

    @Test
    fun appStartDividerHasAStableFormat() {
        assertEquals(
            "===== app start 1970-01-01T00:00:00Z =====",
            Diagnostics.appStartDivider(0L),
        )
    }

    @Test
    fun copyLogIncludesThePreviousRunFileAndTheDivider(@TempDir dir: Path) {
        val first = ServerDiagnostics(clock = { 1_000L })
        first.file = writer(dir)
        first.markAppStart(1_000L)
        first.record("previous-run event")

        val second = ServerDiagnostics(clock = { 2_000L })
        second.file = writer(dir)
        second.markAppStart(2_000L)
        second.record("current-run event")

        val out = Diagnostics.copyLog(second.snapshot(), second.persistedLog())
        assertTrue(out.contains("previous-run event"))
        assertTrue(out.contains("current-run event"))
        assertTrue(out.contains("===== app start "))
        assertTrue(out.indexOf("previous-run event") < out.indexOf("current-run event"))
    }

    @Test
    fun copyLogFallsBackToTheRingWhenNoFileWasWritten() {
        val out = Diagnostics.copyLog(listOf("00:00:01.000 conn open"))
        assertTrue(out.contains("conn open"))
    }

    @Test
    fun debugEntryReachesTheRingNotTheDurableLog(@TempDir dir: Path) {
        val d = ServerDiagnostics(clock = { 0 })
        d.file = writer(dir)

        d.record("[discovery] peer=ab12cd34 hello result=online", DiagLevel.Debug)
        d.record("[discovery] peer=ab12cd34 hello result=offline", DiagLevel.Info)

        // Routine chatter stays in the ring so the copied report stays complete.
        assertTrue(d.snapshot().any { it.contains("result=online") })
        val onDisk = d.persistedLog()
        assertFalse(onDisk.contains("result=online"), "a routine success must not fill the durable log")
        // A normal offline event stays visible, so a real problem can be seen.
        assertTrue(onDisk.contains("result=offline"))
    }

    @Test
    fun debugEntryReachesTheCallbackAtItsLevel() {
        val d = ServerDiagnostics()
        val seen = mutableListOf<Pair<DiagLevel, String>>()
        d.onRecord = { level, line -> seen.add(level to line) }

        d.record("routine", DiagLevel.Debug)
        d.record("normal")
        d.record("problem", DiagLevel.Warn)
        d.record("broken", DiagLevel.Error)

        assertEquals(
            listOf(DiagLevel.Debug, DiagLevel.Info, DiagLevel.Warn, DiagLevel.Error),
            seen.map { it.first },
        )
    }
}
