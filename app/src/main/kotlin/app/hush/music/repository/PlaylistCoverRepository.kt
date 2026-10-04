/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.repository

import android.os.SystemClock
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import app.hush.music.db.MusicDatabase
import app.hush.music.innertube.YouTube
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistCoverRepository
    @Inject
    constructor(
        private val database: MusicDatabase,
    ) {
        private val refreshLocks = Array(8) { Mutex() }
        private val refreshedCovers = LruCache<String, RefreshedCover>(64)

        /**
         * Fetches the playlist's current cover from YouTube and persists it, replacing
         * [thumbnailUrl] — the expired URL that the image loader just failed on. Guards
         * concurrent loads of the same artwork with striped locks and caches both success
         * and failure for a minute so a screen full of dead thumbnails refreshes once.
         */
        suspend fun refreshRemoteCover(
            playlistId: String,
            thumbnailUrl: String,
        ): String = withContext(Dispatchers.IO) {
            val lock = refreshLocks[(thumbnailUrl.hashCode() and Int.MAX_VALUE) % refreshLocks.size]
            lock.withLock {
                val cached = refreshedCovers.get(thumbnailUrl)
                if (cached != null && SystemClock.elapsedRealtime() - cached.updatedAt < COVER_REFRESH_CACHE_MILLIS) {
                    return@withLock cached.result.getOrThrow()
                }

                val result: Result<String> =
                    try {
                        val refreshedUrl =
                            YouTube.playlist(playlistId).getOrThrow().playlist.thumbnail
                                ?.takeIf(String::isNotBlank)
                                ?: throw IllegalStateException("Playlist returned no cover artwork")
                        database.refreshPlaylistThumbnail(
                            browseId = playlistId,
                            previousThumbnailUrl = thumbnailUrl,
                            thumbnailUrl = refreshedUrl,
                        )
                        Result.success(refreshedUrl)
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        Result.failure(exception)
                    }

                refreshedCovers.put(thumbnailUrl, RefreshedCover(result, SystemClock.elapsedRealtime()))
                result.getOrThrow()
            }
        }

        private data class RefreshedCover(
            val result: Result<String>,
            val updatedAt: Long,
        )

        private companion object {
            const val COVER_REFRESH_CACHE_MILLIS = 60_000L
        }
    }
