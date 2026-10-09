/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.player

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import app.hush.music.BuildConfig
import app.hush.music.LocalAnimationsDisabled
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Which way the player's content moves when the track changes.
 *
 * The player changes track in two ways - a tap on skip, and a swipe across the artwork - and a
 * person reads both as the same movement: next takes the content away to the left and brings it
 * back from the right, previous does the reverse. [TRACK_CHANGE_NEXT] and [TRACK_CHANGE_PREVIOUS]
 * are that direction, and everything that moves on a track change reads it, so the title, the
 * artists and the artwork cannot disagree about which way the queue was travelled.
 */
internal const val TRACK_CHANGE_NEXT = 1
internal const val TRACK_CHANGE_PREVIOUS = -1

/**
 * Works out which way the queue moved, from the two facts that say so.
 *
 * The track id alone cannot say it - jumping to a track played earlier says nothing about
 * direction - and the queue index alone cannot either: editing the queue moves the index under a
 * track that never changed. Together they can. A changed id *and* a moved index is a direction; a
 * changed id at the same index is a wrap-around or a jump, and keeps the last direction, which is
 * what a person who pressed skip on the last track of a repeat-all queue expects to see.
 *
 * Deliberately not Compose state: it is read once, when a change *starts*, and a snapshot write
 * during composition would be neither needed nor correct.
 */
internal class TrackChangeDirection {
    private var lastMediaId: String? = null
    private var lastIndex: Int = 0
    private var direction: Int = TRACK_CHANGE_NEXT

    fun directionOf(
        mediaId: String?,
        queueIndex: Int,
    ): Int {
        if (mediaId == null) return direction
        if (mediaId == lastMediaId) {
            // The same track with the queue rearranged around it is not a track change.
            lastIndex = queueIndex
            return direction
        }
        val moved = queueIndex - lastIndex
        direction =
            when {
                moved > 0 -> TRACK_CHANGE_NEXT
                moved < 0 -> TRACK_CHANGE_PREVIOUS
                else -> direction
            }
        lastMediaId = mediaId
        lastIndex = queueIndex
        return direction
    }
}

/**
 * The one [TrackChangeDirection] the player's surfaces read.
 *
 * One instance, shared, because the surfaces have to agree: the cover, the title and the artists
 * are one panel, and a panel that splits - the cover leaving to the left while the title arrives
 * from the right - is worse than either moving at all. A direction per surface would also read the
 * same change in whatever order each of them happened to recompose in, which is not something any
 * of them can know about the others.
 *
 * It is deliberately not provided per screen. There is one player, and the surfaces that move with
 * it are spread across it - a title deep inside a design-style branch, the cover inside the swipe
 * grid, the mini player outside the sheet entirely - so the only place all of them can be reached is
 * above all of them.
 */
internal val LocalTrackChangeDirection = staticCompositionLocalOf { TrackChangeDirection() }

/**
 * How much of its own width the text travels on a track change.
 *
 * Text is read at a glance, so it travels less than the cover: enough to be seen as a direction,
 * not so much that a title has to be chased across the screen to be read.
 */
private const val TRACK_CHANGE_TEXT_SLIDE_FRACTION = 0.16f

/**
 * The transition a track change uses on text: the same direction as the cover, at text scale.
 *
 * Call it from an `AnimatedContent`'s `transitionSpec`, resolving [direction] there - that is the
 * moment a change is known, and the only moment the new value is the one to animate towards.
 */
internal fun <S> AnimatedContentTransitionScope<S>.trackChangeSlide(direction: Int): ContentTransform {
    // "Towards the left" is what a skip does: the leaving line goes left, the arriving one comes
    // from the right and follows it. A return is the same motion the other way round.
    val towards =
        if (direction >= 0) {
            AnimatedContentTransitionScope.SlideDirection.Left
        } else {
            AnimatedContentTransitionScope.SlideDirection.Right
        }
    val travel: (Int) -> Int = { (it * TRACK_CHANGE_TEXT_SLIDE_FRACTION).roundToInt() }
    return (
        slideIntoContainer(towards, animationSpec = TRACK_CHANGE_SLIDE_SPEC, initialOffset = travel) +
            fadeIn(animationSpec = TRACK_CHANGE_FADE_SPEC)
    ) togetherWith
        (
            slideOutOfContainer(towards, animationSpec = TRACK_CHANGE_SLIDE_SPEC, targetOffset = travel) +
                fadeOut(animationSpec = TRACK_CHANGE_FADE_SPEC)
        )
}

private val TRACK_CHANGE_SLIDE_SPEC =
    tween<IntOffset>(durationMillis = 260, easing = FastOutSlowInEasing)

private val TRACK_CHANGE_FADE_SPEC = tween<Float>(durationMillis = 180, easing = FastOutSlowInEasing)

/**
 * How far the artwork travels across on a track change, as a fraction of its own width.
 *
 * The travel is the smaller half of the arrival - the turn below is the larger one - so it is
 * short: a page that is being turned does not also need to be thrown.
 */
private const val TRACK_CHANGE_SLIDE_FRACTION = 0.09f

/**
 * How far the arriving cover is turned away from the frame when a track change starts, in degrees.
 *
 * A track change is a page being turned. The cover the new track brings swings in from the side the
 * queue is travelling towards, hinged on the edge it leads with, and flattens as it lands - so a
 * tap on skip says *direction* on its own, the same thing a swipe across the cover says with a
 * whole page under the finger. That matters most where the swipe's own grid does not move at all:
 * the two gestures are one gesture in a book, and this is what makes them read as one here.
 *
 * Less than a right angle on purpose. Past 90 degrees the page turns edge-on and then shows its
 * back, and a cover seen from behind - mirrored - is a reflection, not a page.
 */
private const val TRACK_CHANGE_FLIP_ANGLE = 68f

/**
 * The camera distance the turn is seen from, in dp.
 *
 * The default (8dp) puts the camera close enough that the far edge of a turning page is noticeably
 * shorter than the near one, which on a cover this size reads as a warp. 12dp keeps the two edges
 * related - the page still visibly leans into the screen - without the trapezoid becoming the thing
 * a person notices.
 */
private const val TRACK_CHANGE_CAMERA_DISTANCE_DP = 12f

/**
 * How long the turn takes, from the cover being turned away to the cover lying flat.
 *
 * Long enough to be seen as a page coming over, short enough that it never stands between a person
 * and the next track - a skip is a reply, and a reply that keeps moving is a wait. It was 480ms, and
 * the part of that window the slow end of the curve spent on the last of the movement was what a
 * person noticed: see [turnProgress]'s decay.
 */
/** The arriving cover's pose: turned away from the frame, and lying flat in it. */
private const val TURNED_AWAY = 1f
private const val FLAT = 0f

internal const val TRACK_CHANGE_TURN_MS = 340L

/**
 * How far through the turn the artwork is at [elapsedMillis] since the arrival started: 1 is the
 * cover turned away, 0 is the cover lying flat.
 *
 * Driven from frame time rather than by a Compose animation, which is not a matter of taste.
 * Measured on the device: with `animator_duration_scale = 0` a Compose animation paints a frame and
 * holds it, so a turned cover stayed turned and the arrival became a cut - the same fault the
 * transport's wiggle and the aura cover's breath are written the same way to avoid.
 *
 * The shape is a page coming to rest: `(1 - t)^[TRACK_CHANGE_TURN_DECAY]`, so the rotation starts at
 * 1 and decays into flat, and the page lands without swinging back past flat - a page lands, it does
 * not bounce - since a counter-lean part-way through reads as a glitch rather than as paper. An
 * earlier curve here decayed as `exp(-8t) * cos(2.8πt)`, which put the page flat within about an
 * eighth of the window and then held it there: on screen that is a snap followed by nothing, and on
 * a device that drops the frames around a decode the snap is most of what is ever seen.
 *
 * The decay is mild on purpose. Squares - `(1 - t)^2`, which this was - spend *half* the window on
 * the last quarter of the movement, and that is a turn that is smooth for its first half and then
 * crawls: it is how the movement was reported ("very smooth initially, and after that it is slow").
 * A page coming to rest does decelerate, but it does not spend half a turn covering the last tenth
 * of the way. `TrackChangeMotionTest` holds both ends of that: the turn is still carrying the page
 * at half way, and the second half of it still moves.
 */
/** A finger's page held turned away: none of the arriving cover is on show. */
internal const val HELD_TURN = 1f

/** A finger's page lying flat: the arriving cover is the page. */
internal const val HELD_FLAT = 0f

/**
 * How far a swipe drags a page across before the track is handed over, as a fraction of the page.
 *
 * Past a third: far enough that a flick through the pages of the queue is not mistaken for a scroll,
 * early enough that the song changes while the page is still going over rather than after it lands.
 */
internal const val SWIPE_HAND_OVER_FRACTION = 0.35f

/** How long an unfinished swipe takes to swing back, in milliseconds. */
internal const val SWIPE_SETTLE_MS = 240

/**
 * How long a handed-over swipe waits for the page to take the track before giving the turn back, in
 * milliseconds. A net for the case where nothing takes it - a player that refused the skip - so the
 * artwork cannot be left standing half turned over with nothing coming.
 */
internal const val SWIPE_GIVE_UP_MS = 1500L

/**
 * The progress a swipe holds a page at, from how far the finger has dragged it across.
 *
 * Direct, and linear: the cover comes over exactly as far as the page has been carried. That is what
 * makes the gesture its own motion - it starts on the first pixel dragged instead of on a clock that
 * starts after the fling has settled.
 */
internal fun heldTurnProgress(draggedFraction: Float): Float =
    (HELD_TURN - draggedFraction).coerceIn(HELD_FLAT, HELD_TURN)

/**
 * What one swipe's turn-in is remembered by: the gesture it belongs to, and the side it took.
 *
 * The turn-in - the neighbour's cover the finger carries over the page - is read once, when the
 * direction is decided, and kept for the rest of that gesture, because the hand-over moves the queue
 * underneath it and re-reading it later would swap the layer to the *following* track's cover halfway
 * through its own turn.
 *
 * So the key has to say which gesture it was, not only which way it went. Keyed on the direction
 * alone, two swipes the same way in a row were one key: the second swipe never read its neighbour
 * again and kept the first swipe's - which, after the first swipe had handed the track over and the
 * queue had moved on, is the track now *playing*. On screen that is the flap showing the artwork of
 * the song already playing instead of the one the finger is turning in, which is how it was reported
 * ("shows the currently playing song's album art / canvas when flapping").
 *
 * Not Compose state: it is read in composition, to key a `remember`, and written nowhere.
 */
internal data class SwipeTurnInKey(
    val gestureId: Int,
    val direction: Int,
)

/** Whether a drag has gone far enough to hand the track over; a drag of no direction asks for nothing. */
internal fun swipeHandsOver(
    draggedFraction: Float,
    direction: Int,
    canSkipNext: Boolean,
    canSkipPrevious: Boolean,
): Boolean =
    draggedFraction >= SWIPE_HAND_OVER_FRACTION &&
        when {
            direction > 0 -> canSkipNext
            direction < 0 -> canSkipPrevious
            else -> false
        }

/**
 * Where a handed-over turn picks up: the time whose progress is [progress].
 *
 * The inverse of [turnProgress] - `t = 1 - p^(1/decay)` - so a page a finger carried a third of the
 * way has also spent the first part of the turn's time, and the rest is what is left to run.
 */
internal fun turnElapsedFor(progress: Float): Long {
    val p = progress.coerceIn(HELD_FLAT, HELD_TURN)
    return (TRACK_CHANGE_TURN_MS * (1.0 - p.toDouble().pow(1.0 / TRACK_CHANGE_TURN_DECAY))).toLong()
}

internal fun turnProgress(elapsedMillis: Long): Float {
    if (elapsedMillis <= 0L) return 1f
    if (elapsedMillis >= TRACK_CHANGE_TURN_MS) return 0f
    val t = elapsedMillis.toFloat() / TRACK_CHANGE_TURN_MS
    return (1f - t).pow(TRACK_CHANGE_TURN_DECAY)
}

/**
 * How fast the turn's movement decays across its window; see [turnProgress].
 *
 * 1 is a page dragged at a constant rate and stopped dead; 2 is the square whose tail crawls. 1.6
 * keeps the movement even enough that the second half of the turn is still visibly travelling, while
 * the page still arrives at a standstill rather than stopping with a jolt.
 */
private const val TRACK_CHANGE_TURN_DECAY = 1.6f

/**
 * Identity of a track, for the purpose of deciding when an arrival starts.
 *
 * It is the *track*, not the image request for it. Hush asks for the same track's cover twice - a
 * video thumbnail arrives first and the full-resolution image a moment later - and an arrival keyed
 * on the request played itself twice for one skip: the new cover arrived, was taken away again and
 * turned back in. On screen that is "it switched, then it switched back, then it moved".
 *
 * The artwork falls back to the request only when a track carries no id at all, which nothing
 * playable does; there is then nothing better to key on.
 */
internal fun trackIdentityOf(
    mediaId: String,
    artworkUri: String?,
): String = mediaId.ifBlank { artworkUri.orEmpty() }

/**
 * What an arrival does, given what brought the track in and what the layer is.
 *
 * A pure decision, taken once per arrival, so that the rules it encodes can be checked without a
 * device: the first composition never turns, an arrival that has already played never plays again
 * (the same track asks for its cover twice), and a layer nobody watches - the blurred backdrop, the
 * pages either side - is simply placed.
 */
internal enum class TrackChangeArrival {
    /** A page coming over: the cover turns into the frame from the side the queue travelled. */
    TURN,

    /** A cover settling into place rather than travelling: a surface's first composition. */
    FADE,

    /** Nothing to move: a layer that is not read frame by frame, or motion switched off. */
    SETTLE,
}

/**
 * The arrival for one track's cover. See [TrackChangeArrival] for what each answer means.
 *
 * @param arrivalsStarted false on a surface's very first composition, which has its own entrance.
 * @param turn whether this track was brought in by a tap (see the caller) rather than a swipe.
 * @param animated whether this layer is the page a person is reading.
 * @param animationsDisabled the person has asked Hush for no motion.
 * @param played whether this arrival has already been played - the same track asks for its cover
 *   twice, a video thumbnail and then the high-resolution image, and the second request is not a
 *   second track.
 */
/**
 * Where an arrival starts, in the same units [turnProgress] ends in - 1 turned away, 0 flat.
 *
 * Named because the readout needs it and the page itself nearly does: reporting the value the
 * page's progress happened to hold *before* the branch below assigned the real one gave a line that
 * read "TURN, 0.00" for a page that was, at that moment, turned right away - the one number in the
 * panel a person would draw a wrong conclusion from.
 *
 * @param handedOver the progress a finger or a button turn handed this arrival over at, if the
 *   track changed under one; null when the arrival starts from the beginning.
 */
internal fun arrivalStartProgress(
    kind: TrackChangeArrival,
    handedOver: Float?,
): Float =
    if (kind == TrackChangeArrival.TURN) handedOver ?: TURNED_AWAY else FLAT

internal fun trackChangeArrival(
    arrivalsStarted: Boolean,
    turn: Boolean,
    animated: Boolean,
    animationsDisabled: Boolean,
    played: Boolean,
): TrackChangeArrival =
    when {
        animationsDisabled || !animated -> TrackChangeArrival.SETTLE
        played -> TrackChangeArrival.SETTLE
        arrivalsStarted && turn -> TrackChangeArrival.TURN
        else -> TrackChangeArrival.FADE
    }

/**
 * A layer that appears - or goes - on the frame clock instead of by a Compose animation.
 *
 * For a surface that becomes ready on its own: the canvas video, which starts painting a moment
 * after the track changes and used to be switched on by `animateFloatAsState`. On a device whose
 * system animator duration scale is 0 Compose holds that animation's first frame, so the canvas
 * arrived as a cut - and the canvas is the whole of the artwork area when a person has it switched
 * on, which is why a track change looked like the new song's banner appearing at once.
 *
 * Starts at [ready], so a layer that is already showing when it is composed does not fade in from
 * nothing.
 */
@Composable
internal fun rememberFrameDrivenFade(
    ready: Boolean,
    durationMs: Int = TRACK_CHANGE_FADE_MS,
): Float {
    val alpha = remember { mutableFloatStateOf(if (ready) 1f else 0f) }
    LaunchedEffect(ready) {
        val from = alpha.floatValue
        val to = if (ready) 1f else 0f
        if (from == to) return@LaunchedEffect
        // Timed from the clock, so a fade takes [durationMs] whatever the frames it is drawn on are
        // doing - the same rule the track change's own movement follows; see [turnProgress].
        var startNanos = 0L
        while (true) {
            val running =
                withFrameNanos { frameTimeNanos ->
                    if (startNanos == 0L) startNanos = frameTimeNanos
                    val elapsed = (frameTimeNanos - startNanos) / 1_000_000L
                    val fraction = (elapsed / durationMs.toFloat()).coerceIn(0f, 1f)
                    alpha.floatValue = from + (to - from) * fraction
                    elapsed < durationMs
                }
            if (!running) break
        }
        alpha.floatValue = to
    }
    return alpha.floatValue
}

/** How long the artwork takes to arrive. Short: this is a reply to a tap, not a scene. */
private const val TRACK_CHANGE_FADE_MS = 260

/**
 * The artwork, as one arriving cover over the cover that was there before.
 *
 * Two things went wrong here. The incoming cover was a *new* composable with nothing in it until
 * its image decoded, so the frame after a skip was empty - a black flash over most artwork - and
 * the cover then appeared in a single step instead of arriving. So the last cover that finished
 * loading is drawn underneath (one track behind, never the same request as the one on top) and the
 * incoming one is animated over it: a skip always shows a cover, and it is the *movement* that
 * changes it.
 *
 * @param model the image request for this track's artwork.
 * @param trackKey identity of this artwork - media id *and* url, since a track can change covers
 *   without changing id. Used only to know when a new arrival starts.
 * @param direction resolved once per change, when the arrival starts.
 * @param animated whether this layer *is* the page being read. Its arrival is then driven from
 *   frame time - the cover fades in, and turns - where every other layer keeps Compose's own fade.
 *   A layer that is not the page being read is either off-screen (the pages either side) or the
 *   blurred backdrop the cover is seen against, and neither is worth a state write per frame; the
 *   backdrop is worst of all, since a frame of it is a full-screen blur re-evaluated.
 * @param turn whether this arrival turns the page as well as fading. Every way a track arrives - a
 *   tap on skip, and a swipe across the artwork - turns it: the gesture is the same movement to a
 *   person, so the two have to be the same movement on screen, and artwork that only settles after
 *   a swipe looks like a cover being replaced next to a page being turned.
 *
 * Frame time rather than Compose animations, and skipped where a person has asked Hush for no
 * motion: with `animator_duration_scale = 0` a Compose animation is a frame *held*, so the fade is
 * a pop and the turn is a cover stuck at an angle - see [turnProgress].
 */
/**
 * What one artwork page remembers across the changes it arrives on.
 *
 * Held by the caller rather than inside [TrackArtwork], and that is the whole point. A page is drawn
 * inside a lazy grid item, and the grid *recycles* those: a page that scrolls out has its
 * composition deactivated and the same nodes handed back to a page that scrolls in. A swipe is
 * exactly the gesture that makes it do that - the change arrives while the grid is still moving - so
 * the arriving page used to be a page that had never arrived before, and all three facts below were
 * gone with the composition it was recycled from: it had not started arriving, so a swipe faded
 * where a tap on skip turned, which is the one thing the two gestures were written to share; it had
 * no record of the arrival it had already played, so the same track's second cover - a video
 * thumbnail and then the high-resolution image - could turn the page a second time; and it had no
 * cover under the arriving one, so the frame that cover decodes on was empty.
 *
 * One memory per page slot, created above the grid, survives all three.
 */
internal class TrackArtworkMemory {
    /** The cover of the track before this one - the one a skip falls back to. */
    var baseModel = mutableStateOf<Any?>(null)

    /** False until this page has arrived once; see [trackChangeArrival]. */
    var arrivalsStarted = mutableStateOf(false)

    /** The arrival this page has already played; see [trackChangeArrival]'s `played`. */
    var turnedFor = mutableStateOf<Any?>(null)

    /** True for the length of a turn; see [TrackArtwork]. */
    val turning = mutableStateOf(false)

    /**
     * The progress a swipe handed this page's next arrival over at, if a finger carried the turn
     * part of the way and the track changed under it. Cleared by the first arrival that reads it, so
     * an arrival that was not handed over still starts turned away.
     */
    var handover = mutableStateOf<Float?>(null)
}

/**
 * The memories of the pages a player surface shows, by page slot.
 *
 * Keyed by slot and not by track, for the same reason the grid's items are: the slot is what
 * persists, and the track is what arrives in it.
 */
internal class TrackArtworkMemories {
    private val bySlot = mutableMapOf<String, TrackArtworkMemory>()

    /** The memory of one page slot, created on first use and kept after that. */
    fun forSlot(slotKey: String): TrackArtworkMemory = bySlot.getOrPut(slotKey) { TrackArtworkMemory() }
}

@Composable
internal fun TrackArtwork(
    model: Any?,
    trackKey: Any?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    animated: Boolean = false,
    turn: Boolean = false,
    direction: () -> Int = { TRACK_CHANGE_NEXT },
    memory: TrackArtworkMemory = remember { TrackArtworkMemory() },
    /**
     * A progress a finger is holding this page at, instead of an arrival the page runs itself: 1 is
     * turned away, 0 is flat. A layer drawn under a finger has no clock of its own - a turn running
     * underneath it would be a second page turning at a different rate.
     */
    heldProgress: Float? = null,
    /**
     * Where a debug build reads this page's arrival from; see [PlayerDiagnosticsOverlay]. Null
     * everywhere but the player screen's own page, and a write through it costs nothing while that
     * overlay is off.
     */
    diagnostics: PlayerTrackChangeDiagnostics? = null,
) {
    val painter = rememberAsyncImagePainter(model = model)
    // Coil 3 publishes this painter's progress as a StateFlow, not as Compose state.
    val painterState by painter.state.collectAsState()
    val loaded = painterState is AsyncImagePainter.State.Success

    // What this page remembers across the changes it arrives on - see [TrackArtworkMemory]. The
    // caller holds it, because the page it is handed to may be a recycled one.
    val baseModel = memory.baseModel
    val animationsDisabled = LocalAnimationsDisabled.current
    val arrivalsStarted = memory.arrivalsStarted
    // The arrival this page has already turned. An arrival is not one request: the artwork is asked
    // for again when it resolves from a video thumbnail to a higher-resolution image of the same
    // cover, and a turn keyed on the request would turn the page a second time for one skip.
    val turnedFor = memory.turnedFor
    // True for the length of a turn. Held so that the cover *under* the turning page is kept: it is
    // what the turning page is read against, and it is what makes the arrival a page coming over
    // rather than a cover appearing.
    val turning = memory.turning
    // 1f is turned away, 0f is the cover lying flat. Created per arrival and turned away *from its
    // first frame*, not by the effect that runs after it: the frame the arriving cover decodes on is
    // the frame it is first drawn on, and a cover drawn flat for even one frame is the whole of
    // "it switched to the new song, then it switched back to the last one, then it moved".
    val arrival =
        remember(trackKey) {
            mutableFloatStateOf(
                memory.handover.value
                    ?: if (arrivalsStarted.value && turn && !animationsDisabled) TURNED_AWAY else FLAT,
            )
        }
    // Resolved per arrival, in composition, for the same reason: the hinge and the direction the
    // page falls are read by the first frame it is drawn on.
    val arrivalDirection = remember(trackKey) { direction() }
    // How much of the cover is showing: a layer that is read every frame gets this from the loop
    // below, everything else keeps Compose's fade, which is the whole of the arrival for a layer
    // nobody is watching.
    val presence = remember(trackKey) { mutableFloatStateOf(if (animated) 0f else 1f) }

    // Keyed on the track, and on nothing else.
    //
    // Not on `loaded`. An effect that restarted when the cover finished decoding could cancel a
    // turn that was already half over - and an arrival is not one request anyway: the same track
    // asks for its cover twice, a video thumbnail and then the high-resolution image, so a restart
    // on decode was a second arrival for a single skip. The track change is the only moment an
    // arrival starts.
    LaunchedEffect(trackKey) {
        // One decision per arrival, from the rules in [trackChangeArrival]: a track brought in by
        // a gesture turns; the first composition settles; and no arrival plays twice for one track.
        // Both are on the frame clock, so they happen on a device that has Compose's own animations
        // switched off instead of popping.
        val arrivalKind =
            trackChangeArrival(
                arrivalsStarted = arrivalsStarted.value,
                turn = turn,
                animated = animated,
                animationsDisabled = animationsDisabled,
                played = turnedFor.value == trackKey,
            )
        // Consumed by whichever arrival this is, so a hand-over can never be left lying around for
        // the following track to start halfway through.
        val handedOver = memory.handover.value
        memory.handover.value = null
        // The panel's copy of this decision, taken here and not derived from the frame that draws
        // it: which arrival this is and where it starts are what a track change has to be
        // explained with, and neither is on screen afterwards - the page is flat again by then.
        //
        // Debug builds only, exactly like the panel it belongs to; see [PlayerDiagnosticsOverlay].
        // Left ungated it was the one piece of a track change that a shipped build still ran: Hush
        // plants [app.hush.music.utils.GlobalLogTree] in every build, so this formatted a line per
        // arrival - a queue of them per skip - into the buffer a release build keeps for the field,
        // where nothing can read it against a panel that is not drawn there.
        if (BuildConfig.DEBUG) {
            timber.log.Timber.tag("ARRIVAL").d(
                "kind=%s key=%s animated=%s turn=%s handedOver=%s diagnostics=%s enabled=%s",
                arrivalKind,
                trackKey,
                animated,
                turn,
                handedOver,
                diagnostics != null,
                diagnostics?.enabled,
            )
        }
        diagnostics?.arrivalStarted(
            trackKey,
            arrivalKind,
            arrivalDirection,
            arrivalStartProgress(arrivalKind, handedOver),
        )
        if (arrivalKind == TrackChangeArrival.TURN) {
            turnedFor.value = trackKey
            turning.value = true
            // A swipe can hand this arrival part of its turn: the finger carried the page over and
            // the track changed under it, so the turn carries on from where the finger left it. Doing
            // the whole turn again, from turned away, is the animation that arrived *after* the song
            // had changed and its cover was already showing - a second motion for one gesture.
            arrival.floatValue = handedOver ?: TURNED_AWAY
            // The turn is its own arrival: it does not also fade, or the page would be a ghost for
            // its first quarter while it is already coming over it.
            presence.floatValue = 1f
            // The turn is timed from the clock and from nothing else: it takes the same time whether
            // the cover is already cached or still arriving, and the cover is drawn into it whenever
            // it lands. Loading is not part of the movement, so it no longer sets the movement's pace.
            //
            // It used to wait for the cover before starting, and to advance by the length of each
            // frame with a cap on how much of the turn one frame might carry. Both made the same
            // skip a different length: a cached cover started the turn at once where a cover that
            // took a moment spent that moment standing still, and the heavy frames around a decode -
            // which is what a skip is made of - let the turn fall behind its own clock.
            //
            // Started where a finger left it: a page carried part of the way over has also spent
            // that part of the turn's time, and only the rest is left to run.
            val offsetMs = handedOver?.let { turnElapsedFor(it) } ?: 0L
            var startNanos = 0L
            while (true) {
                val running =
                    withFrameNanos { frameTimeNanos ->
                        if (startNanos == 0L) startNanos = frameTimeNanos - offsetMs * 1_000_000L
                        val elapsed = (frameTimeNanos - startNanos) / 1_000_000L
                        arrival.floatValue = turnProgress(elapsed)
                        diagnostics?.arrivalFrame(arrival.floatValue)
                        elapsed < TRACK_CHANGE_TURN_MS
                    }
                if (!running) break
            }
            arrival.floatValue = FLAT
            turning.value = false
            diagnostics?.arrivalSettled()
        } else if (arrivalKind == TrackChangeArrival.FADE) {
            // The arrival is spent, so that a later request for the same track - the same track asks
            // for its cover twice - cannot turn this page again.
            turnedFor.value = trackKey
            // Timed from the clock like the turn, and not held back for the cover either: the fade is
            // a fixed part of a track change, and the cover is whatever is there when it is drawn.
            var startNanos = 0L
            while (true) {
                val running =
                    withFrameNanos { frameTimeNanos ->
                        if (startNanos == 0L) startNanos = frameTimeNanos
                        val elapsed = (frameTimeNanos - startNanos) / 1_000_000L
                        presence.floatValue =
                            (elapsed / TRACK_CHANGE_FADE_MS.toFloat()).coerceIn(0f, 1f)
                        diagnostics?.arrivalFrame(presence.floatValue)
                        elapsed < TRACK_CHANGE_FADE_MS
                    }
                if (!running) break
            }
            presence.floatValue = 1f
        } else {
            // Placed, not moved: a layer nobody reads the frames of, or motion switched off. The
            // cover belongs flat and showing.
            arrival.floatValue = FLAT
            turning.value = false
            if (animated) presence.floatValue = 1f
        }
        arrivalsStarted.value = true
    }

    val alpha by
        animateFloatAsState(
            targetValue = if (loaded) 1f else 0f,
            animationSpec = tween(durationMillis = TRACK_CHANGE_FADE_MS, easing = FastOutSlowInEasing),
            label = "track_artwork_alpha",
        )

    LaunchedEffect(loaded, turning.value) {
        // Written only once the image is *decoded*: a request that was made is not a cover that
        // can stand in for the next one. And not while the page is turning: the cover before this
        // one is the page the turning page is turning over, so replacing it mid-turn would put a
        // blurred copy of the arriving cover underneath it and lose the turn's other half.
        if (loaded && !turning.value && baseModel.value != model) baseModel.value = model
    }

    // The panel's copy of what this page's cover is doing. Driven by the painter's own state, which
    // is what the fade above is driven by as well: the recomposition that notices a cover decode is
    // the one that reports it, and a request that was made is not a cover that is drawn.
    LaunchedEffect(painterState, animated) {
        if (animated) diagnostics?.cover(describePainterState(painterState), loaded)
    }

    Box(modifier = modifier.fillMaxSize()) {
        baseModel.value
            ?.takeIf { it != model }
            ?.let { previous ->
                Image(
                    painter = rememberAsyncImagePainter(model = previous),
                    contentDescription = null,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                )
            }

        Image(
            painter = painter,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val progress = heldProgress ?: arrival.floatValue
                        val changeDirection = arrivalDirection
                        if (turn) {
                            translationX =
                                progress * size.width * TRACK_CHANGE_SLIDE_FRACTION * changeDirection
                            // Pivoted on the edge it arrives over, so the cover comes into view
                            // from the side the queue is travelling from - the way the page under
                            // a turning sheet appears - and lies flat once it lands. Pivoting on
                            // the leading edge instead would show the cover arriving on the
                            // opposite side from the one it is arriving from.
                            transformOrigin = TransformOrigin(if (changeDirection >= 0) 1f else 0f, 0.5f)
                            cameraDistance = TRACK_CHANGE_CAMERA_DISTANCE_DP.dp.toPx()
                            // A positive rotationY leans whatever sits to the right of the pivot
                            // away from the viewer, so the far edge - the one opposite the pivot -
                            // is the one leaning back, and it has to lean back on both sides of
                            // the queue for the two turns to be mirror images.
                            rotationY = -progress * TRACK_CHANGE_FLIP_ANGLE * changeDirection
                        } else {
                            translationX = 0f
                            rotationY = 0f
                        }
                        // Until it decodes the arriving cover is invisible and the base underneath
                        // is what is seen; the fade is what makes the last step of the arrival a
                        // dissolve rather than a cut. The page being read is faded by the frame
                        // loop above instead, since Compose's fade holds its first frame when the
                        // device has animations switched off - a pop, where this is a fade.
                        this.alpha = if (animated) presence.floatValue else alpha
                    },
        )
    }
}
