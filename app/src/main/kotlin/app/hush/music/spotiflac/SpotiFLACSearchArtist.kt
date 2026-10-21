/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import java.util.Locale

/**
 * The artist line a provider is asked with.
 *
 * A provider search is a *string* query, so whatever is put in it is what the service tries to match.
 * The artist a queue item carries is not a string, though: Media3 exposes a composite `artist` field
 * that is assembled from the item's structured artists, and for a track with several - and
 * particularly with empty ones - the assembly leaves the separators behind. Measured on the reporting
 * device for "Ente Mavum Poothe":
 *
 * ```
 * resolve start title="Ente Mavum Poothe" artist=" & , , , Rzee, Shaan Rahman, Arun Alat, Vineeth Sreenivasan"
 * module source=qobuz-addon… matched nothing (2 row(s) offered, best "Enta…")
 * module source=gglos…        matched nothing (2 row(s) offered, best "Entam…")
 * module source=tidal-flac…   matched nothing (2 row(s) offered, best "Enta…")
 * 
 * all sources failed: qobuz=no audio; gglos=no audio; tidal-flac=no audio
 * ```
 *
 * Three working sources each returned the right row and each refused it, because the query they were
 * given was `Ente Mavum Poothe & , , , Rzee, Shaan Rahman…` and no matcher can score that against
 * `Entam…`. Every source failing at once is what made the track look like a Tidal outage; the
 * catalogue was never the problem.
 *
 * So the order here is the evidence: the item's own structured artists first (they are names, not a
 * format), then the composite field *split back into names* rather than trusted verbatim, then the
 * database row. Blank entries are dropped, and so are entries with nothing readable in them - a
 * leftover `&` or `,` is punctuation where a name belongs, and passing it on is what broke the match.
 *
 * Deliberately pure - no Android, no Media3 type, no clock - so the shape of the query is pinned by
 * tests rather than by whichever track happened to be playing.
 */
object SpotiFLACSearchArtist {

    /**
     * The artist names a provider is asked with, joined as one line, or "" when nothing is known.
     *
     * @param names the item's structured artists, in the order the item lists them.
     * @param composite the item's own `artist` field, which is a rendering rather than a list.
     * @param fallback the stored row's artists, used only when the item says nothing readable.
     */
    fun forSearch(
        names: List<String?>? = null,
        composite: String? = null,
        fallback: List<String?>? = null,
    ): String {
        clean(names).takeIf { it.isNotEmpty() }?.let { return it.joinToString(", ") }
        val fromComposite = composite?.let(::split)?.let(::clean).orEmpty()
        if (fromComposite.isNotEmpty()) return fromComposite.joinToString(", ")
        return clean(fallback).joinToString(", ")
    }

    /**
     * The artists named inside a composite field.
     *
     * Split on the two separators Media3 assembles with, plus the bullet the app's own sub-titles use
     * - the field reaches here from several producers, and a name that arrived with a separator
     * inside it is still a name.
     */
    private fun split(composite: String): List<String> =
        composite.split(',', '&', '•', '/', ';', '|')

    /**
     * The readable names in [values]: trimmed, blanks and bare punctuation dropped.
     *
     * "Readable" is deliberately loose - any letter or digit counts, whatever the script - because
     * the names this guards are not all Latin, and a name this dropped would be a track the user
     * cannot play. What it does drop is a token with no letter or digit at all: the `&`, the `,`, the
     * lone `-` that a composite field leaves behind.
     */
    private fun clean(values: List<String?>?): List<String> =
        values
            .orEmpty()
            .mapNotNull { value -> value?.trim()?.takeIf { it.isNotEmpty() } }
            .filter { name -> name.any { it.isLetterOrDigit() } }
            .distinct()

    /** The same reading for a single stored line, used where only one string is available. */
    fun cleanLine(line: String?): String = forSearch(composite = line)

    /** Lower-cased, for a caller that wants to compare two of these. */
    internal fun normalized(line: String?): String =
        line?.trim()?.lowercase(Locale.US).orEmpty()
}
