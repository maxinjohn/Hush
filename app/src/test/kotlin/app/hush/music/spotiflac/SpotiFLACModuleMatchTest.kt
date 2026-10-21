/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins which of a module's own rows is the song that was asked for.
 *
 * The rows are the ones the Archive module actually returns for `Pure Hex`, measured on the device:
 * an unrelated podcast that shares a word and ranks above the band, two live tapes whose titles carry
 * the date and the venue, and a taper whose artist field is the band's name. A matcher that picks the
 * podcast plays the wrong audio, which is worse than picking nothing, so each of these is a case here
 * rather than a judgement made by reading the code.
 */
class SpotiFLACModuleMatchTest {
    private fun row(
        id: String,
        title: String,
        artist: String,
        durationMs: Long,
    ) = SpotiFLACModuleMatch.Row(id = id, title = title, artist = artist, durationMs = durationMs)

    private val wanted =
        SpotiFLACModuleMatch.Wanted(
            title = "Believe Me",
            artist = "TREASVRE",
            durationMs = 277_000L,
        )

    @Test
    fun `the taper wins over the podcast that shares a word`() {
        val rows =
            listOf(
                // What the service ranks first: a podcast whose title happens to carry the words.
                row("podcast", "Believe Me A Storybrooke Roundtable", "Universe Box", 3_100_000L),
                // The recording itself: the stage's own title, the band as the creator.
                row("tape", "01 Believe Me", "TREASVRE", 277_400L),
                row("other", "02 Too Late", "TREASVRE", 240_000L),
            )

        assertEquals("tape", SpotiFLACModuleMatch.best(wanted, rows)?.id)
    }

    @Test
    fun `a live performance of the right song is still the right song`() {
        // These modules exist for live tapes, so a performance that runs half again as long as the
        // studio cut the queue is holding must not be rejected for it.
        val rows =
            listOf(
                row("live", "Believe Me (Live at Bottom of the Hill)", "TREASVRE", 410_000L),
                row("podcast", "Believe Me A Roundtable", "Universe Box", 3_100_000L),
            )

        assertEquals("live", SpotiFLACModuleMatch.best(wanted, rows)?.id)
    }

    @Test
    fun `a title that carries the date and the venue still matches the song`() {
        val rows = listOf(row("tape", "Believe Me - 2023-03-11 San Francisco", "TREASVRE", 277_000L))

        assertEquals("tape", SpotiFLACModuleMatch.best(wanted, rows)?.id)
    }

    @Test
    fun `an unknown artist does not count against a row`() {
        // "not reported" is not a mismatch: a module that names no artist must not be penalised for
        // one, and the same holds for a queue item restored without a duration.
        val rows = listOf(row("anonymous", "Believe Me", "", 0L))

        assertEquals("anonymous", SpotiFLACModuleMatch.best(wanted, rows)?.id)
    }

    @Test
    fun `nothing above the threshold means nothing is played`() {
        val rows =
            listOf(
                row("other", "Completely Different Song", "Someone Else", 199_000L),
                row("short", "Believe", "Another Band", 30_000L),
            )

        assertNull(SpotiFLACModuleMatch.best(wanted, rows))
    }

    @Test
    fun `an empty list answers nothing rather than throwing`() {
        assertNull(SpotiFLACModuleMatch.best(wanted, emptyList()))
    }

    @Test
    fun `running time separates two rows whose titles are the same`() {
        val rows =
            listOf(
                row("wrong-cut", "Believe Me", "TREASVRE", 199_000L),
                row("right-cut", "Believe Me", "TREASVRE", 277_000L),
            )

        assertEquals("right-cut", SpotiFLACModuleMatch.best(wanted, rows)?.id)
        assertTrue(
            SpotiFLACModuleMatch.score(wanted, rows[1]) > SpotiFLACModuleMatch.score(wanted, rows[0]),
        )
    }

    @Test
    fun `normalisation is what makes two spellings one title`() {
        // Each removal here was a real mismatch: a leading track number the tape's file listing
        // carries, a bracketed remaster suffix, punctuation, and an accent that arrives in two
        // Unicode normalisations.
        assertEquals("believe me", SpotiFLACModuleMatch.normalize("01 Believe Me"))
        assertEquals("believe me", SpotiFLACModuleMatch.normalize("Believe Me (Remastered 2011)"))
        assertEquals("believe me", SpotiFLACModuleMatch.normalize("Believe-Me!"))
        assertEquals("beyonce", SpotiFLACModuleMatch.normalize("Beyonce\u0301"))
        assertEquals("beyonce", SpotiFLACModuleMatch.normalize("Beyonc\u00e9"))
        // A number that is part of the title is kept: only a leading standalone one is dropped, and
        // a title made of one word is never treated as a track number.
        assertEquals("1999", SpotiFLACModuleMatch.normalize("1999"))
        assertEquals("track 2", SpotiFLACModuleMatch.normalize("Track 2"))
    }
}
