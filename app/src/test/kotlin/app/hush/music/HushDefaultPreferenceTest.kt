package app.hush.music

import app.hush.music.constants.PulseMatrixEnabledDefault
import app.hush.music.constants.ShowCodecOnPlayerDefault
import app.hush.music.ui.player.visualizer.PulseMatrixDefaultTheme
import app.hush.music.ui.player.visualizer.PulseMatrixTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These defaults are shared by every read site, and each one is deliberately "on unless
 * the user turned it off": a stored value always beats the default, so a fresh install —
 * or an upgrade that never touched the setting — gets the feature without a trip to
 * settings, while anyone who switched it off keeps their choice.
 */
class HushDefaultPreferenceTest {
    @Test
    fun `the codec row is on by default`() {
        assertTrue(ShowCodecOnPlayerDefault)
    }

    @Test
    fun `PulseMatrix is on by default`() {
        assertTrue(PulseMatrixEnabledDefault)
    }

    @Test
    fun `PulseMatrix starts on the Neon theme`() {
        assertEquals(PulseMatrixTheme.NEON, PulseMatrixDefaultTheme)
    }
}
