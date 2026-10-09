package io.github.tuscani712.lanyard.core

import java.io.File

/**
 * A [PushDestination] that picks the real destination for each call. It must
 * forward [folder] and [folderLocation] as well as [place]: a bare lambda
 * implementing only `place` silently reports an empty folder, so a finished
 * receive would never learn where its files landed (no "Open folder").
 */
class SwitchingDestination(private val pick: () -> PushDestination) : PushDestination {
    override fun place(relPath: String, spool: File, size: Long): String = pick().place(relPath, spool, size)

    override fun folder(): String = pick().folder()

    override fun folderLocation(): String = pick().folderLocation()
}
