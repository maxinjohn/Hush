/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.constants

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mini player <-> full player transition, pinned as numbers.
 *
 * A transition is felt rather than read, so the two properties that make it feel wrong are the two
 * this guards: how long the sheet takes to arrive, and how much of that time is spent moving
 * distances nobody can see. Both are decisions in [BottomSheetSoftAnimationSpec] and both are
 * checkable without a device, because Compose can run the spec itself.
 */
class PlayerSheetMotionTest {

    /** Roughly a sheet's travel: the mini player's line to the top of a phone screen. */
    private val travel = 2000.dp

    private fun settleMs(spec: AnimationSpec<Dp>): Double =
        TargetBasedAnimation(
            animationSpec = spec,
            typeConverter = Dp.VectorConverter,
            initialValue = 0.dp,
            targetValue = travel,
        ).durationNanos / 1_000_000.0

    /**
     * The stiffness this spec was written against: `Spring.StiffnessLow` is the *old* value, and the
     * tap that opens the player used to run at it.
     */
    private val previousSpec =
        spring<Dp>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow,
        )

    @Test
    fun `the sheet arrives in a third of a second, not most of one`() {
        val now = settleMs(BottomSheetSoftAnimationSpec)
        val before = settleMs(previousSpec)
        assertTrue(
            "the sheet takes ${now.toInt()}ms; the Low-stiffness spec it replaced took ${before.toInt()}ms",
            now < before,
        )
        // A person taps the mini player expecting the player: the transition is a response, not a
        // performance. Anything past ~400ms reads as the app thinking about it.
        assertTrue("the sheet takes ${now.toInt()}ms to arrive", now < 400.0)
    }

    /**
     * A Dp spring's default visibility threshold is a hundredth of a pixel, so the tail of every
     * transition was frames of a full-screen sheet being re-drawn for movement no display can show.
     * Half a pixel ends that early without changing where the sheet lands.
     */
    @Test
    fun `the last fraction of a pixel is not animated`() {
        assertEquals(SheetSettleThreshold, 0.5.dp)
        assertTrue(
            "the sheet settles ${(settleMs(previousSpec) - settleMs(BottomSheetSoftAnimationSpec)).toInt()}ms sooner than it did",
            settleMs(BottomSheetSoftAnimationSpec) < settleMs(previousSpec),
        )
    }

    /**
     * A spring is not a tween: it must land *on* its target, because the sheet's expanded, collapsed
     * and dismissed states are read as exact comparisons against those bounds. An animation that
     * stopped half a pixel short would leave the player permanently "expanding".
     */
    @Test
    fun `the animation lands exactly on the bound it was given`() {
        val animation =
            TargetBasedAnimation(
                animationSpec = BottomSheetSoftAnimationSpec,
                typeConverter = Dp.VectorConverter,
                initialValue = 0.dp,
                targetValue = travel,
            )
        val finishedAt = animation.durationNanos
        assertEquals(travel, animation.getValueFromNanos(finishedAt))
    }

    /** A drag that is released is a fling, and it is allowed to be softer than a tap. */
    @Test
    fun `a released drag still settles`() {
        val fling = settleMs(BottomSheetAnimationSpec)
        assertTrue("a released drag takes ${fling.toInt()}ms", fling < 700.0)
    }
}
