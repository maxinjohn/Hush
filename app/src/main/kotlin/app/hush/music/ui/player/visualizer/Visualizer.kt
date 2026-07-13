/*
 * Hush — GPL-3.0
 * Multi-style visualizer with color themes, rendered on top of album art.
 */

package app.hush.music.ui.player.visualizer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI
import kotlin.random.Random

// ── Style & Theme enums ──────────────────────────────────────────────────────

enum class VisualizerStyle {
    BOTTOM_BARS,
    COLOR_WAVE,
    GLOW_BARS,
    DUAL_BARS,
    PULSE_BARS,
    SPECTRUM,
    JUMPING_BARS,
    PULSE_MATRIX,
}

enum class VisualizerColorTheme {
    THEME,
    RAINBOW,
    NEON,
    MONOCHROME,
    FIRE,
    AURORA,
    OCEAN,
    FOREST,
    SUNSET,
}

internal fun VisualizerColorTheme.toPulseMatrixTheme(): PulseMatrixTheme = when (this) {
    VisualizerColorTheme.THEME -> PulseMatrixTheme.AURORA
    VisualizerColorTheme.AURORA -> PulseMatrixTheme.AURORA
    VisualizerColorTheme.OCEAN -> PulseMatrixTheme.CYAN
    VisualizerColorTheme.FOREST -> PulseMatrixTheme.EMERALD
    VisualizerColorTheme.SUNSET -> PulseMatrixTheme.AMBER
    VisualizerColorTheme.RAINBOW -> PulseMatrixTheme.NEON
    VisualizerColorTheme.NEON -> PulseMatrixTheme.NEON
    VisualizerColorTheme.MONOCHROME -> PulseMatrixTheme.ICE
    VisualizerColorTheme.FIRE -> PulseMatrixTheme.CRIMSON
}

// ── Public entry point ───────────────────────────────────────────────────────

/**
 * Rich visualizer that renders on top of album art.
 * Uses semi-transparent shapes so the album art shows through.
 * Supports multiple [style]s and [colorTheme]s.
 */
@Composable
fun Visualizer(
    isPlaying: Boolean,
    style: VisualizerStyle,
    colorTheme: VisualizerColorTheme,
    opacity: Float = 0.8f,
    modifier: Modifier = Modifier,
    spectrumData: List<Float>? = null,
) {
    val colors = rememberVisualizerColors(colorTheme)
    val alphaModifier = modifier.then(Modifier.alpha(opacity.coerceIn(0f, 1f)))

    when (style) {
        VisualizerStyle.BOTTOM_BARS -> BottomBarsStyle(isPlaying, colors, alphaModifier, spectrumData)
        VisualizerStyle.COLOR_WAVE -> ColorWaveStyle(isPlaying, colors, alphaModifier, spectrumData)
        VisualizerStyle.GLOW_BARS -> GlowBarsStyle(isPlaying, colors, alphaModifier, spectrumData)
        VisualizerStyle.DUAL_BARS -> DualBarsStyle(isPlaying, colors, alphaModifier, spectrumData)
        VisualizerStyle.PULSE_BARS -> PulseBarsStyle(isPlaying, colors, alphaModifier, spectrumData)
        VisualizerStyle.SPECTRUM -> SpectrumStyle(isPlaying, colors, alphaModifier, spectrumData)
        VisualizerStyle.JUMPING_BARS -> JumpingBarsStyle(isPlaying, colors, alphaModifier, spectrumData)
VisualizerStyle.PULSE_MATRIX -> PulseMatrixCanvas(
            theme = colorTheme.toPulseMatrixTheme(),
            opacity = opacity,
            modifier = modifier,
            bands = spectrumData?.toFloatArray(),
        )
    }
}

// ── Color theme resolver ─────────────────────────────────────────────────────

@Composable
fun rememberVisualizerColors(theme: VisualizerColorTheme): List<Color> {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val secondary = MaterialTheme.colorScheme.secondary

    return remember(theme, primary, tertiary, secondary) {
        when (theme) {
            VisualizerColorTheme.THEME -> listOf(primary, tertiary, primary)
            VisualizerColorTheme.RAINBOW -> listOf(
                Color(0xFFFF4444),
                Color(0xFFFFBB33),
                Color(0xFF99CC00),
                Color(0xFF33B5E5),
                Color(0xFFAA66CC),
                Color(0xFFFF4444),
            )
            VisualizerColorTheme.NEON -> listOf(
                Color(0xFF00FFFF),
                Color(0xFFFF00FF),
                Color(0xFF4488FF),
                Color(0xFF00FF88),
                Color(0xFFFF00FF),
            )
            VisualizerColorTheme.MONOCHROME -> listOf(primary, primary, primary)
            VisualizerColorTheme.FIRE -> listOf(
                Color(0xFFFF2200),
                Color(0xFFFF6600),
                Color(0xFFFFAA00),
                Color(0xFFFFCC00),
                Color(0xFFFF8800),
            )
            VisualizerColorTheme.AURORA -> listOf(
                Color(0xFF00E5FF),
                Color(0xFF536DFE),
                Color(0xFF7C4DFF),
                Color(0xFF00BCD4),
                Color(0xFF18FFFF),
            )
            VisualizerColorTheme.OCEAN -> listOf(
                Color(0xFF01579B),
                Color(0xFF0288D1),
                Color(0xFF03A9F4),
                Color(0xFF4FC3F7),
                Color(0xFF80DEEA),
            )
            VisualizerColorTheme.FOREST -> listOf(
                Color(0xFF1B5E20),
                Color(0xFF388E3C),
                Color(0xFF4CAF50),
                Color(0xFF66BB6A),
                Color(0xFFA5D6A7),
            )
            VisualizerColorTheme.SUNSET -> listOf(
                Color(0xFFFF3D00),
                Color(0xFFFF9100),
                Color(0xFFFFEA00),
                Color(0xFFE040FB),
                Color(0xFF536DFE),
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  STYLES
// ══════════════════════════════════════════════════════════════════════════════

// ── 1. BOTTOM_BARS (edge-to-edge spectrum analyzer) ─────────────────────────

private const val BOTTOM_BAR_COUNT = 36
private const val SMOOTH_FACTOR = 0.30f

@Composable
private fun BottomBarsStyle(
    isPlaying: Boolean,
    colors: List<Color>,
    modifier: Modifier,
    spectrumData: List<Float>?,
) {
    val smoothValues = remember { mutableStateOf(FloatArray(BOTTOM_BAR_COUNT) { 0.05f }) }
    val prevValues = remember { mutableStateOf(FloatArray(BOTTOM_BAR_COUNT) { 0.05f }) }

    val infiniteTransition = rememberInfiniteTransition(label = "bottom_bars")
    val syntheticPhase = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "bottom_bars_phase",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        if (!isPlaying) return@Canvas

        val gap = size.width * 0.06f
        val usableWidth = size.width - gap * 2f
        val barWidth = usableWidth / (BOTTOM_BAR_COUNT * 1.25f)
        val spacing = barWidth * 0.25f
        val totalBarWidth = barWidth + spacing
        val leftOffset = (size.width - (BOTTOM_BAR_COUNT * totalBarWidth - spacing)) / 2f

        val currentTargets = FloatArray(BOTTOM_BAR_COUNT) { i ->
            if (spectrumData != null && i < spectrumData.size && spectrumData[i] > 0.02f) {
                spectrumData[i].coerceIn(0.05f, 1f)
            } else {
                val raw = abs(sin((syntheticPhase.value + i * 0.4f) * (1f + i * 0.06f)).toDouble()).toFloat()
                (raw * 0.5f + 0.08f).coerceIn(0.05f, 0.6f)
            }
        }

        val smooth = smoothValues.value
        val prev = prevValues.value
        for (i in 0 until BOTTOM_BAR_COUNT) {
            smooth[i] = prev[i] + (currentTargets[i] - prev[i]) * SMOOTH_FACTOR
        }
        prevValues.value = currentTargets

        // Base glow
        for (i in 0 until BOTTOM_BAR_COUNT) {
            val normVal = smooth[i].coerceIn(0.01f, 1f)
            if (normVal < 0.08f) continue
            val x = leftOffset + i * totalBarWidth
            val barH = normVal * size.height * 0.88f
            val c = colors[i % colors.size]
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        c.copy(alpha = 0f),
                        c.copy(alpha = (normVal * 0.08f).coerceIn(0f, 0.12f)),
                    ),
                ),
                topLeft = Offset(x - barWidth * 0.3f, size.height - barH),
                size = Size(barWidth * 1.6f, barH),
                cornerRadius = CornerRadius(barWidth),
                alpha = 1f,
            )
        }

        // Main bars from the very bottom edge
        for (i in 0 until BOTTOM_BAR_COUNT) {
            val normVal = smooth[i].coerceIn(0.01f, 1f)
            val barH = normVal * size.height * 0.88f
            if (barH <= 1f) continue

            val x = leftOffset + i * totalBarWidth
            val c1 = colors[i % colors.size]
            val c2 = colors[(i + 1) % colors.size]

            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        c1.copy(alpha = (0.2f + normVal * 0.6f).coerceIn(0f, 1f)),
                        c2.copy(alpha = (0.5f + normVal * 0.5f).coerceIn(0f, 1f)),
                    ),
                    startY = size.height - barH,
                    endY = size.height,
                ),
                topLeft = Offset(x, size.height - barH),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                alpha = 1f,
            )

            if (normVal > 0.15f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            c1.copy(alpha = (normVal * 0.55f).coerceIn(0f, 1f)),
                            c1.copy(alpha = 0f),
                        ),
                        radius = barWidth * 0.8f,
                    ),
                    radius = barWidth * 0.8f,
                    center = Offset(x + barWidth / 2f, size.height - barH),
                )
            }
        }
    }
}

// ── 2. COLOR_WAVE (colorful sound wave equalizer) ─────────────────────────────

private const val COLOR_WAVE_COUNT = 6

@Composable
private fun ColorWaveStyle(
    isPlaying: Boolean,
    colors: List<Color>,
    modifier: Modifier,
    spectrumData: List<Float>?,
) {
    val smoothValues = remember { mutableStateOf(FloatArray(COLOR_WAVE_COUNT) { 0.5f }) }
    val prevValues = remember { mutableStateOf(FloatArray(COLOR_WAVE_COUNT) { 0.5f }) }

    val infiniteTransition = rememberInfiniteTransition(label = "color_wave")
    val phases = Array(COLOR_WAVE_COUNT) { idx ->
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 2f * PI.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween((2200 + idx * 250).toInt(), easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "cw_phase_$idx",
        )
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        if (!isPlaying) return@Canvas

        val amplitudeScale = if (spectrumData != null && spectrumData.isNotEmpty()) {
            spectrumData.average().toFloat().coerceIn(0.1f, 1f)
        } else 0.5f

        val targets = FloatArray(COLOR_WAVE_COUNT) { idx ->
            if (spectrumData != null && idx < spectrumData.size && spectrumData[idx] > 0.02f) {
                spectrumData[idx].coerceIn(0.05f, 1f)
            } else 0.5f
        }
        val smooth = smoothValues.value
        val prev = prevValues.value
        for (i in 0 until COLOR_WAVE_COUNT) {
            smooth[i] = prev[i] + (targets[i] - prev[i]) * 0.25f
        }
        prevValues.value = targets

        val width = size.width
        val height = size.height
        val samples = 80

        for (w in 0 until COLOR_WAVE_COUNT) {
            val amp = smooth[w].coerceIn(0.05f, 1f)
            val phaseVal = phases[w].value
            val waveSpeed = 1f + w * 0.2f
            val wavePhase = w * 1.2f
            val freq = 1.8f + w * 0.4f
            val c = colors[w % colors.size]
            val waveHeight = height * 0.35f * amp
            val baseY = height * (0.5f + (w - COLOR_WAVE_COUNT / 2f) * 0.04f)

            val path = Path()
            path.moveTo(0f, height)

            for (i in 0..samples) {
                val x = width * i / samples
                val offset = sin((phaseVal * waveSpeed + i * freq * 0.12f + wavePhase).toDouble()).toFloat()
                val y = baseY + offset * waveHeight
                path.lineTo(x, y)
            }

            path.lineTo(width, height)
            path.close()

            drawPath(
                path = path,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        c.copy(alpha = (0.5f + amp * 0.5f).coerceIn(0f, 1f)),
                        c.copy(alpha = (0.2f + amp * 0.3f).coerceIn(0f, 1f)),
                        c.copy(alpha = 0f),
                    ),
                    startY = baseY - waveHeight,
                    endY = height,
                ),
                alpha = 0.7f,
            )

            drawPath(
                path = Path().apply {
                    moveTo(0f, height)
                    for (i in 0..samples) {
                        val x = width * i / samples
                        val offset = sin((phaseVal * waveSpeed + i * freq * 0.12f + wavePhase).toDouble()).toFloat()
                        val y = baseY + offset * waveHeight
                        lineTo(x, y)
                    }
                },
                brush = Brush.horizontalGradient(
                    colors = colors.map { it.copy(alpha = 0.3f + amp * 0.7f) },
                ),
                style = Stroke(width = 2.5f * amp.coerceAtLeast(0.3f), cap = StrokeCap.Round),
                alpha = 0.8f,
            )
        }
    }
}

// ── 3. GLOW_BARS (neon glow spectrum) ────────────────────────────────────────

private const val GLOW_BAR_COUNT = 28

@Composable
private fun GlowBarsStyle(
    isPlaying: Boolean,
    colors: List<Color>,
    modifier: Modifier,
    spectrumData: List<Float>?,
) {
    val smoothValues = remember { mutableStateOf(FloatArray(GLOW_BAR_COUNT) { 0.05f }) }
    val prevValues = remember { mutableStateOf(FloatArray(GLOW_BAR_COUNT) { 0.05f }) }

    val infiniteTransition = rememberInfiniteTransition(label = "glow_bars")
    val phase = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(2500, easing = LinearEasing), RepeatMode.Restart),
        label = "glow_phase",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        if (!isPlaying) return@Canvas

        val barWidth = size.width / (GLOW_BAR_COUNT * 1.3f)
        val spacing = barWidth * 0.3f
        val totalBarWidth = barWidth + spacing
        val leftOffset = (size.width - (GLOW_BAR_COUNT * totalBarWidth - spacing)) / 2f

        val targets = FloatArray(GLOW_BAR_COUNT) { i ->
            if (spectrumData != null && i < spectrumData.size && spectrumData[i] > 0.02f) {
                spectrumData[i].coerceIn(0.05f, 1f)
            } else {
                val r = abs(sin((phase.value + i * 0.5f) * (1f + i * 0.08f)).toDouble()).toFloat()
                (r * 0.4f + 0.06f).coerceIn(0.05f, 0.5f)
            }
        }

        val smooth = smoothValues.value
        val prev = prevValues.value
        for (i in 0 until GLOW_BAR_COUNT) {
            smooth[i] = prev[i] + (targets[i] - prev[i]) * 0.3f
        }
        prevValues.value = targets

        for (i in 0 until GLOW_BAR_COUNT) {
            val v = smooth[i].coerceIn(0.01f, 1f)
            val barH = v * size.height * 0.88f
            if (barH <= 1f) continue
            val x = leftOffset + i * totalBarWidth
            val c = colors[i % colors.size]

            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(c.copy(alpha = 0f), c.copy(alpha = (v * 0.12f).coerceIn(0f, 0.2f))),
                ),
                topLeft = Offset(x - barWidth * 0.6f, size.height - barH),
                size = Size(barWidth * 2.2f, barH),
                cornerRadius = CornerRadius(barWidth),
                alpha = 1f,
            )
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(c.copy(alpha = (0.15f + v * 0.5f).coerceIn(0f, 1f)), c.copy(alpha = (0.4f + v * 0.6f).coerceIn(0f, 1f))),
                    startY = size.height - barH, endY = size.height,
                ),
                topLeft = Offset(x, size.height - barH),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(barWidth / 2f),
                alpha = 1f,
            )
        }
    }
}

// ── 3. DUAL_BARS (top & bottom symmetric) ───────────────────────────────────

private const val DUAL_BAR_COUNT = 24

@Composable
private fun DualBarsStyle(
    isPlaying: Boolean,
    colors: List<Color>,
    modifier: Modifier,
    spectrumData: List<Float>?,
) {
    val smoothValues = remember { mutableStateOf(FloatArray(DUAL_BAR_COUNT) { 0.05f }) }
    val prevValues = remember { mutableStateOf(FloatArray(DUAL_BAR_COUNT) { 0.05f }) }

    val infiniteTransition = rememberInfiniteTransition(label = "dual_bars")
    val phase = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "dual_phase",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        if (!isPlaying) return@Canvas

        val barWidth = size.width / (DUAL_BAR_COUNT * 1.4f)
        val spacing = barWidth * 0.4f
        val totalBarWidth = barWidth + spacing
        val leftOffset = (size.width - (DUAL_BAR_COUNT * totalBarWidth - spacing)) / 2f
        val midY = size.height * 0.5f

        val targets = FloatArray(DUAL_BAR_COUNT) { i ->
            if (spectrumData != null && i < spectrumData.size && spectrumData[i] > 0.02f) {
                spectrumData[i].coerceIn(0.05f, 1f)
            } else {
                val r = abs(sin((phase.value + i * 0.45f) * (1f + i * 0.07f)).toDouble()).toFloat()
                (r * 0.35f + 0.05f).coerceIn(0.05f, 0.5f)
            }
        }

        val smooth = smoothValues.value
        val prev = prevValues.value
        for (i in 0 until DUAL_BAR_COUNT) {
            smooth[i] = prev[i] + (targets[i] - prev[i]) * 0.28f
        }
        prevValues.value = targets

        for (i in 0 until DUAL_BAR_COUNT) {
            val v = smooth[i].coerceIn(0.01f, 1f)
            val h = v * size.height * 0.42f
            if (h <= 1f) continue
            val x = leftOffset + i * totalBarWidth
            val c = colors[i % colors.size]
            val alpha = (0.3f + v * 0.7f).coerceIn(0f, 1f)

            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(c.copy(alpha = alpha * 0.6f), c.copy(alpha = alpha)),
                    startY = midY - h, endY = midY,
                ),
                topLeft = Offset(x, midY - h),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f),
                alpha = 1f,
            )
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(c.copy(alpha = alpha), c.copy(alpha = alpha * 0.6f)),
                    startY = midY, endY = midY + h,
                ),
                topLeft = Offset(x, midY),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f),
                alpha = 1f,
            )
        }
    }
}

// ── 4. PULSE_BARS (pulse-driven bars) ───────────────────────────────────────

private const val PULSE_BAR_COUNT = 20

@Composable
private fun PulseBarsStyle(
    isPlaying: Boolean,
    colors: List<Color>,
    modifier: Modifier,
    spectrumData: List<Float>?,
) {
    val smoothValues = remember { mutableStateOf(FloatArray(PULSE_BAR_COUNT) { 0.05f }) }
    val prevValues = remember { mutableStateOf(FloatArray(PULSE_BAR_COUNT) { 0.05f }) }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse_bars")
    val phase = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse_phase",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        if (!isPlaying) return@Canvas

        val barWidth = size.width / (PULSE_BAR_COUNT * 1.5f)
        val spacing = barWidth * 0.5f
        val totalBarWidth = barWidth + spacing
        val leftOffset = (size.width - (PULSE_BAR_COUNT * totalBarWidth - spacing)) / 2f

        val spectrumEnergy = if (spectrumData != null && spectrumData.isNotEmpty()) {
            (spectrumData.take(4).average().toFloat().coerceIn(0.1f, 1f))
        } else 0.5f

        val targets = FloatArray(PULSE_BAR_COUNT) { i ->
            if (spectrumData != null && i < spectrumData.size && spectrumData[i] > 0.02f) {
                spectrumData[i].coerceIn(0.05f, 1f)
            } else {
                val r = abs(sin((phase.value + i * 0.5f) * (1f + i * 0.1f)).toDouble()).toFloat()
                (r * 0.3f + 0.05f).coerceIn(0.05f, 0.5f)
            }
        }

        val smooth = smoothValues.value
        val prev = prevValues.value
        for (i in 0 until PULSE_BAR_COUNT) {
            smooth[i] = prev[i] + (targets[i] - prev[i]) * 0.25f * spectrumEnergy
        }
        prevValues.value = targets

        for (i in 0 until PULSE_BAR_COUNT) {
            val v = smooth[i].coerceIn(0.01f, 1f)
            val barH = v * size.height * 0.88f
            if (barH <= 1f) continue
            val x = leftOffset + i * totalBarWidth
            val c = colors[i % colors.size]
            val a = (0.25f + v * 0.75f).coerceIn(0f, 1f)

            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(c.copy(alpha = a * 0.3f), c.copy(alpha = a)),
                    startY = size.height - barH, endY = size.height,
                ),
                topLeft = Offset(x, size.height - barH),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(barWidth / 2f),
                alpha = 1f,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(c.copy(alpha = (v * spectrumEnergy * 0.6f).coerceIn(0f, 1f)), c.copy(alpha = 0f)),
                    radius = barWidth * 1f,
                ),
                radius = barWidth * 1f,
                center = Offset(x + barWidth / 2f, size.height - barH),
            )
        }
    }
}

// ── 5. JUMPING_BARS (bottom-origin equalizer bars) ───────────────────────────

private const val JUMPING_BAR_COUNT = 24

@Composable
private fun JumpingBarsStyle(
    isPlaying: Boolean,
    colors: List<Color>,
    modifier: Modifier,
    spectrumData: List<Float>? = null,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "jumping_bars")
    val barAnims = remember {
        Array(JUMPING_BAR_COUNT) { index ->
            val phase = index * 0.3f
            val speedMul = 1f + (index % 5) * 0.08f
            Pair(phase, speedMul)
        }
    }
    val barValues = Array(JUMPING_BAR_COUNT) { index ->
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 2f * PI.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = (1800 + index * 60),
                    easing = LinearEasing,
                ),
                repeatMode = RepeatMode.Restart,
            ),
            label = "jump_$index",
        )
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        if (!isPlaying) return@Canvas

        val barWidth = size.width / (JUMPING_BAR_COUNT * 1.5f)
        val spacing = barWidth * 0.5f
        val totalBarWidth = barWidth + spacing
        val bottomY = size.height * 0.92f
        val maxHeight = size.height * 0.75f
        val leftOffset = (size.width - (JUMPING_BAR_COUNT * totalBarWidth - spacing)) / 2f

        val spectrumEnergy = if (spectrumData != null && spectrumData.isNotEmpty()) {
            (spectrumData.take(6).average().toFloat().coerceIn(0.3f, 1f))
        } else 1f

        for (i in 0 until JUMPING_BAR_COUNT) {
            val animVal = barValues[i].value
            val (phase, speedMul) = barAnims[i]
            val raw = abs(sin(animVal * speedMul + phase))
            val barH = (raw * 0.92f + 0.08f) * maxHeight * (0.3f + 0.7f * spectrumEnergy)
            if (barH <= 2f) continue

            val x = leftOffset + i * totalBarWidth
            val gradient = Brush.verticalGradient(
                colors = listOf(
                    colors[i % colors.size].copy(alpha = 0.6f),
                    colors[(i + 1) % colors.size].copy(alpha = 0.25f),
                ),
                startY = bottomY - barH,
                endY = bottomY,
            )

            drawRoundRect(
                brush = gradient,
                topLeft = Offset(x, bottomY - barH),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                alpha = (0.45f + (raw * 0.55f)) * (0.4f + 0.6f * spectrumEnergy),
            )
        }

        // Glow line at bar bottoms
        drawLine(
            color = colors[0].copy(alpha = 0.12f),
            start = Offset(leftOffset, bottomY),
            end = Offset(leftOffset + JUMPING_BAR_COUNT * totalBarWidth - spacing, bottomY),
            strokeWidth = 2f,
            cap = StrokeCap.Round,
        )
    }
}

// ── 9. SPECTRUM (real FFT-based audio spectrum bars) ────────────────────────

private const val SPECTRUM_BAR_COUNT = 16

@Composable
private fun SpectrumStyle(
    isPlaying: Boolean,
    colors: List<Color>,
    modifier: Modifier,
    spectrumData: List<Float>? = null,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "spectrum")
    val fallbackAnims = Array(SPECTRUM_BAR_COUNT) { index ->
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 2f * PI.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(1500 + index * 100, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "spec_$index",
        )
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        if (!isPlaying) return@Canvas

        val barWidth = size.width / (SPECTRUM_BAR_COUNT * 1.5f)
        val spacing = barWidth * 0.5f
        val totalBarWidth = barWidth + spacing
        val bottomY = size.height * 0.90f
        val maxHeight = size.height * 0.75f
        val leftOffset = (size.width - (SPECTRUM_BAR_COUNT * totalBarWidth - spacing)) / 2f

        for (i in 0 until SPECTRUM_BAR_COUNT) {
            val normalizedValue = if (spectrumData != null && i < spectrumData.size) {
                spectrumData[i]
            } else {
                val animVal = fallbackAnims[i].value
                val raw = abs(sin(animVal * (1f + i * 0.12f) + i * 0.3f))
                raw * 0.7f + 0.3f
            }

            val barH = (normalizedValue.coerceIn(0.05f, 1f)) * maxHeight
            if (barH <= 2f) continue

            val x = leftOffset + i * totalBarWidth
            val gradient = Brush.verticalGradient(
                colors = listOf(
                    colors[i % colors.size].copy(alpha = 0.7f),
                    colors[(i + 1) % colors.size].copy(alpha = 0.2f),
                ),
                startY = bottomY - barH,
                endY = bottomY,
            )

            drawRoundRect(
                brush = gradient,
                topLeft = Offset(x, bottomY - barH),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(barWidth / 3f, barWidth / 3f),
                alpha = 0.5f + normalizedValue * 0.5f,
            )
        }

        // Bright base line
        drawLine(
            color = colors[0].copy(alpha = 0.2f),
            start = Offset(leftOffset, bottomY),
            end = Offset(leftOffset + SPECTRUM_BAR_COUNT * totalBarWidth - spacing, bottomY),
            strokeWidth = 2f,
            cap = StrokeCap.Round,
        )
    }
}

// ── LiveMesh ────────────────────────────────────────────────────────────────────

private const val MESH_NODES = 48
private const val MESH_EDGE_PX = 160f

private data class MeshNodeConfig(
    val baseX: Float,
    val baseY: Float,
    val phase: Float,
    val speed: Float,
)

private fun buildMeshConfigs(width: Float, height: Float): Array<MeshNodeConfig> {
    val cols = 8
    val rows = 6
    val cellW = width / (cols + 1)
    val cellH = height / (rows + 1)
    val rng = java.util.Random(42)
    return Array(cols * rows) { i ->
        val col = i % cols
        val row = i / cols
        MeshNodeConfig(
            baseX = cellW * (col + 1),
            baseY = cellH * (row + 1),
            phase = rng.nextFloat() * 2f * PI.toFloat(),
            speed = 0.3f + rng.nextFloat() * 0.5f,
        )
    }
}

@Composable
private fun LiveMeshStyle(
    isPlaying: Boolean,
    colors: List<Color>,
    modifier: Modifier,
    spectrumData: List<Float>?,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "livemesh")
    val time = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "mesh_time",
    )
    var meshConfigs by remember { mutableStateOf(emptyArray<MeshNodeConfig>()) }

    Canvas(modifier = modifier.fillMaxSize()) {
        if (!isPlaying) return@Canvas

        if (meshConfigs.size != MESH_NODES) {
            meshConfigs = buildMeshConfigs(size.width, size.height)
        }
        val configs = meshConfigs
        val t = time.value
        val amp = if (spectrumData != null && spectrumData.isNotEmpty()) {
            spectrumData.take(6).average().toFloat().coerceIn(0.1f, 1f)
        } else {
            0.5f
        }

        val positions = configs.map { cfg ->
            val waveX = sin((t * cfg.speed + cfg.phase).toDouble()).toFloat() * 20f * amp
            val waveY = cos((t * cfg.speed * 0.7f + cfg.phase).toDouble()).toFloat() * 20f * amp
            Offset(cfg.baseX + waveX, cfg.baseY + waveY)
        }

        val cols = 8
        val rows = 6

        // Draw mesh connections (horizontal, vertical, diagonal)
        val maxDist = minOf(size.width, size.height) * 0.28f
        for (i in positions.indices) {
            for (j in i + 1 until positions.size) {
                val dx = positions[i].x - positions[j].x
                val dy = positions[i].y - positions[j].y
                val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                if (dist < maxDist) {
                    val alpha = (1f - dist / maxDist) * 0.25f * amp
                    val phase = abs(sin((t + i * 0.3f).toDouble())).toFloat()
                    drawLine(
                        color = colors[i % colors.size].copy(alpha = alpha * (0.5f + phase * 0.5f)),
                        start = positions[i],
                        end = positions[j],
                        strokeWidth = 1.2f * amp,
                    )
                }
            }
        }

        // Draw nodes
        for (i in positions.indices) {
            val pulse = abs(sin((t * 1.5f + i * 0.2f).toDouble())).toFloat()
            val r = (4f + pulse * 5f) * amp
            drawCircle(
                color = colors[i % colors.size].copy(alpha = 0.4f + pulse * 0.5f),
                radius = r,
                center = positions[i],
            )
        }
    }
}
