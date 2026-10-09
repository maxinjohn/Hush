/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the band rule that keeps an artwork accent off the full-bleed surfaces.
 *
 * The bug this exists for: the player's wash drew the *punched* accent - the same colour the play
 * button uses - so a cover whose accent was a deep ultramarine turned the whole player into a blue
 * slab, and the seek bar and slider had to be frost-white to stay visible on top of it. The tone is
 * two lines of arithmetic and every wrong direction is a user-visible regression: too bright and the
 * player is a colour instead of a surface, too dark and the artwork no longer tints anything, and
 * any change to the hue and the tint stops matching the cover. Testing it through the composable
 * would need a device, and through `Color` would need `android.graphics` - which a JVM test cannot
 * call - so the rule itself is exercised here on raw HSV.
 */
class AuraBackdropToneTest {
    /** The value band `withAuraPunch` lands accents in; every backdrop has to come out of it darker. */
    private val punchedAccentValues = 0.58f..0.86f

    @Test
    fun `a punched accent comes out dark enough to be a surface`() {
        // The ultramarine that turned the player blue: hsv(236, 0.94, 0.67).
        val toned = auraBackdropTone(floatArrayOf(236f, 0.94f, 0.67f))

        assertTrue("tone must stay inside the surface band", toned[2] <= AuraBackdropValueMax)
        assertTrue("tone must stay dark", toned[2] < punchedAccentValues.start)
        assertTrue("tone must stay tinted, not black", toned[2] >= AuraBackdropValueMin)
        assertTrue("chroma must come down", toned[1] <= AuraBackdropSaturationMax)
    }

    @Test
    fun `every value an accent can carry lands in the surface band`() {
        var value = punchedAccentValues.start
        while (value <= punchedAccentValues.endInclusive) {
            val toned = auraBackdropTone(floatArrayOf(210f, 0.62f, value))
            assertTrue("punched value $value leaked out as ${toned[2]}", toned[2] <= AuraBackdropValueMax)
            value += 0.02f
        }
    }

    @Test
    fun `the hue is untouched`() {
        listOf(0f, 32f, 120f, 210f, 236f, 300f, 359f).forEach { hue ->
            assertEquals("hue $hue must survive the tone", hue, auraBackdropTone(floatArrayOf(hue, 0.8f, 0.7f))[0], 0.0001f)
        }
    }

    @Test
    fun `different artwork hues stay different surfaces`() {
        // A blue cover and an amber one must not tone to the same slab: the tint is how the player
        // follows the artwork, and a rule that greyed them together would quietly delete Aura.
        val blue = auraBackdropTone(floatArrayOf(236f, 0.94f, 0.67f))
        val amber = auraBackdropTone(floatArrayOf(32f, 0.90f, 0.70f))

        assertTrue("surfaces must keep some chroma", blue[1] > 0.1f && amber[1] > 0.1f)
        assertTrue("hues must stay far apart", kotlin.math.abs(blue[0] - amber[0]) > 90f)
    }

    @Test
    fun `extremes are clamped, not passed through`() {
        val white = auraBackdropTone(floatArrayOf(0f, 0f, 1f))
        val black = auraBackdropTone(floatArrayOf(0f, 0f, 0f))
        val neon = auraBackdropTone(floatArrayOf(120f, 1f, 1f))

        assertEquals(AuraBackdropValueMax, white[2], 0.0001f)
        assertEquals(AuraBackdropValueMin, black[2], 0.0001f)
        assertEquals(AuraBackdropSaturationMax, neon[1], 0.0001f)
    }
}
