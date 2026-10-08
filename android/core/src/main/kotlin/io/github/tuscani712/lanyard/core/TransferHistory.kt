package io.github.tuscani712.lanyard.core

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persists the Transfers list so a finished row survives an app restart. Mirrors
 * the [SettingsStore]/[TrustStore] JSON pattern: a single file, written through
 * a sibling temp file and atomically renamed, so a crash mid-write can never
 * corrupt the history.
 */
interface TransferHistoryStore {
    /** The stored rows, newest first, capped. Never throws; a bad file reads empty. */
    fun load(): List<TransferRecord>

    /** Replaces the stored rows, keeping only the newest [TransferBoard.HISTORY_CAP]. */
    fun save(records: List<TransferRecord>)
}

/**
 * A [TransferHistoryStore] backed by one JSON file. The list is stored newest
 * first, so the cap always drops the oldest rows and keeps the newest ~100.
 */
class JsonFileTransferHistoryStore(
    private val file: File,
    private val cap: Int = TransferBoard.HISTORY_CAP,
) : TransferHistoryStore {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val type = object : TypeToken<MutableList<TransferRecord>>() {}.type

    @Synchronized
    override fun load(): List<TransferRecord> {
        if (!file.isFile) return emptyList()
        return try {
            val rows = gson.fromJson<MutableList<TransferRecord>>(file.readText(), type) ?: return emptyList()
            // A file written by a bigger cap (or hand-edited) is trimmed on read
            // too, so the in-memory list can never exceed the cap.
            rows.take(cap)
        } catch (_: JsonSyntaxException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    override fun save(records: List<TransferRecord>) {
        val dir = file.absoluteFile.parentFile
        dir?.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(gson.toJson(records.take(cap), type))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
