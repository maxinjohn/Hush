/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.component.shimmer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.valentinilk.shimmer.defaultShimmerTheme
import com.valentinilk.shimmer.shimmer

@Composable
fun ShimmerHost(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = verticalArrangement,
        modifier =
            modifier
                .shimmer()
                .graphicsLayer(alpha = 0.99f)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(listOf(Color.Black, Color.Transparent)),
                        blendMode = BlendMode.DstIn,
                    )
                },
        content = content,
    )
}

/**
 * Left on the library's animation on purpose.
 *
 * The shimmer is decoration over a skeleton: the skeleton blocks are the signal that something is
 * loading, and with the system's animation scale at 0 they still say exactly that - a still
 * highlight is what a skeleton looks like in most apps. The indicators and loops whose motion *is*
 * the message run on [app.hush.music.ui.component.rememberFramePhase], which the setting cannot
 * silence. Changing this one would mean replacing a maintained library for no information gained.
 */
val ShimmerTheme =
    defaultShimmerTheme.copy(
        animationSpec =
            infiniteRepeatable(
                animation =
                    tween(
                        durationMillis = 800,
                        easing = LinearEasing,
                        delayMillis = 250,
                    ),
                repeatMode = RepeatMode.Restart,
            ),
        shaderColors =
            listOf(
                Color.Unspecified.copy(alpha = 0.25f),
                Color.Unspecified.copy(alpha = 0.50f),
                Color.Unspecified.copy(alpha = 0.25f),
            ),
    )
