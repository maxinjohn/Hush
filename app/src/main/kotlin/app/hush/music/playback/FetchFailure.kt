/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

/**
 * A provider sweep that ended with nothing, and the sources it actually asked.
 *
 * The player used to report this with the full error panel over the artwork, which is the surface
 * for a *broken* stream - a decoder failure, a refused session, an HTTP error. "No enabled source
 * has this track" is not that: the audio the user is looking at is fine, the walk simply ended. It
 * belongs on the codec line, the one place the player already says which source is being tried and
 * how far a transfer has got, so the answer arrives where the question was asked - and the artwork
 * stays visible while it is read.
 *
 * [sources] are display names, not registry ids, because the line is read by a listener: the sweep
 * asks `bitchord-streaming-modules:bitchord-tidal-flac`, the row says `Tidal FLAC`.
 */
data class FetchFailure(
    /** The track this verdict is about, so a stale verdict can never be drawn over another song. */
    val mediaId: String,
    /** What the walk asked, in the user's own Audio Sources order. */
    val sources: List<String> = emptyList(),
    /**
     * True when the walk never asked: every source in [sources] refused service before it named a
     * track.
     *
     * The line has to say which of the two happened. "No source has this track" is a statement about
     * catalogues, and a source that answered `403 (Unavailable)` made no such statement - it said it
     * was not serving anyone, which is a fact about the service and the only one the listener can
     * act on. Measured on the reporting device: two Tido profiles refusing every request were drawn
     * as "No source has this track · Tido · Lossless, Tido", so the app looked as though it had
     * searched Tidal and found nothing.
     */
    val refused: Boolean = false,
) {
    companion object {
        /** How many source names fit on the one line the codec row has. */
        const val NAME_LIMIT = 3

        /**
         * The names to print, and the only ordering that is meaningful: the order they were asked.
         *
         * Blank names are dropped, and so are duplicates: two registry entries can carry the same
         * display name, and a sentence that says `Deezer, Deezer` is worse than one that says
         * `Deezer`. The list is capped so the line stays a line - the remainder is counted rather
         * than named, because a codec row is a marquee, not a report.
         */
        fun sourceNames(
            sources: List<String>,
            limit: Int = NAME_LIMIT,
        ): List<String> =
            distinct(sources).take(limit.coerceAtLeast(1))

        /** How many asked sources [sourceNames] left off, for a `+N more` suffix. */
        fun remainingCount(
            sources: List<String>,
            limit: Int = NAME_LIMIT,
        ): Int = (distinct(sources).size - limit.coerceAtLeast(1)).coerceAtLeast(0)

        /**
         * The source ids a finished sweep actually asked, read out of its aggregate failure.
         *
         * The sweep's own message is the only record of the walk that ran: `All SpotiFLAC sources
         * failed (deezer@LOSSLESS=…; qobuz-web@LOSSLESS=…; …)`, in the order the user's list put them.
         * Without it the row named the *enabled* list - measured on device as four names for a
         * six-source walk, because the two modules in the walk were not in that list - so the sentence
         * undercounted the work the app had just done.
         *
         * A detail that is not that aggregate (a timeout, a blocked gateway, a cancellation) yields
         * nothing, and the caller falls back to the enabled list.
         */
        fun askedSourceIds(detail: String?): List<String> {
            val text = detail?.trim().orEmpty()
            val marker = "All SpotiFLAC sources failed"
            val start = text.indexOf(marker).takeIf { it >= 0 } ?: return emptyList()
            val open = text.indexOf('(', start)
            val close = text.lastIndexOf(')')
            if (open < 0 || close <= open) return emptyList()
            return text
                .substring(open + 1, close)
                .split(';')
                .mapNotNull { entry ->
                    val trimmed = entry.trim()
                    if (trimmed.isEmpty()) return@mapNotNull null
                    val id = trimmed.substringBefore('@').substringBefore('=').trim()
                    id.takeIf { it.isNotEmpty() }
                }
        }

        private fun distinct(sources: List<String>): List<String> =
            sources
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
    }
}
