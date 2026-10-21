/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the motion that has to survive a device with its animations switched off.
 *
 * Measured on the reporting device: `animator_duration_scale = 0`, so every Compose animation -
 * infinite transitions, `animateFloatAsState`, the indeterminate `*ProgressIndicator`s - is scaled
 * to nothing and paints a single frame. On screen that is a spinner that never moves, a visualizer
 * whose bars never rise, and a listening orb that pulses once and stops. Everything here is
 * therefore advanced from frame time, and these pin the arithmetic the frames feed.
 */
class FrameDrivenMotionTest {

    /**
     * The ring that has no percentage still has to move.
     *
     * Measured on the reporting device: `animator_duration_scale = 0`, so every Compose animation -
     * including the indeterminate progress indicator the transport used - is scaled to nothing and
     * paints a single frame. On screen that is a play/pause button that looks stuck for the whole
     * download. The rotation is therefore advanced from frame time instead, and these pin the two
     * properties that make it read as motion rather than as a glitch: it turns, and it comes back
     * round instead of winding up.
     */
    @Test
    fun `the ring turns as time passes`() {
        val quarter = IndeterminateMotion.ringDegrees(IndeterminateMotion.RING_PERIOD_MILLIS / 4)
        assertEquals(90f, quarter, 0.001f)
        assertTrue(IndeterminateMotion.ringDegrees(120L) > 0f)
        assertTrue(IndeterminateMotion.ringDegrees(600L) > IndeterminateMotion.ringDegrees(120L))
    }

    @Test
    fun `the ring comes back round instead of winding up`() {
        assertEquals(0f, IndeterminateMotion.ringDegrees(0L), 0.001f)
        assertEquals(0f, IndeterminateMotion.ringDegrees(IndeterminateMotion.RING_PERIOD_MILLIS), 0.001f)
        assertTrue(IndeterminateMotion.ringDegrees(IndeterminateMotion.RING_PERIOD_MILLIS * 97 + 450L) < 360f)
    }

    /** A visible sweep, not a full circle: a full one cannot be seen to turn. */
    @Test
    fun `the arc is a readable slice of the circle`() {
        assertTrue(IndeterminateMotion.RING_SWEEP_DEGREES in 60f..270f)
    }

    /** A looping value stays inside its period however far the clock has run. */
    @Test
    fun `a phase never leaves its period`() {
        val period = 1700L
        listOf(0L, 1L, period - 1, period, period * 500 + 13, -250L, -period * 3 - 11).forEach { elapsed ->
            val value = IndeterminateMotion.fraction(elapsed, period)
            assertTrue("elapsed=$elapsed -> $value", value >= 0f && value < 1f)
        }
    }

    /** A negative offset is how a ring starts part-way round, so it must shift, not clamp. */
    @Test
    fun `an offset shifts the phase and still stays in range`() {
        assertEquals(0.25f, IndeterminateMotion.fraction(0L, 2000L, 500L), 0.0001f)
        assertEquals(0.0f, IndeterminateMotion.fraction(-500L, 2000L, 500L), 0.0001f)
        assertTrue(IndeterminateMotion.fraction(0L, 2000L, -500L) > 0.7f)
    }

    /** A period of nothing would divide by zero: the answer is a still value, not a crash. */
    @Test
    fun `a period of zero is still, not fatal`() {
        assertEquals(0f, IndeterminateMotion.fraction(1234L, 0L), 0.0001f)
        assertEquals(0f, IndeterminateMotion.fraction(1234L, -5L), 0.0001f)
    }

    /** The linear loader has to travel a readable fraction of its track, not the whole of it. */
    @Test
    fun `the linear sweep is a readable segment`() {
        assertTrue(IndeterminateMotion.BAR_SWEEP_FRACTION in 0.15f..0.6f)
    }

    /**
     * A pull-to-refresh ring at rest draws nothing, not a dot.
     *
     * Material's own refresh indicator spins on an `infiniteRepeatable` tween, which the system's
     * animation scale pins to one angle - so a refresh in progress, and a screen that has merely
     * been pulled a little, both looked like a still dot. This one is told what to draw, and at rest
     * the answer is "nothing": the container is empty until there is something to say.
     */
    @Test
    fun `an unpulled refresh ring draws nothing`() {
        assertEquals(null, IndeterminateMotion.refreshRingSweepDegrees(0f, isRefreshing = false))
        assertEquals(null, IndeterminateMotion.refreshRingSweepDegrees(-1f, isRefreshing = false))
    }

    /** While dragging, the ring is a gauge: the further the pull, the more of it there is. */
    @Test
    fun `the refresh ring grows with the pull`() {
        assertEquals(90f, IndeterminateMotion.refreshRingSweepDegrees(0.25f, false)!!, 0.001f)
        assertEquals(180f, IndeterminateMotion.refreshRingSweepDegrees(0.5f, false)!!, 0.001f)
        assertEquals(360f, IndeterminateMotion.refreshRingSweepDegrees(1f, false)!!, 0.001f)
        // Past the threshold it is a full ring, never one that has wrapped past its own start.
        assertEquals(360f, IndeterminateMotion.refreshRingSweepDegrees(3f, false)!!, 0.001f)
    }

    /**
     * Refreshing wins over the pull.
     *
     * Once the finger is off, the pull is spent - a ring sized by it would sit still at whatever the
     * drag reached, which is exactly the frozen-looking state this replaced.
     */
    @Test
    fun `a refreshing ring turns even with no pull left`() {
        assertEquals(
            IndeterminateMotion.RING_SWEEP_DEGREES,
            IndeterminateMotion.refreshRingSweepDegrees(0f, isRefreshing = true)!!,
            0.001f,
        )
        assertTrue(IndeterminateMotion.RING_SWEEP_DEGREES < 360f)
    }

    /** The ring is anchored at the top while dragging, and turns while refreshing. */
    @Test
    fun `the refresh ring starts at the top while pulling and turns while refreshing`() {
        assertEquals(-90f, IndeterminateMotion.refreshRingStartDegrees(0.4f, isRefreshing = false), 0.001f)
        assertEquals(144f, IndeterminateMotion.refreshRingStartDegrees(0.4f, isRefreshing = true), 0.001f)
        assertTrue(
            IndeterminateMotion.refreshRingStartDegrees(0.6f, true) >
                IndeterminateMotion.refreshRingStartDegrees(0.4f, true),
        )
    }

    /**
     * The app's playing indicators are one rule, and no two bars of them share a clock.
     *
     * Measured on the reporting device: `animator_duration_scale = 0`, so the pill's bars - a
     * `rememberInfiniteTransition` with a sine on it - were drawn once and held as three still lines
     * for the whole track. The replacement is only motion if the bars turn at all, and only a meter
     * if they turn out of step.
     */
    @Test
    fun `every equalizer bar turns on its own clock`() {
        val periods = (0 until 3).map(IndeterminateMotion::equalizerPeriodMillis)
        assertEquals(3, periods.distinct().size)
        assertTrue(periods.zipWithNext().all { (first, next) -> next > first })
        assertTrue(periods.all { it > 0L })
        // A caller whose index is zero or negative still gets a period it can count on.
        assertTrue(IndeterminateMotion.equalizerPeriodMillis(0) > 0L)
        assertTrue(IndeterminateMotion.equalizerPeriodMillis(-4) > 0L)
    }

    /**
     * A bar leaves its dot, reaches the top and comes back to the dot.
     *
     * The paused pill already stands on three dots, so the travelling bar has to start there: a sine
     * would begin at half height and the first frame of playback would be a jump.
     */
    @Test
    fun `an equalizer bar rises from its dot and returns to it`() {
        assertEquals(0f, IndeterminateMotion.equalizerBarRise(0f), 0.0001f)
        assertEquals(1f, IndeterminateMotion.equalizerBarRise(0.5f), 0.0001f)
        assertEquals(0.5f, IndeterminateMotion.equalizerBarRise(0.25f), 0.0001f)
        // Past the top it is on its way back down, not climbing again.
        assertTrue(IndeterminateMotion.equalizerBarRise(0.9f) < IndeterminateMotion.equalizerBarRise(0.5f))
    }

    /** The loop has to be seamless: the frame the phase wraps is not a jump. */
    @Test
    fun `an equalizer bar is back on its dot at the end of a turn`() {
        assertEquals(0f, IndeterminateMotion.equalizerBarRise(1f), 0.0001f)
        assertEquals(0f, IndeterminateMotion.equalizerBarRise(2f), 0.0001f)
        assertEquals(0f, IndeterminateMotion.equalizerBarRise(0.9999f), 0.001f)
    }

    /** Whatever the clock did, a bar stays inside its travel: never a negative or oversized height. */
    @Test
    fun `an equalizer bar never leaves its travel`() {
        (0..1000).forEach { step ->
            val rise = IndeterminateMotion.equalizerBarRise(step / 1000f)
            assertTrue("rise=$rise at step=$step", rise >= 0f && rise <= 1f)
        }
        // A phase from a clock that ran far past its period, and one that went backwards.
        assertTrue(IndeterminateMotion.equalizerBarRise(4137.42f) in 0f..1f)
        assertTrue(IndeterminateMotion.equalizerBarRise(-0.3f) in 0f..1f)
    }
}
