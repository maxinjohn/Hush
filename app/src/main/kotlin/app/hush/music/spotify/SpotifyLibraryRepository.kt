/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotify

import android.content.Context
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import app.hush.music.R
import app.hush.music.constants.SpotifyAccessTokenExpiresAtKey
import app.hush.music.constants.SpotifyAccessTokenKey
import app.hush.music.constants.SpotifyAccountAvatarUrlKey
import app.hush.music.constants.SpotifyAccountNameKey
import app.hush.music.constants.SpotifyLibraryPlaylistsCacheKey
import app.hush.music.constants.SpotifySpDcKey
import app.hush.music.constants.SpotifySpKeyKey
import app.hush.music.spotify.models.SpotifyPlaylist
import app.hush.music.spotify.models.SpotifyPlaylistTracksRef
import app.hush.music.spotify.models.SpotifyTrack
import app.hush.music.utils.clearWebAuthSession
import app.hush.music.utils.dataStore
import app.hush.music.utils.reportException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpotifyLibraryRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val _playlists = MutableStateFlow<List<SpotifyPlaylist>>(emptyList())
        val playlists: StateFlow<List<SpotifyPlaylist>> = _playlists.asStateFlow()

        private val _isRefreshing = MutableStateFlow(false)
        val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        /**
         * Whether the stored session has been read at all.
         *
         * Reading it costs a DataStore round trip and possibly a token refresh, which is too much
         * to pay on every browse of a car screen - Android Auto asks for the same folder repeatedly
         * while the user scrolls. The answer does not change on its own, so it is read once and then
         * kept; connecting and logging out update it directly.
         */
        @Volatile
        private var sessionResolved = false

        suspend fun restoreCachedPlaylists() {
            withContext(Dispatchers.IO) {
                if (_playlists.value.isNotEmpty()) return@withContext
                val cached =
                    context.dataStore.data
                        .first()[SpotifyLibraryPlaylistsCacheKey]
                        .orEmpty()
                if (cached.isBlank()) return@withContext
                runCatching {
                    spotifyCacheJson.decodeFromString(
                        ListSerializer(SpotifyPlaylist.serializer()),
                        cached,
                    )
                }.onSuccess { playlists ->
                    _playlists.value = playlists
                }.onFailure { error ->
                    reportException(error)
                    context.dataStore.edit { prefs ->
                        prefs.remove(SpotifyLibraryPlaylistsCacheKey)
                    }
                }
            }
        }

        /**
         * Whether a Spotify account is connected, reading the stored session at most once.
         *
         * An unreachable account is not an error here: a car screen asking "is there a Spotify
         * library to show" only has to be told no.
         */
        suspend fun ensureConnected(): Boolean {
            if (sessionResolved) return _isConnected.value
            return try {
                restoreSession().isAuthenticated
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportException(error)
                sessionResolved = true
                _isConnected.value = false
                false
            }
        }

        suspend fun restoreSession(): SpotifyAccountSession =
            withContext(Dispatchers.IO) {
                val session = readStoredSession()
                sessionResolved = true
                _isConnected.value = session.isAuthenticated
                session
            }

        private suspend fun readStoredSession(): SpotifyAccountSession =
            withContext(Dispatchers.IO) {
                val prefs = context.dataStore.data.first()
                val token = prefs[SpotifyAccessTokenKey].orEmpty()
                val expiresAt = prefs[SpotifyAccessTokenExpiresAtKey] ?: 0L
                val accountName = prefs[SpotifyAccountNameKey].orEmpty()
                val avatarUrl = prefs[SpotifyAccountAvatarUrlKey]

                if (token.isNotBlank() && expiresAt > System.currentTimeMillis() + TOKEN_EXPIRY_GRACE_MS) {
                    Spotify.accessToken = token
                    return@withContext SpotifyAccountSession(
                        isAuthenticated = true,
                        accountName = accountName,
                        accountAvatarUrl = avatarUrl,
                    )
                }

                val spDc = prefs[SpotifySpDcKey].orEmpty()
                if (spDc.isBlank()) return@withContext SpotifyAccountSession()

                refreshAccessToken(spDc = spDc, spKey = prefs[SpotifySpKeyKey].orEmpty())
                    .fold(
                        onSuccess = {
                            val refreshed = context.dataStore.data.first()
                            SpotifyAccountSession(
                                isAuthenticated = true,
                                accountName = refreshed[SpotifyAccountNameKey].orEmpty(),
                                accountAvatarUrl = refreshed[SpotifyAccountAvatarUrlKey],
                            )
                        },
                        onFailure = {
                            if (it is CancellationException) throw it
                            reportException(it)
                            SpotifyAccountSession()
                        },
                    )
            }

        suspend fun connectWithCookies(
            spDc: String,
            spKey: String,
        ): SpotifyAccountSession =
            withContext(Dispatchers.IO) {
                context.dataStore.edit { prefs ->
                    prefs[SpotifySpDcKey] = spDc
                    prefs.remove(SpotifyLibraryPlaylistsCacheKey)
                    if (spKey.isNotBlank()) {
                        prefs[SpotifySpKeyKey] = spKey
                    } else {
                        prefs.remove(SpotifySpKeyKey)
                    }
                }
                _playlists.value = emptyList()
                _errorMessage.value = null
                refreshAccessToken(spDc = spDc, spKey = spKey).getOrThrow()
                _isConnected.value = true
                sessionResolved = true
                val prefs = context.dataStore.data.first()
                SpotifyAccountSession(
                    isAuthenticated = true,
                    accountName = prefs[SpotifyAccountNameKey].orEmpty(),
                    accountAvatarUrl = prefs[SpotifyAccountAvatarUrlKey],
                )
            }

        suspend fun logout() {
            withContext(Dispatchers.IO) {
                context.dataStore.edit { prefs ->
                    prefs.remove(SpotifySpDcKey)
                    prefs.remove(SpotifySpKeyKey)
                    prefs.remove(SpotifyAccessTokenKey)
                    prefs.remove(SpotifyAccessTokenExpiresAtKey)
                    prefs.remove(SpotifyAccountNameKey)
                    prefs.remove(SpotifyAccountAvatarUrlKey)
                    prefs.remove(SpotifyLibraryPlaylistsCacheKey)
                }
                _playlists.value = emptyList()
                _errorMessage.value = null
                Spotify.accessToken = null
                _isConnected.value = false
                sessionResolved = true
                runCatching { clearWebAuthSession(context) }
                    .onFailure(::reportException)
            }
        }

        suspend fun refreshPlaylists(): List<SpotifyPlaylist> =
            withContext(Dispatchers.IO) {
                _isRefreshing.value = true
                _errorMessage.value = null
                try {
                    ensureAuthenticated()
                    refreshProfile()
                    val loaded = fetchAllPlaylists()
                    _playlists.value = loaded
                    context.dataStore.edit { prefs ->
                        prefs[SpotifyLibraryPlaylistsCacheKey] =
                            spotifyCacheJson.encodeToString(
                                ListSerializer(SpotifyPlaylist.serializer()),
                                loaded,
                            )
                    }
                    loaded
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    reportException(error)
                    _errorMessage.value = error.message
                    _playlists.value
                } finally {
                    _isRefreshing.value = false
                }
            }

        suspend fun playlist(playlistId: String): SpotifyPlaylist =
            withContext(Dispatchers.IO) {
                ensureAuthenticated()
                spotifyCallWithTokenRetry {
                    Spotify.playlist(playlistId).getOrThrow()
                }
            }

        suspend fun playlistTracks(playlistId: String): List<SpotifyTrack> =
            withContext(Dispatchers.IO) {
                ensureAuthenticated()
                val tracks = ArrayList<SpotifyTrack>()
                var offset = 0
                val limit = 50

                while (true) {
                    val page =
                        spotifyCallWithTokenRetry {
                            Spotify
                                .playlistTracks(
                                    playlistId = playlistId,
                                    limit = limit,
                                    offset = offset,
                                ).getOrThrow()
                        }
                    if (page.items.isEmpty()) break
                    val pageTracks = page.items.mapNotNull { it.track?.takeUnless(SpotifyTrack::isLocal) }
                    tracks += pageTracks
                    offset += page.items.size
                    if (offset >= page.total || page.items.size < limit) break
                }

                tracks
            }

        /**
         * How many tracks the account has in Liked Songs, or `null` when the account cannot answer.
         *
         * One page of one item, because the only thing a browsable folder needs to know is whether
         * it would be empty - a Liked Songs entry that opens onto nothing is the same broken promise
         * as a switch that opens onto nothing.
         */
        suspend fun likedSongsCount(): Int? =
            withContext(Dispatchers.IO) {
                runCatching {
                    ensureAuthenticated()
                    spotifyCallWithTokenRetry { Spotify.likedSongs(limit = 1, offset = 0).getOrThrow() }.total
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    reportException(error)
                    null
                }
            }

        /**
         * The account's Liked Songs as playable-track candidates.
         *
         * Paged the way [playlistTracks] is, and for the same reason: the caller decides how many it
         * can match, so this stops as soon as it has handed over enough.
         */
        suspend fun likedSongs(limit: Int = 50): List<SpotifyTrack> =
            withContext(Dispatchers.IO) {
                ensureAuthenticated()
                val tracks = ArrayList<SpotifyTrack>()
                var offset = 0
                val pageSize = 50

                while (tracks.size < limit) {
                    val page =
                        spotifyCallWithTokenRetry {
                            Spotify
                                .likedSongs(
                                    limit = pageSize,
                                    offset = offset,
                                ).getOrThrow()
                        }
                    if (page.items.isEmpty()) break
                    tracks += page.items.mapNotNull { saved -> saved.track.takeUnless(SpotifyTrack::isLocal) }
                    offset += page.items.size
                    if (offset >= page.total || page.items.size < pageSize) break
                }

                tracks.take(limit)
            }

        /**
         * Adds one track to a Spotify playlist, reporting whether the account accepted it.
         *
         * The caller supplies the URI because only the caller knows where the track came from - a
         * track Hush matched through YouTube has no Spotify id of its own and has to be looked up
         * first, which is a decision about the playing track, not about the account.
         */
        suspend fun addTrackToPlaylist(
            playlistId: String,
            trackUri: String,
        ): Boolean =
            withContext(Dispatchers.IO) {
                runCatching {
                    ensureAuthenticated()
                    spotifyCallWithTokenRetry {
                        Spotify
                            .addTracksToPlaylist(playlistId = playlistId, trackUris = listOf(trackUri))
                            .getOrThrow()
                    }
                }.fold(
                    onSuccess = { true },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        reportException(error)
                        _errorMessage.value = error.message
                        false
                    },
                )
            }

        /**
         * The Spotify URI of a track Hush is playing, or `null` when the account has no match.
         *
         * Used to save a track that was matched through YouTube into a Spotify playlist: without
         * the lookup the save button would work only on tracks that arrived through Spotify.
         */
        suspend fun findTrackUri(
            title: String,
            artist: String,
        ): String? {
            val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
            if (query.isBlank()) return null
            return withContext(Dispatchers.IO) {
                runCatching {
                    ensureAuthenticated()
                    spotifyCallWithTokenRetry {
                        Spotify.search(query = query, limit = 1).getOrThrow()
                    }
                }.getOrNull()?.tracks?.items?.firstOrNull()?.uri?.takeIf { it.isNotBlank() }
            }
        }

        private suspend fun ensureAuthenticated() {
            val prefs = context.dataStore.data.first()
            val token = prefs[SpotifyAccessTokenKey].orEmpty()
            val expiresAt = prefs[SpotifyAccessTokenExpiresAtKey] ?: 0L
            if (token.isNotBlank() && expiresAt > System.currentTimeMillis() + TOKEN_EXPIRY_GRACE_MS) {
                Spotify.accessToken = token
                return
            }

            val spDc = prefs[SpotifySpDcKey].orEmpty()
            if (spDc.isBlank()) {
                throw IllegalStateException(context.getString(R.string.spotify_not_connected))
            }
            refreshAccessToken(spDc = spDc, spKey = prefs[SpotifySpKeyKey].orEmpty()).getOrThrow()
        }

        private suspend fun refreshAccessToken(
            spDc: String,
            spKey: String,
        ): Result<Unit> =
            SpotifyAuth
                .fetchAccessToken(spDc = spDc, spKey = spKey)
                .mapCatching { token ->
                    Spotify.accessToken = token.accessToken
                    context.dataStore.edit { prefs ->
                        prefs[SpotifyAccessTokenKey] = token.accessToken
                        prefs[SpotifyAccessTokenExpiresAtKey] = token.accessTokenExpirationTimestampMs
                    }
                    refreshProfile()
                }

        private suspend fun refreshProfile() {
            Spotify
                .me()
                .onSuccess { user ->
                    context.dataStore.edit { prefs ->
                        prefs[SpotifyAccountNameKey] = user.displayName.orEmpty()
                        user.images
                            .firstOrNull()
                            ?.url
                            ?.let { prefs[SpotifyAccountAvatarUrlKey] = it }
                            ?: prefs.remove(SpotifyAccountAvatarUrlKey)
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                }
        }

        private suspend fun fetchAllPlaylists(): List<SpotifyPlaylist> {
            val playlists = ArrayList<SpotifyPlaylist>()
            var offset = 0
            val limit = 50

            while (true) {
                val page =
                    spotifyCallWithTokenRetry {
                        Spotify.myPlaylists(limit = limit, offset = offset).getOrThrow()
                    }
                if (page.items.isEmpty()) break
                playlists +=
                    page.items.map { playlist ->
                        if (playlist.tracks?.total != null) {
                            playlist
                        } else {
                            playlistTrackCount(playlist.id)
                                ?.let { playlist.copy(tracks = SpotifyPlaylistTracksRef(total = it)) }
                                ?: playlist
                        }
                    }
                offset += page.items.size
                if (offset >= page.total || page.items.size < limit) break
            }

            return playlists
        }

        private suspend fun playlistTrackCount(playlistId: String): Int? =
            try {
                spotifyCallWithTokenRetry {
                    Spotify
                        .playlistTracks(
                            playlistId = playlistId,
                            limit = 1,
                            offset = 0,
                        ).getOrThrow()
                }.total
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportException(error)
                null
            }

        private suspend fun <T> spotifyCallWithTokenRetry(block: suspend () -> T): T =
            runCatching { block() }
                .getOrElse { error ->
                    if ((error as? Spotify.SpotifyException)?.statusCode != 401) throw error
                    val prefs = context.dataStore.data.first()
                    val spDc = prefs[SpotifySpDcKey].orEmpty()
                    if (spDc.isBlank()) throw error
                    refreshAccessToken(spDc = spDc, spKey = prefs[SpotifySpKeyKey].orEmpty()).getOrThrow()
                    block()
                }

        companion object {
            private const val TOKEN_EXPIRY_GRACE_MS = 60_000L
            private val spotifyCacheJson =
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                }
        }
    }

data class SpotifyAccountSession(
    val isAuthenticated: Boolean = false,
    val accountName: String = "",
    val accountAvatarUrl: String? = null,
)
