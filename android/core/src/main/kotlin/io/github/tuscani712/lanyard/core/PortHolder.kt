package io.github.tuscani712.lanyard.core

import java.io.File

/**
 * Best-effort name of the process listening on a TCP port, read from `/proc`.
 *
 * Used only to explain a busy configured port ("held by …"). Returns null when
 * the holder cannot be determined: not Linux, a locked-down `/proc`, or no
 * matching socket. Never throws.
 */
object PortHolder {
    fun find(port: Int): String? = runCatching { resolve(port) }.getOrNull()

    private fun resolve(port: Int): String? {
        val inode = listeningInode(port) ?: return null
        val pid = pidForInode(inode) ?: return null
        return processName(pid)
    }

    /** The inode of a LISTEN socket on [port] from `/proc/net/tcp{,6}`. */
    private fun listeningInode(port: Int): String? {
        for (fileName in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            val file = File(fileName)
            if (!file.isFile) continue
            for (line in file.readLines().drop(1)) {
                val parts = line.trim().split(WHITESPACE)
                if (parts.size < 10 || parts[3] != "0A") continue
                val hexPort = parts[1].substringAfterLast(':')
                if (hexPort.toIntOrNull(16) == port) return parts[9]
            }
        }
        return null
    }

    /** The pid owning the socket with [inode], scanned from `/proc/<pid>/fd`. */
    private fun pidForInode(inode: String): Int? {
        val target = "socket:[$inode]"
        val procs = File("/proc").listFiles() ?: return null
        for (proc in procs) {
            val pid = proc.name.toIntOrNull() ?: continue
            val fds = File(proc, "fd").listFiles() ?: continue
            for (fd in fds) {
                val link = runCatching {
                    java.nio.file.Files.readSymbolicLink(fd.toPath()).toString()
                }.getOrNull() ?: continue
                if (link == target) return pid
            }
        }
        return null
    }

    /** A short program name for [pid] from its cmdline, falling back to comm. */
    private fun processName(pid: Int): String? {
        val cmdline = runCatching { File("/proc/$pid/cmdline").readText() }.getOrNull()
        if (!cmdline.isNullOrEmpty()) {
            val first = cmdline.split('\u0000').firstOrNull { it.isNotBlank() }
            if (!first.isNullOrBlank()) return first.substringAfterLast('/')
        }
        return runCatching { File("/proc/$pid/comm").readText().trim() }.getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private val WHITESPACE = Regex("\\s+")
}
