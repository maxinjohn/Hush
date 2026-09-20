/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import app.hush.music.downloads.DownloadFolderHygiene
import app.hush.music.storage.StorageFolderKind
import app.hush.music.storage.StorageLocationRepository
import timber.log.Timber
import java.io.File

/**
 * Sweeps the Media3 cache bookkeeping an older layout left in the song-cache folder.
 *
 * Media3's streaming cache used to be created *on* the song-cache folder, which is the folder
 * that also holds Hush's SpotiFLAC playback files and the download cache. It now has a
 * `player-cache` subdirectory of its own (see
 * [StorageLocationRepository.playerCacheDirectory]), so the spans and shard directories in the
 * root are nobody's: they are Media3's bytes with no cache left to claim them, and they count
 * against the storage the user is told they are using.
 *
 * The patterns are the strict ones [DownloadFolderHygiene] already uses - a file only matches when
 * its whole name is mechanical (`<hex>.uid`, `<uid>.<position>.<timestamp>.v<n>.exo`) and a shard
 * directory only when it is a single digit - so nothing a user put here is at risk. Runs once, when
 * the new player cache directory is created, which is what makes it a migration rather than a
 * recurring sweep: afterwards the root holds no Media3 files to find.
 */
object PlayerCacheLayoutMigration {
    /** The `0`-`9` directories Media3 spreads cached spans across. */
    private val SHARD_DIRECTORY = Regex("^\\d$")

    /**
     * Deletes leftover player-cache files from the song-cache root, returning how many went.
     *
     * Does nothing once [playerCacheDirectory] exists, so a folder already on the new layout is
     * never touched.
     */
    fun sweepLegacyRoot(
        songCacheRoot: File,
        playerCacheDirectory: File,
    ): Int {
        if (playerCacheDirectory.isDirectory) return 0
        var removed = 0
        songCacheRoot.listFiles()?.forEach { entry ->
            when {
                entry.isFile && DownloadFolderHygiene.isMedia3CacheArtifact(entry.name) -> {
                    if (entry.delete()) removed++
                }

                entry.isDirectory && SHARD_DIRECTORY.matches(entry.name) -> {
                    entry.listFiles()?.forEach inner@{ inner ->
                        if (inner.isFile && DownloadFolderHygiene.isMedia3CacheArtifact(inner.name) && inner.delete()) {
                            removed++
                        }
                    }
                    // Only removed once it is empty, so a shard this app is still writing into
                    // keeps whatever it holds.
                    entry.listFiles()?.isEmpty()?.let { empty -> if (empty) entry.delete() }
                }
            }
        }
        if (removed > 0) {
            Timber.tag("PlayerCache").i("swept %d legacy cache file(s) from the song cache root", removed)
        }
        return removed
    }

    /** Convenience for the provider: the sweep for the device's configured folders. */
    fun sweepLegacyRoot(context: android.content.Context): Int =
        runCatching {
            sweepLegacyRoot(
                songCacheRoot = StorageLocationRepository.cacheDirectory(
                    context,
                    StorageFolderKind.SONG_CACHE,
                ),
                playerCacheDirectory = StorageLocationRepository.playerCacheDirectory(context),
            )
        }.getOrDefault(0)
}
