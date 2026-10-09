package io.github.tuscani712.lanyard

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/** One folder the user is sharing with paired devices. */
data class AppShare(
    val id: String,
    val label: String,
    val uri: String,
    val createdAt: Long,
)

/** Persists the user's shares (folder SAF tree URIs) in the app's files dir. */
class ShareStore(context: Context) {
    private val file = File(context.filesDir, "shares.json")
    private val gson = Gson()
    private val type = object : TypeToken<MutableList<AppShare>>() {}.type

    @Synchronized
    fun list(): List<AppShare> {
        if (!file.isFile) return emptyList()
        return try {
            gson.fromJson<MutableList<AppShare>>(file.readText(), type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    fun add(label: String, uri: Uri) {
        val shares = list().toMutableList()
        shares.removeAll { it.uri == uri.toString() }
        shares.add(AppShare("s_" + System.currentTimeMillis().toString(16), label, uri.toString(), System.currentTimeMillis()))
        save(shares)
    }

    @Synchronized
    fun remove(id: String) {
        save(list().filterNot { it.id == id }.toMutableList())
    }

    private fun save(shares: MutableList<AppShare>) {
        file.writeText(gson.toJson(shares, type))
    }
}
