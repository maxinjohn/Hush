/*
 * ArchiveTune (2026)
 * Â© Rukamori â€” github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.screens.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import app.hush.music.LocalPlayerAwareWindowInsets
import app.hush.music.LocalPlayerConnection
import app.hush.music.R
import app.hush.music.extensions.togglePlayPause
import app.hush.music.innertube.models.AlbumItem
import app.hush.music.innertube.models.ArtistItem
import app.hush.music.innertube.models.BrowseEndpoint
import app.hush.music.innertube.models.SongItem
import app.hush.music.innertube.models.WatchEndpoint
import app.hush.music.models.toMediaMetadata
import app.hush.music.playback.queues.YouTubeQueue
import app.hush.music.innertube.pages.MoodAndGenres
import app.hush.music.ui.component.LocalMenuState
import app.hush.music.ui.component.NavigationTitle
import app.hush.music.ui.component.YouTubeGridItem
import app.hush.music.ui.component.YouTubeListItem
import app.hush.music.ui.theme.HushAmbientBackground
import app.hush.music.ui.theme.HushExploreBackground
import app.hush.music.ui.theme.LocalExploreTheme
import app.hush.music.ui.component.shimmer.ShimmerHost
import app.hush.music.ui.component.shimmer.TextPlaceholder
import app.hush.music.ui.menu.YouTubeAlbumMenu
import app.hush.music.ui.menu.YouTubeArtistMenu
import app.hush.music.ui.menu.YouTubeSongMenu
import app.hush.music.ui.screens.MoodAndGenresButton
import app.hush.music.viewmodels.SearchDiscoveryScreenState
import app.hush.music.viewmodels.SearchDiscoveryTab
import app.hush.music.viewmodels.SearchDiscoveryViewModel

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    navController: NavController,
    onSearchClick: () -> Unit,
    viewModel: SearchDiscoveryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val lazyListState = rememberLazyListState()
    val moodColumnCount = rememberMoodColumnCount()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val scrollToTop =
        backStackEntry
            ?.savedStateHandle
            ?.getStateFlow("scrollToTop", false)
            ?.collectAsStateWithLifecycle()

    LaunchedEffect(scrollToTop?.value) {
        if (scrollToTop?.value == true) {
            lazyListState.animateScrollToItem(0)
            backStackEntry?.savedStateHandle?.set("scrollToTop", false)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        HushExploreBackground(modifier = Modifier.align(Alignment.TopCenter))
        CompositionLocalProvider(LocalExploreTheme provides true) {

        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
            modifier = Modifier.fillMaxSize(),
        ) {
        item(
            key = "search_field",
            contentType = "search_field",
        ) {
            SearchEntryField(
                onClick = onSearchClick,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .animateItem(),
            )
        }

        item(
            key = "search_tabs",
            contentType = "search_tabs",
        ) {
            SearchDiscoveryTabs(
                selectedTab = selectedTab,
                onTabSelected = viewModel::selectTab,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .animateItem(),
            )
        }

        when (val currentState = state) {
            SearchDiscoveryScreenState.Loading -> {
                item(
                    key = "search_loading",
                    contentType = "search_loading",
                ) {
                    SearchDiscoveryLoading(modifier = Modifier.animateItem())
                }
            }

            SearchDiscoveryScreenState.Empty -> {
                item(
                    key = "search_empty",
                    contentType = "search_empty",
                ) {
                    SearchStateMessage(
                        message = stringResource(R.string.no_results_found),
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            is SearchDiscoveryScreenState.Error -> {
                item(
                    key = "search_error",
                    contentType = "search_error",
                ) {
                    SearchStateMessage(
                        message = stringResource(currentState.messageResId),
                        action = {
                            Button(onClick = viewModel::retry) {
                                Text(stringResource(R.string.retry_button))
                            }
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            is SearchDiscoveryScreenState.Success -> {
                when (selectedTab) {
                    SearchDiscoveryTab.EXPLORE -> {
                        item(
                            key = "search_explore_moods_title",
                            contentType = "section_title",
                        ) {
                            NavigationTitle(
                                title = stringResource(R.string.mood_and_genres),
                                modifier = Modifier.animateItem(),
                            )
                        }
                        moodAndGenresRows(
                            items = currentState.data.moodAndGenres,
                            columnCount = moodColumnCount,
                            onSelect = { endpoint ->
                                navController.navigate("youtube_browse/${endpoint.browseId}?params=${endpoint.params}")
                            },
                        )
                    }

                    SearchDiscoveryTab.SUGGESTIONS -> {
                        item(
                            key = "search_suggestions_songs",
                            contentType = "suggestion_songs",
                        ) {
                            SuggestedSongsSection(
                                songs = currentState.data.suggestedSongs,
                                navController = navController,
                                modifier = Modifier.animateItem(),
                            )
                        }

                        item(
                            key = "search_suggestions_artists",
                            contentType = "suggestion_artists",
                        ) {
                            SuggestedArtistsSection(
                                artists = currentState.data.suggestedArtists,
                                navController = navController,
                                modifier = Modifier.animateItem(),
                            )
                        }

                        item(
                            key = "search_suggestions_albums",
                            contentType = "suggestion_albums",
                        ) {
                            TrendingAlbumsSection(
                                albums = currentState.data.trendingAlbums,
                                navController = navController,
                                modifier = Modifier.animateItem(),
                            )
            }
        }
        }
    }
}
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchEntryField(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SearchBar(
        inputField = {
            SearchBarDefaults.InputField(
                query = "",
                onQueryChange = { onClick() },
                onSearch = { onClick() },
                expanded = false,
                onExpandedChange = { expanded ->
                    if (expanded) onClick()
                },
                placeholder = {
                    Text(
                        text = stringResource(R.string.search_yt_music),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.search),
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.language),
                        contentDescription = null,
                    )
                },
            )
        },
        expanded = false,
        onExpandedChange = { expanded ->
            if (expanded) onClick()
        },
        windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        modifier = modifier.fillMaxWidth(),
    ) {}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchDiscoveryTabs(
    selectedTab: SearchDiscoveryTab,
    onTabSelected: (SearchDiscoveryTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = remember { SearchDiscoveryTab.entries }
    PrimaryTabRow(
        selectedTabIndex = tabs.indexOf(selectedTab),
        modifier = modifier,
    ) {
        tabs.forEach { tab ->
            Tab(
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
                text = {
                    Text(
                        text =
                            stringResource(
                                when (tab) {
                                    SearchDiscoveryTab.EXPLORE -> R.string.explore
                                    SearchDiscoveryTab.SUGGESTIONS -> R.string.suggestions
                                },
                            ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

/**
 * How many mood/genre cards fit on one row.
 *
 * The rows are emitted into the surrounding [LazyColumn], so each one has to work out the same
 * column count on its own; the window width is identical for all of them, which is what keeps
 * the rows aligned.
 */
@Composable
private fun rememberMoodColumnCount(): Int {
    val configuration = LocalConfiguration.current
    return (configuration.screenWidthDp / MoodAndGenresMinCellWidth.value).toInt().coerceAtLeast(1)
}

/**
 * The mood/genre cards, one lazy item per row.
 *
 * This used to be a `LazyVerticalGrid` with `userScrollEnabled = false` inside the search
 * LazyColumn, given an exact height for every row it would hold. Being "not scrollable" and
 * exactly as tall as its content, the inner grid considered every card visible and built the
 * whole set at once - fifty-odd cards, each with its own gradients and artwork request, in the
 * frame where the search screen opens. Emitting the rows into the outer list keeps the exact
 * same layout while only building the rows near the viewport, which is the difference that
 * matters on a low-RAM device.
 */
private fun LazyListScope.moodAndGenresRows(
    items: List<MoodAndGenres.Item>,
    columnCount: Int,
    onSelect: (BrowseEndpoint) -> Unit,
) {
    if (items.isEmpty()) return

    val rows = items.chunked(columnCount)
    rows.forEachIndexed { rowIndex, rowItems ->
        item(
            key = "search_explore_moods_row_$rowIndex",
            contentType = "mood_genres_row",
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        // Matches the padding the old grid put around its cells, so the cards
                        // keep the same width and the same inset from the screen edge.
                        .padding(horizontal = 6.dp)
                        .then(if (rowIndex == 0) Modifier.padding(top = 6.dp) else Modifier)
                        .then(if (rowIndex == rows.lastIndex) Modifier.padding(bottom = 6.dp) else Modifier)
                        .animateItem(),
            ) {
                rowItems.forEach { item ->
                    MoodAndGenresButton(
                        title = item.title,
                        stripeColor = item.stripeColor,
                        endpoint = item.endpoint,
                        onClick = { onSelect(item.endpoint) },
                        modifier =
                            Modifier
                                .weight(1f)
                                .padding(6.dp),
                    )
                }
                // A short last row must not stretch its cards across the full width.
                repeat(columnCount - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private val MoodAndGenresMinCellWidth = 180.dp

private val SuggestedSongGroupHorizontalPadding = 12.dp
private val SuggestedSongGroupVerticalPadding = 2.dp
private val SuggestedSongGroupItemSpacing = 2.dp
private val SuggestedSongGroupLargeCorner = 28.dp
private val SuggestedSongGroupSmallCorner = 6.dp

private fun segmentedSuggestedSongShape(
    index: Int,
    count: Int,
): Shape {
    val large = SuggestedSongGroupLargeCorner
    val small = SuggestedSongGroupSmallCorner
    return when {
        count <= 1 -> RoundedCornerShape(large)
        index == 0 ->
            RoundedCornerShape(
                topStart = large,
                topEnd = large,
                bottomEnd = small,
                bottomStart = small,
            )
        index == count - 1 ->
            RoundedCornerShape(
                topStart = small,
                topEnd = small,
                bottomEnd = large,
                bottomStart = large,
            )
        else -> RoundedCornerShape(small)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SuggestedSongsSection(
    songs: List<SongItem>,
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    if (songs.isEmpty()) return

    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()

    SectionContainer(
        title = stringResource(R.string.stats_unique_songs),
        modifier = modifier,
    ) {
        val visibleSongs = remember(songs) { songs.take(6) }

        Column(
            verticalArrangement = Arrangement.spacedBy(SuggestedSongGroupItemSpacing),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = SuggestedSongGroupHorizontalPadding,
                        vertical = SuggestedSongGroupVerticalPadding,
                    ),
        ) {
            visibleSongs.forEachIndexed { index, song ->
                Card(
                    shape = segmentedSuggestedSongShape(index = index, count = visibleSongs.size),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (song.id == mediaMetadata?.id) {
                                        playerConnection.player.togglePlayPause()
                                    } else {
                                        playerConnection.playQueue(
                                            YouTubeQueue(
                                                endpoint = song.endpoint ?: WatchEndpoint(videoId = song.id),
                                                preloadItem = song.toMediaMetadata(),
                                            ),
                                        )
                                    }
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    menuState.show {
                                        YouTubeSongMenu(
                                            song = song,
                                            navController = navController,
                                            onDismiss = menuState::dismiss,
                                        )
                                    }
                                },
                            ),
                ) {
                    YouTubeListItem(
                        item = song,
                        albumIndex = index + 1,
                        viewCountText = song.viewCountText,
                        isActive = song.id == mediaMetadata?.id,
                        isPlaying = isPlaying,
                        isSwipeable = false,
                        trailingContent = {
                            YouTubeSongMenuButton(song = song, navController = navController)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrendingAlbumsSection(
    albums: List<AlbumItem>,
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    if (albums.isEmpty()) return

    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    NavigationTitle(
        title = stringResource(R.string.top_albums),
        modifier = modifier,
    )
    LazyRow(
        contentPadding = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal).asPaddingValues(),
    ) {
        items(
            items = albums,
            key = { album -> album.id },
            contentType = { "trending_album" },
        ) { album ->
            YouTubeGridItem(
                item = album,
                isActive = mediaMetadata?.album?.id == album.id,
                isPlaying = isPlaying,
                coroutineScope = coroutineScope,
                modifier =
                    Modifier
                        .combinedClickable(
                            onClick = {
                                navController.navigate("album/${album.id}")
                            },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuState.show {
                                    YouTubeAlbumMenu(
                                        albumItem = album,
                                        navController = navController,
                                        onDismiss = menuState::dismiss,
                                    )
                                }
                            },
                        ).animateItem(),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SuggestedArtistsSection(
    artists: List<ArtistItem>,
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    if (artists.isEmpty()) return

    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current

    NavigationTitle(
        title = stringResource(R.string.stats_unique_artists),
        modifier = modifier,
    )
    LazyRow(
        contentPadding = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal).asPaddingValues(),
    ) {
        items(
            items = artists,
            key = { artist -> artist.id },
            contentType = { "trending_artist" },
        ) { artist ->
            YouTubeGridItem(
                item = artist,
                modifier =
                    Modifier
                        .combinedClickable(
                            onClick = {
                                navController.navigate("artist/${artist.id}")
                            },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuState.show {
                                    YouTubeArtistMenu(
                                        artist = artist,
                                        onDismiss = menuState::dismiss,
                                    )
                                }
                            },
                        ).animateItem(),
            )
        }
    }
}

@Composable
private fun SectionContainer(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    NavigationTitle(
        title = title,
        modifier = modifier,
    )
    content()
}

@Composable
private fun YouTubeSongMenuButton(
    song: SongItem,
    navController: NavController,
) {
    val menuState = LocalMenuState.current
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
}

@Composable
private fun SearchDiscoveryLoading(
    modifier: Modifier = Modifier,
) {
    ShimmerHost(
        modifier = modifier.fillMaxWidth(),
    ) {
        TextPlaceholder(
            height = 56.dp,
            modifier =
                Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
        )
        TextPlaceholder(
            height = 28.dp,
            modifier =
                Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .width(180.dp),
        )
        repeat(6) {
            TextPlaceholder(
                height = 84.dp,
                modifier =
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SearchStateMessage(
    message: String,
    modifier: Modifier = Modifier,
    action: @Composable RowScope.() -> Unit = {},
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.search_off),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.foundation.layout.Row(content = action)
        }
    }
}
