/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the wording of the provider-outage line, because it has one job: stop a healthy session and a
 * failing source from reading as a contradiction.
 */
class SpotiFLACProviderNoteTest {

    @Test
    fun `the headline names the provider before what failed`() {
        assertEquals(
            "Provider's own service - Zarz API is offline",
            SpotiFLACProviderNote.headline("Zarz API is offline"),
        )
    }

    @Test
    fun `a detail from the check is carried through verbatim`() {
        assertEquals(
            "Provider's own service - Zarz API: fetch failed",
            SpotiFLACProviderNote.headline("Zarz API: fetch failed"),
        )
    }

    @Test
    fun `the scope is stated first, so an ellipsized row still answers the question`() {
        // The row truncates from the right on a narrow screen, so the words that resolve "why is my
        // session fine and the test failing" have to be the leading ones.
        val line = SpotiFLACProviderNote.row("Zarz API is offline", 8 * 60_000L)
        assertTrue(line, line.startsWith("Provider's own service - "))
        assertEquals("Provider's own service - Zarz API is offline · tried last for 8m", line)
    }

    @Test
    fun `the reason is trimmed so a padded health string cannot shift the scope`() {
        assertEquals(
            "Provider's own service - Zarz API is offline",
            SpotiFLACProviderNote.headline("  Zarz API is offline  "),
        )
    }

    @Test
    fun `the demotion length reads in the same units the block countdown uses`() {
        assertEquals(
            "Provider's own service - offline · tried last for 10m",
            SpotiFLACProviderNote.row("offline", 10 * 60_000L),
        )
        assertEquals(
            "Provider's own service - offline · tried last for 1h 5m",
            SpotiFLACProviderNote.row("offline", 65 * 60_000L),
        )
    }

    /**
     * An addon is *skipped*, not moved to the back, so its row may not promise a turn it will not get.
     *
     * The two sentences describe two different decisions: a runtime provider that reported itself down
     * stays in the walk behind the others, while an addon that refused before naming any track is left
     * out of the walk entirely.
     */
    @Test
    fun `a skipped addon's row leads with the decision and the wait`() {
        // The row ellipsizes from the right, so "why is this row not being tried, and for how long"
        // has to be the part that stays on screen. Measured on the phone: the same sentence with the
        // wait at the end read `Provider's own service - Tido · …`.
        val line =
            SpotiFLACProviderNote.row(
                "Tido · Lossless answered HTTP 403 (Unavailable)",
                9 * 60_000L,
                skipped = true,
            )
        assertTrue(line, line.startsWith("Skipped for 9m - "))
        assertEquals("Skipped for 9m - Tido · Lossless answered HTTP 403 (Unavailable)", line)
    }

    @Test
    fun `an elapsed-looking remainder still reads as a wait rather than a negative`() {
        assertEquals(
            "Provider's own service - offline · tried last for 0m",
            SpotiFLACProviderNote.row("offline", -5_000L),
        )
    }
}
