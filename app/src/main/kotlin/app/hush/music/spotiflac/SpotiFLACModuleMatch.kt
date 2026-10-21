/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import java.util.Locale

/**
 * Which of a module's own search rows is the song the user asked for.
 *
 * A module searches its service and hands back what that service calls a match. For a module that
 * fronts a catalogue - the Tidal one - that is close to the answer, and for the Archive one it is
 * not: its rows are live tapes, transfers and bootlegs, ranked by the service's own free-text
 * relevance, and the right taper lands second to fourth behind a podcast that happens to share a
 * word (which the module's own author measured, and why it does not filter at all). So the last step
 * of the fallback has to be Hush's judgement rather than the service's, or a module would play a
 * podcast because its title overlapped.
 *
 * Kept pure and separate from the host so the decision can be pinned by tests: a wrong match plays
 * the wrong song, which is worse than no match, and it is the one part of this path that can be
 * checked exhaustively without a network.
 *
 * ## How it scores
 *
 * Three signals - title, artist, running time - each scored 0..1 and then weighted. A signal whose
 * *either* side is unknown is dropped and the remaining weights are renormalised, because "not
 * reported" is not evidence of a mismatch: a module that reports no artist must not be penalised for
 * it, and the same applies to a queue item restored without a duration.
 *
 * Running time is the weakest signal on purpose and is weighted accordingly: these modules exist for
 * live recordings, where a performance of the right song is legitimately longer than the studio cut
 * the user is holding - so a difference is a tie-break between two otherwise equal rows, not a
 * reason to reject the only row there is.
 */
object SpotiFLACModuleMatch {
    /** What the caller is looking for. */
    data class Wanted(
        val title: String,
        val artist: String,
        val durationMs: Long,
    )

    /** One row a module returned. */
    data class Row(
        val id: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
    )

    /**
     * Lowest score a row may have and still be played.
     *
     * Above a shared word and below a genuine partial title, which is where the two ways of being
     * wrong sit: a threshold that lets a loose row through plays the wrong song, and one that needs
     * an exact title plays nothing from a module whose titles carry the taper's own suffixes.
     */
    const val MIN_SCORE = 0.60

    private const val TITLE_WEIGHT = 0.60
    private const val ARTIST_WEIGHT = 0.25
    private const val DURATION_WEIGHT = 0.15

    /** A difference this small is the same recording, whatever the metadata says. */
    private const val DURATION_EXACT_MS = 15_000L

    /** Beyond this, the two are different recordings and the difference stops mattering. */
    private const val DURATION_IRRELEVANT_MS = 120_000L

    /** The row worth playing, or null when none of them is. */
    fun best(
        wanted: Wanted,
        rows: List<Row>,
    ): Row? {
        val scored = rows.map { it to score(wanted, it) }.filter { it.second >= MIN_SCORE }
        // Ties are broken by the module's own order, which is its relevance ranking: this only
        // chooses between rows the score already calls equivalent.
        return scored.maxByOrNull { it.second }?.first
    }

    /** How well [row] answers [wanted], 0..1. */
    fun score(
        wanted: Wanted,
        row: Row,
    ): Double {
        var total = 0.0
        var weight = 0.0

        fun add(
            value: Double?,
            componentWeight: Double,
        ) {
            if (value == null) return
            total += value * componentWeight
            weight += componentWeight
        }

        add(titleScore(wanted.title, row.title), TITLE_WEIGHT)
        add(artistScore(wanted.artist, row.artist), ARTIST_WEIGHT)
        add(durationScore(wanted.durationMs, row.durationMs), DURATION_WEIGHT)

        return if (weight <= 0.0) 0.0 else total / weight
    }

    private fun titleScore(
        wanted: String,
        found: String,
    ): Double? {
        val a = normalize(wanted)
        val b = normalize(found)
        if (a.isEmpty() || b.isEmpty()) return null
        if (a == b) return 1.0
        // A taper's title carries the venue, the date or a "[soundboard]" marker after the song's
        // name, and the song's name is the whole of it that has to match.
        if (b.startsWith(a) || a.startsWith(b)) return 0.9
        if (b.contains(a) || a.contains(b)) return 0.8
        return 0.9 * overlap(a, b)
    }

    private fun artistScore(
        wanted: String,
        found: String,
    ): Double? {
        val a = normalize(wanted)
        val b = normalize(found)
        if (a.isEmpty() || b.isEmpty()) return null
        if (a == b) return 1.0
        if (b.contains(a) || a.contains(b)) return 0.8
        return 0.8 * overlap(a, b)
    }

    /**
     * How close two running times are, 0..1, or null when either is unknown.
     *
     * Flat inside [DURATION_EXACT_MS], then linear decay: the difference between two performances of
     * one song says nothing until it is minutes, and everything after that is a different recording
     * rather than a worse match.
     */
    private fun durationScore(
        wantedMs: Long,
        foundMs: Long,
    ): Double? {
        if (wantedMs <= 0L || foundMs <= 0L) return null
        val difference = kotlin.math.abs(wantedMs - foundMs)
        if (difference <= DURATION_EXACT_MS) return 1.0
        if (difference >= DURATION_IRRELEVANT_MS) return 0.0
        return 1.0 -
            (difference - DURATION_EXACT_MS).toDouble() /
            (DURATION_IRRELEVANT_MS - DURATION_EXACT_MS).toDouble()
    }

    /** Shared words as a fraction of the shorter side's words. */
    private fun overlap(
        a: String,
        b: String,
    ): Double {
        val left = a.split(' ').filter { it.isNotBlank() }.toSet()
        val right = b.split(' ').filter { it.isNotBlank() }.toSet()
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val shared = left.count { it in right }
        return shared.toDouble() / minOf(left.size, right.size).toDouble()
    }

    /**
     * A comparison form: lowercase, unaccented, without bracketed asides, punctuation or a leading
     * track number.
     *
     * Every removal here was a measured mismatch rather than tidiness. `01 Believe Me` is what the
     * Archive's file listing calls the first track of a tape, so the number is not part of the title;
     * `Africa (Remastered 2011)` and `Africa` are one song; and an accented title that arrives in two
     * Unicode normalisations (`Beyoncé` as NFC and as NFD) is not two songs.
     */
    internal fun normalize(value: String): String {
        val lowered = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD)
        val stripped = StringBuilder(lowered.length)
        var depth = 0
        for (character in lowered) {
            when {
                character == '(' || character == '[' || character == '{' -> depth++
                character == ')' || character == ']' || character == '}' -> if (depth > 0) depth--
                // Also drops combining marks, which NFKD has just separated out.
                depth > 0 -> Unit
                character.isLetterOrDigit() -> stripped.append(character)
                else -> stripped.append(' ')
            }
        }
        val words =
            stripped
                .toString()
                .lowercase(Locale.US)
                .split(' ')
                .filter { it.isNotBlank() }
        // A leading track number: digits alone, which no word of a title is.
        val start = if (words.size > 1 && words.first().all { it.isDigit() }) 1 else 0
        return words.drop(start).joinToString(" ")
    }
}
