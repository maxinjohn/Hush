/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.lyrics

import app.hush.music.constants.SYSTEM_DEFAULT
import java.lang.Character.UnicodeScript
import java.util.Locale

/**
 * Reject-only filter for mismatched lyric languages.
 *
 * Malayalam and other regional Indian lyrics are always accepted when they are not
 * clearly the wrong script (e.g. Arabic or Japanese on an Indian track).
 */
object LyricsLanguageFilter {
    private enum class LyricsScript {
        LATIN,
        DEVANAGARI,
        MALAYALAM,
        TAMIL,
        TELUGU,
        KANNADA,
        BENGALI,
        GUJARATI,
        GURMUKHI,
        ORIYA,
        ARABIC,
        HEBREW,
        THAI,
        HIRAGANA,
        KATAKANA,
        HANGUL,
        HAN,
        CYRILLIC,
        OTHER,
    }

    private val INDIAN_SCRIPTS =
        setOf(
            LyricsScript.DEVANAGARI,
            LyricsScript.MALAYALAM,
            LyricsScript.TAMIL,
            LyricsScript.TELUGU,
            LyricsScript.KANNADA,
            LyricsScript.BENGALI,
            LyricsScript.GUJARATI,
            LyricsScript.GURMUKHI,
            LyricsScript.ORIYA,
        )

    private val FOREIGN_SCRIPTS =
        setOf(
            LyricsScript.ARABIC,
            LyricsScript.HEBREW,
            LyricsScript.THAI,
            LyricsScript.HIRAGANA,
            LyricsScript.KATAKANA,
            LyricsScript.HANGUL,
            LyricsScript.CYRILLIC,
        )

    private val LANGUAGE_SCRIPT_MAP: Map<String, Set<LyricsScript>> =
        mapOf(
            "en" to setOf(LyricsScript.LATIN),
            "hi" to setOf(LyricsScript.DEVANAGARI, LyricsScript.LATIN),
            "mr" to setOf(LyricsScript.DEVANAGARI, LyricsScript.LATIN),
            "ne" to setOf(LyricsScript.DEVANAGARI, LyricsScript.LATIN),
            "bn" to setOf(LyricsScript.BENGALI, LyricsScript.LATIN),
            "ta" to setOf(LyricsScript.TAMIL, LyricsScript.LATIN),
            "te" to setOf(LyricsScript.TELUGU, LyricsScript.LATIN),
            "ml" to setOf(LyricsScript.MALAYALAM, LyricsScript.LATIN),
            "kn" to setOf(LyricsScript.KANNADA, LyricsScript.LATIN),
            "gu" to setOf(LyricsScript.GUJARATI, LyricsScript.LATIN),
            "pa" to setOf(LyricsScript.GURMUKHI, LyricsScript.LATIN),
            "or" to setOf(LyricsScript.ORIYA, LyricsScript.LATIN),
            "ja" to setOf(LyricsScript.HIRAGANA, LyricsScript.KATAKANA, LyricsScript.HAN),
            "ko" to setOf(LyricsScript.HANGUL),
            "zh" to setOf(LyricsScript.HAN),
            "ar" to setOf(LyricsScript.ARABIC, LyricsScript.LATIN),
            "he" to setOf(LyricsScript.HEBREW),
            "th" to setOf(LyricsScript.THAI),
            "ru" to setOf(LyricsScript.CYRILLIC, LyricsScript.LATIN),
            "uk" to setOf(LyricsScript.CYRILLIC, LyricsScript.LATIN),
            "id" to setOf(LyricsScript.LATIN),
            "ms" to setOf(LyricsScript.LATIN),
            "vi" to setOf(LyricsScript.LATIN),
        )

    private val INDONESIAN_MARKERS =
        setOf(
            "yang",
            "dan",
            "dengan",
            "dalam",
            "tidak",
            "adalah",
            "aku",
            "kamu",
            "kau",
            "untuk",
            "ini",
            "itu",
            "ada",
            "juga",
            "akan",
            "bisa",
            "saja",
            "dari",
            "pada",
            "kalau",
            "tapi",
            "atau",
            "telah",
            "sudah",
            "karena",
            "hanya",
            "masih",
            "sangat",
            "lebih",
            "belum",
            "engkau",
            "ku",
            "mu",
            "nya",
            "lah",
            "pun",
        )

    fun isAcceptableLyrics(
        lyrics: String,
        title: String,
        artist: String,
        contentLanguage: String? = null,
        contentCountry: String? = null,
    ): Boolean = relevanceScore(lyrics, title, artist, contentLanguage, contentCountry) >= 0

    fun relevanceScore(
        lyrics: String,
        title: String,
        artist: String,
        contentLanguage: String? = null,
        contentCountry: String? = null,
    ): Int {
        val visibleText = LyricsUtils.displayLyricsText(lyrics)
        if (visibleText.isBlank()) return 0

        val distribution = scriptDistribution(visibleText)
        if (distribution.isEmpty()) return 1

        val expected = buildExpectedScripts(title, artist, contentLanguage, contentCountry)
        val metadataScripts = scriptsInText("$title $artist")
        val dominant = dominantScripts(distribution)

        var score = 0
        val matchedExpected = dominant.intersect(expected)
        if (matchedExpected.isNotEmpty()) {
            score += 80 + matchedExpected.size * 10
        }

        val matchedMetadata = dominant.intersect(metadataScripts)
        if (matchedMetadata.isNotEmpty()) {
            score += 120
        }

        val unexpectedForeign = dominant.intersect(FOREIGN_SCRIPTS) - expected
        if (unexpectedForeign.isNotEmpty()) {
            return -1_000
        }

        val unexpectedHan =
            if (LyricsScript.HAN in dominant && LyricsScript.HAN !in expected) {
                val hasJapaneseKana =
                    LyricsScript.HIRAGANA in distribution || LyricsScript.KATAKANA in distribution
                val hasKorean = LyricsScript.HANGUL in distribution
                !hasJapaneseKana && !hasKorean
            } else {
                false
            }
        if (unexpectedHan) {
            return -1_000
        }

        if (LyricsScript.LATIN in dominant && metadataScripts.any { it in INDIAN_SCRIPTS }) {
            if (looksLikeIndonesianLatin(visibleText)) {
                return -1_000
            }
            if (expected.any { it in INDIAN_SCRIPTS }) {
                score += 40
            }
        }

        if (score <= 0 && dominant.any { it in INDIAN_SCRIPTS }) {
            return 50
        }

        if (score == 0 && dominant.all { it == LyricsScript.LATIN || it == LyricsScript.OTHER }) {
            return 10
        }

        return score
    }

    private fun buildExpectedScripts(
        title: String,
        artist: String,
        contentLanguage: String?,
        contentCountry: String?,
    ): Set<LyricsScript> {
        val expected = linkedSetOf<LyricsScript>()
        val metadataScripts = scriptsInText("$title $artist")
        expected += metadataScripts

        if (metadataScripts.any { it in INDIAN_SCRIPTS }) {
            expected += INDIAN_SCRIPTS
            expected += LyricsScript.LATIN
        }

        resolveContentLanguageCode(contentLanguage)?.let { code ->
            LANGUAGE_SCRIPT_MAP[code]?.let { expected += it }
        }

        if (resolveContentCountryCode(contentCountry) == "IN") {
            expected += INDIAN_SCRIPTS
            expected += LyricsScript.LATIN
        }

        if (expected.isEmpty()) {
            expected += LyricsScript.LATIN
        } else {
            expected += LyricsScript.LATIN
        }

        return expected
    }

    private fun resolveContentCountryCode(raw: String?): String? {
        if (raw.isNullOrBlank() || raw.equals("system", ignoreCase = true) || raw == SYSTEM_DEFAULT) {
            return Locale.getDefault().country.uppercase(Locale.ROOT).takeIf { it.isNotBlank() }
        }
        return raw.uppercase(Locale.ROOT)
    }

    private fun resolveContentLanguageCode(raw: String?): String? {
        if (raw.isNullOrBlank() || raw.equals("system", ignoreCase = true) || raw == SYSTEM_DEFAULT) {
            return Locale.getDefault().language.lowercase(Locale.ROOT).takeIf { it.isNotBlank() }
        }
        return raw.substringBefore('-').lowercase(Locale.ROOT)
    }

    private fun scriptsInText(text: String): Set<LyricsScript> =
        scriptDistribution(text).keys.toSet()

    private fun scriptDistribution(text: String): Map<LyricsScript, Int> {
        val counts = linkedMapOf<LyricsScript, Int>()
        text.forEach { char ->
            if (!char.isLetter()) return@forEach
            val script = classifyChar(char)
            counts[script] = counts.getOrDefault(script, 0) + 1
        }
        return counts
    }

    private fun dominantScripts(distribution: Map<LyricsScript, Int>): Set<LyricsScript> {
        val total = distribution.values.sum()
        if (total == 0) return emptySet()

        val threshold = maxOf(3, (total * 0.12).toInt())
        return distribution
            .filter { (script, count) ->
                script != LyricsScript.OTHER && count >= threshold
            }.keys
    }

    private fun classifyChar(char: Char): LyricsScript =
        when (UnicodeScript.of(char.code)) {
            UnicodeScript.LATIN -> LyricsScript.LATIN
            UnicodeScript.DEVANAGARI -> LyricsScript.DEVANAGARI
            UnicodeScript.MALAYALAM -> LyricsScript.MALAYALAM
            UnicodeScript.TAMIL -> LyricsScript.TAMIL
            UnicodeScript.TELUGU -> LyricsScript.TELUGU
            UnicodeScript.KANNADA -> LyricsScript.KANNADA
            UnicodeScript.BENGALI -> LyricsScript.BENGALI
            UnicodeScript.GUJARATI -> LyricsScript.GUJARATI
            UnicodeScript.GURMUKHI -> LyricsScript.GURMUKHI
            UnicodeScript.ORIYA -> LyricsScript.ORIYA
            UnicodeScript.ARABIC -> LyricsScript.ARABIC
            UnicodeScript.HEBREW -> LyricsScript.HEBREW
            UnicodeScript.THAI -> LyricsScript.THAI
            UnicodeScript.HIRAGANA -> LyricsScript.HIRAGANA
            UnicodeScript.KATAKANA -> LyricsScript.KATAKANA
            UnicodeScript.HANGUL -> LyricsScript.HANGUL
            UnicodeScript.HAN -> LyricsScript.HAN
            UnicodeScript.CYRILLIC -> LyricsScript.CYRILLIC
            UnicodeScript.COMMON, UnicodeScript.INHERITED -> LyricsScript.OTHER
            else -> LyricsScript.OTHER
        }

    private fun looksLikeIndonesianLatin(text: String): Boolean {
        val words =
            text
                .lowercase(Locale.ROOT)
                .split(Regex("\\W+"))
                .filter { it.length >= 2 }
        if (words.size < 6) return false

        val markerCount = words.count { it in INDONESIAN_MARKERS }
        return markerCount >= maxOf(3, words.size / 8)
    }
}
