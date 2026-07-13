/*
 * ArchiveTune (2026)
 * GPL-3.0 License
 */

package app.hush.music.ui.player

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.navigation.NavController
import app.hush.music.constants.PlayerDesignStyle
import app.hush.music.constants.PlayerHorizontalPadding
import app.hush.music.constants.SliderStyle
import app.hush.music.constants.CarExpressiveAutoHideTitleKey
import app.hush.music.constants.CarExpressiveTitleHideDelayKey
import app.hush.music.models.MediaMetadata
import app.hush.music.playback.PlayerConnection
import app.hush.music.ui.component.BottomSheetPageState
import app.hush.music.ui.component.BottomSheetState
import app.hush.music.ui.component.MenuState
import app.hush.music.utils.makeTimeString
import app.hush.music.utils.rememberPreference
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Dedicated V6 car presentation. It reuses playback, visualizer, and control components while
 * keeping all car-only geometry out of the normal Expressive player.
 */
@Composable
internal fun CarExpressivePlayerContent(
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
    sleepTimerEnabled: Boolean,
    sleepTimerTimeLeft: Long,
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    onExpandQueue: () -> Unit,
    onSleepTimerClick: () -> Unit,
    onShowLyrics: () -> Unit,
    artworkContent: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentSong by playerConnection.currentSong.collectAsState(initial = null)
    val currentSongLiked = currentSong?.song?.liked == true
    val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsState()
    val (autoHideTitle) = rememberPreference(CarExpressiveAutoHideTitleKey, defaultValue = false)
    val (titleHideDelaySeconds) =
        rememberPreference(CarExpressiveTitleHideDelayKey, defaultValue = 5)
    var titleResetToken by remember(mediaMetadata.id) { mutableIntStateOf(0) }
    val playPauseRoundness by animateDpAsState(
        targetValue = if (isPlaying) 24.dp else 36.dp,
        animationSpec = tween(durationMillis = 90, easing = LinearEasing),
        label = "carExpressivePlayPauseRoundness",
    )

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .pointerInput(autoHideTitle) {
                    awaitEachGesture {
                        awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Final,
                        )
                        if (autoHideTitle) titleResetToken++
                        waitForUpOrCancellation()
                    }
                },
    ) {
        // Reserve more of the display for the cinematic canvas; the existing controls then sit
        // closer to the bottom edge without changing their size or behavior.
        val artworkHeight = maxHeight * 0.52f

        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(artworkHeight),
            ) {
                artworkContent()

                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(104.dp)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent),
                                ),
                            ),
                )

                CarArtworkTitleOverlay(
                    mediaMetadata = mediaMetadata,
                    textBackgroundColor = textBackgroundColor,
                    navController = navController,
                    state = state,
                    autoHide = autoHideTitle,
                    hideDelaySeconds = titleHideDelaySeconds,
                    resetToken = titleResetToken,
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                )
            }

            CarProgressRow(
                sliderStyle = sliderStyle,
                sliderPosition = sliderPosition,
                position = position,
                duration = duration,
                isPlaying = isPlaying,
                textButtonColor = textButtonColor,
                textBackgroundColor = textBackgroundColor,
                onSliderValueChange = onSliderValueChange,
                onSliderValueChangeFinished = onSliderValueChangeFinished,
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
                landscape = false,
                landscapeCompact = false,
            )

            V6PortraitSingleActionRow(
                mediaMetadata = mediaMetadata,
                textBackgroundColor = textBackgroundColor,
                currentSongLiked = currentSongLiked,
                shuffleModeEnabled = shuffleModeEnabled,
                repeatMode = repeatMode,
                accent = MaterialTheme.colorScheme.primary,
                playerConnection = playerConnection,
                navController = navController,
                menuState = menuState,
                state = state,
                bottomSheetPageState = bottomSheetPageState,
                context = context,
                onExpandQueue = onExpandQueue,
                sleepTimerEnabled = sleepTimerEnabled,
                onSleepTimerClick = onSleepTimerClick,
                onShowLyrics = onShowLyrics,
                buttonSize = 48.dp,
                iconSize = 22.dp,
                buttonSpacing = 4.dp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun CarArtworkTitleOverlay(
    mediaMetadata: MediaMetadata,
    textBackgroundColor: Color,
    navController: NavController,
    state: BottomSheetState,
    autoHide: Boolean,
    hideDelaySeconds: Int,
    resetToken: Int,
    modifier: Modifier = Modifier,
) {
    val actions = rememberPlayerTitleActions(mediaMetadata, navController, state)
    val titleStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val artistStyle = MaterialTheme.typography.titleMedium.copy(color = textBackgroundColor)
    var visible by remember(mediaMetadata.id) { mutableStateOf(true) }
    val titleScroll = remember(mediaMetadata.id) { Animatable(0f) }

    BoxWithConstraints(modifier = modifier) {
        val availableWidthPx = with(density) { maxWidth.toPx() }
        val titleWidthPx =
            remember(mediaMetadata.title, titleStyle) {
                textMeasurer
                    .measure(
                        text = AnnotatedString(mediaMetadata.title),
                        style = titleStyle,
                        maxLines = 1,
                        softWrap = false,
                    ).size.width
                    .toFloat()
            }
        val marqueeDistancePx = (titleWidthPx - availableWidthPx).coerceAtLeast(0f)
        val shouldMarquee = marqueeDistancePx > 0f
        val hasArtwork = !mediaMetadata.thumbnailUrl.isNullOrBlank()
        val marqueeDurationMillis =
            if (shouldMarquee) {
                // 30dp/s matches Compose's default marquee pace closely enough to wait for its full pass.
                ((marqueeDistancePx / with(density) { 30.dp.toPx() }) * 1_000f).roundToInt().toLong()
            } else {
                0L
            }

        LaunchedEffect(
            mediaMetadata.id,
            autoHide,
            hideDelaySeconds,
            shouldMarquee,
            marqueeDistancePx,
            resetToken,
            hasArtwork,
        ) {
            visible = true
            titleScroll.snapTo(0f)
            if (autoHide && hasArtwork) {
                if (shouldMarquee) {
                    delay(800)
                    titleScroll.animateTo(
                        marqueeDistancePx,
                        animationSpec = tween(marqueeDurationMillis.toInt(), easing = LinearEasing),
                    )
                }
                delay(hideDelaySeconds.coerceIn(1, 60) * 1_000L)
                visible = false
            }
        }

        AnimatedVisibility(visible = visible) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(32.dp)
                            .clipToBounds(),
                    contentAlignment = if (shouldMarquee) Alignment.CenterStart else Alignment.Center,
                ) {
                    Text(
                        text = mediaMetadata.title,
                        style = titleStyle,
                        color = textBackgroundColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = if (shouldMarquee) TextOverflow.Clip else TextOverflow.Ellipsis,
                        modifier =
                            Modifier
                                .then(
                                    if (shouldMarquee) {
                                        Modifier
                                            .width(with(density) { titleWidthPx.toDp() })
                                            .offset { IntOffset(-titleScroll.value.roundToInt(), 0) }
                                    } else {
                                        Modifier
                                    },
                                ).combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = actions.onTitleClick,
                                    onLongClick = actions.onCopyTitle,
                                ),
                    )
                }

                ClickableArtists(
                    artists = mediaMetadata.artists,
                    onArtistClick = actions.onArtistClick,
                    style = artistStyle,
                    onLongClick = actions.onCopyArtists,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun CarProgressRow(
    sliderStyle: SliderStyle,
    sliderPosition: Long?,
    position: Long,
    duration: Long,
    isPlaying: Boolean,
    textButtonColor: Color,
    textBackgroundColor: Color,
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
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
        )
        Spacer(Modifier.width(8.dp))
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
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (duration == C.TIME_UNSET) "" else makeTimeString(duration),
            style = MaterialTheme.typography.labelMedium,
            color = textBackgroundColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
