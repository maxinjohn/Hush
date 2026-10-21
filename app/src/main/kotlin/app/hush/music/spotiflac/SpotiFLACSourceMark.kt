/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import androidx.annotation.DrawableRes
import app.hush.music.R

/**
 * The mark Hush draws beside a source in Audio Sources.
 *
 * A source list of plain names is a list of words that all read the same: `amazon`, `deezer`,
 * `tidal-web` and the rest differ by a few letters, and the one the user is looking for - the source
 * that is failing, the one they arranged first - has to be found by reading. Every source the two
 * registries publish has a mark here, so no row falls back to a generic note while its neighbours
 * are quoting a brand: that half-drawn list is what made a source look like it had lost its icon.
 *
 * Where the artwork comes from matters, because this is the one part of the app that may not be
 * Hush's own: Spotify, Deezer, TIDAL, Apple Music, SoundCloud, YouTube Music and Pandora are drawn
 * from the paths Simple Icons publishes (CC0), and Amazon Music, Qobuz, JioSaavn, Bugs, Melon and
 * Genie Music are Hush's single-colour tracings of each service's own icon - the one they publish
 * for their app or their site - because no icon set Hush may ship has a path for them. Each file's
 * own header says which of the two it is. Drawn, not fetched: the screen has to look the same on a
 * car head unit with no network as it does on a phone, and an icon that arrives late is an icon that
 * moves the row under the user's finger.
 *
 * The id is what is matched, never the display name - the name is registry prose and a provider is
 * free to rename itself, while `spotify-web` is what the runtime resolves with. Matching is by
 * prefix because ids carry their variant: `tidal-web` and `tidal` are the same service, and the
 * runtime's own log names the short form (`source=amazon`) for the same source the registry lists as
 * `amazon`.
 *
 * Which colour is deliberate. The mark is drawn in the brand's own colour where that colour is
 * legible on both of Hush's surfaces, and in the theme's foreground colour where it is not: TIDAL's
 * mark is black and Qobuz's is black on white, and black on Hush's dark surface is an empty circle -
 * the failure would look like a missing icon rather than a coloured one. That is also what TIDAL's
 * own app does with it.
 */
object SpotiFLACSourceMark {
    /** The generic mark, for a source that has no shape of its own here. */
    @DrawableRes
    val fallbackIconRes: Int = R.drawable.music_note

    /** The mark for [sourceId], or [fallbackIconRes] when it has none. */
    @DrawableRes
    fun iconRes(sourceId: String): Int = markFor(sourceId)?.iconRes ?: fallbackIconRes

    /**
     * The colour to draw [sourceId]'s mark in, or null for the theme's own foreground colour.
     *
     * Packed ARGB rather than a Compose `Color` so that the table is a plain decision about sources
     * and can be checked without a screen.
     */
    fun brandArgb(sourceId: String): Int? = markFor(sourceId)?.argb

    /**
     * The mark for an id, by the vendor token inside it.
     *
     * A registry id is the vendor's own (`tidal-web`, `spotify-web`), so a prefix is the match. A
     * module id is namespaced by the repository that published it
     * (`bitchord-streaming-modules:bitchord-tidal-flac`), because module ids are chosen by their
     * authors and are not unique across indexes - and a prefix match on that reads as a source with
     * no icon at all, which is precisely the half-drawn list this table exists to prevent. So the id
     * is also split on everything that is not a letter or a digit and each of *its* tokens is asked,
     * which is what gives a BitChord-style module the mark of the service it streams from.
     *
     * A module for a service Hush has no mark for - an archive.org module, say - still falls back to
     * the generic note rather than borrowing a neighbour's brand.
     */
    private fun markFor(sourceId: String): Mark? {
        val id = sourceId.trim().lowercase()
        MARKS.entries.firstOrNull { (prefix, _) -> id.startsWith(prefix) }?.let { return it.value }
        val tokens = id.split(NonIdToken).filter { it.isNotBlank() }
        return MARKS.entries
            .firstOrNull { (prefix, _) ->
                tokens.any { token -> token == prefix || token.startsWith(prefix) }
            }
            ?.value
    }

    /** Whatever separates the parts of a namespaced id: `:`, `-`, `_`, `.`, `/`. */
    private val NonIdToken = Regex("[^a-z0-9]+")

    private data class Mark(
        @DrawableRes val iconRes: Int,
        val argb: Int?,
    )

    /**
     * Longest-prefix-wins is not needed: no key here is the start of another (`apple` and `amazon`
     * diverge at the third letter, `tidal` and `tidal-web` are the same service).
     *
     * Every id the two registries publish is here, plus the built-ins' own ids, and each entry's
     * colour is the one the service's own art uses - read off that art where the mark was traced
     * from it. Amazon Music is the one entry whose colour is not taken from the file the mark was
     * traced from: its icon paints a black smile on cyan, and the smile's own orange is what Amazon
     * Music is recognised by.
     *
     * The map and the sources the app knows about have to be checked against each other, not assumed
     * equal: Pandora sat in the built-in list with no entry here for as long as the map was written
     * against the two registries alone, and read as the generic note in a list where its twelve
     * neighbours all quoted a brand.
     */
    private val MARKS: Map<String, Mark> =
        linkedMapOf(
            // Spotify's green, the one colour the whole family shares.
            "spotify" to Mark(R.drawable.ic_source_spotify, 0xFF1ED760.toInt()),
            "deezer" to Mark(R.drawable.ic_source_deezer, 0xFFA238FF.toInt()),
            // Black on black: left to the theme, which is how TIDAL itself draws it after dark.
            "tidal" to Mark(R.drawable.ic_source_tidal, null),
            "apple" to Mark(R.drawable.ic_source_apple_music, 0xFFFA243C.toInt()),
            "soundcloud" to Mark(R.drawable.ic_source_soundcloud, 0xFFFF5500.toInt()),
            "ytmusic" to Mark(R.drawable.ic_source_youtube_music, 0xFFFF0000.toInt()),
            "youtube" to Mark(R.drawable.ic_source_youtube_music, 0xFFFF0000.toInt()),
            // Amazon's smile orange, which is the colour of the mark itself.
            "amazon" to Mark(R.drawable.ic_source_amazon_music, 0xFFFF9900.toInt()),
            // The second black mark, left to the theme for TIDAL's reason.
            "qobuz" to Mark(R.drawable.ic_source_qobuz, null),
            "jiosaavn" to Mark(R.drawable.ic_source_jiosaavn, 0xFF1ECCB0.toInt()),
            "bugs" to Mark(R.drawable.ic_source_bugs, 0xFFFB3B50.toInt()),
            "melon" to Mark(R.drawable.ic_source_melon, 0xFF05DF2F.toInt()),
            "genie" to Mark(R.drawable.ic_source_genie, 0xFF0096FE.toInt()),
            // Built-in, and the last row that had no mark of its own.
            "pandora" to Mark(R.drawable.ic_source_pandora, 0xFF3668FF.toInt()),
        )
}
