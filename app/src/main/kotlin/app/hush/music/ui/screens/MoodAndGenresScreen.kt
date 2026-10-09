/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.screens

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.hush.music.LocalPlayerAwareWindowInsets
import app.hush.music.R
import app.hush.music.ui.theme.LocalExploreTheme
import app.hush.music.innertube.YouTube
import app.hush.music.innertube.models.BrowseEndpoint
import app.hush.music.ui.component.NavigationTitle
import app.hush.music.ui.component.shimmer.ShimmerHost
import app.hush.music.ui.component.shimmer.TextPlaceholder
import app.hush.music.viewmodels.MoodAndGenresViewModel
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MoodAndGenresScreen(
    navController: NavController,
    viewModel: MoodAndGenresViewModel = hiltViewModel(),
) {
    val moodAndGenres by viewModel.moodAndGenres.collectAsState()
    val gridState = rememberLazyGridState()
    val density = LocalDensity.current
    val windowInsets = LocalPlayerAwareWindowInsets.current
    val topPadding = with(density) { windowInsets.getTop(this).toDp() }
    val bottomPadding = with(density) { windowInsets.getBottom(this).toDp() }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val scrollToTop =
        backStackEntry?.savedStateHandle?.getStateFlow("scrollToTop", false)?.collectAsState()

    LaunchedEffect(scrollToTop?.value) {
        if (scrollToTop?.value == true) {
            gridState.animateScrollToItem(0)
            backStackEntry?.savedStateHandle?.set("scrollToTop", false)
        }
    }

    CompositionLocalProvider(LocalExploreTheme provides true) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 180.dp),
        state = gridState,
        contentPadding =
            PaddingValues(
                start = 6.dp,
                top = topPadding,
                end = 6.dp,
                bottom = bottomPadding,
            ),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            NavigationTitle(
                title = stringResource(R.string.mood_and_genres),
                modifier = Modifier.animateItem(),
            )
        }

        if (moodAndGenres == null) {
            items(
                count = 12,
                key = { index -> "mood_genres_shimmer_$index" },
                contentType = { "mood_genres_shimmer" },
            ) {
                ShimmerHost {
                    TextPlaceholder(
                        height = MoodAndGenresButtonHeight,
                        shape = MoodAndGenresButtonShape,
                        modifier = Modifier.padding(6.dp),
                    )
                }
            }
        } else {
            items(
                items = moodAndGenres.orEmpty(),
                key = { item -> "${item.title}:${item.endpoint.browseId}:${item.endpoint.params}" },
                contentType = { "mood_genres_item" },
            ) { item ->
                MoodAndGenresButton(
                    title = item.title,
                    stripeColor = item.stripeColor,
                    endpoint = item.endpoint,
                    onClick = {
                        navController.navigate("youtube_browse/${item.endpoint.browseId}?params=${item.endpoint.params}")
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(6.dp)
                            .animateItem(),
                )
            }
        }
    }
    }  // Close CompositionLocalProvider
}

@Composable
fun MoodAndGenresButton(
    title: String,
    stripeColor: Long,
    endpoint: BrowseEndpoint? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isExploreTheme = LocalExploreTheme.current
    val colorScheme = MaterialTheme.colorScheme
    val base = remember(stripeColor) { Color(stripeColor) }
    val artworkUrl = rememberMoodAndGenresArtworkUrl(endpoint)
    val artworkModel = rememberMoodAndGenresArtworkModel(endpoint = endpoint, artworkUrl = artworkUrl)

    // More vibrant gradients for explore theme
    val cardStart =
        remember(base, colorScheme.primaryContainer, isExploreTheme) {
            if (isExploreTheme) {
                lerp(base, colorScheme.primary, 0.35f)
            } else {
                lerp(base, colorScheme.primaryContainer, 0.18f)
            }
        }
    val cardEnd =
        remember(base, colorScheme.surfaceContainerHighest, isExploreTheme) {
            if (isExploreTheme) {
                lerp(base, colorScheme.tertiary, 0.40f)
            } else {
                lerp(base, colorScheme.surfaceContainerHighest, 0.34f)
            }
        }
    val coverStart =
        remember(base, colorScheme.surface, isExploreTheme) {
            if (isExploreTheme) {
                lerp(base, colorScheme.secondary, 0.32f)
            } else {
                lerp(base, colorScheme.surface, 0.28f)
            }
        }
    val coverEnd =
        remember(base, colorScheme.scrim, isExploreTheme) {
            if (isExploreTheme) {
                lerp(base, colorScheme.primary.copy(alpha = 0.6f), 0.25f)
            } else {
                lerp(base, colorScheme.scrim, 0.2f)
            }
        }
    val cardBrush =
        remember(cardStart, cardEnd) {
            Brush.linearGradient(
                colors = listOf(cardStart, cardEnd),
                start = Offset(0f, 200f),
                end = Offset(900f, 650f),
            )
        }
    val coverBrush =
        remember(coverStart, coverEnd) {
            Brush.linearGradient(
                colors = listOf(coverStart, coverEnd),
                start = Offset.Zero,
                end = Offset(360f, 360f),
            )
        }
    val textScrimBrush =
        remember(colorScheme.scrim, isExploreTheme) {
            Brush.horizontalGradient(
                colors =
                    if (isExploreTheme) {
                        listOf(
                            colorScheme.scrim.copy(alpha = 0.50f),
                            colorScheme.scrim.copy(alpha = 0.22f),
                            Color.Transparent,
                        )
                    } else {
                        listOf(
                            colorScheme.scrim.copy(alpha = 0.38f),
                            colorScheme.scrim.copy(alpha = 0.18f),
                            Color.Transparent,
                        )
                    },
            )
        }
    Card(
        onClick = onClick,
        shape = MoodAndGenresButtonShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier =
            modifier
                .height(MoodAndGenresButtonHeight)
                .then(
                    if (isExploreTheme) {
                        Modifier
                            .clip(MoodAndGenresButtonShape)
                            .border(
                                width = 0.5.dp,
                                color = colorScheme.primary.copy(alpha = 0.16f),
                                shape = MoodAndGenresButtonShape,
                            )
                    } else {
                        Modifier
                    },
                ),
    ) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(cardBrush),
        ) {
            // Measured rather than assumed: this button is a half-width cell on a phone and a
            // wider column elsewhere, and the title has to clear the artwork at either width.
            val layout = moodAndGenresCardLayout(maxWidth)
            val bottomScrimBrush =
                remember(colorScheme.scrim) {
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, colorScheme.scrim.copy(alpha = 0.55f)),
                    )
                }
            if (artworkModel != null) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = layout.coverTop, end = layout.coverInset)
                            .size(layout.coverSize)
                            .clip(MoodAndGenresCoverShape)
                            .background(coverBrush),
                ) {
                    AsyncImage(
                        model = artworkModel,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(textScrimBrush),
            )
            // A narrow card puts its title under the artwork rather than beside it, so a wrapped
            // second line would end over the light half of the card. This pass is what keeps the
            // text readable wherever that line stops.
            if (layout.compact) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(bottomScrimBrush),
                )
            }
            Text(
                text = title,
                style =
                    when {
                        layout.compact ->
                            MaterialTheme.typography.titleSmall.copy(
                                fontWeight = if (isExploreTheme) FontWeight.ExtraBold else FontWeight.Bold,
                            )
                        isExploreTheme ->
                            MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold)
                        else ->
                            MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black)
                    },
                color = Color.White,
                maxLines = layout.titleMaxLines,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(
                            start = layout.titleStart,
                            end = layout.titleEnd,
                            bottom = layout.titleBottom,
                        ),
            )
        }
    }
}

@Composable
private fun rememberMoodAndGenresArtworkUrl(endpoint: BrowseEndpoint?): String? {
    endpoint ?: return null

    val cacheKey = buildMoodAndGenresArtworkCacheKey(endpoint)
    val cachedArtwork = moodAndGenresArtworkCache[cacheKey]
    val artworkUrl by produceState(initialValue = cachedArtwork, key1 = cacheKey) {
        if (!value.isNullOrBlank()) return@produceState

        val resolvedArtwork =
            withContext(Dispatchers.IO) {
                YouTube.browse(endpoint.browseId, endpoint.params).getOrNull()?.thumbnail
            }

        if (!resolvedArtwork.isNullOrBlank()) {
            moodAndGenresArtworkCache[cacheKey] = resolvedArtwork
            value = resolvedArtwork
        }
    }

    return artworkUrl
}

@Composable
private fun rememberMoodAndGenresArtworkModel(
    endpoint: BrowseEndpoint?,
    artworkUrl: String?,
): ImageRequest? {
    if (artworkUrl.isNullOrBlank()) return null

    val context = LocalContext.current
    val requestSizePx = with(LocalDensity.current) { MoodAndGenresArtworkRequestSize.roundToPx() }
    val cacheKey =
        remember(endpoint, artworkUrl) {
            endpoint?.let(::buildMoodAndGenresArtworkCacheKey) ?: artworkUrl
        }

    return remember(context, artworkUrl, cacheKey, requestSizePx) {
        ImageRequest
            .Builder(context)
            .data(artworkUrl)
            .memoryCacheKey("mood_and_genres:$cacheKey")
            .diskCacheKey("mood_and_genres:$cacheKey")
            .diskCachePolicy(CachePolicy.ENABLED)
            .size(requestSizePx)
            .build()
    }
}

private fun buildMoodAndGenresArtworkCacheKey(endpoint: BrowseEndpoint): String = "${endpoint.browseId}:${endpoint.params.orEmpty()}"

private val moodAndGenresArtworkCache = ConcurrentHashMap<String, String>()

private val MoodAndGenresButtonShape = RoundedCornerShape(24.dp)
private val MoodAndGenresCoverShape = RoundedCornerShape(16.dp)
private val MoodAndGenresCoverSize = 80.dp
private val MoodAndGenresArtworkRequestSize = 80.dp

/**
 * How one mood/genre card lays itself out at the width it was given.
 *
 * The card used to assume it was wide: the title kept 92dp of its width clear of the artwork and
 * was held to one line. In the Search screen's two-up grid on a 360dp phone a card is 162dp wide,
 * which leaves the title 54dp - four or five characters, so "Chill" and "Feel Good" both arrived
 * as "Chi..." and "Fee..." and every mood had to be guessed from its gradient. The two widths are
 * the same card, so the fix belongs in what the card does with the room it is given.
 *
 * Narrow (below [MoodAndGenresCompactMaxWidth]) drops to two lines and shrinks the cover to 44dp,
 * which widens the title's column to 90dp - wide enough for the longest single-word label the mood
 * strip ships ("Classical" measures about 57dp at the 14sp used here) and for the longest label of
 * all ("Bollywood & Indian", which takes two lines) to read in full. Wide cards keep the original
 * metrics, so the standalone Mood & Genres screen - one 348dp column on this screen size, where the
 * title has always had 240dp - is unchanged, as are tablets and landscape.
 */
internal data class MoodAndGenresCardLayout(
    val compact: Boolean,
    val coverSize: Dp,
    val coverInset: Dp,
    val coverTop: Dp,
    val titleStart: Dp,
    val titleEnd: Dp,
    val titleBottom: Dp,
    val titleMaxLines: Int,
) {
    /** The horizontal room the title actually gets, once both of its insets are taken off. */
    internal fun titleWidth(cardWidth: Dp): Dp = cardWidth - titleStart - titleEnd
}

/** Width below which a cell is treated as the search screen's half-width one. */
private val MoodAndGenresCompactMaxWidth = 210.dp

/** Cover size, and the gap kept between it and the title's column, on a narrow cell. */
private val MoodAndGenresCompactCoverSize = 44.dp
private val MoodAndGenresCompactCoverInset = 8.dp
private val MoodAndGenresCompactGap = 8.dp

internal fun moodAndGenresCardLayout(width: Dp): MoodAndGenresCardLayout {
    val compact = width < MoodAndGenresCompactMaxWidth
    return if (compact) {
        MoodAndGenresCardLayout(
            compact = true,
            coverSize = MoodAndGenresCompactCoverSize,
            coverInset = MoodAndGenresCompactCoverInset,
            coverTop = 8.dp,
            titleStart = 12.dp,
            // The title stops where the artwork's column starts, so a second line can never run
            // under the cover even when the first one stops early.
            titleEnd = MoodAndGenresCompactCoverInset + MoodAndGenresCompactCoverSize + MoodAndGenresCompactGap,
            titleBottom = 10.dp,
            titleMaxLines = 2,
        )
    } else {
        MoodAndGenresCardLayout(
            compact = false,
            coverSize = MoodAndGenresCoverSize,
            coverInset = 12.dp,
            coverTop = 10.dp,
            titleStart = 16.dp,
            titleEnd = 92.dp,
            titleBottom = 16.dp,
            titleMaxLines = 1,
        )
    }
}

val MoodAndGenresButtonHeight = 100.dp
