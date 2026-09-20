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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import app.hush.music.ui.component.hushMarquee
import app.hush.music.R
import app.hush.music.ui.theme.HushDesign
import app.hush.music.ui.theme.rememberHushAccentGradient
import app.hush.music.ui.theme.hushPressable
import app.hush.music.ui.utils.resolvePlaybackArtworkUrl
import app.hush.music.constants.EnableHapticFeedbackKey
import app.hush.music.constants.MiniPlayerArtworkInnerSize
import app.hush.music.constants.MiniPlayerArtworkOuterSize
import app.hush.music.constants.MiniPlayerHeight
import app.hush.music.constants.PulseMatrixEnabledDefault
import app.hush.music.constants.PulseMatrixEnabledKey
import app.hush.music.constants.PulseMatrixThemeKey
import app.hush.music.constants.PulseMatrixMiniPlayerKey
import app.hush.music.constants.PulseMatrixIntensityKey
import app.hush.music.constants.PulseMatrixPeakHoldKey
import app.hush.music.extensions.togglePlayPause
import app.hush.music.models.MediaMetadata
import app.hush.music.playback.PlayerConnection
import app.hush.music.together.TogetherSessionState
import app.hush.music.utils.rememberPreference
import app.hush.music.utils.rememberEnumPreference
import app.hush.music.utils.fetchFraction
import app.hush.music.utils.isFetchingTrack
import app.hush.music.ui.component.HushProgressSpinner
import app.hush.music.ui.player.visualizer.PulseMatrixCanvas
import app.hush.music.ui.player.visualizer.PulseMatrixEngine
import app.hush.music.ui.player.visualizer.PulseMatrixSettings
import app.hush.music.ui.player.visualizer.PulseMatrixConsumerToken
import app.hush.music.ui.player.visualizer.PulseMatrixDefaultTheme
import app.hush.music.ui.player.visualizer.PulseMatrixTheme
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
    /**
     * Fill behind the previous/next buttons.
     *
     * The play/pause button is not in here: it is filled with the accent gradient, so this
     * only ever paints a *secondary* control.
     */
    val secondaryButtonContainer: Color,
    /**
     * Icon tint for those buttons.
     *
     * It has to contrast with [secondaryButtonContainer], which is a faint tint rather than a
     * filled accent circle - see [MiniPlayerTransportButton].
     */
    val secondaryButtonIcon: Color,
    val buttonBorder: Color,
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
                modifier = Modifier.hushMarquee(),
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
                modifier = Modifier.hushMarquee(),
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
    downloadProgress: Float? = null,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(MiniPlayerArtworkOuterSize)
                .aspectRatio(1f),
    ) {
        if (isLoading) {
            // The ring around the artwork turns on its own rather than relying on the platform to
            // animate it, which a device with animations off does not do.
            HushProgressSpinner(
                modifier = Modifier.fillMaxSize(),
                color = colors.progress,
            )
        } else {
            // While a SpotiFLAC track is still being fetched the ring shows that
            // download's progress instead of a playback position stuck at zero.
            CircularWavyProgressIndicator(
                progress = {
                    downloadProgress
                        ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                },
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
            val (pulseMatrixEnabled) = rememberPreference(PulseMatrixEnabledKey, PulseMatrixEnabledDefault)
            val (pulseMatrixThemeStr) = rememberPreference(PulseMatrixThemeKey, PulseMatrixDefaultTheme.name)
            val pulseMatrixTheme = PulseMatrixTheme.valueOf(pulseMatrixThemeStr)
            val (pulseMatrixMiniPlayer) = rememberPreference(PulseMatrixMiniPlayerKey, true)
            val (pulseMatrixIntensityStr) = rememberPreference(PulseMatrixIntensityKey, PulseMatrixSettings.IntensityLevel.NORMAL.name)
            val pulseMatrixIntensity = PulseMatrixSettings.IntensityLevel.valueOf(pulseMatrixIntensityStr)
            val (pulseMatrixPeakHold) = rememberPreference(PulseMatrixPeakHoldKey, true)

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

            if (pulseMatrixEnabled && pulseMatrixMiniPlayer) {
                LaunchedEffect(Unit) {
                    // A token, not a counter: this effect releases only its own
                    // registration, so overlapping with the full player during a transition
                    // cannot stop the visualiser the other screen is still showing.
                    var token: PulseMatrixConsumerToken? = null
                    var lastSid = 0
                    try {
                        while (true) {
                            val sid = try {
                                playerConnection.player.audioSessionId
                            } catch (_: Exception) { 0 }
                            if (sid > 0 && sid != lastSid) {
                                if (token == null) {
                                    token = PulseMatrixEngine.acquire(sid)
                                    lastSid = if (token != null) sid else 0
                                } else {
                                    PulseMatrixEngine.changeSession(sid)
                                    lastSid = sid
                                }
                            }
                            delay(100)
                        }
                    } finally {
                        token?.release()
                    }
                }

                val visualizerHeight = MiniPlayerArtworkInnerSize * 0.35f
                val bands by PulseMatrixEngine.barHeights.collectAsState()
                PulseMatrixCanvas(
                    theme = pulseMatrixTheme,
                    opacity = 0.8f,
                    modifier = Modifier
                        .width(MiniPlayerArtworkInnerSize * 0.60f)
                        .height(visualizerHeight)
                        .align(Alignment.BottomCenter),
                    bands = bands,
                    miniMode = true,
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
    /**
     * Draw a spinner instead of the icon while this track is still being prepared.
     *
     * Only the play/pause button uses this. It is the button a user presses when nothing is
     * happening yet, so it is the one that has to say "working on it" rather than showing a
     * play glyph for a track whose audio has not arrived - which reads as a dead button, and
     * was reported as exactly that while a SpotiFLAC track was downloading.
     */
    isLoading: Boolean = false,
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
            // A disabled secondary button fades its own container towards the surface, so the
            // two states differ in the fill as well as the glyph - otherwise "off" and "on"
            // were one shade apart and neither was readable.
            val container =
                if (enabled) {
                    colors.secondaryButtonContainer
                } else {
                    colors.secondaryButtonContainer.copy(alpha = colors.secondaryButtonContainer.alpha * 0.4f)
                }
            Modifier.background(container, CircleShape)
        }
    val borderColor =
        if (enabled) {
            if (isPrimary) colors.buttonBorder.copy(alpha = 0.45f) else colors.buttonBorder
        } else {
            colors.buttonBorder.copy(alpha = 0.12f)
        }
    val iconTint =
        when {
            !enabled -> colors.disabledButtonIcon
            isPrimary -> MaterialTheme.colorScheme.onPrimary
            else -> colors.secondaryButtonIcon
        }

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .then(modifier)
                .size(if (isPrimary) 40.dp else 36.dp)
                // Press motion first: the ring is drawn in this button's own layer, and these controls
                // are 40dp inside a 48dp touch target, so the halo's own outer edge (1.30x a 40dp box)
                // is the only part of it that shows past the fill. Keep this above the clip.
                .hushPressable(
                    enabled = enabled,
                    pressScale = HushDesign.ChipPressScale,
                    haloColor = iconTint,
                    onClick = {
                    if (enableHapticFeedback) {
                        view.performHapticFeedback(
                            android.view.HapticFeedbackConstants.CONTEXT_CLICK,
                            android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
                        )
                    }
                    onClick()
                },
                )
                .clip(CircleShape)
                .then(backgroundModifier)
                .border(width = 0.5.dp, color = borderColor, shape = CircleShape),
    ) {
        if (isLoading) {
            // The full player's indicator, so the bar and the sheet cannot disagree: determinate
            // when the runtime reports bytes moving, and turning on its own when it does not. The
            // standard indeterminate indicator was frozen here on a device whose animation scale is
            // off, which is what a play/pause button "stuck" for a whole download looks like.
            FetchingIndicator(
                modifier = Modifier.size(if (isPrimary) 22.dp else 18.dp),
                color = iconTint,
            )
        } else {
            Icon(
                painter = painterResource(iconResId),
                contentDescription = contentDescription,
                tint = iconTint,
                modifier = Modifier.size(if (isPrimary) 22.dp else 18.dp),
            )
        }
    }
}

@Composable
private fun MiniPlayerTransportControls(
    isPlaying: Boolean,
    playbackState: Int,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    isLoading: Boolean,
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
                isLoading = isLoading,
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
    val downloadProgress by playerConnection.activeDownloadProgress.collectAsStateWithLifecycle()
    // The full player's rule, "not while it is playing" included: a fetch signal that outlives the
    // audio would leave the bar drawing a download ring over a song that is already playing.
    val activeDownloadFraction = downloadProgress.fetchFraction(mediaMetadata?.id, isPlaying)

    // The same rule the full player uses: a SpotiFLAC track is fetched before media3 has
    // anything to buffer, so buffering alone leaves the transport looking idle during a fetch.
    val rawLoading =
        playbackState == Player.STATE_BUFFERING ||
            (!isPlaying && downloadProgress.isFetchingTrack(mediaMetadata?.id))
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
            downloadProgress = activeDownloadFraction,
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

        // The fetch fraction the sheet already reads, handed to the bar's button: a real percentage
        // is the only kind of progress that survives a device with its animations turned off, and
        // the source sweep that has no percentage gets the turning arc instead.
        CompositionLocalProvider(
            LocalPlayerFetchFraction provides activeDownloadFraction,
        ) {
            MiniPlayerTransportControls(
                isPlaying = isPlaying,
                playbackState = playbackState,
                canSkipPrevious = canSkipPrevious,
                canSkipNext = canSkipNext,
                isLoading = isLoading,
                playerConnection = playerConnection,
                colors = colors,
            )
        }
    }
}
