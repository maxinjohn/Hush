/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.constants

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val CONTENT_TYPE_HEADER = 0
const val CONTENT_TYPE_LIST = 1
const val CONTENT_TYPE_SONG = 2
const val CONTENT_TYPE_ARTIST = 3
const val CONTENT_TYPE_ALBUM = 4
const val CONTENT_TYPE_PLAYLIST = 5

val FloatingToolbarHeight = 64.dp
val CompactFloatingToolbarHeight = 56.dp
val FloatingToolbarHorizontalPadding = 0.dp
val FloatingToolbarBottomPadding = 8.dp
val CompactFloatingToolbarBottomPadding = 6.dp
val MiniPlayerHeight = 64.dp

/**
 * The pill's height at the text size the display asks for, which is [MiniPlayerHeight] whenever that
 * is enough for the two lines it draws.
 *
 * The pill is a fixed 64dp with two lines of text inside - Material's titleMedium over bodySmall -
 * and it was the one surface in the app that did not follow the font scale. Measured on device at a
 * 2.0 font scale, its 48dp of content could not hold the two lines: the artist line came back as a
 * 15.2dp box and was drawn cut in half (the title line had taken its share first), while the pill
 * itself stayed 64dp tall. Everything that places the pill - the sheet's collapsed bound, the
 * insets the screens pad by - has to agree on the number, so it is computed once here and published
 * through [app.hush.music.LocalMiniPlayerHeight] rather than by each site.
 *
 * What the two lines need is asked of the typography rather than assumed, and that is the part that
 * has to be read carefully: since Android 14 a font scale is *not* a multiplier. Android converts sp
 * to dp through a curve that grows more slowly above roughly 20sp, and Compose applies the same
 * curve, so the 24sp and 16sp line heights measure 36dp and 28dp at a 2.0 scale rather than the 48dp
 * and 32dp a multiplication would give. Adding the 40sp up front and converting the sum - the first
 * version of this - returns 46.9dp where the two lines really take 64dp, which is less than the 64dp
 * pill it was being compared against, so the pill stayed exactly as clipped as before. Asking each
 * line for its own height is what makes the two agree at every scale, including the ones where the
 * curve is linear.
 */
val MiniPlayerContentVerticalPadding = 8.dp

/** Material's defaults, for a theme that leaves a line height unspecified. */
private const val MiniPlayerTitleLineHeightSp = 24f
private const val MiniPlayerArtistLineHeightSp = 16f

/**
 * @see MiniPlayerHeight
 *
 * The floor is the skin's own: a skin whose parent draws a 56dp pill gets a 56dp pill, and one whose
 * parent draws 64dp gets 64dp, while the two lines inside still push the number up at a large text
 * size in either case. Read from [app.hush.music.ui.theme.LocalMiniPlayerAnatomy] rather than passed
 * in, so the sheet's collapsed bound, the insets the screens pad by and the pill itself all keep
 * agreeing without three call sites having to thread a number between them.
 */
@Composable
fun rememberMiniPlayerHeight(): Dp {
    val typography = MaterialTheme.typography
    val titleLineHeight = typography.titleMedium.lineHeight
    val artistLineHeight = typography.bodySmall.lineHeight
    val lines =
        with(LocalDensity.current) {
            titleLineHeight.orSp(MiniPlayerTitleLineHeightSp).toDp() to
                artistLineHeight.orSp(MiniPlayerArtistLineHeightSp).toDp()
        }
    return miniPlayerHeight(
        titleLineHeight = lines.first,
        artistLineHeight = lines.second,
        floor = app.hush.music.ui.theme.LocalMiniPlayerAnatomy.current.height,
    )
}

/**
 * The pill's height for the two lines it draws, given their heights in dp.
 *
 * Split out of [rememberMiniPlayerHeight] so the arithmetic can be pinned by a test without a
 * composition - the conversion from sp is where the bug was, and it is the same conversion either
 * way.
 */
internal fun miniPlayerHeight(
    titleLineHeight: Dp,
    artistLineHeight: Dp,
    floor: Dp = MiniPlayerHeight,
): Dp = maxOf(floor, titleLineHeight + artistLineHeight + MiniPlayerContentVerticalPadding * 2)

/**
 * A line height to measure with: the type's own, or Material's default when the theme leaves it open.
 *
 * An `em` line height would need the font size to mean anything and nothing in the app sets one, so
 * it is treated the same as an unspecified one rather than measured as if it were dp.
 */
private fun TextUnit.orSp(fallbackSp: Float): TextUnit =
    if (type == TextUnitType.Sp) this else fallbackSp.sp

val MiniPlayerArtworkOuterSize = 48.dp
val MiniPlayerArtworkInnerSize = 38.dp
val MiniPlayerBottomSpacing = 6.dp
val CompactMiniPlayerBottomSpacing = 4.dp
val QueuePeekHeight = 64.dp
val LandscapeQueuePeekHeight = 44.dp
val LandscapeQueuePeekHeightV9 = 52.dp
val LandscapeUpNextBarHeight = 76.dp
// Gap between the landscape player content and the collapsed queue sheet. Small on purpose:
// the sheet's own slack (AuraLandscapePeekSlack) already keeps the glow clear of the content,
// and every dp here is a dp the landscape artwork cannot use.
val LandscapePlayerBottomSpacing = 6.dp

/** Landscape Aura bottom chips: a 38dp touch target inside a 42dp bar. */
val AuraLandscapePillHeight = 38.dp
val AuraLandscapeBarVerticalPadding = 2.dp

/**
 * Slack the collapsed sheet keeps above its content. The player reserves
 * (peek + system bottom inset + LandscapePlayerBottomSpacing) at the bottom, so the glow and the
 * artwork clear the first sheet line by exactly (slack + spacing) on every device - the system
 * inset cancels out because both the sheet bound and the player reserve include it.
 */
val AuraLandscapePeekSlack = 4.dp

/**
 * Breathing room the landscape Aura artwork keeps under itself. The chip row and the provenance
 * line are right-aligned and the cover only ever occupies the left column, so the player does not
 * reserve the whole bottom band for the artwork - this hairline is all it pays, and the canvas
 * reaches the bottom of the screen on every window size.
 */
val AuraLandscapeArtBottomReserve = 14.dp

/**
 * Layout height of the landscape seek row. Material's own slider centres its track inside a 48dp
 * touch row, and the 21dp of air that leaves *under* the bar is what made the seek bar read as
 * floating high above the transport. Landscape therefore gives the row this short strip and drops
 * the shared slider by [AuraLandscapeSeekSharedDrop] so the track lands on the strip's bottom edge
 * - directly over the play button - without moving anything else in the column.
 */
val AuraLandscapeSeekStripHeight = 26.dp

/**
 * Dead air Material leaves under the track once the row is constrained to
 * [AuraLandscapeSeekStripHeight]: the shared slider centres its track in whatever height it is
 * given, so the bar sits (strip - stroke) / 2 above the strip's bottom and that is exactly how far
 * the row has to move down for the track to land on it. Six dp is the stroke Wavy / Circular draw
 * (Simple is 3dp, Thick 12dp); the caller picks the right value per style.
 */
val AuraLandscapeSeekSharedStroke = 6.dp
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
            PlayerDesignStyle.AURA -> V6QueueBottomBarHeight + 4.dp
            PlayerDesignStyle.V4,
            PlayerDesignStyle.V1,
            PlayerDesignStyle.V3,
            -> 124.dp
            PlayerDesignStyle.V2 -> 96.dp
            else -> QueuePeekHeight
        }
    return base +
        (if (showCodecOnPlayer) {
            if (playerDesignStyle == PlayerDesignStyle.V6 || playerDesignStyle == PlayerDesignStyle.AURA) 22.dp else 32.dp
        } else {
            0.dp
        }) +
        (if (sleepTimerEnabled && playerDesignStyle != PlayerDesignStyle.V6 && playerDesignStyle != PlayerDesignStyle.AURA) 48.dp else 0.dp)
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
            // Aura draws a compact chip row in landscape: AuraLandscapePillHeight (38dp) plus
            // AuraLandscapeBarVerticalPadding on each side, so the bar measures 42dp. The sheet
            // content is bottom-anchored and the bar pads for the real system bottom inset
            // itself, so the reserve only needs the bar plus the slack - anything more was dead
            // band at the sheet's top that the landscape artwork could not use.
            PlayerDesignStyle.AURA -> AuraLandscapePillHeight + AuraLandscapeBarVerticalPadding * 2 + AuraLandscapePeekSlack
            else -> LandscapeQueuePeekHeight
        }
    return base +
        // Aura renders its provenance label inline beside the landscape chips (it shares the
        // chip row's height), so unlike every other style the codec adds nothing to the reserve -
        // that freed band is what lets the landscape artwork reach the bottom of the screen.
        (if (showCodecOnPlayer) {
            if (playerDesignStyle == PlayerDesignStyle.AURA) 0.dp else 32.dp
        } else {
            0.dp
        }) +
        (if (sleepTimerEnabled && playerDesignStyle != PlayerDesignStyle.AURA) LandscapeSleepTimerPeekExtra else 0.dp)
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

// Both sheet springs carry an explicit [spring] visibilityThreshold. A Dp spring's default is a
// hundredth of a pixel, so the last stretch of every transition was spent animating movements
// nobody can see - on the sheet, that is a full screen of content still being re-drawn for a
// fraction of a pixel of travel. Half a pixel is below what a display can show, and the animation
// still lands exactly on its target value.
val SheetSettleThreshold = 0.5.dp

val BottomSheetAnimationSpec =
    spring<Dp>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = SheetSettleThreshold,
    )

// StiffnessMedium, not Low. Low (200) takes roughly three quarters of a second to cross a screen,
// which every frame of a transition has to be drawn within - and, more to the point, is long enough
// to read as hesitation when a person taps the mini player expecting the player. Medium (1500)
// arrives in about a third of that and still leaves from and lands on the same places.
val BottomSheetSoftAnimationSpec =
    spring<Dp>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
        visibilityThreshold = SheetSettleThreshold,
    )
