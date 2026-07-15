/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.lyrics

import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import app.hush.music.constants.LowDataModeKey
import app.hush.music.constants.PreloadQueueLyricsEnabledKey
import app.hush.music.constants.QueueLyricsPreloadCountKey
import app.hush.music.db.MusicDatabase
import app.hush.music.db.entities.LyricsEntity
import app.hush.music.models.MediaMetadata
import app.hush.music.utils.NetworkConnectivityObserver
import app.hush.music.utils.dataStore
import app.hush.music.utils.isLowDataModeActive
import app.hush.music.utils.reportException
import javax.inject.Inject

/**
 * Manages pre-loading of lyrics for upcoming songs in the queue.
 * This improves user experience by having lyrics ready when songs change.
 */
class LyricsPreloadManager
    @Inject
    constructor(
        @ApplicationContext private val context: android.content.Context,
        private val database: MusicDatabase,
        private val networkConnectivity: NetworkConnectivityObserver,
        private val lyricsHelper: LyricsHelper,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var preloadJob: Job? = null

        /**
         * Called when the current song changes in the player.
         * Triggers pre-loading of lyrics for the next N songs in the queue.
         *
         * @param currentIndex The index of the currently playing song in the queue
         * @param queue List of MediaMetadata for songs in the queue
         */
        fun onSongChanged(
            currentIndex: Int,
            queue: List<MediaMetadata>,
        ) {
            preloadJob?.cancel()

            scope.launch {
                try {
                    val preferences = context.dataStore.data.first()
                    val isEnabled = preferences[PreloadQueueLyricsEnabledKey] ?: true

                    if (!isEnabled) {
                        Log.d(TAG, "Queue lyrics pre-load is disabled")
                        return@launch
                    }

                    val isNetworkAvailable =
                        try {
                            networkConnectivity.isCurrentlyConnected()
                        } catch (e: Exception) {
                            true
                        }

                    if (!isNetworkAvailable) {
                        Log.w(TAG, "Network unavailable, skipping lyrics pre-load")
                        return@launch
                    }

                    if (context.isLowDataModeActive(preferences[LowDataModeKey] ?: false)) {
                        Log.d(TAG, "Low Data Mode active, skipping lyrics pre-load")
                        return@launch
                    }

                    val preloadCount = preferences[QueueLyricsPreloadCountKey] ?: DEFAULT_PRELOAD_COUNT
                    val nextSongs = getNextSongs(queue, currentIndex, preloadCount)

                    if (nextSongs.isEmpty()) {
                        Log.d(TAG, "No songs to pre-load")
                        return@launch
                    }

                    Log.d(TAG, "Starting pre-load for ${nextSongs.size} songs")
                    preloadLyrics(nextSongs)
                } catch (e: Exception) {
                    reportException(e)
                }
            }
        }

        private fun getNextSongs(
            queue: List<MediaMetadata>,
            currentIndex: Int,
            count: Int,
        ): List<MediaMetadata> {
            if (queue.isEmpty() || currentIndex < 0) {
                return emptyList()
            }

            val startIndex = currentIndex + 1
            val endIndex = minOf(startIndex + count, queue.size)

            if (startIndex >= queue.size) {
                return emptyList()
            }

            return queue.subList(startIndex, endIndex)
        }

        private suspend fun preloadLyrics(songs: List<MediaMetadata>) {
            preloadJob =
                scope.launch {
                    try {
                        val semaphore = Semaphore(PRELOAD_CONCURRENCY)
                        songs
                            .map { song ->
                                async {
                                    semaphore.withPermit {
                                        preloadSongLyrics(song)
                                    }
                                }
                            }.awaitAll()
                    } catch (e: Exception) {
                        reportException(e)
                    }
                }
        }

        private suspend fun preloadSongLyrics(song: MediaMetadata) {
            val existingLyrics = database.lyrics(song.id).first()
            if (existingLyrics != null && existingLyrics.lyrics != LyricsEntity.LYRICS_NOT_FOUND) {
                Log.d(TAG, "Lyrics already cached for: ${song.title}")
                return
            }

            try {
                val lyrics = fetchLyricsForSong(song)
                if (lyrics != null && lyrics != LyricsEntity.LYRICS_NOT_FOUND) {
                    database.query {
                        insertLyricsIfAbsent(
                            id = song.id,
                            lyrics = lyrics,
                        )
                    }
                    Log.d(TAG, "Pre-loaded lyrics for: ${song.title}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to pre-load lyrics for ${song.title}: ${e.message}")
            }
        }

        private suspend fun fetchLyricsForSong(song: MediaMetadata): String? =
            try {
                lyricsHelper.getLyrics(song)
            } catch (e: Exception) {
                Log.w(TAG, "Error fetching lyrics for ${song.title}: ${e.message}")
                null
            }

        fun cancel() {
            preloadJob?.cancel()
            preloadJob = null
        }

        fun destroy() {
            cancel()
            scope.cancel()
        }

        companion object {
            private const val TAG = "LyricsPreloadManager"
            private const val DEFAULT_PRELOAD_COUNT = 5
            private const val PRELOAD_CONCURRENCY = 5
        }
    }
