/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.constants

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the mini player's height against the display's font scale.
 *
 * The bug this exists for was measured on a device at a 2.0 font scale: the pill stayed its 64dp
 * while its two lines came back 32.8dp and 15.2dp, so the artist line was drawn cut in half. The
 * repair is a pill that is tall enough for the typography, which sounds like a multiplication and is
 * not one: since Android 14 a font scale follows a curve that grows more slowly above roughly 20sp,
 * and Compose applies the same curve, so Material's 24sp and 16sp line heights measure 36dp and 28dp
 * at a 2.0 scale rather than the 48dp and 32dp a multiplier would give.
 *
 * Both halves of that are not obvious from reading the code, and the version of it that added the sp
 * up first - 40sp, converted once, 46.9dp at that scale - looked right and left the pill exactly as
 * clipped as before, because 46.9dp is less than the 64dp it was compared against. So the conversion
 * is pinned here, at the densities a phone really has, rather than left to be re-derived.
 */
class MiniPlayerHeightTest {

    /** The density override this was measured on: 640dpi, so four pixels to the dp. */
    private fun phone(fontScale: Float) = Density(density = 4f, fontScale = fontScale)

    /** Material's defaults for the two lines MiniPlayerInfo draws. */
    private fun lines(fontScale: Float): Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp> =
        with(phone(fontScale)) { 24.sp.toDp() to 16.sp.toDp() }

    @Test
    fun `at the default text size the pill keeps its designed height`() {
        val (title, artist) = lines(1.0f)
        assertEquals(24.dp, title)
        assertEquals(16.dp, artist)
        assertEquals(MiniPlayerHeight, miniPlayerHeight(title, artist))
    }

    @Test
    fun `at the largest text size the pill grows to fit both lines`() {
        val (title, artist) = lines(2.0f)
        // The curve, not a multiplication: 24sp and 16sp measure 36dp and 28dp here.
        assertEquals(36.dp, title)
        assertEquals(28.dp, artist)
        assertEquals(36.dp + 28.dp + MiniPlayerContentVerticalPadding * 2, miniPlayerHeight(title, artist))
    }

    @Test
    fun `the pill always leaves the content row room for both lines`() {
        listOf(0.85f, 1.0f, 1.15f, 1.3f, 1.5f, 1.8f, 2.0f).forEach { scale ->
            val (title, artist) = lines(scale)
            val pill = miniPlayerHeight(title, artist)
            assertTrue(
                "at scale $scale the pill is $pill for two lines needing ${title + artist}",
                pill - MiniPlayerContentVerticalPadding * 2 >= title + artist,
            )
        }
    }

    @Test
    fun `reading the two line heights separately is what makes the pill grow`() {
        val (title, artist) = lines(2.0f)
        // The shape of the fix, spelled out: one 40sp line height converted in one step is 46.9dp at
        // this scale, which the 64dp floor swallows whole. If this fails the pill has stopped
        // following the text and the artist line is clipped again.
        val convertedInOneStep = with(phone(2.0f)) { (24f + 16f).sp.toDp() }
        assertTrue("46.9dp was expected, got $convertedInOneStep", convertedInOneStep < 48.dp)
        assertEquals(MiniPlayerHeight, miniPlayerHeight(convertedInOneStep, 0.dp))
        assertTrue(miniPlayerHeight(title, artist) > MiniPlayerHeight)
    }
}
