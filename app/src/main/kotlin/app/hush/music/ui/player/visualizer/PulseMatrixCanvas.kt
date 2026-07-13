package app.hush.music.ui.player.visualizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

enum class PulseMatrixTheme {
    NEON,
    AMBER,
    CYAN,
    EMERALD,
    CRIMSON,
    VIOLET,
    ICE,
    AURORA,
}

private class PeakState(
    var height: Float = 0f,
    var holdFrames: Int = 0,
)

private data class BarConfig(
    val minBars: Int,
    val maxBars: Int,
    val barGap: Dp,
    val segmentHeight: Dp,
    val segmentGap: Dp,
    val capThickness: Dp,
    val capGap: Dp,
    val capWidthScale: Float,
    val peakHoldFrames: Int,
    val peakFallPerFrame: Float,
    val minBarWidth: Dp = Dp(8f),
)

private fun fullBarConfig(): BarConfig = BarConfig(
    minBars = 18,
    maxBars = 28,
    barGap = Dp(4f),
    segmentHeight = Dp(6f),
    segmentGap = Dp(2f),
    capThickness = Dp(2.5f),
    capGap = Dp(2f),
    capWidthScale = 1.15f,
    peakHoldFrames = 20,
    peakFallPerFrame = 0.012f,
)

private fun miniBarConfig(): BarConfig = BarConfig(
    minBars = 6,
    maxBars = 10,
    barGap = Dp(1f),
    segmentHeight = Dp(3f),
    segmentGap = Dp(0.8f),
    capThickness = Dp(1.5f),
    capGap = Dp(0.8f),
    capWidthScale = 1.15f,
    peakHoldFrames = 10,
    peakFallPerFrame = 0.008f,
    minBarWidth = Dp(3f),
)

@Composable
fun PulseMatrixCanvas(
    theme: PulseMatrixTheme = PulseMatrixTheme.NEON,
    opacity: Float = 1f,
    modifier: Modifier = Modifier,
    bands: FloatArray? = null,
    miniMode: Boolean = false,
) {
    val barConfig = remember(miniMode) { if (miniMode) miniBarConfig() else fullBarConfig() }
    val peaks = remember { mutableListOf<PeakState>() }
    val themeStyle = remember(theme) { resolveThemeStyle(theme) }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f || bands == null) return@Canvas

        val barGapPx = barConfig.barGap.toPx()
        val segHeightPx = barConfig.segmentHeight.toPx()
        val segGapPx = barConfig.segmentGap.toPx()
        val capThickPx = barConfig.capThickness.toPx()
        val capGapPx = barConfig.capGap.toPx()

        val minBarWidthPx = barConfig.minBarWidth.toPx()
        val barCount = (w / (minBarWidthPx + barGapPx)).toInt()
            .coerceIn(barConfig.minBars, barConfig.maxBars)

        while (peaks.size < barCount) peaks.add(PeakState())
        while (peaks.size > barCount) peaks.removeLast()

        val totalGapPx = barGapPx * (barCount - 1)
        val barWidthPx = (w - totalGapPx) / barCount
        val segStepPx = segHeightPx + segGapPx

        val interpolated = interpolateBands(bands, barCount)
        val effectiveOpacity = opacity.coerceIn(0f, 1f)

        for (i in 0 until barCount) {
            val value = interpolated[i].coerceIn(0f, 1f)
            val barHeightPx = value * h
            val fraction = if (barCount > 1) i.toFloat() / (barCount - 1) else 0.5f
            val colorIndex = (fraction * (themeStyle.barColors.size - 1)).roundToInt()
                .coerceIn(0, themeStyle.barColors.size - 1)
            val barColor = themeStyle.barColors[colorIndex]
            val barX = i * (barWidthPx + barGapPx)

            if (barHeightPx > segGapPx) {
                val fullSegs = (barHeightPx / segStepPx).toInt()
                val remainder = barHeightPx - fullSegs * segStepPx

                for (seg in 0 until fullSegs) {
                    val segBot = h - seg * segStepPx
                    val segTop = segBot - segHeightPx
                    drawRoundRect(
                        color = barColor.copy(alpha = themeStyle.barAlpha * effectiveOpacity),
                        topLeft = Offset(barX, segTop),
                        size = Size(barWidthPx, segHeightPx),
                        cornerRadius = CornerRadius(
                            segHeightPx * 0.35f,
                            segHeightPx * 0.35f,
                        ),
                    )
                }

                if (remainder > segGapPx * 0.5f) {
                    val segBot = h - fullSegs * segStepPx
                    val segTop = segBot - remainder
                    val partialAlpha = effectiveOpacity * (remainder / segStepPx)
                        .coerceIn(0.25f, 1f)
                    drawRoundRect(
                        color = barColor.copy(alpha = themeStyle.barAlpha * partialAlpha),
                        topLeft = Offset(barX, segTop),
                        size = Size(barWidthPx, remainder),
                        cornerRadius = CornerRadius(
                            segHeightPx * 0.35f,
                            segHeightPx * 0.35f,
                        ),
                    )
                }
            }

            val p = peaks[i]
            if (barHeightPx > p.height) {
                p.height = barHeightPx
                p.holdFrames = barConfig.peakHoldFrames
            } else if (p.holdFrames > 0) {
                p.holdFrames--
            } else {
                p.height -= barConfig.peakFallPerFrame * h
                if (p.height < 0f) p.height = 0f
            }

            if (p.height > 0f && PulseMatrixSettings.peakHoldEnabled) {
                val capW = barWidthPx * barConfig.capWidthScale
                val capX = barX + (barWidthPx - capW) / 2f
                val capY = h - p.height - capThickPx - capGapPx
                if (capY >= 0f) {
                    drawRoundRect(
                        color = themeStyle.peakCapColor.copy(
                            alpha = themeStyle.peakCapAlpha * effectiveOpacity,
                        ),
                        topLeft = Offset(capX, capY),
                        size = Size(capW, capThickPx),
                        cornerRadius = CornerRadius(
                            capThickPx * 0.5f,
                            capThickPx * 0.5f,
                        ),
                    )
                }
            }
        }
    }
}

internal fun interpolateBands(bands: FloatArray, targetCount: Int): FloatArray {
    val result = FloatArray(targetCount)
    val sourceCount = bands.size
    if (sourceCount == 0) return result

    if (sourceCount == targetCount) {
        bands.copyInto(result)
        return result
    }

    for (i in 0 until targetCount) {
        val srcPos = i.toFloat() / (targetCount - 1).coerceAtLeast(1) * (sourceCount - 1)
        val srcIdx = srcPos.toInt().coerceAtMost(sourceCount - 2)
        val frac = srcPos - srcIdx
        val lo = bands[srcIdx.coerceAtMost(sourceCount - 1)]
        val hi = bands[(srcIdx + 1).coerceAtMost(sourceCount - 1)]
        result[i] = lo + (hi - lo) * frac
    }
    return result
}

private fun resolveThemeStyle(theme: PulseMatrixTheme): PulseMatrixThemeStyle {
    val themeId = PulseMatrixThemeId.entries.firstOrNull {
        it.name == theme.name
    } ?: PulseMatrixThemeId.AURORA
    return PulseMatrixThemeRegistry.getStyle(themeId)
}
