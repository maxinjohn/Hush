/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.utils

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.gestures.snapping.snapFlingBehavior
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.util.fastForEach
import kotlin.math.abs

/** Snap anchor for peek-style horizontal carousels (shows a slice of the next card). */
fun carouselPeekSnapPosition(widthFactor: Float): (layoutSize: Float, itemSize: Float) -> Float =
    { layoutSize, itemSize ->
        layoutSize * widthFactor / 2f - itemSize / 2f
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberSmoothSnapFlingBehavior(
    snapLayoutInfoProvider: SnapLayoutInfoProvider,
): FlingBehavior {
    val snapAnimationSpec =
        remember {
            spring<Float>(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow,
            )
        }
    val decayAnimationSpec =
        remember {
            exponentialDecay<Float>(
                frictionMultiplier = 0.72f,
                absVelocityThreshold = 0.04f,
            )
        }
    return remember(snapLayoutInfoProvider, decayAnimationSpec, snapAnimationSpec) {
        snapFlingBehavior(
            snapLayoutInfoProvider = snapLayoutInfoProvider,
            decayAnimationSpec = decayAnimationSpec,
            snapAnimationSpec = snapAnimationSpec,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberSmoothPagerFlingBehavior(
    state: PagerState,
): TargetedFlingBehavior {
    val snapAnimationSpec =
        remember {
            spring<Float>(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow,
            )
        }
    return PagerDefaults.flingBehavior(
        state = state,
        snapAnimationSpec = snapAnimationSpec,
    )
}

@OptIn(ExperimentalFoundationApi::class)
internal fun resolveNearestSnapOffset(
    bounds: ClosedFloatingPointRange<Float>,
    velocity: Float,
    velocityThreshold: Float = 350f,
): Float {
    if (bounds.start == Float.NEGATIVE_INFINITY && bounds.endInclusive == Float.POSITIVE_INFINITY) {
        return 0f
    }

    return if (abs(velocity) < velocityThreshold) {
        if (abs(bounds.start) < abs(bounds.endInclusive)) {
            bounds.start
        } else {
            bounds.endInclusive
        }
    } else {
        when {
            velocity < 0 -> bounds.start
            velocity > 0 -> bounds.endInclusive
            else -> 0f
        }
    }
}

/**
 * A peek carousel's snap positions, approached the way the framework approaches them.
 *
 * The row this serves is the one that was reported as jerky, and the fault was here. Its snap
 * positions were right - a card lands with its leading edge on the row's content start, the same
 * alignment the peek layout wants - but the snap *animation* was told to begin only once the fling
 * behind it had run out (`calculateApproachOffset` answering `0f`). A fling could therefore travel a
 * distance, come to a dead stop, and only then be hauled up to half a card further on by a spring of
 * its own: two movements and a pause between them, which is exactly what "not smooth and jerky"
 * describes, and what no other list on the device does.
 *
 * The offsets below are unchanged; only the approach is left to the framework, which takes the snap
 * up while the decay is still running, so the row lands on the card it was thrown towards without
 * ever standing still first. [rememberSnapFlingBehavior] brings the framework's own decay and snap
 * specs with it for the same reason - a hand-written `exponentialDecay(frictionMultiplier = 0.72f,
 * absVelocityThreshold = 0.04f)` let the fling creep on for hundreds of near-zero-velocity frames,
 * and a sub-pixel creep on a 3x display is the 1px, 0px, 1px stutter it looks like.
 */
@OptIn(ExperimentalFoundationApi::class)
private class PeekCarouselSnapLayoutInfoProvider(
    private val lazyListState: LazyListState,
    private val widthFactor: Float,
) : SnapLayoutInfoProvider {
    private val layoutInfo: LazyListLayoutInfo
        get() = lazyListState.layoutInfo

    override fun calculateApproachOffset(
        initialVelocity: Float,
        decayOffset: Float,
    ): Float = decayOffset

    override fun calculateSnapOffset(velocity: Float): Float {
        var lowerBoundOffset = Float.NEGATIVE_INFINITY
        var upperBoundOffset = Float.POSITIVE_INFINITY

        layoutInfo.visibleItemsInfo.fastForEach { item ->
            val offset = distanceToSnapPosition(layoutInfo, item)

            if (offset <= 0 && offset > lowerBoundOffset) {
                lowerBoundOffset = offset
            }
            if (offset >= 0 && offset < upperBoundOffset) {
                upperBoundOffset = offset
            }
        }

        return resolveNearestSnapOffset(lowerBoundOffset.rangeTo(upperBoundOffset), velocity)
    }

    private fun distanceToSnapPosition(
        layoutInfo: LazyListLayoutInfo,
        item: LazyListItemInfo,
    ): Float {
        val containerSize =
            layoutInfo.singleAxisViewportSize - layoutInfo.beforeContentPadding - layoutInfo.afterContentPadding
        val desiredDistance = carouselPeekSnapPosition(widthFactor)(containerSize.toFloat(), item.size.toFloat())
        return item.offset.toFloat() - desiredDistance
    }
}

private val LazyListLayoutInfo.singleAxisViewportSize: Int
    get() = if (orientation == Orientation.Vertical) viewportSize.height else viewportSize.width

/**
 * A horizontal row of cards that snaps to the card it was thrown towards.
 *
 * The snap behavior is the framework's own - the one every other snapping list on the device uses,
 * and the one the Quick Picks carousel uses - rather than a hand-written decay and spring, because
 * a fling that ends in a pause and then a second movement is what a person reads as jerky. See
 * [PeekCarouselSnapLayoutInfoProvider].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberLazyRowCarouselScroll(
    widthFactor: Float,
): Pair<LazyListState, FlingBehavior> {
    val lazyListState = rememberLazyListState()
    val snapLayoutInfoProvider =
        remember(lazyListState, widthFactor) {
            PeekCarouselSnapLayoutInfoProvider(
                lazyListState = lazyListState,
                widthFactor = widthFactor,
            )
        }
    val flingBehavior = rememberSnapFlingBehavior(snapLayoutInfoProvider = snapLayoutInfoProvider)
    return lazyListState to flingBehavior
}
