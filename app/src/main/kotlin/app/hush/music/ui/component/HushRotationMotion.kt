/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.component

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import app.hush.music.LocalAnimationsDisabled
import kotlin.math.cos
import kotlin.math.exp

/**
 * The scale the layout is laid down at when the phone is turned, on its way to 1.
 *
 * Portrait and landscape are two different layouts, not one layout re-measured: the player alone
 * draws six different trees behind that choice. Handled by `configChanges`, so the activity is not
 * recreated and nothing is on screen between the two - the old tree is gone and the new one is
 * already at its final size on the very next frame, which is why a turn read as a cut. Starting
 * the arriving tree slightly small is what turns the cut into a settle.
 */
private const val ROTATION_FROM_SCALE = 0.94f

/** How much of the arriving layout is visible on its first frame, before it settles to full. */
private const val ROTATION_FROM_ALPHA = 0.55f

/**
 * How far the arriving layout rides up from below, as a fraction of its own height.
 *
 * The turn itself already moves everything on screen, so this is small on purpose: it is the depth
 * that tells a person the new layout *arrived* rather than the old one simply becoming the new one.
 */
private const val ROTATION_FROM_TRAVEL = 0.045f

/**
 * The angle the arriving layout is laid down at when the phone is turned, in degrees, on its way
 * to 0.
 *
 * Android reports a turn as a new `Configuration` and nothing else: the way the phone physically
 * went round is not in it, so this cannot lean the way the hand did. What *is* in it is which of
 * the two layouts is arriving, so the lean is keyed to that - a layout arriving in landscape is
 * laid down leaning one way, one arriving in portrait the other. The two turns are therefore
 * mirror images of each other and each is the same every time, which is what lets an eye read the
 * lean as the turn rather than as a stray wobble. The bounce below carries it the rest of the way:
 * the curve overshoots past upright, so the layout rocks back through zero and holds.
 *
 * Small, and smaller than it wants to be, because the layout being turned is most of a screen tall.
 * A rotation moves a corner by half the *other* dimension: at 7 degrees the top of a portrait player
 * is a hundred pixels off its own right edge, which walks the close button off the screen and lets
 * the screen's edge cut the bottom bar - a turn that looks broken rather than bouncy. Two degrees is
 * the angle at which the whole titled layout still fits inside the display it was laid down in.
 */
private const val ROTATION_FROM_ANGLE = 2f

/**
 * The settle's own window.
 *
 * Fixed rather than "until the motion is spent", because the motion is spent by an analytic curve
 * and the last of it is sub-pixel: a fixed, short window keeps the frame loop to the part of the
 * settle a person can actually see, and cannot leave the loop running.
 */
private const val ROTATION_SETTLE_MS = 420L

/**
 * The most of the settle a single frame may carry, in milliseconds.
 *
 * The settle advances by the length of each frame rather than by the clock, so that the frame after
 * a turn - the one the whole new layout is measured and laid out on, which is the most expensive
 * frame of the turn - holds the layout where it is instead of being skipped over. Two frames'
 * worth is the cap: a dropped frame stays smooth, and a stall stops the clock instead of the turn.
 */
private const val ROTATION_SETTLE_MAX_FRAME_MS = 33L

/** One turn of the damped oscillation below, in radians. */
private const val TWO_PI = 6.2831853f

/**
 * The settle at [elapsedMillis] since the turn: 0 is the arriving layout laid down, 1 is it holding
 * still.
 *
 * Frame time, not a Compose animation, and not for style: with `animator_duration_scale = 0` a
 * Compose animation paints one frame and holds it, and a held settle is a player left small, dim
 * and off upright on the screen. The transport's wiggle and the aura cover's breath are written the
 * same way for the same reason.
 *
 * What is left is a damped oscillation that rises to 1, overshoots it and settles back. The
 * overshoot is the whole effect: a curve that merely arrives reads as a resize, and a layout that
 * rocks past upright and returns - every button with it - reads as a thing that was moved.
 */
private fun settleProgress(elapsedMillis: Long): Float {
    if (elapsedMillis >= ROTATION_SETTLE_MS) return 1f
    if (elapsedMillis <= 0L) return 0f
    val t = elapsedMillis.toFloat() / ROTATION_SETTLE_MS
    return 1f - exp(-9f * t) * cos(TWO_PI * 1.5f * t)
}

/**
 * Settles a layout that changes shape when the phone is turned.
 *
 * The transition is driven entirely inside a layer: the settle's progress is read in the
 * `graphicsLayer` block and never in composition, so a frame of the settle costs a layer update
 * rather than a recomposition of the screen under it - which, on the player, is a full player tree.
 * The frame loop that advances it is spent in [ROTATION_SETTLE_MS].
 *
 * The bounce is deliberate and is the whole point of the effect: a curve that merely arrives reads
 * as a resize, and the overshoot is what makes the layout - and every button in it - give a short,
 * legible jiggle and then hold still. Nothing is drawn outside the layout's own bounds, so the
 * overshoot is a movement, not an overflow.
 *
 * The settle is a turn as well as a settle: the arriving layout is laid down [ROTATION_FROM_ANGLE]
 * off upright and rocks through zero on the way in, which is the one part of a phone being turned
 * that Android does not animate for a `configChanges` activity. It holds for the same reason the
 * scale does - a rotation of a layout whose corners already sit inside the smaller scale never
 * shows an edge.
 *
 * The first composition never animates: opening the player for the first time is its own entrance,
 * and an extra settle over it would be one motion too many.
 *
 * @param key the layout identity being settled. Two things that re-shape for a different reason -
 *   a window resize, a size class flip - can pass their own key so only a real turn settles.
 */
@Composable
fun Modifier.hushRotationSettle(key: Any? = null): Modifier {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val animationsDisabled = LocalAnimationsDisabled.current
    // The configuration is read here, in composition, on purpose: it is what says a turn happened,
    // and the read costs one recomposition of the layout being settled - which has just been
    // re-measured for the new shape anyway.
    val settle = remember { mutableFloatStateOf(1f) }
    // Held, not re-created: a holder keyed on [landscape] would be rebuilt *by* the turn it is
    // meant to notice, and the settle would see its own new value as the one it started from.
    val previousLandscape = remember { mutableStateOf<Boolean?>(null) }
    val trigger = if (key == null) landscape else landscape to key
    // Keyed to the layout that is *arriving*: see [ROTATION_FROM_ANGLE]. Read here, in composition,
    // next to the configuration read it comes from, and captured by the layer below for the whole
    // of that turn.
    val fromAngle = if (landscape) ROTATION_FROM_ANGLE else -ROTATION_FROM_ANGLE

    LaunchedEffect(trigger) {
        val previous = previousLandscape.value
        previousLandscape.value = landscape
        if (previous == null || previous == landscape) return@LaunchedEffect
        if (animationsDisabled) return@LaunchedEffect
        // Laid down before the first frame of the loop, not by it: a loop's first write lands on the
        // frame *after* the one the new layout was measured on, and that frame would otherwise show
        // the arriving layout already settled, flat and full.
        settle.floatValue = 0f
        var elapsed = 0L
        var previousFrameNanos = 0L
        while (true) {
            val running =
                withFrameNanos { frameTimeNanos ->
                    if (previousFrameNanos != 0L) {
                        val frameMillis = (frameTimeNanos - previousFrameNanos) / 1_000_000L
                        elapsed += frameMillis.coerceIn(0L, ROTATION_SETTLE_MAX_FRAME_MS)
                    }
                    previousFrameNanos = frameTimeNanos
                    settle.floatValue = settleProgress(elapsed)
                    elapsed < ROTATION_SETTLE_MS
                }
            if (!running) break
        }
        settle.floatValue = 1f
    }

    return graphicsLayer {
        val progress = settle.floatValue
        alpha = (ROTATION_FROM_ALPHA + (1f - ROTATION_FROM_ALPHA) * progress).coerceIn(0f, 1f)
        val scale = ROTATION_FROM_SCALE + (1f - ROTATION_FROM_SCALE) * progress
        scaleX = scale
        scaleY = scale
        rotationZ = fromAngle * (1f - progress)
        translationY = (1f - progress) * size.height * ROTATION_FROM_TRAVEL
    }
}
