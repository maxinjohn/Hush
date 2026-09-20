/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.component

import android.os.SystemClock
import app.hush.music.BuildConfig
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import app.hush.music.LocalAnimationsDisabled
import app.hush.music.ui.theme.HushDesign
import kotlin.math.exp
import kotlin.math.sin

/**
 * What a button does when it is pressed: it gives, it springs, and it throws a ring.
 *
 * This is deliberately **not** built on `animateFloatAsState`. Every press animation in the app
 * used to be, and on a device with the system animator duration scale at 0 - which is what the
 * reporting device reports, and what a car head unit runs with - Compose scales such an animation
 * to nothing: the button jumped between two sizes and no ripple ever appeared. "The buttons look
 * static, like an old design" is that behaviour seen from outside.
 *
 * The curves are pure functions of elapsed time, so they can be checked without a device, and the
 * composable below advances them from `withFrameNanos`, which no system setting can silence.
 *
 * The shape of a press:
 *
 *  - **down** - a fast, slightly-eased contract towards the press scale (~95ms) with a sideways
 *    squash, so the button behaves like a soft object instead of a still image;
 *  - **up** - a bouncy release: past its own size, then wobbling back like a spring let go;
 *  - **a ring** - a halo expanding out of the button and fading, in two staggered waves, so a tap
 *    reads as a splash rather than a single blink. It starts on the *press* rather than the
 *    release, so even the quickest tap shows the whole effect.
 *
 * The halo is a fraction of the button's own short side and is drawn in the button's own layer, so
 * one implementation fits a 36dp mini player control and a 96dp play button with nothing tuned by
 * hand.
 */
object PressMotion {

    /** How long the button takes to give under the finger. */
    const val PRESS_IN_MILLIS = 95L

    /** How long the release wobble lasts. The last frame lands exactly on 1.0. */
    const val RELEASE_MILLIS = 460L

    /** How long one halo ring lives. */
    const val HALO_MILLIS = 520L

    /** The second ring leaves this long after the first. */
    const val HALO_STAGGER_MILLIS = 150L

    /** How far past its own size the button travels on release. */
    const val BOUNCE_AMPLITUDE = 0.13f

    /** How many wobbles the release makes. */
    const val BOUNCE_CYCLES = 3.2f

    /** How quickly the wobble dies away: higher settles sooner. */
    const val BOUNCE_DECAY = 3.6f

    /** Sideways squash while pressed, as a fraction of how far the button has contracted. */
    const val SQUASH_RATIO = 0.32f

    /** Where a halo ring begins and ends, as a multiple of the button's short side. */
    const val HALO_START_FRACTION = 0.50f
    const val HALO_END_FRACTION = 1.30f

    /** The halo's opacity at the moment it appears, before the caller's strength is applied. */
    const val HALO_PEAK_ALPHA = 0.42f

    /**
     * How far into its life a ring still has to be clearly readable.
     *
     * Asserted against both the radius and the fade: whatever else changes here, by this point the
     * ring must have cleared the button's own edge and still have some opacity left.
     */
    const val HALO_OPACITY_STILL_VISIBLE = 0.45f

    /**
     * The largest control that still reads as a button, in dp, for the halo's sake.
     *
     * A burst belongs on a button, not on a list row or a card: the ring is a circle that grows out
     * of the control's centre, so on anything much taller than a thumb target it stops reading as an
     * aura and becomes a disc floating in the middle of the surface. The full player's own play
     * button is the biggest control that should keep one (84dp), which is what sets this.
     */
    const val HALO_MAX_SHORT_SIDE_DP = 96f

    /**
     * How much longer than it is tall a control may be and still keep a ring.
     *
     * Square is what a ring wants. The moment a surface is a row, a pill or a card, its centre is no
     * longer the thing being pressed, and the ring would be clipped by the surface's own shape or
     * spill over whatever sits beside it.
     */
    const val HALO_MAX_ASPECT = 1.8f

    /**
     * Whether a surface of this shape should throw a ring at all.
     *
     * Rows and cards still give and spring - the size change is the feedback there - they just do not
     * splash. This is the rule the call sites used to have to remember by hand, and the reason
     * forty-eight of them silently had no press animation beyond a hard size jump.
     */
    fun haloSuits(
        shortSideDp: Float,
        longSideDp: Float,
    ): Boolean {
        if (shortSideDp <= 0f) return false
        return shortSideDp <= HALO_MAX_SHORT_SIDE_DP && longSideDp <= shortSideDp * HALO_MAX_ASPECT
    }

    /**
     * Below this short side, a ring is too small to read at full strength and gets [haloVisibilityBoost].
     *
     * Chosen from measurement rather than taste: 72dp is under the smallest control whose ring was
     * measured as clearly visible (the playlist header's 56dp buttons put 8228 changed pixels outside
     * their paint; the full player's transport row, which is 58-76dp, put 13871), and above the two
     * that did not (the mini player's 40dp play and 36dp skip controls).
     */
    const val HALO_SMALL_SHORT_SIDE_DP = 72f

    /** How much extra opacity the smallest control gets. 60% is audible rather than subtle. */
    const val HALO_SMALL_STRENGTH_BOOST = 0.6f

    /**
     * A halo's opacity multiplier for a control's size.
     *
     * The ring is a fraction of the button's own short side, so a small button throws a
     * proportionally small one - and the *area* it covers, which is what the eye integrates, falls
     * with the square of that. Measured on the reporting device, the same animation read two ways: a
     * 40dp play button showed 679-1599 changed pixels outside its paint at a delta of 505, while the
     * 36dp skip controls beside it showed 17-484 at a delta of 45-55. Two of the three controls a
     * thumb reaches without looking, with the same code and the same shape rule (`haloSuits` is true
     * for all three), looked inert.
     *
     * So a shrinking control gets a brighter ring, tapering to nothing at [HALO_SMALL_SHORT_SIDE_DP].
     * That bound is what makes this safe to change at all: at and above it the multiplier is exactly
     * 1, so the player's transport row, its action circles and the playlist header - every control
     * whose ring has already been measured and kept - are untouched by construction.
     */
    fun haloVisibilityBoost(shortSideDp: Float): Float {
        if (shortSideDp >= HALO_SMALL_SHORT_SIDE_DP) return 1f
        if (shortSideDp <= 0f) return 1f + HALO_SMALL_STRENGTH_BOOST
        val shortfall = (HALO_SMALL_SHORT_SIDE_DP - shortSideDp) / HALO_SMALL_SHORT_SIDE_DP
        return 1f + HALO_SMALL_STRENGTH_BOOST * shortfall
    }

    /**
     * The button's scale while the finger is down.
     *
     * Total: a negative elapsed time (a clock that stepped backwards) reads as the start of the
     * press rather than as some other size.
     */
    fun pressedScale(
        elapsedMillis: Long,
        pressScale: Float,
    ): Float {
        val target = pressScale.coerceIn(0.5f, 1f)
        val progress = easeOutCubic(unitProgress(elapsedMillis, PRESS_IN_MILLIS))
        return 1f - (1f - target) * progress
    }

    /**
     * The button's scale after the finger comes up: from where the press left it, back to its own
     * size, overshooting and settling.
     *
     * [fromScale] is the scale at the moment of release, so a flicked tap that never reached the
     * full press depth still springs from where it actually was, instead of jumping to a deeper
     * press on the way out.
     */
    fun releasedScale(
        elapsedMillis: Long,
        fromScale: Float,
        pressScale: Float,
    ): Float {
        if (elapsedMillis >= RELEASE_MILLIS) return 1f
        val start = fromScale.coerceIn(0.5f, 1.4f)
        val base = start + (1f - start) * easeOutCubic(unitProgress(elapsedMillis, RELEASE_MILLIS))
        return base + bounce(elapsedMillis, pressScale)
    }

    /**
     * The release wobble on its own: zero at the moment of release, then decaying.
     *
     * Capped so the overshoot can never take the button past [maxScale] whatever scale the press
     * asked for - a bouncy release must not make a small button briefly enormous.
     */
    fun bounce(
        elapsedMillis: Long,
        pressScale: Float = 0.9f,
        maxScale: Float = 1.16f,
    ): Float {
        if (elapsedMillis <= 0L || elapsedMillis >= RELEASE_MILLIS) return 0f
        val t = elapsedMillis.toFloat() / RELEASE_MILLIS.toFloat()
        val raw = BOUNCE_AMPLITUDE * exp(-BOUNCE_DECAY * t) * sin(TWO_PI * BOUNCE_CYCLES * t)
        val headroom = (maxScale - pressScale.coerceIn(0.5f, 1f)).coerceAtLeast(0f)
        return raw.coerceIn(-headroom, headroom)
    }

    /**
     * Sideways squash for a given scale: nothing at rest, proportional to how far the button has
     * contracted, so a press reads as a press rather than as a size change.
     */
    fun squash(scale: Float): Float = ((1f - scale) * SQUASH_RATIO).coerceIn(0f, 0.12f)

    /**
     * How far a halo ring has travelled, 0 until 1, or null when it is not alive.
     *
     * The stagger is this same curve asked a little later, which is why one function serves both
     * rings.
     */
    fun haloProgress(
        elapsedMillis: Long,
        staggerMillis: Long = 0L,
    ): Float? {
        if (elapsedMillis < 0L) return null
        val age = elapsedMillis - staggerMillis
        if (age < 0L || age >= HALO_MILLIS) return null
        return age.toFloat() / HALO_MILLIS.toFloat()
    }

    /** A ring's opacity over its life: starts at its peak and is gone by the end. */
    fun haloAlpha(
        progress: Float,
        strength: Float = 1f,
    ): Float {
        val u = progress.coerceIn(0f, 1f)
        val fade = (1f - u) * (1f - u)
        return (HALO_PEAK_ALPHA * strength * fade).coerceIn(0f, 1f)
    }

    /**
     * A ring's radius over its life, as a multiple of the button's short side.
     *
     * Eased out rather than linear. The ring starts *under* the button and has to clear its edge
     * before any of it can be seen, and a linear expansion only got there in the last quarter of its
     * life - where the fade had already taken it down to about three percent, so a wide button (the
     * transport row's 312x232 controls) drew a ring nobody could see. Easing out crosses the button's
     * own edge while there is still most of the fade left.
     */
    fun haloRadiusFraction(progress: Float): Float {
        val u = progress.coerceIn(0f, 1f)
        val eased = 1f - (1f - u) * (1f - u) * (1f - u)
        return HALO_START_FRACTION + (HALO_END_FRACTION - HALO_START_FRACTION) * eased
    }

    private fun unitProgress(
        elapsedMillis: Long,
        durationMillis: Long,
    ): Float = (elapsedMillis.toFloat() / durationMillis.toFloat()).coerceIn(0f, 1f)

    private fun easeOutCubic(t: Float): Float {
        val inv = 1f - t
        return 1f - inv * inv * inv
    }

    private const val TWO_PI = 6.2831855f
}

/** Sentinel for "this ring is not alive this frame", so no list is allocated per frame. */
private const val NO_RING = -1f

/** Temporary: rate limit for the halo trace in [hushPressLayer]. */
private var lastHaloTraceAt = 0L

private const val NO_HALO = -1L

/**
 * A button's press state for one frame.
 *
 * [ringA] and [ringB] are the two staggered halos, each either a progress in 0 until 1 or
 * [NO_RING]. Two floats rather than a list: this is read on every frame of a press, and allocating
 * on the animation path is how a press animation turns into garbage collector pressure.
 */
@Immutable
data class HushPressFeedback(
    val scale: Float = 1f,
    val squash: Float = 0f,
    val pressed: Boolean = false,
    val ringA: Float = NO_RING,
    val ringB: Float = NO_RING,
) {
    val hasRing: Boolean get() = ringA != NO_RING || ringB != NO_RING
}

/**
 * Follows an interaction source and returns what the button should look like right now.
 *
 * The frame loop ends as soon as there is nothing left to show, so an idle screen costs no frames -
 * the same discipline as [rememberFramePhase].
 *
 * When the app's own "disable animations" setting is on, nothing here runs at all: the setting is
 * a promise, and a press effect that jumped sizes instantly would break it just as loudly as a
 * smooth one. That is also how every press animation in Hush behaved before this file existed.
 */
@Composable
fun rememberHushPressFeedback(
    interactionSource: MutableInteractionSource,
    pressScale: Float = HushDesign.PressScale,
    enabled: Boolean = true,
    haloStrength: Float = 1f,
): HushPressFeedback {
    // Checked before anything is remembered, so turning the setting on removes this whole block from
    // the composition rather than leaving state stranded behind an early return.
    if (LocalAnimationsDisabled.current) return HushPressFeedback()
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressed = isPressed && enabled

    var scale by remember { mutableFloatStateOf(1f) }
    var ringA by remember { mutableFloatStateOf(NO_RING) }
    var ringB by remember { mutableFloatStateOf(NO_RING) }
    var haloStartedAt by remember { mutableLongStateOf(NO_HALO) }

    LaunchedEffect(pressed, pressScale, haloStrength) {
        val target = pressScale.coerceIn(0.5f, 1f)
        if (pressed) {
            val startedAt = SystemClock.elapsedRealtime()
            if (haloStrength > 0f) haloStartedAt = startedAt
            // Cancelled when the press ends, which is how this loop terminates - the same shape as
            // rememberFramePhase.
            //
            // The rings are advanced here as well as on release. A press held for longer than a
            // ring's life used to draw none at all: the clock started on the press but the rings
            // were only read once the finger came up, by which time they had already expired, so
            // the same button that splashed on a tap was silent on a hold.
            while (true) {
                withFrameNanos {
                    val sincePress = SystemClock.elapsedRealtime() - haloStartedAt
                    scale = PressMotion.pressedScale(SystemClock.elapsedRealtime() - startedAt, target)
                    ringA =
                        if (haloStartedAt == NO_HALO) {
                            NO_RING
                        } else {
                            PressMotion.haloProgress(sincePress) ?: NO_RING
                        }
                    ringB =
                        if (haloStartedAt == NO_HALO) {
                            NO_RING
                        } else {
                            PressMotion.haloProgress(sincePress, PressMotion.HALO_STAGGER_MILLIS) ?: NO_RING
                        }
                }
            }
        } else {
            val from = scale
            val releasedAt = SystemClock.elapsedRealtime()
            while (true) {
                val keepGoing =
                    withFrameNanos {
                        val elapsed = SystemClock.elapsedRealtime() - releasedAt
                        scale = PressMotion.releasedScale(elapsed, from, target)
                        val sincePress =
                            if (haloStartedAt == NO_HALO) NO_HALO else SystemClock.elapsedRealtime() - haloStartedAt
                        ringA =
                            if (sincePress == NO_HALO) {
                                NO_RING
                            } else {
                                PressMotion.haloProgress(sincePress) ?: NO_RING
                            }
                        ringB =
                            if (sincePress == NO_HALO) {
                                NO_RING
                            } else {
                                PressMotion.haloProgress(sincePress, PressMotion.HALO_STAGGER_MILLIS) ?: NO_RING
                            }
                        elapsed < PressMotion.RELEASE_MILLIS || ringA != NO_RING || ringB != NO_RING
                    }
                if (!keepGoing) break
            }
            scale = 1f
            ringA = NO_RING
            ringB = NO_RING
            haloStartedAt = NO_HALO
        }
    }

    return HushPressFeedback(
        scale = scale,
        squash = PressMotion.squash(scale),
        pressed = pressed,
        ringA = ringA,
        ringB = ringB,
    )
}

/**
 * Applies the press animation to something that already handles its own clicks - a Material
 * `IconButton`, a `Card`, a `Surface(onClick = ...)`.
 *
 * Hand it the same interaction source the composable uses (or one of its own) and it replaces what
 * the system's animation scale would otherwise have silenced. Because it installs no click handler,
 * the composable keeps its own enabled state, colours, role and semantics.
 */
@Composable
fun Modifier.hushPressMotion(
    interactionSource: MutableInteractionSource,
    pressScale: Float = HushDesign.PressScale,
    enabled: Boolean = true,
    haloColor: Color? = null,
    haloStrength: Float = 1f,
): Modifier {
    val feedback = rememberHushPressFeedback(interactionSource, pressScale, enabled, haloStrength)
    return hushPressLayer(feedback, haloColor ?: MaterialTheme.colorScheme.primary, haloStrength)
}

/**
 * A press-animated button: the click and the motion in one modifier, for the many `Surface`, `Box`
 * and `Row` containers that would otherwise only ripple.
 */
fun Modifier.hushBouncyClickable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    pressScale: Float = HushDesign.PressScale,
    haloColor: Color? = null,
    haloStrength: Float = 1f,
    onLongClick: (() -> Unit)? = null,
): Modifier =
    composed {
        val interactionSource = remember { MutableInteractionSource() }
        val feedback = rememberHushPressFeedback(interactionSource, pressScale, enabled, haloStrength)
        val base =
            hushPressLayer(
                feedback = feedback,
                color = haloColor ?: MaterialTheme.colorScheme.primary,
                haloStrength = haloStrength,
            )
        if (onLongClick != null) {
            base.combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = role,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = null,
            )
        } else {
            base.clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = role,
                onClick = onClick,
            )
        }
    }

/**
 * A tap that does nothing to the surface it is on.
 *
 * For the surfaces where the motion is wrong rather than missing: a settings row, a navigation tile,
 * a section header. The give-and-spring is the gesture a *button* makes, and a full-width row that
 * squashes and springs under the finger reads as the page moving, not as the row responding - which is
 * why "the jiggle is good on the player" and wrong here. Those surfaces get the plain tap they always
 * had, and the player's controls, the list actions and the playlist header keep the motion through
 * [HushIconButton] and [hushBouncyClickable].
 *
 * There is deliberately no ripple either: this exists to take motion away, not to trade one animation
 * for another.
 */
fun Modifier.hushTappable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    role: Role? = null,
    onLongClick: (() -> Unit)? = null,
): Modifier =
    composed {
        val interactionSource = remember { MutableInteractionSource() }
        if (onLongClick != null) {
            combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = role,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = null,
            )
        } else {
            clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = role,
                onClick = onClick,
            )
        }
    }

/**
 * A one-shot pop for the icon inside a button: it starts at [peak] and springs back with the same
 * decaying wobble every press in Hush uses.
 *
 * Frame-driven for the reason the rest of this file is. The version this replaces was an `Animatable`
 * with a spring, which the system's animator duration scale silences *completely* - so on the device
 * that runs with it at 0 (a developer setting, and what a car head unit uses) the playlist header's
 * like, play, shuffle, download and options buttons popped for nobody, which is what "the player's
 * buttons animate but the playlist's do not" turned out to be. The curve is [PressMotion.releasedScale]
 * so an icon pop and a button press can never drift apart.
 *
 * The returned [TapPop.scale] is a state, so a caller reads it inside a `graphicsLayer` block and the
 * pop costs no recomposition per frame.
 */
@Composable
fun rememberTapPopScale(peak: Float = 1.12f): TapPop {
    val animationsDisabled = LocalAnimationsDisabled.current
    val scale = remember { mutableFloatStateOf(1f) }
    var taps by remember { mutableIntStateOf(0) }
    LaunchedEffect(taps, animationsDisabled) {
        if (animationsDisabled || taps == 0) return@LaunchedEffect
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            val keepGoing =
                withFrameNanos {
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    scale.floatValue = PressMotion.releasedScale(elapsed, fromScale = peak, pressScale = 1f)
                    elapsed < PressMotion.RELEASE_MILLIS
                }
            if (!keepGoing) break
        }
        scale.floatValue = 1f
    }
    return remember(scale) {
        TapPop(scale = scale, trigger = { taps++ })
    }
}

/** What a tap pop hands a button: the icon's current scale, and the trigger that starts one. */
class TapPop(
    val scale: State<Float>,
    val trigger: () -> Unit,
)

/** A stable interaction source for a composable that needs one to feed [hushPressMotion]. */
@Composable
fun rememberPressInteractionSource(): MutableInteractionSource = remember { MutableInteractionSource() }

/**
 * The drawing half: squash-scale, then the staggered rings behind the button's own content.
 *
 * Behind, not in front: the ring reads as an aura around the button, and the part of it that falls
 * under the button's own fill is hidden, which is what keeps a rectangular button looking
 * rectangular while it pulses.
 *
 * **The chain is deliberately the same whether or not a press is happening.** An earlier version
 * returned the untouched modifier while a button was idle and only inserted the layer while it was
 * pressed, so the shape of the chain changed under a live node twice per tap. That is not free: on
 * the reporting device it made RenderThread recurse until its stack ran out (`Cause: stack pointer is
 * not in a rw map`, 512 frames inside `RenderNode::prepareTreeImpl`) the moment the full player was
 * composed, killing the app on the very screen these buttons live on - measured, and fixed by
 * attaching the layer for the button's whole life and letting only what it *draws* change. HEAD's
 * own press code always attached its layer, which is why this never showed up before.
 */
internal fun Modifier.hushPressLayer(
    feedback: HushPressFeedback,
    color: Color,
    haloStrength: Float,
): Modifier =
    this
        .graphicsLayer {
            scaleX = feedback.scale * (1f + feedback.squash)
            scaleY = feedback.scale * (1f - feedback.squash)
        }
        .drawBehind {
            if (!feedback.hasRing || haloStrength <= 0f) return@drawBehind
            // A control's shape is only known here, at draw time, so this is where "a burst belongs
            // on a button, not on a list row" can be enforced for every call site at once. Rows and
            // cards keep the give and the spring; they just do not splash.
            val shortSide = size.minDimension.toDp().value
            val longSide = size.maxDimension.toDp().value
            val suits = PressMotion.haloSuits(shortSide, longSide)
            // A small control's ring covers too few pixels to read at the strength a large one uses,
            // so its opacity is lifted; exactly 1.0 at 72dp and above, so large controls are unchanged.
            val boost = PressMotion.haloVisibilityBoost(shortSide)
            // Temporary trace: what the halo decided, in the units the rule uses.
            if (BuildConfig.DEBUG) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastHaloTraceAt > 200L) {
                    lastHaloTraceAt = now
                    val ring = if (feedback.ringA != NO_RING) feedback.ringA else feedback.ringB
                    android.util.Log.d(
                        "HushHalo",
                        "node=${size.width}x${size.height}px short=${shortSide}dp long=${longSide}dp " +
                            "suits=$suits strength=$haloStrength boost=$boost progress=$ring " +
                            "radius=${size.minDimension * 0.5f * PressMotion.haloRadiusFraction(ring)}px",
                    )
                }
            }
            if (!suits) return@drawBehind
            val unit = size.minDimension * 0.5f
            listOf(feedback.ringA, feedback.ringB).forEach { progress ->
                if (progress == NO_RING) return@forEach
                val alpha = PressMotion.haloAlpha(progress, haloStrength * boost)
                if (alpha <= 0.002f) return@forEach
                drawCircle(
                    color = color.copy(alpha = alpha),
                    radius = unit * PressMotion.haloRadiusFraction(progress),
                    center = center,
                )
            }
        }
