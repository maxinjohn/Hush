/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp
import app.hush.music.lyrics.WordTimestamp

/** Constrain lyric content to the viewport and clip animated scale overflow. */
internal fun Modifier.lyricsViewport(): Modifier = fillMaxWidth().clipToBounds()

internal fun Modifier.lyricLineWidth(): Modifier = fillMaxWidth()

/** Scripts that lack reliable space breaks and need character-level wrapping. */
internal fun isComplexScriptLyric(text: String): Boolean {
    var complex = 0
    var latin = 0
    for (ch in text) {
        when {
            isComplexScriptCodePoint(ch.code) -> complex++
            ch.isLetter() -> latin++
        }
    }
    return complex > 0 && complex >= latin
}

/** @see isComplexScriptLyric */
internal fun isCjkDominantLyric(text: String): Boolean = isComplexScriptLyric(text)

private fun isComplexScriptCodePoint(code: Int): Boolean =
    code in 0x4E00..0x9FFF ||
        code in 0x3040..0x30FF ||
        code in 0xAC00..0xD7A3 ||
        code in 0x3400..0x4DBF ||
        code in 0x0900..0x097F || // Devanagari
        code in 0x0980..0x09FF || // Bengali
        code in 0x0A00..0x0A7F || // Gurmukhi
        code in 0x0A80..0x0AFF || // Gujarati
        code in 0x0B00..0x0B7F || // Oriya
        code in 0x0B80..0x0BFF || // Tamil
        code in 0x0C00..0x0C7F || // Telugu
        code in 0x0C80..0x0CFF || // Kannada
        code in 0x0D00..0x0D7F || // Malayalam
        code in 0x0D80..0x0DFF || // Sinhala
        code in 0x0E00..0x0E7F || // Thai
        code in 0x0600..0x06FF || // Arabic
        code in 0x0590..0x05FF || // Hebrew
        code in 0x1100..0x11FF || // Hangul Jamo
        code in 0x3130..0x318F // Hangul Compatibility Jamo

/** Whether a lyric line should use per-word FlowRow (Latin) vs full-line Text wrapping. */
internal fun lyricLineUsesWordFlow(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.contains(' ') && !isComplexScriptLyric(trimmed)
}

/**
 * Split lyric content into wrap-friendly segments for karaoke / bounce layouts.
 * Complex scripts are split per character; long unbroken Latin runs are chunked.
 */
internal fun splitLyricTextForWrapping(
    text: String,
    maxRunLength: Int = 8,
): List<String> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return emptyList()
    return when {
        lyricLineUsesWordFlow(trimmed) ->
            trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }

        isComplexScriptLyric(trimmed) ->
            trimmed.map { it.toString() }

        trimmed.length > maxRunLength ->
            trimmed.chunked(maxRunLength.coerceIn(4, 12))

        else -> listOf(trimmed)
    }
}

/** Split an LRC line into bounce segments (words or screen-friendly chunks). */
internal fun splitLrcWordsForBounce(text: String): List<String> = splitLyricTextForWrapping(text)

/** True when a TTML/LRC token must be broken up before layout (Indic/CJK/long runs). */
internal fun shouldSplitLyricTokenForWrapping(text: String): Boolean =
    isComplexScriptLyric(text) || text.length > 8

/**
 * Break long unbroken TTML tokens into smaller segments so FlowRow can wrap them on narrow screens.
 */
internal fun WordTimestamp.segmentsForWrapping(maxRunLength: Int = 10): List<WordTimestamp> {
    val content = text
    if (content.isBlank() || content == " " || content == "\n") return listOf(this)

    val segments =
        when {
            content.contains(' ') && !isComplexScriptLyric(content) -> {
                if (content.length <= maxRunLength) {
                    return listOf(this)
                }
                content.split(Regex("\\s+")).filter { it.isNotEmpty() }
            }

            isComplexScriptLyric(content) ->
                content.map { it.toString() }

            content.length <= maxRunLength ->
                return listOf(this)

            else -> content.chunked(maxRunLength.coerceIn(4, 12))
        }

    if (segments.size <= 1) return listOf(this)

    val duration = (endTime - startTime).coerceAtLeast(0.001)
    return segments.mapIndexed { index, segment ->
        val start = startTime + duration * index / segments.size
        val end = startTime + duration * (index + 1) / segments.size
        copy(text = segment, startTime = start, endTime = end)
    }
}

/** Scale user lyric size down slightly on very narrow viewports. */
internal fun effectiveLyricFontSize(
    baseSizeSp: Float,
    viewportWidth: Dp,
): Float {
    val width = viewportWidth.value
    return when {
        width <= 0f -> baseSizeSp
        width < 320f -> baseSizeSp * 0.84f
        width < 360f -> baseSizeSp * 0.92f
        width > 600f -> baseSizeSp * 1.04f
        else -> baseSizeSp
    }
}
