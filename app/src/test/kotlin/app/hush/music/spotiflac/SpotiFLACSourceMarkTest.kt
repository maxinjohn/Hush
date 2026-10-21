/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import app.hush.music.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards which mark each source is drawn with in Audio Sources.
 *
 * The failure this exists to catch is a source drawn with the *wrong* brand's mark, which is worse
 * than drawing none: `tidal-web` shown with Deezer's bars is a statement that the app has the two
 * confused, and the mistake is a prefix that was matched too loosely - a key like `t` or `ti` would
 * make TIDAL the answer for half the list. Every id the registries actually publish is asserted here
 * by name, and the ids are spelled as the runtime spells them, because that is what the row passes in.
 *
 * The second failure is the one that made this table worth finishing: a list where six sources carry
 * a brand mark and six carry the same grey note reads as six sources that have lost their icon, not
 * as six services Hush has no art for. So a source with no mark now has to be a source *neither*
 * registry publishes - the assertion below names two of those, so that a newly published source
 * still shows up here rather than silently picking up the generic note.
 */
class SpotiFLACSourceMarkTest {
    @Test
    fun `each source the registries publish is drawn with its own mark`() {
        // The ids, verbatim, from both registries' `registry.json`.
        assertEquals(R.drawable.ic_source_spotify, SpotiFLACSourceMark.iconRes("spotify-web"))
        assertEquals(R.drawable.ic_source_deezer, SpotiFLACSourceMark.iconRes("deezer"))
        assertEquals(R.drawable.ic_source_tidal, SpotiFLACSourceMark.iconRes("tidal-web"))
        assertEquals(R.drawable.ic_source_apple_music, SpotiFLACSourceMark.iconRes("apple-music"))
        assertEquals(R.drawable.ic_source_soundcloud, SpotiFLACSourceMark.iconRes("soundcloud"))
        assertEquals(R.drawable.ic_source_youtube_music, SpotiFLACSourceMark.iconRes("ytmusic-spotiflac"))
        assertEquals(R.drawable.ic_source_amazon_music, SpotiFLACSourceMark.iconRes("amazon"))
        assertEquals(R.drawable.ic_source_qobuz, SpotiFLACSourceMark.iconRes("qobuz-web"))
        assertEquals(R.drawable.ic_source_jiosaavn, SpotiFLACSourceMark.iconRes("jiosaavn"))
        assertEquals(R.drawable.ic_source_bugs, SpotiFLACSourceMark.iconRes("bugs-music"))
        assertEquals(R.drawable.ic_source_melon, SpotiFLACSourceMark.iconRes("melon-music"))
        assertEquals(R.drawable.ic_source_genie, SpotiFLACSourceMark.iconRes("genie-music"))
    }

    /**
     * Every source the app knows about on its own is drawn too - the ones no registry has to publish.
     *
     * This is the half the twelve-id assertion above missed, and it is the half that failed: Pandora
     * is in `ExtensionRepositoryManager.BUILTIN_SOURCES`, so it appears in Audio Sources whether or
     * not either registry answers, and the table had been written against the registries alone. It sat
     * in the list as the generic note between twelve brand marks with nothing to say so.
     */
    @Test
    fun `every built-in source has a mark of its own`() {
        // The ids of ExtensionRepositoryManager's built-in list, verbatim.
        val builtIns =
            listOf(
                "tidal-web", "deezer", "qobuz-web", "amazon",
                "soundcloud", "apple-music", "spotify-web", "pandora",
            )
        for (id in builtIns) {
            assertNotEquals(id, SpotiFLACSourceMark.fallbackIconRes, SpotiFLACSourceMark.iconRes(id))
        }
    }

    /**
     * No two sources share a mark, which is the whole point of the table.
     *
     * `youtube` and `ytmusic` are the deliberate exception: they are the same service under the two
     * ids the runtime logs it as, so they are asserted equal instead of distinct.
     */
    @Test
    fun `every mark in the table is drawn by one source only`() {
        val ids =
            listOf(
                "spotify-web", "deezer", "tidal-web", "apple-music", "soundcloud",
                "ytmusic-spotiflac", "amazon", "qobuz-web", "jiosaavn", "bugs-music",
                "melon-music", "genie-music", "pandora",
            )
        val drawn = ids.associateWith { SpotiFLACSourceMark.iconRes(it) }
        assertEquals(
            drawn.keys.size,
            drawn.values.toSet().size,
        )
        assertEquals(
            SpotiFLACSourceMark.iconRes("youtube-music"),
            SpotiFLACSourceMark.iconRes("ytmusic-spotiflac"),
        )
    }

    /**
     * The runtime's short id reaches the same mark as the registry's long one.
     *
     * `tidal-web` and `tidal` are one service, and the runtime's own log names the short form
     * (`source=amazon`) while the registry lists the long one. Matching the whole id exactly would
     * leave the row that appears during playback without a mark.
     */
    @Test
    fun `the runtime's short id and the registry's long one are the same source`() {
        assertEquals(SpotiFLACSourceMark.iconRes("tidal-web"), SpotiFLACSourceMark.iconRes("tidal"))
        assertEquals(
            SpotiFLACSourceMark.iconRes("ytmusic-spotiflac"),
            SpotiFLACSourceMark.iconRes("youtube-music"),
        )
        assertEquals(SpotiFLACSourceMark.iconRes("qobuz-web"), SpotiFLACSourceMark.iconRes("qobuz"))
    }

    /**
     * A source neither registry publishes still gets the generic mark, in the theme's colour.
     *
     * The honest drawing for a service Hush has no art for is the generic note - not another brand's
     * mark, and not an unpainted icon, which is what would make the row look like it had lost one.
     */
    @Test
    fun `a source with no mark of its own falls back without claiming another brand`() {
        for (id in listOf("anghami", "kkbox", "boomplay", "some-future-source")) {
            assertEquals(id, SpotiFLACSourceMark.fallbackIconRes, SpotiFLACSourceMark.iconRes(id))
            assertNull(id, SpotiFLACSourceMark.brandArgb(id))
        }
    }

    /**
     * Every published source's colour is the one its own art uses, and the two black marks are the
     * theme's.
     *
     * TIDAL's mark is black and so is Qobuz's. Painted black on Hush's dark surface each is an empty
     * circle, which reads as a broken row rather than as a brand - so they are the two sources whose
     * colour the theme supplies, exactly as TIDAL's own app draws it after dark.
     */
    @Test
    fun `the mark keeps the brand colour except where that colour would disappear`() {
        assertEquals(0xFF1ED760.toInt(), SpotiFLACSourceMark.brandArgb("spotify-web"))
        assertEquals(0xFFFA243C.toInt(), SpotiFLACSourceMark.brandArgb("apple-music"))
        assertEquals(0xFFFF9900.toInt(), SpotiFLACSourceMark.brandArgb("amazon"))
        assertEquals(0xFF1ECCB0.toInt(), SpotiFLACSourceMark.brandArgb("jiosaavn"))
        assertEquals(0xFFFB3B50.toInt(), SpotiFLACSourceMark.brandArgb("bugs-music"))
        assertEquals(0xFF05DF2F.toInt(), SpotiFLACSourceMark.brandArgb("melon-music"))
        assertEquals(0xFF0096FE.toInt(), SpotiFLACSourceMark.brandArgb("genie-music"))
        assertEquals(0xFF3668FF.toInt(), SpotiFLACSourceMark.brandArgb("pandora"))
        assertNull(SpotiFLACSourceMark.brandArgb("tidal-web"))
        assertNull(SpotiFLACSourceMark.brandArgb("qobuz-web"))
    }

    /**
     * The id decides, not the prose.
     *
     * A registry is free to rename a source's `display_name`, and ids arrive from the runtime in
     * whatever case it logged them; the mark must follow the id through both.
     */
    @Test
    fun `the id is matched whatever case the runtime writes it in`() {
        assertEquals(SpotiFLACSourceMark.iconRes("deezer"), SpotiFLACSourceMark.iconRes("  Deezer "))
        assertEquals(R.drawable.ic_source_deezer, SpotiFLACSourceMark.iconRes("DEEZER"))
        assertEquals(R.drawable.ic_source_jiosaavn, SpotiFLACSourceMark.iconRes("JioSaavn"))
    }

    /**
     * A module's id is namespaced by the repository that published it, so its vendor is not a prefix.
     *
     * This is the third way an id gets written - a plain registry id, the runtime's short form, and a
     * module's `index:module` form - and it is the one a prefix match cannot see: the BitChord index
     * publishes `bitchord-tidal-flac` under the namespace `bitchord-streaming-modules:`, and reading
     * only the front of that id gave the Tidal module the generic note in a list where the
     * registry-sourced Tidal row beside it quoted TIDAL's mark.
     */
    @Test
    fun `a repository-namespaced module id still finds the vendor's mark`() {
        assertEquals(
            R.drawable.ic_source_tidal,
            SpotiFLACSourceMark.iconRes("bitchord-streaming-modules:bitchord-tidal-flac"),
        )
        assertEquals(
            0xFF1ED760.toInt(),
            SpotiFLACSourceMark.brandArgb("some-index:spotify-web-module"),
        )
        // And a module for a service Hush has no art for stays generic rather than borrowing a
        // neighbour's brand: archive.org is not TIDAL, even inside the same index.
        assertEquals(
            SpotiFLACSourceMark.fallbackIconRes,
            SpotiFLACSourceMark.iconRes("bitchord-streaming-modules:archive-flac"),
        )
    }
}
