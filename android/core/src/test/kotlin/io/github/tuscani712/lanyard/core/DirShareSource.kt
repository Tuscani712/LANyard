package io.github.tuscani712.lanyard.core

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * A filesystem-backed [ShareSource] for tests: one folder share over [root],
 * with seekable file reads. Mirrors how the app's SAF source behaves.
 */
class DirShareSource(private val id: String, private val root: File) : ShareSource {
    private val stopped = HashSet<String>()

    fun stop() { stopped.add(id) }

    private fun resolveDir(rel: String): File? {
        if (!SafePath.validRel(rel)) return null
        var f = root
        if (rel.isNotEmpty()) {
            for (seg in rel.split('/')) {
                f = File(f, seg)
                if (!f.isDirectory) return null
            }
        }
        return f
    }

    override fun list(): List<ShareInfo> = listOf(ShareInfo(id, "folder", "", "folder", 0))

    override fun ended(shareId: String): String? =
        if (shareId == id && stopped.contains(id)) "stopped" else null

    override fun children(shareId: String, rel: String): List<ShareChild>? {
        if (shareId != id) return null
        val dir = resolveDir(rel) ?: return null
        return dir.listFiles()?.filter { SafePath.validSegment(it.name) }?.map {
            ShareChild(
                name = it.name,
                path = SafePath.childPath(rel, it.name),
                isDir = it.isDirectory,
                size = if (it.isDirectory) 0 else it.length(),
                mtimeMillis = it.lastModified(),
            )
        } ?: emptyList()
    }

    override fun resolve(shareId: String, rel: String): ResolvedFile? {
        if (shareId != id || rel.isEmpty() || !SafePath.validRel(rel)) return null
        val dir = resolveDir(rel.substringBeforeLast('/', "")) ?: return null
        val f = File(dir, rel.substringAfterLast('/'))
        if (!f.isFile) return null
        return ResolvedFile(f.name, f.length(), f.lastModified()) { offset ->
            object : InputStream() {
                private val raf = RandomAccessFile(f, "r").apply { seek(offset) }
                override fun read(): Int = raf.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = raf.read(b, off, len)
                override fun close() = raf.close()
            }
        }
    }
}
