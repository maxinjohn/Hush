/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.screens

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the width at which a mood/genre card stops assuming it is wide.
 *
 * The bug behind this was only visible on a phone: the Search screen's two-up grid gives a card
 * 162dp, the old layout kept 92dp of that clear for the artwork and held the title to one line, so
 * the title had 54dp - four or five characters, which turned every mood into "Chi..." or "Fee...".
 * Both halves of that are arithmetic over the measured width, so both are pinned here rather than
 * by looking at a device.
 */
class MoodAndGenresCardLayoutTest {

    /** The card a 360dp-wide phone gets: 2 columns, 12dp of row padding, 6dp of cell padding. */
    private val phoneCell = 162.dp

    /** A landscape or tablet cell, where the original metrics were fine. */
    private val wideCell = 260.dp

    /** Two lines of `titleSmall` (14sp on a 20sp line), the tallest title the card allows. */
    private val twoLines = 40.dp

    @Test
    fun `a phone-width cell gives the title enough room for a full label`() {
        val layout = moodAndGenresCardLayout(phoneCell)
        assertTrue("162dp is the half-width cell this exists for", layout.compact)
        assertEquals(2, layout.titleMaxLines)
        // Measured on the device: the longest single-word mood label ("Classical") is about 57dp at
        // the 14sp this layout uses, and the longest label of all ("Bollywood & Indian") needs two
        // lines of it. Ninety is the width that leaves both readable.
        assertTrue(
            "title column was only ${layout.titleWidth(phoneCell)}",
            layout.titleWidth(phoneCell) >= 90.dp,
        )
    }

    @Test
    fun `the compact title column stops where the artwork starts`() {
        // The invariant that makes two lines safe: the title's column and the cover's column never
        // intersect, so a wrapped second line cannot run under the image.
        val layout = moodAndGenresCardLayout(phoneCell)
        val titleEndsAt = phoneCell - layout.titleEnd
        val coverStartsAt = phoneCell - layout.coverInset - layout.coverSize
        assertTrue(
            "title ends at $titleEndsAt but the cover starts at $coverStartsAt",
            titleEndsAt <= coverStartsAt,
        )
    }

    @Test
    fun `both lines fit inside the card's height`() {
        val layout = moodAndGenresCardLayout(phoneCell)
        assertTrue(
            "two lines plus the bottom inset must fit in $MoodAndGenresButtonHeight",
            layout.titleBottom + twoLines <= MoodAndGenresButtonHeight,
        )
    }

    @Test
    fun `a wide cell keeps the original metrics`() {
        val layout = moodAndGenresCardLayout(wideCell)
        assertFalse("a 260dp cell has room for the wide arrangement", layout.compact)
        assertEquals(1, layout.titleMaxLines)
        assertEquals(80.dp, layout.coverSize)
        assertEquals(92.dp, layout.titleEnd)
        assertEquals(16.dp, layout.titleBottom)
    }

    @Test
    fun `an unbounded measurement is not treated as a narrow cell`() {
        // A card measured with no width limit must not pick the compact metrics, which would only
        // exist to fit a column it has no reason to assume.
        assertFalse(moodAndGenresCardLayout(Dp.Infinity).compact)
    }
}
