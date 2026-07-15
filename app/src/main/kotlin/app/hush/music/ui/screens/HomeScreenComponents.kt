/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import app.hush.music.ui.theme.hushCombinedPressable
import app.hush.music.ui.theme.hushCarouselContentParallax
import app.hush.music.ui.theme.hushCarouselLiveParallax
import app.hush.music.ui.theme.hushHomeCarouselCard
import app.hush.music.ui.theme.hushHomeRowCard
import app.hush.music.ui.theme.rememberHushAccentGradient
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.HorizontalCenteredHeroCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import app.hush.music.R
import app.hush.music.constants.GridThumbnailHeight
import app.hush.music.constants.ListItemHeight
import app.hush.music.constants.ListThumbnailSize
import app.hush.music.constants.QuickPicksDisplayMode
import app.hush.music.constants.ShowSpotifyPlaylistsKey
import app.hush.music.constants.SpotifyAccountAvatarUrlKey
import app.hush.music.constants.SpotifyAccountNameKey
import app.hush.music.constants.LibraryFilter
import app.hush.music.constants.ThumbnailCornerRadius
import app.hush.music.db.entities.Album
import app.hush.music.db.entities.Artist
import app.hush.music.db.entities.LocalItem
import app.hush.music.db.entities.Playlist
import app.hush.music.db.entities.Song
import app.hush.music.extensions.toMediaItem
import app.hush.music.extensions.togglePlayPause
import app.hush.music.innertube.models.AlbumItem
import app.hush.music.innertube.models.ArtistItem
import app.hush.music.innertube.models.EpisodeItem
import app.hush.music.innertube.models.PodcastItem
import app.hush.music.innertube.models.PlaylistItem
import app.hush.music.innertube.models.SongItem
import app.hush.music.innertube.models.WatchEndpoint
import app.hush.music.innertube.models.YTItem
import app.hush.music.innertube.pages.HomePage
import app.hush.music.models.MediaMetadata
import app.hush.music.models.SimilarRecommendation
import app.hush.music.models.toMediaMetadata
import app.hush.music.playback.PlayerConnection
import app.hush.music.playback.queues.ListQueue
import app.hush.music.playback.queues.YouTubeQueue
import app.hush.music.ui.component.AlbumGridItem
import app.hush.music.ui.component.ArtistGridItem
import app.hush.music.ui.component.LocalMenuState
import app.hush.music.ui.component.MenuState
import app.hush.music.ui.component.NavigationTitle
import app.hush.music.ui.component.SongGridItem
import app.hush.music.ui.component.SongListItem
import app.hush.music.ui.component.SpeedDialGridItem
import app.hush.music.ui.component.YouTubeGridItem
import app.hush.music.ui.component.shimmer.GridItemPlaceHolder
import app.hush.music.ui.component.shimmer.ShimmerHost
import app.hush.music.ui.component.shimmer.TextPlaceholder
import app.hush.music.ui.menu.AlbumMenu
import app.hush.music.ui.menu.ArtistMenu
import app.hush.music.ui.menu.PlaylistMenu
import app.hush.music.ui.menu.SongMenu
import app.hush.music.ui.menu.YouTubeAlbumMenu
import app.hush.music.ui.menu.YouTubeArtistMenu
import app.hush.music.ui.menu.YouTubePlaylistMenu
import app.hush.music.ui.menu.YouTubeSongMenu
import app.hush.music.spotify.SpotifyLibraryViewModel
import app.hush.music.spotify.models.SpotifyPlaylist
import app.hush.music.ui.component.SpotifyPlaylistCarouselCard
import app.hush.music.ui.utils.rememberHorizontalCarouselMetrics
import app.hush.music.ui.utils.rememberLazyRowCarouselScroll
import app.hush.music.ui.utils.rememberSmoothPagerFlingBehavior
import app.hush.music.ui.utils.rememberSmoothSnapFlingBehavior
import app.hush.music.utils.rememberPreference
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random
import app.hush.music.ui.utils.SnapLayoutInfoProvider as buildSnapLayoutInfoProvider
import app.hush.music.ui.utils.heroCarouselHeight
import app.hush.music.ui.utils.rememberHeroCarouselMetrics

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeCategoryChips(
    chips: List<HomePage.Chip>,
    selectedChip: HomePage.Chip?,
    onChipSelected: (HomePage.Chip) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        chips.forEach { chip ->
            val selected = chip == selectedChip
            FilterChip(
                selected = selected,
                onClick = { onChipSelected(chip) },
                label = {
                    Text(
                        text = chip.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon =
                    if (selected) {
                        {
                            Icon(
                                painter = painterResource(R.drawable.done),
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else {
                        null
                    },
                shapes = FilterChipDefaults.shapes(),
                colors =
                    FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f),
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.94f),
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                border = null,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    thumbnail: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        thumbnail?.invoke()
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.weight(1f),
        ) {
            label?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLargeEmphasized,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onClick != null) {
            Icon(
                painter = painterResource(R.drawable.arrow_forward),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun QuickPicksSection(
    quickPicks: List<Song>,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    displayMode: QuickPicksDisplayMode,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    modifier: Modifier = Modifier,
) {
    val distinctQuickPicks = remember(quickPicks) { quickPicks.distinctBy { it.id } }
    val context = LocalContext.current

    when (displayMode) {
        QuickPicksDisplayMode.CARD -> {
            BoxWithConstraints(
                modifier =
                    modifier
                        .fillMaxWidth(),
            ) {
                val heroMetrics = rememberHeroCarouselMetrics(maxWidth)
                val carouselHeight = heroMetrics.heroCarouselHeight()
                val carouselState = rememberCarouselState { distinctQuickPicks.size }
                val heroSnapSpec =
                    remember {
                        spring<Float>(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessLow,
                        )
                    }
                HorizontalCenteredHeroCarousel(
                    state = carouselState,
                    maxItemWidth = heroMetrics.itemWidth,
                    itemSpacing = 10.dp,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    flingBehavior =
                        CarouselDefaults.multiBrowseFlingBehavior(
                            state = carouselState,
                            snapAnimationSpec = heroSnapSpec,
                        ),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(carouselHeight),
                ) { index ->
                val song = distinctQuickPicks[index]
                val isActive = song.id == mediaMetadata?.id
                val drawInfo = carouselItemDrawInfo
                val artworkRequest =
                    remember(song.id, song.song.thumbnailUrl, context) {
                        ImageRequest
                            .Builder(context)
                            .data(song.song.thumbnailUrl)
                            .crossfade(false)
                            .build()
                    }

                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .hushCarouselLiveParallax(drawInfo)
                            .hushHomeCarouselCard(shape = MaterialTheme.shapes.extraLarge)
                            .maskClip(MaterialTheme.shapes.extraLarge)
                            .focusable()
                            .hushCombinedPressable(
                                onClick = {
                                    if (isActive) {
                                        playerConnection.player.togglePlayPause()
                                    } else {
                                        playerConnection.playQueue(
                                            if (song.song.isLocal) {
                                                ListQueue(items = listOf(song.toMediaItem()))
                                            } else {
                                                YouTubeQueue.radio(song.toMediaMetadata())
                                            },
                                        )
                                    }
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    menuState.show {
                                        SongMenu(
                                            originalSong = song,
                                            navController = navController,
                                            onDismiss = menuState::dismiss,
                                        )
                                    }
                                },
                            ),
                ) {
                    AsyncImage(
                        model = artworkRequest,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .hushCarouselContentParallax(drawInfo),
                    )

                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors =
                                            listOf(
                                                Color.Transparent,
                                                Color.Transparent,
                                                Color.Black.copy(alpha = 0.7f),
                                            ),
                                    ),
                                ),
                    )

                    if (isActive && isPlaying) {
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(12.dp)
                                    .size(32.dp)
                                    .background(
                                        rememberHushAccentGradient(),
                                        CircleShape,
                                    ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.volume_up),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    Column(
                        modifier =
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(16.dp),
                    ) {
                        Text(
                            text = song.song.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = song.artists.joinToString { it.name },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            }
        }

        QuickPicksDisplayMode.LIST -> {
            BoxWithConstraints(
                modifier = modifier.fillMaxWidth(),
            ) {
                val carouselMetrics = rememberHorizontalCarouselMetrics(maxWidth)
                val widthFactor = carouselMetrics.widthFactor
                val itemWidth = carouselMetrics.itemWidth
                val lazyGridState = rememberLazyGridState()
                val snapLayoutInfoProvider =
                    remember(lazyGridState, widthFactor) {
                        buildSnapLayoutInfoProvider(
                            lazyGridState = lazyGridState,
                            positionInLayout = { layoutSize, itemSize ->
                                layoutSize * widthFactor / 2f - itemSize / 2f
                            },
                        )
                    }
                LazyHorizontalGrid(
                    state = lazyGridState,
                    rows = GridCells.Fixed(4),
                    flingBehavior = rememberSmoothSnapFlingBehavior(snapLayoutInfoProvider),
                    contentPadding =
                        WindowInsets.systemBars
                            .only(WindowInsetsSides.Horizontal)
                            .asPaddingValues(),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(ListItemHeight * 4),
                ) {
                    items(
                        items = distinctQuickPicks,
                        key = { it.id },
                        contentType = { "quick_pick_song" },
                    ) { song ->
                        SongListItem(
                            song = song,
                            showInLibraryIcon = true,
                            isActive = song.id == mediaMetadata?.id,
                            isPlaying = isPlaying,
                            isSwipeable = false,
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        menuState.show {
                                            SongMenu(
                                                originalSong = song,
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
                                    .width(itemWidth)
                                    .hushCombinedPressable(
                                        onClick = {
                                            if (song.id == mediaMetadata?.id) {
                                                playerConnection.player.togglePlayPause()
                                            } else {
                                                playerConnection.playQueue(
                                                    if (song.song.isLocal) {
                                                        ListQueue(items = listOf(song.toMediaItem()))
                                                    } else {
                                                        YouTubeQueue.radio(song.toMediaMetadata())
                                                    },
                                                )
                                            }
                                        },
                                        onLongClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            menuState.show {
                                                SongMenu(
                                                    originalSong = song,
                                                    navController = navController,
                                                    onDismiss = menuState::dismiss,
                                                )
                                            }
                                        },
                                    ),
                        )
                    }
                }
            }
        }
    }
}

private const val SpeedDialGridRows = 3
private const val SpeedDialGridColumns = 3
private const val SpeedDialItemsPerPage = SpeedDialGridRows * SpeedDialGridColumns

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SpeedDialSection(
    speedDialItems: List<LocalItem>,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    data class SpeedDialTile(
        val key: String,
        val localItem: LocalItem?,
        val ytItem: YTItem?,
    )

    val distinctSpeedDial =
        remember(speedDialItems) {
            speedDialItems
                .distinctBy {
                    when (it) {
                        is Song -> "song_${it.id}"
                        is Album -> "album_${it.id}"
                        is Artist -> "artist_${it.id}"
                        is Playlist -> "playlist_${it.id}"
                    }
                }.take(24)
        }
    val speedDialSongs = remember(distinctSpeedDial) { distinctSpeedDial.filterIsInstance<Song>() }
    val speedDialSongIndexById =
        remember(speedDialSongs) {
            speedDialSongs.mapIndexed { index, song -> song.id to index }.toMap()
        }
    val spacing = 10.dp

    val tiles =
        remember(distinctSpeedDial) {
            buildList {
                distinctSpeedDial.forEach { localItem ->
                    val key =
                        when (localItem) {
                            is Song -> "song_${localItem.id}"
                            is Album -> "album_${localItem.id}"
                            is Artist -> "artist_${localItem.id}"
                            is Playlist -> "playlist_${localItem.id}"
                        }
                    val ytItem =
                        when (localItem) {
                            is Song -> {
                                SongItem(
                                    id = localItem.id,
                                    title = localItem.title,
                                    artists =
                                        localItem.artists.map {
                                            app.hush.music.innertube.models
                                                .Artist(name = it.name, id = it.id)
                                        },
                                    thumbnail = localItem.song.thumbnailUrl.orEmpty(),
                                    explicit = localItem.song.explicit,
                                )
                            }

                            is Album -> {
                                AlbumItem(
                                    browseId = localItem.id,
                                    playlistId = localItem.album.playlistId.orEmpty(),
                                    title = localItem.title,
                                    artists =
                                        localItem.artists.map {
                                            app.hush.music.innertube.models
                                                .Artist(name = it.name, id = it.id)
                                        },
                                    year = localItem.album.year,
                                    thumbnail = localItem.album.thumbnailUrl.orEmpty(),
                                )
                            }

                            is Artist -> {
                                ArtistItem(
                                    id = localItem.id,
                                    title = localItem.title,
                                    thumbnail = localItem.artist.thumbnailUrl,
                                    channelId = localItem.artist.channelId,
                                    playEndpoint = null,
                                    shuffleEndpoint = null,
                                    radioEndpoint = null,
                                )
                            }

                            is Playlist -> {
                                PlaylistItem(
                                    id = localItem.id,
                                    title = localItem.title,
                                    author = null,
                                    songCountText = localItem.songCount.toString(),
                                    thumbnail = localItem.thumbnails.firstOrNull(),
                                    playEndpoint = null,
                                    shuffleEndpoint = null,
                                    radioEndpoint = null,
                                    isEditable = localItem.playlist.isEditable,
                                )
                            }
                        }
                    add(SpeedDialTile(key = key, localItem = localItem, ytItem = ytItem))
                }
                add(SpeedDialTile(key = "random", localItem = null, ytItem = null))
            }
        }
    val tilePages =
        remember(tiles) {
            tiles.chunked(SpeedDialItemsPerPage)
        }
    val visibleGridRows =
        remember(tilePages) {
            if (tilePages.size == 1) {
                ((tilePages.first().size + SpeedDialGridColumns - 1) / SpeedDialGridColumns)
                    .coerceIn(1, SpeedDialGridRows)
            } else {
                SpeedDialGridRows
            }
        }
    val pagerState =
        rememberPagerState(
            pageCount = { tilePages.size },
        )

    fun playSpeedDialQueue(startIndex: Int) {
        if (speedDialSongs.isEmpty()) return
        playerConnection.playQueue(
            ListQueue(
                title = context.getString(R.string.speed_dial),
                items = speedDialSongs.map { it.toMediaItem() },
                startIndex = startIndex,
            ),
        )
    }

    val selectedDotIndex by
        remember(pagerState, tilePages) {
            derivedStateOf {
                (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                    .roundToInt()
                    .coerceIn(0, (tilePages.size - 1).coerceAtLeast(0))
            }
        }
    val motionScheme = MaterialTheme.motionScheme

    Column(modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .padding(horizontal = 8.dp)
                    .fillMaxWidth(),
        ) {
            val tileSize = (maxWidth - spacing * (SpeedDialGridColumns - 1)) / SpeedDialGridColumns
            val gridHeight = (tileSize * visibleGridRows) + (spacing * (visibleGridRows - 1))

            HorizontalPager(
                state = pagerState,
                pageSize = PageSize.Fill,
                pageSpacing = spacing,
                flingBehavior = rememberSmoothPagerFlingBehavior(pagerState),
                key = { page -> tilePages[page].firstOrNull()?.key ?: "speed_dial_page_$page" },
                verticalAlignment = Alignment.Top,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(gridHeight),
            ) { page ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(spacing),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    tilePages[page]
                        .chunked(SpeedDialGridColumns)
                        .forEach { rowTiles ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(spacing),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                rowTiles.forEach { tile ->
                                    val localItem = tile.localItem
                                    val ytItem = tile.ytItem
                                    if (localItem == null || ytItem == null) {
                                        SpeedDialRandomTile(
                                            onClick = {
                                                if (speedDialSongs.isNotEmpty()) {
                                                    playSpeedDialQueue(Random.nextInt(speedDialSongs.size))
                                                }
                                            },
                                            modifier = Modifier.size(tileSize),
                                        )
                                    } else {
                                        val isActive =
                                            when (localItem) {
                                                is Song -> localItem.id == mediaMetadata?.id
                                                is Album -> localItem.id == mediaMetadata?.album?.id
                                                is Artist -> false
                                                is Playlist -> false
                                            }
                                        val songIndex =
                                            if (localItem is Song) speedDialSongIndexById[localItem.id] ?: 0 else 0

                                        Box(
                                            modifier =
                                                Modifier
                                                    .size(tileSize)
                                                    .clip(MaterialTheme.shapes.large)
                                                    .focusable()
                                                    .hushCombinedPressable(
                                                        onClick = {
                                                            when (localItem) {
                                                                is Song -> {
                                                                    if (isActive) {
                                                                        playerConnection.player.togglePlayPause()
                                                                    } else {
                                                                        playSpeedDialQueue(songIndex)
                                                                    }
                                                                }

                                                                is Album -> {
                                                                    navController.navigate("album/${localItem.id}")
                                                                }

                                                                is Artist -> {
                                                                    navController.navigate("artist/${localItem.id}")
                                                                }

                                                                is Playlist -> {
                                                                    navController.navigate("local_playlist/${localItem.id}")
                                                                }
                                                            }
                                                        },
                                                        onLongClick = {
                                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            menuState.show {
                                                                when (localItem) {
                                                                    is Song -> {
                                                                        SongMenu(
                                                                            originalSong = localItem,
                                                                            navController = navController,
                                                                            onDismiss = menuState::dismiss,
                                                                        )
                                                                    }

                                                                    is Album -> {
                                                                        AlbumMenu(
                                                                            originalAlbum = localItem,
                                                                            navController = navController,
                                                                            onDismiss = menuState::dismiss,
                                                                        )
                                                                    }

                                                                    is Artist -> {
                                                                        ArtistMenu(
                                                                            originalArtist = localItem,
                                                                            coroutineScope = scope,
                                                                            onDismiss = menuState::dismiss,
                                                                        )
                                                                    }

                                                                    is Playlist -> {
                                                                        PlaylistMenu(
                                                                            playlist = localItem,
                                                                            coroutineScope = scope,
                                                                            onDismiss = menuState::dismiss,
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        },
                                                    ),
                                        ) {
                                            SpeedDialGridItem(
                                                item = ytItem,
                                                isPinned = true,
                                                isActive = isActive,
                                                isPlaying = isPlaying,
                                            )
                                        }
                                    }
                                }
                                repeat(SpeedDialGridColumns - rowTiles.size) {
                                    Spacer(modifier = Modifier.size(tileSize))
                                }
                            }
                        }
                }
            }
        }

        if (tilePages.size > 1) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                repeat(tilePages.size) { index ->
                    val isSelected = index == selectedDotIndex
                    val dotColor by animateColorAsState(
                        targetValue =
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                        animationSpec = motionScheme.defaultEffectsSpec(),
                        label = "speedDialDotColor",
                    )
                    val dotWidth by animateDpAsState(
                        targetValue = if (isSelected) 22.dp else 8.dp,
                        animationSpec = motionScheme.defaultSpatialSpec(),
                        label = "speedDialDotWidth",
                    )
                    Surface(
                        color = dotColor,
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier =
                            Modifier
                                .width(dotWidth)
                                .height(8.dp),
                    ) {}
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpeedDialRandomTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
        modifier =
            modifier
                .aspectRatio(1f)
                .hushCombinedPressable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                repeat(3) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                        modifier = Modifier.size(18.dp),
                    ) {}
                }
            }
        }
    }
}

/**
 * Keep Listening section - horizontal grid of local items
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeepListeningSection(
    keepListening: List<LocalItem>,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val rows = if (keepListening.size > 6) 2 else 1
    val gridHeight =
        (
            GridThumbnailHeight +
                with(LocalDensity.current) {
                    MaterialTheme.typography.bodyLarge.lineHeight
                        .toDp() * 2 +
                        MaterialTheme.typography.bodyMedium.lineHeight
                            .toDp() * 2
                }
        ) * rows

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val carouselMetrics = rememberHorizontalCarouselMetrics(maxWidth)
        val lazyGridState = rememberLazyGridState()
        val snapLayoutInfoProvider =
            remember(lazyGridState, carouselMetrics.widthFactor) {
                buildSnapLayoutInfoProvider(
                    lazyGridState = lazyGridState,
                    positionInLayout = { layoutSize, itemSize ->
                        layoutSize * carouselMetrics.widthFactor / 2f - itemSize / 2f
                    },
                )
            }

        LazyHorizontalGrid(
            state = lazyGridState,
            rows = GridCells.Fixed(rows),
            flingBehavior = rememberSmoothSnapFlingBehavior(snapLayoutInfoProvider),
            contentPadding =
                WindowInsets.systemBars
                    .only(WindowInsetsSides.Horizontal)
                    .asPaddingValues(),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(gridHeight),
        ) {
        items(
            items = keepListening,
            key = { item ->
                when (item) {
                    is Song -> "song_${item.id}"
                    is Album -> "album_${item.id}"
                    is Artist -> "artist_${item.id}"
                    is Playlist -> "playlist_${item.id}"
                }
            },
        ) { item ->
            LocalGridItem(
                item = item,
                mediaMetadata = mediaMetadata,
                isPlaying = isPlaying,
                navController = navController,
                playerConnection = playerConnection,
                menuState = menuState,
                haptic = haptic,
                scope = scope,
            )
        }
        }
    }
}

/**
 * Forgotten Favorites section - horizontal grid of songs
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ForgottenFavoritesSection(
    forgottenFavorites: List<Song>,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    horizontalLazyGridItemWidth: Dp,
    lazyGridState: LazyGridState,
    snapLayoutInfoProvider: SnapLayoutInfoProvider,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    modifier: Modifier = Modifier,
) {
    val rows = min(4, forgottenFavorites.size)
    val distinctForgottenFavorites = remember(forgottenFavorites) { forgottenFavorites.distinctBy { it.id } }

    LazyHorizontalGrid(
        state = lazyGridState,
        rows = GridCells.Fixed(rows),
        flingBehavior = rememberSmoothSnapFlingBehavior(snapLayoutInfoProvider),
        contentPadding =
            WindowInsets.systemBars
                .only(WindowInsetsSides.Horizontal)
                .asPaddingValues(),
        modifier =
            modifier
                .fillMaxWidth()
                .height(ListItemHeight * rows),
    ) {
        items(
            items = distinctForgottenFavorites,
            key = { it.id },
        ) { song ->
            SongListItem(
                song = song,
                showInLibraryIcon = true,
                isActive = song.id == mediaMetadata?.id,
                isPlaying = isPlaying,
                isSwipeable = false,
                trailingContent = {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuState.show {
                                SongMenu(
                                    originalSong = song,
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
                        .width(horizontalLazyGridItemWidth)
                        .focusable()
                        .hushCombinedPressable(
                            onClick = {
                                if (song.id == mediaMetadata?.id) {
                                    playerConnection.player.togglePlayPause()
                                } else {
                                    playerConnection.playQueue(
                                        if (song.song.isLocal) {
                                            ListQueue(items = listOf(song.toMediaItem()))
                                        } else {
                                            YouTubeQueue.radio(song.toMediaMetadata())
                                        },
                                    )
                                }
                            },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuState.show {
                                    SongMenu(
                                        originalSong = song,
                                        navController = navController,
                                        onDismiss = menuState::dismiss,
                                    )
                                }
                            },
                        ),
            )
        }
    }
}

/**
 * Account Playlists section - horizontal row of YouTube playlists
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AccountPlaylistsSection(
    accountPlaylists: List<PlaylistItem>,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val distinctPlaylists = remember(accountPlaylists) { accountPlaylists.distinctBy { it.id } }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val carouselMetrics = rememberHorizontalCarouselMetrics(maxWidth)
        val (lazyListState, flingBehavior) = rememberLazyRowCarouselScroll(carouselMetrics.widthFactor)

        LazyRow(
            state = lazyListState,
            flingBehavior = flingBehavior,
            contentPadding =
                WindowInsets.systemBars
                    .only(WindowInsetsSides.Horizontal)
                    .asPaddingValues(),
        ) {
        items(
            items = distinctPlaylists,
            key = { it.id },
        ) { item ->
            YouTubeGridItemWrapper(
                item = item,
                mediaMetadata = mediaMetadata,
                isPlaying = isPlaying,
                navController = navController,
                playerConnection = playerConnection,
                menuState = menuState,
                haptic = haptic,
                scope = scope,
                thumbnailWidth = carouselMetrics.itemWidth,
            )
        }
        }
    }
}

/**
 * Similar Recommendations section
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SimilarRecommendationsSection(
    recommendation: SimilarRecommendation,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val carouselMetrics = rememberHorizontalCarouselMetrics(maxWidth)
        val (lazyListState, flingBehavior) = rememberLazyRowCarouselScroll(carouselMetrics.widthFactor)

        LazyRow(
            state = lazyListState,
            flingBehavior = flingBehavior,
            contentPadding =
                WindowInsets.systemBars
                    .only(WindowInsetsSides.Horizontal)
                    .asPaddingValues(),
        ) {
            items(
                items = recommendation.items,
                key = { it.id },
            ) { item ->
                YouTubeGridItemWrapper(
                    item = item,
                    mediaMetadata = mediaMetadata,
                    isPlaying = isPlaying,
                    navController = navController,
                    playerConnection = playerConnection,
                    menuState = menuState,
                    haptic = haptic,
                    scope = scope,
                    thumbnailWidth = carouselMetrics.itemWidth,
                )
            }
        }
    }
}

/**
 * HomePage Section - a single section from YouTube home page
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomePageSectionContent(
    section: HomePage.Section,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val carouselMetrics = rememberHorizontalCarouselMetrics(maxWidth)
        val (lazyListState, flingBehavior) = rememberLazyRowCarouselScroll(carouselMetrics.widthFactor)
        LazyRow(
            state = lazyListState,
            flingBehavior = flingBehavior,
            contentPadding =
                WindowInsets.systemBars
                    .only(WindowInsetsSides.Horizontal)
                    .asPaddingValues(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(
                items = section.items,
                key = { it.id },
            ) { item ->
                YouTubeGridItemWrapper(
                    item = item,
                    mediaMetadata = mediaMetadata,
                    isPlaying = isPlaying,
                    navController = navController,
                    playerConnection = playerConnection,
                    menuState = menuState,
                    haptic = haptic,
                    scope = scope,
                    thumbnailWidth = carouselMetrics.itemWidth,
                )
            }
        }
    }
}

/**
 * Loading shimmer for home page sections
 */
@Composable
fun HomeLoadingShimmer(modifier: Modifier = Modifier) {
    ShimmerHost(modifier = modifier) {
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

// ============== Helper Composables ==============

/**
 * Wrapper for YouTube grid items with click handling
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun YouTubeGridItemWrapper(
    item: YTItem,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    scope: CoroutineScope,
    thumbnailWidth: Dp? = null,
    modifier: Modifier = Modifier,
) {
    YouTubeGridItem(
        item = item,
        isActive = item.id in listOf(mediaMetadata?.album?.id, mediaMetadata?.id),
        isPlaying = isPlaying,
        coroutineScope = scope,
        thumbnailRatio = 1f,
        thumbnailWidth = thumbnailWidth,
        modifier =
            modifier
                .padding(horizontal = 4.dp)
                .hushHomeRowCard()
                .focusable()
                .hushCombinedPressable(
                    onClick = {
                        when (item) {
                            is SongItem -> {
                                playerConnection.playQueue(
                                    YouTubeQueue(
                                        item.endpoint ?: WatchEndpoint(videoId = item.id),
                                        item.toMediaMetadata(),
                                    ),
                                )
                            }

                            is AlbumItem -> {
                                navController.navigate("album/${item.id}")
                            }

                            is ArtistItem -> {
                                navController.navigate("artist/${item.id}")
                            }

                            is PlaylistItem -> {
                                navController.navigate("online_playlist/${item.id}")
                            }

                            is PodcastItem -> {
                                navController.navigate("online_podcast/${item.id}")
                            }

                            is EpisodeItem -> {
                                playerConnection.playQueue(
                                    ListQueue(
                                        title = item.title,
                                        items = listOf(item.asSongItem().toMediaMetadata().toMediaItem()),
                                    ),
                                )
                            }
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
                                        coroutineScope = scope,
                                        onDismiss = menuState::dismiss,
                                    )
                                }

                                is PodcastItem -> {
                                    YouTubePlaylistMenu(
                                        playlist = item.asPlaylistItem(),
                                        coroutineScope = scope,
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
                ),
    )
}

/**
 * Local item grid item for songs, albums, artists
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocalGridItem(
    item: LocalItem,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    when (item) {
        is Song -> {
            SongGridItem(
                song = item,
                modifier =
                    modifier
                        .fillMaxWidth()
                        .focusable()
                        .hushCombinedPressable(
                            onClick = {
                                if (item.id == mediaMetadata?.id) {
                                    playerConnection.player.togglePlayPause()
                                } else {
                                    playerConnection.playQueue(YouTubeQueue.radio(item.toMediaMetadata()))
                                }
                            },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuState.show {
                                    SongMenu(
                                        originalSong = item,
                                        navController = navController,
                                        onDismiss = menuState::dismiss,
                                    )
                                }
                            },
                        ),
                isActive = item.id == mediaMetadata?.id,
                isPlaying = isPlaying,
            )
        }

        is Album -> {
            AlbumGridItem(
                album = item,
                isActive = item.id == mediaMetadata?.album?.id,
                isPlaying = isPlaying,
                coroutineScope = scope,
                modifier =
                    modifier
                        .fillMaxWidth()
                        .focusable()
                        .hushCombinedPressable(
                            onClick = { navController.navigate("album/${item.id}") },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuState.show {
                                    AlbumMenu(
                                        originalAlbum = item,
                                        navController = navController,
                                        onDismiss = menuState::dismiss,
                                    )
                                }
                            },
                        ),
            )
        }

        is Artist -> {
            ArtistGridItem(
                artist = item,
                modifier =
                    modifier
                        .fillMaxWidth()
                        .focusable()
                        .hushCombinedPressable(
                            onClick = { navController.navigate("artist/${item.id}") },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuState.show {
                                    ArtistMenu(
                                        originalArtist = item,
                                        coroutineScope = scope,
                                        onDismiss = menuState::dismiss,
                                    )
                                }
                            },
                        ),
            )
        }

        is Playlist -> { /* Not displayed */ }
    }
}

/**
 * Account playlist navigation title with image
 */
@Composable
fun AccountPlaylistsTitle(
    accountName: String,
    accountImageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationTitle(
        label = stringResource(R.string.your_youtube_playlists),
        title = accountName.ifBlank { stringResource(R.string.account) },
        thumbnail = {
            if (accountImageUrl != null) {
                AsyncImage(
                    model =
                        ImageRequest
                            .Builder(LocalContext.current)
                            .data(accountImageUrl)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .diskCacheKey(accountImageUrl)
                            .crossfade(true)
                            .build(),
                    placeholder = painterResource(id = R.drawable.person),
                    error = painterResource(id = R.drawable.person),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .size(ListThumbnailSize)
                            .clip(CircleShape),
                )
            } else {
                Icon(
                    painter = painterResource(id = R.drawable.person),
                    contentDescription = null,
                    modifier = Modifier.size(ListThumbnailSize),
                )
            }
        },
        onClick = onClick,
        modifier = modifier,
    )
}

/**
 * Similar recommendations navigation title
 */
@Composable
fun SimilarRecommendationsTitle(
    recommendation: SimilarRecommendation,
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    NavigationTitle(
        label = stringResource(R.string.similar_to),
        title = recommendation.title.title,
        thumbnail =
            recommendation.title.thumbnailUrl?.let { thumbnailUrl ->
                {
                    val shape =
                        if (recommendation.title is Artist) {
                            CircleShape
                        } else {
                            RoundedCornerShape(ThumbnailCornerRadius)
                        }
                    AsyncImage(
                        model = thumbnailUrl,
                        contentDescription = null,
                        modifier =
                            Modifier
                                .size(ListThumbnailSize)
                                .clip(shape),
                    )
                }
            },
        onClick = {
            when (recommendation.title) {
                is Song -> {
                    navController.navigate("album/${recommendation.title.album!!.id}")
                }

                is Album -> {
                    navController.navigate("album/${recommendation.title.id}")
                }

                is Artist -> {
                    navController.navigate("artist/${recommendation.title.id}")
                }

                is Playlist -> {}
            }
        },
        modifier = modifier,
    )
}

/**
 * HomePage section navigation title
 */
@Composable
fun HomePageSectionTitle(
    section: HomePage.Section,
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    NavigationTitle(
        title = section.title,
        label = section.label,
        thumbnail =
            section.thumbnail?.let { thumbnailUrl ->
                {
                    val shape =
                        if (section.endpoint?.isArtistEndpoint == true) {
                            CircleShape
                        } else {
                            RoundedCornerShape(ThumbnailCornerRadius)
                        }
                    AsyncImage(
                        model = thumbnailUrl,
                        contentDescription = null,
                        modifier =
                            Modifier
                                .size(ListThumbnailSize)
                                .clip(shape),
                    )
                }
            },
        onClick =
            section.endpoint?.browseId?.let { browseId ->
                {
                    if (browseId == "FEmusic_moods_and_genres") {
                        navController.navigate(Screens.ROUTE_MOOD_AND_GENRES)
                    } else {
                        navController.navigate("browse/$browseId")
                    }
                }
            },
        modifier = modifier,
    )
}

/**
 * Spotify playlists section - horizontal row matching library carousel cards.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpotifyPlaylistsSection(
    playlists: List<SpotifyPlaylist>,
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val carouselMetrics = rememberHorizontalCarouselMetrics(maxWidth)
        val (lazyListState, flingBehavior) = rememberLazyRowCarouselScroll(carouselMetrics.widthFactor)

        LazyRow(
            state = lazyListState,
            flingBehavior = flingBehavior,
            contentPadding =
                WindowInsets.systemBars
                    .only(WindowInsetsSides.Horizontal)
                    .asPaddingValues(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
        items(
            items = playlists,
            key = { playlist -> playlist.id },
            contentType = { "home_spotify_playlist" },
        ) { playlist ->
            SpotifyPlaylistCarouselCard(
                playlist = playlist,
                onClick = {
                    navController.navigate("spotify_playlist/${playlist.id}")
                },
            )
        }
        }
    }
}

@Composable
fun SpotifyPlaylistsTitle(
    accountName: String,
    accountAvatarUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationTitle(
        label = stringResource(R.string.spotify_playlists),
        title = accountName.ifBlank { stringResource(R.string.spotify_account) },
        thumbnail = {
            if (!accountAvatarUrl.isNullOrBlank()) {
                AsyncImage(
                    model =
                        ImageRequest
                            .Builder(LocalContext.current)
                            .data(accountAvatarUrl)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .diskCacheKey(accountAvatarUrl)
                            .crossfade(true)
                            .build(),
                    placeholder = painterResource(id = R.drawable.spotify_icon),
                    error = painterResource(id = R.drawable.spotify_icon),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .size(ListThumbnailSize)
                            .clip(CircleShape),
                )
            } else {
                Icon(
                    painter = painterResource(id = R.drawable.spotify_icon),
                    contentDescription = null,
                    modifier = Modifier.size(ListThumbnailSize),
                )
            }
        },
        onClick = onClick,
        modifier = modifier,
    )
}

@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.SpotifyPlaylistsContainer(
    navController: NavController,
    isHomeRefreshing: Boolean,
) {
    item(key = "home_spotify_playlists") {
        val spotifyLibraryViewModel: SpotifyLibraryViewModel = hiltViewModel()
        val (showSpotifyPlaylists) = rememberPreference(ShowSpotifyPlaylistsKey, false)
        val spotifyPlaylists by spotifyLibraryViewModel.playlists.collectAsStateWithLifecycle()
        val (accountName) = rememberPreference(SpotifyAccountNameKey, "")
        val (accountAvatarUrl) = rememberPreference(SpotifyAccountAvatarUrlKey, "")

        LaunchedEffect(showSpotifyPlaylists) {
            if (showSpotifyPlaylists) {
                spotifyLibraryViewModel.refreshPlaylists()
            }
        }

        LaunchedEffect(isHomeRefreshing, showSpotifyPlaylists) {
            if (isHomeRefreshing && showSpotifyPlaylists) {
                spotifyLibraryViewModel.refreshPlaylists()
            }
        }

        if (showSpotifyPlaylists && spotifyPlaylists.isNotEmpty()) {
            Column {
                SpotifyPlaylistsTitle(
                    accountName = accountName,
                    accountAvatarUrl = accountAvatarUrl,
                    onClick = {
                        navController.currentBackStackEntry
                            ?.savedStateHandle
                            ?.set("pendingLibraryFilter", LibraryFilter.SPOTIFY.name)
                        navController.navigate(Screens.ROUTE_LIBRARY) {
                            launchSingleTop = true
                        }
                    },
                )
                SpotifyPlaylistsSection(
                    playlists = spotifyPlaylists,
                    navController = navController,
                )
            }
        }
    }
}
