/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.hush.music.LocalAnimationsDisabled

/**
 * When true, search/explore/suggestions pages use a vibrant Gen-Z visual style
 * with glass morphism, bold gradients, and enhanced typography.
 */
val LocalExploreTheme = compositionLocalOf { false }

object HushDesign {
    val ChipCornerRadius = 24.dp
    val ItemCornerRadius = 20.dp
    val CardCornerRadius = 32.dp
    val SearchBarCornerRadius = 24.dp
    val ThumbnailCornerRadius = 18.dp
    val NavItemCornerRadius = 28.dp
    val MiniButtonCornerRadius = 50.dp
    val ToolbarCornerRadius = 32.dp
    val HeaderActionCornerRadius = 14.dp
    val HeaderActionSize = 56.dp
    val HeaderActionIconSize = 26.dp

    const val HeaderActionPressScale = 0.82f

    val ScreenHorizontalPadding = 16.dp
    val ScreenCompactHorizontalPadding = 12.dp
    val SectionVerticalSpacing = 20.dp
    val ItemVerticalSpacing = 6.dp

    const val PressScale = 0.92f
    const val RowPressScale = 0.96f
    const val NavPressScale = 0.88f
    const val ChipPressScale = 0.88f
    const val TransportPressScale = 0.90f
    const val SelectedChipScale = 1.05f

    val chipShape: Shape get() = RoundedCornerShape(ChipCornerRadius)
    val headerActionShape: Shape get() = RoundedCornerShape(HeaderActionCornerRadius)
    val itemShape: Shape get() = RoundedCornerShape(ItemCornerRadius)
    val cardShape: Shape get() = RoundedCornerShape(CardCornerRadius)
    val navItemShape: Shape get() = RoundedCornerShape(NavItemCornerRadius)
    val toolbarShape: Shape get() = RoundedCornerShape(ToolbarCornerRadius)
    val searchBarShape: Shape get() = RoundedCornerShape(SearchBarCornerRadius)
}

object HushMotion {
    @Composable
    fun <T> fastSpring(): FiniteAnimationSpec<T> =
        if (LocalAnimationsDisabled.current) {
            androidx.compose.animation.core.snap()
        } else {
            spring(stiffness = Spring.StiffnessHigh, dampingRatio = Spring.DampingRatioLowBouncy)
        }

    @Composable
    fun <T> gentleSpring(): FiniteAnimationSpec<T> =
        if (LocalAnimationsDisabled.current) {
            androidx.compose.animation.core.snap()
        } else {
            spring(stiffness = Spring.StiffnessMedium, dampingRatio = Spring.DampingRatioMediumBouncy)
        }
}

@Composable
fun rememberArchiveTunePressScale(
    interactionSource: MutableInteractionSource,
    pressScale: Float = HushDesign.PressScale,
): Float {
    val isPressed by interactionSource.collectIsPressedAsState()
    val animationsDisabled = LocalAnimationsDisabled.current
    val scale by animateFloatAsState(
        targetValue = if (!animationsDisabled && isPressed) pressScale else 1f,
        animationSpec = HushMotion.fastSpring(),
        label = "archiveTunePressScale",
    )
    return scale
}

@Composable
fun rememberArchiveTunePressFeedback(
    interactionSource: MutableInteractionSource,
    pressScale: Float = HushDesign.PressScale,
): Pair<Float, Float> {
    val isPressed by interactionSource.collectIsPressedAsState()
    val animationsDisabled = LocalAnimationsDisabled.current
    val scale by animateFloatAsState(
        targetValue = if (!animationsDisabled && isPressed) pressScale else 1f,
        animationSpec = HushMotion.gentleSpring(),
        label = "archiveTunePressFeedbackScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (!animationsDisabled && isPressed) 0.88f else 1f,
        animationSpec = HushMotion.fastSpring(),
        label = "archiveTunePressFeedbackAlpha",
    )
    return scale to alpha
}

fun Modifier.graphicsLayerPressScale(scale: Float): Modifier =
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }

fun Modifier.hushPressable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    pressScale: Float = HushDesign.PressScale,
    role: Role? = null,
): Modifier =
    composed {
        val interactionSource = remember { MutableInteractionSource() }
        val scale = rememberArchiveTunePressScale(interactionSource, pressScale)
        this
            .graphicsLayerPressScale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = role,
                onClick = onClick,
            )
    }

fun Modifier.archiveTuneHeaderActionPressable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    role: Role? = null,
): Modifier =
    composed {
        val interactionSource = remember { MutableInteractionSource() }
        val (scale, alpha) =
            rememberArchiveTunePressFeedback(
                interactionSource = interactionSource,
                pressScale = HushDesign.HeaderActionPressScale,
            )
        this
            .graphicsLayerPressScale(scale)
            .alpha(alpha)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = role,
                onClick = onClick,
            )
    }

fun Modifier.hushCombinedPressable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    pressScale: Float = HushDesign.PressScale,
    role: Role? = null,
): Modifier =
    composed {
        val interactionSource = remember { MutableInteractionSource() }
        val scale = rememberArchiveTunePressScale(interactionSource, pressScale)
        this
            .graphicsLayerPressScale(scale)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = role,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = null,
            )
    }
