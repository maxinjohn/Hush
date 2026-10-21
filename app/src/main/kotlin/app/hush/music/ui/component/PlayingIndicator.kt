/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.hush.music.R
import app.hush.music.constants.ThumbnailCornerRadius

/** How much of its height a bar keeps at rest: the dot it sits on between rises. */
private const val PlayingIndicatorRestFraction = 0.12f

@Composable
fun PlayingIndicator(
    color: Color,
    modifier: Modifier = Modifier,
    bars: Int = 3,
    barWidth: Dp = 4.dp,
    cornerRadius: Dp = ThumbnailCornerRadius,
) {
    // Frame time, not animation time. Each bar used to run an Animatable at a fresh random height
    // every 50ms, and the system's animator duration scale completes an animation outright: with it
    // at 0 - the state this was measured in - the bars snapped to a new height twenty times a
    // second, which reads as flicker rather than as music. A frame callback is not scaled by
    // anything the user set, so the same bars rise on every device.
    //
    // One shared period per bar, so a list row and the player's NOW PLAYING pill are the same
    // indicator instead of two inventions.
    val rises =
        List(bars.coerceAtLeast(1)) { index ->
            IndeterminateMotion.equalizerBarRise(
                rememberFramePhase(IndeterminateMotion.equalizerPeriodMillis(index)),
            )
        }

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = modifier,
    ) {
        rises.forEach { rise ->
            Canvas(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .width(barWidth),
            ) {
                val fraction = PlayingIndicatorRestFraction + (1f - PlayingIndicatorRestFraction) * rise
                val height = size.height * fraction
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x = 0f, y = size.height - height),
                    size = size.copy(height = height),
                    cornerRadius = CornerRadius(cornerRadius.toPx()),
                )
            }
        }
    }
}

@Composable
fun PlayingIndicatorBox(
    modifier: Modifier = Modifier,
    isActive: Boolean,
    playWhenReady: Boolean,
    color: Color = LocalContentColor.current,
) {
    AnimatedVisibility(
        visible = isActive,
        enter = fadeIn(tween(500)),
        exit = fadeOut(tween(500)),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier,
        ) {
            if (playWhenReady) {
                PlayingIndicator(
                    color = color,
                    modifier = Modifier.height(24.dp),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.play),
                    contentDescription = null,
                    tint = color,
                )
            }
        }
    }
}
