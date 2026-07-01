/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.constants

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

const val CONTENT_TYPE_HEADER = 0
const val CONTENT_TYPE_LIST = 1
const val CONTENT_TYPE_SONG = 2
const val CONTENT_TYPE_ARTIST = 3
const val CONTENT_TYPE_ALBUM = 4
const val CONTENT_TYPE_PLAYLIST = 5

val FloatingToolbarHeight = 64.dp
val CompactFloatingToolbarHeight = 56.dp
val FloatingToolbarHorizontalPadding = 16.dp
val FloatingToolbarBottomPadding = 8.dp
val CompactFloatingToolbarBottomPadding = 6.dp
val MiniPlayerHeight = 64.dp
val MiniPlayerArtworkOuterSize = 48.dp
val MiniPlayerArtworkInnerSize = 38.dp
val MiniPlayerBottomSpacing = 6.dp
val CompactMiniPlayerBottomSpacing = 4.dp
val QueuePeekHeight = 64.dp
val LandscapeQueuePeekHeight = 44.dp
val LandscapeQueuePeekHeightV9 = 52.dp
val LandscapeUpNextBarHeight = 76.dp
val LandscapePlayerBottomSpacing = 10.dp
val LandscapePlayerCompactHeight = 380.dp
val LandscapePlayerTightHeight = 440.dp

fun landscapeControlsContentFooterGap(compact: Boolean): Dp =
    if (compact) {
        20.dp
    } else {
        24.dp
    }

fun landscapeMetadataReservedHeight(
    compact: Boolean,
    tight: Boolean = compact,
): Dp =
    when {
        compact -> 52.dp
        tight -> 60.dp
        else -> 72.dp
    }

/** Estimated vertical space for landscape progress + transport + optional extras. */
fun landscapeControlsFooterReserve(
    compact: Boolean,
    includeVolume: Boolean = false,
    includeSecondaryActions: Boolean = false,
    sideBySideSecondaryActions: Boolean = false,
): Dp {
    val progressBlock = if (compact) 68.dp else 76.dp
    val progressTransportGap = if (compact) 10.dp else 14.dp
    val transportBlock = if (compact) 68.dp else 76.dp
    val transportSecondaryGap = if (compact) 8.dp else 12.dp
    val volumeBlock = if (includeVolume && !compact) 48.dp else 0.dp
    val volumeGap = if (volumeBlock > 0.dp) 10.dp else 0.dp
    val secondaryBlock =
        when {
            !includeSecondaryActions -> 0.dp
            sideBySideSecondaryActions -> 0.dp
            compact -> 48.dp
            else -> 52.dp
        }
    return progressBlock +
        progressTransportGap +
        transportBlock +
        transportSecondaryGap +
        volumeBlock +
        volumeGap +
        secondaryBlock
}
val LandscapeSleepTimerPeekExtra = 34.dp
val V6QueueBottomBarHeight = 52.dp

/** Reserved height for the collapsed queue sheet in portrait so it does not overlap player artwork. */
fun portraitQueuePeekHeight(
    playerDesignStyle: PlayerDesignStyle,
    showCodecOnPlayer: Boolean,
    sleepTimerEnabled: Boolean,
): Dp {
    val base =
        when (playerDesignStyle) {
            PlayerDesignStyle.V9 -> 88.dp
            PlayerDesignStyle.V7,
            PlayerDesignStyle.V8,
            -> 112.dp
            PlayerDesignStyle.V6 -> V6QueueBottomBarHeight + 4.dp
            PlayerDesignStyle.V4,
            PlayerDesignStyle.V1,
            PlayerDesignStyle.V3,
            -> 124.dp
            PlayerDesignStyle.V2 -> 96.dp
            else -> QueuePeekHeight
        }
    return base +
        (if (showCodecOnPlayer) {
            if (playerDesignStyle == PlayerDesignStyle.V6) 22.dp else 32.dp
        } else {
            0.dp
        }) +
        (if (sleepTimerEnabled && playerDesignStyle != PlayerDesignStyle.V6) 48.dp else 0.dp)
}

/** Reserved height for the collapsed queue sheet in landscape. */
fun landscapeQueuePeekHeight(
    playerDesignStyle: PlayerDesignStyle,
    sleepTimerEnabled: Boolean,
    showCodecOnPlayer: Boolean = false,
): Dp {
    val base =
        when (playerDesignStyle) {
            PlayerDesignStyle.V7,
            PlayerDesignStyle.V8,
            PlayerDesignStyle.V9,
            -> LandscapeUpNextBarHeight + 56.dp
            PlayerDesignStyle.V4,
            PlayerDesignStyle.V6,
            PlayerDesignStyle.V1,
            PlayerDesignStyle.V3,
            -> if (playerDesignStyle == PlayerDesignStyle.V6) 96.dp else 84.dp
            PlayerDesignStyle.V2 -> 76.dp
            else -> LandscapeQueuePeekHeight
        }
    return base +
        (if (showCodecOnPlayer) 32.dp else 0.dp) +
        (if (sleepTimerEnabled) LandscapeSleepTimerPeekExtra else 0.dp)
}

val AppBarHeight = 64.dp

val ListItemHeight = 72.dp
val SuggestionItemHeight = 56.dp
val SearchFilterHeight = 48.dp
val ListThumbnailSize = 56.dp
val SmallGridThumbnailHeight = 104.dp
val GridThumbnailHeight = 128.dp
val AlbumThumbnailSize = 144.dp

val ThumbnailCornerRadius = 10.dp
val GridThumbnailCornerRadius = 8.dp

val PlayerHorizontalPadding = 32.dp

val NavigationBarAnimationSpec =
    spring<Dp>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessLow,
    )

val BottomSheetAnimationSpec =
    spring<Dp>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

val BottomSheetSoftAnimationSpec =
    spring<Dp>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessLow,
    )
