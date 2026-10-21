/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins when the player's provenance line is drawn at all.
 *
 * The reported bug: a song started with "next", or by tapping a row in the queue, sat at 0:00 with
 * no line under the controls explaining the wait - while the same fetch reached through "Refetch
 * song" printed "Downloading 25% • gglos". The refetch differed in one thing only: that track had
 * been played before, so Media3 already held a stored format row for it. The gate asked for a format
 * row and nothing else, so the one window where the line has something to say - a track whose audio
 * does not exist yet - was the one window it was never drawn.
 *
 * A live transfer is a statement about the track on screen all by itself, so it has to open the
 * gate: the row's own text already prefers the fetch or download state over stored facts.
 */
class CodecRowVisibilityTest {

    /** The reported case: fetching a track that has no stored format row yet. */
    @Test
    fun `a fetch with no format row is drawn`() {
        assertTrue(
            codecRowIsVisible(
                showCodecOnPlayer = true,
                hasFormatRow = false,
                hasProgress = true,
            ),
        )
    }

    /** The ordinary case: audio already exists, so its facts are drawn. */
    @Test
    fun `a stored row is drawn without any transfer`() {
        assertTrue(
            codecRowIsVisible(
                showCodecOnPlayer = true,
                hasFormatRow = true,
                hasProgress = false,
            ),
        )
    }

    /** Nothing to say: no row, no transfer. The line is hidden rather than drawn empty. */
    @Test
    fun `nothing known means no row`() {
        assertFalse(
            codecRowIsVisible(
                showCodecOnPlayer = true,
                hasFormatRow = false,
                hasProgress = false,
            ),
        )
    }

    /**
     * A sweep that ended with nothing has no format row and no progress left, so this verdict is the
     * only thing the row could draw - and it is the surface that replaced the panel over the cover.
     */
    @Test
    fun `a failed sweep is drawn with nothing else to say`() {
        assertTrue(
            codecRowIsVisible(
                showCodecOnPlayer = true,
                hasFormatRow = false,
                hasProgress = false,
                hasFailure = true,
            ),
        )
    }

    /** The switch still wins: turning the row off is not overridden by an in-flight transfer. */
    @Test
    fun `the setting hides the row even while fetching`() {
        assertFalse(
            codecRowIsVisible(
                showCodecOnPlayer = false,
                hasFormatRow = true,
                hasProgress = true,
            ),
        )
    }
}
