/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.downloads

import java.io.File

/**
 * Keeps Media3's cache bookkeeping out of the downloads folder.
 *
 * The downloads folder holds songs: one real file per download, named for the track and
 * carrying the extension of the container the source served. Media3's cache belongs in the
 * cache folder, and it is recognisable because its names are entirely mechanical:
 *
 *  - `<hex>.uid` - the cache's identity file,
 *  - `<uid>.<position>.<timestamp>.v<n>.exo` - one 5 MiB span of cached bytes.
 *
 * A download cache used to be created in the downloads folder, so folders that had been in
 * use for a while were littered with those files (and with the `0`-`9` shard directories
 * they live in). Nothing reads them any more, and a user looking at their downloads should
 * see their music rather than cache bookkeeping - so they are swept out once.
 *
 * The patterns are deliberately strict. A name only matches if it is *entirely* mechanical,
 * so a track called `Something.uid` or a file `123.456.exo` that a user put there by hand is
 * left exactly where it is.
 */
object DownloadFolderHygiene {
    /** `<hex>.uid` - e.g. `5bccea7197bafb27.uid`. */
    private val UID_FILE = Regex("^[0-9a-f]{8,32}\\.uid$", RegexOption.IGNORE_CASE)

    /**
     * `<uid>.<position>[.<length>][.v<n>].exo` - e.g. `15.10485760.1789300859647.v3.exo`.
     *
     * Current Media3 writes uid, start position, timestamp and version; older layouts used a
     * length instead of a timestamp, so one or two middle groups are both accepted.
     */
    private val SPAN_FILE = Regex("^\\d+(\\.\\d+){1,3}(\\.v\\d+)?\\.exo$", RegexOption.IGNORE_CASE)

    /** The `0`-`9` directories Media3 spreads cache files across. */
    private val SHARD_DIRECTORY = Regex("^\\d$")

    /** Whether [fileName] is Media3 cache bookkeeping rather than a song. */
    fun isMedia3CacheArtifact(fileName: String): Boolean =
        UID_FILE.matches(fileName) || SPAN_FILE.matches(fileName)

    /**
     * Deletes cache bookkeeping from [folder], returning how many files were removed.
     *
     * Only files whose whole name matches a cache pattern are touched, and a shard directory
     * is only removed once it is empty - so a folder holding real downloads keeps them all.
     */
    fun purge(folder: File): Int {
        if (!folder.isDirectory) return 0
        var removed = 0
        folder.listFiles()?.forEach { entry ->
            when {
                entry.isFile && isMedia3CacheArtifact(entry.name) -> {
                    if (entry.delete()) removed++
                }

                entry.isDirectory && SHARD_DIRECTORY.matches(entry.name) -> {
                    entry.listFiles()?.forEach { inner ->
                        if (inner.isFile && isMedia3CacheArtifact(inner.name) && inner.delete()) {
                            removed++
                        }
                    }
                    if (entry.listFiles()?.isEmpty() != false && entry.delete()) {
                        // Directory itself gone; the count above already reflected the files.
                    }
                }
            }
        }
        return removed
    }
}
