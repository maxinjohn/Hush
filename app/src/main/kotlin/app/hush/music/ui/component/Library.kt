/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.CoroutineScope
import app.hush.music.R
import app.hush.music.db.entities.Album
import app.hush.music.db.entities.Artist
import app.hush.music.db.entities.Playlist
import app.hush.music.innertube.models.PlaylistItem
import app.hush.music.innertube.models.WatchEndpoint
import app.hush.music.ui.menu.AlbumMenu
import app.hush.music.ui.menu.ArtistMenu
import app.hush.music.ui.menu.PlaylistMenu
import app.hush.music.ui.menu.YouTubePlaylistMenu
import app.hush.music.ui.theme.HushDesign
import app.hush.music.ui.theme.hushCombinedPressable
import app.hush.music.ui.theme.hushPressable

@Composable
fun LibraryArtistListItem(
    navController: NavController,
    menuState: MenuState,
    coroutineScope: CoroutineScope,
    artist: Artist,
    modifier: Modifier = Modifier,
) = ArtistListItem(
    artist = artist,
    trailingContent = {
        androidx.compose.material3.IconButton(
            onClick = {
                menuState.show {
                    ArtistMenu(
                        originalArtist = artist,
                        coroutineScope = coroutineScope,
                        onDismiss = menuState::dismiss,
                    )
                }
            },
        ) {
            Icon(
                painter = painterResource(R.drawable.more_vert),
                contentDescription = null,
            )
        }
    },
    modifier =
        modifier
            .fillMaxWidth()
            .hushPressable(
                onClick = { navController.navigate("artist/${artist.id}") },
                pressScale = HushDesign.RowPressScale,
            ),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryArtistGridItem(
    navController: NavController,
    menuState: MenuState,
    coroutineScope: CoroutineScope,
    artist: Artist,
    modifier: Modifier = Modifier,
) = ArtistGridItem(
    artist = artist,
    fillMaxWidth = true,
    modifier =
        modifier
            .fillMaxWidth()
            .hushCombinedPressable(
                onClick = {
                    navController.navigate("artist/${artist.id}")
                },
                onLongClick = {
                    menuState.show {
                        ArtistMenu(
                            originalArtist = artist,
                            coroutineScope = coroutineScope,
                            onDismiss = menuState::dismiss,
                        )
                    }
                },
                pressScale = HushDesign.RowPressScale,
            ),
)

@Composable
fun LibraryAlbumListItem(
    modifier: Modifier = Modifier,
    navController: NavController,
    menuState: MenuState,
    album: Album,
    isActive: Boolean = false,
    isPlaying: Boolean = false,
) = AlbumListItem(
    album = album,
    isActive = isActive,
    isPlaying = isPlaying,
    trailingContent = {
        androidx.compose.material3.IconButton(
            onClick = {
                menuState.show {
                    AlbumMenu(
                        originalAlbum = album,
                        navController = navController,
                        onDismiss = menuState::dismiss,
                    )
                }
            },
        ) {
            Icon(
                painter = painterResource(R.drawable.more_vert),
                contentDescription = null,
            )
        }
    },
    modifier =
        modifier
            .fillMaxWidth()
            .hushPressable(
                onClick = { navController.navigate("album/${album.id}") },
                pressScale = HushDesign.RowPressScale,
            ),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryAlbumGridItem(
    modifier: Modifier = Modifier,
    navController: NavController,
    menuState: MenuState,
    coroutineScope: CoroutineScope,
    album: Album,
    isActive: Boolean = false,
    isPlaying: Boolean = false,
) = AlbumGridItem(
    album = album,
    isActive = isActive,
    isPlaying = isPlaying,
    coroutineScope = coroutineScope,
    fillMaxWidth = true,
    modifier =
        modifier
            .fillMaxWidth()
            .hushCombinedPressable(
                onClick = {
                    navController.navigate("album/${album.id}")
                },
                onLongClick = {
                    menuState.show {
                        AlbumMenu(
                            originalAlbum = album,
                            navController = navController,
                            onDismiss = menuState::dismiss,
                        )
                    }
                },
                pressScale = HushDesign.RowPressScale,
            ),
)

@Composable
fun LibraryPlaylistListItem(
    navController: NavController,
    menuState: MenuState,
    coroutineScope: CoroutineScope,
    playlist: Playlist,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(26.dp),
    showDragHandle: Boolean = false,
    dragHandleModifier: Modifier = Modifier,
) {
    val trailing: @Composable RowScope.() -> Unit = {
        if (showDragHandle) {
            androidx.compose.material3.IconButton(
                onClick = { },
                modifier = dragHandleModifier,
            ) {
                Icon(
                    painter = painterResource(R.drawable.drag_handle),
                    contentDescription = null,
                )
            }
        }
        androidx.compose.material3.IconButton(
            onClick = {
                menuState.show {
                    if (playlist.playlist.isEditable || playlist.songCount != 0) {
                        PlaylistMenu(
                            playlist = playlist,
                            coroutineScope = coroutineScope,
                            onDismiss = menuState::dismiss,
                        )
                    } else {
                        playlist.playlist.browseId?.let { browseId ->
                            YouTubePlaylistMenu(
                                playlist =
                                    PlaylistItem(
                                        id = browseId,
                                        title = playlist.playlist.name,
                                        author = null,
                                        songCountText = null,
                                        thumbnail = playlist.thumbnails.getOrNull(0) ?: "",
                                        playEndpoint =
                                            WatchEndpoint(
                                                playlistId = browseId,
                                                params = playlist.playlist.playEndpointParams,
                                            ),
                                        shuffleEndpoint =
                                            WatchEndpoint(
                                                playlistId = browseId,
                                                params = playlist.playlist.shuffleEndpointParams,
                                            ),
                                        radioEndpoint =
                                            WatchEndpoint(
                                                playlistId = "RDAMPL$browseId",
                                                params = playlist.playlist.radioEndpointParams,
                                            ),
                                        isEditable = false,
                                    ),
                                coroutineScope = coroutineScope,
                                onDismiss = menuState::dismiss,
                            )
                        }
                    }
                }
            },
        ) {
            Icon(
                painter = painterResource(R.drawable.more_vert),
                contentDescription = null,
            )
        }
    }

    val openPlaylist: () -> Unit = {
        if (
            !playlist.playlist.isEditable &&
            playlist.songCount == 0 &&
            playlist.playlist.remoteSongCount != 0
        ) {
            navController.navigate("online_playlist/${playlist.playlist.browseId}")
        } else {
            navController.navigate("local_playlist/${playlist.id}")
        }
    }

    LibraryPlaylistFeatureCard(
        playlist = playlist,
        shape = shape,
        trailingContent = trailing,
        modifier =
            modifier
                .fillMaxWidth()
                .focusable()
                .hushPressable(
                    onClick = openPlaylist,
                    pressScale = HushDesign.RowPressScale,
                ),
    )
}
