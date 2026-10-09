package io.github.tuscani712.lanyard.core

/**
 * The set of names known to exist in one destination folder, plus collision-free
 * allocation against it.
 *
 * A receive places many files into one growing folder. Asking the platform
 * (`DocumentFile.findFile`, or a MediaStore query) for every candidate name is a
 * full folder scan each time, so N files cost O(N²). [FolderNames] is seeded
 * once with a single listing, then every allocation is an in-memory lookup that
 * also reserves the chosen name, so two files placed in the same run can never
 * pick the same name even before the platform listing would reflect them.
 *
 * Matching is case-insensitive, because both SAF (some providers) and MediaStore
 * treat `Photo.JPG` and `photo.jpg` as the same file on their underlying
 * filesystem.
 */
class FolderNames(existing: Collection<String> = emptyList()) {
    private val taken = HashSet<String>(existing.size * 2)
    private val lock = Any()

    init {
        existing.forEach { if (it.isNotEmpty()) taken.add(normalize(it)) }
    }

    /** True when [name] (case-insensitive) is already known to be present. */
    fun contains(name: String): Boolean = synchronized(lock) { normalize(name) in taken }

    /**
     * Returns [desired] when it is free, else `base (n)ext` for the smallest
     * positive `n` that is free. The returned name is reserved, so a second
     * allocation cannot return it again.
     */
    fun allocate(desired: String): String = synchronized(lock) { pick(desired) }

    private fun pick(desired: String): String {
        if (taken.add(normalize(desired))) return desired
        val dot = desired.lastIndexOf('.')
        val base = if (dot > 0) desired.substring(0, dot) else desired
        val ext = if (dot > 0) desired.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = "$base ($n)$ext"
            if (taken.add(normalize(candidate))) return candidate
            n++
        }
    }

    private fun normalize(name: String): String = name.lowercase()
}
