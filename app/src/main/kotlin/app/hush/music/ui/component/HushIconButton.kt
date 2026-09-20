/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.component

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.hush.music.ui.theme.HushDesign

/**
 * Material's icon button with Hush's press motion in it.
 *
 * A drop-in replacement: same parameters, same defaults, same colours - the only difference is that
 * the button gives, springs and throws a ring when pressed. Material's own feedback here is a
 * ripple, and a ripple is an animation: on a device whose animator duration scale is 0 (a developer
 * setting, and what a car head unit runs with) it never appears, which is what "the buttons look
 * static" turned out to be.
 *
 * [haloColor] defaults to the button's own content colour, so a themed button glows in its own
 * colour without every call site having to know about it.
 */
@Composable
fun HushIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    pressScale: Float = HushDesign.PressScale,
    haloColor: Color? = null,
    haloStrength: Float = 1f,
    content: @Composable () -> Unit,
) {
    val source = interactionSource ?: rememberPressInteractionSource()
    IconButton(
        onClick = onClick,
        modifier =
            modifier.hushPressMotion(
                interactionSource = source,
                pressScale = pressScale,
                enabled = enabled,
                haloColor =
                    haloColor
                        ?: (if (enabled) colors.contentColor else colors.disabledContentColor)
                            .takeOrElse { LocalContentColor.current },
                haloStrength = haloStrength,
            ),
        enabled = enabled,
        colors = colors,
        interactionSource = source,
        content = content,
    )
}

/** `Color.Unspecified` means "whatever the theme says", which is not a colour to glow in. */
private inline fun Color.takeOrElse(fallback: () -> Color): Color = if (this == Color.Unspecified) fallback() else this
