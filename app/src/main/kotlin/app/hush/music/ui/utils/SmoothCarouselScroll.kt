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
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.snapFlingBehavior
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberLazyRowCarouselScroll(
    widthFactor: Float,
): Pair<LazyListState, FlingBehavior> {
    val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val snapLayoutInfoProvider =
        remember(lazyListState, widthFactor) {
            SnapLayoutInfoProvider(
                lazyListState = lazyListState,
                positionInLayout = carouselPeekSnapPosition(widthFactor),
            )
        }
    val flingBehavior = rememberSmoothSnapFlingBehavior(snapLayoutInfoProvider)
    return lazyListState to flingBehavior
}
