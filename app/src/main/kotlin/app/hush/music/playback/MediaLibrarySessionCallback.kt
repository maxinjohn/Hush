/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.offline.Download
import app.hush.music.spotiflac.SpotiFLACDiag
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import app.hush.music.R
import app.hush.music.constants.AndroidAutoSectionsOrderKey
import app.hush.music.constants.AndroidAutoSpotifyPlaylistsKey
import app.hush.music.constants.AndroidAutoTargetPlaylistKey
import app.hush.music.constants.AndroidAutoYouTubePlaylistsKey
import app.hush.music.constants.HideExplicitKey
import app.hush.music.constants.HideVideoKey
import app.hush.music.constants.MediaSessionConstants
import app.hush.music.constants.PlaylistSongSortType
import app.hush.music.constants.PlaylistSortType
import app.hush.music.constants.SongSortType
import app.hush.music.db.MusicDatabase
import app.hush.music.db.entities.PlaylistEntity
import app.hush.music.db.entities.PlaylistSong
import app.hush.music.db.entities.Song
import app.hush.music.extensions.ExtraSpotifyTrackId
import app.hush.music.extensions.metadata
import app.hush.music.extensions.toMediaItem
import app.hush.music.extensions.toggleRepeatMode
import app.hush.music.innertube.YouTube
import app.hush.music.innertube.models.PlaylistItem
import app.hush.music.innertube.models.SongItem
import app.hush.music.innertube.models.filterExplicit
import app.hush.music.innertube.models.filterVideo
import app.hush.music.models.PersistQueue
import app.hush.music.playback.MusicService.Companion.PERSISTENT_QUEUE_FILE
import app.hush.music.spotify.SpotifyLibraryRepository
import app.hush.music.spotify.SpotifyPlaybackResolver
import app.hush.music.utils.dataStore
import app.hush.music.utils.get
import app.hush.music.utils.isLocalMediaId
import app.hush.music.utils.reportException
import java.io.ObjectInputStream
import java.text.Collator
import java.time.LocalDateTime
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.math.min

class MediaLibrarySessionCallback
    @Inject
    constructor(
        @ApplicationContext val context: Context,
        val database: MusicDatabase,
        val downloadUtil: DownloadUtil,
        val spotifyLibrary: SpotifyLibraryRepository,
    ) : MediaLibrarySession.Callback {
        private val scope = CoroutineScope(Dispatchers.Main) + Job()
        private var pendingSearchJob: Job? = null
        private val onlineSearchItemCache = ConcurrentHashMap<String, MediaItem>()
        var toggleLike: () -> Unit = {}
        var toggleStartRadio: () -> Unit = {}
        var toggleLibrary: () -> Unit = {}

        /**
         * Invoked when a transport command arrives with nothing loaded to act on it.
         *
         * A hardware or headset key press is delivered to the session, not to the app's own
         * transport buttons, so a car or a headset pressing skip on a freshly installed app used
         * to be swallowed by an empty player. The service wires this to the same recovery the
         * in-app buttons use.
         */
        var onEmptyPlayerTransportRequest: (() -> Unit)? = null

        private data class AutoPlaylistSortOption(
            val sortType: PlaylistSongSortType,
            val descending: Boolean,
            @StringRes val titleRes: Int,
        )

        private fun browsableExtras(
            browsableHint: Int = CONTENT_STYLE_GRID_ITEM,
            playableHint: Int = CONTENT_STYLE_LIST_ITEM,
        ) = Bundle().apply {
            putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED, true)
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT, browsableHint)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT, playableHint)
        }

        private fun playableExtras(playableHint: Int = CONTENT_STYLE_LIST_ITEM) =
            Bundle().apply {
                putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED, true)
                putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT, playableHint)
            }

        private fun List<MediaItem>.paged(
            page: Int,
            pageSize: Int,
        ): List<MediaItem> {
            if (page < 0 || pageSize <= 0) return this
            val from = page.toLong() * pageSize.toLong()
            if (from >= size) return emptyList()
            val to = min(from + pageSize, size.toLong()).toInt()
            return subList(from.toInt(), to)
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val connectionResult = super.onConnect(session, controller)
            return MediaSession.ConnectionResult.accept(
                connectionResult.availableSessionCommands
                    .buildUpon()
                    .add(MediaSessionConstants.CommandToggleLike)
                    .add(MediaSessionConstants.CommandToggleStartRadio)
                    .add(MediaSessionConstants.CommandToggleLibrary)
                    .add(MediaSessionConstants.CommandToggleShuffle)
                    .add(MediaSessionConstants.CommandToggleRepeatMode)
                    // Offered by name to every controller so the car's own quick-add button can
                    // reach it, not only the phone's notification.
                    .add(MediaSessionConstants.CommandAddToTargetPlaylist)
                    .build(),
                connectionResult.availablePlayerCommands,
            )
        }

        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int,
        ): Int {
            // Only the commands that mean "play me something" are answered, and only while the
            // timeline is empty: with items loaded the player handles them itself and this must
            // stay out of the way. `prepare` is deliberately absent - Android Auto sends it on
            // every connect, and starting playback because a car screen opened is not a transport
            // press.
            if (session.player.mediaItemCount == 0 && playerCommand in EMPTY_PLAYER_TRANSPORT_COMMANDS) {
                SpotiFLACDiag.log("empty-player transport recovery: command=$playerCommand")
                onEmptyPlayerTransportRequest?.invoke()
            }
            return super.onPlayerCommandRequest(session, controller, playerCommand)
        }

        override fun onMediaButtonEvent(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            // A headset or steering-wheel key with nothing loaded is the same dead press the app's
            // own buttons hit - and it is the only press that stays inside the media session, so
            // the player would be asked to skip an empty timeline. Consume it and rebuild a queue
            // instead, rather than letting it fall through to the default handling.
            if (session.player.mediaItemCount == 0 && intent.action == Intent.ACTION_MEDIA_BUTTON) {
                val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (event?.action == KeyEvent.ACTION_DOWN &&
                    TransportRecoveryPolicy.requestsPlaybackForKeyCode(event.keyCode)
                ) {
                    SpotiFLACDiag.log("empty-player media button recovery: keyCode=${event.keyCode}")
                    onEmptyPlayerTransportRequest?.invoke()
                    return true
                }
            }
            return super.onMediaButtonEvent(session, controller, intent)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = onPlaybackResumption(mediaSession, controller, true)

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            scope.future {
                val player = mediaSession.player
                val currentItems = List(player.mediaItemCount) { index -> player.getMediaItemAt(index) }
                val persistedItems =
                    withContext(Dispatchers.IO) {
                        readPersistentQueue()?.let { queue ->
                            PlaybackResumptionPlanner.PersistedItems(
                                items = queue.items.map { it.toMediaItem() },
                                mediaItemIndex = queue.mediaItemIndex,
                                positionMs = queue.position,
                            )
                        }
                    }
                // Read only in the case it can be used: a playback request with nothing loaded
                // and nothing persisted. This is the media-key path, where a skip or a play
                // reaches the player directly and never passes the UI transport policy, so
                // without a fallback here those presses are the ones that do nothing at all.
                val fallbackItems =
                    if (isForPlayback && currentItems.isEmpty() && persistedItems == null) {
                        withContext(Dispatchers.IO) { database.historyRecoveryItems() }
                    } else {
                        emptyList()
                    }
                val result =
                    PlaybackResumptionPlanner.resolve(
                        currentItems = currentItems,
                        currentIndex = player.currentMediaItemIndex,
                        currentPositionMs = player.currentPosition,
                        persistedItems = persistedItems,
                        isForPlayback = isForPlayback,
                        fallbackItems = fallbackItems,
                    )
                MediaSession.MediaItemsWithStartPosition(
                    result.items,
                    result.startIndex,
                    result.startPositionMs,
                )
            }

        private fun readPersistentQueue(): PersistQueue? {
            val file = context.filesDir.resolve(PERSISTENT_QUEUE_FILE)
            if (!file.exists() || !file.isFile) return null
            return try {
                file.inputStream().use { fis ->
                    ObjectInputStream(fis).use { input ->
                        input.readObject() as? PersistQueue
                    }
                }
            } catch (e: Exception) {
                null
            }
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                MediaSessionConstants.ACTION_TOGGLE_LIKE -> {
                    toggleLike()
                }

                MediaSessionConstants.ACTION_TOGGLE_START_RADIO -> {
                    toggleStartRadio()
                }

                MediaSessionConstants.ACTION_TOGGLE_LIBRARY -> {
                    toggleLibrary()
                }

                MediaSessionConstants.ACTION_TOGGLE_SHUFFLE -> {
                    session.player.shuffleModeEnabled =
                        !session.player.shuffleModeEnabled
                }

                MediaSessionConstants.ACTION_TOGGLE_REPEAT_MODE -> {
                    session.player.toggleRepeatMode()
                }

                MediaSessionConstants.ACTION_ADD_TO_TARGET_PLAYLIST -> {
                    return scope.future(Dispatchers.IO) {
                        val saved = addCurrentSongToTargetPlaylist(session)
                        SessionResult(
                            if (saved) SessionResult.RESULT_SUCCESS else SessionError.ERROR_UNKNOWN,
                        )
                    }
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(
                LibraryResult.ofItem(
                    MediaItem
                        .Builder()
                        .setMediaId(MusicService.ROOT)
                        .setMediaMetadata(
                            MediaMetadata
                                .Builder()
                                .setIsPlayable(false)
                                .setIsBrowsable(true)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                                .setExtras(browsableExtras())
                                .build(),
                        ).build(),
                    params,
                ),
            )

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> =
            Futures.immediateFuture(LibraryResult.ofVoid(params)).also {
                val q = query.trim()
                pendingSearchJob?.cancel()
                pendingSearchJob =
                    scope.launch(Dispatchers.IO) {
                        val count =
                            runCatching {
                                if (q.isBlank()) {
                                    0
                                } else {
                                    val localCount =
                                        searchOfflineSongs(q, previewSize = 25).count +
                                            database.searchArtistsCount(q) +
                                            database.searchAlbumsCount(q) +
                                            database.searchPlaylistsCount(q)
                                    val onlineCount = searchOnlineSongs(q, previewSize = 25).size
                                    localCount + onlineCount
                                }
                            }.getOrElse { 0 }
                        withContext(Dispatchers.Main) {
                            session.notifySearchResultChanged(browser, query, count, params)
                        }
                    }
            }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            scope.future(Dispatchers.IO) {
                val q = query.trim()
                val safePage = page.coerceAtLeast(0)
                val safePageSize = pageSize.coerceIn(1, 50)
                if (q.isBlank()) {
                    return@future LibraryResult.ofItemList(emptyList(), params)
                }

                val requested = (safePage + 1) * safePageSize
                val items = ArrayList<MediaItem>(min(requested, 200))

                val offlineSongs = searchOfflineSongs(q, previewSize = requested)
                val existingSongIds =
                    offlineSongs.items
                        .mapTo(HashSet(offlineSongs.items.size * 2), ::searchSongIdentity)
                val onlineSongs =
                    searchOnlineSongs(q, previewSize = requested).filter { onlineItem ->
                        existingSongIds.add(searchSongIdentity(onlineItem))
                    }
                onlineSongs.forEach { onlineSearchItemCache[it.mediaId] = it }
                items +=
                    interleaveMediaItems(
                        first = offlineSongs.items,
                        second = onlineSongs,
                    ).take(requested)

                if (items.size < requested) {
                    val remaining = requested - items.size

                    val artists = database.searchArtists(q, previewSize = remaining).first()
                    items +=
                        artists.map { artist ->
                            browsableMediaItem(
                                "${MusicService.ARTIST}/${artist.id}",
                                artist.title,
                                context.resources.getQuantityString(
                                    R.plurals.n_song,
                                    artist.songCount,
                                    artist.songCount,
                                ),
                                artist.thumbnailUrl?.toUri(),
                                MediaMetadata.MEDIA_TYPE_ARTIST,
                            )
                        }
                }

                if (items.size < requested) {
                    val remaining = requested - items.size

                    val albums = database.searchAlbums(q, previewSize = remaining).first()
                    items +=
                        albums.map { album ->
                            browsableMediaItem(
                                "${MusicService.ALBUM}/${album.id}",
                                album.title,
                                album.artists.joinToString { it.name },
                                album.thumbnailUrl?.toUri(),
                                MediaMetadata.MEDIA_TYPE_ALBUM,
                            )
                        }
                }

                if (items.size < requested) {
                    val remaining = requested - items.size

                    val playlists = database.searchPlaylists(q, previewSize = remaining).first()
                    items +=
                        playlists.map { playlist ->
                            browsableMediaItem(
                                "${MusicService.PLAYLIST}/${playlist.id}",
                                playlist.title,
                                context.resources.getQuantityString(
                                    R.plurals.n_song,
                                    playlist.songCount,
                                    playlist.songCount,
                                ),
                                playlist.thumbnails.firstOrNull()?.toUri(),
                                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            )
                        }
                }

                val from = safePage * safePageSize
                if (from >= items.size) return@future LibraryResult.ofItemList(emptyList(), params)
                val to = min(from + safePageSize, items.size)

                LibraryResult.ofItemList(items.subList(from, to), params)
            }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            scope.future(Dispatchers.IO) {
                val items =
                    when (parentId) {
                        MusicService.ROOT -> {
                            // Home, Quick picks, History and Downloaded have no switch on the Android
                            // Auto screen, so they are always published. The five sections that do
                            // are published in the order that screen arranged them, minus the ones
                            // switched off - which is what its own hint promises.
                            val alwaysOn =
                                listOf(
                                    browsableMediaItem(
                                        MusicService.HOME,
                                        context.getString(R.string.home),
                                        null,
                                        drawableUri(R.drawable.home_filled),
                                        MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                                    ),
                                    queueMediaItem(
                                        MusicService.QUICK_PICKS,
                                        context.getString(R.string.quick_picks),
                                        null,
                                        drawableUri(R.drawable.playlist_play),
                                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                    ),
                                    queueMediaItem(
                                        MusicService.RECENT,
                                        context.getString(R.string.history),
                                        null,
                                        drawableUri(R.drawable.history),
                                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                    ),
                                    queueMediaItem(
                                        MusicService.DOWNLOADED,
                                        context.getString(R.string.downloaded_songs),
                                        null,
                                        drawableUri(R.drawable.download),
                                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                    ),
                                )
                            val bySection =
                                mapOf(
                                    AndroidAutoPlaylists.Section.LIKED to
                                        queueMediaItem(
                                            MusicService.LIKED,
                                            context.getString(R.string.liked_songs),
                                            null,
                                            drawableUri(R.drawable.favorite),
                                            MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                        ),
                                    AndroidAutoPlaylists.Section.SONGS to
                                        browsableMediaItem(
                                            MusicService.SONG,
                                            context.getString(R.string.songs),
                                            null,
                                            drawableUri(R.drawable.music_note),
                                            MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                        ),
                                    AndroidAutoPlaylists.Section.ARTISTS to
                                        browsableMediaItem(
                                            MusicService.ARTIST,
                                            context.getString(R.string.artists),
                                            null,
                                            drawableUri(R.drawable.artist),
                                            MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
                                        ),
                                    AndroidAutoPlaylists.Section.ALBUMS to
                                        browsableMediaItem(
                                            MusicService.ALBUM,
                                            context.getString(R.string.albums),
                                            null,
                                            drawableUri(R.drawable.album),
                                            MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
                                        ),
                                    AndroidAutoPlaylists.Section.PLAYLISTS to
                                        browsableMediaItem(
                                            MusicService.PLAYLIST,
                                            context.getString(R.string.playlists),
                                            null,
                                            drawableUri(R.drawable.queue_music),
                                            MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                                        ),
                                )
                            val sections =
                                AndroidAutoPlaylists.enabledSections(
                                    context.dataStore.get(AndroidAutoSectionsOrderKey, ""),
                                )
                            alwaysOn + sections.mapNotNull { section -> bySection[section] }
                        }

                        MusicService.HOME -> {
                            listOf(
                                queueMediaItem(
                                    MusicService.HOME_QUICK_PICKS,
                                    context.getString(R.string.quick_picks),
                                    null,
                                    drawableUri(R.drawable.playlist_play),
                                    MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                ),
                                queueMediaItem(
                                    MusicService.HOME_FORGOTTEN_FAVORITES,
                                    context.getString(R.string.forgotten_favorites),
                                    null,
                                    drawableUri(R.drawable.favorite),
                                    MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                ),
                                queueMediaItem(
                                    MusicService.HOME_KEEP_LISTENING,
                                    context.getString(R.string.keep_listening),
                                    null,
                                    drawableUri(R.drawable.history),
                                    MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                ),
                                queueMediaItem(
                                    MusicService.HOME_SUGGESTED_SONGS,
                                    context.getString(R.string.android_auto_suggested_songs),
                                    null,
                                    drawableUri(R.drawable.music_note),
                                    MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                ),
                                browsableMediaItem(
                                    MusicService.HOME_MIXES_AND_RADIOS,
                                    context.getString(R.string.android_auto_mixes_and_radios),
                                    null,
                                    drawableUri(R.drawable.radio),
                                    MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                                ),
                            )
                        }

                        MusicService.HOME_QUICK_PICKS -> {
                            database
                                .quickPicks()
                                .first()
                                .shuffled()
                                .take(AUTO_BROWSE_LIMIT)
                                .map { it.toMediaItem(parentId) }
                        }

                        MusicService.HOME_FORGOTTEN_FAVORITES -> {
                            database
                                .forgottenFavorites()
                                .first()
                                .shuffled()
                                .take(AUTO_BROWSE_LIMIT)
                                .map { it.toMediaItem(parentId) }
                        }

                        MusicService.HOME_KEEP_LISTENING -> {
                            homeKeepListeningSongs()
                                .map { it.toMediaItem(parentId) }
                        }

                        MusicService.HOME_SUGGESTED_SONGS -> {
                            homeSuggestedSongs()
                                .map { it.toMediaItem(parentId) }
                        }

                        MusicService.HOME_MIXES_AND_RADIOS -> {
                            homeMixesAndRadios()
                        }

                        MusicService.QUICK_PICKS -> {
                            database
                                .quickPicks()
                                .first()
                                .map { it.toMediaItem(parentId) }
                        }

                        MusicService.RECENT -> {
                            database
                                .recentSongs(AUTO_BROWSE_LIMIT)
                                .first()
                                .map { it.toMediaItem(parentId) }
                        }

                        MusicService.LIKED -> {
                            database
                                .likedSongs(
                                    SongSortType.CREATE_DATE,
                                    descending = true,
                                ).first()
                                .map { it.toMediaItem(parentId) }
                        }

                        MusicService.DOWNLOADED -> {
                            downloadedSongs()
                                .first()
                                .map { it.toMediaItem(parentId) }
                        }

                        MusicService.SONG -> {
                            database
                                .songsByCreateDateAsc()
                                .first()
                                .map { it.toMediaItem(parentId) }
                        }

                        MusicService.ARTIST -> {
                            database.artistsByCreateDateAsc().first().map { artist ->
                                browsableMediaItem(
                                    "${MusicService.ARTIST}/${artist.id}",
                                    artist.artist.name,
                                    context.resources.getQuantityString(
                                        R.plurals.n_song,
                                        artist.songCount,
                                        artist.songCount,
                                    ),
                                    artist.artist.thumbnailUrl?.toUri(),
                                    MediaMetadata.MEDIA_TYPE_ARTIST,
                                )
                            }
                        }

                        MusicService.ALBUM -> {
                            database.albumsByCreateDateAsc().first().map { album ->
                                browsableMediaItem(
                                    "${MusicService.ALBUM}/${album.id}",
                                    album.album.title,
                                    album.artists.joinToString {
                                        it.name
                                    },
                                    album.album.thumbnailUrl?.toUri(),
                                    MediaMetadata.MEDIA_TYPE_ALBUM,
                                )
                            }
                        }

                        MusicService.PLAYLIST -> {
                            val likedSongCount = database.likedSongsCount().first()
                            val downloadedSongCount = downloadUtil.downloads.value.size
                            listOf(
                                queueMediaItem(
                                    "${MusicService.PLAYLIST}/${PlaylistEntity.LIKED_PLAYLIST_ID}",
                                    context.getString(R.string.liked_songs),
                                    context.resources.getQuantityString(
                                        R.plurals.n_song,
                                        likedSongCount,
                                        likedSongCount,
                                    ),
                                    drawableUri(R.drawable.favorite),
                                    MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                ),
                                queueMediaItem(
                                    "${MusicService.PLAYLIST}/${PlaylistEntity.DOWNLOADED_PLAYLIST_ID}",
                                    context.getString(R.string.downloaded_songs),
                                    context.resources.getQuantityString(
                                        R.plurals.n_song,
                                        downloadedSongCount,
                                        downloadedSongCount,
                                    ),
                                    drawableUri(R.drawable.download),
                                    MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                ),
                            ) +
                                database.playlists(PlaylistSortType.CUSTOM, descending = false).first().map { playlist ->
                                    queueMediaItem(
                                        "${MusicService.PLAYLIST}/${playlist.id}",
                                        playlist.playlist.name,
                                        context.resources.getQuantityString(
                                            R.plurals.n_song,
                                            playlist.songCount,
                                            playlist.songCount,
                                        ),
                                        playlist.thumbnails.firstOrNull()?.toUri(),
                                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                    )
                                }
                        }

                        else -> {
                            when {
                                parentId.startsWith("${MusicService.ARTIST}/") -> {
                                    database
                                        .artistSongsByCreateDateAsc(parentId.removePrefix("${MusicService.ARTIST}/"))
                                        .first()
                                        .map {
                                            it.toMediaItem(parentId)
                                        }
                                }

                                parentId.startsWith("${MusicService.ALBUM}/") -> {
                                    database
                                        .albumSongs(parentId.removePrefix("${MusicService.ALBUM}/"))
                                        .first()
                                        .map {
                                            it.toMediaItem(parentId)
                                        }
                                }

                                parentId.startsWith("${MusicService.PLAYLIST}/") -> {
                                    playlistChildren(
                                        session = session,
                                        parentId = parentId,
                                    )
                                }

                                parentId.startsWith("${MusicService.ONLINE_PLAYLIST}/") -> {
                                    onlinePlaylistChildren(parentId)
                                }

                                parentId.startsWith("${AndroidAutoPlaylists.SPOTIFY_ROOT}/") -> {
                                    spotifyPlaylistChildren(parentId)
                                }

                                else -> {
                                    emptyList()
                                }
                            }
                        }
                    }

                LibraryResult.ofItemList(items.paged(page, pageSize), params)
            }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            scope.future(Dispatchers.IO) {
                when {
                    mediaId == MusicService.ROOT -> {
                        LibraryResult.ofItem(
                            MediaItem
                                .Builder()
                                .setMediaId(MusicService.ROOT)
                                .setMediaMetadata(
                                    MediaMetadata
                                        .Builder()
                                        .setIsPlayable(false)
                                        .setIsBrowsable(true)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                                        .setExtras(browsableExtras())
                                        .build(),
                                ).build(),
                            null,
                        )
                    }

                    mediaId == MusicService.HOME -> {
                        LibraryResult.ofItem(
                            browsableMediaItem(
                                MusicService.HOME,
                                context.getString(R.string.home),
                                null,
                                drawableUri(R.drawable.home_filled),
                                MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                            ),
                            null,
                        )
                    }

                    mediaId == MusicService.HOME_QUICK_PICKS -> {
                        LibraryResult.ofItem(
                            queueMediaItem(
                                MusicService.HOME_QUICK_PICKS,
                                context.getString(R.string.quick_picks),
                                null,
                                drawableUri(R.drawable.playlist_play),
                                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            ),
                            null,
                        )
                    }

                    mediaId == MusicService.HOME_FORGOTTEN_FAVORITES -> {
                        LibraryResult.ofItem(
                            queueMediaItem(
                                MusicService.HOME_FORGOTTEN_FAVORITES,
                                context.getString(R.string.forgotten_favorites),
                                null,
                                drawableUri(R.drawable.favorite),
                                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            ),
                            null,
                        )
                    }

                    mediaId == MusicService.HOME_KEEP_LISTENING -> {
                        LibraryResult.ofItem(
                            queueMediaItem(
                                MusicService.HOME_KEEP_LISTENING,
                                context.getString(R.string.keep_listening),
                                null,
                                drawableUri(R.drawable.history),
                                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            ),
                            null,
                        )
                    }

                    mediaId == MusicService.HOME_SUGGESTED_SONGS -> {
                        LibraryResult.ofItem(
                            queueMediaItem(
                                MusicService.HOME_SUGGESTED_SONGS,
                                context.getString(R.string.android_auto_suggested_songs),
                                null,
                                drawableUri(R.drawable.music_note),
                                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            ),
                            null,
                        )
                    }

                    mediaId == MusicService.HOME_MIXES_AND_RADIOS -> {
                        LibraryResult.ofItem(
                            browsableMediaItem(
                                MusicService.HOME_MIXES_AND_RADIOS,
                                context.getString(R.string.android_auto_mixes_and_radios),
                                null,
                                drawableUri(R.drawable.radio),
                                MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                            ),
                            null,
                        )
                    }

                    mediaId == MusicService.SONG -> {
                        LibraryResult.ofItem(
                            browsableMediaItem(
                                MusicService.SONG,
                                context.getString(R.string.songs),
                                null,
                                drawableUri(R.drawable.music_note),
                                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            ),
                            null,
                        )
                    }

                    mediaId == MusicService.QUICK_PICKS -> {
                        LibraryResult.ofItem(
                            queueMediaItem(
                                MusicService.QUICK_PICKS,
                                context.getString(R.string.quick_picks),
                                null,
                                drawableUri(R.drawable.playlist_play),
                                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            ),
                            null,
                        )
                    }

                    mediaId == MusicService.RECENT -> {
                        LibraryResult.ofItem(
                            queueMediaItem(
                                MusicService.RECENT,
                                context.getString(R.string.history),
                                null,
                                drawableUri(R.drawable.history),
                                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            ),
                            null,
                        )
                    }

                    mediaId == MusicService.LIKED -> {
                        LibraryResult.ofItem(
                            queueMediaItem(
                                MusicService.LIKED,
                                context.getString(R.string.liked_songs),
                                null,
                                drawableUri(R.drawable.favorite),
                                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            ),
                            null,
                        )
                    }

                    mediaId == MusicService.DOWNLOADED -> {
                        LibraryResult.ofItem(
                            queueMediaItem(
                                MusicService.DOWNLOADED,
                                context.getString(R.string.downloaded_songs),
                                null,
                                drawableUri(R.drawable.download),
                                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            ),
                            null,
                        )
                    }

                    mediaId == MusicService.ARTIST -> {
                        LibraryResult.ofItem(
                            browsableMediaItem(
                                MusicService.ARTIST,
                                context.getString(R.string.artists),
                                null,
                                drawableUri(R.drawable.artist),
                                MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
                            ),
                            null,
                        )
                    }

                    mediaId == MusicService.ALBUM -> {
                        LibraryResult.ofItem(
                            browsableMediaItem(
                                MusicService.ALBUM,
                                context.getString(R.string.albums),
                                null,
                                drawableUri(R.drawable.album),
                                MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
                            ),
                            null,
                        )
                    }

                    mediaId == MusicService.PLAYLIST -> {
                        LibraryResult.ofItem(
                            browsableMediaItem(
                                MusicService.PLAYLIST,
                                context.getString(R.string.playlists),
                                null,
                                drawableUri(R.drawable.queue_music),
                                MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                            ),
                            null,
                        )
                    }

                    mediaId.startsWith("${MusicService.ONLINE_PLAYLIST}/") -> {
                        onlinePlaylistItem(mediaId)?.let {
                            LibraryResult.ofItem(it, null)
                        } ?: LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
                    }

                    AndroidAutoPlaylists.parseSpotify(mediaId) != null -> {
                        spotifyPlaylistItem(mediaId)?.let {
                            LibraryResult.ofItem(it, null)
                        } ?: LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
                    }

                    mediaId.isAutoQueueSongPath() -> {
                        autoQueueSongPathItem(mediaId)?.let {
                            LibraryResult.ofItem(it, null)
                        } ?: LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
                    }

                    mediaId.startsWith("${MusicService.SONG}/") -> {
                        database.song(mediaId.removePrefix("${MusicService.SONG}/")).first()?.let {
                            LibraryResult.ofItem(it.toMediaItem(MusicService.SONG), null)
                        } ?: LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
                    }

                    mediaId.startsWith("${MusicService.ARTIST}/") -> {
                        database.artist(mediaId.removePrefix("${MusicService.ARTIST}/")).first()?.let { artist ->
                            LibraryResult.ofItem(
                                browsableMediaItem(
                                    "${MusicService.ARTIST}/${artist.id}",
                                    artist.title,
                                    context.resources.getQuantityString(
                                        R.plurals.n_song,
                                        artist.songCount,
                                        artist.songCount,
                                    ),
                                    artist.thumbnailUrl?.toUri(),
                                    MediaMetadata.MEDIA_TYPE_ARTIST,
                                ),
                                null,
                            )
                        } ?: LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
                    }

                    mediaId.startsWith("${MusicService.ALBUM}/") -> {
                        database.album(mediaId.removePrefix("${MusicService.ALBUM}/")).first()?.let { album ->
                            LibraryResult.ofItem(
                                browsableMediaItem(
                                    "${MusicService.ALBUM}/${album.id}",
                                    album.title,
                                    album.artists.joinToString { it.name },
                                    album.thumbnailUrl?.toUri(),
                                    MediaMetadata.MEDIA_TYPE_ALBUM,
                                ),
                                null,
                            )
                        } ?: LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
                    }

                    mediaId.startsWith("${MusicService.PLAYLIST}/") -> {
                        playlistItem(mediaId)?.let {
                            LibraryResult.ofItem(it, null)
                        } ?: LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
                    }

                    else -> {
                        onlineSearchItemCache[mediaId]?.let {
                            LibraryResult.ofItem(it, null)
                        } ?: database.song(mediaId).first()?.toMediaItem()?.let {
                            LibraryResult.ofItem(it, null)
                        } ?: LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
                    }
                }
            }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            scope.future(Dispatchers.IO) {
                // Play from Android Auto
                val defaultResult =
                    MediaSession.MediaItemsWithStartPosition(emptyList(), startIndex, startPositionMs)
                val firstItem = mediaItems.firstOrNull() ?: return@future defaultResult
                val voiceQuery =
                    firstItem.requestMetadata.searchQuery
                        ?.trim()
                        .orEmpty()
                if (voiceQuery.isNotBlank()) {
                    val offlineSongs = searchOfflineSongs(voiceQuery, previewSize = 50)
                    val existingSongIds =
                        offlineSongs.items.mapTo(HashSet(offlineSongs.items.size * 2), ::searchSongIdentity)
                    val onlineSongs =
                        searchOnlineSongs(voiceQuery, previewSize = 50).filter { onlineItem ->
                            existingSongIds.add(searchSongIdentity(onlineItem))
                        }
                    val searchQueue = interleaveMediaItems(offlineSongs.items, onlineSongs)
                    if (searchQueue.isNotEmpty()) {
                        return@future MediaSession.MediaItemsWithStartPosition(
                            searchQueue,
                            0,
                            startPositionMs,
                        )
                    }
                }
                val path = firstItem.mediaId.split("/").filter { it.isNotBlank() }
                when (path.firstOrNull()) {
                    MusicService.HOME_QUICK_PICKS -> {
                        val songs =
                            database
                                .quickPicks()
                                .first()
                                .shuffled()
                                .take(AUTO_BROWSE_LIMIT)
                        songs.toMediaItemsWithStartPosition(path.getOrNull(1), startPositionMs)
                    }

                    MusicService.HOME_FORGOTTEN_FAVORITES -> {
                        val songs =
                            database
                                .forgottenFavorites()
                                .first()
                                .shuffled()
                                .take(AUTO_BROWSE_LIMIT)
                        songs.toMediaItemsWithStartPosition(path.getOrNull(1), startPositionMs)
                    }

                    MusicService.HOME_KEEP_LISTENING -> {
                        val songs = homeKeepListeningSongs()
                        songs.toMediaItemsWithStartPosition(path.getOrNull(1), startPositionMs)
                    }

                    MusicService.HOME_SUGGESTED_SONGS -> {
                        val songs = homeSuggestedSongs().map { it.toMediaItem() }
                        songs.toMediaItemsWithStartPosition(path.getOrNull(1), startPositionMs)
                    }

                    MusicService.QUICK_PICKS -> {
                        val songs = database.quickPicks().first()
                        songs.toMediaItemsWithStartPosition(path.getOrNull(1), startPositionMs)
                    }

                    MusicService.RECENT -> {
                        val songs = database.recentSongs(AUTO_BROWSE_LIMIT).first()
                        songs.toMediaItemsWithStartPosition(path.getOrNull(1), startPositionMs)
                    }

                    MusicService.LIKED -> {
                        val songs =
                            database
                                .likedSongs(
                                    SongSortType.CREATE_DATE,
                                    descending = true,
                                ).first()
                        songs.toMediaItemsWithStartPosition(path.getOrNull(1), startPositionMs)
                    }

                    MusicService.DOWNLOADED -> {
                        val songs = downloadedSongs().first()
                        songs.toMediaItemsWithStartPosition(path.getOrNull(1), startPositionMs)
                    }

                    MusicService.SONG -> {
                        val songId = path.getOrNull(1) ?: return@future defaultResult
                        val allSongs = database.songsByCreateDateAsc().first()
                        MediaSession.MediaItemsWithStartPosition(
                            allSongs.map { it.toMediaItem() },
                            allSongs.indexOfFirst { it.id == songId }.takeIf { it != -1 } ?: 0,
                            startPositionMs,
                        )
                    }

                    MusicService.ARTIST -> {
                        val songId = path.getOrNull(2) ?: return@future defaultResult
                        val artistId = path.getOrNull(1) ?: return@future defaultResult
                        val songs = database.artistSongsByCreateDateAsc(artistId).first()
                        MediaSession.MediaItemsWithStartPosition(
                            songs.map { it.toMediaItem() },
                            songs.indexOfFirst { it.id == songId }.takeIf { it != -1 } ?: 0,
                            startPositionMs,
                        )
                    }

                    MusicService.ALBUM -> {
                        val songId = path.getOrNull(2) ?: return@future defaultResult
                        val albumId = path.getOrNull(1) ?: return@future defaultResult
                        val albumWithSongs =
                            database.albumWithSongs(albumId).first() ?: return@future defaultResult
                        MediaSession.MediaItemsWithStartPosition(
                            albumWithSongs.songs.map { it.toMediaItem() },
                            albumWithSongs.songs.indexOfFirst { it.id == songId }.takeIf { it != -1 }
                                ?: 0,
                            startPositionMs,
                        )
                    }

                    MusicService.PLAYLIST -> {
                        val playlistId = path.getOrNull(1) ?: return@future defaultResult
                        val action = path.getOrNull(2)
                        if (action == PLAYLIST_ACTION_ADD_CURRENT_SONG) {
                            addCurrentSongToPlaylist(mediaSession, playlistId)
                            return@future mediaSession.currentMediaItemsWithStartPosition()
                        }
                        val sortOption =
                            if (action == PLAYLIST_ACTION_SORT) {
                                playlistSortOption(
                                    sortType = path.getOrNull(3),
                                    order = path.getOrNull(4),
                                )
                            } else {
                                null
                            }
                        val selectedSongId =
                            when {
                                sortOption != null -> path.getOrNull(5)
                                action == PLAYLIST_ACTION_SHUFFLE -> path.getOrNull(3)
                                else -> path.getOrNull(2)
                            }
                        val songs =
                            playlistSongs(
                                playlistId = playlistId,
                                sortOption = sortOption,
                            ).let { songs ->
                                if (action == PLAYLIST_ACTION_SHUFFLE) songs.shuffled() else songs
                            }
                        if (action == PLAYLIST_ACTION_SHUFFLE) {
                            withContext(Dispatchers.Main.immediate) {
                                mediaSession.player.shuffleModeEnabled = true
                            }
                        }
                        MediaSession.MediaItemsWithStartPosition(
                            songs.map { it.toMediaItem() },
                            selectedSongId?.let { songId ->
                                songs.indexOfFirst { it.id == songId }.takeIf { it != -1 }
                            } ?: 0,
                            startPositionMs,
                        )
                    }

                    MusicService.ONLINE_PLAYLIST -> {
                        val playlistId = path.getOrNull(1) ?: return@future defaultResult
                        val action = path.getOrNull(2)
                        val selectedSongId =
                            if (action == PLAYLIST_ACTION_SHUFFLE) path.getOrNull(3) else path.getOrNull(2)
                        val songs =
                            onlinePlaylistSongs(playlistId).let { songs ->
                                if (action == PLAYLIST_ACTION_SHUFFLE) songs.shuffled() else songs
                            }
                        if (action == PLAYLIST_ACTION_SHUFFLE) {
                            withContext(Dispatchers.Main.immediate) {
                                mediaSession.player.shuffleModeEnabled = true
                            }
                        }
                        val mediaItems = songs.map { it.toMediaItem() }
                        MediaSession.MediaItemsWithStartPosition(
                            mediaItems,
                            selectedSongId?.let { songId ->
                                mediaItems.indexOfFirst { it.mediaId == songId }.takeIf { it != -1 }
                            } ?: 0,
                            startPositionMs,
                        )
                    }

                    AndroidAutoPlaylists.SPOTIFY_ROOT -> {
                        val path =
                            AndroidAutoPlaylists.parseSpotify(firstItem.mediaId)
                                ?: return@future defaultResult
                        val mediaItems =
                            spotifyPlaylistSongs(path.playlistId).let { items ->
                                if (path.isShuffle) items.shuffled() else items
                            }
                        if (path.isShuffle) {
                            withContext(Dispatchers.Main.immediate) {
                                mediaSession.player.shuffleModeEnabled = true
                            }
                        }
                        MediaSession.MediaItemsWithStartPosition(
                            mediaItems,
                            path.selectedTrackId?.let { trackId ->
                                mediaItems.indexOfFirst { it.mediaId == trackId }.takeIf { it != -1 }
                            } ?: 0,
                            startPositionMs,
                        )
                    }

                    else -> {
                        val directMediaId = firstItem.mediaId.trim()
                        if (directMediaId.isNotBlank() && !directMediaId.contains("/")) {
                            val selectedItem = onlineSearchItemCache[directMediaId] ?: firstItem
                            return@future MediaSession.MediaItemsWithStartPosition(
                                listOf(selectedItem),
                                0,
                                startPositionMs,
                            )
                        }

                        val query =
                            firstItem.requestMetadata.searchQuery
                                ?.trim()
                                .orEmpty()
                        if (query.isBlank()) return@future defaultResult

                        val matchedSongs = database.searchSongs(query, previewSize = 50).first()
                        val songId = matchedSongs.firstOrNull()?.id ?: return@future defaultResult
                        val allSongs = database.songsByCreateDateAsc().first()
                        MediaSession.MediaItemsWithStartPosition(
                            allSongs.map { it.toMediaItem() },
                            allSongs.indexOfFirst { it.id == songId }.takeIf { it != -1 } ?: 0,
                            startPositionMs,
                        )
                    }
                }
            }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            scope.future(Dispatchers.IO) {
                mediaItems
                    .flatMap { item ->
                        val query =
                            item.requestMetadata.searchQuery
                                ?.trim()
                                .orEmpty()
                        if (query.isBlank()) {
                            listOf(item)
                        } else {
                            val resolved = resolveVoiceMediaItems(query)
                            resolved.ifEmpty { listOf(item) }
                        }
                    }.toMutableList()
            }

        private suspend fun autoQueueSongPathItem(mediaId: String): MediaItem? {
            val path = mediaId.pathSegments()
            val root = path.getOrNull(0) ?: return null
            val songId = path.getOrNull(1) ?: return null
            val parentId = mediaId.substringBeforeLast('/')
            return if (root == MusicService.HOME_SUGGESTED_SONGS) {
                onlineSearchItemCache[songId]
                    ?.buildUpon()
                    ?.setMediaId(mediaId)
                    ?.build()
                    ?: homeSuggestedSongs()
                        .firstOrNull { it.id == songId }
                        ?.toMediaItem(parentId)
            } else {
                database.song(songId).first()?.toMediaItem(parentId)
            }
        }

        private fun String.isAutoQueueSongPath(): Boolean {
            val root = pathSegments().firstOrNull() ?: return false
            return root in AUTO_QUEUE_SONG_ROOTS && contains("/")
        }

        private suspend fun playlistChildren(
            session: MediaLibrarySession,
            parentId: String,
        ): List<MediaItem> {
            val path = parentId.pathSegments()
            val playlistId = path.getOrNull(1) ?: return emptyList()
            return when (path.getOrNull(2)) {
                null -> {
                    val actionItems =
                        buildList {
                            add(
                                queueMediaItem(
                                    "$parentId/$PLAYLIST_ACTION_SHUFFLE",
                                    context.getString(R.string.shuffle),
                                    null,
                                    drawableUri(R.drawable.shuffle),
                                    MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                ),
                            )
                            add(
                                browsableMediaItem(
                                    "$parentId/$PLAYLIST_ACTION_SORT",
                                    context.getString(R.string.android_auto_sort_playlist),
                                    null,
                                    drawableUri(R.drawable.list),
                                    MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                                ),
                            )
                            val playlist = database.getPlaylistById(playlistId)
                            val playlistEntity = playlist?.playlist
                            val currentSongId =
                                withContext(Dispatchers.Main.immediate) {
                                    session.player.currentMediaItem
                                        ?.mediaId
                                        ?.trim()
                                }?.takeIf(String::isNotBlank)
                            val canAddCurrentSong =
                                playlistEntity != null &&
                                    playlistEntity.isEditable &&
                                    currentSongId != null &&
                                    database.checkInPlaylist(playlistId, currentSongId) == 0 &&
                                    (playlistEntity.browseId == null || !currentSongId.isLocalMediaId())
                            if (canAddCurrentSong) {
                                add(
                                    queueMediaItem(
                                        "$parentId/$PLAYLIST_ACTION_ADD_CURRENT_SONG",
                                        context.getString(R.string.add_current_song),
                                        playlistEntity?.name,
                                        drawableUri(R.drawable.playlist_add),
                                        MediaMetadata.MEDIA_TYPE_MUSIC,
                                    ),
                                )
                            }
                        }
                    actionItems + playlistSongs(playlistId).map { it.toMediaItem(parentId) }
                }

                PLAYLIST_ACTION_SORT -> {
                    if (path.size == 3) {
                        PLAYLIST_SORT_OPTIONS.map { option ->
                            queueMediaItem(
                                playlistSortPath(playlistId, option),
                                playlistSortTitle(option),
                                null,
                                drawableUri(R.drawable.list),
                                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            )
                        }
                    } else {
                        val sortOption =
                            playlistSortOption(path.getOrNull(3), path.getOrNull(4))
                                ?: return emptyList()
                        playlistSongs(playlistId, sortOption).map { it.toMediaItem(parentId) }
                    }
                }

                PLAYLIST_ACTION_SHUFFLE -> {
                    playlistSongs(playlistId).shuffled().map { it.toMediaItem(parentId) }
                }

                else -> {
                    emptyList()
                }
            }
        }

        private suspend fun playlistItem(mediaId: String): MediaItem? {
            val path = mediaId.pathSegments()
            val playlistId = path.getOrNull(1) ?: return null
            return when (path.getOrNull(2)) {
                null -> {
                    playlistHeaderItem(playlistId)
                }

                PLAYLIST_ACTION_SHUFFLE -> {
                    if (path.size == 3) {
                        queueMediaItem(
                            mediaId,
                            context.getString(R.string.shuffle),
                            playlistHeaderItem(playlistId)?.mediaMetadata?.title?.toString(),
                            drawableUri(R.drawable.shuffle),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST,
                        )
                    } else {
                        path.getOrNull(3)?.let { songId ->
                            database.song(songId).first()?.toMediaItem(mediaId.substringBeforeLast('/'))
                        }
                    }
                }

                PLAYLIST_ACTION_ADD_CURRENT_SONG -> {
                    queueMediaItem(
                        mediaId,
                        context.getString(R.string.add_current_song),
                        playlistHeaderItem(playlistId)?.mediaMetadata?.title?.toString(),
                        drawableUri(R.drawable.playlist_add),
                        MediaMetadata.MEDIA_TYPE_MUSIC,
                    )
                }

                PLAYLIST_ACTION_SORT -> {
                    if (path.size == 3) {
                        browsableMediaItem(
                            mediaId,
                            context.getString(R.string.android_auto_sort_playlist),
                            null,
                            drawableUri(R.drawable.list),
                            MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                        )
                    } else if (path.size > 5) {
                        path.getOrNull(5)?.let { songId ->
                            database.song(songId).first()?.toMediaItem(mediaId.substringBeforeLast('/'))
                        }
                    } else {
                        playlistSortOption(path.getOrNull(3), path.getOrNull(4))?.let { option ->
                            queueMediaItem(
                                mediaId,
                                playlistSortTitle(option),
                                playlistHeaderItem(playlistId)?.mediaMetadata?.title?.toString(),
                                drawableUri(R.drawable.list),
                                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            )
                        }
                    }
                }

                else -> {
                    val songId =
                        if (path.getOrNull(2) == PLAYLIST_ACTION_SORT) {
                            path.getOrNull(5)
                        } else {
                            path.getOrNull(2)
                        } ?: return null
                    database.song(songId).first()?.toMediaItem(mediaId.substringBeforeLast('/'))
                }
            }
        }

        private suspend fun playlistHeaderItem(playlistId: String): MediaItem? =
            when (playlistId) {
                PlaylistEntity.LIKED_PLAYLIST_ID -> {
                    val count = database.likedSongsCount().first()
                    queueMediaItem(
                        "${MusicService.PLAYLIST}/$playlistId",
                        context.getString(R.string.liked_songs),
                        context.resources.getQuantityString(R.plurals.n_song, count, count),
                        drawableUri(R.drawable.favorite),
                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                    )
                }

                PlaylistEntity.DOWNLOADED_PLAYLIST_ID -> {
                    val count = downloadUtil.downloads.value.size
                    queueMediaItem(
                        "${MusicService.PLAYLIST}/$playlistId",
                        context.getString(R.string.downloaded_songs),
                        context.resources.getQuantityString(R.plurals.n_song, count, count),
                        drawableUri(R.drawable.download),
                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                    )
                }

                else -> {
                    database.playlist(playlistId).first()?.let { playlist ->
                        queueMediaItem(
                            "${MusicService.PLAYLIST}/${playlist.id}",
                            playlist.title,
                            context.resources.getQuantityString(
                                R.plurals.n_song,
                                playlist.songCount,
                                playlist.songCount,
                            ),
                            playlist.thumbnails.firstOrNull()?.toUri(),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST,
                        )
                    }
                }
            }

        private suspend fun playlistSongs(
            playlistId: String,
            sortOption: AutoPlaylistSortOption? = null,
        ): List<Song> =
            when (playlistId) {
                PlaylistEntity.LIKED_PLAYLIST_ID -> {
                    val songSortType = sortOption?.sortType?.toSongSortType() ?: SongSortType.CREATE_DATE
                    database
                        .likedSongs(
                            songSortType,
                            descending = sortOption?.descending ?: true,
                        ).first()
                }

                PlaylistEntity.DOWNLOADED_PLAYLIST_ID -> {
                    sortDownloadedSongs(downloadedSongs().first(), sortOption)
                }

                else -> {
                    database
                        .playlistSongs(playlistId)
                        .first()
                        .sortedPlaylistSongs(sortOption)
                        .map { it.song }
                }
            }

        private fun sortDownloadedSongs(
            songs: List<Song>,
            sortOption: AutoPlaylistSortOption?,
        ): List<Song> {
            val option = sortOption ?: return songs
            val sorted =
                when (option.sortType) {
                    PlaylistSongSortType.CUSTOM,
                    PlaylistSongSortType.CREATE_DATE,
                    -> {
                        songs.sortedBy { it.song.dateDownload ?: it.song.inLibrary ?: LocalDateTime.MIN }
                    }

                    PlaylistSongSortType.NAME -> {
                        songs.sortedWith(songTitleComparator())
                    }

                    PlaylistSongSortType.ARTIST -> {
                        songs.sortedWith(songArtistComparator())
                    }

                    PlaylistSongSortType.PLAY_TIME -> {
                        songs.sortedBy { it.song.totalPlayTime }
                    }
                }
            return if (option.descending && option.sortType != PlaylistSongSortType.CUSTOM) {
                sorted.asReversed()
            } else {
                sorted
            }
        }

        private fun List<PlaylistSong>.sortedPlaylistSongs(sortOption: AutoPlaylistSortOption?): List<PlaylistSong> {
            val option = sortOption ?: return sortedWith(compareBy({ it.map.position }, { it.map.id }))
            val sorted =
                when (option.sortType) {
                    PlaylistSongSortType.CUSTOM -> {
                        sortedWith(compareBy({ it.map.position }, { it.map.id }))
                    }

                    PlaylistSongSortType.CREATE_DATE -> {
                        sortedWith(compareBy({ it.map.id }, { it.map.position }))
                    }

                    PlaylistSongSortType.NAME -> {
                        sortedWith(compareBy(songTitleCollator()) { it.song.song.title })
                    }

                    PlaylistSongSortType.ARTIST -> {
                        sortedWith(
                            compareBy(songTitleCollator()) { playlistSong ->
                                playlistSong.song.artists.joinToString("") { artist -> artist.name }
                            },
                        )
                    }

                    PlaylistSongSortType.PLAY_TIME -> {
                        sortedBy { it.song.song.totalPlayTime }
                    }
                }
            return if (option.descending && option.sortType != PlaylistSongSortType.CUSTOM) {
                sorted.asReversed()
            } else {
                sorted
            }
        }

        private suspend fun homeKeepListeningSongs(): List<Song> {
            val fromTimestamp = System.currentTimeMillis() - HOME_RECENT_WINDOW_MS
            return database
                .mostPlayedSongs(fromTimestamp, limit = AUTO_BROWSE_LIMIT, offset = 0)
                .first()
                .ifEmpty { database.recentSongs(AUTO_BROWSE_LIMIT).first() }
        }

        private suspend fun homeSuggestedSongs(): List<SongItem> {
            val hideExplicit = context.dataStore.get(HideExplicitKey, false)
            val hideVideo = context.dataStore.get(HideVideoKey, false)
            return YouTube
                .home()
                .getOrNull()
                ?.sections
                .orEmpty()
                .flatMap { it.items }
                .filterExplicit(hideExplicit)
                .filterVideo(hideVideo)
                .filterIsInstance<SongItem>()
                .distinctBy { it.id }
                .take(AUTO_BROWSE_LIMIT)
                .onEach { onlineSearchItemCache[it.id] = it.toMediaItem() }
        }

        /**
         * Which recommended-playlist groups this install should offer in the car.
         *
         * Both answers come from the switches on the Android Auto screen, which is the only place
         * that says what a car should show. The Spotify account is only consulted when its switch is
         * on: with the switch off there is nothing to find out, and the answer costs a read.
         */
        private suspend fun autoPlaylistSources(): AndroidAutoPlaylists.Sources {
            val spotifyEnabled = context.dataStore.get(AndroidAutoSpotifyPlaylistsKey, true)
            return AndroidAutoPlaylists.sources(
                youtubeEnabled = context.dataStore.get(AndroidAutoYouTubePlaylistsKey, true),
                spotifyEnabled = spotifyEnabled,
                spotifyConnected = spotifyEnabled && spotifyLibrary.ensureConnected(),
            )
        }

        private suspend fun homeMixesAndRadios(): List<MediaItem> {
            val localPlaylists =
                database
                    .playlists(PlaylistSortType.LAST_UPDATED, descending = true)
                    .first()
                    .take(AUTO_HOME_PLAYLIST_LIMIT)
                    .map { playlist ->
                        queueMediaItem(
                            "${MusicService.PLAYLIST}/${playlist.id}",
                            playlist.playlist.name,
                            context.resources.getQuantityString(
                                R.plurals.n_song,
                                playlist.songCount,
                                playlist.songCount,
                            ),
                            playlist.thumbnails.firstOrNull()?.toUri(),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST,
                        )
                    }
            val sources = autoPlaylistSources()
            val spotifyPlaylists = if (sources.spotify) homeSpotifyPlaylists() else emptyList()
            val onlinePlaylists = if (sources.youtube) homeOnlinePlaylists() else emptyList()
            return localPlaylists + spotifyPlaylists + onlinePlaylists
        }

        /**
         * The user's own Spotify playlists, as the Spotify library screen already caches them.
         *
         * A connected account whose library has never been fetched is refreshed once here: otherwise
         * a freshly connected account shows an empty folder in the car until the phone's Spotify
         * screen happens to be opened.
         */
        private suspend fun homeSpotifyPlaylists(): List<MediaItem> {
            spotifyLibrary.restoreCachedPlaylists()
            val cached = spotifyLibrary.playlists.value
            val playlists = if (cached.isEmpty()) spotifyLibrary.refreshPlaylists() else cached
            return playlists
                .take(AUTO_HOME_PLAYLIST_LIMIT)
                .map { playlist ->
                    val trackCount = playlist.tracks?.total?.takeIf { it > 0 }
                    queueMediaItem(
                        AndroidAutoPlaylists.spotifyMediaId(playlist.id),
                        playlist.name,
                        trackCount?.let { count ->
                            context.resources.getQuantityString(R.plurals.n_song, count, count)
                        } ?: playlist.owner?.displayName,
                        playlist.images.firstOrNull()?.url?.toUri(),
                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                    )
                }
        }

        private suspend fun homeOnlinePlaylists(): List<MediaItem> {
            val hideExplicit = context.dataStore.get(HideExplicitKey, false)
            val hideVideo = context.dataStore.get(HideVideoKey, false)
            return YouTube
                .home()
                .getOrNull()
                ?.sections
                .orEmpty()
                .flatMap { it.items }
                .filterExplicit(hideExplicit)
                .filterVideo(hideVideo)
                .filterIsInstance<PlaylistItem>()
                .distinctBy { it.id }
                .take(AUTO_HOME_PLAYLIST_LIMIT)
                .map { playlist ->
                    queueMediaItem(
                        "${MusicService.ONLINE_PLAYLIST}/${playlist.id}",
                        playlist.title,
                        playlist.songCountText ?: playlist.author?.name,
                        playlist.thumbnail?.toUri(),
                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                    )
                }
        }

        private suspend fun onlinePlaylistChildren(parentId: String): List<MediaItem> {
            val path = parentId.pathSegments()
            val playlistId = path.getOrNull(1) ?: return emptyList()
            return when (path.getOrNull(2)) {
                null -> {
                    listOf(
                        queueMediaItem(
                            "$parentId/$PLAYLIST_ACTION_SHUFFLE",
                            context.getString(R.string.shuffle),
                            null,
                            drawableUri(R.drawable.shuffle),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST,
                        ),
                    ) + onlinePlaylistSongs(playlistId).map { it.toMediaItem(parentId) }
                }

                PLAYLIST_ACTION_SHUFFLE -> {
                    onlinePlaylistSongs(playlistId).map { it.toMediaItem(parentId) }
                }

                else -> {
                    emptyList()
                }
            }
        }

        private suspend fun onlinePlaylistItem(mediaId: String): MediaItem? {
            val path = mediaId.pathSegments()
            val playlistId = path.getOrNull(1) ?: return null
            return when (path.getOrNull(2)) {
                null -> {
                    val playlist = YouTube.playlist(playlistId).getOrNull()?.playlist
                    queueMediaItem(
                        "${MusicService.ONLINE_PLAYLIST}/$playlistId",
                        playlist?.title ?: playlistId,
                        playlist?.songCountText ?: playlist?.author?.name,
                        playlist?.thumbnail?.toUri(),
                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                    )
                }

                PLAYLIST_ACTION_SHUFFLE -> {
                    if (path.size == 3) {
                        queueMediaItem(
                            mediaId,
                            context.getString(R.string.shuffle),
                            null,
                            drawableUri(R.drawable.shuffle),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST,
                        )
                    } else {
                        path.getOrNull(3)?.let { songId ->
                            onlinePlaylistSongItem(
                                playlistId = playlistId,
                                songId = songId,
                                parentId = mediaId.substringBeforeLast('/'),
                            )
                        }
                    }
                }

                else -> {
                    val songId = path.getOrNull(2) ?: return null
                    onlinePlaylistSongItem(
                        playlistId = playlistId,
                        songId = songId,
                        parentId = mediaId.substringBeforeLast('/'),
                    )
                }
            }
        }

        /**
         * The children of a Spotify playlist: a shuffle entry, then its tracks.
         *
         * A Spotify track is not playable until it has been matched to a stream, so the tracks are
         * resolved here rather than handed over as names - a car screen that lists tracks a tap
         * cannot play is worse than one that lists fewer.
         */
        private suspend fun spotifyPlaylistChildren(parentId: String): List<MediaItem> {
            val path = AndroidAutoPlaylists.parseSpotify(parentId) ?: return emptyList()
            return when {
                path.action == null -> {
                    listOf(
                        queueMediaItem(
                            AndroidAutoPlaylists.spotifyShuffleMediaId(path.playlistId),
                            context.getString(R.string.shuffle),
                            null,
                            drawableUri(R.drawable.shuffle),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST,
                        ),
                    ) + spotifyPlaylistSongs(path.playlistId).map { it.addressedFrom(parentId) }
                }

                path.isShuffle && path.trackId == null -> {
                    spotifyPlaylistSongs(path.playlistId).map { it.addressedFrom(parentId) }
                }

                else -> emptyList()
            }
        }

        /**
         * Re-addresses a resolved Spotify track into the folder it is being browsed from.
         *
         * A resolved track carries the media id of the stream it matched, so the folder has to be
         * prefixed exactly the way the device's own playlists do it - the id a car hands back on a
         * tap is this same string, and [spotifyPlaylistItem] has to be able to parse it.
         */
        private fun MediaItem.addressedFrom(parentId: String) =
            buildUpon()
                .setMediaId("$parentId/$mediaId")
                .build()
                .asFolderLeaf()

        /**
         * Marks a track as a playable leaf of the folder it is listed in.
         *
         * Media3's library session refuses any child whose metadata does not state explicitly
         * whether it is browsable ("mediaMetadata must specify isBrowsable"), and the items a
         * resolved online track is built from only ever state that it is playable. Device
         * playlists never hit this because their leaves come from [SongItem.toMediaItem], which
         * sets both flags - so the online ones have to be re-marked to match before a car is
         * offered them.
         */
        private fun MediaItem.asFolderLeaf() =
            buildUpon()
                .setMediaMetadata(
                    mediaMetadata
                        .buildUpon()
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .build(),
                )
                .build()

        private suspend fun spotifyPlaylistItem(mediaId: String): MediaItem? {
            val path = AndroidAutoPlaylists.parseSpotify(mediaId) ?: return null
            return when {
                path.action == null -> {
                    spotifyLibrary.restoreCachedPlaylists()
                    val playlist = spotifyLibrary.playlists.value.firstOrNull { it.id == path.playlistId }
                    val trackCount = playlist?.tracks?.total?.takeIf { it > 0 }
                    queueMediaItem(
                        AndroidAutoPlaylists.spotifyMediaId(path.playlistId),
                        playlist?.name ?: path.playlistId,
                        trackCount?.let { count ->
                            context.resources.getQuantityString(R.plurals.n_song, count, count)
                        } ?: playlist?.owner?.displayName,
                        playlist?.images?.firstOrNull()?.url?.toUri(),
                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                    )
                }

                path.isShuffle && path.trackId == null -> {
                    queueMediaItem(
                        mediaId,
                        context.getString(R.string.shuffle),
                        null,
                        drawableUri(R.drawable.shuffle),
                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                    )
                }

                else -> {
                    path.selectedTrackId?.let { trackId ->
                        spotifyPlaylistSongItem(
                            playlistId = path.playlistId,
                            trackId = trackId,
                            parentId = mediaId.substringBeforeLast('/'),
                        )
                    }
                }
            }
        }

        private suspend fun spotifyPlaylistSongItem(
            playlistId: String,
            trackId: String,
            parentId: String,
        ): MediaItem? =
            onlineSearchItemCache[trackId]
                ?.buildUpon()
                ?.setMediaId("$parentId/$trackId")
                ?.build()
                ?.asFolderLeaf()
                ?: spotifyPlaylistSongs(playlistId)
                    .firstOrNull { it.mediaId == trackId }

        /**
         * A Spotify playlist's tracks as playable items, matched in batches the way the in-app
         * Spotify queue already matches them - the match is one lookup per track, and doing those
         * one at a time is what makes an online playlist take seconds to open.
         */
        private suspend fun spotifyPlaylistSongs(playlistId: String): List<MediaItem> {
            if (!spotifyLibrary.ensureConnected()) return emptyList()
            val tracks =
                try {
                    spotifyLibrary.playlistTracks(playlistId).take(AUTO_SPOTIFY_TRACK_LIMIT)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    reportException(error)
                    return emptyList()
                }
            if (tracks.isEmpty()) return emptyList()

            val resolved = ArrayList<MediaItem>(tracks.size)
            tracks.chunked(AUTO_SPOTIFY_MATCH_BATCH).forEach { batch ->
                coroutineScope {
                    batch
                        .map { track -> async { SpotifyPlaybackResolver.resolveToMediaItem(track) } }
                        .awaitAll()
                }.filterNotNull().forEach { item -> resolved += item }
            }
            resolved.forEach { item -> onlineSearchItemCache[item.mediaId] = item }
            return resolved
        }

        private suspend fun onlinePlaylistSongs(playlistId: String): List<SongItem> =
            YouTube
                .playlist(playlistId)
                .getOrNull()
                ?.songs
                .orEmpty()
                .distinctBy { it.id }
                .take(AUTO_BROWSE_LIMIT)
                .onEach { onlineSearchItemCache[it.id] = it.toMediaItem() }

        private suspend fun onlinePlaylistSongItem(
            playlistId: String,
            songId: String,
            parentId: String,
        ): MediaItem? =
            onlineSearchItemCache[songId]
                ?.buildUpon()
                ?.setMediaId("$parentId/$songId")
                ?.build()
                ?: onlinePlaylistSongs(playlistId)
                    .firstOrNull { it.id == songId }
                    ?.toMediaItem(parentId)

        /**
         * Saves the playing track to the playlist the user picked for the car's quick-add button.
         *
         * The destination is the same media id the browse tree publishes, so a Spotify playlist
         * picked here is spelled exactly the way the tree spells it - one id, so the picker and the
         * button cannot disagree about where a save goes.
         */
        private suspend fun addCurrentSongToTargetPlaylist(mediaSession: MediaSession): Boolean {
            val target =
                context.dataStore.get(
                    AndroidAutoTargetPlaylistKey,
                    MediaSessionConstants.TARGET_PLAYLIST_AUTO,
                )
            AndroidAutoPlaylists.spotifyDestinationId(target)?.let { playlistId ->
                val currentItem =
                    withContext(Dispatchers.Main.immediate) {
                        mediaSession.player.currentMediaItem
                    } ?: return false
                return addCurrentSongToSpotifyPlaylist(playlistId, currentItem)
            }
            if (target.isBlank() || target == MediaSessionConstants.TARGET_PLAYLIST_AUTO) {
                // Nothing was chosen, so there is nowhere to put the track. Say so rather than
                // letting the button look broken.
                withContext(Dispatchers.Main.immediate) {
                    runCatching {
                        Toast
                            .makeText(
                                context,
                                R.string.android_auto_target_playlist_not_set,
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
                }
                return false
            }
            return addCurrentSongToPlaylist(mediaSession, target)
        }

        /**
         * Saves the playing track to a Spotify playlist.
         *
         * A track Hush plays from a Spotify playlist already carries its Spotify id. One matched
         * through YouTube does not, so it is looked up by title and artist first: the button asks
         * about the song being played, not about which route brought it here.
         */
        private suspend fun addCurrentSongToSpotifyPlaylist(
            playlistId: String,
            item: MediaItem,
        ): Boolean {
            if (!spotifyLibrary.ensureConnected()) return false
            val uri = spotifyTrackUri(item) ?: return false
            return spotifyLibrary.addTrackToPlaylist(playlistId = playlistId, trackUri = uri)
        }

        private suspend fun spotifyTrackUri(item: MediaItem): String? {
            item.mediaMetadata.extras
                ?.getString(ExtraSpotifyTrackId)
                ?.takeIf { it.isNotBlank() }
                ?.let { return "spotify:track:$it" }
            val title = item.mediaMetadata.title?.toString()?.trim().orEmpty()
            if (title.isBlank()) return null
            val artist = item.mediaMetadata.artist?.toString()?.trim().orEmpty()
            return spotifyLibrary.findTrackUri(title = title, artist = artist)
        }

        private suspend fun addCurrentSongToPlaylist(
            mediaSession: MediaSession,
            playlistId: String,
        ): Boolean {
            val currentItem =
                withContext(Dispatchers.Main.immediate) {
                    mediaSession.player.currentMediaItem
                } ?: return false
            val songId = currentItem.mediaId.trim().takeIf(String::isNotBlank) ?: return false
            return withContext(Dispatchers.IO) {
                val playlist = database.getPlaylistById(playlistId) ?: return@withContext false
                if (!playlist.playlist.isEditable) return@withContext false
                if (database.checkInPlaylist(playlistId, songId) > 0) return@withContext false
                val browseId = playlist.playlist.browseId
                val setVideoId =
                    if (browseId != null) {
                        if (songId.isLocalMediaId()) return@withContext false
                        YouTube.addToPlaylist(browseId, songId).getOrNull() ?: return@withContext false
                    } else {
                        null
                    }
                database.withTransaction {
                    if (getSongByIdBlocking(songId) == null) {
                        currentItem.metadata?.let { insert(it) } ?: return@withTransaction false
                    }
                    val latestPlaylist = getPlaylistByIdBlocking(playlistId) ?: return@withTransaction false
                    if (checkInPlaylist(playlistId, songId) > 0) return@withTransaction false
                    addSongEntriesToPlaylist(latestPlaylist, listOf(songId to setVideoId))
                    true
                }
            }
        }

        private suspend fun MediaSession.currentMediaItemsWithStartPosition(): MediaSession.MediaItemsWithStartPosition =
            withContext(Dispatchers.Main.immediate) {
                MediaSession.MediaItemsWithStartPosition(
                    List(player.mediaItemCount) { index -> player.getMediaItemAt(index) },
                    player.currentMediaItemIndex.coerceAtLeast(0),
                    player.currentPosition,
                )
            }

        @JvmName("mediaItemsToMediaItemsWithStartPosition")
        private fun List<MediaItem>.toMediaItemsWithStartPosition(
            selectedMediaId: String?,
            startPositionMs: Long,
        ) = MediaSession.MediaItemsWithStartPosition(
            this,
            selectedMediaId?.let { mediaId ->
                indexOfFirst { it.mediaId == mediaId }.takeIf { it != -1 }
            } ?: 0,
            startPositionMs,
        )

        private fun SongItem.toMediaItem(path: String): MediaItem =
            toMediaItem()
                .buildUpon()
                .setMediaId("$path/$id")
                .build()

        private fun playlistSortPath(
            playlistId: String,
            option: AutoPlaylistSortOption,
        ) = "${MusicService.PLAYLIST}/$playlistId/$PLAYLIST_ACTION_SORT/${option.sortType.name}/${option.orderValue}"

        private fun playlistSortOption(
            sortType: String?,
            order: String?,
        ): AutoPlaylistSortOption? {
            val parsedSortType =
                sortType?.let {
                    runCatching { PlaylistSongSortType.valueOf(it) }.getOrNull()
                } ?: return null
            val descending =
                when (order) {
                    SORT_ORDER_ASC -> false
                    SORT_ORDER_DESC -> true
                    else -> return null
                }
            return PLAYLIST_SORT_OPTIONS.firstOrNull {
                it.sortType == parsedSortType && it.descending == descending
            }
        }

        private fun playlistSortTitle(option: AutoPlaylistSortOption): String =
            context.getString(
                R.string.android_auto_sort_option_format,
                context.getString(option.titleRes),
                context.getString(
                    if (option.descending) {
                        R.string.sort_order_descending
                    } else {
                        R.string.sort_order_ascending
                    },
                ),
            )

        private val AutoPlaylistSortOption.orderValue: String
            get() = if (descending) SORT_ORDER_DESC else SORT_ORDER_ASC

        private fun PlaylistSongSortType.toSongSortType(): SongSortType =
            when (this) {
                PlaylistSongSortType.CUSTOM,
                PlaylistSongSortType.CREATE_DATE,
                -> SongSortType.CREATE_DATE

                PlaylistSongSortType.NAME -> SongSortType.NAME

                PlaylistSongSortType.ARTIST -> SongSortType.ARTIST

                PlaylistSongSortType.PLAY_TIME -> SongSortType.PLAY_TIME
            }

        private fun songTitleComparator(): Comparator<Song> = compareBy(songTitleCollator()) { it.song.title }

        private fun songArtistComparator(): Comparator<Song> =
            compareBy(songTitleCollator()) { song ->
                song.artists.joinToString("") { artist -> artist.name }
            }

        private fun songTitleCollator(): Collator =
            Collator.getInstance(Locale.getDefault()).apply {
                strength = Collator.PRIMARY
            }

        private fun String.pathSegments(): List<String> = split("/").filter { it.isNotBlank() }

        private fun drawableUri(
            @DrawableRes id: Int,
        ) = Uri
            .Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(context.resources.getResourcePackageName(id))
            .appendPath(context.resources.getResourceTypeName(id))
            .appendPath(context.resources.getResourceEntryName(id))
            .build()

        private fun browsableMediaItem(
            id: String,
            title: String,
            subtitle: String?,
            iconUri: Uri?,
            mediaType: Int = MediaMetadata.MEDIA_TYPE_MUSIC,
        ) = MediaItem
            .Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtist(subtitle)
                    .setArtworkUri(iconUri)
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .setMediaType(mediaType)
                    .setExtras(browsableExtras())
                    .build(),
            ).build()

        private fun queueMediaItem(
            id: String,
            title: String,
            subtitle: String?,
            iconUri: Uri?,
            mediaType: Int = MediaMetadata.MEDIA_TYPE_PLAYLIST,
        ) = MediaItem
            .Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtist(subtitle)
                    .setArtworkUri(iconUri)
                    .setIsPlayable(true)
                    .setIsBrowsable(true)
                    .setMediaType(mediaType)
                    .setExtras(browsableExtras())
                    .build(),
            ).build()

        private fun Song.toMediaItem(path: String) =
            MediaItem
                .Builder()
                .setMediaId("$path/$id")
                .setMediaMetadata(
                    MediaMetadata
                        .Builder()
                        .setTitle(song.title)
                        .setSubtitle(artists.joinToString { it.name })
                        .setArtist(artists.joinToString { it.name })
                        .setArtworkUri(song.thumbnailUrl?.toUri())
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .setExtras(playableExtras())
                        .build(),
                ).build()

        @JvmName("songsToMediaItemsWithStartPosition")
        private fun List<Song>.toMediaItemsWithStartPosition(
            selectedSongId: String?,
            startPositionMs: Long,
        ) = MediaSession.MediaItemsWithStartPosition(
            map { it.toMediaItem() },
            selectedSongId?.let { id ->
                indexOfFirst { it.id == id }.takeIf { it != -1 }
            } ?: 0,
            startPositionMs,
        )

        private fun downloadedSongs(): Flow<List<Song>> {
            val downloads = downloadUtil.downloads.value
            return database
                .allSongs()
                .flowOn(Dispatchers.IO)
                .map { songs ->
                    songs.filter {
                        downloads[it.id]?.state == Download.STATE_COMPLETED
                    }
                }.map { songs ->
                    songs
                        .map { it to downloads[it.id] }
                        .sortedBy { it.second?.updateTimeMs ?: 0L }
                        .map { it.first }
                }
        }

        private data class OfflineSongSearchResult(
            val items: List<MediaItem>,
            val count: Int,
        )

        private suspend fun searchOfflineSongs(
            query: String,
            previewSize: Int,
        ): OfflineSongSearchResult {
            if (query.isBlank() || previewSize <= 0) {
                return OfflineSongSearchResult(
                    items = emptyList(),
                    count = 0,
                )
            }

            val librarySongs = database.searchSongs(query, previewSize = previewSize).first()
            val libraryIds = librarySongs.mapTo(HashSet(librarySongs.size)) { it.id }
            val cachedOnlySongs = searchCachedOnlySongs(query, excludeIds = libraryIds)

            return OfflineSongSearchResult(
                items =
                    interleaveMediaItems(
                        first = librarySongs.map { it.toMediaItem(MusicService.SONG) },
                        second = cachedOnlySongs.take(previewSize).map { it.toMediaItem() },
                    ).take(previewSize),
                count = database.searchSongsCount(query) + cachedOnlySongs.size,
            )
        }

        private suspend fun searchCachedOnlySongs(
            query: String,
            excludeIds: Set<String> = emptySet(),
        ): List<Song> {
            val cachedIds = cachedSongIds()
            if (cachedIds.isEmpty()) return emptyList()

            val normalizedQuery = query.lowercase(Locale.getDefault())
            return database
                .getSongsByIds(cachedIds)
                .asSequence()
                .filter { it.song.inLibrary == null }
                .filterNot { it.id in excludeIds }
                .filter {
                    it.song.title
                        .lowercase(Locale.getDefault())
                        .contains(normalizedQuery)
                }.sortedBy { it.song.title.lowercase(Locale.getDefault()) }
                .toList()
        }

        private fun cachedSongIds(): List<String> {
            val completedDownloadIds =
                downloadUtil.downloads.value
                    .asSequence()
                    .filter { (_, download) -> download.state == Download.STATE_COMPLETED }
                    .map { (id, _) -> id }
            val downloadCacheIds =
                runCatching { downloadUtil.downloadCache.keys.asSequence() }
                    .getOrDefault(emptySequence())
            val playerCacheIds =
                runCatching { downloadUtil.playerCache.keys.asSequence() }
                    .getOrDefault(emptySequence())
            // SpotiFLAC playback files live outside Media3's caches, so they are not in
            // playerCacheIds even though the track is on this device and playable offline.
            val spotiflacCacheIds =
                runCatching { downloadUtil.spotiflacCachedMediaIds.asSequence() }
                    .getOrDefault(emptySequence())

            return sequenceOf(completedDownloadIds, downloadCacheIds, playerCacheIds, spotiflacCacheIds)
                .flatten()
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .toList()
        }

        private fun interleaveMediaItems(
            first: List<MediaItem>,
            second: List<MediaItem>,
        ): List<MediaItem> {
            if (first.isEmpty()) return second
            if (second.isEmpty()) return first

            val merged = ArrayList<MediaItem>(first.size + second.size)
            val maxSize = maxOf(first.size, second.size)
            repeat(maxSize) { index ->
                first.getOrNull(index)?.let(merged::add)
                second.getOrNull(index)?.let(merged::add)
            }
            return merged
        }

        internal suspend fun resolveVoiceMediaItems(
            query: String,
            previewSize: Int = 50,
        ): List<MediaItem> {
            val q = query.trim()
            if (q.isBlank()) return emptyList()
            val offlineSongs = searchOfflineSongs(q, previewSize)
            val existingIds = offlineSongs.items.mapTo(HashSet(offlineSongs.items.size * 2), ::searchSongIdentity)
            val onlineSongs =
                searchOnlineSongs(q, previewSize).filter { onlineItem ->
                    existingIds.add(searchSongIdentity(onlineItem))
                }
            onlineSongs.forEach { onlineSearchItemCache[it.mediaId] = it }
            return interleaveMediaItems(offlineSongs.items, onlineSongs).take(previewSize)
        }

        private fun searchSongIdentity(item: MediaItem): String = item.mediaId.removePrefix("${MusicService.SONG}/")

        private suspend fun searchOnlineSongs(
            query: String,
            previewSize: Int,
        ): List<MediaItem> {
            if (query.isBlank() || previewSize <= 0) return emptyList()
            return YouTube
                .search(query, YouTube.SearchFilter.FILTER_SONG)
                .getOrNull()
                ?.items
                .orEmpty()
                .asSequence()
                .filterIsInstance<SongItem>()
                .distinctBy { it.id }
                .take(previewSize)
                .map { it.toMediaItem() }
                .toList()
        }

        companion object {
            /**
             * Transport commands that mean "play something", and so may trigger a cold-start
             * recovery when the timeline is empty. Mirrors [TransportRecoveryPolicy].
             */
            private val EMPTY_PLAYER_TRANSPORT_COMMANDS =
                setOf(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                )

            private const val EXTRA_CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
            private const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT =
                "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
            private const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT =
                "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"

            private const val CONTENT_STYLE_LIST_ITEM = 1
            private const val CONTENT_STYLE_GRID_ITEM = 2
            private const val AUTO_BROWSE_LIMIT = 100
            private const val AUTO_HOME_PLAYLIST_LIMIT = 20

            /** How many of a Spotify playlist's tracks a car browse resolves. */
            private const val AUTO_SPOTIFY_TRACK_LIMIT = 50

            /** Matches resolved at once; the same batch the in-app Spotify queue resolves. */
            private const val AUTO_SPOTIFY_MATCH_BATCH = 20
            private const val HOME_RECENT_WINDOW_MS = 86400000L * 14L
            private const val PLAYLIST_ACTION_SHUFFLE = "_shuffle"
            private const val PLAYLIST_ACTION_SORT = "_sort"
            private const val PLAYLIST_ACTION_ADD_CURRENT_SONG = "_add_current_song"
            private const val SORT_ORDER_ASC = "asc"
            private const val SORT_ORDER_DESC = "desc"
            private val AUTO_QUEUE_SONG_ROOTS =
                setOf(
                    MusicService.HOME_QUICK_PICKS,
                    MusicService.HOME_FORGOTTEN_FAVORITES,
                    MusicService.HOME_KEEP_LISTENING,
                    MusicService.HOME_SUGGESTED_SONGS,
                    MusicService.QUICK_PICKS,
                    MusicService.RECENT,
                    MusicService.LIKED,
                    MusicService.DOWNLOADED,
                    MusicService.SONG,
                )
            private val PLAYLIST_SORT_OPTIONS =
                listOf(
                    AutoPlaylistSortOption(
                        sortType = PlaylistSongSortType.CUSTOM,
                        descending = false,
                        titleRes = R.string.sort_by_custom,
                    ),
                    AutoPlaylistSortOption(
                        sortType = PlaylistSongSortType.CREATE_DATE,
                        descending = true,
                        titleRes = R.string.sort_by_create_date,
                    ),
                    AutoPlaylistSortOption(
                        sortType = PlaylistSongSortType.CREATE_DATE,
                        descending = false,
                        titleRes = R.string.sort_by_create_date,
                    ),
                    AutoPlaylistSortOption(
                        sortType = PlaylistSongSortType.NAME,
                        descending = false,
                        titleRes = R.string.sort_by_name,
                    ),
                    AutoPlaylistSortOption(
                        sortType = PlaylistSongSortType.NAME,
                        descending = true,
                        titleRes = R.string.sort_by_name,
                    ),
                    AutoPlaylistSortOption(
                        sortType = PlaylistSongSortType.ARTIST,
                        descending = false,
                        titleRes = R.string.sort_by_artist,
                    ),
                    AutoPlaylistSortOption(
                        sortType = PlaylistSongSortType.ARTIST,
                        descending = true,
                        titleRes = R.string.sort_by_artist,
                    ),
                    AutoPlaylistSortOption(
                        sortType = PlaylistSongSortType.PLAY_TIME,
                        descending = true,
                        titleRes = R.string.sort_by_play_time,
                    ),
                    AutoPlaylistSortOption(
                        sortType = PlaylistSongSortType.PLAY_TIME,
                        descending = false,
                        titleRes = R.string.sort_by_play_time,
                    ),
                )
        }
    }
