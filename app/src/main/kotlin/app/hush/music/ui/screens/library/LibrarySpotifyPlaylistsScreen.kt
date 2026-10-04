/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.hush.music.LocalPlayerAwareWindowInsets
import app.hush.music.R
import app.hush.music.extensions.matchesNormalizedQuery
import app.hush.music.extensions.normalizeForSearch
import app.hush.music.spotify.SpotifyLikedSongs
import app.hush.music.spotify.SpotifyLibraryViewModel
import app.hush.music.ui.component.ExpressivePullToRefreshBox
import app.hush.music.ui.component.SpotifyLibraryPlaylistListItem

@Composable
fun LibrarySpotifyPlaylistsScreen(
    navController: NavController,
    searchQuery: String = "",
    viewModel: SpotifyLibraryViewModel = hiltViewModel(),
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val likedSongsTotal by viewModel.likedSongsTotal.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    // Listed ahead of the playlists the way the car's Spotify group orders it, and only when the
    // account has something saved: an entry that opens onto nothing is the same broken promise as
    // a switch that opens onto nothing.
    val likedSongsTitle = stringResource(R.string.liked_songs)
    val likedSongs =
        likedSongsTotal
            ?.takeIf { total -> total > 0 }
            ?.let { total -> SpotifyLikedSongs.playlist(displayName = likedSongsTitle, total = total) }

    val normalizedQuery = remember(searchQuery) { searchQuery.normalizeForSearch() }

    val visiblePlaylists =
        remember(playlists, normalizedQuery) {
            if (normalizedQuery.isBlank()) {
                playlists
            } else {
                playlists.filter { playlist -> matchesNormalizedQuery(normalizedQuery, playlist.name) }
            }
        }

    val visibleLikedSongs =
        likedSongs?.takeIf { playlist -> matchesNormalizedQuery(normalizedQuery, playlist.name) }
    val playerAwareBottomPadding =
        LocalPlayerAwareWindowInsets.current
            .only(WindowInsetsSides.Bottom)
            .asPaddingValues()
            .calculateBottomPadding() + 12.dp

    ExpressivePullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = viewModel::refreshPlaylists,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = rememberLazyListState(),
            contentPadding =
                PaddingValues(
                    start = 24.dp,
                    end = 24.dp,
                    bottom = playerAwareBottomPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (visiblePlaylists.isEmpty() && visibleLikedSongs == null) {
                item(key = "spotify_empty", contentType = "spotify_empty") {
                    LibraryEmptyState(
                        iconRes = R.drawable.spotify_icon,
                        actionLabelRes = R.string.refresh,
                        onAction = viewModel::refreshPlaylists,
                    )
                }
            }

            visibleLikedSongs?.let { liked ->
                item(key = liked.id, contentType = "spotify_playlist") {
                    SpotifyLibraryPlaylistListItem(
                        playlist = liked,
                        navController = navController,
                    )
                }
            }

            items(
                items = visiblePlaylists,
                key = { playlist -> playlist.id },
                contentType = { "spotify_playlist" },
            ) { playlist ->
                SpotifyLibraryPlaylistListItem(
                    playlist = playlist,
                    navController = navController,
                )
            }
        }
    }
}
