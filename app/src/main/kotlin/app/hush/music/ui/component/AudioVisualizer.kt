/*
 * Hush (2026)
 * Audio visualizer UI — Canvas-based frequency spectrum bars, waveform,
 * and circular visualizers that react to real-time audio data from
 * [AudioVisualizerEngine].
 *
 * This is a new feature not present in the parent ArchiveTune project.
 */

package app.hush.music.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.hush.music.playback.VisualizerBands
import app.hush.music.playback.VisualizerStyle
import app.hush.music.playback.VisualizerWaveform
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// ── Style selectors ──────────────────────────────────────────────────────────

/**
 * Renders the chosen [style] visualizer inside the given [modifier] bounds.
 *
 * @param bands     Frequency band data.
 * @param waveform  Waveform data.
 * @param style     Which visual style to render.
 * @param paletteColors  Optional list of colors from album artwork for multi-color gradient. When non-null, overrides accentColor.
 * @param accentColor  Primary color for the visualization. When null, the theme primary is used.
 * @param dimmed    When true, renders at lower opacity (good for background use).
 * @param opacityOverride  Override the dimmed/normal opacity. (0..1). When non-null, takes precedence over dimmed.
 */
@Composable
fun AudioVisualizer(
    bands: VisualizerBands,
    waveform: VisualizerWaveform,
    style: VisualizerStyle,
    modifier: Modifier = Modifier,
    paletteColors: List<Color>? = null,
    accentColor: Color? = null,
    dimmed: Boolean = false,
    opacityOverride: Float? = null,
) {
    val primary = accentColor ?: MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val surface = MaterialTheme.colorScheme.surface

    val baseAlpha = opacityOverride ?: if (dimmed) 0.35f else 1f
    val alpha = baseAlpha.coerceIn(0f, 1f)
    val colorPrimary = primary.copy(alpha = alpha)
    val colorTertiary = tertiary.copy(alpha = alpha * 0.8f)
    val colorSurface = surface.copy(alpha = alpha * 0.5f)

    // Apply alpha to palette colors as well
    val paletteWithAlpha = paletteColors?.map { it.copy(alpha = alpha) }

    // Animate the band data for smooth transitions
    var animatedBands by remember { mutableStateOf(bands) }
    LaunchedEffect(bands) {
        animatedBands = bands
    }

    var animatedWaveform by remember { mutableStateOf(waveform) }
    LaunchedEffect(waveform) {
        animatedWaveform = waveform
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            when (style) {
                VisualizerStyle.BARS -> drawBars(animatedBands, colorPrimary, colorTertiary, paletteWithAlpha)
                VisualizerStyle.WAVEFORM -> drawWaveform(animatedWaveform, colorPrimary, colorSurface, paletteWithAlpha)
                VisualizerStyle.CIRCULAR -> drawCircular(animatedBands, colorPrimary, colorTertiary, colorSurface, paletteWithAlpha)
            }
        }
    }
}

// ── Bar-style frequency spectrum ─────────────────────────────────────────────

private fun DrawScope.drawBars(
    bands: VisualizerBands,
    primary: Color,
    tertiary: Color,
    paletteColors: List<Color>? = null,
) {
    val barCount = bands.bands.size
    if (barCount == 0) return

    val totalWidth = size.width
    val totalHeight = size.height
    val barSpacing = totalWidth / barCount * 0.18f
    val barWidth = (totalWidth / barCount) - barSpacing
    val baseY = totalHeight

    for (i in 0 until barCount) {
        val amplitude = bands.bands[i].coerceIn(0f, 1f)
        val peakAmplitude = bands.peakBands[i].coerceIn(0f, 1f)

        if (amplitude < 0.003f && peakAmplitude < 0.003f) continue

        val barHeight = amplitude * totalHeight
        val x = (i.toFloat() / barCount) * totalWidth + barSpacing / 2f
        val y = baseY - barHeight

        // Color: use palette if available, otherwise gradient from primary to tertiary
        val color =
            if (paletteColors != null && paletteColors.isNotEmpty()) {
                val paletteIndex = ((i.toFloat() / barCount) * paletteColors.size).toInt().coerceIn(0, paletteColors.size - 1)
                paletteColors[paletteIndex].copy(alpha = 0.5f + amplitude * 0.5f)
            } else {
                val fraction = amplitude.coerceIn(0f, 1f)
                when {
                    fraction < 0.33f -> primary.copy(alpha = 0.5f + fraction * 0.5f)
                    fraction < 0.66f -> primary.copy(alpha = 0.8f)
                    else -> tertiary.copy(alpha = 0.6f + fraction * 0.4f)
                }
            }

        // Draw bar with rounded top
        val cornerRadius = (barWidth * 0.35f).coerceAtMost(4f)
        drawRoundRect(
            color = color,
            topLeft = Offset(x, y),
            size = Size(barWidth, barHeight),
            cornerRadius = CornerRadius(cornerRadius, cornerRadius),
        )

        // Draw peak dot
        if (peakAmplitude > 0.02f) {
            val peakY = baseY - (peakAmplitude * totalHeight)
            val peakDotRadius = (barWidth * 0.22f).coerceAtLeast(1.5f)
            drawCircle(
                color = tertiary.copy(alpha = 0.7f),
                radius = peakDotRadius,
                center = Offset(x + barWidth / 2f, peakY),
            )
        }
    }
}

// ── Waveform style ───────────────────────────────────────────────────────────

private fun DrawScope.drawWaveform(
    waveform: VisualizerWaveform,
    primary: Color,
    surface: Color,
    paletteColors: List<Color>? = null,
) {
    val samples = waveform.samples
    if (samples.isEmpty()) return

    val width = size.width
    val height = size.height
    val centerY = height / 2f
    val maxAmplitude = height * 0.42f

    val path = Path()
    val stepX = width / (samples.size - 1).coerceAtLeast(1)

    // Build a symmetric waveform: center is 0.5, values above/below swing around center
    path.moveTo(0f, centerY)

    for (i in samples.indices) {
        val x = i * stepX
        // Center the waveform: 0.5 = silence, >0.5 = positive swing, <0.5 = negative swing
        val normalized = (samples[i] - 0.5f) * 2f  // -1..1
        val y = centerY - (normalized * maxAmplitude)
        path.lineTo(x, y)
    }

    // Line to the right edge at center level
    path.lineTo(width, centerY)

    // Draw the filled waveform with a gradient (palette or theme)
    val gradientColors =
        if (paletteColors != null && paletteColors.size >= 2) {
            paletteColors.map { it.copy(alpha = 0.6f) }
        } else {
            listOf(
                primary.copy(alpha = 0.6f),
                primary.copy(alpha = 0.2f),
                primary.copy(alpha = 0.05f),
            )
        }
    val gradientBrush = Brush.verticalGradient(
        colors = gradientColors,
        startY = centerY - maxAmplitude,
        endY = centerY + maxAmplitude,
    )

    drawPath(
        path = path,
        brush = gradientBrush,
        style = Stroke(
            width = 2.5f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )

    // Draw subtle glow line using the first palette color or primary
    val glowColor = paletteColors?.firstOrNull()?.copy(alpha = 0.15f) ?: primary.copy(alpha = 0.15f)
    drawPath(
        path = path,
        color = glowColor,
        style = Stroke(
            width = 8f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
}

// ── Circular / radial frequency spectrum ─────────────────────────────────────

private fun DrawScope.drawCircular(
    bands: VisualizerBands,
    primary: Color,
    tertiary: Color,
    surface: Color,
    paletteColors: List<Color>? = null,
) {
    val bandCount = bands.bands.size
    if (bandCount == 0) return

    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val radius = min(size.width, size.height) * 0.35f
    val startAngle = -90f  // Start from top

    val angleStep = 360f / bandCount
    val barWidth = angleStep * 0.60f

    for (i in 0 until bandCount) {
        val amplitude = bands.bands[i].coerceIn(0f, 1f)
        val peakAmplitude = bands.peakBands[i].coerceIn(0f, 1f)

        if (amplitude < 0.003f && peakAmplitude < 0.003f) continue

        val barLength = amplitude * radius * 0.7f
        val innerRadius = radius * 0.30f
        val outerRadius = innerRadius + barLength

        val angle = startAngle + i * angleStep
        val angleRad = Math.toRadians(angle.toDouble())
        val halfAngleRad = Math.toRadians((angle + barWidth / 2f).toDouble())

        // Calculate start and end points
        val x1 = (centerX + innerRadius * cos(angleRad).toFloat()).toFloat()
        val y1 = (centerY + innerRadius * sin(angleRad).toFloat()).toFloat()
        val x2 = (centerX + outerRadius * cos(angleRad).toFloat()).toFloat()
        val y2 = (centerY + outerRadius * sin(angleRad).toFloat()).toFloat()

        // Color based on amplitude — interpolate from palette if available
        val color =
            if (paletteColors != null && paletteColors.isNotEmpty()) {
                val paletteIndex = ((i.toFloat() / bandCount) * paletteColors.size).toInt().coerceIn(0, paletteColors.size - 1)
                paletteColors[paletteIndex].copy(alpha = 0.4f + amplitude * 0.6f)
            } else {
                val fraction = amplitude.coerceIn(0f, 1f)
                when {
                    fraction < 0.33f -> primary.copy(alpha = 0.4f + fraction * 0.6f)
                    fraction < 0.66f -> primary.copy(alpha = 0.7f)
                    else -> tertiary.copy(alpha = 0.5f + fraction * 0.5f)
                }
            }

        // Draw bar as a thick line from inner to outer radius
        drawLine(
            color = color,
            start = Offset(x1, y1),
            end = Offset(x2, y2),
            strokeWidth = 3.dp.toPx().coerceAtLeast(2f),
            cap = StrokeCap.Round,
        )

        // Draw peak dot
        if (peakAmplitude > 0.02f) {
            val peakOuterRadius = innerRadius + peakAmplitude * radius * 0.7f
            val px = centerX + peakOuterRadius * cos(angleRad).toFloat()
            val py = centerY + peakOuterRadius * sin(angleRad).toFloat()
            drawCircle(
                color = tertiary.copy(alpha = 0.6f),
                radius = 2.5f,
                center = Offset(px, py),
            )
        }
    }

    // Draw subtle center ring
    drawCircle(
        color = primary.copy(alpha = 0.12f),
        radius = radius * 0.30f,
        center = Offset(centerX, centerY),
        style = Stroke(width = 1.5f),
    )
}
