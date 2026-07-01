/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.hush.music.ui.player

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Player.STATE_ENDED
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.core.net.toUri
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import me.saket.squiggles.SquigglySlider
import app.hush.music.LocalDatabase
import app.hush.music.LocalDownloadUtil
import app.hush.music.LocalPlayerConnection
import app.hush.music.R
import app.hush.music.constants.EnableHapticFeedbackKey
import app.hush.music.constants.PlayerBackgroundStyle
import app.hush.music.constants.PlayerButtonsStyle
import app.hush.music.constants.PlayerDesignStyle
import app.hush.music.constants.LandscapePlayerCompactHeight
import app.hush.music.constants.LandscapePlayerTightHeight
import app.hush.music.constants.landscapeControlsContentFooterGap
import app.hush.music.constants.landscapeControlsFooterReserve
import app.hush.music.constants.landscapeMetadataReservedHeight
import app.hush.music.constants.PlayerHorizontalPadding
import app.hush.music.constants.V6QueueBottomBarHeight
import app.hush.music.constants.SliderStyle
import app.hush.music.db.entities.FormatEntity
import app.hush.music.db.entities.codecLabel
import app.hush.music.extensions.togglePlayPause
import app.hush.music.extensions.toggleRepeatMode
import app.hush.music.models.MediaMetadata
import app.hush.music.playback.ExoDownloadService
import app.hush.music.playback.PlayerConnection
import app.hush.music.ui.component.BottomSheetPageState
import app.hush.music.ui.component.BottomSheetState
import app.hush.music.ui.component.MenuState
import app.hush.music.ui.component.PlayerSliderTrack
import app.hush.music.ui.component.ResizableIconButton
import app.hush.music.ui.menu.PlayerMenu
import app.hush.music.ui.theme.HushDesign
import app.hush.music.ui.theme.PlayerBackgroundColorUtils
import app.hush.music.ui.theme.PlayerSliderColors
import app.hush.music.ui.theme.hushPressable
import app.hush.music.ui.theme.hushPlayButtonBackground
import app.hush.music.ui.utils.ShowMediaInfo
import app.hush.music.ui.utils.highRes
import app.hush.music.utils.isLocalMediaId
import app.hush.music.utils.makeTimeString
import app.hush.music.utils.rememberPreference

private const val PlayerBackgroundMaxBlurRadius = 64f

@Composable
fun PlayerTitleSection(
    mediaMetadata: MediaMetadata,
    textBackgroundColor: Color,
    navController: NavController,
    state: BottomSheetState,
    titleMaxLines: Int = 1,
    centerAligned: Boolean = false,
) {
    // Tap/long-press behavior is centralized; this style keeps its own visual rendering.
    val actions =
        rememberPlayerTitleActions(
            mediaMetadata = mediaMetadata,
            navController = navController,
            state = state,
        )
    AnimatedContent(
        targetState = mediaMetadata.title,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "",
    ) { title ->
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = titleMaxLines,
            overflow = TextOverflow.Ellipsis,
            color = textBackgroundColor,
            textAlign = if (centerAligned) TextAlign.Center else TextAlign.Start,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .basicMarquee()
                    .combinedClickable(
                        enabled = true,
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = actions.onTitleClick,
                        onLongClick = actions.onCopyTitle,
                    ),
        )
    }

    Spacer(Modifier.height(6.dp))

    ClickableArtists(
        artists = mediaMetadata.artists,
        onArtistClick = actions.onArtistClick,
        style = MaterialTheme.typography.titleMedium.copy(color = textBackgroundColor, fontSize = 16.sp),
        onLongClick = actions.onCopyArtists,
        modifier =
            Modifier
                .fillMaxWidth()
                .basicMarquee()
                .padding(end = if (centerAligned) 0.dp else 12.dp),
        textAlign = if (centerAligned) TextAlign.Center else TextAlign.Start,
    )
}

@Composable
fun PlayerTopActions(
    mediaMetadata: MediaMetadata,
    playerDesignStyle: PlayerDesignStyle,
    textButtonColor: Color,
    iconButtonColor: Color,
    textBackgroundColor: Color,
    playerConnection: PlayerConnection,
    navController: NavController,
    menuState: MenuState,
    state: BottomSheetState,
    bottomSheetPageState: BottomSheetPageState,
    context: Context,
    currentSongLiked: Boolean,
    compact: Boolean = false,
    landscape: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsState()
    val database = LocalDatabase.current
    val download by LocalDownloadUtil.current
        .getDownload(mediaMetadata.id)
        .collectAsState(initial = null)
    val librarySong by database.song(mediaMetadata.id).collectAsState(initial = null)
    val isLocalMedia =
        remember(librarySong?.song?.isLocal, mediaMetadata.id) {
            librarySong?.song?.isLocal == true || mediaMetadata.id.isLocalMediaId()
        }

    when (playerDesignStyle) {
        PlayerDesignStyle.V2 -> {
            val shareShape =
                RoundedCornerShape(
                    topStart = 50.dp,
                    bottomStart = 50.dp,
                    topEnd = 10.dp,
                    bottomEnd = 10.dp,
                )

            val favShape =
                RoundedCornerShape(
                    topStart = 10.dp,
                    bottomStart = 10.dp,
                    topEnd = 50.dp,
                    bottomEnd = 50.dp,
                )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(42.dp)
                            .clip(shareShape)
                            .background(textButtonColor)
                            .clickable {
                                val intent =
                                    Intent().apply {
                                        action = Intent.ACTION_SEND
                                        type = "text/plain"
                                        putExtra(
                                            Intent.EXTRA_TEXT,
                                            "https://music.youtube.com/watch?v=${mediaMetadata.id}",
                                        )
                                    }
                                context.startActivity(Intent.createChooser(intent, null))
                            },
                ) {
                    Image(
                        painter = painterResource(R.drawable.share),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(iconButtonColor),
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .size(24.dp),
                    )
                }

                Box(
                    modifier =
                        Modifier
                            .size(42.dp)
                            .clip(favShape)
                            .background(textButtonColor)
                            .clickable {
                                playerConnection.toggleLike()
                            },
                ) {
                    Image(
                        painter =
                            painterResource(
                                if (currentSongLiked) {
                                    R.drawable.favorite
                                } else {
                                    R.drawable.favorite_border
                                },
                            ),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(iconButtonColor),
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .size(24.dp),
                    )
                }
            }
        }

        PlayerDesignStyle.V3, PlayerDesignStyle.V5 -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                val intent =
                                    Intent().apply {
                                        action = Intent.ACTION_SEND
                                        type = "text/plain"
                                        putExtra(
                                            Intent.EXTRA_TEXT,
                                            "https://music.youtube.com/watch?v=${mediaMetadata.id}",
                                        )
                                    }
                                context.startActivity(Intent.createChooser(intent, null))
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.share),
                        contentDescription = null,
                        tint = textBackgroundColor.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp),
                    )
                }
                Box(
                    modifier =
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { playerConnection.toggleLike() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter =
                            painterResource(
                                if (currentSongLiked) {
                                    R.drawable.favorite
                                } else {
                                    R.drawable.favorite_border
                                },
                            ),
                        contentDescription = null,
                        tint =
                            if (currentSongLiked) {
                                MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
                            } else {
                                textBackgroundColor.copy(alpha = 0.7f)
                            },
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        PlayerDesignStyle.V4 -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    onClick = {
                        val intent =
                            Intent().apply {
                                action = Intent.ACTION_SEND
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "https://music.youtube.com/watch?v=${mediaMetadata.id}",
                                )
                            }
                        context.startActivity(Intent.createChooser(intent, null))
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = textBackgroundColor.copy(alpha = 0.12f),
                    modifier =
                        Modifier
                            .height(44.dp)
                            .width(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            painter = painterResource(R.drawable.share),
                            contentDescription = null,
                            tint = textBackgroundColor,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                Surface(
                    onClick = { playerConnection.toggleLike() },
                    shape = RoundedCornerShape(14.dp),
                    color =
                        if (currentSongLiked) {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.25f)
                        } else {
                            textBackgroundColor.copy(alpha = 0.12f)
                        },
                    modifier =
                        Modifier
                            .height(44.dp)
                            .width(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            painter =
                                painterResource(
                                    if (currentSongLiked) {
                                        R.drawable.favorite
                                    } else {
                                        R.drawable.favorite_border
                                    },
                                ),
                            contentDescription = null,
                            tint =
                                if (currentSongLiked) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    textBackgroundColor
                                },
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                // More menu button - cinematic glass card
                Surface(
                    onClick = {
                        menuState.show {
                            PlayerMenu(
                                mediaMetadata = mediaMetadata,
                                navController = navController,
                                playerBottomSheetState = state,
                                onShowDetailsDialog = {
                                    mediaMetadata.id.let {
                                        bottomSheetPageState.show {
                                            ShowMediaInfo(it)
                                        }
                                    }
                                },
                                onDismiss = menuState::dismiss,
                            )
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = textBackgroundColor.copy(alpha = 0.12f),
                    modifier =
                        Modifier
                            .height(44.dp)
                            .width(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            painter = painterResource(R.drawable.more_horiz),
                            contentDescription = null,
                            tint = textBackgroundColor,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }

        PlayerDesignStyle.V1 -> {
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(textButtonColor)
                        .clickable {
                            val intent =
                                Intent().apply {
                                    action = Intent.ACTION_SEND
                                    type = "text/plain"
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "https://music.youtube.com/watch?v=${mediaMetadata.id}",
                                    )
                                }
                            context.startActivity(Intent.createChooser(intent, null))
                        },
            ) {
                Image(
                    painter = painterResource(R.drawable.share),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(iconButtonColor),
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .size(24.dp),
                )
            }

            Spacer(modifier = Modifier.size(12.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(textButtonColor)
                        .clickable {
                            menuState.show {
                                PlayerMenu(
                                    mediaMetadata = mediaMetadata,
                                    navController = navController,
                                    playerBottomSheetState = state,
                                    onShowDetailsDialog = {
                                        mediaMetadata.id.let {
                                            bottomSheetPageState.show {
                                                ShowMediaInfo(it)
                                            }
                                        }
                                    },
                                    onDismiss = menuState::dismiss,
                                )
                            }
                        },
            ) {
                Image(
                    painter = painterResource(R.drawable.more_horiz),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(iconButtonColor),
                )
            }
        }

        PlayerDesignStyle.V6 -> {
            val buttonSize =
                when {
                    landscape -> 44.dp
                    compact -> 36.dp
                    else -> 42.dp
                }
            val iconSize =
                when {
                    landscape -> 22.dp
                    compact -> 18.dp
                    else -> 20.dp
                }
            val actionGap = if (landscape) 10.dp else if (compact) 6.dp else 8.dp

            Row(
                horizontalArrangement = Arrangement.spacedBy(actionGap, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Surface(
                    onClick = {
                        val intent =
                            Intent().apply {
                                action = Intent.ACTION_SEND
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "https://music.youtube.com/watch?v=${mediaMetadata.id}",
                                )
                            }
                        context.startActivity(Intent.createChooser(intent, null))
                    },
                    shape =
                        RoundedCornerShape(
                            topStart = 50.dp,
                            bottomStart = 50.dp,
                            topEnd = 6.dp,
                            bottomEnd = 6.dp,
                        ),
                    color = textBackgroundColor.copy(alpha = 0.12f),
                    modifier = Modifier.size(buttonSize),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            painter = painterResource(R.drawable.share),
                            contentDescription = null,
                            tint = textBackgroundColor,
                            modifier = Modifier.size(iconSize),
                        )
                    }
                }

                Surface(
                    onClick = { playerConnection.toggleLike() },
                    shape = RoundedCornerShape(50),
                    color =
                        if (currentSongLiked) {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
                        } else {
                            textBackgroundColor.copy(alpha = 0.12f)
                        },
                    modifier = Modifier.size(buttonSize),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            painter =
                                painterResource(
                                    if (currentSongLiked) {
                                        R.drawable.favorite
                                    } else {
                                        R.drawable.favorite_border
                                    },
                                ),
                            contentDescription = null,
                            tint =
                                if (currentSongLiked) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    textBackgroundColor
                                },
                            modifier = Modifier.size(iconSize),
                        )
                    }
                }

                if (!isLocalMedia) {
                    val isDownloading =
                        download?.state == Download.STATE_QUEUED ||
                            download?.state == Download.STATE_DOWNLOADING
                    val isDownloaded = download?.state == Download.STATE_COMPLETED
                    Surface(
                        onClick = {
                            handleV6PlayerDownloadClick(
                                context = context,
                                database = database,
                                mediaMetadata = mediaMetadata,
                                download = download,
                            )
                        },
                        shape = RoundedCornerShape(50),
                        color = textBackgroundColor.copy(alpha = 0.12f),
                        modifier = Modifier.size(buttonSize),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            when {
                                isDownloading -> {
                                    CircularWavyProgressIndicator(
                                        modifier = Modifier.size(iconSize),
                                        color = textBackgroundColor,
                                    )
                                }

                                isDownloaded -> {
                                    Icon(
                                        painter = painterResource(R.drawable.offline),
                                        contentDescription = stringResource(R.string.remove_download),
                                        tint = textBackgroundColor,
                                        modifier = Modifier.size(iconSize),
                                    )
                                }

                                else -> {
                                    Icon(
                                        painter = painterResource(R.drawable.download),
                                        contentDescription = stringResource(R.string.action_download),
                                        tint = textBackgroundColor,
                                        modifier = Modifier.size(iconSize),
                                    )
                                }
                            }
                        }
                    }
                }

                Surface(
                    onClick = {
                        menuState.show {
                            PlayerMenu(
                                mediaMetadata = mediaMetadata,
                                navController = navController,
                                playerBottomSheetState = state,
                                onShowDetailsDialog = {
                                    mediaMetadata.id.let {
                                        bottomSheetPageState.show {
                                            ShowMediaInfo(it)
                                        }
                                    }
                                },
                                onDismiss = menuState::dismiss,
                            )
                        }
                    },
                    shape =
                        RoundedCornerShape(
                            topStart = 6.dp,
                            bottomStart = 6.dp,
                            topEnd = 50.dp,
                            bottomEnd = 50.dp,
                        ),
                    color = textBackgroundColor.copy(alpha = 0.12f),
                    modifier = Modifier.size(buttonSize),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            painter = painterResource(R.drawable.more_horiz),
                            contentDescription = null,
                            tint = textBackgroundColor,
                            modifier = Modifier.size(iconSize),
                        )
                    }
                }
            }
        }

        PlayerDesignStyle.V7, PlayerDesignStyle.V8, PlayerDesignStyle.V9 -> {
            Unit
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSlider(
    sliderStyle: SliderStyle,
    sliderPosition: Long?,
    position: Long,
    duration: Long,
    isPlaying: Boolean,
    textButtonColor: Color,
    onValueChange: (Long) -> Unit,
    onValueChangeFinished: () -> Unit,
    horizontalPadding: Dp = PlayerHorizontalPadding,
) {
    val safeDuration = if (duration <= 0L) 0f else duration.toFloat()
    val safeValue = (sliderPosition ?: position).toFloat().coerceIn(0f, maxOf(0f, safeDuration))

    StyledPlaybackSlider(
        sliderStyle = sliderStyle,
        value = safeValue,
        valueRange = 0f..maxOf(1f, safeDuration),
        onValueChange = { onValueChange(it.toLong()) },
        onValueChangeFinished = onValueChangeFinished,
        activeColor = textButtonColor,
        isPlaying = isPlaying,
        modifier = Modifier.padding(horizontal = horizontalPadding),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyledPlaybackSlider(
    sliderStyle: SliderStyle,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    activeColor: Color,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    when (sliderStyle) {
        SliderStyle.Standard -> {
            Slider(
                value = value,
                valueRange = valueRange,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                colors = PlayerSliderColors.standardSliderColors(activeColor),
                modifier = modifier,
            )
        }

        SliderStyle.Wavy -> {
            SquigglySlider(
                value = value,
                valueRange = valueRange,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                colors = PlayerSliderColors.wavySliderColors(activeColor),
                modifier = modifier,
                squigglesSpec =
                    SquigglySlider.SquigglesSpec(
                        amplitude = if (isPlaying) 2.dp else 0.dp,
                        strokeWidth = 6.dp,
                    ),
            )
        }

        SliderStyle.Thick -> {
            Slider(
                value = value,
                valueRange = valueRange,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                colors = PlayerSliderColors.thickSliderColors(activeColor),
                thumb = { Spacer(modifier = Modifier.size(0.dp)) },
                track = { sliderState ->
                    PlayerSliderTrack(
                        sliderState = sliderState,
                        colors = PlayerSliderColors.thickSliderColors(activeColor),
                        trackHeight = 12.dp,
                    )
                },
                modifier = modifier,
            )
        }

        SliderStyle.Circular -> {
            SquigglySlider(
                value = value,
                valueRange = valueRange,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                colors = PlayerSliderColors.circularSliderColors(activeColor),
                modifier = modifier,
                squigglesSpec =
                    SquigglySlider.SquigglesSpec(
                        amplitude = if (isPlaying) 2.dp else 0.dp,
                        strokeWidth = 6.dp,
                    ),
            )
        }

        SliderStyle.Simple -> {
            Slider(
                value = value,
                valueRange = valueRange,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                colors = PlayerSliderColors.simpleSliderColors(activeColor),
                thumb = { Spacer(modifier = Modifier.size(0.dp)) },
                track = { sliderState ->
                    PlayerSliderTrack(
                        sliderState = sliderState,
                        colors = PlayerSliderColors.simpleSliderColors(activeColor),
                        trackHeight = 3.dp,
                    )
                },
                modifier = modifier,
            )
        }
    }
}

@Composable
fun PlayerTimeLabel(
    sliderPosition: Long?,
    position: Long,
    duration: Long,
    textBackgroundColor: Color,
    showRemainingTime: Boolean = false,
    centerContent: @Composable (() -> Unit)? = null,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PlayerHorizontalPadding + 4.dp),
    ) {
        Text(
            text = makeTimeString(sliderPosition ?: position),
            style = MaterialTheme.typography.labelMedium,
            color = textBackgroundColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterStart),
        )

        if (centerContent != null) {
            Box(
                modifier = Modifier.align(Alignment.Center),
                contentAlignment = Alignment.Center,
            ) {
                centerContent()
            }
        }

        Text(
            text =
                if (duration != C.TIME_UNSET) {
                    if (showRemainingTime) {
                        val remaining = duration - (sliderPosition ?: position)
                        "-${makeTimeString(remaining.coerceAtLeast(0))}"
                    } else {
                        makeTimeString(duration)
                    }
                } else {
                    ""
                },
            style = MaterialTheme.typography.labelMedium,
            color = textBackgroundColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
fun PlayerPlaybackControls(
    playerDesignStyle: PlayerDesignStyle,
    playbackState: Int,
    isPlaying: Boolean,
    isLoading: Boolean,
    repeatMode: Int,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    textButtonColor: Color,
    iconButtonColor: Color,
    textBackgroundColor: Color,
    icBackgroundColor: Color,
    playPauseRoundness: androidx.compose.ui.unit.Dp,
    playerConnection: PlayerConnection,
    currentSongLiked: Boolean,
    landscape: Boolean = false,
    landscapeCompact: Boolean = false,
    useWeightedTransportLayout: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsState()
    val view = LocalView.current
    val (enableHapticFeedback) = rememberPreference(EnableHapticFeedbackKey, true)

    val cinematicPlayPauseCorner by animateDpAsState(
        targetValue = if (isPlaying) 28.dp else 44.dp,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        label = "cinematicPlayPauseCorner",
    )

    when (playerDesignStyle) {
        PlayerDesignStyle.V2 -> {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
            ) {
                val maxW = maxWidth
                val playButtonHeight = maxW / 6f
                val playButtonWidth = playButtonHeight * 1.6f
                val sideButtonHeight = playButtonHeight * 0.8f
                val sideButtonWidth = sideButtonHeight * 1.3f

                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FilledTonalIconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            playerConnection.seekToPrevious()
                        },
                        enabled = canSkipPrevious,
                        colors =
                            IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = textButtonColor,
                                contentColor = iconButtonColor,
                            ),
                        modifier =
                            Modifier
                                .size(width = sideButtonWidth, height = sideButtonHeight)
                                .clip(RoundedCornerShape(32.dp)),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.skip_previous),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    FilledIconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (playbackState == STATE_ENDED) {
                                playerConnection.player.seekTo(0, 0)
                                playerConnection.player.playWhenReady = true
                            } else {
                                playerConnection.player.togglePlayPause()
                            }
                        },
                        colors =
                            IconButtonDefaults.filledIconButtonColors(
                                containerColor = textButtonColor,
                                contentColor = iconButtonColor,
                            ),
                        modifier =
                            Modifier
                                .size(width = playButtonWidth, height = playButtonHeight)
                                .clip(RoundedCornerShape(32.dp)),
                    ) {
                        if (isLoading) {
                            CircularWavyProgressIndicator(
                                modifier = Modifier.size(42.dp),
                                color = iconButtonColor,
                            )
                        } else {
                            Icon(
                                painter =
                                    painterResource(
                                        when {
                                            playbackState == STATE_ENDED -> R.drawable.replay
                                            isPlaying -> R.drawable.pause
                                            else -> R.drawable.play
                                        },
                                    ),
                                contentDescription = null,
                                modifier = Modifier.size(42.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    FilledTonalIconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            playerConnection.seekToNext()
                        },
                        enabled = canSkipNext,
                        colors =
                            IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = textButtonColor,
                                contentColor = iconButtonColor,
                            ),
                        modifier =
                            Modifier
                                .size(width = sideButtonWidth, height = sideButtonHeight)
                                .clip(RoundedCornerShape(32.dp)),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.skip_next),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            }
        }

        PlayerDesignStyle.V3 -> {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PlayerHorizontalPadding),
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(textBackgroundColor.copy(alpha = 0.08f))
                                .hushPressable(
                                    enabled = canSkipPrevious,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        playerConnection.seekToPrevious()
                                    },
                                    pressScale = HushDesign.ChipPressScale,
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.skip_previous),
                            contentDescription = null,
                            tint = textBackgroundColor.copy(alpha = if (canSkipPrevious) 0.9f else 0.4f),
                            modifier = Modifier.size(26.dp),
                        )
                    }

                    Box(
                        modifier =
                            Modifier
                                .size(70.dp)
                                .clip(RoundedCornerShape(50))
                                .background(textBackgroundColor)
                                .hushPressable(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        if (playbackState == STATE_ENDED) {
                                            playerConnection.player.seekTo(0, 0)
                                            playerConnection.player.playWhenReady = true
                                        } else {
                                            playerConnection.player.togglePlayPause()
                                        }
                                    },
                                    pressScale = HushDesign.PressScale,
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isLoading) {
                            CircularWavyProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = icBackgroundColor,
                            )
                        } else {
                            Icon(
                                painter =
                                    painterResource(
                                        when {
                                            playbackState == STATE_ENDED -> R.drawable.replay
                                            isPlaying -> R.drawable.pause
                                            else -> R.drawable.play
                                        },
                                    ),
                                contentDescription = null,
                                tint = icBackgroundColor,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                    }

                    Box(
                        modifier =
                            Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(textBackgroundColor.copy(alpha = 0.08f))
                                .hushPressable(
                                    enabled = canSkipNext,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        playerConnection.seekToNext()
                                    },
                                    pressScale = HushDesign.ChipPressScale,
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.skip_next),
                            contentDescription = null,
                            tint = textBackgroundColor.copy(alpha = if (canSkipNext) 0.9f else 0.4f),
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }
        }

        PlayerDesignStyle.V4 -> {
            BoxWithConstraints(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PlayerHorizontalPadding),
            ) {
                val baseLarge = 56.dp
                val baseSmall = 46.dp
                val baseGap = 12.dp
                val baseLargeIcon = 28.dp
                val baseSmallIcon = 22.dp
                val baseLargeRadius = 18.dp
                val baseSmallRadius = 16.dp
                val centerSize = 88.dp
                val centerPadding = 40.dp
                val sideTotal = (maxWidth - centerSize - centerPadding) / 2f
                val minScale = if (landscape) 0.85f else 0.6f
                val scale = (sideTotal / baseLarge).coerceAtMost(1f).coerceAtLeast(minScale)
                val large = baseLarge * scale
                val gap = baseGap * scale
                val largeIcon = baseLargeIcon * scale
                val largeRadius = baseLargeRadius * scale

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                playerConnection.seekToPrevious()
                            },
                            enabled = canSkipPrevious,
                            shape = RoundedCornerShape(largeRadius),
                            color = textBackgroundColor.copy(alpha = 0.15f),
                            modifier = Modifier.size(large),
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.skip_previous),
                                    contentDescription = null,
                                    tint =
                                        textBackgroundColor.copy(
                                            alpha = if (canSkipPrevious) 1f else 0.4f,
                                        ),
                                    modifier = Modifier.size(largeIcon),
                                )
                            }
                        }
                    }

                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (playbackState == STATE_ENDED) {
                                playerConnection.player.seekTo(0, 0)
                                playerConnection.player.playWhenReady = true
                            } else {
                                playerConnection.player.togglePlayPause()
                            }
                        },
                        shape = RoundedCornerShape(cinematicPlayPauseCorner),
                        color = Color.Transparent,
                        modifier =
                            Modifier
                                .padding(horizontal = 20.dp)
                                .size(88.dp)
                                .hushPlayButtonBackground(RoundedCornerShape(cinematicPlayPauseCorner)),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isLoading) {
                                CircularWavyProgressIndicator(
                                    modifier = Modifier.size(40.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                Icon(
                                    painter =
                                        painterResource(
                                            when {
                                                playbackState == STATE_ENDED -> R.drawable.replay
                                                isPlaying -> R.drawable.pause
                                                else -> R.drawable.play
                                            },
                                        ),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(44.dp),
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                playerConnection.seekToNext()
                            },
                            enabled = canSkipNext,
                            shape = RoundedCornerShape(largeRadius),
                            color = textBackgroundColor.copy(alpha = 0.15f),
                            modifier = Modifier.size(large),
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.skip_next),
                                    contentDescription = null,
                                    tint =
                                        textBackgroundColor.copy(
                                            alpha = if (canSkipNext) 1f else 0.4f,
                                        ),
                                    modifier = Modifier.size(largeIcon),
                                )
                            }
                        }
                    }
                }
            }
        }

        PlayerDesignStyle.V1, PlayerDesignStyle.V5 -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PlayerHorizontalPadding),
            ) {
                ResizableIconButton(
                    icon = R.drawable.skip_previous,
                    enabled = canSkipPrevious,
                    color = textBackgroundColor,
                    modifier = Modifier.size(32.dp),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        playerConnection.seekToPrevious()
                    },
                )

                Spacer(Modifier.width(8.dp))

                Box(
                    modifier =
                        Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(playPauseRoundness))
                            .background(textButtonColor)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (playbackState == STATE_ENDED) {
                                    playerConnection.player.seekTo(0, 0)
                                    playerConnection.player.playWhenReady = true
                                } else {
                                    playerConnection.player.togglePlayPause()
                                }
                            },
                ) {
                    if (isLoading) {
                        CircularWavyProgressIndicator(
                            modifier =
                                Modifier
                                    .align(Alignment.Center)
                                    .size(36.dp),
                            color = iconButtonColor,
                        )
                    } else {
                        Image(
                            painter =
                                painterResource(
                                    if (playbackState ==
                                        STATE_ENDED
                                    ) {
                                        R.drawable.replay
                                    } else if (isPlaying) {
                                        R.drawable.pause
                                    } else {
                                        R.drawable.play
                                    },
                                ),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(iconButtonColor),
                            modifier =
                                Modifier
                                    .align(Alignment.Center)
                                    .size(36.dp),
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                ResizableIconButton(
                    icon = R.drawable.skip_next,
                    enabled = canSkipNext,
                    color = textBackgroundColor,
                    modifier = Modifier.size(32.dp),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        playerConnection.seekToNext()
                    },
                )
            }
        }

        PlayerDesignStyle.V6 -> {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (landscape) 0.dp else PlayerHorizontalPadding),
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = textBackgroundColor.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    BoxWithConstraints(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(6.dp),
                    ) {
                        val buttonGap = 6.dp
                        val landscapeWeighted = landscape && useWeightedTransportLayout
                        val portraitWideTransport = !landscape && !landscapeCompact && !useWeightedTransportLayout
                        val baseSideHeight =
                            when {
                                landscapeWeighted -> 60.dp
                                landscapeCompact -> 52.dp
                                landscape -> 78.dp
                                portraitWideTransport -> 58.dp
                                else -> 56.dp
                            }
                        val basePlayWidth =
                            when {
                                landscapeWeighted -> 76.dp
                                landscapeCompact -> 72.dp
                                landscape -> 96.dp
                                else -> 88.dp
                            }
                        val basePlayHeight =
                            when {
                                landscapeWeighted -> 64.dp
                                landscapeCompact -> 58.dp
                                landscape -> 84.dp
                                portraitWideTransport -> 78.dp
                                else -> 80.dp
                            }
                        val useFixedLandscapeWidths = landscape && !useWeightedTransportLayout
                        val baseSideWidth =
                            when {
                                useFixedLandscapeWidths && landscapeCompact -> 64.dp
                                useFixedLandscapeWidths && landscape -> 90.dp
                                else -> null
                            }
                        val baseSideIconSize =
                            when {
                                landscapeWeighted -> 28.dp
                                landscapeCompact -> 24.dp
                                landscape -> 32.dp
                                else -> 28.dp
                            }
                        val basePlayIconSize =
                            when {
                                landscapeWeighted -> 36.dp
                                landscapeCompact -> 32.dp
                                landscape -> 44.dp
                                else -> 44.dp
                            }

                        val layoutScale =
                            if (baseSideWidth != null) {
                                val requiredWidth = baseSideWidth * 2 + basePlayWidth + buttonGap * 2
                                (maxWidth / requiredWidth).coerceAtMost(1f)
                            } else {
                                1f
                            }

                        val sideHeight = baseSideHeight * layoutScale
                        val playWidth = basePlayWidth * layoutScale
                        val playHeight = basePlayHeight * layoutScale
                        val sideIconSize = baseSideIconSize * layoutScale
                        val playIconSize = basePlayIconSize * layoutScale
                        val sideWidth = baseSideWidth?.let { it * layoutScale }

                        val portraitPlaySize =
                            if (portraitWideTransport) {
                                76.dp
                            } else {
                                playHeight
                            }
                        val portraitSideWeight = if (portraitWideTransport) 1.85f else 1f

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val prevShape =
                                RoundedCornerShape(
                                    topStart = 22.dp,
                                    bottomStart = 22.dp,
                                    topEnd = 8.dp,
                                    bottomEnd = 8.dp,
                                )
                            val nextShape =
                                RoundedCornerShape(
                                    topStart = 8.dp,
                                    bottomStart = 8.dp,
                                    topEnd = 22.dp,
                                    bottomEnd = 22.dp,
                                )
                            val playShape =
                                if (portraitWideTransport) {
                                    CircleShape
                                } else {
                                    RoundedCornerShape(28.dp)
                                }
                            val sideModifier =
                                if (portraitWideTransport) {
                                    Modifier.weight(portraitSideWeight).height(sideHeight)
                                } else if (sideWidth != null) {
                                    Modifier
                                        .width(sideWidth)
                                        .height(sideHeight)
                                } else {
                                    Modifier
                                        .weight(1f)
                                        .height(sideHeight)
                                }
                            val playModifier =
                                if (portraitWideTransport) {
                                    Modifier.size(portraitPlaySize)
                                } else {
                                    Modifier.size(width = playWidth, height = playHeight)
                                }

                            Surface(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    playerConnection.seekToPrevious()
                                },
                                enabled = canSkipPrevious,
                                shape = prevShape,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = sideModifier,
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.skip_previous),
                                        contentDescription = null,
                                        tint =
                                            MaterialTheme.colorScheme.onSecondaryContainer.copy(
                                                alpha = if (canSkipPrevious) 1f else 0.4f,
                                            ),
                                        modifier = Modifier.size(sideIconSize),
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(buttonGap))

                            Surface(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (playbackState == STATE_ENDED) {
                                        playerConnection.player.seekTo(0, 0)
                                        playerConnection.player.playWhenReady = true
                                    } else {
                                        playerConnection.player.togglePlayPause()
                                    }
                                },
                                shape = playShape,
                                color = Color.Transparent,
                                modifier =
                                    playModifier
                                        .hushPlayButtonBackground(playShape),
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isLoading) {
                                        CircularWavyProgressIndicator(
                                            modifier = Modifier.size(if (landscape) 46.dp else 40.dp),
                                            color = MaterialTheme.colorScheme.onPrimary,
                                        )
                                    } else {
                                        Icon(
                                            painter =
                                                painterResource(
                                                    when {
                                                        playbackState == STATE_ENDED -> R.drawable.replay
                                                        isPlaying -> R.drawable.pause
                                                        else -> R.drawable.play
                                                    },
                                                ),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(playIconSize),
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(buttonGap))

                            Surface(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    playerConnection.seekToNext()
                                },
                                enabled = canSkipNext,
                                shape = nextShape,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = sideModifier,
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.skip_next),
                                        contentDescription = null,
                                        tint =
                                            MaterialTheme.colorScheme.onSecondaryContainer.copy(
                                                alpha = if (canSkipNext) 1f else 0.4f,
                                            ),
                                        modifier = Modifier.size(sideIconSize),
                                    )
                                }
                            }
                        }
                    }
                }

            }
        }

        PlayerDesignStyle.V7, PlayerDesignStyle.V8, PlayerDesignStyle.V9 -> {
            Unit
        }
    }
}

/**
 * Wrapper composable that combines all player control components.
 * This replaces the large inline controlsContent lambda in BottomSheetPlayer
 * to reduce JIT compilation overhead.
 */
@Composable
fun PlayerControlsContent(
    mediaMetadata: MediaMetadata,
    playerDesignStyle: PlayerDesignStyle,
    sliderStyle: SliderStyle,
    playbackState: Int,
    isPlaying: Boolean,
    isLoading: Boolean,
    repeatMode: Int,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    textButtonColor: Color,
    iconButtonColor: Color,
    textBackgroundColor: Color,
    icBackgroundColor: Color,
    sliderPosition: Long?,
    position: Long,
    duration: Long,
    playerConnection: PlayerConnection,
    navController: NavController,
    state: BottomSheetState,
    menuState: MenuState,
    bottomSheetPageState: BottomSheetPageState,
    context: Context,
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    currentFormat: FormatEntity? = null,
    landscape: Boolean = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE,
    landscapeCompact: Boolean = false,
    onLyricsClick: (() -> Unit)? = null,
    onLibraryClick: (() -> Unit)? = null,
    onQueueClick: (() -> Unit)? = null,
    horizontalPadding: Dp? = null,
) {
    val currentSong by playerConnection.currentSong.collectAsState(initial = null)
    val currentSongLiked = currentSong?.song?.liked == true

    val playPauseRoundness by animateDpAsState(
        targetValue = if (isPlaying) 24.dp else 36.dp,
        animationSpec = tween(durationMillis = 90, easing = LinearEasing),
        label = "playPauseRoundness",
    )

    val titleSectionGap =
        when {
            landscape && landscapeCompact -> 6.dp
            landscape -> 8.dp
            else -> 12.dp
        }
    val sliderTimeGap = if (landscape) 2.dp else 4.dp
    val progressToTransportGap =
        when {
            landscape && landscapeCompact -> 10.dp
            landscape -> 20.dp
            else -> 12.dp
        }
    val playbackControlsGap =
        when {
            landscape && landscapeCompact -> 4.dp
            landscape -> 8.dp
            else -> 12.dp
        }

    val controlsHorizontalPadding =
        horizontalPadding ?: when {
            playerDesignStyle == PlayerDesignStyle.V6 && landscapeCompact -> 8.dp
            landscape && landscapeCompact -> 12.dp
            else -> PlayerHorizontalPadding
        }

    val progressBody: @Composable ColumnScope.() -> Unit = {
        PlayerSlider(
            sliderStyle = sliderStyle,
            sliderPosition = sliderPosition,
            position = position,
            duration = duration,
            isPlaying = isPlaying,
            textButtonColor = textButtonColor,
            onValueChange = onSliderValueChange,
            onValueChangeFinished = onSliderValueChangeFinished,
            horizontalPadding = controlsHorizontalPadding,
        )

        Spacer(Modifier.height(sliderTimeGap))

        PlayerTimeLabel(
            sliderPosition = sliderPosition,
            position = position,
            duration = duration,
            textBackgroundColor = textBackgroundColor,
            showRemainingTime = playerDesignStyle == PlayerDesignStyle.V7,
            centerContent =
                if (playerDesignStyle == PlayerDesignStyle.V7 && currentFormat != null) {
                    {
                        val codec = currentFormat.mimeType.substringAfter("/").uppercase()
                        val label =
                            when {
                                codec.contains("FLAC") || codec.contains("ALAC") -> "Lossless"
                                codec.contains("OPUS") -> codec
                                codec.contains("AAC") -> codec
                                codec.contains("MP4A") -> "AAC"
                                codec.contains("VORBIS") -> "Vorbis"
                                else -> codec
                            }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = textBackgroundColor.copy(alpha = 0.12f),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.graphic_eq),
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = textBackgroundColor.copy(alpha = 0.8f),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = textBackgroundColor.copy(alpha = 0.8f),
                                )
                            }
                        }
                    }
                } else {
                    null
                },
        )
    }

    val controlsBody: @Composable ColumnScope.() -> Unit = {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = controlsHorizontalPadding),
        ) {
            PlayerTitleSection(
                mediaMetadata = mediaMetadata,
                textBackgroundColor = textBackgroundColor,
                navController = navController,
                state = state,
                titleMaxLines = if (landscape && landscapeCompact) 2 else 3,
            )
        }

        if (!landscape) {
            Spacer(Modifier.height(titleSectionGap))
            progressBody()
            Spacer(Modifier.height(playbackControlsGap))
        }
    }

    val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsState()

    val playbackControls: @Composable ColumnScope.() -> Unit = {
        if (landscape) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = controlsHorizontalPadding),
            ) {
                progressBody()
                Spacer(Modifier.height(progressToTransportGap))
            }
        }

        PlayerPlaybackControls(
            playerDesignStyle = playerDesignStyle,
            playbackState = playbackState,
            isPlaying = isPlaying,
            isLoading = isLoading,
            repeatMode = repeatMode,
            canSkipPrevious = canSkipPrevious,
            canSkipNext = canSkipNext,
            textButtonColor = textButtonColor,
            iconButtonColor = iconButtonColor,
            textBackgroundColor = textBackgroundColor,
            icBackgroundColor = icBackgroundColor,
            playPauseRoundness = playPauseRoundness,
            playerConnection = playerConnection,
            currentSongLiked = currentSongLiked,
            landscape = landscape,
            landscapeCompact = landscapeCompact,
        )

        if (playerDesignStyle != PlayerDesignStyle.V7 &&
            playerDesignStyle != PlayerDesignStyle.V8 &&
            playerDesignStyle != PlayerDesignStyle.V9
        ) {
            Spacer(Modifier.height(if (landscapeCompact) 6.dp else 12.dp))

            if (playerDesignStyle == PlayerDesignStyle.V6) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = if (landscape) 0.dp else controlsHorizontalPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PlayerTopActions(
                        mediaMetadata = mediaMetadata,
                        playerDesignStyle = playerDesignStyle,
                        textButtonColor = textButtonColor,
                        iconButtonColor = iconButtonColor,
                        textBackgroundColor = textBackgroundColor,
                        playerConnection = playerConnection,
                        navController = navController,
                        menuState = menuState,
                        state = state,
                        bottomSheetPageState = bottomSheetPageState,
                        context = context,
                        currentSongLiked = currentSongLiked,
                    )

                    Spacer(Modifier.height(if (landscapeCompact) 8.dp else 10.dp))

                    V6ShuffleRepeatRow(
                        shuffleModeEnabled = shuffleModeEnabled,
                        repeatMode = repeatMode,
                        accent = MaterialTheme.colorScheme.primary,
                        foreground = textBackgroundColor,
                        playerConnection = playerConnection,
                        compact = landscapeCompact,
                    )

                    if (onLyricsClick != null && onLibraryClick != null) {
                        Spacer(Modifier.height(if (landscapeCompact) 6.dp else 8.dp))
                        V6LibraryLyricsRow(
                            textBackgroundColor = textBackgroundColor,
                            onLibraryClick = onLibraryClick,
                            onLyricsClick = onLyricsClick,
                            horizontalPadding = 0.dp,
                            compact = landscapeCompact,
                        )
                    }
                }
            } else {
                PlayerLandscapeSecondaryActions(
                    mediaMetadata = mediaMetadata,
                    currentSongLiked = currentSongLiked,
                    shuffleModeEnabled = shuffleModeEnabled,
                    repeatMode = repeatMode,
                    accent = MaterialTheme.colorScheme.primary,
                    foreground = textBackgroundColor,
                    playerConnection = playerConnection,
                    onMenuClick = {
                        menuState.show {
                            PlayerMenu(
                                mediaMetadata = mediaMetadata,
                                navController = navController,
                                playerBottomSheetState = state,
                                onShowDetailsDialog = {
                                    bottomSheetPageState.show {
                                        ShowMediaInfo(mediaMetadata.id)
                                    }
                                },
                                onDismiss = menuState::dismiss,
                            )
                        }
                    },
                    onLyricsClick = onLyricsClick,
                    onQueueClick = onQueueClick,
                    modifier = Modifier.padding(horizontal = controlsHorizontalPadding),
                    compact = landscapeCompact,
                )
            }
        }
    }

    if (landscape) {
        LandscapePlayerControlsColumn(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            compact = landscapeCompact,
            footerContent = playbackControls,
            content = controlsBody,
        )
    } else {
        Column {
            controlsBody()
            playbackControls()
        }
    }
}

@Composable
private fun LandscapePlayerControlsColumn(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    footerContent: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )

        Spacer(modifier = Modifier.weight(1f))

        if (footerContent != null) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = footerContent,
            )
        }
    }
}

private fun landscapePlayerArtworkSize(
    maxWidth: Dp,
    maxHeight: Dp,
    verticalPadding: Dp,
    minControlsHeight: Dp,
    maxHeightFraction: Float,
    maxWidthFraction: Float,
    minSize: Dp = 120.dp,
): Dp {
    val availableHeight = (maxHeight - verticalPadding).coerceAtLeast(minSize)
    val heightCap = (availableHeight - minControlsHeight).coerceAtLeast(minSize)
    return (maxHeight * maxHeightFraction)
        .coerceAtMost(maxWidth * maxWidthFraction)
        .coerceAtMost(heightCap)
        .coerceAtLeast(minSize)
}

/**
 * Expressive landscape shell: artwork on the left, controls on the right,
 * Queue / Lyrics bar pinned along the bottom edge.
 */
@Composable
fun V6LandscapePlayerContent(
    artworkContent: @Composable androidx.compose.foundation.layout.BoxScope.(artSize: androidx.compose.ui.unit.Dp) -> Unit,
    controlsContent: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
    textBackgroundColor: Color,
    sleepTimerEnabled: Boolean,
    sleepTimerTimeLeft: Long,
    onExpandQueue: () -> Unit,
    onSleepTimerClick: () -> Unit,
    onShowLyrics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val horizontalPadding = if (maxWidth < 560.dp) 10.dp else 16.dp
        val verticalPadding = if (maxHeight < 340.dp) 4.dp else 8.dp
        val contentGap = if (maxWidth < 560.dp) 14.dp else 20.dp
        val artFraction = if (maxWidth < 560.dp) 0.40f else 0.44f
        val availableHeight = maxHeight - verticalPadding - V6QueueBottomBarHeight - 12.dp
        val artColumnWidth = maxWidth * artFraction
        val artSize =
            minOf(availableHeight * 0.96f, artColumnWidth * 0.98f)
                .coerceIn(210.dp, 440.dp)

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = verticalPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(contentGap),
            ) {
                Box(
                    modifier =
                        Modifier
                            .weight(artFraction)
                            .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    artworkContent(artSize)
                }

                Column(
                    modifier =
                        Modifier
                            .weight(1f - artFraction)
                            .fillMaxHeight()
                            .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    content = controlsContent,
                )
            }

            V6QueueBottomBar(
                textBackgroundColor = textBackgroundColor,
                sleepTimerEnabled = sleepTimerEnabled,
                sleepTimerTimeLeft = sleepTimerTimeLeft,
                onExpandQueue = onExpandQueue,
                onSleepTimerClick = onSleepTimerClick,
                onShowLyrics = onShowLyrics,
                modifier = Modifier.zIndex(3f),
            )
        }
    }
}

/**
 * Expressive portrait controls — anchored to the bottom above the queue bar.
 */
@Composable
fun V6PortraitControlsPanel(
    mediaMetadata: MediaMetadata,
    playerDesignStyle: PlayerDesignStyle,
    sliderStyle: SliderStyle,
    playbackState: Int,
    isPlaying: Boolean,
    isLoading: Boolean,
    repeatMode: Int,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    textButtonColor: Color,
    iconButtonColor: Color,
    textBackgroundColor: Color,
    icBackgroundColor: Color,
    sliderPosition: Long?,
    position: Long,
    duration: Long,
    playerConnection: PlayerConnection,
    navController: NavController,
    state: BottomSheetState,
    menuState: MenuState,
    bottomSheetPageState: BottomSheetPageState,
    context: Context,
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentSong by playerConnection.currentSong.collectAsState(initial = null)
    val currentSongLiked = currentSong?.song?.liked == true
    val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsState()
    val accent = MaterialTheme.colorScheme.primary
    val horizontalPadding = 20.dp
    val metadataGap = 6.dp
    val transportGap = 6.dp

    val playPauseRoundness by animateDpAsState(
        targetValue = if (isPlaying) 24.dp else 36.dp,
        animationSpec = tween(durationMillis = 90, easing = LinearEasing),
        label = "v6PortraitPlayPauseRoundness",
    )

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PlayerTitleSection(
            mediaMetadata = mediaMetadata,
            textBackgroundColor = textBackgroundColor,
            navController = navController,
            state = state,
            titleMaxLines = 2,
            centerAligned = true,
        )

        Spacer(Modifier.height(metadataGap))

        PlayerSlider(
            sliderStyle = sliderStyle,
            sliderPosition = sliderPosition,
            position = position,
            duration = duration,
            isPlaying = isPlaying,
            textButtonColor = textButtonColor,
            onValueChange = onSliderValueChange,
            onValueChangeFinished = onSliderValueChangeFinished,
            horizontalPadding = 0.dp,
        )

        Spacer(Modifier.height(4.dp))

        PlayerTimeLabel(
            sliderPosition = sliderPosition,
            position = position,
            duration = duration,
            textBackgroundColor = textBackgroundColor,
        )

        Spacer(Modifier.height(transportGap))

        PlayerPlaybackControls(
            playerDesignStyle = playerDesignStyle,
            playbackState = playbackState,
            isPlaying = isPlaying,
            isLoading = isLoading,
            repeatMode = repeatMode,
            canSkipPrevious = canSkipPrevious,
            canSkipNext = canSkipNext,
            textButtonColor = textButtonColor,
            iconButtonColor = iconButtonColor,
            textBackgroundColor = textBackgroundColor,
            icBackgroundColor = icBackgroundColor,
            playPauseRoundness = playPauseRoundness,
            playerConnection = playerConnection,
            currentSongLiked = currentSongLiked,
            landscape = false,
            landscapeCompact = false,
        )

        Spacer(Modifier.height(6.dp))

        V6PortraitSingleActionRow(
            mediaMetadata = mediaMetadata,
            textBackgroundColor = textBackgroundColor,
            currentSongLiked = currentSongLiked,
            shuffleModeEnabled = shuffleModeEnabled,
            repeatMode = repeatMode,
            accent = accent,
            playerConnection = playerConnection,
            navController = navController,
            menuState = menuState,
            state = state,
            bottomSheetPageState = bottomSheetPageState,
            context = context,
        )
    }
}

@Composable
private fun V6PortraitSingleActionRow(
    mediaMetadata: MediaMetadata,
    textBackgroundColor: Color,
    currentSongLiked: Boolean,
    shuffleModeEnabled: Boolean,
    repeatMode: Int,
    accent: Color,
    playerConnection: PlayerConnection,
    navController: NavController,
    menuState: MenuState,
    state: BottomSheetState,
    bottomSheetPageState: BottomSheetPageState,
    context: Context,
    modifier: Modifier = Modifier,
) {
    val database = LocalDatabase.current
    val download by LocalDownloadUtil.current
        .getDownload(mediaMetadata.id)
        .collectAsState(initial = null)
    val librarySong by database.song(mediaMetadata.id).collectAsState(initial = null)
    val isLocalMedia =
        remember(librarySong?.song?.isLocal, mediaMetadata.id) {
            librarySong?.song?.isLocal == true || mediaMetadata.id.isLocalMediaId()
        }
    val isDownloading =
        download?.state == Download.STATE_QUEUED ||
            download?.state == Download.STATE_DOWNLOADING
    val isDownloaded = download?.state == Download.STATE_COMPLETED
    val repeatIcon =
        when (repeatMode) {
            Player.REPEAT_MODE_ONE -> R.drawable.repeat_one_on
            Player.REPEAT_MODE_ALL -> R.drawable.repeat_on
            else -> R.drawable.repeat
        }

    val buttonSize = 40.dp
    val iconSize = 20.dp

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        V6PortraitActionIcon(
            iconRes = R.drawable.share,
            contentDescription = stringResource(R.string.share),
            tint = textBackgroundColor,
            backgroundColor = textBackgroundColor.copy(alpha = 0.12f),
            buttonSize = buttonSize,
            iconSize = iconSize,
            onClick = {
                val intent =
                    Intent().apply {
                        action = Intent.ACTION_SEND
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "https://music.youtube.com/watch?v=${mediaMetadata.id}",
                        )
                    }
                context.startActivity(Intent.createChooser(intent, null))
            },
        )

        V6PortraitActionIcon(
            iconRes = if (currentSongLiked) R.drawable.favorite else R.drawable.favorite_border,
            contentDescription = stringResource(R.string.liked),
            tint = if (currentSongLiked) MaterialTheme.colorScheme.error else textBackgroundColor,
            backgroundColor =
                if (currentSongLiked) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
                } else {
                    textBackgroundColor.copy(alpha = 0.12f)
                },
            buttonSize = buttonSize,
            iconSize = iconSize,
            onClick = { playerConnection.toggleLike() },
        )

        if (!isLocalMedia) {
            V6PortraitActionIcon(
                iconRes =
                    when {
                        isDownloaded -> R.drawable.offline
                        else -> R.drawable.download
                    },
                contentDescription =
                    stringResource(
                        if (isDownloaded) R.string.remove_download else R.string.action_download,
                    ),
                tint = textBackgroundColor,
                backgroundColor = textBackgroundColor.copy(alpha = 0.12f),
                buttonSize = buttonSize,
                iconSize = iconSize,
                onClick = {
                    handleV6PlayerDownloadClick(
                        context = context,
                        database = database,
                        mediaMetadata = mediaMetadata,
                        download = download,
                    )
                },
                showProgress = isDownloading,
            )
        }

        V6PortraitActionIcon(
            iconRes = R.drawable.shuffle,
            contentDescription = stringResource(R.string.shuffle),
            tint = if (shuffleModeEnabled) accent else textBackgroundColor,
            backgroundColor =
                if (shuffleModeEnabled) {
                    accent.copy(alpha = 0.18f)
                } else {
                    textBackgroundColor.copy(alpha = 0.12f)
                },
            buttonSize = buttonSize,
            iconSize = iconSize,
            onClick = {
                playerConnection.player.shuffleModeEnabled = !shuffleModeEnabled
            },
        )

        V6PortraitActionIcon(
            iconRes = repeatIcon,
            contentDescription = stringResource(R.string.repeat_mode_all),
            tint = if (repeatMode != Player.REPEAT_MODE_OFF) accent else textBackgroundColor,
            backgroundColor =
                if (repeatMode != Player.REPEAT_MODE_OFF) {
                    accent.copy(alpha = 0.18f)
                } else {
                    textBackgroundColor.copy(alpha = 0.12f)
                },
            buttonSize = buttonSize,
            iconSize = iconSize,
            onClick = { playerConnection.player.toggleRepeatMode() },
        )

        V6PortraitActionIcon(
            iconRes = R.drawable.more_horiz,
            contentDescription = null,
            tint = textBackgroundColor,
            backgroundColor = textBackgroundColor.copy(alpha = 0.12f),
            buttonSize = buttonSize,
            iconSize = iconSize,
            onClick = {
                menuState.show {
                    PlayerMenu(
                        mediaMetadata = mediaMetadata,
                        navController = navController,
                        playerBottomSheetState = state,
                        onShowDetailsDialog = {
                            bottomSheetPageState.show {
                                ShowMediaInfo(mediaMetadata.id)
                            }
                        },
                        onDismiss = menuState::dismiss,
                    )
                }
            },
        )
    }
}

@Composable
private fun V6PortraitActionIcon(
    iconRes: Int,
    contentDescription: String?,
    tint: Color,
    backgroundColor: Color,
    buttonSize: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
    showProgress: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = backgroundColor,
        modifier = modifier.size(buttonSize),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            if (showProgress) {
                CircularWavyProgressIndicator(
                    modifier = Modifier.size(iconSize),
                    color = tint,
                )
            } else {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = contentDescription,
                    tint = tint,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}

/**
 * Dedicated Expressive landscape controls — portrait order, larger controls, anchored to bottom.
 */
@Composable
fun V6LandscapeControlsPanel(
    mediaMetadata: MediaMetadata,
    playerDesignStyle: PlayerDesignStyle,
    sliderStyle: SliderStyle,
    playbackState: Int,
    isPlaying: Boolean,
    isLoading: Boolean,
    repeatMode: Int,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    textButtonColor: Color,
    iconButtonColor: Color,
    textBackgroundColor: Color,
    icBackgroundColor: Color,
    sliderPosition: Long?,
    position: Long,
    duration: Long,
    playerConnection: PlayerConnection,
    navController: NavController,
    state: BottomSheetState,
    menuState: MenuState,
    bottomSheetPageState: BottomSheetPageState,
    context: Context,
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
) {
    val currentSong by playerConnection.currentSong.collectAsState(initial = null)
    val currentSongLiked = currentSong?.song?.liked == true

    val playPauseRoundness by animateDpAsState(
        targetValue = if (isPlaying) 24.dp else 36.dp,
        animationSpec = tween(durationMillis = 90, easing = LinearEasing),
        label = "v6LandscapePlayPauseRoundness",
    )
    val controlsSpacing = 8.dp

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PlayerTitleSection(
            mediaMetadata = mediaMetadata,
            textBackgroundColor = textBackgroundColor,
            navController = navController,
            state = state,
            titleMaxLines = 2,
        )

        Spacer(modifier = Modifier.weight(1f))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(controlsSpacing),
        ) {
            PlayerSlider(
                sliderStyle = sliderStyle,
                sliderPosition = sliderPosition,
                position = position,
                duration = duration,
                isPlaying = isPlaying,
                textButtonColor = textButtonColor,
                onValueChange = onSliderValueChange,
                onValueChangeFinished = onSliderValueChangeFinished,
                horizontalPadding = 0.dp,
            )

            PlayerTimeLabel(
                sliderPosition = sliderPosition,
                position = position,
                duration = duration,
                textBackgroundColor = textBackgroundColor,
            )

            PlayerPlaybackControls(
                playerDesignStyle = playerDesignStyle,
                playbackState = playbackState,
                isPlaying = isPlaying,
                isLoading = isLoading,
                repeatMode = repeatMode,
                canSkipPrevious = canSkipPrevious,
                canSkipNext = canSkipNext,
                textButtonColor = textButtonColor,
                iconButtonColor = iconButtonColor,
                textBackgroundColor = textBackgroundColor,
                icBackgroundColor = icBackgroundColor,
                playPauseRoundness = playPauseRoundness,
                playerConnection = playerConnection,
                currentSongLiked = currentSongLiked,
                landscape = true,
                landscapeCompact = false,
                useWeightedTransportLayout = true,
            )

            PlayerTopActions(
                mediaMetadata = mediaMetadata,
                playerDesignStyle = playerDesignStyle,
                textButtonColor = textButtonColor,
                iconButtonColor = iconButtonColor,
                textBackgroundColor = textBackgroundColor,
                playerConnection = playerConnection,
                navController = navController,
                menuState = menuState,
                state = state,
                bottomSheetPageState = bottomSheetPageState,
                context = context,
                currentSongLiked = currentSongLiked,
                landscape = true,
            )
        }
    }
}

@Composable
private fun V6LandscapeAllActionRows(
    mediaMetadata: MediaMetadata,
    textBackgroundColor: Color,
    currentSongLiked: Boolean,
    shuffleModeEnabled: Boolean,
    repeatMode: Int,
    accent: Color,
    playerConnection: PlayerConnection,
    navController: NavController,
    menuState: MenuState,
    state: BottomSheetState,
    bottomSheetPageState: BottomSheetPageState,
    context: Context,
    onLibraryClick: () -> Unit,
    onLyricsClick: () -> Unit,
    buttonSize: Dp,
    iconSize: Dp,
    rowGap: Dp,
    libraryLyricsHeight: Dp = buttonSize,
) {
    val database = LocalDatabase.current
    val download by LocalDownloadUtil.current
        .getDownload(mediaMetadata.id)
        .collectAsState(initial = null)
    val librarySong by database.song(mediaMetadata.id).collectAsState(initial = null)
    val isLocalMedia =
        remember(librarySong?.song?.isLocal, mediaMetadata.id) {
            librarySong?.song?.isLocal == true || mediaMetadata.id.isLocalMediaId()
        }
    val isDownloading =
        download?.state == Download.STATE_QUEUED ||
            download?.state == Download.STATE_DOWNLOADING
    val isDownloaded = download?.state == Download.STATE_COMPLETED
    val repeatIcon =
        when (repeatMode) {
            Player.REPEAT_MODE_ONE -> R.drawable.repeat_one_on
            Player.REPEAT_MODE_ALL -> R.drawable.repeat_on
            else -> R.drawable.repeat
        }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(rowGap),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(rowGap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            V6LandscapeIconButton(
                iconRes = R.drawable.share,
                contentDescription = stringResource(R.string.share),
                tint = textBackgroundColor,
                backgroundColor = textBackgroundColor.copy(alpha = 0.12f),
                buttonSize = buttonSize,
                iconSize = iconSize,
                shape =
                    RoundedCornerShape(
                        topStart = 50.dp,
                        bottomStart = 50.dp,
                        topEnd = 6.dp,
                        bottomEnd = 6.dp,
                    ),
                onClick = {
                    val intent =
                        Intent().apply {
                            action = Intent.ACTION_SEND
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "https://music.youtube.com/watch?v=${mediaMetadata.id}",
                            )
                        }
                    context.startActivity(Intent.createChooser(intent, null))
                },
            )

            V6LandscapeIconButton(
                iconRes = if (currentSongLiked) R.drawable.favorite else R.drawable.favorite_border,
                contentDescription = stringResource(R.string.liked),
                tint = if (currentSongLiked) MaterialTheme.colorScheme.error else textBackgroundColor,
                backgroundColor =
                    if (currentSongLiked) {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
                    } else {
                        textBackgroundColor.copy(alpha = 0.12f)
                    },
                buttonSize = buttonSize,
                iconSize = iconSize,
                shape = RoundedCornerShape(50),
                onClick = { playerConnection.toggleLike() },
            )

            if (!isLocalMedia) {
                V6LandscapeIconButton(
                    iconRes =
                        when {
                            isDownloaded -> R.drawable.offline
                            else -> R.drawable.download
                        },
                    contentDescription =
                        stringResource(
                            if (isDownloaded) {
                                R.string.remove_download
                            } else {
                                R.string.action_download
                            },
                        ),
                    tint = textBackgroundColor,
                    backgroundColor = textBackgroundColor.copy(alpha = 0.12f),
                    buttonSize = buttonSize,
                    iconSize = iconSize,
                    shape = RoundedCornerShape(50),
                    onClick = {
                        handleV6PlayerDownloadClick(
                            context = context,
                            database = database,
                            mediaMetadata = mediaMetadata,
                            download = download,
                        )
                    },
                    loading = isDownloading,
                )
            }

            V6LandscapeIconButton(
                iconRes = R.drawable.more_horiz,
                contentDescription = stringResource(R.string.more_options),
                tint = textBackgroundColor,
                backgroundColor = textBackgroundColor.copy(alpha = 0.12f),
                buttonSize = buttonSize,
                iconSize = iconSize,
                shape =
                    RoundedCornerShape(
                        topStart = 6.dp,
                        bottomStart = 6.dp,
                        topEnd = 50.dp,
                        bottomEnd = 50.dp,
                    ),
                onClick = {
                    menuState.show {
                        PlayerMenu(
                            mediaMetadata = mediaMetadata,
                            navController = navController,
                            playerBottomSheetState = state,
                            onShowDetailsDialog = {
                                bottomSheetPageState.show {
                                    ShowMediaInfo(mediaMetadata.id)
                                }
                            },
                            onDismiss = menuState::dismiss,
                        )
                    }
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(rowGap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            V6LandscapeIconButton(
                iconRes = R.drawable.shuffle,
                contentDescription = stringResource(R.string.shuffle),
                tint = if (shuffleModeEnabled) accent else textBackgroundColor,
                backgroundColor =
                    if (shuffleModeEnabled) {
                        accent.copy(alpha = 0.18f)
                    } else {
                        textBackgroundColor.copy(alpha = 0.12f)
                    },
                buttonSize = buttonSize,
                iconSize = iconSize,
                shape =
                    RoundedCornerShape(
                        topStart = 50.dp,
                        bottomStart = 50.dp,
                        topEnd = 6.dp,
                        bottomEnd = 6.dp,
                    ),
                onClick = {
                    playerConnection.player.shuffleModeEnabled = !shuffleModeEnabled
                },
            )

            V6LandscapeIconButton(
                iconRes = repeatIcon,
                contentDescription = stringResource(R.string.repeat_mode_all),
                tint = if (repeatMode != Player.REPEAT_MODE_OFF) accent else textBackgroundColor,
                backgroundColor =
                    if (repeatMode != Player.REPEAT_MODE_OFF) {
                        accent.copy(alpha = 0.18f)
                    } else {
                        textBackgroundColor.copy(alpha = 0.12f)
                    },
                buttonSize = buttonSize,
                iconSize = iconSize,
                shape =
                    RoundedCornerShape(
                        topStart = 6.dp,
                        bottomStart = 6.dp,
                        topEnd = 50.dp,
                        bottomEnd = 50.dp,
                    ),
                onClick = { playerConnection.player.toggleRepeatMode() },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(rowGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            V6LandscapeTextButton(
                iconRes = R.drawable.library_outlined,
                label = stringResource(R.string.filter_library),
                textColor = textBackgroundColor,
                buttonSize = libraryLyricsHeight,
                iconSize = iconSize,
                onClick = onLibraryClick,
                modifier = Modifier.weight(1f),
            )
            V6LandscapeTextButton(
                iconRes = R.drawable.lyrics,
                label = stringResource(R.string.lyrics),
                textColor = textBackgroundColor,
                buttonSize = libraryLyricsHeight,
                iconSize = iconSize,
                onClick = onLyricsClick,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun V6LandscapeIconButton(
    iconRes: Int,
    contentDescription: String,
    tint: Color,
    backgroundColor: Color,
    buttonSize: Dp,
    iconSize: Dp,
    shape: androidx.compose.ui.graphics.Shape,
    onClick: () -> Unit,
    loading: Boolean = false,
) {
    Surface(
        onClick = onClick,
        shape = shape,
        color = backgroundColor,
        modifier = Modifier.size(buttonSize),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            if (loading) {
                CircularWavyProgressIndicator(
                    modifier = Modifier.size(iconSize),
                    color = tint,
                )
            } else {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = contentDescription,
                    tint = tint,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}

@Composable
private fun V6LandscapeTextButton(
    iconRes: Int,
    label: String,
    textColor: Color,
    buttonSize: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = textColor.copy(alpha = 0.12f),
        modifier = modifier.height(buttonSize),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(iconSize),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun V6LandscapeTransportRow(
    playbackState: Int,
    isPlaying: Boolean,
    isLoading: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    playerConnection: PlayerConnection,
    buttonSize: Dp,
    iconSize: Dp,
) {
    val haptic = LocalHapticFeedback.current
    val playSize = (buttonSize * 1.35f).coerceIn(buttonSize + 10.dp, 76.dp)
    val playIconSize = (iconSize * 1.2f).coerceAtMost(38.dp)
    val buttonGap = if (buttonSize < 54.dp) 10.dp else 14.dp

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(buttonGap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                playerConnection.seekToPrevious()
            },
            enabled = canSkipPrevious,
            shape =
                RoundedCornerShape(
                    topStart = 22.dp,
                    bottomStart = 22.dp,
                    topEnd = 8.dp,
                    bottomEnd = 8.dp,
                ),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(buttonSize),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.skip_previous),
                    contentDescription = null,
                    tint =
                        MaterialTheme.colorScheme.onSecondaryContainer.copy(
                            alpha = if (canSkipPrevious) 1f else 0.4f,
                        ),
                    modifier = Modifier.size(iconSize),
                )
            }
        }

        Surface(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (playbackState == STATE_ENDED) {
                    playerConnection.player.seekTo(0, 0)
                    playerConnection.player.playWhenReady = true
                } else {
                    playerConnection.player.togglePlayPause()
                }
            },
            shape = RoundedCornerShape(28.dp),
            color = Color.Transparent,
            modifier =
                Modifier
                    .size(playSize)
                    .hushPlayButtonBackground(RoundedCornerShape(28.dp)),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (isLoading) {
                    CircularWavyProgressIndicator(
                        modifier = Modifier.size(playIconSize),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        painter =
                            painterResource(
                                when {
                                    playbackState == STATE_ENDED -> R.drawable.replay
                                    isPlaying -> R.drawable.pause
                                    else -> R.drawable.play
                                },
                            ),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(playIconSize),
                    )
                }
            }
        }

        Surface(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                playerConnection.seekToNext()
            },
            enabled = canSkipNext,
            shape =
                RoundedCornerShape(
                    topStart = 8.dp,
                    bottomStart = 8.dp,
                    topEnd = 22.dp,
                    bottomEnd = 22.dp,
                ),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(buttonSize),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.skip_next),
                    contentDescription = null,
                    tint =
                        MaterialTheme.colorScheme.onSecondaryContainer.copy(
                            alpha = if (canSkipNext) 1f else 0.4f,
                        ),
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}

private fun handleV6PlayerDownloadClick(
    context: Context,
    database: app.hush.music.db.MusicDatabase,
    mediaMetadata: MediaMetadata,
    download: Download?,
) {
    when (download?.state) {
        Download.STATE_COMPLETED,
        Download.STATE_QUEUED,
        Download.STATE_DOWNLOADING,
        -> {
            DownloadService.sendRemoveDownload(
                context,
                ExoDownloadService::class.java,
                mediaMetadata.id,
                false,
            )
        }

        else -> {
            database.transaction {
                insert(mediaMetadata)
            }
            val downloadRequest =
                DownloadRequest
                    .Builder(mediaMetadata.id, mediaMetadata.id.toUri())
                    .setCustomCacheKey(mediaMetadata.id)
                    .setData(mediaMetadata.title.toByteArray())
                    .build()
            DownloadService.sendAddDownload(
                context,
                ExoDownloadService::class.java,
                downloadRequest,
                false,
            )
        }
    }
}

@Composable
fun V8PlayerControlsContent(
    mediaMetadata: MediaMetadata,
    queueTitle: String?,
    sliderStyle: SliderStyle,
    playbackState: Int,
    isPlaying: Boolean,
    isLoading: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    currentSongLiked: Boolean,
    sliderPosition: Long?,
    position: Long,
    duration: Long,
    volume: Float,
    currentFormat: FormatEntity?,
    playerConnection: PlayerConnection,
    navController: NavController,
    state: BottomSheetState,
    menuState: MenuState,
    bottomSheetPageState: BottomSheetPageState,
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onLyricsClick: () -> Unit,
    onQueueClick: () -> Unit,
    modifier: Modifier = Modifier,
    landscape: Boolean = false,
) {
    val foreground = Color.White
    val secondaryForeground = foreground.copy(alpha = 0.72f)
    val onMenuClick =
        remember(mediaMetadata, navController, state, menuState, bottomSheetPageState) {
            {
                menuState.show {
                    PlayerMenu(
                        mediaMetadata = mediaMetadata,
                        navController = navController,
                        playerBottomSheetState = state,
                        onShowDetailsDialog = {
                            bottomSheetPageState.show {
                                ShowMediaInfo(mediaMetadata.id)
                            }
                        },
                        onDismiss = menuState::dismiss,
                    )
                }
            }
        }
    val titleActions = rememberPlayerTitleActions(mediaMetadata, navController, state)
    val onTitleClick = titleActions.onTitleClick
    val onArtistClick = titleActions.onArtistClick
    val onPlayPauseClick =
        remember(playbackState, playerConnection) {
            {
                if (playbackState == STATE_ENDED) {
                    playerConnection.player.seekTo(0, 0)
                    playerConnection.player.playWhenReady = true
                } else {
                    playerConnection.player.togglePlayPause()
                }
            }
        }
    val onToggleLike =
        remember(playerConnection) {
            { playerConnection.toggleLike() }
        }
    val onPreviousClick =
        remember(playerConnection) {
            { playerConnection.seekToPrevious() }
        }
    val onNextClick =
        remember(playerConnection) {
            { playerConnection.seekToNext() }
        }

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (landscape) Modifier.fillMaxHeight() else Modifier),
    ) {
        val compactHeight = maxHeight < LandscapePlayerCompactHeight
        val horizontalPadding =
            if (landscape) {
                if (maxWidth < 560.dp) 16.dp else 36.dp
            } else if (maxWidth < 380.dp) {
                22.dp
            } else {
                24.dp
            }
        val contentGap = if (landscape && compactHeight) 10.dp else if (landscape) 14.dp else 18.dp
        val progressToTransportGap =
            when {
                landscape && compactHeight -> 16.dp
                landscape -> 20.dp
                else -> if (compactHeight) 10.dp else 18.dp
            }
        val transportToVolumeGap = if (landscape && compactHeight) 0.dp else if (landscape) 12.dp else 18.dp
        val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsState()
        val repeatMode by playerConnection.repeatMode.collectAsState()
        val subtitle = queueTitle ?: mediaMetadata.album?.title.orEmpty()

        val controlsBody: @Composable ColumnScope.() -> Unit = {
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = secondaryForeground,
                    textAlign = if (landscape) TextAlign.Start else TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .basicMarquee(),
                )

                Spacer(Modifier.height(contentGap))
            }

            PlayerTrackMetadataBlock(
                title = mediaMetadata.title,
                artists = mediaMetadata.artists,
                foreground = foreground,
                secondaryForeground = secondaryForeground,
                onTitleClick = onTitleClick,
                onArtistClick = onArtistClick,
                textAlign = if (landscape) TextAlign.Start else TextAlign.Center,
            )

            Spacer(Modifier.height(contentGap))

            if (!landscape) {
                V8PlaybackProgress(
                    sliderStyle = sliderStyle,
                    sliderPosition = sliderPosition,
                    position = position,
                    duration = duration,
                    isPlaying = isPlaying,
                    currentFormat = currentFormat,
                    foreground = foreground,
                    onSliderValueChange = onSliderValueChange,
                    onSliderValueChangeFinished = onSliderValueChangeFinished,
                )

                Spacer(Modifier.height(progressToTransportGap))
            }
        }

        val transportFooter: @Composable ColumnScope.() -> Unit = {
            if (landscape) {
                V8PlaybackProgress(
                    sliderStyle = sliderStyle,
                    sliderPosition = sliderPosition,
                    position = position,
                    duration = duration,
                    isPlaying = isPlaying,
                    currentFormat = currentFormat,
                    foreground = foreground,
                    onSliderValueChange = onSliderValueChange,
                    onSliderValueChangeFinished = onSliderValueChangeFinished,
                )

                Spacer(Modifier.height(progressToTransportGap))
            }

            V8TransportControls(
                playbackState = playbackState,
                isPlaying = isPlaying,
                isLoading = isLoading,
                canSkipPrevious = canSkipPrevious,
                canSkipNext = canSkipNext,
                foreground = foreground,
                onPreviousClick = onPreviousClick,
                onPlayPauseClick = onPlayPauseClick,
                onNextClick = onNextClick,
                landscape = landscape,
            )

            Spacer(Modifier.height(if (compactHeight) 10.dp else 14.dp))

            PlayerLandscapeSecondaryActions(
                mediaMetadata = mediaMetadata,
                currentSongLiked = currentSongLiked,
                shuffleModeEnabled = shuffleModeEnabled,
                repeatMode = repeatMode,
                accent = MaterialTheme.colorScheme.primary,
                foreground = foreground,
                playerConnection = playerConnection,
                onMenuClick = onMenuClick,
                compact = compactHeight,
            )

            Spacer(Modifier.height(transportToVolumeGap))

            if (!landscape || !compactHeight) {
                V8VolumeControls(
                    volume = volume,
                    foreground = foreground,
                    secondaryForeground = secondaryForeground,
                    onVolumeChange = onVolumeChange,
                )
            }
        }

        if (landscape) {
            LandscapePlayerControlsColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .padding(horizontal = horizontalPadding),
                compact = compactHeight,
                footerContent = transportFooter,
                content = controlsBody,
            )
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = horizontalPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                controlsBody()
                transportFooter()
            }
        }
    }
}

@Composable
fun V8PlayerContent(
    mediaMetadata: MediaMetadata,
    queueTitle: String?,
    sliderStyle: SliderStyle,
    playbackState: Int,
    isPlaying: Boolean,
    isLoading: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    currentSongLiked: Boolean,
    sliderPosition: Long?,
    position: Long,
    duration: Long,
    volume: Float,
    playerConnection: PlayerConnection,
    navController: NavController,
    state: BottomSheetState,
    menuState: MenuState,
    bottomSheetPageState: BottomSheetPageState,
    currentFormat: FormatEntity?,
    canvasPrimaryUrl: String?,
    canvasFallbackUrl: String?,
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onLyricsClick: () -> Unit,
    onQueueClick: () -> Unit,
    modifier: Modifier = Modifier,
    landscape: Boolean = false,
) {
    val foreground = Color.White
    val secondaryForeground = foreground.copy(alpha = 0.72f)
    val artworkUrl = mediaMetadata.thumbnailUrl?.highRes()
    val subtitle = queueTitle ?: mediaMetadata.album?.title.orEmpty()
    val onMenuClick = {
        menuState.show {
            PlayerMenu(
                mediaMetadata = mediaMetadata,
                navController = navController,
                playerBottomSheetState = state,
                onShowDetailsDialog = {
                    bottomSheetPageState.show {
                        ShowMediaInfo(mediaMetadata.id)
                    }
                },
                onDismiss = menuState::dismiss,
            )
        }
    }

    val titleActions = rememberPlayerTitleActions(mediaMetadata, navController, state)
    val onTitleClick = titleActions.onTitleClick
    val onArtistClick = titleActions.onArtistClick

    BoxWithConstraints(modifier = modifier) {
        val useLandscapeLayout = landscape || maxWidth > maxHeight

        if (useLandscapeLayout) {
            V8LandscapeContent(
            mediaMetadata = mediaMetadata,
            subtitle = subtitle,
            artists = mediaMetadata.artists,
            artworkUrl = artworkUrl,
            canvasPrimaryUrl = canvasPrimaryUrl,
            canvasFallbackUrl = canvasFallbackUrl,
            sliderStyle = sliderStyle,
            playbackState = playbackState,
            isPlaying = isPlaying,
            isLoading = isLoading,
            canSkipPrevious = canSkipPrevious,
            canSkipNext = canSkipNext,
            currentSongLiked = currentSongLiked,
            sliderPosition = sliderPosition,
            position = position,
            duration = duration,
            volume = volume,
            currentFormat = currentFormat,
            foreground = foreground,
            secondaryForeground = secondaryForeground,
            onMenuClick = onMenuClick,
            onToggleLike = playerConnection::toggleLike,
            onTitleClick = onTitleClick,
            onArtistClick = onArtistClick,
            onPreviousClick = playerConnection::seekToPrevious,
            onNextClick = playerConnection::seekToNext,
            onPlayPauseClick = {
                if (playbackState == STATE_ENDED) {
                    playerConnection.player.seekTo(0, 0)
                    playerConnection.player.playWhenReady = true
                } else {
                    playerConnection.player.togglePlayPause()
                }
            },
            onSliderValueChange = onSliderValueChange,
            onSliderValueChangeFinished = onSliderValueChangeFinished,
            onVolumeChange = onVolumeChange,
            onLyricsClick = onLyricsClick,
            onQueueClick = onQueueClick,
            modifier = Modifier.fillMaxSize(),
        )
        } else {
            V8PortraitContent(
            mediaMetadata = mediaMetadata,
            subtitle = subtitle,
            artists = mediaMetadata.artists,
            artworkUrl = artworkUrl,
            canvasPrimaryUrl = canvasPrimaryUrl,
            canvasFallbackUrl = canvasFallbackUrl,
            sliderStyle = sliderStyle,
            playbackState = playbackState,
            isPlaying = isPlaying,
            isLoading = isLoading,
            canSkipPrevious = canSkipPrevious,
            canSkipNext = canSkipNext,
            currentSongLiked = currentSongLiked,
            sliderPosition = sliderPosition,
            position = position,
            duration = duration,
            volume = volume,
            currentFormat = currentFormat,
            foreground = foreground,
            secondaryForeground = secondaryForeground,
            onMenuClick = onMenuClick,
            onToggleLike = playerConnection::toggleLike,
            onTitleClick = onTitleClick,
            onArtistClick = onArtistClick,
            onLyricsClick = onLyricsClick,
            onQueueClick = onQueueClick,
            playerConnection = playerConnection,
            onPreviousClick = playerConnection::seekToPrevious,
            onNextClick = playerConnection::seekToNext,
            onPlayPauseClick = {
                if (playbackState == STATE_ENDED) {
                    playerConnection.player.seekTo(0, 0)
                    playerConnection.player.playWhenReady = true
                } else {
                    playerConnection.player.togglePlayPause()
                }
            },
            onSliderValueChange = onSliderValueChange,
            onSliderValueChangeFinished = onSliderValueChangeFinished,
            onVolumeChange = onVolumeChange,
            modifier = Modifier.fillMaxSize(),
        )
        }
    }
}

@Composable
private fun V8PortraitContent(
    mediaMetadata: MediaMetadata,
    subtitle: String,
    artists: List<MediaMetadata.Artist>,
    artworkUrl: String?,
    canvasPrimaryUrl: String?,
    canvasFallbackUrl: String?,
    sliderStyle: SliderStyle,
    playbackState: Int,
    isPlaying: Boolean,
    isLoading: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    currentSongLiked: Boolean,
    sliderPosition: Long?,
    position: Long,
    duration: Long,
    volume: Float,
    currentFormat: FormatEntity?,
    foreground: Color,
    secondaryForeground: Color,
    onMenuClick: () -> Unit,
    onToggleLike: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onTitleClick: () -> Unit,
    onArtistClick: (artistId: String) -> Unit,
    onLyricsClick: () -> Unit,
    onQueueClick: () -> Unit,
    playerConnection: PlayerConnection,
    modifier: Modifier = Modifier,
) {
    val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsState()
    val repeatMode by playerConnection.repeatMode.collectAsState()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val contentPadding = if (maxWidth < 380.dp) 22.dp else 24.dp
        val compactHeight = maxHeight < 760.dp
        val veryCompactHeight = maxHeight < 680.dp
        val headerTop = if (compactHeight) 6.dp else 14.dp
        val headerToArtwork =
            when {
                veryCompactHeight -> 10.dp
                compactHeight -> 14.dp
                else -> 28.dp
            }
        val artworkToMetadata =
            when {
                veryCompactHeight -> 12.dp
                compactHeight -> 16.dp
                else -> 28.dp
            }
        val controlsGap = if (compactHeight) 10.dp else 18.dp
        val progressToTransportGap = if (compactHeight) 12.dp else 18.dp
        val transportToVolumeGap = if (compactHeight) 8.dp else 18.dp
        val bottomGap = if (compactHeight) 8.dp else 16.dp
        val reservedControlsHeight =
            headerTop +
                56.dp +
                headerToArtwork +
                artworkToMetadata +
                58.dp +
                controlsGap +
                62.dp +
                progressToTransportGap +
                72.dp +
                52.dp +
                transportToVolumeGap +
                68.dp +
                bottomGap
        val maxArtworkSize =
            (maxWidth - contentPadding * 2)
                .coerceAtMost(if (compactHeight) 360.dp else 420.dp)
        val artworkSize =
            maxArtworkSize
                .coerceAtMost(maxHeight - reservedControlsHeight)
                .coerceAtLeast(0.dp)

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = contentPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(headerTop))

            V8Header(
                title = stringResource(R.string.now_playing),
                subtitle = subtitle,
                foreground = foreground,
                secondaryForeground = secondaryForeground,
            )

            Spacer(Modifier.height(headerToArtwork))

            V8Artwork(
                artworkUrl = artworkUrl,
                canvasPrimaryUrl = canvasPrimaryUrl,
                canvasFallbackUrl = canvasFallbackUrl,
                isPlaying = isPlaying,
                size = artworkSize,
            )

            Spacer(Modifier.height(artworkToMetadata))

            PlayerTrackMetadataBlock(
                title = mediaMetadata.title,
                artists = artists,
                foreground = foreground,
                secondaryForeground = secondaryForeground,
                onTitleClick = onTitleClick,
                onArtistClick = onArtistClick,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(controlsGap))

            V8PlaybackProgress(
                sliderStyle = sliderStyle,
                sliderPosition = sliderPosition,
                position = position,
                duration = duration,
                isPlaying = isPlaying,
                currentFormat = currentFormat,
                foreground = foreground,
                onSliderValueChange = onSliderValueChange,
                onSliderValueChangeFinished = onSliderValueChangeFinished,
            )

            Spacer(Modifier.height(progressToTransportGap))

            V8TransportControls(
                playbackState = playbackState,
                isPlaying = isPlaying,
                isLoading = isLoading,
                canSkipPrevious = canSkipPrevious,
                canSkipNext = canSkipNext,
                foreground = foreground,
                onPreviousClick = onPreviousClick,
                onPlayPauseClick = onPlayPauseClick,
                onNextClick = onNextClick,
            )

            Spacer(Modifier.height(12.dp))

            PlayerLandscapeSecondaryActions(
                mediaMetadata = mediaMetadata,
                currentSongLiked = currentSongLiked,
                shuffleModeEnabled = shuffleModeEnabled,
                repeatMode = repeatMode,
                accent = MaterialTheme.colorScheme.primary,
                foreground = foreground,
                playerConnection = playerConnection,
                onMenuClick = onMenuClick,
                compact = compactHeight,
            )

            Spacer(Modifier.height(transportToVolumeGap))

            V8VolumeControls(
                volume = volume,
                foreground = foreground,
                secondaryForeground = secondaryForeground,
                onVolumeChange = onVolumeChange,
            )

            Spacer(Modifier.weight(1f))

            Spacer(Modifier.height(bottomGap))
        }
    }
}

@Composable
private fun V8LandscapeContent(
    mediaMetadata: MediaMetadata,
    subtitle: String,
    artists: List<MediaMetadata.Artist>,
    artworkUrl: String?,
    canvasPrimaryUrl: String?,
    canvasFallbackUrl: String?,
    sliderStyle: SliderStyle,
    playbackState: Int,
    isPlaying: Boolean,
    isLoading: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    currentSongLiked: Boolean,
    sliderPosition: Long?,
    position: Long,
    duration: Long,
    volume: Float,
    currentFormat: FormatEntity?,
    foreground: Color,
    secondaryForeground: Color,
    onMenuClick: () -> Unit,
    onToggleLike: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onTitleClick: () -> Unit,
    onArtistClick: (artistId: String) -> Unit,
    onLyricsClick: () -> Unit,
    onQueueClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val horizontalPadding = if (maxWidth < 560.dp) 24.dp else 36.dp
        val contentGap = if (maxWidth < 560.dp) 20.dp else 36.dp
        val compactHeight = maxHeight < LandscapePlayerCompactHeight
        val verticalPadding = if (compactHeight) 12.dp else 24.dp
        val minControlsHeight =
            landscapeControlsFooterReserve(
                compact = compactHeight,
                includeVolume = !compactHeight,
            )
        val artworkSize =
            landscapePlayerArtworkSize(
                maxWidth = maxWidth,
                maxHeight = maxHeight,
                verticalPadding = verticalPadding,
                minControlsHeight = minControlsHeight,
                maxHeightFraction = if (compactHeight) 0.62f else 0.72f,
                maxWidthFraction = 0.38f,
                minSize = if (compactHeight) 112.dp else 140.dp,
            )

        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(contentGap),
        ) {
            V8Artwork(
                artworkUrl = artworkUrl,
                canvasPrimaryUrl = canvasPrimaryUrl,
                canvasFallbackUrl = canvasFallbackUrl,
                isPlaying = isPlaying,
                size = artworkSize,
            )

            LandscapePlayerControlsColumn(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                compact = compactHeight,
                footerContent = {
                    V8PlaybackProgress(
                        sliderStyle = sliderStyle,
                        sliderPosition = sliderPosition,
                        position = position,
                        duration = duration,
                        isPlaying = isPlaying,
                        currentFormat = currentFormat,
                        foreground = foreground,
                        onSliderValueChange = onSliderValueChange,
                        onSliderValueChangeFinished = onSliderValueChangeFinished,
                    )

                    Spacer(Modifier.height(if (compactHeight) 16.dp else 20.dp))

                    V8TransportControls(
                        playbackState = playbackState,
                        isPlaying = isPlaying,
                        isLoading = isLoading,
                        canSkipPrevious = canSkipPrevious,
                        canSkipNext = canSkipNext,
                        foreground = foreground,
                        onPreviousClick = onPreviousClick,
                        onPlayPauseClick = onPlayPauseClick,
                        onNextClick = onNextClick,
                        landscape = true,
                    )

                    Spacer(Modifier.height(if (compactHeight) 10.dp else 16.dp))

                    if (!compactHeight) {
                        V8VolumeControls(
                            volume = volume,
                            foreground = foreground,
                            secondaryForeground = secondaryForeground,
                            onVolumeChange = onVolumeChange,
                        )
                    }
                },
            ) {
                PlayerChromeActionRow(
                    containerColor = foreground.copy(alpha = 0.16f),
                    iconColor = foreground,
                    onLyricsClick = onLyricsClick,
                    onQueueClick = onQueueClick,
                )

                Spacer(Modifier.height(8.dp))

                V8Header(
                    title = stringResource(R.string.now_playing),
                    subtitle = subtitle,
                    foreground = foreground,
                    secondaryForeground = secondaryForeground,
                )

                Spacer(Modifier.height(if (compactHeight) 10.dp else 18.dp))

                V8MetadataActions(
                    title = mediaMetadata.title,
                    artists = artists,
                    liked = currentSongLiked,
                    foreground = foreground,
                    onMenuClick = onMenuClick,
                    onToggleLike = onToggleLike,
                    onTitleClick = onTitleClick,
                    onArtistClick = onArtistClick,
                    compact = compactHeight,
                )
            }
        }
    }
}

@Composable
private fun V8Header(
    title: String,
    subtitle: String,
    foreground: Color,
    secondaryForeground: Color,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = foreground,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.titleMedium,
            color = secondaryForeground,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .basicMarquee(),
        )
    }
}

@Composable
private fun V8Artwork(
    artworkUrl: String?,
    canvasPrimaryUrl: String?,
    canvasFallbackUrl: String?,
    isPlaying: Boolean,
    size: androidx.compose.ui.unit.Dp,
    cornerRadius: androidx.compose.ui.unit.Dp = 8.dp,
) {
    val artworkRequest = rememberOfflineArtworkImageRequest(artworkUrl)
    Box(
        modifier =
            Modifier
                .size(size)
                .clip(RoundedCornerShape(cornerRadius))
                .background(Color.White.copy(alpha = 0.08f)),
    ) {
        AsyncImage(
            model = artworkRequest,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        if (!canvasPrimaryUrl.isNullOrBlank() || !canvasFallbackUrl.isNullOrBlank()) {
            CanvasArtworkPlayer(
                primaryUrl = canvasPrimaryUrl,
                fallbackUrl = canvasFallbackUrl,
                isPlaying = isPlaying,
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun V8MetadataActions(
    title: String,
    artists: List<MediaMetadata.Artist>,
    liked: Boolean,
    foreground: Color,
    onMenuClick: () -> Unit,
    onToggleLike: () -> Unit,
    onTitleClick: () -> Unit,
    onArtistClick: (artistId: String) -> Unit,
    compact: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
        ) {
            Text(
                text = title,
                style =
                    if (compact) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.titleLarge
                    },
                fontWeight = FontWeight.Bold,
                color = foreground,
                maxLines = if (compact) 1 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .basicMarquee()
                        .hushPressable(onClick = onTitleClick),
            )
            ClickableArtists(
                artists = artists,
                onArtistClick = onArtistClick,
                style = MaterialTheme.typography.titleMedium,
                color = foreground,
                modifier = Modifier.basicMarquee(),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            V8ActionButton(
                iconRes = R.drawable.more_vert,
                contentDescription = stringResource(R.string.more_options),
                foreground = foreground,
                containerColor = foreground.copy(alpha = 0.16f),
                iconSize = 24.dp,
                onClick = onMenuClick,
            )
            V8ActionButton(
                iconRes = if (liked) R.drawable.favorite else R.drawable.favorite_border,
                contentDescription = stringResource(R.string.action_like),
                foreground = foreground,
                containerColor = foreground.copy(alpha = 0.16f),
                iconSize = 26.dp,
                onClick = onToggleLike,
            )
        }
    }
}

@Composable
private fun V8ActionButton(
    iconRes: Int,
    contentDescription: String,
    foreground: Color,
    containerColor: Color,
    iconSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = containerColor,
        modifier = Modifier.size(48.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                tint = foreground,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
private fun V8PlaybackProgress(
    sliderStyle: SliderStyle,
    sliderPosition: Long?,
    position: Long,
    duration: Long,
    isPlaying: Boolean,
    currentFormat: FormatEntity?,
    foreground: Color,
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
) {
    val safeDuration = if (duration <= 0L || duration == C.TIME_UNSET) 0f else duration.toFloat()
    val safeValue = (sliderPosition ?: position).toFloat().coerceIn(0f, maxOf(0f, safeDuration))

    Column(modifier = Modifier.fillMaxWidth()) {
        StyledPlaybackSlider(
            sliderStyle = sliderStyle,
            value = safeValue,
            valueRange = 0f..maxOf(1f, safeDuration),
            onValueChange = { onSliderValueChange(it.toLong()) },
            onValueChangeFinished = onSliderValueChangeFinished,
            activeColor = foreground.copy(alpha = 0.88f),
            isPlaying = isPlaying,
            modifier = Modifier.fillMaxWidth(),
        )

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
        ) {
            Text(
                text = makeTimeString(sliderPosition ?: position),
                style = MaterialTheme.typography.labelMedium,
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.CenterStart),
            )

            if (currentFormat != null) {
                V8QualityChip(
                    currentFormat = currentFormat,
                    foreground = foreground,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            Text(
                text = if (duration != C.TIME_UNSET) makeTimeString(duration) else "",
                style = MaterialTheme.typography.labelMedium,
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

@Composable
private fun V8QualityChip(
    currentFormat: FormatEntity,
    foreground: Color,
    modifier: Modifier = Modifier,
) {
    val label =
        remember(currentFormat.mimeType, currentFormat.codecs) {
            currentFormat.codecLabel()
        }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = foreground.copy(alpha = 0.1f),
        border =
            androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = foreground.copy(alpha = 0.13f),
            ),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.graphic_eq),
                contentDescription = null,
                tint = foreground.copy(alpha = 0.72f),
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = foreground.copy(alpha = 0.72f),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun V8TransportControls(
    playbackState: Int,
    isPlaying: Boolean,
    isLoading: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    foreground: Color,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    modifier: Modifier = Modifier,
    landscape: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    val sideTouchSize = if (landscape) 64.dp else 72.dp
    val sideIconSize = if (landscape) 36.dp else 46.dp
    val playButtonSize = if (landscape) 68.dp else 76.dp
    val playIconSize = if (landscape) 42.dp else 54.dp

    if (landscape) {
        BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            val requiredWidth = sideTouchSize * 2 + playButtonSize
            val layoutScale = (maxWidth / requiredWidth).coerceAtMost(1f)
            val scaledSideTouch = sideTouchSize * layoutScale
            val scaledSideIcon = sideIconSize * layoutScale
            val scaledPlayButton = playButtonSize * layoutScale
            val scaledPlayIcon = playIconSize * layoutScale

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                V8TransportButton(
                    iconRes = R.drawable.skip_previous,
                    contentDescription = stringResource(R.string.widget_previous),
                    foreground = foreground,
                    enabled = canSkipPrevious,
                    touchSize = scaledSideTouch,
                    iconSize = scaledSideIcon,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPreviousClick()
                    },
                )

                Surface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPlayPauseClick()
                    },
                    shape = CircleShape,
                    color = Color.Transparent,
                    modifier =
                        Modifier
                            .size(scaledPlayButton)
                            .hushPlayButtonBackground(CircleShape),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isLoading) {
                            CircularWavyProgressIndicator(
                                modifier = Modifier.size(scaledPlayIcon),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(
                                painter =
                                    painterResource(
                                        when {
                                            playbackState == STATE_ENDED -> R.drawable.replay
                                            isPlaying -> R.drawable.pause
                                            else -> R.drawable.play
                                        },
                                    ),
                                contentDescription =
                                    if (isPlaying) {
                                        stringResource(R.string.widget_pause)
                                    } else {
                                        stringResource(R.string.play)
                                    },
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(scaledPlayIcon),
                            )
                        }
                    }
                }

                V8TransportButton(
                    iconRes = R.drawable.skip_next,
                    contentDescription = stringResource(R.string.next),
                    foreground = foreground,
                    enabled = canSkipNext,
                    touchSize = scaledSideTouch,
                    iconSize = scaledSideIcon,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onNextClick()
                    },
                )
            }
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        V8TransportButton(
            iconRes = R.drawable.skip_previous,
            contentDescription = stringResource(R.string.widget_previous),
            foreground = foreground,
            enabled = canSkipPrevious,
            touchSize = sideTouchSize,
            iconSize = sideIconSize,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onPreviousClick()
            },
        )

        Surface(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onPlayPauseClick()
            },
            shape = CircleShape,
            color = Color.Transparent,
            modifier =
                Modifier
                    .size(playButtonSize)
                    .hushPlayButtonBackground(CircleShape),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (isLoading) {
                    CircularWavyProgressIndicator(
                        modifier = Modifier.size(playIconSize),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        painter =
                            painterResource(
                                when {
                                    playbackState == STATE_ENDED -> R.drawable.replay
                                    isPlaying -> R.drawable.pause
                                    else -> R.drawable.play
                                },
                            ),
                        contentDescription =
                            if (isPlaying) {
                                stringResource(R.string.widget_pause)
                            } else {
                                stringResource(R.string.play)
                            },
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(playIconSize),
                    )
                }
            }
        }

        V8TransportButton(
            iconRes = R.drawable.skip_next,
            contentDescription = stringResource(R.string.next),
            foreground = foreground,
            enabled = canSkipNext,
            touchSize = sideTouchSize,
            iconSize = sideIconSize,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onNextClick()
            },
        )
    }
}

@Composable
private fun V8TransportButton(
    iconRes: Int,
    contentDescription: String,
    foreground: Color,
    enabled: Boolean,
    touchSize: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Surface(
        shape = CircleShape,
        color = foreground.copy(alpha = if (enabled) 0.12f else 0.06f),
        modifier =
            Modifier
                .size(touchSize)
                .hushPressable(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onClick()
                    },
                    enabled = enabled,
                    pressScale = HushDesign.TransportPressScale,
                ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                tint = foreground.copy(alpha = if (enabled) 1f else 0.4f),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
private fun V8VolumeControls(
    volume: Float,
    foreground: Color,
    secondaryForeground: Color,
    onVolumeChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.volume_off),
            contentDescription = stringResource(R.string.minimum_volume),
            tint = secondaryForeground,
            modifier = Modifier.size(22.dp),
        )
        V8FlatSlider(
            value = volume.coerceIn(0f, 1f),
            valueRange = 0f..1f,
            activeColor = foreground.copy(alpha = 0.86f),
            inactiveColor = foreground.copy(alpha = 0.24f),
            trackHeight = 8.dp,
            onValueChange = { onVolumeChange(it.coerceIn(0f, 1f)) },
            onValueChangeFinished = {},
            modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = 18.dp),
        )
        Icon(
            painter = painterResource(R.drawable.volume_up),
            contentDescription = stringResource(R.string.maximum_volume),
            tint = secondaryForeground,
            modifier = Modifier.size(24.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun V8FlatSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    activeColor: Color,
    inactiveColor: Color,
    trackHeight: androidx.compose.ui.unit.Dp,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val safeEnd = valueRange.endInclusive.coerceAtLeast(valueRange.start + 1f)
    val safeRange = valueRange.start..safeEnd
    val colors =
        SliderDefaults.colors(
            activeTrackColor = activeColor,
            activeTickColor = activeColor,
            thumbColor = Color.Transparent,
            inactiveTrackColor = inactiveColor,
        )

    Slider(
        value = value.coerceIn(safeRange),
        valueRange = safeRange,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        enabled = enabled,
        colors = colors,
        thumb = { Spacer(modifier = Modifier.size(0.dp)) },
        track = { sliderState ->
            PlayerSliderTrack(
                sliderState = sliderState,
                colors = colors,
                trackHeight = trackHeight,
            )
        },
        modifier = modifier.height(30.dp),
    )
}

@Composable
fun V9PlayerContent(
    mediaMetadata: MediaMetadata,
    sliderStyle: SliderStyle,
    playbackState: Int,
    isPlaying: Boolean,
    isLoading: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    sliderPosition: Long?,
    position: Long,
    duration: Long,
    playerConnection: PlayerConnection,
    navController: NavController,
    state: BottomSheetState,
    textBackgroundColor: Color,
    textButtonColor: Color,
    iconButtonColor: Color,
    canvasPrimaryUrl: String?,
    canvasFallbackUrl: String?,
    onCollapseClick: () -> Unit,
    onQueueClick: () -> Unit,
    onLyricsClick: () -> Unit,
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    landscape: Boolean = false,
) {
    val artworkUrl = mediaMetadata.thumbnailUrl?.highRes()
    val titleActions = rememberPlayerTitleActions(mediaMetadata, navController, state)
    val onTitleClick = titleActions.onTitleClick
    val onArtistClick = titleActions.onArtistClick
    val onPlayPauseClick = {
        if (playbackState == STATE_ENDED) {
            playerConnection.player.seekTo(0, 0)
            playerConnection.player.playWhenReady = true
        } else {
            playerConnection.player.togglePlayPause()
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val useLandscapeLayout = landscape || maxWidth > maxHeight

        if (useLandscapeLayout) {
            V9LandscapeContent(
            title = mediaMetadata.title,
            artists = mediaMetadata.artists,
            artworkUrl = artworkUrl,
            canvasPrimaryUrl = canvasPrimaryUrl,
            canvasFallbackUrl = canvasFallbackUrl,
            sliderStyle = sliderStyle,
            playbackState = playbackState,
            isPlaying = isPlaying,
            isLoading = isLoading,
            canSkipPrevious = canSkipPrevious,
            canSkipNext = canSkipNext,
            sliderPosition = sliderPosition,
            position = position,
            duration = duration,
            textBackgroundColor = textBackgroundColor,
            textButtonColor = textButtonColor,
            iconButtonColor = iconButtonColor,
            onCollapseClick = onCollapseClick,
            onQueueClick = onQueueClick,
            onLyricsClick = onLyricsClick,
            onTitleClick = onTitleClick,
            onArtistClick = onArtistClick,
            onPreviousClick = playerConnection::seekToPrevious,
            onPlayPauseClick = onPlayPauseClick,
            onNextClick = playerConnection::seekToNext,
            onSliderValueChange = onSliderValueChange,
            onSliderValueChangeFinished = onSliderValueChangeFinished,
            modifier = Modifier.fillMaxSize(),
        )
        } else {
            V9PortraitContent(
            title = mediaMetadata.title,
            artists = mediaMetadata.artists,
            artworkUrl = artworkUrl,
            canvasPrimaryUrl = canvasPrimaryUrl,
            canvasFallbackUrl = canvasFallbackUrl,
            sliderStyle = sliderStyle,
            playbackState = playbackState,
            isPlaying = isPlaying,
            isLoading = isLoading,
            canSkipPrevious = canSkipPrevious,
            canSkipNext = canSkipNext,
            sliderPosition = sliderPosition,
            position = position,
            duration = duration,
            textBackgroundColor = textBackgroundColor,
            textButtonColor = textButtonColor,
            iconButtonColor = iconButtonColor,
            onCollapseClick = onCollapseClick,
            onQueueClick = onQueueClick,
            onLyricsClick = onLyricsClick,
            onTitleClick = onTitleClick,
            onArtistClick = onArtistClick,
            onPreviousClick = playerConnection::seekToPrevious,
            onPlayPauseClick = onPlayPauseClick,
            onNextClick = playerConnection::seekToNext,
            onSliderValueChange = onSliderValueChange,
            onSliderValueChangeFinished = onSliderValueChangeFinished,
            modifier = Modifier.fillMaxSize(),
        )
        }
    }
}

@Composable
private fun V9PortraitContent(
    title: String,
    artists: List<MediaMetadata.Artist>,
    artworkUrl: String?,
    canvasPrimaryUrl: String?,
    canvasFallbackUrl: String?,
    sliderStyle: SliderStyle,
    playbackState: Int,
    isPlaying: Boolean,
    isLoading: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    sliderPosition: Long?,
    position: Long,
    duration: Long,
    textBackgroundColor: Color,
    textButtonColor: Color,
    iconButtonColor: Color,
    onCollapseClick: () -> Unit,
    onQueueClick: () -> Unit,
    onLyricsClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    onTitleClick: () -> Unit,
    onArtistClick: (artistId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val horizontalPadding = if (maxWidth < 380.dp) 16.dp else 20.dp
        val compactHeight = maxHeight < 760.dp
        val veryCompactHeight = maxHeight < 700.dp

        val artworkMinSize =
            when {
                veryCompactHeight -> 200.dp
                compactHeight -> 216.dp
                else -> 236.dp
            }
        val artworkHeightLimit =
            maxHeight *
                when {
                    veryCompactHeight -> 0.32f
                    compactHeight -> 0.35f
                    else -> 0.40f
                }
        val artworkSize =
            (maxWidth - horizontalPadding * 2)
                .coerceAtMost(artworkHeightLimit)
                .coerceAtLeast(artworkMinSize)

        val headerGap =
            when {
                veryCompactHeight -> 14.dp
                compactHeight -> 18.dp
                else -> 26.dp
            }
        val metadataGap =
            when {
                veryCompactHeight -> 16.dp
                compactHeight -> 20.dp
                else -> 26.dp
            }
        val controlsGap =
            when {
                veryCompactHeight -> 12.dp
                compactHeight -> 16.dp
                else -> 22.dp
            }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(if (compactHeight) 8.dp else 14.dp))

            V9Header(
                textColor = textBackgroundColor,
                containerColor = textButtonColor.copy(alpha = 0.16f),
                iconColor = textBackgroundColor,
                onCollapseClick = onCollapseClick,
                onLyricsClick = onLyricsClick,
                onQueueClick = onQueueClick,
            )

            Spacer(Modifier.height(headerGap))

            V9Artwork(
                artworkUrl = artworkUrl,
                canvasPrimaryUrl = canvasPrimaryUrl,
                canvasFallbackUrl = canvasFallbackUrl,
                isPlaying = isPlaying,
                size = artworkSize,
                placeholderColor = textButtonColor.copy(alpha = 0.12f),
            )

            Spacer(Modifier.height(metadataGap))

            V9Metadata(
                title = title,
                artists = artists,
                textColor = textBackgroundColor,
                onTitleClick = onTitleClick,
                onArtistClick = onArtistClick,
            )

            Spacer(Modifier.height(controlsGap))

            V9PlaybackProgress(
                sliderStyle = sliderStyle,
                sliderPosition = sliderPosition,
                position = position,
                duration = duration,
                isPlaying = isPlaying,
                activeColor = textButtonColor,
                textColor = textBackgroundColor,
                onSliderValueChange = onSliderValueChange,
                onSliderValueChangeFinished = onSliderValueChangeFinished,
            )

            Spacer(Modifier.height(if (compactHeight) 24.dp else 32.dp))

            V9TransportControls(
                playbackState = playbackState,
                isPlaying = isPlaying,
                isLoading = isLoading,
                canSkipPrevious = canSkipPrevious,
                canSkipNext = canSkipNext,
                containerColor = textButtonColor.copy(alpha = 0.14f),
                primaryContainerColor = textButtonColor,
                iconColor = textBackgroundColor,
                primaryIconColor = iconButtonColor,
                onPreviousClick = onPreviousClick,
                onPlayPauseClick = onPlayPauseClick,
                onNextClick = onNextClick,
                landscape = false,
            )

            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun V9LandscapeContent(
    title: String,
    artists: List<MediaMetadata.Artist>,
    artworkUrl: String?,
    canvasPrimaryUrl: String?,
    canvasFallbackUrl: String?,
    sliderStyle: SliderStyle,
    playbackState: Int,
    isPlaying: Boolean,
    isLoading: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    sliderPosition: Long?,
    position: Long,
    duration: Long,
    textBackgroundColor: Color,
    textButtonColor: Color,
    iconButtonColor: Color,
    onCollapseClick: () -> Unit,
    onQueueClick: () -> Unit,
    onLyricsClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    onTitleClick: () -> Unit,
    onArtistClick: (artistId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val compactHeight = maxHeight < LandscapePlayerCompactHeight
        val verticalPadding = if (compactHeight) 10.dp else 16.dp
        val minControlsHeight =
            landscapeControlsFooterReserve(
                compact = compactHeight,
                includeSecondaryActions = false,
            )
        val artworkSize =
            landscapePlayerArtworkSize(
                maxWidth = maxWidth,
                maxHeight = maxHeight,
                verticalPadding = verticalPadding,
                minControlsHeight = minControlsHeight,
                maxHeightFraction = if (compactHeight) 0.58f else 0.68f,
                maxWidthFraction = 0.36f,
                minSize = if (compactHeight) 108.dp else 132.dp,
            )

        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = if (maxWidth < 560.dp) 20.dp else 28.dp, vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (maxWidth < 560.dp) 18.dp else 26.dp),
        ) {
            V9Artwork(
                artworkUrl = artworkUrl,
                canvasPrimaryUrl = canvasPrimaryUrl,
                canvasFallbackUrl = canvasFallbackUrl,
                isPlaying = isPlaying,
                size = artworkSize,
                placeholderColor = textButtonColor.copy(alpha = 0.12f),
            )

            LandscapePlayerControlsColumn(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                compact = compactHeight,
                footerContent = {
                    V9PlaybackProgress(
                        sliderStyle = sliderStyle,
                        sliderPosition = sliderPosition,
                        position = position,
                        duration = duration,
                        isPlaying = isPlaying,
                        activeColor = textButtonColor,
                        textColor = textBackgroundColor,
                        onSliderValueChange = onSliderValueChange,
                        onSliderValueChangeFinished = onSliderValueChangeFinished,
                    )

                    Spacer(Modifier.height(if (compactHeight) 16.dp else 20.dp))

                    V9TransportControls(
                        playbackState = playbackState,
                        isPlaying = isPlaying,
                        isLoading = isLoading,
                        canSkipPrevious = canSkipPrevious,
                        canSkipNext = canSkipNext,
                        containerColor = textButtonColor.copy(alpha = 0.14f),
                        primaryContainerColor = textButtonColor,
                        iconColor = textBackgroundColor,
                        primaryIconColor = iconButtonColor,
                        onPreviousClick = onPreviousClick,
                        onPlayPauseClick = onPlayPauseClick,
                        onNextClick = onNextClick,
                        landscape = true,
                        landscapeCompact = compactHeight,
                    )
                },
            ) {
                V9Header(
                    textColor = textBackgroundColor,
                    containerColor = textButtonColor.copy(alpha = 0.16f),
                    iconColor = textBackgroundColor,
                    onCollapseClick = onCollapseClick,
                    onLyricsClick = onLyricsClick,
                    onQueueClick = onQueueClick,
                )

                Spacer(Modifier.height(if (compactHeight) 10.dp else 18.dp))

                V9Metadata(
                    title = title,
                    artists = artists,
                    textColor = textBackgroundColor,
                    onTitleClick = onTitleClick,
                    onArtistClick = onArtistClick,
                )
            }
        }
    }
}

@Composable
private fun V9Header(
    textColor: Color,
    containerColor: Color,
    iconColor: Color,
    onCollapseClick: () -> Unit,
    onLyricsClick: () -> Unit,
    onQueueClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        V9HeaderButton(
            iconRes = R.drawable.expand_more,
            contentDescription = null,
            containerColor = containerColor,
            iconColor = iconColor,
            shape = CircleShape,
            onClick = onCollapseClick,
        )

        Text(
            text = stringResource(R.string.now_playing),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .weight(1f)
                    .basicMarquee(),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            V9HeaderButton(
                iconRes = R.drawable.lyrics,
                contentDescription = stringResource(R.string.lyrics),
                containerColor = containerColor,
                iconColor = iconColor,
                shape = RoundedCornerShape(22.dp),
                onClick = onLyricsClick,
            )
            V9HeaderButton(
                iconRes = R.drawable.queue_music,
                contentDescription = stringResource(R.string.queue),
                containerColor = containerColor,
                iconColor = iconColor,
                shape = RoundedCornerShape(22.dp),
                onClick = onQueueClick,
            )
        }
    }
}

@Composable
private fun PlayerChromeActionRow(
    containerColor: Color,
    iconColor: Color,
    onLyricsClick: () -> Unit,
    onQueueClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            V9HeaderButton(
                iconRes = R.drawable.lyrics,
                contentDescription = stringResource(R.string.lyrics),
                containerColor = containerColor,
                iconColor = iconColor,
                shape = RoundedCornerShape(22.dp),
                onClick = onLyricsClick,
            )
            V9HeaderButton(
                iconRes = R.drawable.queue_music,
                contentDescription = stringResource(R.string.queue),
                containerColor = containerColor,
                iconColor = iconColor,
                shape = RoundedCornerShape(22.dp),
                onClick = onQueueClick,
            )
        }
    }
}

@Composable
private fun V9HeaderButton(
    iconRes: Int,
    contentDescription: String?,
    containerColor: Color,
    iconColor: Color,
    shape: androidx.compose.ui.graphics.Shape,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = shape,
        color = containerColor,
        modifier = Modifier.size(56.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                tint = iconColor,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun V9Artwork(
    artworkUrl: String?,
    canvasPrimaryUrl: String?,
    canvasFallbackUrl: String?,
    isPlaying: Boolean,
    size: Dp,
    placeholderColor: Color,
) {
    val artworkRequest = rememberOfflineArtworkImageRequest(artworkUrl)
    Box(
        modifier =
            Modifier
                .size(size)
                .clip(RoundedCornerShape(30.dp))
                .background(placeholderColor),
    ) {
        AsyncImage(
            model = artworkRequest,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        if (!canvasPrimaryUrl.isNullOrBlank() || !canvasFallbackUrl.isNullOrBlank()) {
            CanvasArtworkPlayer(
                primaryUrl = canvasPrimaryUrl,
                fallbackUrl = canvasFallbackUrl,
                isPlaying = isPlaying,
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun V9Metadata(
    title: String,
    artists: List<MediaMetadata.Artist>,
    textColor: Color,
    onTitleClick: () -> Unit,
    onArtistClick: (artistId: String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = textColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .basicMarquee()
                    .hushPressable(onClick = onTitleClick),
        )
        ClickableArtists(
            artists = artists,
            onArtistClick = onArtistClick,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = textColor.copy(alpha = 0.72f),
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .basicMarquee(),
        )
    }
}

@Composable
private fun V9PlaybackProgress(
    sliderStyle: SliderStyle,
    sliderPosition: Long?,
    position: Long,
    duration: Long,
    isPlaying: Boolean,
    activeColor: Color,
    textColor: Color,
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
) {
    val safeDuration = if (duration <= 0L || duration == C.TIME_UNSET) 0f else duration.toFloat()
    val safeValue = (sliderPosition ?: position).toFloat().coerceIn(0f, maxOf(0f, safeDuration))

    Column(modifier = Modifier.fillMaxWidth()) {
        StyledPlaybackSlider(
            sliderStyle = sliderStyle,
            value = safeValue,
            valueRange = 0f..maxOf(1f, safeDuration),
            onValueChange = { onSliderValueChange(it.toLong()) },
            onValueChangeFinished = onSliderValueChangeFinished,
            activeColor = activeColor,
            isPlaying = isPlaying,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (sliderStyle == SliderStyle.Wavy) {
                            Modifier.height(36.dp)
                        } else {
                            Modifier
                        },
                    ),
        )

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = makeTimeString(sliderPosition ?: position),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = textColor.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (duration != C.TIME_UNSET) makeTimeString(duration) else "",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = textColor.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun V9TransportControls(
    playbackState: Int,
    isPlaying: Boolean,
    isLoading: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    containerColor: Color,
    primaryContainerColor: Color,
    iconColor: Color,
    primaryIconColor: Color,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    landscape: Boolean = false,
    landscapeCompact: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    val playPauseCorner by animateDpAsState(
        targetValue = if (isPlaying) 34.dp else 52.dp,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        label = "v9PlayPauseCorner",
    )

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val controlHeight =
            when {
                landscape && landscapeCompact -> 72.dp
                landscape -> 80.dp
                else -> 110.dp
            }
        val buttonGap = if (landscape) 12.dp else 10.dp
        val sideIconSize = if (landscape) 32.dp else 34.dp
        val playIconSize = if (landscape) 38.dp else 42.dp

        if (landscape) {
            val baseSideWidth = 96.dp
            val baseCenterWidth = 116.dp
            val requiredWidth = baseSideWidth * 2 + baseCenterWidth + buttonGap * 2
            val layoutScale = (maxWidth / requiredWidth).coerceAtMost(1f)
            val sideWidth = baseSideWidth * layoutScale
            val centerWidth = baseCenterWidth * layoutScale
            val height = controlHeight * layoutScale

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                V9TransportButton(
                    iconRes = R.drawable.skip_previous,
                    contentDescription = stringResource(R.string.widget_previous),
                    enabled = canSkipPrevious,
                    containerColor = containerColor,
                    iconColor = iconColor,
                    iconSize = sideIconSize,
                    modifier = Modifier.width(sideWidth).height(height),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPreviousClick()
                    },
                )

                Spacer(Modifier.width(buttonGap))

                Surface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPlayPauseClick()
                    },
                    shape = RoundedCornerShape(playPauseCorner),
                    color = primaryContainerColor,
                    modifier = Modifier.width(centerWidth).height(height),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isLoading) {
                            CircularWavyProgressIndicator(
                                modifier = Modifier.size(playIconSize),
                                color = primaryIconColor,
                            )
                        } else {
                            AnimatedContent(
                                targetState =
                                    when {
                                        playbackState == STATE_ENDED -> R.drawable.replay
                                        isPlaying -> R.drawable.pause
                                        else -> R.drawable.play
                                    },
                                transitionSpec = {
                                    fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) togetherWith fadeOut(tween(90))
                                },
                                label = "v9PlayPauseIcon",
                            ) { iconRes ->
                                Icon(
                                    painter = painterResource(iconRes),
                                    contentDescription =
                                        if (isPlaying) {
                                            stringResource(R.string.widget_pause)
                                        } else {
                                            stringResource(R.string.play)
                                        },
                                    tint = primaryIconColor,
                                    modifier = Modifier.size(playIconSize),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.width(buttonGap))

                V9TransportButton(
                    iconRes = R.drawable.skip_next,
                    contentDescription = stringResource(R.string.next),
                    enabled = canSkipNext,
                    containerColor = containerColor,
                    iconColor = iconColor,
                    iconSize = sideIconSize,
                    modifier = Modifier.width(sideWidth).height(height),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onNextClick()
                    },
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(buttonGap),
            ) {
                V9TransportButton(
                    iconRes = R.drawable.skip_previous,
                    contentDescription = stringResource(R.string.widget_previous),
                    enabled = canSkipPrevious,
                    containerColor = containerColor,
                    iconColor = iconColor,
                    iconSize = sideIconSize,
                    modifier = Modifier.weight(1f).height(controlHeight),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPreviousClick()
                    },
                )

                Surface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPlayPauseClick()
                    },
                    shape = RoundedCornerShape(playPauseCorner),
                    color = primaryContainerColor,
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(controlHeight),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isLoading) {
                            CircularWavyProgressIndicator(
                                modifier = Modifier.size(playIconSize),
                                color = primaryIconColor,
                            )
                        } else {
                            AnimatedContent(
                                targetState =
                                    when {
                                        playbackState == STATE_ENDED -> R.drawable.replay
                                        isPlaying -> R.drawable.pause
                                        else -> R.drawable.play
                                    },
                                transitionSpec = {
                                    fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) togetherWith fadeOut(tween(90))
                                },
                                label = "v9PlayPauseIcon",
                            ) { iconRes ->
                                Icon(
                                    painter = painterResource(iconRes),
                                    contentDescription =
                                        if (isPlaying) {
                                            stringResource(R.string.widget_pause)
                                        } else {
                                            stringResource(R.string.play)
                                        },
                                    tint = primaryIconColor,
                                    modifier = Modifier.size(playIconSize),
                                )
                            }
                        }
                    }
                }

                V9TransportButton(
                    iconRes = R.drawable.skip_next,
                    contentDescription = stringResource(R.string.next),
                    enabled = canSkipNext,
                    containerColor = containerColor,
                    iconColor = iconColor,
                    iconSize = sideIconSize,
                    modifier = Modifier.weight(1f).height(controlHeight),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onNextClick()
                    },
                )
            }
        }
    }
}

@Composable
private fun V9TransportButton(
    iconRes: Int,
    contentDescription: String,
    enabled: Boolean,
    containerColor: Color,
    iconColor: Color,
    modifier: Modifier = Modifier,
    iconSize: androidx.compose.ui.unit.Dp = 34.dp,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(56.dp),
        color = containerColor,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                tint = iconColor.copy(alpha = if (enabled) 0.88f else 0.36f),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
fun PlayerBackground(
    playerBackground: PlayerBackgroundStyle,
    mediaMetadata: MediaMetadata?,
    gradientColors: List<Color>,
    disableBlur: Boolean,
    blurRadius: Float,
    playerCustomImageUri: String,
    playerCustomBlur: Float,
    playerCustomContrast: Float,
    playerCustomBrightness: Float,
) {
    val effectiveBlurRadius = blurRadius.coerceIn(0f, PlayerBackgroundMaxBlurRadius)
    val shouldApplyBlur = !disableBlur && effectiveBlurRadius > 0f
    val styleAppliesBlur =
        effectiveBlurRadius > 0f && effectiveBlurRadius >= 0.5f
    Box(modifier = Modifier.fillMaxSize()) {
        when (playerBackground) {
            PlayerBackgroundStyle.BLUR -> {
                AnimatedContent(
                    targetState = mediaMetadata?.thumbnailUrl,
                    transitionSpec = {
                        fadeIn(tween(1000)) togetherWith fadeOut(tween(1000))
                    },
                    label = "",
                ) { thumbnailUrl ->
                    if (thumbnailUrl != null) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = thumbnailUrl.highRes(),
                                contentDescription = "Blurred background",
                                contentScale = ContentScale.Crop,
                                modifier =
                                    Modifier.fillMaxSize().let {
                                        if (styleAppliesBlur) it.blur(radius = effectiveBlurRadius.dp) else it
                                    },
                            )
                            val overlayStops = PlayerBackgroundColorUtils.buildBlurOverlayStops(gradientColors)
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .background(Brush.verticalGradient(colorStops = overlayStops)),
                            )
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.08f)),
                            )
                        }
                    }
                }
            }

            PlayerBackgroundStyle.GRADIENT -> {
                AnimatedContent(
                    targetState = gradientColors,
                    transitionSpec = {
                        fadeIn(tween(1000)) togetherWith fadeOut(tween(1000))
                    },
                    label = "",
                ) { colors ->
                    if (colors.isNotEmpty()) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            val gradientColorStops =
                                if (colors.size >= 3) {
                                    arrayOf(
                                        0.0f to colors[0].copy(alpha = 0.92f), // Top: primary vibrant color
                                        0.5f to colors[1].copy(alpha = 0.75f), // Middle: darker variant
                                        1.0f to colors[2].copy(alpha = 0.65f), // Bottom: black-ish
                                    )
                                } else {
                                    arrayOf(
                                        0.0f to colors[0].copy(alpha = 0.9f), // Top: primary color
                                        0.6f to colors[0].copy(alpha = 0.55f), // Middle: faded variant
                                        1.0f to Color.Black.copy(alpha = 0.7f), // Bottom: black
                                    )
                                }
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .background(Brush.verticalGradient(colorStops = gradientColorStops)),
                            )
                            // Keep a gentle dark overlay to ensure text contrast on bright artwork
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.18f)),
                            )
                        }
                    }
                }
            }

            PlayerBackgroundStyle.COLORING -> {
                AnimatedContent(
                    targetState = gradientColors,
                    transitionSpec = {
                        fadeIn(tween(1000)) togetherWith fadeOut(tween(1000))
                    },
                    label = "",
                ) { colors ->
                    if (colors.isNotEmpty()) {
                        val baseColor = PlayerBackgroundColorUtils.ensureComfortableColor(colors.first())
                        val gradientStops = PlayerBackgroundColorUtils.buildColoringStops(baseColor)
                        Box(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.fillMaxSize().background(baseColor))
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .background(Brush.verticalGradient(colorStops = gradientStops)),
                            )
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.25f)),
                            )
                        }
                    }
                }
            }

            PlayerBackgroundStyle.BLUR_GRADIENT -> {
                AnimatedContent(
                    targetState = mediaMetadata?.thumbnailUrl,
                    transitionSpec = {
                        fadeIn(tween(1000)) togetherWith fadeOut(tween(1000))
                    },
                    label = "",
                ) { thumbnailUrl ->
                    if (thumbnailUrl != null) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = thumbnailUrl.highRes(),
                                contentDescription = "Blurred background",
                                contentScale = ContentScale.Crop,
                                modifier =
                                    Modifier.fillMaxSize().let {
                                        if (styleAppliesBlur) it.blur(radius = effectiveBlurRadius.dp) else it
                                    },
                            )
                            val gradientColorStops =
                                PlayerBackgroundColorUtils.buildBlurGradientStops(gradientColors)
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .background(Brush.verticalGradient(colorStops = gradientColorStops)),
                            )
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.05f)),
                            )
                        }
                    }
                }
            }

            PlayerBackgroundStyle.CUSTOM -> {
                AnimatedContent(
                    targetState = playerCustomImageUri,
                    transitionSpec = {
                        fadeIn(tween(1000)) togetherWith fadeOut(tween(1000))
                    },
                    label = "",
                ) { uri ->
                    if (uri.isNotBlank()) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            val blurPx = playerCustomBlur
                            val contrastVal = playerCustomContrast
                            val brightnessVal = playerCustomBrightness

                            val t = (1f - contrastVal) * 128f + (brightnessVal - 1f) * 255f
                            val matrix =
                                floatArrayOf(
                                    contrastVal,
                                    0f,
                                    0f,
                                    0f,
                                    t,
                                    0f,
                                    contrastVal,
                                    0f,
                                    0f,
                                    t,
                                    0f,
                                    0f,
                                    contrastVal,
                                    0f,
                                    t,
                                    0f,
                                    0f,
                                    0f,
                                    1f,
                                    0f,
                                )

                            val cm = ColorMatrix(matrix)

                            AsyncImage(
                                model = Uri.parse(uri),
                                contentDescription = "Custom background",
                                contentScale = ContentScale.Crop,
                                modifier =
                                    Modifier.fillMaxSize().let {
                                        if (disableBlur) it else it.blur(radius = blurPx.dp)
                                    },
                                colorFilter = ColorFilter.colorMatrix(cm),
                            )
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.4f)),
                            )
                        }
                    }
                }
            }

            PlayerBackgroundStyle.GLOW -> {
                AnimatedContent(
                    targetState = gradientColors,
                    transitionSpec = {
                        fadeIn(tween(1200)) togetherWith fadeOut(tween(1200))
                    },
                    label = "",
                ) { colors ->
                    if (colors.isNotEmpty()) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .drawWithCache {
                                        val width = size.width
                                        val height = size.height

                                        // Use a dark base, but the gradients will cover most of it
                                        val baseColor = Color(0xFF050505)

                                        // Extract up to 6 colors
                                        val color1 = colors.getOrElse(0) { Color.DarkGray }
                                        val color2 = colors.getOrElse(1) { color1 }
                                        val color3 = colors.getOrElse(2) { color2 }
                                        val color4 = colors.getOrElse(3) { color1 }
                                        val color5 = colors.getOrElse(4) { color2 }
                                        val color6 = colors.getOrElse(5) { color3 }

                                        // Top-Left Large Glow (Primary)
                                        val brush1 =
                                            Brush.radialGradient(
                                                colors =
                                                    listOf(
                                                        color1.copy(alpha = 0.8f),
                                                        color1.copy(alpha = 0.5f),
                                                        Color.Transparent,
                                                    ),
                                                center = Offset(width * 0.2f, height * 0.25f),
                                                radius = width * 1.2f,
                                            )

                                        // Bottom-Right Large Glow (Secondary)
                                        val brush2 =
                                            Brush.radialGradient(
                                                colors =
                                                    listOf(
                                                        color2.copy(alpha = 0.75f),
                                                        color2.copy(alpha = 0.45f),
                                                        Color.Transparent,
                                                    ),
                                                center = Offset(width * 0.85f, height * 0.8f),
                                                radius = width * 1.1f,
                                            )

                                        // Top-Right Glow (Tertiary)
                                        val brush3 =
                                            Brush.radialGradient(
                                                colors =
                                                    listOf(
                                                        color3.copy(alpha = 0.7f),
                                                        color3.copy(alpha = 0.4f),
                                                        Color.Transparent,
                                                    ),
                                                center = Offset(width * 0.9f, height * 0.15f),
                                                radius = width * 1.0f,
                                            )

                                        // Bottom-Left (Quaternary)
                                        val brush4 =
                                            Brush.radialGradient(
                                                colors =
                                                    listOf(
                                                        color4.copy(alpha = 0.65f),
                                                        color4.copy(alpha = 0.35f),
                                                        Color.Transparent,
                                                    ),
                                                center = Offset(width * 0.1f, height * 0.9f),
                                                radius = width * 1.0f,
                                            )

                                        // Top-Center (Quinary)
                                        val brush5 =
                                            Brush.radialGradient(
                                                colors =
                                                    listOf(
                                                        color5.copy(alpha = 0.6f),
                                                        color5.copy(alpha = 0.3f),
                                                        Color.Transparent,
                                                    ),
                                                center = Offset(width * 0.5f, height * 0.1f),
                                                radius = width * 0.9f,
                                            )

                                        // Bottom-Center (Senary)
                                        val brush6 =
                                            Brush.radialGradient(
                                                colors =
                                                    listOf(
                                                        color6.copy(alpha = 0.6f),
                                                        color6.copy(alpha = 0.3f),
                                                        Color.Transparent,
                                                    ),
                                                center = Offset(width * 0.5f, height * 0.95f),
                                                radius = width * 0.9f,
                                            )

                                        onDrawBehind {
                                            drawRect(color = baseColor)
                                            drawRect(brush = brush1)
                                            drawRect(brush = brush2)
                                            drawRect(brush = brush3)
                                            drawRect(brush = brush4)
                                            drawRect(brush = brush5)
                                            drawRect(brush = brush6)
                                        }
                                    },
                        )
                    }
                }
            }

            PlayerBackgroundStyle.GLOW_ANIMATED -> {
                AnimatedContent(
                    targetState = gradientColors,
                    transitionSpec = {
                        fadeIn(tween(1200)) togetherWith fadeOut(tween(1200))
                    },
                    label = "GlowAnimatedContent",
                ) { colors ->
                    if (colors.isNotEmpty()) {
                        val infiniteTransition = rememberInfiniteTransition(label = "GlowAnimation")

                        val progress by infiniteTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 1f,
                            animationSpec =
                                infiniteRepeatable(
                                    animation = tween(20000, easing = LinearEasing),
                                    repeatMode = RepeatMode.Restart,
                                ),
                            label = "glowProgress",
                        )

                        fun rotatedColorAt(index: Int): Color {
                            val size = colors.size
                            val idx = index.toFloat() + progress * size
                            val a = kotlin.math.floor(idx).toInt() % size
                            val b = (a + 1) % size
                            val frac = idx - kotlin.math.floor(idx)
                            return androidx.compose.ui.graphics.lerp(
                                colors.getOrElse(a) { Color.DarkGray },
                                colors.getOrElse(b) { Color.DarkGray },
                                frac,
                            )
                        }

                        fun oscillate(
                            min: Float,
                            max: Float,
                            phase: Float,
                            speed: Float = 1f,
                        ): Float {
                            // speed MUST be an integer to ensure seamless looping when progress wraps from 1f to 0f.
                            val v = kotlin.math.sin(2f * kotlin.math.PI.toFloat() * (progress * speed + phase)).toFloat()
                            return min + (max - min) * ((v + 1f) * 0.5f)
                        }

                        val color1 = rotatedColorAt(0)
                        val color2 = rotatedColorAt(1)
                        val color3 = rotatedColorAt(2)
                        val color4 = rotatedColorAt(3)
                        val color5 = rotatedColorAt(4)
                        val color6 = rotatedColorAt(5)

                        val o1x = oscillate(0.0f, 1.0f, 0.00f, 1.0f)
                        val o1y = oscillate(0.0f, 0.5f, 0.07f, 1.0f)
                        val r1 = oscillate(0.8f, 1.6f, 0.12f, 1.0f)

                        val o2x = oscillate(1.0f, 0.0f, 0.2f, 1.0f)
                        val o2y = oscillate(0.5f, 1.0f, 0.25f, 1.0f)
                        val r2 = oscillate(0.7f, 1.5f, 0.18f, 1.0f)

                        val o3x = oscillate(0.2f, 0.8f, 0.33f, 1.0f)
                        val o3y = oscillate(0.8f, 0.2f, 0.36f, 1.0f)
                        val r3 = oscillate(0.6f, 1.4f, 0.29f, 1.0f)

                        val o4x = oscillate(0.3f, 0.7f, 0.44f, 1.0f)
                        val o4y = oscillate(0.2f, 0.8f, 0.41f, 1.0f)
                        val r4 = oscillate(0.9f, 1.7f, 0.47f, 1.0f)

                        val o5x = oscillate(0.4f, 0.6f, 0.55f, 1.0f)
                        val o5y = oscillate(0.0f, 1.0f, 0.51f, 1.0f)
                        val r5 = oscillate(0.7f, 1.5f, 0.58f, 1.0f)

                        val o6x = oscillate(0.0f, 1.0f, 0.66f, 1.0f)
                        val o6y = oscillate(0.5f, 0.7f, 0.62f, 1.0f)
                        val r6 = oscillate(0.8f, 1.8f, 0.69f, 1.0f)

                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .drawWithCache {
                                        val width = size.width
                                        val height = size.height
                                        val baseColor = Color(0xFF050505)

                                        val brush1 =
                                            Brush.radialGradient(
                                                colors = listOf(color1.copy(alpha = 0.85f), color1.copy(alpha = 0.5f), Color.Transparent),
                                                center = Offset(width * o1x, height * o1y),
                                                radius = width * r1,
                                            )
                                        val brush2 =
                                            Brush.radialGradient(
                                                colors = listOf(color2.copy(alpha = 0.8f), color2.copy(alpha = 0.45f), Color.Transparent),
                                                center = Offset(width * o2x, height * o2y),
                                                radius = width * r2,
                                            )
                                        val brush3 =
                                            Brush.radialGradient(
                                                colors = listOf(color3.copy(alpha = 0.75f), color3.copy(alpha = 0.4f), Color.Transparent),
                                                center = Offset(width * o3x, height * o3y),
                                                radius = width * r3,
                                            )
                                        val brush4 =
                                            Brush.radialGradient(
                                                colors = listOf(color4.copy(alpha = 0.7f), color4.copy(alpha = 0.35f), Color.Transparent),
                                                center = Offset(width * o4x, height * o4y),
                                                radius = width * r4,
                                            )
                                        val brush5 =
                                            Brush.radialGradient(
                                                colors = listOf(color5.copy(alpha = 0.65f), color5.copy(alpha = 0.3f), Color.Transparent),
                                                center = Offset(width * o5x, height * o5y),
                                                radius = width * r5,
                                            )
                                        val brush6 =
                                            Brush.radialGradient(
                                                colors = listOf(color6.copy(alpha = 0.6f), color6.copy(alpha = 0.25f), Color.Transparent),
                                                center = Offset(width * o6x, height * o6y),
                                                radius = width * r6,
                                            )

                                        onDrawBehind {
                                            drawRect(color = baseColor)
                                            drawRect(brush = brush1)
                                            drawRect(brush = brush2)
                                            drawRect(brush = brush3)
                                            drawRect(brush = brush4)
                                            drawRect(brush = brush5)
                                            drawRect(brush = brush6)
                                        }
                                    },
                        )
                    }
                }
            }

            else -> {
                // DEFAULT or other modes - no background
            }
        }
    }
}

/**
 * Wide landscape / car player layout: controls on the left, large artwork on the right,
 * with source and audio info tiles beneath the art.
 */
@Composable
fun WideLandscapePlayerContent(
    mediaMetadata: MediaMetadata,
    queueTitle: String?,
    artworkUrl: String?,
    canvasPrimaryUrl: String?,
    canvasFallbackUrl: String?,
    playerDesignStyle: PlayerDesignStyle = PlayerDesignStyle.DEFAULT,
    sliderStyle: SliderStyle,
    playbackState: Int,
    isPlaying: Boolean,
    isLoading: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    currentSongLiked: Boolean,
    sliderPosition: Long?,
    position: Long,
    duration: Long,
    currentFormat: FormatEntity?,
    playerConnection: PlayerConnection,
    navController: NavController,
    state: BottomSheetState,
    menuState: MenuState,
    bottomSheetPageState: BottomSheetPageState,
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    onCollapseClick: () -> Unit,
    onLyricsClick: (() -> Unit)? = null,
    onQueueClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val foreground = Color.White
    val secondaryForeground = foreground.copy(alpha = 0.72f)
    val accent = MaterialTheme.colorScheme.primary
    val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsState()
    val repeatMode by playerConnection.repeatMode.collectAsState()
    val playbackSourceLabel by playerConnection.activePlaybackClientLabel.collectAsStateWithLifecycle()

    val onMenuClick =
        remember(mediaMetadata, navController, state, menuState, bottomSheetPageState) {
            {
                menuState.show {
                    PlayerMenu(
                        mediaMetadata = mediaMetadata,
                        navController = navController,
                        playerBottomSheetState = state,
                        onShowDetailsDialog = {
                            bottomSheetPageState.show {
                                ShowMediaInfo(mediaMetadata.id)
                            }
                        },
                        onDismiss = menuState::dismiss,
                    )
                }
            }
        }
    val titleActions = rememberPlayerTitleActions(mediaMetadata, navController, state)
    val onPlayPauseClick =
        remember(playbackState, playerConnection) {
            {
                if (playbackState == STATE_ENDED) {
                    playerConnection.player.seekTo(0, 0)
                    playerConnection.player.playWhenReady = true
                } else {
                    playerConnection.player.togglePlayPause()
                }
            }
        }

    val subtitle = queueTitle ?: mediaMetadata.album?.title.orEmpty()
    val sourceValue =
        playbackSourceLabel?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.playback_source_youtube)
    val audioValue =
        currentFormat?.codecLabel()?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.player_audio_stereo)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val compactHeight = maxHeight < LandscapePlayerCompactHeight
        val tightHeight = maxHeight < LandscapePlayerTightHeight
        val horizontalPadding = if (maxWidth < 640.dp) 20.dp else 32.dp
        val sectionGap = if (compactHeight) 16.dp else 24.dp
        val verticalPadding = if (compactHeight) 12.dp else 20.dp
        val minControlsHeight =
            landscapeControlsFooterReserve(
                compact = compactHeight,
                includeSecondaryActions = true,
            )
        val progressToTransportGap = if (compactHeight) 8.dp else 12.dp
        val artworkSize =
            landscapePlayerArtworkSize(
                maxWidth = maxWidth * 0.42f,
                maxHeight = maxHeight,
                verticalPadding = verticalPadding,
                minControlsHeight = minControlsHeight,
                maxHeightFraction = if (compactHeight) 0.52f else 0.62f,
                maxWidthFraction = 0.92f,
                minSize = if (compactHeight) 120.dp else 160.dp,
            )

        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sectionGap),
        ) {
            Column(
                modifier =
                    Modifier
                        .weight(0.92f)
                        .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                V8Artwork(
                    artworkUrl = artworkUrl,
                    canvasPrimaryUrl = canvasPrimaryUrl,
                    canvasFallbackUrl = canvasFallbackUrl,
                    isPlaying = isPlaying,
                    size = artworkSize,
                    cornerRadius = if (playerDesignStyle == PlayerDesignStyle.V9) 30.dp else 8.dp,
                )

                Spacer(Modifier.height(if (compactHeight) 12.dp else 18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    WideLandscapeInfoTile(
                        iconRes = R.drawable.mic,
                        label = stringResource(R.string.player_info_source),
                        value = sourceValue,
                        modifier = Modifier.weight(1f),
                    )
                    WideLandscapeInfoTile(
                        iconRes = R.drawable.graphic_eq,
                        label = stringResource(R.string.player_info_audio),
                        value = audioValue,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Column(
                modifier =
                    Modifier
                        .weight(1.08f)
                        .fillMaxHeight(),
            ) {
                LandscapePlayerControlsColumn(
                    modifier = Modifier.fillMaxSize(),
                    compact = compactHeight,
                    footerContent = {
                        V8PlaybackProgress(
                            sliderStyle = sliderStyle,
                            sliderPosition = sliderPosition,
                            position = position,
                            duration = duration,
                            isPlaying = isPlaying,
                            currentFormat = null,
                            foreground = accent,
                            onSliderValueChange = onSliderValueChange,
                            onSliderValueChangeFinished = onSliderValueChangeFinished,
                        )

                        Spacer(Modifier.height(progressToTransportGap))

                        V8TransportControls(
                            playbackState = playbackState,
                            isPlaying = isPlaying,
                            isLoading = isLoading,
                            canSkipPrevious = canSkipPrevious,
                            canSkipNext = canSkipNext,
                            foreground = foreground,
                            onPreviousClick = { playerConnection.seekToPrevious() },
                            onPlayPauseClick = onPlayPauseClick,
                            onNextClick = { playerConnection.seekToNext() },
                            landscape = true,
                        )

                        Spacer(Modifier.height(if (compactHeight) 6.dp else 8.dp))

                        PlayerLandscapeSecondaryActions(
                            mediaMetadata = mediaMetadata,
                            currentSongLiked = currentSongLiked,
                            shuffleModeEnabled = shuffleModeEnabled,
                            repeatMode = repeatMode,
                            accent = accent,
                            foreground = foreground,
                            playerConnection = playerConnection,
                            onMenuClick = onMenuClick,
                            compact = compactHeight,
                        )
                    },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.now_playing),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = accent,
                        )
                        Surface(
                            onClick = onCollapseClick,
                            shape = CircleShape,
                            color = foreground.copy(alpha = 0.12f),
                            modifier = Modifier.size(36.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(
                                    painter = painterResource(R.drawable.expand_more),
                                    contentDescription = null,
                                    tint = foreground,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(if (compactHeight) 8.dp else 12.dp))

                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(
                                    min =
                                        landscapeMetadataReservedHeight(
                                            compact = compactHeight,
                                            tight = tightHeight,
                                        ),
                                )
                                .padding(bottom = if (compactHeight) 8.dp else 12.dp),
                    ) {
                        PlayerTrackMetadataBlock(
                            title = mediaMetadata.title,
                            artists = mediaMetadata.artists,
                            foreground = foreground,
                            secondaryForeground = secondaryForeground,
                            onTitleClick = titleActions.onTitleClick,
                            onArtistClick = titleActions.onArtistClick,
                            titleMaxLines = 1,
                            titleStyle =
                                when {
                                    compactHeight -> MaterialTheme.typography.titleMedium
                                    tightHeight -> MaterialTheme.typography.titleLarge
                                    else -> MaterialTheme.typography.headlineMedium
                                },
                            metadataSpacing = if (compactHeight || tightHeight) 4.dp else 6.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerTrackMetadataBlock(
    title: String,
    artists: List<MediaMetadata.Artist>,
    foreground: Color,
    secondaryForeground: Color,
    onTitleClick: () -> Unit,
    onArtistClick: (artistId: String) -> Unit,
    modifier: Modifier = Modifier,
    titleMaxLines: Int = 3,
    textAlign: TextAlign = TextAlign.Start,
    titleStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.headlineSmall,
    metadataSpacing: Dp = 6.dp,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment =
            when (textAlign) {
                TextAlign.Center -> Alignment.CenterHorizontally
                TextAlign.End -> Alignment.End
                else -> Alignment.Start
            },
    ) {
        Text(
            text = title,
            style = titleStyle,
            fontWeight = FontWeight.Bold,
            color = foreground,
            maxLines = titleMaxLines,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .basicMarquee()
                    .hushPressable(onClick = onTitleClick),
        )

        Spacer(Modifier.height(metadataSpacing))

        ClickableArtists(
            artists = artists,
            onArtistClick = onArtistClick,
            style = MaterialTheme.typography.titleMedium,
            color = secondaryForeground,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .basicMarquee(),
        )
    }
}

@Composable
private fun V6ShuffleRepeatRow(
    shuffleModeEnabled: Boolean,
    repeatMode: Int,
    accent: Color,
    foreground: Color,
    playerConnection: PlayerConnection,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    landscape: Boolean = false,
) {
    val buttonSize =
        when {
            landscape -> 42.dp
            compact -> 40.dp
            else -> 42.dp
        }
    val iconSize =
        when {
            landscape -> 22.dp
            compact -> 20.dp
            else -> 22.dp
        }
    val repeatIcon =
        when (repeatMode) {
            Player.REPEAT_MODE_ONE -> R.drawable.repeat_one_on
            Player.REPEAT_MODE_ALL -> R.drawable.repeat_on
            else -> R.drawable.repeat
        }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = {
                playerConnection.player.shuffleModeEnabled = !shuffleModeEnabled
            },
            shape =
                RoundedCornerShape(
                    topStart = 50.dp,
                    bottomStart = 50.dp,
                    topEnd = 6.dp,
                    bottomEnd = 6.dp,
                ),
            color =
                if (shuffleModeEnabled) {
                    accent.copy(alpha = 0.18f)
                } else {
                    foreground.copy(alpha = 0.12f)
                },
            modifier = Modifier.size(buttonSize),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    painter = painterResource(R.drawable.shuffle),
                    contentDescription = stringResource(R.string.shuffle),
                    tint = if (shuffleModeEnabled) accent else foreground,
                    modifier = Modifier.size(iconSize),
                )
            }
        }

        Surface(
            onClick = { playerConnection.player.toggleRepeatMode() },
            shape =
                RoundedCornerShape(
                    topStart = 6.dp,
                    bottomStart = 6.dp,
                    topEnd = 50.dp,
                    bottomEnd = 50.dp,
                ),
            color =
                if (repeatMode != Player.REPEAT_MODE_OFF) {
                    accent.copy(alpha = 0.18f)
                } else {
                    foreground.copy(alpha = 0.12f)
                },
            modifier = Modifier.size(buttonSize),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    painter = painterResource(repeatIcon),
                    contentDescription = stringResource(R.string.repeat_mode_all),
                    tint = if (repeatMode != Player.REPEAT_MODE_OFF) accent else foreground,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}

@Composable
private fun V6LibraryLyricsRow(
    textBackgroundColor: Color,
    onLibraryClick: () -> Unit,
    onLyricsClick: () -> Unit,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    landscape: Boolean = false,
) {
    val buttonSize =
        when {
            landscape -> 44.dp
            compact -> 44.dp
            else -> 48.dp
        }
    val iconSize =
        when {
            landscape -> 22.dp
            compact -> 20.dp
            else -> 22.dp
        }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    horizontal = horizontalPadding,
                    vertical = if (compact) 4.dp else 8.dp,
                ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        V6LibraryLyricsPillButton(
            iconRes = R.drawable.library_outlined,
            label = stringResource(R.string.filter_library),
            textColor = textBackgroundColor,
            buttonSize = buttonSize,
            iconSize = iconSize,
            onClick = onLibraryClick,
            modifier = Modifier.weight(1f),
        )
        V6LibraryLyricsPillButton(
            iconRes = R.drawable.lyrics,
            label = stringResource(R.string.lyrics),
            textColor = textBackgroundColor,
            buttonSize = buttonSize,
            iconSize = iconSize,
            onClick = onLyricsClick,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun V6LibraryLyricsPillButton(
    iconRes: Int,
    label: String,
    textColor: Color,
    buttonSize: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .height(buttonSize)
                .clip(RoundedCornerShape(16.dp))
                .background(textColor.copy(alpha = 0.1f))
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = textColor,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                color = textColor,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PlayerLandscapeSecondaryActions(
    mediaMetadata: MediaMetadata,
    currentSongLiked: Boolean,
    shuffleModeEnabled: Boolean,
    repeatMode: Int,
    accent: Color,
    foreground: Color,
    playerConnection: PlayerConnection,
    onMenuClick: () -> Unit,
    onLyricsClick: (() -> Unit)? = null,
    onQueueClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val context = LocalContext.current
    val buttonSize = if (compact) 40.dp else 44.dp
    val iconSize = if (compact) 18.dp else 20.dp
    val repeatIcon =
        when (repeatMode) {
            Player.REPEAT_MODE_ONE -> R.drawable.repeat_one_on
            Player.REPEAT_MODE_ALL -> R.drawable.repeat_on
            else -> R.drawable.repeat
        }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WideLandscapeActionButton(
            iconRes = R.drawable.share,
            contentDescription = stringResource(R.string.share),
            foreground = foreground,
            buttonSize = buttonSize,
            iconSize = iconSize,
            onClick = {
                val intent =
                    Intent().apply {
                        action = Intent.ACTION_SEND
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "https://music.youtube.com/watch?v=${mediaMetadata.id}",
                        )
                    }
                context.startActivity(Intent.createChooser(intent, null))
            },
        )
        WideLandscapeActionButton(
            iconRes = if (currentSongLiked) R.drawable.favorite else R.drawable.favorite_border,
            contentDescription = stringResource(R.string.action_like),
            foreground = if (currentSongLiked) MaterialTheme.colorScheme.error else foreground,
            buttonSize = buttonSize,
            iconSize = iconSize,
            onClick = { playerConnection.toggleLike() },
        )
        onLyricsClick?.let { lyricsClick ->
            WideLandscapeActionButton(
                iconRes = R.drawable.lyrics,
                contentDescription = stringResource(R.string.lyrics),
                foreground = foreground,
                buttonSize = buttonSize,
                iconSize = iconSize,
                onClick = lyricsClick,
            )
        }
        onQueueClick?.let { queueClick ->
            WideLandscapeActionButton(
                iconRes = R.drawable.queue_music,
                contentDescription = stringResource(R.string.queue),
                foreground = foreground,
                buttonSize = buttonSize,
                iconSize = iconSize,
                onClick = queueClick,
            )
        }
        WideLandscapeActionButton(
            iconRes = R.drawable.more_vert,
            contentDescription = stringResource(R.string.more_options),
            foreground = foreground,
            buttonSize = buttonSize,
            iconSize = iconSize,
            onClick = onMenuClick,
        )
        WideLandscapeActionButton(
            iconRes = R.drawable.shuffle,
            contentDescription = stringResource(R.string.shuffle),
            foreground = if (shuffleModeEnabled) accent else foreground,
            buttonSize = buttonSize,
            iconSize = iconSize,
            onClick = {
                playerConnection.player.shuffleModeEnabled = !shuffleModeEnabled
            },
        )
        WideLandscapeActionButton(
            iconRes = repeatIcon,
            contentDescription = stringResource(R.string.repeat_mode_all),
            foreground = if (repeatMode != Player.REPEAT_MODE_OFF) accent else foreground,
            buttonSize = buttonSize,
            iconSize = iconSize,
            onClick = { playerConnection.player.toggleRepeatMode() },
        )
    }
}

@Composable
private fun WideLandscapeActionButton(
    iconRes: Int,
    contentDescription: String,
    foreground: Color,
    onClick: () -> Unit,
    buttonSize: Dp = 48.dp,
    iconSize: Dp = 22.dp,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = foreground.copy(alpha = 0.12f),
        modifier = Modifier.size(buttonSize),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                tint = foreground,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
private fun WideLandscapeInfoTile(
    iconRes: Int,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.08f),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.65f),
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.65f),
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
