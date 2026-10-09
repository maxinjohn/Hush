/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the readout the track-change overlay shows, and not the drawing of it.
 *
 * The overlay is read on a device, off a screenshot, after a movement that is over in a third of a
 * second - so the part that has to be right is the part that decides *what* it says: whether the
 * grid is on the playing page, which bug an arrangement of fractions amounts to, and how a track is
 * shortened for a line of text. Those are the pieces here; the panel itself is a `Text` with the
 * answers in it.
 */
class PlayerDiagnosticsTest {
    @Test
    fun `a grid sitting on the playing page is aligned`() {
        assertTrue(
            gridPageIsAligned(
                firstVisibleItemIndex = 1,
                firstVisibleItemScrollOffset = 0,
                currentSlotIndex = 1,
            ),
        )
    }

    @Test
    fun `a grid on another page is the split page`() {
        // Left between two pages: the previous track's artwork on one side, this one on the other.
        assertFalse(
            gridPageIsAligned(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
                currentSlotIndex = 1,
            ),
        )
    }

    @Test
    fun `a grid short of the page is the split page as well`() {
        // The same split a few pixels wide: the right page, stopped part way towards it.
        assertFalse(
            gridPageIsAligned(
                firstVisibleItemIndex = 1,
                firstVisibleItemScrollOffset = 137,
                currentSlotIndex = 1,
            ),
        )
    }

    @Test
    fun `a change that is behaving names no fault`() {
        assertEquals(
            emptyList<String>(),
            trackChangeWarnings(
                gridAligned = true,
                canvasAlpha = 0f,
                turning = true,
                arrivalProgress = 0.6f,
                coverLoaded = true,
            ),
        )
    }

    @Test
    fun `a grid off the playing page is reported as a split page`() {
        assertTrue(
            trackChangeWarnings(
                gridAligned = false,
                canvasAlpha = 0f,
                turning = false,
                arrivalProgress = 0f,
                coverLoaded = true,
            ).contains("split-page"),
        )
    }

    @Test
    fun `a canvas still showing over a turning page is reported`() {
        // The bug the overlay was written for: the live-action frame of the song already playing,
        // at full opacity, beside the cover of the song arriving.
        assertTrue(
            trackChangeWarnings(
                gridAligned = true,
                canvasAlpha = 1f,
                turning = true,
                arrivalProgress = 0.5f,
                coverLoaded = true,
            ).contains("canvas-over-turn"),
        )
    }

    @Test
    fun `a canvas that has faded out under a turn is not a fault`() {
        // Turning with no canvas over it is what a change without a canvas looks like at all.
        assertFalse(
            trackChangeWarnings(
                gridAligned = true,
                canvasAlpha = 0f,
                turning = true,
                arrivalProgress = 0.5f,
                coverLoaded = true,
            ).contains("canvas-over-turn"),
        )
    }

    @Test
    fun `a canvas over a page that is not moving is not a fault`() {
        // A canvas at full opacity is the normal state of a track that has one.
        assertFalse(
            trackChangeWarnings(
                gridAligned = true,
                canvasAlpha = 1f,
                turning = false,
                arrivalProgress = 0f,
                coverLoaded = true,
            ).contains("canvas-over-turn"),
        )
    }

    @Test
    fun `a turn running without its cover is reported`() {
        assertTrue(
            trackChangeWarnings(
                gridAligned = true,
                canvasAlpha = 0f,
                turning = true,
                arrivalProgress = 0.4f,
                coverLoaded = false,
            ).contains("cover-not-loaded"),
        )
    }

    @Test
    fun `a cover that has not decoded is not a fault while nothing is moving`() {
        assertFalse(
            trackChangeWarnings(
                gridAligned = true,
                canvasAlpha = 0f,
                turning = false,
                arrivalProgress = 0f,
                coverLoaded = false,
            ).contains("cover-not-loaded"),
        )
    }

    @Test
    fun `the first frame of a movement is not yet a movement`() {
        // The threshold exists because the last frame of a turn lands a hair off the end, and a
        // panel that flickered between "turning" and "still" would be unreadable.
        assertEquals(
            emptyList<String>(),
            trackChangeWarnings(
                gridAligned = true,
                canvasAlpha = 1f,
                turning = true,
                arrivalProgress = PLAYER_DIAGNOSTICS_ACTIVITY,
                coverLoaded = false,
            ),
        )
    }

    @Test
    fun `a long track key is shown as its tail`() {
        // Two media ids, or two artwork urls, differ at the end; the head is shared boilerplate.
        assertEquals("…aa11bb22cc33", shortTrackKey("song:0123456789aa11bb22cc33"))
    }

    @Test
    fun `a track key that is already short is shown whole`() {
        assertEquals("4eZ2mQ1", shortTrackKey("4eZ2mQ1"))
    }

    @Test
    fun `a page with no key yet is shown as a dash`() {
        assertEquals("-", shortTrackKey(null))
        assertEquals("-", shortTrackKey(""))
    }
}
