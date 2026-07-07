/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.hush.music.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.hush.music.R
import app.hush.music.ui.theme.HushDesign
import app.hush.music.ui.theme.rememberHushAccentGradient
import app.hush.music.ui.theme.hushPressable
import app.hush.music.ui.utils.resolvePlaybackArtworkUrl
import app.hush.music.constants.EnableHapticFeedbackKey
import app.hush.music.constants.MiniPlayerArtworkInnerSize
import app.hush.music.constants.MiniPlayerArtworkOuterSize
import app.hush.music.constants.MiniPlayerHeight
import app.hush.music.constants.VisualizerEnabledKey
import app.hush.music.constants.VisualizerStyleKey
import app.hush.music.constants.VisualizerColorThemeKey
import app.hush.music.constants.VisualizerMiniPlayerKey
import app.hush.music.constants.VisualizerOpacityKey
import app.hush.music.extensions.togglePlayPause
import app.hush.music.models.MediaMetadata
import app.hush.music.playback.PlayerConnection
import app.hush.music.together.TogetherSessionState
import app.hush.music.utils.rememberPreference
import app.hush.music.utils.rememberEnumPreference
import app.hush.music.ui.player.visualizer.AudioSpectrumProvider
import app.hush.music.ui.player.visualizer.Visualizer
import app.hush.music.ui.player.visualizer.VisualizerStyle
import app.hush.music.ui.player.visualizer.VisualizerColorTheme
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

@Immutable
data class MiniPlayerContentColors(
    val title: Color,
    val secondary: Color,
    val progress: Color,
    val progressTrack: Color,
    val artworkContainer: Color,
    val artworkBorder: Color,
    val primaryButtonContainer: Color,
    val buttonBorder: Color,
    val buttonIcon: Color,
    val disabledButtonIcon: Color,
    val togetherContainer: Color,
    val togetherContent: Color,
)

@Composable
fun SwipeableMiniPlayerBox(
    modifier: Modifier = Modifier,
    swipeSensitivity: Float,
    swipeThumbnail: Boolean,
    playerConnection: PlayerConnection,
    layoutDirection: LayoutDirection,
    coroutineScope: CoroutineScope,
    pureBlack: Boolean = false,
    useLegacyBackground: Boolean = false,
    content: @Composable (Float) -> Unit,
) {
    val offsetXAnimatable = remember { Animatable(0f) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragStartTime by remember { mutableStateOf(0L) }
    var totalDragDistance by remember { mutableFloatStateOf(0f) }

    val view = LocalView.current
    val (enableHapticFeedback) = rememberPreference(EnableHapticFeedbackKey, true)

    val animationSpec =
        spring<Float>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow,
        )

    fun calculateAutoSwipeThreshold(swipeSensitivity: Float): Int =
        (600 / (1f + kotlin.math.exp(-(-11.44748 * swipeSensitivity + 9.04945)))).roundToInt()
    val autoSwipeThreshold = calculateAutoSwipeThreshold(swipeSensitivity)

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(MiniPlayerHeight)
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
                .let { baseModifier ->
                    if (useLegacyBackground) {
                        baseModifier.background(
                            if (pureBlack) {
                                Color.Black
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                        )
                    } else {
                        baseModifier.padding(horizontal = 12.dp)
                    }
                }.let { baseModifier ->
                    if (swipeThumbnail) {
                        baseModifier.pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    dragStartTime = System.currentTimeMillis()
                                    totalDragDistance = 0f
                                    dragOffsetX = 0f
                                },
                                onDragCancel = {
                                    dragOffsetX = 0f
                                    coroutineScope.launch {
                                        offsetXAnimatable.animateTo(
                                            targetValue = 0f,
                                            animationSpec = animationSpec,
                                        )
                                    }
                                },
                                onHorizontalDrag = { _, dragAmount ->
                                    val adjustedDragAmount =
                                        if (layoutDirection == LayoutDirection.Rtl) -dragAmount else dragAmount
                                    val canSkipPrevious = playerConnection.player.previousMediaItemIndex != -1
                                    val canSkipNext = playerConnection.player.nextMediaItemIndex != -1
                                    val allowLeft = adjustedDragAmount < 0 && canSkipNext
                                    val allowRight = adjustedDragAmount > 0 && canSkipPrevious
                                    if (allowLeft || allowRight) {
                                        totalDragDistance += kotlin.math.abs(adjustedDragAmount)
                                        dragOffsetX += adjustedDragAmount
                                    }
                                },
                                onDragEnd = {
                                    val dragDuration = System.currentTimeMillis() - dragStartTime
                                    val velocity = if (dragDuration > 0) totalDragDistance / dragDuration else 0f
                                    val currentOffset = offsetXAnimatable.value + dragOffsetX
                                    dragOffsetX = 0f

                                    val minDistanceThreshold = 50f
                                    val velocityThreshold = (swipeSensitivity * -8.25f) + 8.5f

                                    val shouldChangeSong =
                                        (
                                            kotlin.math.abs(currentOffset) > minDistanceThreshold &&
                                                velocity > velocityThreshold
                                        ) || (kotlin.math.abs(currentOffset) > autoSwipeThreshold)

                                    if (shouldChangeSong) {
                                        val isRightSwipe = currentOffset > 0
                                        val canSkipPrevious = playerConnection.player.previousMediaItemIndex != -1
                                        val canSkipNext = playerConnection.player.nextMediaItemIndex != -1

                                        if (isRightSwipe && canSkipPrevious) {
                                            if (enableHapticFeedback) {
                                                view.performHapticFeedback(
                                                    android.view.HapticFeedbackConstants.CONTEXT_CLICK,
                                                    android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
                                                )
                                            }
                                            playerConnection.seekToPrevious()
                                        } else if (!isRightSwipe && canSkipNext) {
                                            if (enableHapticFeedback) {
                                                view.performHapticFeedback(
                                                    android.view.HapticFeedbackConstants.CONTEXT_CLICK,
                                                    android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
                                                )
                                            }
                                            playerConnection.seekToNext()
                                        }
                                    }

                                    coroutineScope.launch {
                                        offsetXAnimatable.snapTo(currentOffset)
                                        offsetXAnimatable.animateTo(
                                            targetValue = 0f,
                                            animationSpec = animationSpec,
                                        )
                                    }
                                },
                            )
                        }
                    } else {
                        baseModifier
                    }
                },
    ) {
        content(offsetXAnimatable.value + dragOffsetX)

        val swipeOffset = offsetXAnimatable.value + dragOffsetX
        if (swipeOffset.absoluteValue > 50f) {
            Box(
                modifier =
                    Modifier
                        .align(if (swipeOffset > 0) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(horizontal = 16.dp),
            ) {
                Icon(
                    painter =
                        painterResource(
                            if (swipeOffset > 0) R.drawable.skip_previous else R.drawable.skip_next,
                        ),
                    contentDescription = null,
                    tint =
                        MaterialTheme.colorScheme.primary.copy(
                            alpha = (offsetXAnimatable.value.absoluteValue / autoSwipeThreshold).coerceIn(0f, 1f),
                        ),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
fun RowScope.MiniPlayerInfo(
    mediaMetadata: MediaMetadata,
    colors: MiniPlayerContentColors,
) {
    Column(
        modifier =
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        AnimatedContent(
            targetState = mediaMetadata.title,
            transitionSpec = {
                fadeIn(tween(180)) togetherWith fadeOut(tween(120))
            },
            label = "title",
        ) { title ->
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(),
            )
        }

        AnimatedContent(
            targetState = mediaMetadata.artists,
            transitionSpec = {
                fadeIn(tween(180)) togetherWith fadeOut(tween(120))
            },
            label = "artist",
        ) { artists ->
            Text(
                text = artists.map { it.name.trim() }.filter { it.isNotBlank() }.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(),
            )
        }
    }
}

@Composable
private fun MiniPlayerArtwork(
    isPlaying: Boolean,
    artworkUrl: String?,
    position: Long,
    duration: Long,
    isLoading: Boolean,
    colors: MiniPlayerContentColors,
    playerConnection: PlayerConnection,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(MiniPlayerArtworkOuterSize)
                .aspectRatio(1f),
    ) {
        if (isLoading) {
            CircularWavyProgressIndicator(
                modifier = Modifier.fillMaxSize(),
                color = colors.progress,
                trackColor = colors.progressTrack,
            )
        } else {
            CircularWavyProgressIndicator(
                progress = { if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxSize(),
                color = colors.progress,
                trackColor = colors.progressTrack,
            )
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(MiniPlayerArtworkInnerSize)
                    .clip(CircleShape)
                    .background(colors.artworkContainer)
                    .border(
                        width = 1.dp,
                        color = colors.artworkBorder,
                        shape = CircleShape,
                    ),
        ) {
            val (visualizerEnabled) = rememberPreference(VisualizerEnabledKey, true)
            val (visualizerMiniPlayer) = rememberPreference(VisualizerMiniPlayerKey, false)
            val (visualizerStyle) = rememberEnumPreference(VisualizerStyleKey, VisualizerStyle.BOTTOM_BARS)
            val (visualizerColorTheme) = rememberEnumPreference(VisualizerColorThemeKey, VisualizerColorTheme.THEME)
            val (visualizerOpacity) = rememberPreference(VisualizerOpacityKey, 0.8f)

            Crossfade(
                targetState = artworkUrl,
                animationSpec = tween(220),
                label = "miniPlayerArtwork",
            ) { url ->
                if (url != null) {
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .clip(CircleShape),
                    )
                } else {
                    Image(
                        painter = painterResource(R.drawable.hush_logo_mark),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            var spectrumData by remember { mutableStateOf<List<Float>?>(null) }
            val audioSessionId = remember(playerConnection) { playerConnection.player.audioSessionId }
            LaunchedEffect(audioSessionId, isPlaying) {
                if (!isPlaying || audioSessionId <= 0) {
                    spectrumData = null
                    return@LaunchedEffect
                }
                AudioSpectrumProvider.spectrumFlow(audioSessionId).collect { data ->
                    spectrumData = data
                }
            }
            DisposableEffect(Unit) {
                onDispose { AudioSpectrumProvider.release() }
            }

            if (visualizerEnabled && visualizerMiniPlayer) {
                Visualizer(
                    isPlaying = isPlaying,
                    style = visualizerStyle,
                    colorTheme = visualizerColorTheme,
                    opacity = visualizerOpacity,
                    modifier = Modifier.fillMaxSize(),
                    spectrumData = spectrumData,
                )
            }
        }
    }
}

@Composable
private fun MiniPlayerTransportButton(
    iconResId: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isPrimary: Boolean = false,
    colors: MiniPlayerContentColors,
) {
    val view = LocalView.current
    val (enableHapticFeedback) = rememberPreference(EnableHapticFeedbackKey, true)
    val accentGradient = rememberHushAccentGradient()

    LaunchedEffect(enableHapticFeedback) {
        view.isHapticFeedbackEnabled = enableHapticFeedback
    }

    val backgroundModifier =
        if (isPrimary) {
            Modifier.background(accentGradient, CircleShape)
        } else {
            Modifier.background(colors.primaryButtonContainer.copy(alpha = 0.12f), CircleShape)
        }
    val borderColor =
        if (enabled) {
            if (isPrimary) colors.buttonBorder.copy(alpha = 0.45f) else colors.buttonBorder
        } else {
            colors.buttonBorder.copy(alpha = 0.12f)
        }
    val iconTint =
        if (enabled) {
            if (isPrimary) MaterialTheme.colorScheme.onPrimary else colors.buttonIcon
        } else {
            colors.disabledButtonIcon
        }

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .then(modifier)
                .size(if (isPrimary) 40.dp else 36.dp)
                .clip(CircleShape)
                .then(backgroundModifier)
                .border(width = 0.5.dp, color = borderColor, shape = CircleShape)
                .hushPressable(
                    enabled = enabled,
                    pressScale = HushDesign.ChipPressScale,
                    onClick = {
                    if (enableHapticFeedback) {
                        view.performHapticFeedback(
                            android.view.HapticFeedbackConstants.CONTEXT_CLICK,
                            android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
                        )
                    }
                    onClick()
                },
                ),
    ) {
        Icon(
            painter = painterResource(iconResId),
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(if (isPrimary) 22.dp else 18.dp),
        )
    }
}

@Composable
private fun MiniPlayerTransportControls(
    isPlaying: Boolean,
    playbackState: Int,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    playerConnection: PlayerConnection,
    colors: MiniPlayerContentColors,
) {
    val haptic = LocalHapticFeedback.current

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniPlayerTransportButton(
            iconResId = R.drawable.skip_previous,
            contentDescription = null,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                playerConnection.seekToPrevious()
            },
            enabled = canSkipPrevious,
            colors = colors,
        )

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp),
        ) {
            MiniPlayerTransportButton(
                iconResId =
                    when {
                        playbackState == Player.STATE_ENDED -> R.drawable.replay
                        isPlaying -> R.drawable.pause
                        else -> R.drawable.play
                    },
                contentDescription =
                    stringResource(
                        if (playbackState == Player.STATE_ENDED || !isPlaying) R.string.play else R.string.widget_pause,
                    ),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (playbackState == Player.STATE_ENDED) {
                        playerConnection.player.seekTo(0, 0)
                        playerConnection.player.playWhenReady = true
                    } else {
                        playerConnection.player.togglePlayPause()
                    }
                },
                isPrimary = true,
                colors = colors,
            )
        }

        MiniPlayerTransportButton(
            iconResId = R.drawable.skip_next,
            contentDescription = null,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                playerConnection.seekToNext()
            },
            enabled = canSkipNext,
            colors = colors,
        )
    }
}

@Composable
fun NewMiniPlayerContent(
    position: Long,
    duration: Long,
    playerConnection: PlayerConnection,
    colors: MiniPlayerContentColors,
) {
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val playbackState by playerConnection.playbackState.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val togetherSessionState by playerConnection.service.togetherSessionState.collectAsStateWithLifecycle()
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsStateWithLifecycle()
    val canSkipNext by playerConnection.canSkipNext.collectAsStateWithLifecycle()

    val rawLoading = playbackState == Player.STATE_BUFFERING
    var isLoading by remember(mediaMetadata?.id) { mutableStateOf(rawLoading) }
    LaunchedEffect(rawLoading) {
        if (rawLoading) {
            isLoading = true
        } else {
            delay(250)
            isLoading = false
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        MiniPlayerArtwork(
            isPlaying = isPlaying,
            artworkUrl =
                mediaMetadata.resolvePlaybackArtworkUrl()
                    ?: playerConnection.player.currentMediaItem.resolvePlaybackArtworkUrl(),
            position = position,
            duration = duration,
            isLoading = isLoading,
            colors = colors,
            playerConnection = playerConnection,
        )

        Spacer(modifier = Modifier.width(5.dp))

        mediaMetadata?.let {
            MiniPlayerInfo(
                mediaMetadata = it,
                colors = colors,
            )
        } ?: Spacer(Modifier.weight(1f))

        if (togetherSessionState !is TogetherSessionState.Idle) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = colors.togetherContainer,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.all_inclusive),
                        contentDescription = stringResource(R.string.music_together),
                        tint = colors.togetherContent,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        MiniPlayerTransportControls(
            isPlaying = isPlaying,
            playbackState = playbackState,
            canSkipPrevious = canSkipPrevious,
            canSkipNext = canSkipNext,
            playerConnection = playerConnection,
            colors = colors,
        )
    }
}
