/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.component

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos

/**
 * Motion that survives a device with its animations turned off.
 *
 * Android's animator duration scale silences every Compose animation: with it at 0 - a developer
 * setting, and the state this was measured in (`animator_duration_scale = 0` on the reporting
 * device) - `rememberInfiniteTransition`, `animateFloatAsState` and the indeterminate
 * `*ProgressIndicator`s all paint a single frame and hold it. For a transition that is the intended
 * behaviour, and this file does not touch transitions. For an *indeterminate* indicator it is a bug:
 * the whole message of a spinner is "something is happening", and a frozen one says the opposite.
 * It reads as a broken control, on the controls a user presses precisely while waiting.
 *
 * `withFrameNanos` is a frame callback rather than an animation, so it is not scaled by anything the
 * user set. Everything here is built on it: a value from [rememberFramePhase] advances at the same
 * speed whether the system animates or not.
 */
/** One full turn, as radians. `2 * PI` is not a compile-time constant, so it lives here. */
private const val TWO_PI_F = 6.2831853f

object IndeterminateMotion {

    /** One turn of an indeterminate ring. Slow enough to read as "busy", quick enough to notice. */
    const val RING_PERIOD_MILLIS = 900L

    /** How much of the ring turns. A quarter would hide on a 16dp spinner; this does not. */
    const val RING_SWEEP_DEGREES = 110f

    /** Pointer sweep of the linear loader, as a fraction of the track. */
    const val BAR_SWEEP_FRACTION = 0.34f

    /**
     * How long the first bar of an equalizer indicator takes to rise and fall.
     *
     * One turn is one rise and one fall, so a bar is a dot, is at its tallest and is a dot again
     * inside a second. Shared by everywhere the app draws the equalizer - the player's NOW PLAYING
     * pill and the playing row of a list - so the two cannot drift into looking like two different
     * indicators.
     */
    const val EQUALIZER_PERIOD_MILLIS = 900L

    /**
     * How much slower each bar after the first turns.
     *
     * A stagger that divided the period evenly would put every bar back in step at the end of each
     * turn, and one bar's climb beside another's descent reads as a single shape pumping rather than
     * as a meter; an odd stagger keeps the bars apart for as long as the loop runs.
     */
    const val EQUALIZER_STAGGER_MILLIS = 160L

    /**
     * Where a looping value sits after this long, as a fraction of its period.
     *
     * Deliberately pure and total: a negative elapsed time (a clock that went backwards, or an
     * offset) still lands inside 0 until 1, so no caller has to guard it and no frame can produce a
     * value that leaves a shape off its own canvas.
     */
    fun fraction(
        elapsedMillis: Long,
        periodMillis: Long,
        offsetMillis: Long = 0L,
    ): Float {
        if (periodMillis <= 0L) return 0f
        val phase = (elapsedMillis + offsetMillis).mod(periodMillis)
        return phase.toFloat() / periodMillis.toFloat()
    }

    /** Where an indeterminate ring's arc starts, in degrees, always inside 0 until 360. */
    fun ringDegrees(elapsedMillis: Long, offsetMillis: Long = 0L): Float =
        fraction(elapsedMillis, RING_PERIOD_MILLIS, offsetMillis) * 360f

    /** The period of bar [index] of an equalizer indicator. */
    fun equalizerPeriodMillis(index: Int): Long =
        EQUALIZER_PERIOD_MILLIS + EQUALIZER_STAGGER_MILLIS * index.coerceAtLeast(0)

    /**
     * How far up an equalizer bar stands at [phase], as 0 until 1 of its travel.
     *
     * A raised cosine rather than a sine: 0 at both ends of the turn and 1 halfway through it. A
     * sine would begin - and end - mid-slope, so a bar that starts life as a resting dot would jump
     * to half height on its first frame and snap back there every time the loop came round. This one
     * leaves the dot, reaches the top and settles back onto the dot, for as long as the music plays.
     *
     * Total like [fraction]: a phase from a clock that ran far past its period, or one that went
     * backwards, still lands inside the travel, so no caller has to guard it and no frame can put a
     * bar off its own box.
     */
    fun equalizerBarRise(phase: Float): Float {
        val turned = phase.mod(1f)
        return (0.5f * (1f - cos(TWO_PI_F * turned))).coerceIn(0f, 1f)
    }

    /**
     * How much of a pull-to-refresh ring to draw, or null for nothing at all.
     *
     * A ring with no angle is not a smaller ring, it is a dot: at rest, and while the finger has
     * not travelled far enough to count, this must draw nothing instead of a stub. Refreshing wins
     * over the pull, because by then the pull is spent and the movement is the only thing left to
     * say the work is still running.
     */
    fun refreshRingSweepDegrees(
        distanceFraction: Float,
        isRefreshing: Boolean,
    ): Float? {
        if (isRefreshing) return RING_SWEEP_DEGREES
        val pulled = distanceFraction.coerceIn(0f, 1f)
        return if (pulled <= 0f) null else pulled * 360f
    }

    /**
     * Where a pull-to-refresh ring's arc starts: from the top while the user drags (so the ring
     * grows like a gauge), turning while the refresh runs.
     */
    fun refreshRingStartDegrees(
        framePhase: Float,
        isRefreshing: Boolean,
    ): Float = if (isRefreshing) framePhase * 360f else -90f
}

/**
 * A value that loops from 0 until 1 while this composable is on screen, advanced by frame time.
 *
 * The scale-independent replacement for `rememberInfiniteTransition` wherever the loop is the
 * signal rather than decoration. It stops when it leaves the composition, so it costs frames only
 * while something is genuinely waiting.
 */
@Composable
fun rememberFramePhase(
    periodMillis: Long,
    offsetMillis: Long = 0L,
): Float {
    var phase by remember(periodMillis, offsetMillis) { mutableFloatStateOf(0f) }
    val startedAt = remember(periodMillis, offsetMillis) { SystemClock.elapsedRealtime() }
    LaunchedEffect(periodMillis, offsetMillis) {
        while (true) {
            withFrameNanos {
                phase = IndeterminateMotion.fraction(
                    elapsedMillis = SystemClock.elapsedRealtime() - startedAt,
                    periodMillis = periodMillis,
                    offsetMillis = offsetMillis,
                )
            }
        }
    }
    return phase
}

/**
 * An arc that turns because time is passing, on every device.
 *
 * Used wherever an indeterminate circular indicator stood, so the answer is the same one the
 * player's transport uses. The stroke scales with the size, and the colour defaults to the same
 * `primary` the Material indicator used, so swapping one for the other is not a visual change.
 */
@Composable
fun HushProgressSpinner(
    // A default size, not `Modifier`: four call sites in this app rely on the indicator's own size,
    // and a Canvas with no size draws nothing at all. Material's circular indicator defaults to
    // 40dp, so a site that passed no modifier keeps the size it had.
    modifier: Modifier = Modifier.size(40.dp),
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 0.dp,
    trackColor: Color? = null,
    containerColor: Color? = null,
) {
    val phase = rememberFramePhase(IndeterminateMotion.RING_PERIOD_MILLIS)
    Canvas(modifier = modifier) {
        if (size.minDimension <= 0f) return@Canvas
        val stroke = if (strokeWidth > 0.dp) strokeWidth.toPx() else size.minDimension * 0.13f
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        if (containerColor != null) {
            drawCircle(
                color = containerColor,
                radius = size.minDimension / 2f,
                center = Offset(size.width / 2f, size.height / 2f),
            )
        }
        if (trackColor != null) {
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke),
                topLeft = Offset(inset, inset),
                size = arcSize,
            )
        }
        drawArc(
            color = color,
            startAngle = phase * 360f,
            sweepAngle = IndeterminateMotion.RING_SWEEP_DEGREES,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
            topLeft = Offset(inset, inset),
            size = arcSize,
        )
    }
}

/**
 * A segment that travels along a track, on every device: the indeterminate linear indicator, drawn
 * from frame time instead of from an animation the system may have switched off.
 *
 * The segment is drawn twice, wrapping at the ends, so the sweep never blinks out of existence.
 */
@Composable
fun HushLinearLoader(
    modifier: Modifier = Modifier.fillMaxWidth().height(4.dp),
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color? = null,
) {
    val phase = rememberFramePhase(1400L)
    Canvas(modifier = modifier) {
        val height = size.height
        val track = height * 0.5f
        val top = (height - track) / 2f
        if (trackColor != null) {
            drawRect(color = trackColor, topLeft = Offset(0f, top), size = Size(size.width, track))
        }
        val segment = size.width * IndeterminateMotion.BAR_SWEEP_FRACTION
        val travel = size.width + segment
        var start = phase * travel - segment
        repeat(2) {
            val left = start.coerceIn(0f, size.width)
            val right = (start + segment).coerceIn(0f, size.width)
            if (right > left) {
                drawRect(
                    color = color,
                    topLeft = Offset(left, top),
                    size = Size(right - left, track),
                )
            }
            start += travel
        }
    }
}
