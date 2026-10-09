/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.player

import app.hush.music.playback.FormatRowSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards which sources the player's codec row names, and that the one it does not name really has no
 * phrase of its own.
 *
 * The row is where a claim about the audio meets the reader, so a phrase that repeats what the row
 * already says - a file on this device, which the delivery beside the codec calls *(device cache)* -
 * is noise on the densest line on the screen. The way such a phrase comes back is a `when` branch
 * added for symmetry, which is not a thing a phone shows you: the row simply grows a phrase again and
 * reads as before. The wording itself lives in `hush_strings.xml` and is not asserted here; what is
 * asserted is that the phrase exists, uniquely, for exactly the sources that need one.
 */
class FormatRowSourceLabelTest {
    @Test
    fun `a row served off a file on this device is named by nothing`() {
        // The one source the row already describes: its delivery reads "(device cache)".
        assertNull(formatRowSourceLabelRes(FormatRowSource.DEVICE_FILE))
    }

    @Test
    fun `every other source is named`() {
        FormatRowSource.entries
            .filter { it != FormatRowSource.DEVICE_FILE }
            .forEach { source ->
                assertNotNull("$source", formatRowSourceLabelRes(source))
            }
    }

    @Test
    fun `two sources never share a phrase`() {
        // A copy-paste between the branches of the `when` would leave two sources named identically,
        // which is a row crediting one origin while it means the other.
        val named = FormatRowSource.entries.mapNotNull { formatRowSourceLabelRes(it) }
        assertEquals(named.size, named.toSet().size)
        assertEquals(FormatRowSource.entries.size - 1, named.size)
    }
}
