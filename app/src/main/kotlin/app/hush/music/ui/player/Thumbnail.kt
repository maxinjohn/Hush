/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.player

import android.graphics.Bitmap
import timber.log.Timber
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import app.hush.music.ui.component.hushMarquee
import app.hush.music.BuildConfig
import app.hush.music.LocalAnimationsDisabled
import app.hush.music.LocalPlayerConnection
import app.hush.music.R
import app.hush.music.canvas.models.CanvasArtwork
import app.hush.music.ui.player.visualizer.AudioSpectrumProvider
import app.hush.music.ui.player.visualizer.PulseMatrixCanvas
import app.hush.music.ui.player.visualizer.PulseMatrixEngine
import app.hush.music.ui.player.visualizer.PulseMatrixSettings
import app.hush.music.ui.player.visualizer.PulseMatrixConsumerToken
import app.hush.music.ui.player.visualizer.PulseMatrixDefaultTheme
import app.hush.music.ui.player.visualizer.PulseMatrixTheme
import app.hush.music.constants.PulseMatrixEnabledDefault
import app.hush.music.constants.PulseMatrixEnabledKey
import app.hush.music.constants.PulseMatrixThemeKey
import app.hush.music.constants.PulseMatrixIntensityKey
import app.hush.music.constants.PulseMatrixPeakHoldKey
import app.hush.music.constants.HushCanvasKey
import app.hush.music.constants.BackdropBlurAmountKey
import app.hush.music.constants.BackdropEnabledKey
import app.hush.music.constants.CanvasSource
import app.hush.music.constants.CanvasSourceKey
import app.hush.music.constants.CanvasWifiOnlyKey
import app.hush.music.constants.CropThumbnailToSquareKey
import app.hush.music.constants.DisableBlurKey
import app.hush.music.constants.EnableHapticFeedbackKey
import app.hush.music.constants.HidePlayerThumbnailKey
import app.hush.music.constants.MaxCanvasCacheSizeKey
import app.hush.music.constants.PlayerBackgroundStyle
import app.hush.music.constants.PlayerBackgroundStyleKey
import app.hush.music.constants.PlayerDesignStyle
import app.hush.music.constants.rememberPlayerDesignStyle
import app.hush.music.constants.PlayerHorizontalPadding
import app.hush.music.constants.SeekExtraSeconds
import app.hush.music.constants.SwipeThumbnailKey
import app.hush.music.constants.ThumbnailCornerRadiusKey
import app.hush.music.extensions.metadata
import app.hush.music.extensions.toMediaItem
import app.hush.music.ui.utils.highRes
import app.hush.music.utils.ImageBlurUtils
import app.hush.music.utils.rememberEnumPreference
import app.hush.music.utils.rememberLowDataModeActive
import app.hush.music.utils.rememberNetworkConnectivity
import app.hush.music.utils.rememberPreference
import java.util.Locale
import kotlin.math.abs

private data class ThumbnailPage(
    val slotKey: String,
    val windowIndex: Int,
    val mediaItem: MediaItem,
)

/**
 * What a swipe is turning in: the neighbour's cover, its arrival identity, and the track it belongs
 * to - fixed when the direction is decided, because the hand-over moves the queue underneath it.
 */
private data class SwipeTurnIn(
    val coverUrl: String?,
    val trackKey: Any?,
    val mediaId: String?,
)

/** Where a swipe explains itself: a drag that went over and had nothing to hand to. */
private const val SwipeLogTag = "Swipe"

/** The cover a swipe turns in for [item], asked for the way a page asks for its own. */
private fun swipeTurnInOf(item: MediaItem?): SwipeTurnIn? =
    item?.let {
        SwipeTurnIn(
            coverUrl = it.metadata?.thumbnailUrl?.highRes() ?: it.mediaMetadata.artworkUri?.toString(),
            trackKey =
                trackIdentityOf(
                    mediaId = it.mediaId,
                    artworkUri = it.mediaMetadata.artworkUri?.toString(),
                ),
            mediaId = it.mediaId,
        )
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Thumbnail(
    sliderPositionProvider: () -> Long?,
    modifier: Modifier = Modifier,
    isPlayerExpanded: Boolean = true, // Add parameter to control swipe based on player state
    showNowPlayingHeader: Boolean = true,
    edgeToEdge: Boolean = false,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val context = LocalContext.current

    // States
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()

    // Which way the queue is travelling, so the artwork, the title and the artists all move the
    // same way when the track changes. The instance is shared - see [LocalTrackChangeDirection].
    val trackChangeDirection = LocalTrackChangeDirection.current
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val error by playerConnection.error.collectAsStateWithLifecycle()
    // Whether the failure is the one the *artwork* must not cover.
    //
    // "No enabled source has this track" arrives as a playback error, and this panel is where every
    // playback error used to land - a red card over the cover, reading like the audio was broken.
    // Nothing is broken: the walk asked every source the user enabled and none had the track, which
    // the codec line states under the controls, next to the sources it asked. So this error is drawn
    // there and the cover is left alone. Everything else - a decoder failure, a refused session, an
    // HTTP error, a stream that never started - keeps the panel, because for those the panel *is* the
    // explanation, with its retry and its details.
    val sourceFetchFailed =
        remember(error) {
            error?.toPlaybackErrorInfo()?.kind == PlaybackErrorKind.NoSourceAvailable
        }
    val queueTitle by playerConnection.queueTitle.collectAsStateWithLifecycle()

    val swipeThumbnail by rememberPreference(SwipeThumbnailKey, true)

    val view = LocalView.current
    val (enableHapticFeedback) = rememberPreference(EnableHapticFeedbackKey, true)

    val hidePlayerThumbnail by rememberPreference(HidePlayerThumbnailKey, false)
    val hushCanvasEnabled by rememberPreference(HushCanvasKey, false)
    val canvasSource by rememberEnumPreference(CanvasSourceKey, CanvasSource.AUTO)
    val lowDataModeActive = rememberLowDataModeActive()
    val canvasWifiOnly by rememberPreference(CanvasWifiOnlyKey, false)
    val networkConnectivity = rememberNetworkConnectivity()
    val playerDesignStyle = rememberPlayerDesignStyle()
    val (maxCanvasCacheSize, _) =
        rememberPreference(
            key = MaxCanvasCacheSizeKey,
            defaultValue = 256,
        )
    val (thumbnailCornerRadius, _) =
        rememberPreference(
            key = ThumbnailCornerRadiusKey,
            defaultValue = 16f,
        )
    val cropThumbnailToSquare by rememberPreference(CropThumbnailToSquareKey, false)
    // Whether to draw the track change over the artwork; see [PlayerDiagnosticsOverlay]. A debug
    // build only: the overlay is what these changes are diagnosed with, and a build that ships has
    // no reason to carry it - or the writes behind it.
    val trackChangeDiagnosticsEnabled by rememberPreference(PlayerDiagnosticsEnabledKey, false)
    val showTrackChangeDiagnostics = BuildConfig.DEBUG && trackChangeDiagnosticsEnabled
    val (pulseMatrixEnabled) = rememberPreference(PulseMatrixEnabledKey, PulseMatrixEnabledDefault)
    val (pulseMatrixThemeStr) = rememberPreference(PulseMatrixThemeKey, PulseMatrixDefaultTheme.name)
    val pulseMatrixTheme = PulseMatrixTheme.valueOf(pulseMatrixThemeStr)
    val (pulseMatrixIntensityStr) = rememberPreference(PulseMatrixIntensityKey, PulseMatrixSettings.IntensityLevel.NORMAL.name)
    val pulseMatrixIntensity = PulseMatrixSettings.IntensityLevel.valueOf(pulseMatrixIntensityStr)
    val (pulseMatrixPeakHold) = rememberPreference(PulseMatrixPeakHoldKey, true)

    // Real-time spectrum data for all visualizer styles
    var spectrumData by remember { mutableStateOf<List<Float>?>(null) }

    // Sync PulseMatrixSettings with preferences
    LaunchedEffect(pulseMatrixIntensity) {
        PulseMatrixSettings.setIntensityLevel(pulseMatrixIntensity)
    }
    LaunchedEffect(pulseMatrixPeakHold) {
        PulseMatrixSettings.setPeakHoldEnabled(pulseMatrixPeakHold)
    }

    // === PulseMatrix: engine lifecycle ===
    // LaunchedEffect(Unit) survives preference flicker. Check enabled inside the loop.
    LaunchedEffect(Unit) {
        Timber.d("PulseMatrixThumb: Lifecycle LaunchedEffect started")
        // The engine hands back a token rather than counting us, so this screen's release
        // can only ever end this screen's registration. Without that, the mini player
        // appearing before this one was disposed could have its registration cancelled by
        // this screen's release — the visualiser then stopped seconds after minimising.
        var token: PulseMatrixConsumerToken? = null
        var lastSid = 0
        try {
            while (true) {
                if (pulseMatrixEnabled) {
                    val sid = try {
                        playerConnection.player.audioSessionId
                    } catch (_: Exception) { 0 }
                    if (sid > 0 && sid != lastSid) {
                        Timber.d("PulseMatrixThumb: Polling sid=$sid holding=${token != null} lastSid=$lastSid")
                        if (token == null) {
                            token = PulseMatrixEngine.acquire(sid)
                            lastSid = if (token != null) sid else 0
                        } else {
                            PulseMatrixEngine.changeSession(sid)
                            lastSid = sid
                        }
                    }
                } else if (token != null) {
                    token?.release()
                    token = null
                    lastSid = 0
                }
                delay(100)
            }
        } finally {
            token?.let {
                Timber.d("PulseMatrixThumb: Lifecycle LaunchedEffect ended — releasing")
                it.release()
            }
        }
    }

    // === AudioSpectrum fallback (when PulseMatrix disabled) ===
    LaunchedEffect(pulseMatrixEnabled) {
        if (pulseMatrixEnabled) return@LaunchedEffect
        val sid = playerConnection.audioSessionId.value.takeIf { it > 0 }
            ?: playerConnection.player.audioSessionId.takeIf { it > 0 }
        if (sid != null) {
            AudioSpectrumProvider.spectrumFlow(sid).collect { data ->
                spectrumData = data
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            AudioSpectrumProvider.release()
        }
    }
    val (disableBlur) = rememberPreference(DisableBlurKey, false)
    val (backdropEnabled) = rememberPreference(BackdropEnabledKey, defaultValue = true)
    val (backdropBlurAmount) = rememberPreference(BackdropBlurAmountKey, defaultValue = 60)
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsStateWithLifecycle()
    val canSkipNext by playerConnection.canSkipNext.collectAsStateWithLifecycle()

    // Player background style for consistent theming
    val playerBackground by rememberEnumPreference(
        key = PlayerBackgroundStyleKey,
        defaultValue = PlayerBackgroundStyle.DEFAULT,
    )

    val textBackgroundColor =
        when (playerBackground) {
            PlayerBackgroundStyle.DEFAULT -> MaterialTheme.colorScheme.onBackground
            PlayerBackgroundStyle.BLUR -> Color.White
            PlayerBackgroundStyle.GRADIENT -> Color.White
            PlayerBackgroundStyle.COLORING -> Color.White
            PlayerBackgroundStyle.BLUR_GRADIENT -> Color.White
            PlayerBackgroundStyle.GLOW -> Color.White
            PlayerBackgroundStyle.GLOW_ANIMATED -> Color.White
            PlayerBackgroundStyle.CUSTOM -> Color.White
        }

    LaunchedEffect(maxCanvasCacheSize) {
        CanvasArtworkPlaybackCache.setMaxSize(maxCanvasCacheSize)
    }

    // Keep the shared policy in sync so canvas video downloads already running in the
    // background stop as soon as Wi-Fi-only starts refusing the current network.
    LaunchedEffect(canvasWifiOnly, networkConnectivity) {
        CanvasNetworkPolicy.update(
            wifiOnly = canvasWifiOnly,
            wifiActive = networkConnectivity.wifi,
        )
        if (!CanvasNetworkPolicy.allowed) {
            CanvasArtworkPlaybackCache.cancelDownloads()
        }
    }

    // Grid state
    val thumbnailLazyGridState = rememberLazyGridState()

    // Whether the platform is animating at all - the system's animator duration scale, which is what
    // Compose's own scroll animations follow - and not Hush's motion preference. The page alignment
    // below is the one place the difference matters; see there.
    val gridAnimationsOff =
        remember(context) {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }

    // What each page remembers across its arrivals, held here rather than in the page.
    //
    // A page lives inside a lazy grid item and the grid recycles those, and a swipe - the gesture
    // this screen exists for - is what makes it recycle. Remembered inside the page, that state was
    // thrown away on exactly the change it was there for: the arriving page turned out to be a page
    // that had never arrived before, so a swipe faded where a tap turns, and it had no cover under
    // it to arrive over. See [TrackArtworkMemory].
    val artworkMemories = remember { TrackArtworkMemories() }

    // The state the readout draws, one instance shared by the three composables a track change is
    // made of - this screen, the page that turns inside the grid, and the canvas over it; see
    // [PlayerTrackChangeDiagnostics]. Written here because this is where the switch is read, and a
    // plain field so that flipping it does not invalidate the screen that draws it.
    val playerDiagnostics = remember { PlayerTrackChangeDiagnostics() }
    playerDiagnostics.enabled = showTrackChangeDiagnostics

    // Create a playlist using correct shuffle-aware logic
    val timeline = playerConnection.player.currentTimeline
    val currentIndex = playerConnection.player.currentMediaItemIndex
    val shuffleModeEnabled = playerConnection.player.shuffleModeEnabled
    val previousWindowIndex =
        if (swipeThumbnail && !timeline.isEmpty) {
            timeline.getPreviousWindowIndex(
                currentIndex,
                Player.REPEAT_MODE_OFF,
                shuffleModeEnabled,
            )
        } else {
            C.INDEX_UNSET
        }
    val previousMediaMetadata =
        if (previousWindowIndex != C.INDEX_UNSET) {
            try {
                playerConnection.player.getMediaItemAt(previousWindowIndex)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

    val nextWindowIndex =
        if (swipeThumbnail && !timeline.isEmpty) {
            timeline.getNextWindowIndex(
                currentIndex,
                Player.REPEAT_MODE_OFF,
                shuffleModeEnabled,
            )
        } else {
            C.INDEX_UNSET
        }
    val nextMediaMetadata =
        if (nextWindowIndex != C.INDEX_UNSET) {
            try {
                playerConnection.player.getMediaItemAt(nextWindowIndex)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

    val currentMediaItem =
        remember(mediaMetadata) {
            // Fallback to player's current item if mediaMetadata is null,
            // but prefer mediaMetadata for immediate updates during crossfade.
            val metadata = mediaMetadata
            if (metadata != null) {
                // Use extension to convert metadata to a proper MediaItem with all fields (uri, artwork, tag)
                metadata.toMediaItem()
            } else {
                try {
                    playerConnection.player.currentMediaItem
                } catch (e: Exception) {
                    null
                }
            }
        }

    val thumbnailPages =
        buildList {
            if (previousMediaMetadata != null) {
                add(ThumbnailPage(slotKey = "previous", windowIndex = previousWindowIndex, mediaItem = previousMediaMetadata))
            }
            if (currentMediaItem != null) {
                add(ThumbnailPage(slotKey = "current", windowIndex = currentIndex, mediaItem = currentMediaItem))
            }
            if (nextMediaMetadata != null) {
                add(ThumbnailPage(slotKey = "next", windowIndex = nextWindowIndex, mediaItem = nextMediaMetadata))
            }
        }
    val currentMediaIndex = thumbnailPages.indexOfFirst { it.slotKey == "current" }

    // OuterTune Snap behavior
    val horizontalLazyGridItemWidthFactor = 1f
    val thumbnailSnapLayoutInfoProvider =
        remember(thumbnailLazyGridState) {
            SnapLayoutInfoProvider(
                lazyGridState = thumbnailLazyGridState,
                positionInLayout = { layoutSize, itemSize ->
                    (layoutSize * horizontalLazyGridItemWidthFactor / 2f - itemSize / 2f)
                },
                velocityThreshold = 500f,
            )
        }

    // The swipe, which is a page turn and nothing else.
    //
    // There used to be two motions for one gesture. The grid scrolled a neighbour in under the
    // finger, a fling had to settle before the track could be changed at all, the grid was carried
    // back to the playing page, and only then did the turn play - on a page that had been off screen
    // while the song, its cover and its title had already changed. So the change arrived first and
    // the flip arrived after it, as a second animation, for one gesture. Now the finger drives the
    // turn: the neighbour's cover comes over exactly as far as the page is dragged, and the track is
    // handed over as the page goes over, so the song changes *during* the flip. See
    // [heldTurnProgress], [swipeHandsOver] and [TrackArtworkMemory.handover].
    val swipeHeldTarget = remember { mutableFloatStateOf(HELD_TURN) }
    var swipeDragging by remember { mutableStateOf(false) }
    var swipeDirection by remember { mutableIntStateOf(0) }
    var swipeHandedOver by remember { mutableStateOf(false) }
    // Whether a *finger* is on the page right now. The button turn below moves the same state a drag
    // does, so it needs to know the difference: one is a script it can finish and put away, the other
    // belongs to the gesture until the finger lifts.
    var fingerDragging by remember { mutableStateOf(false) }
    // Counts the gestures this screen has seen, so that a swipe's turn-in is re-read for each one.
    // See [SwipeTurnInKey]: two swipes the same way in a row are one direction, and keyed on the
    // direction alone the second one kept the first one's neighbour - the track now playing.
    var swipeGestureId by remember { mutableIntStateOf(0) }
    // Follows the finger exactly, swings back if the drag does not go over, and holds where the
    // finger left it when it does.
    val swipeHeld by
        animateFloatAsState(
            targetValue = swipeHeldTarget.floatValue,
            animationSpec = if (swipeDragging) tween(0) else tween(SWIPE_SETTLE_MS),
            label = "swipe_held",
        )
    val swipeCanNext = rememberUpdatedState(canSkipNext)
    val swipeCanPrevious = rememberUpdatedState(canSkipPrevious)

    // What the finger is turning in, fixed when the direction is decided rather than read again as
    // the queue moves: the hand-over changes what the neighbour slot holds, and reading it then would
    // swap this layer to the following track's cover halfway through its own turn.
    //
    // Derived here rather than kept in state and filled by an effect on the direction. An effect
    // keyed on the direction alone did not re-run when two swipes went the same way - the key it
    // compares never changed - so the second swipe kept the first swipe's neighbour, which by then
    // was the track already playing. Keying the derivation on the gesture as well makes every swipe
    // read its own neighbour, while a recomposition *within* one gesture still cannot move it.
    val swipeTurned =
        remember(SwipeTurnInKey(gestureId = swipeGestureId, direction = swipeDirection)) {
            when (swipeDirection) {
                TRACK_CHANGE_NEXT -> swipeTurnInOf(nextMediaMetadata)
                TRACK_CHANGE_PREVIOUS -> swipeTurnInOf(previousMediaMetadata)
                else -> null
            }
        }
    // The turn-in, read live inside the gesture rather than captured by the gesture's own lambda.
    //
    // `pointerInput`'s block runs for as long as its keys stay the same, and a plain `val` read
    // inside it is whatever that val held when the block was built - for this screen, a composition
    // with no direction yet, so `null`. Every swipe therefore animated its page perfectly and then
    // failed the hand-over's `swipeTurned != null` test, forever: the page turned, the song never
    // changed. Wrapped the way the skip flags above already are, the same read is the live one the
    // composition drew the flap from.
    val swipeTurnedState = rememberUpdatedState(swipeTurned)
    val swipeCoverRequest = rememberOfflineArtworkImageRequest(swipeTurned?.coverUrl)
    val currentPageMemory = artworkMemories.forSlot("current")
    val animationsDisabled = LocalAnimationsDisabled.current
    // Once the page's own arrival has taken the handed-over track it draws that cover itself, from
    // the same progress, and the finger's copy of it would be one layer too many.
    val swipeSettled =
        swipeHandedOver &&
            (currentPageMemory.turnedFor.value == swipeTurned?.trackKey ||
                (animationsDisabled && currentMediaItem?.mediaId == swipeTurned?.mediaId))
    val swipeShowing =
        swipeCoverRequest != null && swipeDirection != 0 && !swipeSettled && swipeHeld < HELD_TURN
    // A hand-over that never lands must not leave the page half turned over: a player that refused
    // the skip, say, would otherwise keep the finger's layer at its progress with nothing to take it.
    LaunchedEffect(swipeHandedOver) {
        if (!swipeHandedOver) return@LaunchedEffect
        delay(SWIPE_GIVE_UP_MS)
        if (currentPageMemory.turnedFor.value != swipeTurned?.trackKey) {
            swipeHeldTarget.floatValue = HELD_TURN
        }
    }

    // A button skip, turned the way a swipe turns: the page goes over and the track is handed over as
    // it passes the hand-over point, instead of the track changing at once and the page moving after.
    //
    // The finger is replaced by the frame clock - the same distance over the same time - so the turn
    // always finishes. It used to wait for the neighbour's turn-in before moving, with the hold parked
    // at the hand-over point until that resolved: a parked hold is a cover frozen half way over, which
    // is the stall this was reported as. Nothing here waits to start.
    LaunchedEffect(playerConnection) {
        playerConnection.turnRequests.collect { forward ->
            val canTurn = if (forward) swipeCanNext.value else swipeCanPrevious.value
            if (!isPlayerExpanded || !swipeThumbnail || !canTurn || fingerDragging) {
                if (forward) playerConnection.skipNext() else playerConnection.skipPrevious()
                return@collect
            }
            swipeGestureId++
            // A finger can take the page over while this turn runs, so what this turn owns is the
            // gesture it belongs to: a newer turn - or a drag - must not be written over by an older
            // one finishing.
            val gesture = swipeGestureId
            swipeDirection = if (forward) TRACK_CHANGE_NEXT else TRACK_CHANGE_PREVIOUS
            swipeHandedOver = false
            swipeDragging = true
            swipeHeldTarget.floatValue = HELD_TURN
            var handedOver = false
            // Timed from the clock, like the page's own arrival: the same press takes the same time
            // to carry the page over whether the next cover is cached or still arriving.
            var startNanos = 0L
            try {
                while (true) {
                    val running =
                        withFrameNanos { frameTimeNanos ->
                            if (startNanos == 0L) startNanos = frameTimeNanos
                            val elapsed = (frameTimeNanos - startNanos) / 1_000_000L
                            val fraction = (elapsed / TRACK_CHANGE_TURN_MS.toFloat()).coerceIn(0f, 1f)
                            val held = HELD_TURN - (HELD_TURN - HELD_FLAT) * fraction
                            swipeHeldTarget.floatValue = held
                            if (!handedOver && HELD_TURN - held >= SWIPE_HAND_OVER_FRACTION) {
                                handedOver = true
                                // The page about to receive the track picks the turn up exactly where the
                                // clock left it, the way it picks up a finger's.
                                currentPageMemory.handover.value = held
                                swipeHandedOver = true
                                if (forward) playerConnection.skipNext() else playerConnection.skipPrevious()
                            }
                            elapsed < TRACK_CHANGE_TURN_MS && swipeGestureId == gesture
                        }
                    if (!running) break
                }
                // A press is a press: a turn cut short - a finger crossing it, say - still changes the
                // track rather than being swallowed.
                if (!handedOver && swipeGestureId == gesture) {
                    if (forward) playerConnection.skipNext() else playerConnection.skipPrevious()
                }
            } finally {
                // The layer comes down however this ends - the turn finishing, a finger taking the page
                // over, the sheet closing on it - because a hold left standing is a cover frozen half
                // way over, the very thing this path exists to avoid. A finger on the page is the one
                // case where lifting it is not this turn's to do.
                swipeDragging = false
                if (!fingerDragging && swipeGestureId == gesture) {
                    // Set aside at the flat moment, the page having taken the track by now, and wound
                    // back with the layer already hidden: neither move is seen.
                    swipeDirection = 0
                    swipeHandedOver = false
                    swipeHeldTarget.floatValue = HELD_TURN
                }
            }
        }
    }

    // Keep the page under the finger on the track that is playing.
    //
    // One effect, and an *animated* one. There used to be two: this one and a second that called
    // `scrollToItem` - an instant jump - on the same queue changes. Whenever both ran, the instant
    // jump cancelled the animation that had just started, which is the largest part of why a skip
    // looked like an immediate switch rather than a move. The instant jump had no case of its own:
    // every change it reacted to (the track, or its index) is one this effect already sees, and the
    // non-animated call is kept only as the fallback for a grid that cannot animate at all.
    LaunchedEffect(mediaMetadata, currentMediaItem?.mediaId, canSkipPrevious, canSkipNext, gridAnimationsOff) {
        val index = currentMediaIndex
        if (index < 0 || index >= thumbnailPages.size) return@LaunchedEffect
        // A device with its animations switched off gets the page at once.
        //
        // The platform's scroll animation does not advance when the animator duration scale is 0, so
        // asking it to carry the grid left the artwork between two pages - half the previous track and
        // half the new one, split down the middle - until something else happened to align the grid,
        // which is seconds later and reads as a cover stuck half way over. The instant move is not an
        // animation, so it always lands. On a device that does animate, nothing changes.
        if (gridAnimationsOff) {
            thumbnailLazyGridState.scrollToItem(index)
            return@LaunchedEffect
        }
        try {
            thumbnailLazyGridState.animateScrollToItem(index)
        } catch (_: Exception) {
            thumbnailLazyGridState.scrollToItem(index)
        }
        // The page has to *be* the page, and not a point between two of them.
        //
        // The animated scroll is the only alignment left - the instant jump that used to follow it was
        // dropped as redundant - and it does not always land. A grid that re-lays-out under it cancels
        // it, and the page width follows the artwork's own size, so that is the ordinary case rather
        // than a rare one. What is left on screen then is the artwork cut down the middle, the previous
        // track's page on the left and the new track's on the right, and it does not move again until
        // the next track change: the cover reported as stuck half way over. This snap is not an
        // animation, so it always lands, and it does nothing at all when the scroll did.
        if (thumbnailLazyGridState.isScrollInProgress) return@LaunchedEffect
        if (thumbnailLazyGridState.firstVisibleItemIndex != index ||
            thumbnailLazyGridState.firstVisibleItemScrollOffset != 0
        ) {
            thumbnailLazyGridState.scrollToItem(index)
        }
    }

    // Seek on double tap
    var showSeekEffect by remember { mutableStateOf(false) }
    var seekDirection by remember { mutableStateOf("") }
    val layoutDirection = LocalLayoutDirection.current

    Box(modifier = modifier) {
        // Error view
        AnimatedVisibility(
            visible = error != null && !sourceFetchFailed,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier =
                Modifier
                    .padding(32.dp)
                    .align(Alignment.Center),
        ) {
            error?.let { playbackError ->
                PlaybackError(
                    error = playbackError,
                    retry = {
                        playerConnection.player.prepare()
                        playerConnection.player.play()
                    },
                    playFromYouTube = { playerConnection.playCurrentTrackFromYouTube() },
                )
            }
        }

        // Main thumbnail view
        AnimatedVisibility(
            visible = error == null || sourceFetchFailed,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(
                        if (showNowPlayingHeader) {
                            Modifier.statusBarsPadding()
                        } else {
                            Modifier
                        },
                    ),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (showNowPlayingHeader) {
                    // Now Playing header
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.now_playing),
                            style = MaterialTheme.typography.titleMedium,
                            color = textBackgroundColor,
                        )
                        // Show album title or queue title
                        val playingFrom = queueTitle ?: mediaMetadata?.album?.title
                        if (!playingFrom.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = playingFrom,
                                style = MaterialTheme.typography.titleMedium,
                                color = textBackgroundColor.copy(alpha = 0.8f),
                                maxLines = 1,
                                modifier = Modifier.hushMarquee(),
                            )
                        }
                    }
                }

                // Thumbnail content
                BoxWithConstraints(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    val horizontalLazyGridItemWidth = maxWidth * horizontalLazyGridItemWidthFactor
                    val containerMaxWidth = maxWidth

                    LazyHorizontalGrid(
                        state = thumbnailLazyGridState,
                        rows = GridCells.Fixed(1),
                        flingBehavior = rememberSnapFlingBehavior(thumbnailSnapLayoutInfoProvider),
                        // The finger never scrolls the grid: the swipe is the page's own turn, handled by
                        // the gesture below. A grid that moved as well would put a second motion beside the
                        // turn - and it would need the track to change after it settled, which is exactly
                        // what made the change arrive before the flip.
                        userScrollEnabled = false,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(
                            items = thumbnailPages,
                            // Keyed by *slot* (previous | current | next), not by track. A key that
                            // carried the track id meant a track change replaced the page composable
                            // outright - so nothing inside it could animate, and the arrival was a cut
                            // with an empty frame in front of it. A stable slot lets the artwork bring
                            // itself in; see [TrackArtwork].
                            key = { page -> page.slotKey },
                            contentType = { "thumbnailPage" },
                        ) { page ->
                            val item = page.mediaItem
                            val incrementalSeekSkipEnabled by rememberPreference(SeekExtraSeconds, defaultValue = false)
                            var skipMultiplier by remember { mutableStateOf(1) }
                            var lastTapTime by remember { mutableLongStateOf(0L) }
                            val itemMetadata = remember(item) { item.metadata }
                            val storefront =
                                remember {
                                    val country = Locale.getDefault().country
                                    if (country.length == 2) country.lowercase(Locale.ROOT) else "us"
                                }
                            val shouldUseCanvas =
                                hushCanvasEnabled &&
                                    playerDesignStyle != PlayerDesignStyle.V7 &&
                                    playerDesignStyle != PlayerDesignStyle.V8 &&
                                    item.mediaId.isNotBlank() &&
                                    item.mediaId == currentMediaItem?.mediaId
                            val shouldFetchCanvas =
                                shouldUseCanvas && !lowDataModeActive &&
                                    (!canvasWifiOnly || networkConnectivity.wifi)
                            var canvasArtwork by remember(item.mediaId) { mutableStateOf<CanvasArtwork?>(null) }
                            var canvasFetchInFlight by remember(item.mediaId) { mutableStateOf(false) }

                            LaunchedEffect(shouldUseCanvas) {
                                if (!shouldUseCanvas) {
                                    canvasArtwork = null
                                    canvasFetchInFlight = false
                                }
                            }

                            LaunchedEffect(shouldUseCanvas, shouldFetchCanvas, item.mediaId, canvasSource) {
                                if (!shouldUseCanvas) return@LaunchedEffect

                                val songTitleRaw =
                                    itemMetadata
                                        ?.title
                                        ?.takeIf { it.isNotBlank() }
                                        ?: item.mediaMetadata.title?.toString()
                                        ?: ""

                                val artistNameRaw =
                                    itemMetadata
                                        ?.artists
                                        ?.firstOrNull()
                                        ?.name
                                        ?.takeIf { it.isNotBlank() }
                                        ?: item.mediaMetadata.artist?.toString()
                                        ?: item.mediaMetadata.subtitle?.toString()
                                        ?: ""

                                if (canvasFetchInFlight) return@LaunchedEffect
                                canvasFetchInFlight = true

                                try {
                                    canvasArtwork =
                                        resolveCanvasArtworkForPlayback(
                                            mediaId = item.mediaId,
                                            songTitleRaw = songTitleRaw,
                                            artistNameRaw = artistNameRaw,
                                            albumId = itemMetadata?.album?.id,
                                            albumTitleRaw = itemMetadata?.album?.title,
                                            storefront = storefront,
                                            requireVertical = false,
                                            allowNetwork = shouldFetchCanvas,
                                            canvasSource = canvasSource,
                                        )
                                } finally {
                                    canvasFetchInFlight = false
                                }
                            }

                            Box(
                                modifier =
                                    Modifier
                                        .width(horizontalLazyGridItemWidth)
                                        .fillMaxSize()
                                        .padding(horizontal = if (edgeToEdge) 0.dp else PlayerHorizontalPadding)
                                        .pointerInput(Unit) {
                                            detectTapGestures(
                                                onDoubleTap = { offset ->
                                                    if (enableHapticFeedback) {
                                                        view.performHapticFeedback(
                                                            android.view.HapticFeedbackConstants.CONTEXT_CLICK,
                                                            android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
                                                        )
                                                    }
                                                    val currentPosition = playerConnection.player.currentPosition
                                                    val duration = playerConnection.player.duration

                                                    val now = System.currentTimeMillis()
                                                    if (incrementalSeekSkipEnabled && now - lastTapTime < 1000) {
                                                        skipMultiplier++
                                                    } else {
                                                        skipMultiplier = 1
                                                    }
                                                    lastTapTime = now

                                                    val skipAmount = 5000 * skipMultiplier

                                                    if ((layoutDirection == LayoutDirection.Ltr && offset.x < size.width / 2) ||
                                                        (layoutDirection == LayoutDirection.Rtl && offset.x > size.width / 2)
                                                    ) {
                                                        playerConnection.player.seekTo(
                                                            (currentPosition - skipAmount).coerceAtLeast(0),
                                                        )
                                                        seekDirection =
                                                            context.getString(R.string.seek_backward_dynamic, skipAmount / 1000)
                                                    } else {
                                                        playerConnection.player.seekTo(
                                                            (currentPosition + skipAmount).coerceAtMost(duration),
                                                        )
                                                        seekDirection = context.getString(R.string.seek_forward_dynamic, skipAmount / 1000)
                                                    }
                                                    showSeekEffect = true
                                                },
                                            )
                                        }
                                        .pointerInput(swipeThumbnail, isPlayerExpanded) {
                                            if (!swipeThumbnail || !isPlayerExpanded) return@pointerInput
                                            // The swipe: the finger carries the page over and the track is handed
                                            // over as it goes. No fling, no settle, no snap back to the playing
                                            // page - the turn is the whole of the gesture.
                                            detectHorizontalDragGestures(
                                                onDragStart = {
                                                    swipeGestureId++
                                                    fingerDragging = true
                                                    swipeDragging = true
                                                    swipeHandedOver = false
                                                    swipeDirection = 0
                                                    swipeHeldTarget.floatValue = HELD_TURN
                                                },
                                                onHorizontalDrag = { _, dragAmount ->
                                                    val width = size.width.toFloat()
                                                    if (width <= 0f) return@detectHorizontalDragGestures
                                                    if (swipeDirection == 0 && dragAmount != 0f) {
                                                        swipeDirection =
                                                            if (dragAmount < 0f) {
                                                                TRACK_CHANGE_NEXT
                                                            } else {
                                                                TRACK_CHANGE_PREVIOUS
                                                            }
                                                    }
                                                    // The page comes over as far as the finger has carried it, and
                                                    // a drag that doubles back unwinds it instead of winding it
                                                    // further.
                                                    swipeHeldTarget.floatValue =
                                                        (swipeHeldTarget.floatValue + dragAmount * swipeDirection / width)
                                                            .coerceIn(HELD_FLAT, HELD_TURN)
                                                },
                                                onDragEnd = {
                                                    fingerDragging = false
                                                    swipeDragging = false
                                                    val dragged = HELD_TURN - swipeHeldTarget.floatValue
                                                    // The live turn-in, not the one this lambda captured when it was
                                                    // built: the gesture decides its direction after the handler
                                                    // exists, and a captured value was null for every gesture.
                                                    if (swipeHandsOver(
                                                            dragged,
                                                            swipeDirection,
                                                            swipeCanNext.value,
                                                            swipeCanPrevious.value,
                                                        ) &&
                                                        swipeTurnedState.value != null
                                                    ) {
                                                        // The finger's part of the turn goes to the page that is about
                                                        // to receive the track, and only then is the track changed:
                                                        // the song changes as the page goes over, and the turn carries
                                                        // on from where the finger left it rather than starting again
                                                        // once the cover has already arrived.
                                                        currentPageMemory.handover.value = swipeHeldTarget.floatValue
                                                        swipeHandedOver = true
                                                        // Through the connection, not the raw player: the two transport
                                                        // buttons and every other skip in the app go through these, and
                                                        // they are where the queue's own rules live - a guest in a
                                                        // Together session asks the host instead of skipping locally,
                                                        // an emptied timeline is recovered rather than left silent, and
                                                        // a player left IDLE is prepared and played. A swipe that called
                                                        // the raw player did none of that, so the same gesture skipped
                                                        // the two of them out of step. A skip these refuse is what the
                                                        // hand-over's give-up net is there to catch.
                                                        if (swipeDirection > 0) {
                                                            playerConnection.skipNext()
                                                        } else {
                                                            playerConnection.skipPrevious()
                                                        }
                                                    } else {
                                                        // A drag long enough to hand over that found nothing to
                                                        // hand to - the end of the queue, or a neighbour with
                                                        // no cover to turn in. On screen the page springs back,
                                                        // which is indistinguishable from a skip the player
                                                        // refused, so it is said out loud rather than left to be
                                                        // rediscovered. (This is the shape the stale-turn-in
                                                        // bug hid in: every swipe sprang back here.)
                                                        if (dragged >= SWIPE_HAND_OVER_FRACTION && swipeTurnedState.value == null) {
                                                            Timber.tag(SwipeLogTag).d(
                                                                "no turn-in for direction=%d (neighbour has no cover)",
                                                                swipeDirection,
                                                            )
                                                        }
                                                        swipeHeldTarget.floatValue = HELD_TURN
                                                    }
                                                },
                                                onDragCancel = {
                                                    fingerDragging = false
                                                    swipeDragging = false
                                                    swipeHeldTarget.floatValue = HELD_TURN
                                                },
                                            )
                                        },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(
                                                containerMaxWidth -
                                                    if (edgeToEdge) 0.dp else PlayerHorizontalPadding * 2,
                                            )
                                            .clip(RoundedCornerShape(thumbnailCornerRadius.dp)),
                                ) {
                                    if (hidePlayerThumbnail) {
                                        // Show app logo when thumbnail is hidden
                                        Box(
                                            modifier =
                                                Modifier
                                                    .fillMaxSize()
                                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Image(
                                                painter = painterResource(R.drawable.hush_logo_mark),
                                                contentDescription = stringResource(R.string.hide_player_thumbnail),
                                                modifier = Modifier.size(120.dp),
                                            )
                                        }
                                    } else {
                                        val primaryCanvasUrl = canvasArtwork?.animated
                                        val fallbackCanvasUrl = canvasArtwork?.videoUrl

                                        val shouldCropArtwork =
                                            cropThumbnailToSquare &&
                                                playerDesignStyle != PlayerDesignStyle.V7 &&
                                                playerDesignStyle != PlayerDesignStyle.V8

                                        // One remembered url for the cover and the backdrop behind it,
                                        // which are the same image: the two copies that used to be here
                                        // ran `highRes()` - a set of regexes over the url - twice per page
                                        // on every recomposition, and this screen recomposes several
                                        // times a second while a track plays, so the cost landed on
                                        // frames nobody would connect to a url. Remembered on the item,
                                        // because that is when the artwork can change.
                                        val thumbnailArtworkUrl =
                                            remember(item) {
                                                item.metadata?.thumbnailUrl?.highRes()
                                                    ?: item.mediaMetadata.artworkUri?.toString()
                                            }
                                        val thumbnailBgRequest = rememberOfflineArtworkImageRequest(thumbnailArtworkUrl)
                                        val thumbnailArtworkRequest = thumbnailBgRequest
                                        val thumbnailBgBlurEnabled = backdropEnabled && !disableBlur && backdropBlurAmount > 0

                                        // The backdrop is the same cover, blurred: one layer, so the
                                        // blur is a single pass over the composited result rather than
                                        // one pass per layer. It swaps in place while the artwork over
                                        // it travels - a colour field has no direction to tell.
                                        if (thumbnailBgBlurEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                            val blurRadiusPx = (backdropBlurAmount * 60 / 100f).coerceAtMost(60f)
                                            Box(
                                                modifier =
                                                    Modifier
                                                        .fillMaxSize()
                                                        .let { if (shouldCropArtwork) it.aspectRatio(1f) else it }
                                                        .graphicsLayer(
                                                            renderEffect = BlurEffect(radiusX = blurRadiusPx, radiusY = blurRadiusPx),
                                                            alpha = 0.6f,
                                                        ),
                                            ) {
                                                TrackArtwork(
                                                    model = thumbnailBgRequest,
                                                    trackKey = "backdrop:$thumbnailArtworkUrl",
                                                    contentScale = ContentScale.FillBounds,
                                                    modifier = Modifier.fillMaxSize(),
                                                    turn = false,
                                                )
                                            }
                                        } else if (thumbnailBgBlurEnabled) {
                                            ThumbnailBgBlurApi30(
                                                imageUrl = thumbnailArtworkUrl,
                                                blurAmount = backdropBlurAmount,
                                                shouldCropArtwork = shouldCropArtwork,
                                            )
                                        } else {
                                            Box(
                                                modifier =
                                                    Modifier
                                                        .fillMaxSize()
                                                        .let { if (shouldCropArtwork) it.aspectRatio(1f) else it }
                                                        .graphicsLayer(alpha = 0.6f),
                                            ) {
                                                TrackArtwork(
                                                    model = thumbnailBgRequest,
                                                    trackKey = "backdrop:$thumbnailArtworkUrl",
                                                    contentScale = ContentScale.FillBounds,
                                                    modifier = Modifier.fillMaxSize(),
                                                    turn = false,
                                                )
                                            }
                                        }

                                        // What an arrival belongs to: read once per track, at the
                                        // change - see [trackIdentityOf] - rather than re-read as
                                        // the page recomposes.
                                        val trackIdentity =
                                            remember(item.mediaId, item.mediaMetadata.artworkUri) {
                                                trackIdentityOf(
                                                    mediaId = item.mediaId,
                                                    artworkUri = item.mediaMetadata.artworkUri?.toString(),
                                                )
                                            }
                                        TrackArtwork(
                                            model = thumbnailArtworkRequest,
                                            trackKey = trackIdentity,
                                            contentScale = if (shouldCropArtwork) ContentScale.Crop else ContentScale.Fit,
                                            modifier =
                                                Modifier
                                                    .fillMaxSize()
                                                    .let { if (shouldCropArtwork) it.aspectRatio(1f) else it },
                                            // Only the page being read is faded and turned frame by
                                            // frame: the pages either side of it are off-screen, and
                                            // a page that moved where nobody can see it would be a
                                            // full-frame layer transform per frame spent on nothing.
                                            //
                                            // And it turns whichever way the track arrived - a tap
                                            // on skip, or a swipe across the artwork. A swipe used to
                                            // settle instead, on the argument that the page had
                                            // already moved under the finger and the motion must not
                                            // be doubled; on a device whose system animations are
                                            // switched off the grid's own settle does not happen at
                                            // all, so all that was left was the cover being
                                            // replaced - which is not what a page turning looks
                                            // like, and not what the buttons do.
                                            animated = page.slotKey == "current",
                                            turn = page.slotKey == "current",
                                            direction = {
                                                trackChangeDirection.directionOf(
                                                    mediaMetadata?.id,
                                                    playerConnection.player.currentMediaItemIndex,
                                                )
                                            },
                                            memory = artworkMemories.forSlot(page.slotKey),
                                            // The panel reads the page that is playing and no other:
                                            // [TrackArtwork] reports an arrival only from the layer
                                            // that turns, which is this one.
                                            diagnostics = playerDiagnostics,
                                        )

                                        if (shouldUseCanvas &&
                                            (!primaryCanvasUrl.isNullOrBlank() || !fallbackCanvasUrl.isNullOrBlank())
                                        ) {
                                            CanvasArtworkPlayer(
                                                primaryUrl = primaryCanvasUrl,
                                                fallbackUrl = fallbackCanvasUrl,
                                                isPlaying = isPlaying,
                                                modifier = Modifier.fillMaxSize(),
                                                diagnostics = playerDiagnostics,
                                            )
                                        }

                                        // The finger's page, drawn *over* the canvas and not under it.
                                        //
                                        // A track with a canvas has its whole artwork area covered by this
                                        // layer, so a swipe's cover drawn before it was a page nobody could
                                        // see: what flapped was the canvas of the song already playing, which
                                        // is what was reported. Drawn last, the turn-in is the top layer for
                                        // as long as the finger is on it, and the canvas is what is being
                                        // turned over - which is also what it is after the hand-over, when
                                        // the page itself takes the track and this layer steps aside.
                                        if (page.slotKey == "current" && swipeShowing) {
                                            // The page under the finger: the track a swipe is turning in, held
                                            // at exactly the progress the drag has carried the page to.
                                            // Nothing about it is on a clock - the finger is the clock - and
                                            // it is drawn only until the page itself takes the track over,
                                            // from the same progress.
                                            TrackArtwork(
                                                model = swipeCoverRequest,
                                                trackKey = swipeTurned?.trackKey,
                                                contentScale = if (shouldCropArtwork) ContentScale.Crop else ContentScale.Fit,
                                                modifier =
                                                    Modifier
                                                        .fillMaxSize()
                                                        .let { if (shouldCropArtwork) it.aspectRatio(1f) else it },
                                                animated = false,
                                                turn = true,
                                                direction = { swipeDirection },
                                                heldProgress = swipeHeld,
                                            )
                                        }
                                    }

                                    if (pulseMatrixEnabled) {
                                        val visualizerHeight =
                                            (containerMaxWidth - PlayerHorizontalPadding * 2) * 0.30f
                                        PulseMatrixVisualizer(
                                            theme = pulseMatrixTheme,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(visualizerHeight)
                                                .align(Alignment.BottomCenter),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // The track-change readout, drawn over the artwork it describes and gated *here*, before
        // its bundles are built, so that a build with it off pays nothing for it - the panel's own
        // reads are all inside it. See [PlayerDiagnosticsOverlay].
        if (showTrackChangeDiagnostics) {
            PlayerDiagnosticsOverlay(
                diagnostics = playerDiagnostics,
                grid =
                    PlayerDiagnosticsGrid(
                        state = thumbnailLazyGridState,
                        currentSlotIndex = currentMediaIndex,
                        pageCount = thumbnailPages.size,
                        animationsOff = gridAnimationsOff,
                    ),
                finger =
                    PlayerDiagnosticsFinger(
                        gesture =
                            when {
                                !swipeDragging -> PlayerDiagnosticsGesture.IDLE
                                fingerDragging -> PlayerDiagnosticsGesture.DRAG
                                else -> PlayerDiagnosticsGesture.TURN
                            },
                        // A gesture with no direction yet is reported as the direction the page
                        // would take, so the line never reads as a move that is not happening.
                        direction = if (swipeDirection == 0) TRACK_CHANGE_NEXT else swipeDirection,
                        held = swipeHeld,
                        handedOver = swipeHandedOver,
                        layerShowing = swipeShowing,
                    ),
                trackId = mediaMetadata?.id,
                queueIndex = playerConnection.player.currentMediaItemIndex,
                modifier =
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = PlayerHorizontalPadding + 8.dp),
            )
        }

        // Seek effect
        LaunchedEffect(showSeekEffect) {
            if (showSeekEffect) {
                delay(1000)
                showSeekEffect = false
            }
        }

        AnimatedVisibility(
            visible = showSeekEffect,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Text(
                text = seekDirection,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                        .padding(8.dp),
            )
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun ThumbnailBgBlurApi30(
    imageUrl: String?,
    blurAmount: Int,
    shouldCropArtwork: Boolean,
) {
    val context = LocalContext.current
    val imageLoader = context.imageLoader

    val blurredBitmap by produceState<Bitmap?>(null, imageUrl, blurAmount) {
        if (imageUrl == null) return@produceState
        value =
            withContext(Dispatchers.IO) {
                try {
                    val request =
                        ImageRequest
                            .Builder(context)
                            .data(imageUrl)
                            .memoryCacheKey(imageUrl)
                            .diskCacheKey(imageUrl)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .networkCachePolicy(CachePolicy.ENABLED)
                            .allowHardware(false)
                            .size(500)
                            .build()
                    val result = imageLoader.execute(request)
                    when (result) {
                        is SuccessResult -> {
                            val bitmap = result.image.toBitmap().copy(Bitmap.Config.ARGB_8888, true)
                            val radius = (blurAmount * 25 / 100f).coerceIn(1f, 25f)
                            ImageBlurUtils.blur(bitmap, radius)
                        }

                        else -> {
                            null
                        }
                    }
                } catch (_: Exception) {
                    null
                }
            }
    }

    val modifier =
        Modifier
            .fillMaxSize()
            .let { if (shouldCropArtwork) it.aspectRatio(1f) else it }
            .graphicsLayer(alpha = 0.6f)

    val loadedBitmap = blurredBitmap
    if (loadedBitmap != null) {
        Image(
            painter = BitmapPainter(loadedBitmap.asImageBitmap()),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = modifier,
        )
    } else {
        AsyncImage(
            model = rememberOfflineArtworkImageRequest(imageUrl),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = modifier,
        )
    }
}

/*
 * Copyright (C) OuterTune Project
 * Custom SnapLayoutInfoProvider idea belongs to OuterTune
 */

// SnapLayoutInfoProvider
@ExperimentalFoundationApi
fun SnapLayoutInfoProvider(
    lazyGridState: LazyGridState,
    positionInLayout: (layoutSize: Float, itemSize: Float) -> Float = { layoutSize, itemSize ->
        (layoutSize / 2f - itemSize / 2f)
    },
    velocityThreshold: Float = 1000f,
): SnapLayoutInfoProvider =
    object : SnapLayoutInfoProvider {
        private val layoutInfo: LazyGridLayoutInfo
            get() = lazyGridState.layoutInfo

        override fun calculateApproachOffset(
            velocity: Float,
            decayOffset: Float,
        ): Float = 0f

        override fun calculateSnapOffset(velocity: Float): Float {
            val bounds = calculateSnappingOffsetBounds()

            // Only snap when velocity exceeds threshold
            if (abs(velocity) < velocityThreshold) {
                if (abs(bounds.start) < abs(bounds.endInclusive)) {
                    return bounds.start
                }

                return bounds.endInclusive
            }

            return when {
                velocity < 0 -> bounds.start
                velocity > 0 -> bounds.endInclusive
                else -> 0f
            }
        }

        fun calculateSnappingOffsetBounds(): ClosedFloatingPointRange<Float> {
            var lowerBoundOffset = Float.NEGATIVE_INFINITY
            var upperBoundOffset = Float.POSITIVE_INFINITY

            layoutInfo.visibleItemsInfo.fastForEach { item ->
                val offset = calculateDistanceToDesiredSnapPosition(layoutInfo, item, positionInLayout)

                // Find item that is closest to the center
                if (offset <= 0 && offset > lowerBoundOffset) {
                    lowerBoundOffset = offset
                }

                // Find item that is closest to center, but after it
                if (offset >= 0 && offset < upperBoundOffset) {
                    upperBoundOffset = offset
                }
            }

            return lowerBoundOffset.rangeTo(upperBoundOffset)
        }
    }

fun calculateDistanceToDesiredSnapPosition(
    layoutInfo: LazyGridLayoutInfo,
    item: LazyGridItemInfo,
    positionInLayout: (layoutSize: Float, itemSize: Float) -> Float,
): Float {
    val containerSize =
        layoutInfo.singleAxisViewportSize - layoutInfo.beforeContentPadding - layoutInfo.afterContentPadding

    val desiredDistance = positionInLayout(containerSize.toFloat(), item.size.width.toFloat())
    val itemCurrentPosition = item.offset.x.toFloat()

    return itemCurrentPosition - desiredDistance
}

private val LazyGridLayoutInfo.singleAxisViewportSize: Int
    get() = if (orientation == Orientation.Vertical) viewportSize.height else viewportSize.width

@Composable
private fun PulseMatrixVisualizer(
    theme: PulseMatrixTheme,
    modifier: Modifier = Modifier,
) {
    val bands by PulseMatrixEngine.barHeights.collectAsStateWithLifecycle()
    PulseMatrixCanvas(
        theme = theme,
        opacity = 0.8f,
        modifier = modifier,
        bands = bands,
        miniMode = false,
    )
}
