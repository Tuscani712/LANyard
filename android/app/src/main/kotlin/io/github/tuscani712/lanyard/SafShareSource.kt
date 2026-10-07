package io.github.tuscani712.lanyard

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import io.github.tuscani712.lanyard.core.ResolvedFile
import io.github.tuscani712.lanyard.core.SafePath
import io.github.tuscani712.lanyard.core.ShareChild
import io.github.tuscani712.lanyard.core.ShareInfo
import io.github.tuscani712.lanyard.core.ShareSource
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Shares the user's picked SAF folders. Children are listed with **one
 * `ContentResolver.query` per directory** (`buildChildDocumentsUriUsingTree`),
 * not one per child, and every path is resolved segment by segment downward
 * from the picked tree root, so nothing outside it can ever be reached.
 */
class SafShareSource(
    context: Context,
    private val store: ShareStore,
) : ShareSource {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val stopped = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun addFolder(label: String, uri: Uri) = store.add(label, uri)

    fun stop(id: String) {
        stopped.add(id)
        store.remove(id)
    }

    private fun share(shareId: String): AppShare? = store.list().firstOrNull { it.id == shareId }

    override fun list(): List<ShareInfo> =
        store.list()
            .filter { permissionHeld(Uri.parse(it.uri)) }
            .map { ShareInfo(it.id, it.label, "", "folder", 0) }

    override fun ended(shareId: String): String? = if (stopped.contains(shareId)) "stopped" else null

    override fun children(shareId: String, rel: String): List<ShareChild>? {
        val s = share(shareId) ?: return null
        if (!SafePath.validRel(rel)) return null
        val tree = Uri.parse(s.uri)
        val dirId = resolveDirId(tree, rel) ?: return null
        return listChildren(tree, dirId, rel)
    }

    override fun resolve(shareId: String, rel: String): ResolvedFile? {
        if (rel.isEmpty() || !SafePath.validRel(rel)) return null
        val s = share(shareId) ?: return null
        val tree = Uri.parse(s.uri)
        val parentId = resolveDirId(tree, rel.substringBeforeLast('/', "")) ?: return null
        val name = rel.substringAfterLast('/')
        val rows = queryChildren(tree, parentId) ?: return null
        val row = rows.firstOrNull { it.name == name && !it.isDir } ?: return null
        val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, row.docId)
        return ResolvedFile(row.name, row.size, row.mtime) { offset -> openAt(docUri, offset) }
    }

    private fun permissionHeld(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    /** Resolves a relative directory path to a document id, one query per level. */
    private fun resolveDirId(tree: Uri, rel: String): String? {
        var id = DocumentsContract.getTreeDocumentId(tree)
        if (rel.isEmpty()) return id
        for (seg in rel.split('/')) {
            val kids = queryChildren(tree, id) ?: return null
            val next = kids.firstOrNull { it.name == seg && it.isDir } ?: return null
            id = next.docId
        }
        return id
    }

    private class Row(val name: String, val docId: String, val isDir: Boolean, val size: Long, val mtime: Long)

    private fun queryChildren(tree: Uri, parentDocId: String): List<Row>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val out = ArrayList<Row>()
        val cursor = try {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null, null, null,
            )
        } catch (_: Exception) {
            return null
        } ?: return null
        cursor.use { c: Cursor ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val docId = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                val mtime = if (c.isNull(4)) 0L else c.getLong(4)
                out.add(Row(name, docId, isDir, size, mtime))
            }
        }
        return out
    }

    private fun listChildren(tree: Uri, dirId: String, rel: String): List<ShareChild>? {
        val rows = queryChildren(tree, dirId) ?: return null
        val out = ArrayList<ShareChild>()
        for (r in rows) {
            if (!SafePath.validSegment(r.name)) continue // never serve a hostile name
            out.add(ShareChild(r.name, SafePath.childPath(rel, r.name), r.isDir, r.size, r.mtime))
        }
        return out
    }

    /**
     * Opens a document at [offset]. Providers that expose a seekable file
     * descriptor are positioned directly; otherwise the stream is skipped
     * forward (slower, but correct).
     */
    private fun openAt(uri: Uri, offset: Long): InputStream {
        if (offset > 0) {
            try {
                val pfd = resolver.openFileDescriptor(uri, "r") ?: throw IOException("cannot open the file")
                val fis = FileInputStream(pfd.fileDescriptor)
                return try {
                    fis.channel.position(offset)
                    object : FilterInputStream(fis) {
                        override fun close() {
                            super.close()
                            pfd.close()
                        }
                    }
                } catch (_: Exception) {
                    fis.close()
                    pfd.close()
                    throw IOException("not seekable")
                }
            } catch (_: Exception) {
                // fall through to skipping
            }
        }
        val base = resolver.openInputStream(uri) ?: throw IOException("cannot open the file")
        if (offset <= 0) return base
        var left = offset
        val buf = ByteArray(256 * 1024)
        while (left > 0) {
            val n = base.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            left -= n
        }
        return base
    }
}
