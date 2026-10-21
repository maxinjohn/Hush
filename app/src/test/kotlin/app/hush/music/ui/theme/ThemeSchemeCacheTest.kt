/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The theme's palette is solved once per key, not once per ask.
 *
 * A cold start asked the HCT solver for the *same* scheme four times before the first frame: the
 * dynamic palette feeds one key colour in as primary, secondary, tertiary and neutral, and
 * `mergedSeedColorScheme` calls the solver for each of them. `HushTheme` also recomposes whenever a
 * font, skin or palette preference lands, and the monochrome branch used to solve inline inside that
 * composition - so the work repeated on frames where nothing about the palette had changed.
 *
 * Asserted on object identity rather than on equal colours, because equality would pass even with no
 * cache at all: two solves of one seed produce the same colours by definition. The question this
 * pins is whether the second ask *did the work*, and the only way to see that from outside is
 * whether it handed back the instance the first ask built.
 */
class ThemeSchemeCacheTest {

    private val seed = Color(0xFF3F51B5)

    @Test
    fun `the same seed and appearance is solved once`() {
        val first = materialKolorScheme(seed, isDark = true, contrastLevel = 0.0, style = PaletteStyle.TonalSpot)
        val second = materialKolorScheme(seed, isDark = true, contrastLevel = 0.0, style = PaletteStyle.TonalSpot)
        assertSame(first, second)
    }

    @Test
    fun `a different appearance is a different scheme`() {
        val dark = materialKolorScheme(seed, isDark = true, contrastLevel = 0.0, style = PaletteStyle.TonalSpot)
        val light = materialKolorScheme(seed, isDark = false, contrastLevel = 0.0, style = PaletteStyle.TonalSpot)
        assertNotSame(dark, light)
        // And both are still their own scheme, not one handed back for the other.
        assertNotEqualsScheme(dark, light)
    }

    @Test
    fun `a different seed or style is a different scheme`() {
        val tonal = materialKolorScheme(seed, isDark = true, contrastLevel = 0.0, style = PaletteStyle.TonalSpot)
        val otherSeed = materialKolorScheme(Color(0xFFB53F51), isDark = true, contrastLevel = 0.0, style = PaletteStyle.TonalSpot)
        val monochrome = materialKolorScheme(seed, isDark = true, contrastLevel = 0.0, style = PaletteStyle.Monochrome)
        assertNotSame(tonal, otherSeed)
        assertNotSame(tonal, monochrome)
        assertNotEqualsScheme(tonal, monochrome)
    }

    /** A contrast level is part of the key: the same seed at two levels is two palettes. */
    @Test
    fun `a different contrast level is a different scheme`() {
        val normal = materialKolorScheme(seed, isDark = true, contrastLevel = 0.0, style = PaletteStyle.TonalSpot)
        val high = materialKolorScheme(seed, isDark = true, contrastLevel = 1.0, style = PaletteStyle.TonalSpot)
        assertNotSame(normal, high)
        assertNotEqualsScheme(normal, high)
    }

    private fun assertNotEqualsScheme(
        a: ColorScheme,
        b: ColorScheme,
    ) {
        org.junit.Assert.assertNotEquals(a.primary, b.primary)
    }
}
