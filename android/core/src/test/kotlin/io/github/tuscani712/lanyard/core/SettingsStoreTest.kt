package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class SettingsStoreTest {
    private fun store(): Pair<JsonFileSettingsStore, File> {
        val dir = Files.createTempDirectory("settings").toFile()
        val file = File(dir, "settings.json")
        return JsonFileSettingsStore(file) to file
    }

    @Test
    fun defaultsWhenMissing() {
        val (store, _) = store()
        assertEquals(AppSettings(), store.load())
        assertEquals(ThemeMode.System, store.load().theme)
        assertTrue(store.load().notifications)
        assertFalse(store.load().soundOnComplete)
        assertTrue(store.load().wifiOnly)
        assertNull(store.load().downloadFolder)
        assertEquals(0, store.load().bandwidthLimitMBps)
        assertEquals(0, store.load().preferredPort)
    }

    @Test
    fun roundTripsAcrossInstances() {
        val (store, file) = store()
        store.save(
            AppSettings(
                theme = ThemeMode.Dark,
                speedUnit = SpeedUnit.Mbps,
                notifications = false,
                soundOnComplete = true,
                wifiOnly = false,
                downloadFolder = "content://com.android.externalstorage.documents/tree/primary%3ADownload",
                bandwidthLimitMBps = 7,
                preferredPort = 4242,
            ),
        )

        val reopened = JsonFileSettingsStore(file).load()
        assertEquals(ThemeMode.Dark, reopened.theme)
        assertEquals(SpeedUnit.Mbps, reopened.speedUnit)
        assertFalse(reopened.notifications)
        assertTrue(reopened.soundOnComplete)
        assertFalse(reopened.wifiOnly)
        assertEquals("content://com.android.externalstorage.documents/tree/primary%3ADownload", reopened.downloadFolder)
        assertEquals(7, reopened.bandwidthLimitMBps)
        assertEquals(4242, reopened.preferredPort)
    }

    @Test
    fun corruptFileReadsDefaults() {
        val (store, file) = store()
        file.writeText("{ this is not json")
        assertEquals(AppSettings(), store.load())
    }

    @Test
    fun partialFileFillsMissingFieldsWithDefaults() {
        val (store, file) = store()
        file.writeText("""{"theme":"Light"}""")
        val loaded = store.load()
        assertEquals(ThemeMode.Light, loaded.theme)
        assertEquals(SpeedUnit.MBps, loaded.speedUnit)
        assertTrue(loaded.notifications)
        assertFalse(loaded.soundOnComplete)
        assertTrue(loaded.wifiOnly)
        assertNull(loaded.downloadFolder)
        assertEquals(0, loaded.bandwidthLimitMBps)
    }

    @Test
    fun writesAtomicallyLeavingNoTempFile() {
        val (store, file) = store()
        store.save(AppSettings(theme = ThemeMode.Light))
        assertTrue(file.isFile)
        val leftovers = file.parentFile.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }
        assertTrue(leftovers.isEmpty(), "temp file(s) left behind: $leftovers")
    }

    @Test
    fun formatsSpeedPerUnit() {
        assertEquals("1.0 MB/s", formatSpeed(1_048_576.0, SpeedUnit.MBps))
        assertEquals("8.0 Mbps", formatSpeed(1_000_000.0, SpeedUnit.Mbps))
        assertEquals("0.0 MB/s", formatSpeed(0.0, SpeedUnit.MBps))
    }
}
