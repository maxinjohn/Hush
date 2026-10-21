/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the codec row says after a sweep ends with nothing.
 *
 * The sentence is built from the sources the walk actually asked, so these are the rules that keep
 * it a line: the user's order preserved, blanks and repeats dropped, and the remainder counted
 * rather than named.
 */
class FetchFailureTest {
    @Test
    fun `source names keep the order the walk asked them`() {
        val asked = listOf("Deezer", "Tidal FLAC", "Qobuz")
        assertEquals(asked, FetchFailure.sourceNames(asked))
        assertEquals(0, FetchFailure.remainingCount(asked))
    }

    @Test
    fun `blank and repeated names are dropped`() {
        val asked = listOf("Deezer", "", "  ", "deezer", "Tidal FLAC")
        assertEquals(listOf("Deezer", "Tidal FLAC"), FetchFailure.sourceNames(asked))
        assertEquals(0, FetchFailure.remainingCount(asked))
    }

    /** A seven-source walk cannot fit on a marquee, so the rest is a number. */
    @Test
    fun `names beyond the line are counted`() {
        val asked = listOf("Deezer", "Tidal FLAC", "Qobuz", "Amazon", "SoundCloud", "YtMusic")
        assertEquals(listOf("Deezer", "Tidal FLAC", "Qobuz"), FetchFailure.sourceNames(asked))
        assertEquals(3, FetchFailure.remainingCount(asked))
    }

    /** The limit is a parameter so the row and any other surface agree on the rule, not the number. */
    @Test
    fun `the limit decides both what is named and what is counted`() {
        val asked = listOf("A", "B", "C", "D")
        assertEquals(listOf("A", "B"), FetchFailure.sourceNames(asked, limit = 2))
        assertEquals(2, FetchFailure.remainingCount(asked, limit = 2))
    }

    /** No names at all is still a sentence: the row never renders an empty list. */
    @Test
    fun `an empty walk yields no names`() {
        assertTrue(FetchFailure.sourceNames(emptyList()).isEmpty())
        assertTrue(FetchFailure.sourceNames(listOf("", " ")).isEmpty())
        assertEquals(0, FetchFailure.remainingCount(emptyList()))
    }

    /**
     * The walk is read from the sweep's own aggregate, in the order it ran - the enabled list is not
     * the walk: measured on device, four enabled names were printed for a six-source sweep because the
     * two modules it also asked were not in that list.
     */
    @Test
    fun `the asked sources come out of the aggregate in walk order`() {
        val detail =
            "SpotiFLACException: All SpotiFLAC sources failed (deezer@LOSSLESS=All providers " +
                "failed. Last error: Invalid Deezer track ID; qobuz-web@LOSSLESS=timed out; " +
                "gglos-ggottes-deno-net:gg.gglos.addon=no audio)"
        assertEquals(
            listOf("deezer", "qobuz-web", "gglos-ggottes-deno-net:gg.gglos.addon"),
            FetchFailure.askedSourceIds(detail),
        )
    }

    /** Anything that is not that aggregate says nothing, so the caller falls back to the enabled list. */
    @Test
    fun `a detail that is not an aggregate yields no ids`() {
        assertTrue(FetchFailure.askedSourceIds(null).isEmpty())
        assertTrue(FetchFailure.askedSourceIds("").isEmpty())
        assertTrue(FetchFailure.askedSourceIds("sweep-unfinished").isEmpty())
        assertTrue(FetchFailure.askedSourceIds("SpotiFLACException: All sources failed").isEmpty())
    }

    /** A failure belongs to one track, so the row can refuse one recorded for another. */
    @Test
    fun `a failure carries the track it is about`() {
        val failure = FetchFailure(mediaId = "abc123", sources = listOf("Deezer"))
        assertEquals("abc123", failure.mediaId)
        assertEquals(1, failure.sources.size)
    }
}
