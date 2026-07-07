/*
 * Hush — ocean wave lyrics animation, ported from Vivi Music (GPL-3.0)
 * Upstream: com.music.vivi.ui.component.ViviMusicLyrics
 *
 * Features:
 * - Global wave progress: ONE continuous sweep across the whole sentence
 *   instead of per-word independent fills (creates fluid ocean-like motion)
 * - Trailing-feather gradient: soft 12% edge where the wave front meets
 *   unfilled words, producing a smooth Apple Music-style transition
 * - Sentence linger: active state held 180ms after line deactivates so
 *   the last word can finish its fill animation before cross-fading away
 * - Progressive blur (600ms FastOutSlowInEasing) for non-active lines
 * - Smooth alpha falloff: 0.75 → 0.50 → 0.30 → 0.20 (250ms FastOutSlowInEasing)
 * - Scale: 1.05x tween over 400ms for the active line
 * - Space glyphs animate in sync with preceding word's wave progress
 * - No-word-timestamp fallback: whole sentence sweeps at once with glow
 */

package app.hush.music.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hush.music.constants.LyricsLineBlurKey
import app.hush.music.lyrics.LyricsEntry
import app.hush.music.ui.screens.settings.LyricsPosition
import app.hush.music.utils.rememberPreference
import kotlinx.coroutines.delay

/**
 * Apple Music-style ocean wave lyrics line.
 *
 * Uses a single continuous wave progress (0→1) that sweeps from the first word
 * to the last, creating a fluid motion across the entire sentence. Each word
 * reads where the wave front sits within its own bounds, producing a unified
 * fill animation instead of per-word independent fills.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OceanWaveLyricsLine(
    entry: LyricsEntry,
    nextEntryTime: Long?,
    effectivePlaybackPosition: Long,
    isSynced: Boolean,
    isActive: Boolean,
    distanceFromCurrent: Int,
    lyricsTextPosition: LyricsPosition,
    textColor: Color,
    showRomanized: Boolean,
    showTranslated: Boolean,
    textSize: Float,
    lineSpacing: Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isSelected: Boolean,
    isSelectionModeActive: Boolean,
    isAutoScrollActive: Boolean,
    expressiveAccent: Color,
    modifier: Modifier = Modifier
) {
    val (lyricsLineBlur) = rememberPreference(LyricsLineBlurKey, true)

    // ── Sentence linger ────────────────────────────────────────────────────────
    // Keep the "active" state alive for 180 ms after the line deactivates so
    // the last word can finish its fill animation before cross-fading away.
    val lingeredIsActive = remember { mutableStateOf(false) }
    LaunchedEffect(isActive) {
        if (isActive) {
            lingeredIsActive.value = true
        } else {
            delay(180L)
            lingeredIsActive.value = false
        }
    }

    // ── Blur ──────────────────────────────────────────────────────────────────
    val targetBlur = if (!lyricsLineBlur || !isAutoScrollActive || isActive || !isSynced || isSelectionModeActive) {
        0f
    } else {
        when (distanceFromCurrent) {
            1 -> 0f
            2 -> 0f
            3 -> 2f
            4 -> 4f
            else -> 6f
        }
    }

    val animatedBlur by animateFloatAsState(
        targetValue = targetBlur,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "blur"
    )

    // ── Line duration for sentence-level timing ────────────────────────────────
    val duration = remember(entry.time, nextEntryTime) {
        if (nextEntryTime != null) nextEntryTime - entry.time else 4000L
    }
    val activeDuration = remember(duration) {
        (duration * 0.95).toLong().coerceAtLeast(300L)
    }

    // ── Word data: use precise timestamps when available ──────────────────────
    val hasWordTimestamps = entry.words != null && entry.words.isNotEmpty() &&
        !app.hush.music.lyrics.LyricsUtils.isHindi(entry.text)

    val wordData = remember(entry.text, entry.words, activeDuration) {
        if (hasWordTimestamps) {
            entry.words!!.mapIndexed { _, word ->
                val wordStart = ((word.startTime * 1000).toLong() - entry.time).coerceAtLeast(0L)
                val wordEnd = ((word.endTime * 1000).toLong() - entry.time).coerceAtLeast(wordStart + 50L)
                Triple(word.text, wordStart, wordEnd)
            }
        } else {
            null // signals sentence-level fallback below
        }
    }

    // ── Alpha falloff — soft curve ────────────────────────────────────────────
    val targetAlpha = when {
        !isSynced || (isSelectionModeActive && isSelected) -> 1f
        lingeredIsActive.value -> 1f
        distanceFromCurrent == 1 -> 0.75f
        distanceFromCurrent == 2 -> 0.50f
        distanceFromCurrent == 3 -> 0.30f
        else -> 0.20f
    }

    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "lineAlpha"
    )

    // ── Scale — smooth tween, no bounce ─────────────────────────────────────
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1.05f else 1f,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "lineScale"
    )

    val itemModifier = modifier
        .fillMaxWidth()
        .graphicsLayer {
            this.alpha = animatedAlpha
            this.scaleX = scale
            this.scaleY = scale
        }
        .clip(RoundedCornerShape(16.dp))
        .combinedClickable(
            enabled = true,
            onClick = onClick,
            onLongClick = onLongClick
        )
        .background(
            if (isSelected && isSelectionModeActive)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
            else Color.Transparent
        )
        .padding(horizontal = 24.dp, vertical = (8 * lineSpacing).dp)
        .blur(animatedBlur.dp)

    // ── Agent alignment ───────────────────────────────────────────────────────
    val agentAlignment = when {
        entry.agent == "v1000" -> Alignment.CenterHorizontally
        entry.agent == "v1" -> Alignment.Start
        entry.agent == "v2" -> Alignment.End
        else -> when (lyricsTextPosition) {
            LyricsPosition.LEFT -> Alignment.Start
            LyricsPosition.CENTER -> Alignment.CenterHorizontally
            LyricsPosition.RIGHT -> Alignment.End
        }
    }

    val agentTextAlign = when {
        entry.agent == "v1000" -> TextAlign.Center
        entry.agent == "v1" -> TextAlign.Left
        entry.agent == "v2" -> TextAlign.Right
        else -> when (lyricsTextPosition) {
            LyricsPosition.LEFT -> TextAlign.Left
            LyricsPosition.CENTER -> TextAlign.Center
            LyricsPosition.RIGHT -> TextAlign.Right
        }
    }

    Column(
        modifier = itemModifier,
        horizontalAlignment = agentAlignment
    ) {
        if (wordData != null) {
            // ── WORD-BY-WORD mode — Ocean Wave ────────────────────────────────
            // ONE global wave progress (0→1) sweeps continuously from the first
            // word to the last. Each word just reads where the wave front sits
            // within its own bounds, creating a single fluid motion across the
            // whole sentence instead of per-word independent fills.

            val globalEnd = remember(wordData) {
                wordData.last().third.coerceAtLeast(1L) // endRelative of the last word
            }
            val lineRelTime = (effectivePlaybackPosition - entry.time).coerceAtLeast(0L)

            val rawGlobalWave = (lineRelTime.toFloat() / globalEnd.toFloat()).coerceIn(0f, 1f)

            // Animate as one smooth value — tight follow (80ms)
            val globalWave by animateFloatAsState(
                targetValue = rawGlobalWave,
                animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing),
                label = "globalWaveProgress"
            )

            // Wave-front feather width: 12% of each word's local span.
            val waveFeather = 0.12f

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = when (agentTextAlign) {
                    TextAlign.Center -> Arrangement.Center
                    TextAlign.Right -> Arrangement.End
                    else -> Arrangement.Start
                },
                verticalArrangement = Arrangement.spacedBy(
                    with(LocalDensity.current) {
                        (textSize * (lineSpacing.coerceAtMost(1.3f) - 1f)).sp.toDp()
                    }
                )
            ) {
                wordData.forEachIndexed { index, (wordText, startRelative, endRelative) ->
                    // Map global wave position into this word's local 0→1 space.
                    val wordStartFrac = startRelative.toFloat() / globalEnd
                    val wordEndFrac = endRelative.toFloat() / globalEnd
                    val wordSpan = (wordEndFrac - wordStartFrac).coerceAtLeast(0.001f)

                    // Local word progress: 0 = wave hasn't reached this word yet,
                    // 1 = wave has fully passed through this word.
                    val wordLocalProgress = ((globalWave - wordStartFrac) / wordSpan)
                        .coerceIn(0f, 1f)

                    // Glow tracks fill intensity
                    val glowAlpha = 0.6f * wordLocalProgress
                    val glowRadius = (12f * wordLocalProgress).coerceAtLeast(0.1f)

                    val finalFontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold

                    // ── Wave brush — trailing feather only ───────────────────
                    val waveFront = wordLocalProgress
                    val waveTail = (wordLocalProgress + waveFeather).coerceAtMost(1f)

                    val wordBrush = when {
                        wordLocalProgress <= 0f -> Brush.horizontalGradient(
                            colors = listOf(
                                textColor.copy(alpha = 0.45f),
                                textColor.copy(alpha = 0.45f)
                            )
                        )
                        wordLocalProgress >= 1f -> Brush.horizontalGradient(
                            colors = listOf(textColor, textColor)
                        )
                        else -> Brush.horizontalGradient(
                            0f        to textColor,
                            waveFront to textColor,
                            waveTail  to textColor.copy(alpha = 0.45f),
                            1f        to textColor.copy(alpha = 0.45f)
                        )
                    }

                    Text(
                        text = wordText,
                        fontSize = textSize.sp,
                        style = TextStyle(
                            brush = wordBrush,
                            fontWeight = finalFontWeight,
                            lineHeight = (textSize * lineSpacing.coerceAtMost(1.3f)).sp,
                            textAlign = agentTextAlign,
                            shadow = Shadow(
                                color = textColor.copy(alpha = glowAlpha),
                                offset = Offset.Zero,
                                blurRadius = glowRadius
                            )
                        )
                    )

                    if (index != wordData.lastIndex) {
                        // Space glyph follows the wave front
                        val spaceAlpha = (0.45f + 0.55f * wordLocalProgress).coerceIn(0.45f, 1f)
                        Text(
                            text = " ",
                            fontSize = textSize.sp,
                            color = textColor.copy(alpha = spaceAlpha),
                            lineHeight = (textSize * lineSpacing.coerceAtMost(1.3f)).sp,
                            style = TextStyle(
                                shadow = if (wordLocalProgress >= 1f) {
                                    Shadow(
                                        color = textColor.copy(alpha = 0.3f),
                                        offset = Offset.Zero,
                                        blurRadius = 6f
                                    )
                                } else null
                            )
                        )
                    }
                }
            }
        } else {
            // ── SENTENCE-LEVEL fallback ───────────────────────────────────────
            // No word timestamps -> highlight the whole sentence at once.
            val targetSentenceAlpha = if (isActive || lingeredIsActive.value) 1f else 0.45f

            val sentenceAlpha by animateFloatAsState(
                targetValue = targetSentenceAlpha,
                animationSpec = tween(
                    durationMillis = 300,
                    easing = FastOutSlowInEasing
                ),
                label = "sentenceAlpha"
            )

            val finalFontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold

            Text(
                text = entry.text,
                fontSize = textSize.sp,
                color = textColor.copy(alpha = sentenceAlpha),
                style = TextStyle(
                    fontWeight = finalFontWeight,
                    lineHeight = (textSize * lineSpacing.coerceAtMost(1.3f)).sp,
                    textAlign = agentTextAlign,
                    shadow = if (sentenceAlpha > 0.45f) {
                        val factor = (sentenceAlpha - 0.45f) / 0.55f
                        Shadow(
                            color = textColor.copy(alpha = 0.5f * factor),
                            offset = Offset.Zero,
                            blurRadius = (10f * factor).coerceAtLeast(0.1f)
                        )
                    } else null
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // ── Romanized text ────────────────────────────────────────────────────
        if (showRomanized) {
            val romanizedText by entry.romanizedTextFlow.collectAsState()
            romanizedText?.let { romanized ->
                Text(
                    text = romanized,
                    fontSize = (textSize * 0.65f).sp,
                    color = textColor.copy(alpha = 0.6f),
                    textAlign = agentTextAlign,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 2.dp).fillMaxWidth(),
                    lineHeight = (textSize * 0.65f * lineSpacing.coerceAtMost(1.3f)).sp
                )
            }
        }

        // ── Translated text ───────────────────────────────────────────────────
        if (showTranslated) {
            entry.providerTranslationText?.let { translated ->
                Text(
                    text = translated,
                    fontSize = (textSize * 0.7f).sp,
                    color = textColor.copy(alpha = 0.8f),
                    textAlign = agentTextAlign,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                    lineHeight = (textSize * 0.7f * lineSpacing.coerceAtMost(1.3f)).sp
                )
            }
        }
    }
}
