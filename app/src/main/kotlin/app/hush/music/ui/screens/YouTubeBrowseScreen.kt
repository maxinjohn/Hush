/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:Suppress("UNUSED_EXPRESSION")

package app.hush.music.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import app.hush.music.ui.theme.hushCombinedPressable
import app.hush.music.ui.theme.hushPressable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import app.hush.music.LocalPlayerAwareWindowInsets
import app.hush.music.LocalPlayerConnection
import app.hush.music.R
import app.hush.music.constants.ListItemHeight
import app.hush.music.extensions.togglePlayPause
import app.hush.music.innertube.models.AlbumItem
import app.hush.music.innertube.models.ArtistItem
import app.hush.music.innertube.models.EpisodeItem
import app.hush.music.innertube.models.PodcastItem
import app.hush.music.innertube.models.PlaylistItem
import app.hush.music.innertube.models.SongItem
import app.hush.music.extensions.toMediaItem
import app.hush.music.models.toMediaMetadata
import app.hush.music.playback.queues.ListQueue
import app.hush.music.playback.queues.YouTubeQueue
import app.hush.music.ui.component.IconButton
import app.hush.music.ui.component.LocalMenuState
import app.hush.music.ui.component.NavigationTitle
import app.hush.music.ui.component.YouTubeGridItem
import app.hush.music.ui.component.YouTubeListItem
import app.hush.music.ui.component.shimmer.GridItemPlaceHolder
import app.hush.music.ui.component.shimmer.ShimmerHost
import app.hush.music.ui.component.shimmer.TextPlaceholder
import app.hush.music.ui.menu.YouTubeAlbumMenu
import app.hush.music.ui.menu.YouTubeArtistMenu
import app.hush.music.ui.menu.YouTubePlaylistMenu
import app.hush.music.ui.menu.YouTubeSongMenu
import app.hush.music.ui.theme.HushAmbientBackground
import app.hush.music.ui.utils.SnapLayoutInfoProvider
import app.hush.music.ui.utils.backToMain
import app.hush.music.ui.utils.rememberHorizontalCarouselMetrics
import app.hush.music.viewmodels.YouTubeBrowseViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun YouTubeBrowseScreen(
    navController: NavController,
    viewModel: YouTubeBrowseViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val browseResult by viewModel.result.collectAsState()

    val coroutineScope = rememberCoroutineScope()

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
    ) {
        HushAmbientBackground(modifier = Modifier.align(Alignment.TopCenter))

        val carouselMetrics = rememberHorizontalCarouselMetrics(maxWidth)
        val horizontalLazyGridItemWidthFactor = carouselMetrics.widthFactor
        val horizontalLazyGridItemWidth = carouselMetrics.itemWidth
        val lazyGridState = rememberLazyGridState()
        val snapLayoutInfoProvider =
            remember(lazyGridState, horizontalLazyGridItemWidthFactor) {
                SnapLayoutInfoProvider(
                    lazyGridState = lazyGridState,
                    positionInLayout = { layoutSize, itemSize ->
                        (layoutSize * horizontalLazyGridItemWidthFactor / 2f - itemSize / 2f)
                    },
                )
            }
        LazyColumn(
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        ) {
            if (browseResult == null) {
                item {
                    ShimmerHost(
                        modifier = Modifier.animateItem(),
                    ) {
                        TextPlaceholder(
                            height = 36.dp,
                            modifier =
                                Modifier
                                    .padding(12.dp)
                                    .width(250.dp),
                        )
                        LazyRow {
                            items(4) {
                                GridItemPlaceHolder()
                            }
                        }
                    }
                }
            }

            browseResult?.items?.fastForEach {
                if (it.items.isNotEmpty()) {
                    it.title?.let { title ->
                        item {
                            NavigationTitle(title)
                        }
                    }
                    if (it.items.all { item -> item is SongItem }) {
                        item {
                            LazyHorizontalGrid(
                                state = lazyGridState,
                                rows = GridCells.Fixed(4),
                                flingBehavior = rememberSnapFlingBehavior(snapLayoutInfoProvider),
                                contentPadding =
                                    WindowInsets.systemBars
                                        .only(WindowInsetsSides.Horizontal)
                                        .asPaddingValues(),
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(ListItemHeight * 4)
                                        .animateItem(),
                            ) {
                                items(
                                    items = it.items,
                                ) { song ->
                                    Box(Modifier.width(350.dp)) {
                                        YouTubeListItem(
                                            item = song as SongItem,
                                            isActive = mediaMetadata?.id == song.id,
                                            isPlaying = isPlaying,
                                            isSwipeable = false,
                                            trailingContent = {
                                                IconButton(
                                                    onClick = {
                                                        menuState.show {
                                                            YouTubeSongMenu(
                                                                song = song,
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
                                                Modifier
                                                    .hushPressable(onClick = {
                                                        if (song.id == mediaMetadata?.id) {
                                                            playerConnection.player.togglePlayPause()
                                                        } else {
                                                            playerConnection.playQueue(
                                                                YouTubeQueue.radio(
                                                                    song.toMediaMetadata(),
                                                                ),
                                                            )
                                                        }
                                                    }).animateItem(),
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        item {
                            LazyRow {
                                items(
                                    items = it.items,
                                ) { item ->
                                    YouTubeGridItem(
                                        item = item,
                                        isActive =
                                            when (item) {
                                                is AlbumItem -> mediaMetadata?.album?.id == item.id
                                                else -> false
                                            },
                                        isPlaying = isPlaying,
                                        coroutineScope = coroutineScope,
                                        modifier =
                                            Modifier
                                                .hushCombinedPressable(
                                                    onClick = {
                                                        when (item) {
                                                            is AlbumItem -> navController.navigate("album/${item.id}")
                                                            is ArtistItem -> navController.navigate("artist/${item.id}")
                                                            is PlaylistItem -> navController.navigate("online_playlist/${item.id}")
                                                            is PodcastItem -> navController.navigate("online_podcast/${item.id}")
                                                            is EpisodeItem -> playerConnection.playQueue(ListQueue(title = item.title, items = listOf(item.asSongItem().toMediaMetadata().toMediaItem())))
                                                            else -> item
                                                        }
                                                    },
                                                    onLongClick = {
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        menuState.show {
                                                            when (item) {
                                                                is SongItem -> {
                                                                    YouTubeSongMenu(
                                                                        song = item,
                                                                        navController = navController,
                                                                        onDismiss = menuState::dismiss,
                                                                    )
                                                                }

                                                                is AlbumItem -> {
                                                                    YouTubeAlbumMenu(
                                                                        albumItem = item,
                                                                        navController = navController,
                                                                        onDismiss = menuState::dismiss,
                                                                    )
                                                                }

                                                                is ArtistItem -> {
                                                                    YouTubeArtistMenu(
                                                                        artist = item,
                                                                        onDismiss = menuState::dismiss,
                                                                    )
                                                                }

                                                                is PlaylistItem -> {
                                                                    YouTubePlaylistMenu(
                                                                        playlist = item,
                                                                        coroutineScope = coroutineScope,
                                                                        onDismiss = menuState::dismiss,
                                                                    )
                                                                }

                                                            is PodcastItem -> {
                                                                YouTubePlaylistMenu(
                                                                    playlist = item.asPlaylistItem(),
                                                                    coroutineScope = coroutineScope,
                                                                    onDismiss = menuState::dismiss,
                                                                )
                                                            }

                                                            is EpisodeItem -> {
                                                                YouTubeSongMenu(
                                                                    song = item.asSongItem(),
                                                                    navController = navController,
                                                                    onDismiss = menuState::dismiss,
                                                                )
                                                            }
                                                            }
                                                        }
                                                    },
                                                ).animateItem(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    TopAppBar(
        title = { Text(browseResult?.title.orEmpty()) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}
