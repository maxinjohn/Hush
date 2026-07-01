/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.repository

import android.content.Context
import com.google.common.collect.ImmutableList
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import app.hush.music.constants.HideExplicitKey
import app.hush.music.constants.SongSortType
import app.hush.music.db.MusicDatabase
import app.hush.music.db.entities.LibraryTopMixEntity
import app.hush.music.db.entities.LibraryTopMixSongMap
import app.hush.music.db.entities.Song
import app.hush.music.extensions.filterExplicit
import app.hush.music.library.GeneratedLibraryTopMix
import app.hush.music.library.LibraryTopMix
import app.hush.music.models.MediaMetadata
import app.hush.music.models.toMediaMetadata
import app.hush.music.utils.dataStore
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

private const val LibraryTopMixCandidateLimit = 300
private const val LibraryTopMixDisplayLimit = 5

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class LibraryTopMixRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
    ) {
        fun observePersistedTopMixes(): Flow<List<LibraryTopMix>> =
            hideExplicitEnabled()
                .flatMapLatest { hideExplicit ->
                    database
                        .libraryTopMixes(LibraryTopMixDisplayLimit)
                        .map { mixes ->
                            mixes.mapNotNull { mix ->
                                val tracks =
                                    database
                                        .libraryTopMixSongs(mix.id)
                                        .filterExplicit(hideExplicit)
                                        .map { it.toMediaMetadata() }
                                if (tracks.isEmpty()) {
                                    null
                                } else {
                                    LibraryTopMix(
                                        id = mix.id,
                                        title = mix.title,
                                        description = mix.description,
                                        tracks = ImmutableList.copyOf(tracks),
                                    )
                                }
                            }
                        }
                }.flowOn(Dispatchers.IO)

        suspend fun recentSongsForTopMixes(limit: Int): List<Song> =
            hideExplicitEnabled()
                .flatMapLatest { hideExplicit ->
                    database
                        .recentSongs(limit)
                        .map { songs -> songs.filterExplicit(hideExplicit) }
                }.first()

        suspend fun replaceTopMixes(mixes: List<GeneratedLibraryTopMix>) {
            database.withTransaction {
                deleteLibraryTopMixes()
                mixes
                    .take(LibraryTopMixDisplayLimit)
                    .forEachIndexed { mixIndex, mix ->
                        insert(
                            LibraryTopMixEntity(
                                id = mix.id,
                                title = mix.title,
                                description = mix.description,
                                position = mixIndex,
                                createdAt = LocalDateTime.now(),
                            ),
                        )
                        mix.tracks.forEachIndexed { trackIndex, track ->
                            insert(track)
                            insert(
                                LibraryTopMixSongMap(
                                    mixId = mix.id,
                                    songId = track.id,
                                    position = trackIndex,
                                ),
                            )
                        }
                    }
            }
        }

        fun observeRecentTracks(): Flow<List<MediaMetadata>> =
            hideExplicitEnabled()
                .flatMapLatest { hideExplicit ->
                    database
                        .recentSongs(LibraryTopMixCandidateLimit)
                        .map { songs -> songs.filterExplicit(hideExplicit).map { it.toMediaMetadata() } }
                }.flowOn(Dispatchers.IO)

        fun observeLikedTracks(): Flow<List<MediaMetadata>> =
            hideExplicitEnabled()
                .flatMapLatest { hideExplicit ->
                    database
                        .likedSongsByCreateDateAsc()
                        .map { songs ->
                            songs
                                .filterExplicit(hideExplicit)
                                .asReversed()
                                .map { it.toMediaMetadata() }
                        }
                }.flowOn(Dispatchers.IO)

        fun observeListenedTracks(): Flow<List<MediaMetadata>> =
            hideExplicitEnabled()
                .flatMapLatest { hideExplicit ->
                    database
                        .songs(SongSortType.PLAY_TIME, descending = true, filterVideo = true)
                        .map { songs ->
                            songs
                                .filterExplicit(hideExplicit)
                                .filter { it.song.totalPlayTime > 0L }
                                .map { it.toMediaMetadata() }
                        }
                }.flowOn(Dispatchers.IO)

        fun observeLibraryTracks(): Flow<List<MediaMetadata>> =
            hideExplicitEnabled()
                .flatMapLatest { hideExplicit ->
                    database
                        .songs(SongSortType.CREATE_DATE, descending = true, filterVideo = true)
                        .map { songs ->
                            songs
                                .filterExplicit(hideExplicit)
                                .map { it.toMediaMetadata() }
                        }
                }.flowOn(Dispatchers.IO)

        private fun hideExplicitEnabled(): Flow<Boolean> =
            context.dataStore.data
                .map { preferences -> preferences[HideExplicitKey] ?: false }
                .distinctUntilChanged()
    }
