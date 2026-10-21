/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the artist line a provider is asked with.
 *
 * One measured case is the whole reason this exists, so it is spelled out exactly as the device
 * logged it: the composite field of a Malayalam film track arrived as
 * `" & , , , Rzee, Shaan Rahman, Arun Alat, Vineeth Sreenivasan"`, every provider was asked with it,
 * and three of them that had the row refused it.
 */
class SpotiFLACSearchArtistTest {

    @Test
    fun `the field that broke a track loses its empty entries and stray separators`() {
        assertEquals(
            "Rzee, Shaan Rahman, Arun Alat, Vineeth Sreenivasan",
            SpotiFLACSearchArtist.forSearch(
                composite = " & , , , Rzee, Shaan Rahman, Arun Alat, Vineeth Sreenivasan",
            ),
        )
        // The same string as three empty structured names plus the real ones: the item's own artists
        // are asked first, and they are names rather than a rendering.
        assertEquals(
            "Rzee, Shaan Rahman, Arun Alat, Vineeth Sreenivasan",
            SpotiFLACSearchArtist.forSearch(
                names = listOf("", "  ", "", "Rzee", "Shaan Rahman", "Arun Alat", "Vineeth Sreenivasan"),
                composite = " & , , , Rzee, Shaan Rahman, Arun Alat, Vineeth Sreenivasan",
            ),
        )
    }

    @Test
    fun `an ordinary track is unaffected`() {
        assertEquals(
            "Sabrina Carpenter",
            SpotiFLACSearchArtist.forSearch(names = listOf("Sabrina Carpenter"), composite = "Sabrina Carpenter"),
        )
        assertEquals(
            "Toto",
            SpotiFLACSearchArtist.forSearch(composite = "Toto"),
        )
    }

    @Test
    fun `several artists are joined as a comma-separated line`() {
        assertEquals(
            "Rzee, Shaan Rahman",
            SpotiFLACSearchArtist.forSearch(names = listOf("Rzee", "Shaan Rahman")),
        )
        // A composite field is split back into names, whichever separator it arrived with.
        assertEquals(
            "Toto, Cheryl Lynn",
            SpotiFLACSearchArtist.forSearch(composite = "Toto & Cheryl Lynn"),
        )
        assertEquals(
            "Toto, Cheryl Lynn",
            SpotiFLACSearchArtist.forSearch(composite = "Toto • Cheryl Lynn"),
        )
    }

    @Test
    fun `names written in another script survive`() {
        // Non-Latin names are the ones this guard exists for: dropping a name nobody could read in
        // ASCII would be a track the user cannot play, so any letter or digit counts as readable.
        assertEquals(
            "മോനോരമ, രഹമാൻ",
            SpotiFLACSearchArtist.forSearch(names = listOf("മോനോരമ", "രഹമാൻ")),
        )
        assertEquals(
            "宇多田ヒカル",
            SpotiFLACSearchArtist.forSearch(composite = "宇多田ヒカル"),
        )
    }

    @Test
    fun `a name is not repeated`() {
        assertEquals(
            "Rzee, Shaan Rahman",
            SpotiFLACSearchArtist.forSearch(composite = "Rzee, Rzee, Shaan Rahman, & ,"),
        )
    }

    @Test
    fun `nothing readable falls through instead of asking a provider with punctuation`() {
        // The composite field says nothing usable, so the stored row answers - and when neither
        // says anything, the line is empty rather than "& ,".
        assertEquals(
            "Rzee",
            SpotiFLACSearchArtist.forSearch(composite = " & , , , ", fallback = listOf("Rzee")),
        )
        assertEquals("", SpotiFLACSearchArtist.forSearch(composite = " & , , , ", fallback = listOf("", " ")))
        assertEquals("", SpotiFLACSearchArtist.forSearch())
        assertEquals("", SpotiFLACSearchArtist.forSearch(composite = "   "))
    }

    @Test
    fun `a stored line is cleaned the same way when it is all there is`() {
        assertEquals("Rzee", SpotiFLACSearchArtist.cleanLine(" & , , , Rzee"))
    }
}
