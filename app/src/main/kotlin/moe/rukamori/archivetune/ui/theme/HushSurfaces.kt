/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.carousel.CarouselItemDrawInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.shape.RoundedCornerShape

@Composable
fun Modifier.hushPlayButtonBackground(
    shape: Shape = RoundedCornerShape(28.dp),
): Modifier {
    val gradient = rememberHushAccentGradient()
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)
    return this
        .clip(shape)
        .background(gradient, shape)
        .border(width = 0.5.dp, color = borderColor, shape = shape)
}

@Composable
fun Modifier.hushCarouselLiveParallax(
    drawInfo: CarouselItemDrawInfo,
): Modifier {
    val sizeRange = (drawInfo.maxSize - drawInfo.minSize).coerceAtLeast(1f)
    val focusFraction = ((drawInfo.size - drawInfo.minSize) / sizeRange).coerceIn(0f, 1f)
    val itemSize = drawInfo.size.coerceAtLeast(1f)
    val lateralOffset = (drawInfo.maskRect.center.x / itemSize - 0.5f)
    return this.graphicsLayer {
        val scale = 0.88f + focusFraction * 0.12f
        scaleX = scale
        scaleY = scale
        translationX = -lateralOffset * itemSize * 0.07f
    }
}

@Composable
fun Modifier.hushCarouselContentParallax(
    drawInfo: CarouselItemDrawInfo,
): Modifier {
    val sizeRange = (drawInfo.maxSize - drawInfo.minSize).coerceAtLeast(1f)
    val focusFraction = ((drawInfo.size - drawInfo.minSize) / sizeRange).coerceIn(0f, 1f)
    val itemSize = drawInfo.size.coerceAtLeast(1f)
    val lateralOffset = (drawInfo.maskRect.center.x / itemSize - 0.5f)
    return this.graphicsLayer {
        translationX = -lateralOffset * itemSize * 0.16f
        val scale = 1f + (1f - focusFraction) * 0.05f
        scaleX = scale
        scaleY = scale
    }
}

@Composable
fun Modifier.hushGradientPrimaryButton(
    shape: Shape = ArchiveTuneDesign.cardShape,
): Modifier {
    val gradient = rememberHushAccentGradient()
    return this
        .clip(shape)
        .background(gradient, shape)
}

@Composable
fun Modifier.hushHomeCarouselCard(
    shape: Shape = ArchiveTuneDesign.cardShape,
    elevation: Dp = 10.dp,
): Modifier {
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f)
    return this
        .shadow(elevation = elevation, shape = shape, clip = false)
        .clip(shape)
        .border(width = 0.5.dp, color = borderColor, shape = shape)
}

@Composable
fun Modifier.hushHomeRowCard(
    shape: Shape = ArchiveTuneDesign.itemShape,
): Modifier {
    val fillColor = MaterialTheme.colorScheme.surfaceContainerLow
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f)
    return this
        .clip(shape)
        .background(fillColor, shape)
        .border(width = 0.5.dp, color = borderColor, shape = shape)
        .padding(6.dp)
}

@Immutable
data class HushMeshColors(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val primaryContainer: Color,
    val secondaryContainer: Color,
    val surface: Color,
)

@Composable
fun rememberHushMeshColors(): HushMeshColors {
    val scheme = MaterialTheme.colorScheme
    return remember(
        scheme.primary,
        scheme.secondary,
        scheme.tertiary,
        scheme.primaryContainer,
        scheme.secondaryContainer,
        scheme.surface,
    ) {
        HushMeshColors(
            primary = scheme.primary,
            secondary = scheme.secondary,
            tertiary = scheme.tertiary,
            primaryContainer = scheme.primaryContainer,
            secondaryContainer = scheme.secondaryContainer,
            surface = scheme.surface,
        )
    }
}

@Composable
fun rememberHushAccentGradient(): Brush {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme.primary, scheme.tertiary, scheme.secondary) {
        Brush.horizontalGradient(
            colors =
                listOf(
                    scheme.primary,
                    scheme.tertiary,
                    scheme.secondary,
                ),
        )
    }
}

@Composable
fun rememberHushAccentGradientVertical(): Brush {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme.primary, scheme.tertiary) {
        Brush.verticalGradient(
            colors =
                listOf(
                    scheme.primary,
                    scheme.tertiary,
                ),
        )
    }
}

/**
 * Ambient mesh gradient used on Home, Library, Search, Settings and other primary tabs.
 */
@Composable
fun HushAmbientBackground(
    modifier: Modifier = Modifier,
    disabled: Boolean = false,
    heightFraction: Float = 0.68f,
    intensity: Float = 1f,
) {
    if (disabled) return

    val colors = rememberHushMeshColors()
    val alphaScale = intensity.coerceIn(0.35f, 1.25f)

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .fillMaxSize(heightFraction)
                .zIndex(-1f)
                .drawWithCache {
                    val width = size.width
                    val height = size.height

                    fun blob(
                        color: Color,
                        centerX: Float,
                        centerY: Float,
                        radiusMul: Float,
                        peakAlpha: Float,
                    ): Brush =
                        Brush.radialGradient(
                            colors =
                                listOf(
                                    color.copy(alpha = peakAlpha * alphaScale),
                                    color.copy(alpha = peakAlpha * 0.62f * alphaScale),
                                    color.copy(alpha = peakAlpha * 0.34f * alphaScale),
                                    color.copy(alpha = peakAlpha * 0.12f * alphaScale),
                                    Color.Transparent,
                                ),
                            center = Offset(width * centerX, height * centerY),
                            radius = width * radiusMul,
                        )

                    val brush1 = blob(colors.primary, 0.12f, 0.08f, 0.58f, 0.42f)
                    val brush2 = blob(colors.secondary, 0.88f, 0.16f, 0.62f, 0.38f)
                    val brush3 = blob(colors.tertiary, 0.28f, 0.42f, 0.64f, 0.34f)
                    val brush4 = blob(colors.primaryContainer, 0.78f, 0.48f, 0.56f, 0.28f)
                    val brush5 = blob(colors.secondaryContainer, 0.5f, 0.72f, 0.72f, 0.22f)
                    val fade =
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    Color.Transparent,
                                    colors.surface.copy(alpha = 0.08f),
                                    colors.surface.copy(alpha = 0.55f),
                                    colors.surface,
                                ),
                            startY = height * 0.35f,
                            endY = height,
                        )

                    onDrawBehind {
                        drawRect(brush1)
                        drawRect(brush2)
                        drawRect(brush3)
                        drawRect(brush4)
                        drawRect(brush5)
                        drawRect(fade)
                    }
                },
    )
}

@Composable
fun HushScreenScaffold(
    modifier: Modifier = Modifier,
    disableAmbient: Boolean = false,
    ambientHeightFraction: Float = 0.68f,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        HushAmbientBackground(
            disabled = disableAmbient,
            heightFraction = ambientHeightFraction,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        content()
    }
}

@Composable
fun HushSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = ArchiveTuneDesign.ScreenHorizontalPadding,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(horizontal = horizontalPadding, vertical = 4.dp),
    )
}

@Composable
fun Modifier.hushGlassSurface(
    shape: Shape = ArchiveTuneDesign.cardShape,
    borderAlpha: Float = 0.38f,
    fillAlpha: Float = 0.72f,
): Modifier {
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = borderAlpha)
    val fillColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = fillAlpha)
    return this
        .clip(shape)
        .background(fillColor, shape)
        .border(width = 0.5.dp, color = borderColor, shape = shape)
}

@Composable
fun Modifier.hushElevatedCard(
    shape: Shape = ArchiveTuneDesign.cardShape,
): Modifier {
    val fillColor = MaterialTheme.colorScheme.surfaceContainerLow
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)
    return this
        .clip(shape)
        .background(fillColor, shape)
        .border(width = 0.5.dp, color = borderColor, shape = shape)
}
