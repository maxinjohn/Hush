/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import coil3.compose.AsyncImagePainter
import java.util.Locale

/**
 * Switch that puts [PlayerDiagnosticsOverlay] on the player. Debug builds only: the overlay is a
 * diagnosis aid for a change that lasts a third of a second, and a build that ships has no reason
 * to carry either the panel or the writes behind it.
 */
internal val PlayerDiagnosticsEnabledKey = booleanPreferencesKey("dev_show_track_change_diagnostics")

/**
 * How far a movement has to have travelled to count as happening at all.
 *
 * Every fact below is a fraction of one movement - a turn, or a fade - and they all sit at 0 while
 * nothing is moving and at 1 when it is over. A threshold rather than a comparison against zero,
 * because the last frame of a movement lands on a value a hair off the end, and a panel that
 * flickered between "turning" and "still" would be unreadable.
 */
internal const val PLAYER_DIAGNOSTICS_ACTIVITY = 0.05f

/**
 * The live state of a track change, as the pieces that produce it see it.
 *
 * Written by the three composables a change is made of - the page that turns ([TrackArtwork]), the
 * canvas that lies over it ([CanvasArtworkPlayer]) and the grid that holds their pages
 * ([Thumbnail]) - and read by [PlayerDiagnosticsOverlay] alone. It exists because the facts that
 * explain a track change are spread across those composables and not one of them can see the
 * others: whether the cover was still decoding while the page turned, whether the canvas was still
 * opaque over it as it turned, and whether the grid was on the page that is playing are each
 * decided inside a different composable, and the bugs they explain are visible only on a device, in
 * motion, for a third of a second.
 *
 * Every write is behind [enabled], so with the overlay off a writer pays a boolean read per frame
 * and nothing else: no state write, no recomposition, no allocation.
 */
internal class PlayerTrackChangeDiagnostics {
    /**
     * Whether the overlay is drawing. A plain field and not Compose state on purpose: it is read by
     * the writers and never drawn, so making it state would invalidate the whole player screen every
     * time it flipped.
     */
    var enabled: Boolean = false

    // The page's own arrival. Written once when it starts, per frame while it turns, and once when
    // it settles - this is the movement the panel exists to show, since it is over before a person
    // can look at anything.
    val arrivalTrackKey = mutableStateOf<Any?>(null)
    val arrivalKind = mutableStateOf(TrackChangeArrival.SETTLE)
    val arrivalDirection = mutableIntStateOf(TRACK_CHANGE_NEXT)
    // The page's own movement, whichever it is: a turn's progress (1 turned away, 0 flat), or the
    // cover's presence as it fades in on a surface's first track. [turning] separates the two.
    val arrivalProgress = mutableFloatStateOf(0f)
    val turning = mutableStateOf(false)

    /**
     * How many arrivals this page has played.
     *
     * Counted because one of the rules a track change is built on is that a track arrives *once* -
     * the same song asks for its artwork twice, and a second arrival for one skip is a page that
     * turns, comes back and turns again. A count is the only way to see that from a screenshot:
     * the movement itself is over by the time the next one is taken.
     */
    val arrivals = mutableIntStateOf(0)

    // The arriving cover, which is drawn into the turn whenever it lands.
    val coverState = mutableStateOf("empty")
    val coverLoaded = mutableStateOf(false)

    // The canvas, which is the whole of the artwork area when a track has one and is therefore the
    // layer a turn is read against.
    val canvasPresent = mutableStateOf(false)
    val canvasReady = mutableStateOf(false)
    val canvasAlpha = mutableFloatStateOf(0f)

    /** A page's arrival has begun: what it is, which way the queue moved, and where it starts. */
    fun arrivalStarted(
        trackKey: Any?,
        kind: TrackChangeArrival,
        direction: Int,
        progress: Float,
    ) {
        if (!enabled) return
        arrivalTrackKey.value = trackKey
        arrivalKind.value = kind
        arrivalDirection.value = direction
        arrivalProgress.value = progress
        turning.value = kind == TrackChangeArrival.TURN
        arrivals.value = arrivals.value + 1
    }

    /** One frame of an arrival, written from the page's own frame loop - once per frame, at most. */
    fun arrivalFrame(progress: Float) {
        if (!enabled) return
        arrivalProgress.value = progress
    }

    /** The arrival is over and the page is flat again. */
    fun arrivalSettled() {
        if (!enabled) return
        arrivalProgress.value = 0f
        turning.value = false
    }

    /** What the arriving cover is doing: [describePainterState] of its painter. */
    fun cover(
        state: String,
        loaded: Boolean,
    ) {
        if (!enabled) return
        coverState.value = state
        coverLoaded.value = loaded
    }

    /** The canvas layer is on screen: ready once it has painted a frame, at [alpha] as it fades in. */
    fun canvas(
        ready: Boolean,
        alpha: Float,
    ) {
        if (!enabled) return
        canvasPresent.value = true
        canvasReady.value = ready
        canvasAlpha.value = alpha
    }

    /**
     * The canvas layer has left the composition - the track changed to one without a canvas, or the
     * switch was turned off. Without this the panel would keep reporting the last canvas ready and
     * opaque over artwork that no longer has one.
     */
    fun canvasDetached() {
        if (!enabled) return
        canvasPresent.value = false
        canvasReady.value = false
        canvasAlpha.value = 0f
    }
}

/**
 * A cover painter's state, in a word.
 *
 * [TrackArtwork] draws the page it is given whether or not its image has decoded - the cover that
 * was there before is under it - so "the artwork is not what the panel is showing yet" is a state
 * the panel has to name rather than a fact the screen shows.
 */
internal fun describePainterState(state: AsyncImagePainter.State): String =
    when (state) {
        is AsyncImagePainter.State.Success -> "ready"
        is AsyncImagePainter.State.Loading -> "loading"
        is AsyncImagePainter.State.Error -> "error"
        AsyncImagePainter.State.Empty -> "empty"
    }

/**
 * A track key as the panel shows it: enough to tell one arrival from the next.
 *
 * Keys are media ids, or an artwork url when a track carries no id - and the tail of either is the
 * part that differs between two tracks, where the head is shared boilerplate.
 */
internal fun shortTrackKey(key: Any?): String {
    val text = key?.toString() ?: return "-"
    if (text.isEmpty()) return "-"
    return if (text.length <= 12) text else "…" + text.takeLast(12)
}

/**
 * Whether the grid is sitting on the page that is playing.
 *
 * The one condition behind the artwork showing two tracks at once, cut down the middle: the grid
 * animates to the playing page, the page width follows the artwork's own size, and a re-layout
 * under that scroll cancels it with the artwork left between two pages until something else moves
 * it. Both halves are needed - a correct page index with a non-zero offset is the same split, a few
 * pixels wide.
 */
internal fun gridPageIsAligned(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    currentSlotIndex: Int,
): Boolean = firstVisibleItemIndex == currentSlotIndex && firstVisibleItemScrollOffset == 0

/**
 * The track-change bugs this panel was written for, named.
 *
 * A reader comparing four numbers under a moving cover is a reader who will not notice the one that
 * was wrong, so the panel says which shape the change is in: the artwork split between two pages,
 * the canvas still opaque over a page that is turning (the live-action frame beside the cover), or
 * a turn running with no cover to turn.
 */
internal fun trackChangeWarnings(
    gridAligned: Boolean,
    canvasAlpha: Float,
    turning: Boolean,
    arrivalProgress: Float,
    coverLoaded: Boolean,
): List<String> =
    buildList {
        if (!gridAligned) add("split-page")
        if (turning && arrivalProgress > PLAYER_DIAGNOSTICS_ACTIVITY &&
            canvasAlpha > PLAYER_DIAGNOSTICS_ACTIVITY
        ) {
            add("canvas-over-turn")
        }
        if (turning && arrivalProgress > PLAYER_DIAGNOSTICS_ACTIVITY && !coverLoaded) {
            add("cover-not-loaded")
        }
    }

/**
 * The player screen's own half of the readout.
 *
 * Bundled rather than passed one by one so that [PlayerDiagnosticsOverlay] keeps a signature that
 * says what it shows. Only the state object is passed *unread*: the page index and the offset are
 * read inside the panel, so that a scroll recomposes the panel and nothing else. The rest the
 * caller has already read to lay the grid out.
 */
internal data class PlayerDiagnosticsGrid(
    val state: LazyGridState,
    /** The page slot the track is playing from; the grid should be on it. */
    val currentSlotIndex: Int,
    val pageCount: Int,
    /** The platform's animator duration scale is 0; see the grid alignment in [Thumbnail]. */
    val animationsOff: Boolean,
)

/** Which way a change was asked for: the button's turn, a finger's drag, or nothing. */
internal enum class PlayerDiagnosticsGesture {
    IDLE,
    /** The frame-clock turn a skip button starts; see the hand-over in [Thumbnail]. */
    TURN,
    /** A finger on the page, which owns the turn for as long as it is down. */
    DRAG,
}

/**
 * The finger - or the button standing in for one - as the overlay reads it.
 *
 * `held` is the layer's progress, 1 turned away and 0 flat, and the button and the finger move the
 * same state; `handedOver` is the moment the track changed underneath it, and `layerShowing` is
 * whether the finger's own copy of the cover is still the top layer or the page has taken over.
 */
internal data class PlayerDiagnosticsFinger(
    val gesture: PlayerDiagnosticsGesture,
    val direction: Int,
    val held: Float,
    val handedOver: Boolean,
    val layerShowing: Boolean,
)

/**
 * The track change, drawn over the artwork as it happens.
 *
 * Off unless a debug build has it switched on; see [PlayerDiagnosticsEnabledKey]. Everything it
 * shows is decided somewhere else - half of it inside composables this one cannot see, which is why
 * it goes through [PlayerTrackChangeDiagnostics] instead of being passed in.
 *
 * Its caller is what keeps it off, and nothing else, so that a build with the overlay off does not
 * even build the bundles below - see the guard in [Thumbnail]. Its own reads are here, at the
 * bottom of the chain, so that switching the overlay on subscribes the panel and nothing else.
 *
 * The lines are deliberately dense and monospaced: the whole point is to read the state of a change
 * from a screenshot, after the fact, on a device nobody is holding.
 */
@Composable
internal fun PlayerDiagnosticsOverlay(
    diagnostics: PlayerTrackChangeDiagnostics,
    grid: PlayerDiagnosticsGrid,
    finger: PlayerDiagnosticsFinger,
    trackId: String?,
    queueIndex: Int,
    modifier: Modifier = Modifier,
) {
    val arrivalTrackKey by diagnostics.arrivalTrackKey
    val arrivalKind by diagnostics.arrivalKind
    val arrivalDirection by diagnostics.arrivalDirection
    val arrivalProgress by diagnostics.arrivalProgress
    val arrivals by diagnostics.arrivals
    val turning by diagnostics.turning
    val coverState by diagnostics.coverState
    val coverLoaded by diagnostics.coverLoaded
    val canvasPresent by diagnostics.canvasPresent
    val canvasReady by diagnostics.canvasReady
    val canvasAlpha by diagnostics.canvasAlpha

    val pageIndex = grid.state.firstVisibleItemIndex
    val pageOffset = grid.state.firstVisibleItemScrollOffset
    val scrollInProgress = grid.state.isScrollInProgress
    val aligned = gridPageIsAligned(pageIndex, pageOffset, grid.currentSlotIndex)
    val warnings =
        trackChangeWarnings(
            gridAligned = aligned,
            canvasAlpha = canvasAlpha,
            turning = turning,
            arrivalProgress = arrivalProgress,
            coverLoaded = coverLoaded,
        )

    val text =
        buildString {
            append("TRACK   ").append(trackId?.take(14) ?: "-").append("  #").append(queueIndex)
            append('\n')
            append("ARRIVAL ").append(fraction(arrivalProgress))
                .append("  dir ").append(if (arrivalDirection >= 0) "+1" else "-1")
                .append("  ").append(arrivalKind.name)
                .append(if (turning) "  turning" else "  flat")
                .append("  n").append(arrivals)
                .append("  key ").append(shortTrackKey(arrivalTrackKey))
            append('\n')
            append("COVER   ").append(coverState)
            append('\n')
            append("CANVAS  present ").append(yesNo(canvasPresent))
                .append("  ready ").append(yesNo(canvasReady))
                .append("  fade ").append(fraction(canvasAlpha))
            append('\n')
            append("GRID    page ").append(pageIndex + 1).append('/').append(grid.pageCount)
                .append("  slot ").append(grid.currentSlotIndex)
                .append("  off ").append(pageOffset)
                .append(if (scrollInProgress) "  scroll" else "")
                .append(if (grid.animationsOff) "  anims-off" else "")
                .append("  ").append(if (aligned) "aligned" else "SPLIT")
            append('\n')
            append("FINGER  ").append(finger.gesture.name)
                .append("  dir ").append(if (finger.direction >= 0) "+1" else "-1")
                .append("  held ").append(fraction(finger.held))
                .append(if (finger.handedOver) "  handed-over" else "")
                .append(if (finger.layerShowing) "  layer" else "")
            append('\n')
            append("WARN    ").append(warnings.ifEmpty { listOf("-") }.joinToString(", "))
        }

    Text(
        text = text,
        color = Color.White,
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        lineHeight = 12.sp,
        modifier =
            modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

private fun fraction(value: Float): String = String.format(Locale.US, "%.2f", value)
