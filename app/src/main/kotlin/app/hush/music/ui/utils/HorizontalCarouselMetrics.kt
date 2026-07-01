/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.utils

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class HorizontalCarouselMetrics(
    val itemWidth: Dp,
    val widthFactor: Float,
)

fun horizontalCarouselMetrics(
    containerWidth: Dp,
    isLandscape: Boolean,
    interItemSpacing: Dp = 8.dp,
    contentPaddingHorizontal: Dp = 16.dp,
    minItemWidth: Dp = 112.dp,
    maxItemWidth: Dp = 196.dp,
): HorizontalCarouselMetrics {
    if (containerWidth <= 0.dp) {
        return HorizontalCarouselMetrics(itemWidth = 160.dp, widthFactor = 0.5f)
    }

    val availableWidth =
        (containerWidth - contentPaddingHorizontal * 2).coerceAtLeast(minItemWidth)

    val targetVisibleItems =
        when {
            availableWidth >= 1040.dp -> if (isLandscape) 5.5f else 3f
            availableWidth >= 840.dp -> if (isLandscape) 5f else 2.5f
            availableWidth >= 640.dp -> if (isLandscape) 4f else 2f
            availableWidth >= 480.dp -> if (isLandscape) 3f else 2f
            availableWidth >= 320.dp -> 2f
            else -> 1.2f
        }

    val gapCount = (targetVisibleItems - 1f).coerceAtLeast(0f)
    val computedWidth = (availableWidth - interItemSpacing * gapCount) / targetVisibleItems
    val itemWidth = computedWidth.coerceIn(minItemWidth, maxItemWidth)
    val widthFactor = (itemWidth / containerWidth).coerceIn(0.15f, 0.9f)

    return HorizontalCarouselMetrics(itemWidth = itemWidth, widthFactor = widthFactor)
}

@Composable
fun rememberHorizontalCarouselMetrics(
    containerWidth: Dp,
    interItemSpacing: Dp = 8.dp,
    contentPaddingHorizontal: Dp = 16.dp,
    minItemWidth: Dp = 112.dp,
    maxItemWidth: Dp = 196.dp,
): HorizontalCarouselMetrics {
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    return remember(
        containerWidth,
        isLandscape,
        interItemSpacing,
        contentPaddingHorizontal,
        minItemWidth,
        maxItemWidth,
    ) {
        horizontalCarouselMetrics(
            containerWidth = containerWidth,
            isLandscape = isLandscape,
            interItemSpacing = interItemSpacing,
            contentPaddingHorizontal = contentPaddingHorizontal,
            minItemWidth = minItemWidth,
            maxItemWidth = maxItemWidth,
        )
    }
}

@Composable
fun rememberHeroCarouselMetrics(containerWidth: Dp): HorizontalCarouselMetrics =
    rememberHorizontalCarouselMetrics(
        containerWidth = containerWidth,
        minItemWidth = 128.dp,
        maxItemWidth = 220.dp,
    )

fun HorizontalCarouselMetrics.heroCarouselHeight(
    minHeight: Dp = 180.dp,
    maxHeight: Dp = 300.dp,
): Dp {
    val aspect = 304f / 268f
    return (itemWidth * aspect).coerceIn(minHeight, maxHeight)
}
