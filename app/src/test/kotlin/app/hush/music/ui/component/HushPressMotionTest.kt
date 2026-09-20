/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the press animation that has to be visible on a device whose animations are switched off.
 *
 * Measured on the reporting device: `animator_duration_scale = 0`. The press effects this replaced
 * were `animateFloatAsState` and a ripple, both of which Compose scales to nothing there, so a press
 * showed a hard size jump and no ripple - which is what "the buttons look static" described. These
 * curves are advanced from frame time instead, and the tests below pin the properties that make a
 * press readable: it moves on the first frame, it springs past its own size, the wobble dies, and
 * the ring is already alive when the finger touches down.
 */
class HushPressMotionTest {

    private val pressScale = 0.9f

    /**
     * The property that matters most: the button has already given before the first frame is drawn.
     *
     * A press that starts moving only after an animation has had a chance to run is exactly the
     * behaviour that broke on the reporting device.
     */
    @Test
    fun `a press moves on the very first frame`() {
        val firstFrame = PressMotion.pressedScale(16L, pressScale)
        assertTrue("scale at 16ms was $firstFrame", firstFrame < 1f)
        assertTrue(firstFrame > pressScale)
        assertEquals(1f, PressMotion.pressedScale(0L, pressScale), 0.0001f)
    }

    /** Held down, the button settles at the press scale and stays there. */
    @Test
    fun `a held press settles at the press scale`() {
        assertEquals(pressScale, PressMotion.pressedScale(PressMotion.PRESS_IN_MILLIS, pressScale), 0.0001f)
        assertEquals(pressScale, PressMotion.pressedScale(PressMotion.PRESS_IN_MILLIS * 20, pressScale), 0.0001f)
    }

    /** It only ever contracts: no wobble before the finger comes up. */
    @Test
    fun `a held press never grows past its own size or past the press scale`() {
        var previous = 1f
        (0L..300L step 5L).forEach { t ->
            val scale = PressMotion.pressedScale(t, pressScale)
            assertTrue("t=$t -> $scale", scale <= previous + 0.0001f)
            assertTrue("t=$t -> $scale", scale in pressScale..1f)
            previous = scale
        }
    }

    /** A quick tap springs from where it actually got to, not from a deeper press it never made. */
    @Test
    fun `the release starts where the press left the button`() {
        assertEquals(0.97f, PressMotion.releasedScale(0L, fromScale = 0.97f, pressScale = pressScale), 0.001f)
        assertEquals(
            pressScale,
            PressMotion.releasedScale(0L, fromScale = pressScale, pressScale = pressScale),
            0.001f,
        )
    }

    /** The spring is the point: it must travel past its own size before settling, or it is a fade. */
    @Test
    fun `the release overshoots past its own size and lands exactly on it`() {
        val peak =
            (0L until PressMotion.RELEASE_MILLIS step 5L)
                .maxOf { PressMotion.releasedScale(it, fromScale = pressScale, pressScale = pressScale) }
        assertTrue("peak was $peak", peak > 1.01f)
        assertEquals(
            1f,
            PressMotion.releasedScale(PressMotion.RELEASE_MILLIS, pressScale, pressScale),
            0.0001f,
        )
        assertEquals(
            1f,
            PressMotion.releasedScale(PressMotion.RELEASE_MILLIS * 3, pressScale, pressScale),
            0.0001f,
        )
    }

    /** And it wobbles more than once: one hump is a hop, several is a spring. */
    @Test
    fun `the release wobbles back and forth`() {
        var signChanges = 0
        var last = 0f
        (0L until PressMotion.RELEASE_MILLIS step 4L).forEach { t ->
            val b = PressMotion.bounce(t, pressScale)
            if (last != 0f && (b > 0f) != (last > 0f)) signChanges++
            if (b != 0f) last = b
        }
        assertTrue("the wobble changed direction $signChanges times", signChanges >= 2)
    }

    /** Whatever the press depth, the release stays inside its band - a bouncy button is not a bug. */
    @Test
    fun `the release stays inside a sane band for every press scale`() {
        listOf(0.5f, 0.7f, 0.82f, 0.9f, 0.94f, 1f).forEach { scale ->
            (0L..PressMotion.RELEASE_MILLIS step 5L).forEach { t ->
                val value = PressMotion.releasedScale(t, fromScale = scale, pressScale = scale)
                assertTrue("pressScale=$scale t=$t -> $value", value in 0.5f..1.2f)
            }
        }
    }

    /** The wobble dies away rather than ringing on: half the time, half the amplitude at most. */
    @Test
    fun `the wobble decays`() {
        val early =
            (0L until PressMotion.RELEASE_MILLIS / 2 step 4L)
                .maxOf { kotlin.math.abs(PressMotion.bounce(it, pressScale)) }
        val late =
            (PressMotion.RELEASE_MILLIS / 2 until PressMotion.RELEASE_MILLIS step 4L)
                .maxOf { kotlin.math.abs(PressMotion.bounce(it, pressScale)) }
        assertTrue("early=$early late=$late", late < early * 0.6f)
        assertEquals(0f, PressMotion.bounce(0L, pressScale), 0.0001f)
        assertEquals(0f, PressMotion.bounce(PressMotion.RELEASE_MILLIS, pressScale), 0.0001f)
    }

    /** A halo is alive from the instant of the press, which is what makes a fast tap visible too. */
    @Test
    fun `a halo ring exists on the first frame and is gone by its end`() {
        assertEquals(0f, PressMotion.haloProgress(0L)!!, 0.0001f)
        assertNotNull(PressMotion.haloProgress(16L))
        assertNull(PressMotion.haloProgress(PressMotion.HALO_MILLIS))
        assertNull(PressMotion.haloProgress(PressMotion.HALO_MILLIS * 4))
    }

    /** A clock that steps backwards must not conjure a ring. */
    @Test
    fun `a halo ring cannot exist before a press`() {
        assertNull(PressMotion.haloProgress(-1L))
        assertNull(PressMotion.haloProgress(80L, staggerMillis = 150L))
        assertNotNull(PressMotion.haloProgress(200L, staggerMillis = 150L))
    }

    /** The two rings are two waves: the second is behind the first, never on top of it. */
    @Test
    fun `the staggered ring trails the first one`() {
        val first = PressMotion.haloProgress(300L)!!
        val second = PressMotion.haloProgress(300L, PressMotion.HALO_STAGGER_MILLIS)!!
        assertTrue("first=$first second=$second", second < first)
        assertTrue(PressMotion.HALO_STAGGER_MILLIS in 60L..PressMotion.HALO_MILLIS)
    }

    /** A ring fades as it travels, and is invisible at both ends of its life. */
    @Test
    fun `a halo ring fades away as it expands`() {
        assertEquals(PressMotion.HALO_PEAK_ALPHA, PressMotion.haloAlpha(0f), 0.0001f)
        assertEquals(0f, PressMotion.haloAlpha(1f), 0.0001f)
        var previous = Float.MAX_VALUE
        (0..10).forEach { step ->
            val alpha = PressMotion.haloAlpha(step / 10f)
            assertTrue(alpha <= previous)
            previous = alpha
        }
    }

    /** It grows outwards: it must end up wider than the button it came from, or nobody sees it. */
    @Test
    fun `the halo grows past the button`() {
        assertTrue(PressMotion.haloRadiusFraction(0f) < 1f)
        assertTrue(PressMotion.haloRadiusFraction(1f) > 1f)
        assertEquals(
            PressMotion.HALO_START_FRACTION,
            PressMotion.haloRadiusFraction(-2f),
            0.0001f,
        )
        assertTrue(
            PressMotion.haloRadiusFraction(0.9f) > PressMotion.haloRadiusFraction(0.1f),
        )
    }

    /**
     * The ring has to be visible while it is still bright, on a button of any shape.
     *
     * Measured on the device: the transport row's 312x232 controls drew no visible ring at all,
     * because a linear expansion only cleared the button's own half-height in the last quarter of the
     * fade - by which point the ring was down to about 3% opacity. On a 192x192 action button the
     * same ring was clearly visible (2714 changed pixels outside its bounds). Cleared by half life
     * with most of the fade left is the property that makes the difference, so it is pinned here.
     */
    @Test
    fun `the ring clears the button's own edge early in its life`() {
        val halfLife = PressMotion.haloRadiusFraction(PressMotion.HALO_OPACITY_STILL_VISIBLE)
        assertTrue("radius at the visible-fade point was $halfLife", halfLife >= 1f)
        assertTrue(
            PressMotion.haloAlpha(PressMotion.HALO_OPACITY_STILL_VISIBLE) > 0.08f,
        )
    }

    /** The squash is what makes a press read as a press rather than as a size change. */
    @Test
    fun `the squash is nothing at rest and grows as the button contracts`() {
        assertEquals(0f, PressMotion.squash(1f), 0.0001f)
        assertTrue(PressMotion.squash(0.9f) > 0f)
        assertTrue(PressMotion.squash(0.8f) > PressMotion.squash(0.9f))
        assertTrue(PressMotion.squash(0.5f) <= 0.12f)
    }

    /**
     * A ring belongs on a button, and a button is compact and roughly square.
     *
     * This is the rule that replaced forty-eight call sites each having to remember to ask for one:
     * the ones that forgot gave a size change and nothing else, which is how the player's like,
     * download and shuffle buttons came to look inert. Everything a ring must keep working on is on
     * the allowed side of this line - including the full player's own 84dp play button - and a list
     * row or a grid card is on the other.
     */
    @Test
    fun `a halo suits a button and not a row or a card`() {
        // Buttons: the player's transport controls, its action circles, header actions, mini player.
        listOf(24f, 36f, 40f, 44f, 48f, 56f, 58f, 64f, 70f, 76f, 80f, 84f, 96f).forEach { side ->
            assertTrue("a ${side}dp square should throw a ring", PressMotion.haloSuits(side, side))
        }

        // Rows, pills and cards: they give and spring, they do not splash.
        assertTrue("a song row should not splash", !PressMotion.haloSuits(64f, 360f))
        assertTrue("a wide pill should not splash", !PressMotion.haloSuits(48f, 160f))
        assertTrue("a grid card should not splash", !PressMotion.haloSuits(170f, 170f))
        assertTrue("a hero card should not splash", !PressMotion.haloSuits(190f, 300f))
    }

    /** A nonsense size is not a button, and a square is the shape a ring wants. */
    @Test
    fun `the halo rule treats a missing size as not a button`() {
        assertTrue(!PressMotion.haloSuits(0f, 0f))
        assertTrue(!PressMotion.haloSuits(-4f, -4f))
        assertTrue(PressMotion.haloSuits(96f, 96f * PressMotion.HALO_MAX_ASPECT))
        assertTrue(!PressMotion.haloSuits(96f, 96f * PressMotion.HALO_MAX_ASPECT + 1f))
        assertTrue(PressMotion.HALO_MAX_SHORT_SIDE_DP >= 84f)
    }

    /**
     * The press chain must be the same shape whether or not a press is happening.
     *
     * This is not cosmetic. An earlier version of the layer returned the untouched modifier while a
     * button was idle and only built the layer while it was pressed, so the shape of the chain
     * changed twice per tap. On the reporting device that made RenderThread recurse until its stack
     * ran out the moment the full player composed - `Cause: stack pointer is not in a rw map`, 512
     * frames of `RenderNode::prepareTreeImpl` - killing the app on the screen these buttons live on.
     * Attaching the layer for the button's whole life and letting only what it draws change is the
     * fix, and this is the line that keeps it.
     */
    @Test
    fun `the press layer is attached whether or not a press is happening`() {
        val idle = HushPressFeedback()
        assertTrue(
            "an idle button must still carry its press layer",
            Modifier.hushPressLayer(idle, Color.Red, haloStrength = 1f) !== Modifier,
        )
        val pressed = HushPressFeedback(scale = 0.88f, squash = 0.04f, pressed = true, ringA = 0.2f)
        assertTrue(
            "a pressed button carries the same chain",
            Modifier.hushPressLayer(pressed, Color.Red, haloStrength = 1f) !== Modifier,
        )
        assertTrue(
            "and so does one whose halo is switched off",
            Modifier.hushPressLayer(idle, Color.Red, haloStrength = 0f) !== Modifier,
        )
    }

    /**
     * A small control's ring is lifted, and every control that already read well is untouched.
     *
     * The measurements this comes from: the mini player's 36dp skip controls put 17-484 changed pixels
     * outside their paint at a delta of 45-55, against 679-1599 at 505 on its 40dp play button and
     * 8228-13871 on the playlist header and the player's transport row. The multiplier has to be
     * exactly 1 at and above 72dp or the rings that were measured and kept would move.
     */
    @Test
    fun `a small control's halo is lifted and a large one's is not touched`() {
        listOf(72f, 76f, 80f, 84f, 96f, 200f).forEach { side ->
            assertEquals("a ${side}dp control must keep the measured ring", 1f, PressMotion.haloVisibilityBoost(side), 0.0001f)
        }
        val small = PressMotion.haloVisibilityBoost(36f)
        assertTrue("36dp got $small", small > 1f)
        val medium = PressMotion.haloVisibilityBoost(40f)
        assertTrue("40dp got $medium", medium > 1f)
        assertTrue("a smaller control must not be dimmer", small > medium)
        assertTrue(
            "the boost must stay inside its declared ceiling",
            PressMotion.haloVisibilityBoost(0.1f) <= 1f + PressMotion.HALO_SMALL_STRENGTH_BOOST + 0.0001f,
        )
    }

    /** The lift has to make the ring brighter without ever making the alpha illegal. */
    @Test
    fun `a lifted ring is brighter and still a legal alpha`() {
        val boost = PressMotion.haloVisibilityBoost(36f)
        (0..10).forEach { step ->
            val progress = step / 10f
            val plain = PressMotion.haloAlpha(progress)
            val lifted = PressMotion.haloAlpha(progress, boost)
            assertTrue("progress=$progress lifted=$lifted", lifted >= plain - 0.0001f)
            assertTrue("progress=$progress lifted=$lifted", lifted in 0f..1f)
        }
        // The ring is still invisible at both ends of its life, lifted or not.
        assertEquals(0f, PressMotion.haloAlpha(1f, boost), 0.0001f)
    }

    /** A nonsense press scale is clamped, not obeyed. */
    @Test
    fun `an absurd press scale is clamped`() {
        assertEquals(1f, PressMotion.pressedScale(500L, 4f), 0.0001f)
        assertEquals(0.5f, PressMotion.pressedScale(500L, 0.01f), 0.0001f)
        assertEquals(1f, PressMotion.pressedScale(500L, 1f), 0.0001f)
    }
}
