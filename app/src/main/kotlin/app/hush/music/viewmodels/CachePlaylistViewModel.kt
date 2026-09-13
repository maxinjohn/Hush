/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.datasource.cache.Cache
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import app.hush.music.constants.HideExplicitKey
import app.hush.music.db.MusicDatabase
import app.hush.music.db.entities.Song
import app.hush.music.di.DownloadCache
import app.hush.music.di.PlayerCache
import app.hush.music.extensions.filterExplicit
import app.hush.music.spotiflac.SpotiFLACNativeRuntimeBridge
import app.hush.music.utils.dataStore
import app.hush.music.utils.get
import java.time.LocalDateTime
import javax.inject.Inject

@HiltViewModel
class CachePlaylistViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
        @PlayerCache private val playerCache: Cache,
        @DownloadCache private val downloadCache: Cache,
        private val spotiflacRuntime: SpotiFLACNativeRuntimeBridge,
    ) : ViewModel() {
        private val _cachedSongs = MutableStateFlow<List<Song>>(emptyList())
        val cachedSongs: StateFlow<List<Song>> = _cachedSongs

        private val _isLoading = MutableStateFlow(true)
        val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

        init {
            viewModelScope.launch(Dispatchers.IO) {
                var emptyPolls = 0
                while (true) {
                    val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                    val cachedIds = playerCache.keys.toSet()
                    val downloadedIds = downloadCache.keys.toSet()
                    // SpotiFLAC tracks are cached in their own store as complete local files:
                    // playback reads them directly and never copies them into Media3's song
                    // cache, so they would otherwise be missing from this list entirely. The
                    // download store is subtracted from them for the same reason it is
                    // subtracted from the song cache: those belong to the downloads list.
                    val spotiflacCachedIds =
                        runCatching { spotiflacRuntime.cachedPlaybackMediaIds() }
                            .getOrDefault(emptySet())
                            .subtract(downloadedIds)
                    val pureCacheIds = cachedIds.subtract(downloadedIds) + spotiflacCachedIds

                    val songs =
                        if (pureCacheIds.isNotEmpty()) {
                            database.getSongsByIds(pureCacheIds.toList())
                        } else {
                            emptyList()
                        }

                    val completeSongs =
                        songs.filter {
                            if (it.song.id in spotiflacCachedIds) return@filter true
                            val contentLength = it.format?.contentLength
                            contentLength != null && playerCache.isCached(it.song.id, 0, contentLength)
                        }

                    if (completeSongs.isNotEmpty()) {
                        emptyPolls = 0
                        database.query {
                            completeSongs.forEach {
                                if (it.song.dateDownload == null) {
                                    update(it.song.copy(dateDownload = LocalDateTime.now()))
                                }
                            }
                        }
                    } else {
                        emptyPolls++
                    }

                    if (_isLoading.value && (completeSongs.isNotEmpty() || emptyPolls >= 10)) {
                        _isLoading.value = false
                    }

                    _cachedSongs.value =
                        completeSongs
                            .filter { it.song.dateDownload != null }
                            .sortedByDescending { it.song.dateDownload }
                            .filterExplicit(hideExplicit)

                    delay(1000)
                }
            }
        }

        fun removeSongFromCache(songId: String) {
            playerCache.removeResource(songId)
            // Both stores are caches of the same song, so removing it from the list has to
            // clear whichever one actually holds it.
            runCatching { spotiflacRuntime.removeCachedPlaybackForMediaId(songId) }
        }
    }
