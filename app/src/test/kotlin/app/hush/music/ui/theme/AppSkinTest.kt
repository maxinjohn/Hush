/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what a skin is allowed to change, and what the shipped one must not.
 *
 * Two failures this exists for. The first is an install that never opens the setting and still gets
 * a different app: [AppSkin.HUSH] has to be the identity, and every value in its spec has to be a
 * value `HushTheme` already used. The second is a skin that is a name with nothing in it - entries
 * in a picker that all render the same - which is why the specs' distinctness is asserted rather
 * than assumed, as a pair-by-pair comparison of every skin the picker offers rather than a list
 * written out here.
 */
class AppSkinTest {

    private val brand = getTypography(FontFamily.Default)

    @Test
    fun `the shipped skin changes nothing`() {
        val spec = AppSkin.DEFAULT.spec

        assertEquals(AppSkin.HUSH, AppSkin.DEFAULT)
        assertEquals(1f, spec.radiusScale)
        assertEquals(SkinTypography.BRAND, spec.typography)
        assertEquals(SkinColor.USER_PALETTE, spec.color)
        assertEquals(SkinSurface.STANDARD, spec.surface)
        assertEquals(SkinNav.FLOATING, spec.nav)
        assertEquals(SkinRow.ICON_CIRCLE, spec.row)
        assertEquals(SkinMiniPlayer.HUSH, spec.miniPlayer)
        assertTrue(spec.expressiveMotion)
        // Scale 1 through skinRadius is the identity, so every radius in the app is the number it
        // was written as before skins existed.
        assertEquals(32.dp, skinRadius(32.dp, spec))
        // And the brand scale is passed through untouched, not rebuilt style by style.
        assertSame(brand, skinTypography(AppSkin.HUSH, brand))
    }

    @Test
    fun `a stored id is normalized, never fatal`() {
        assertEquals(AppSkin.HUSH, AppSkin.fromId("hush"))
        // The picker stores the enum's name; a hand-edited or downgraded value arrives in either
        // spelling and both have to land somewhere.
        assertEquals(AppSkin.BITCHORD, AppSkin.fromId("BITCHORD"))
        assertEquals(AppSkin.METROLIST, AppSkin.fromId(" Metrolist "))
        assertEquals(AppSkin.DEFAULT, AppSkin.fromId(null))
        assertEquals(AppSkin.DEFAULT, AppSkin.fromId(""))
        // A skin from a build this one does not have is the shipped look, not a crash on startup.
        assertEquals(AppSkin.DEFAULT, AppSkin.fromId("some-removed-skin"))
    }

    @Test
    fun `every skin in the picker is a choice, so no two are the same`() {
        val specs = AppSkin.selectableValues.map { it.spec }

        assertTrue("the picker offers every skin", AppSkin.selectableValues.size == AppSkin.entries.size)
        for (i in specs.indices) {
            for (j in specs.indices) {
                if (i == j) continue
                assertTrue(
                    "skins ${AppSkin.entries[i]} and ${AppSkin.entries[j]} render identically",
                    specs[i] != specs[j],
                )
            }
        }
    }

    @Test
    fun `radius scale is clamped on both sides`() {
        assertTrue(MinRadiusScale < 1f && 1f < MaxRadiusScale)
        // Floors and ceilings matter because this multiplies values that reach layout: outside them a
        // card becomes a circle or a row collapses to a point.
        assertEquals(MaxRadiusScale.dp, skinRadius(1.dp, AppSkin.HUSH.spec.copy(radiusScale = 99f)))
        assertEquals(MinRadiusScale.dp, skinRadius(1.dp, AppSkin.HUSH.spec.copy(radiusScale = 0f)))
        // And inside them the scale is applied as written, from the same base the components use.
        assertEquals(32.dp * AppSkin.BITCHORD.spec.radiusScale, skinRadius(32.dp, AppSkin.BITCHORD.spec))
        assertTrue(skinRadius(32.dp, AppSkin.BITCHORD.spec) < 32.dp)
        // Metrolist's corners are *tighter* than Hush's, which is the correction this assertion
        // needed: the parent draws its cards at 24dp and Hush's own card is 32dp, so a scale above 1
        // would have made the skin rounder than the app it is named after while looking plausible.
        assertEquals(24.dp, skinRadius(32.dp, AppSkin.METROLIST.spec))
        assertTrue(skinRadius(32.dp, AppSkin.SIMPMUSIC.spec) < 24.dp)
    }

    @Test
    fun `the bottom panel's measurements follow the skin, and the shipped one is untouched`() {
        // Hush's pill is the numbers `MiniPlayerComponents` was written with, so an install that
        // never opens the picker draws exactly what it drew before.
        val hush = skinMiniPlayerAnatomy(AppSkin.HUSH)

        assertEquals(MiniPlayerAnatomy.Hush, hush)
        assertEquals(64.dp, hush.height)
        assertEquals(0.dp, hush.horizontalInset)
        assertEquals(48.dp, hush.artworkOuter)
        assertEquals(38.dp, hush.artworkInner)
        assertNull(hush.artworkCorner)
        assertTrue(hush.showsProgressRing)
        // A parent's capsule is inset and its cover is round; a parent's compact pill is shorter and
        // its cover is a rounded square with no ring, which is the difference a user sees before any
        // colour is involved.
        val capsule = skinMiniPlayerAnatomy(AppSkin.METROLIST)

        assertEquals(MiniPlayerAnatomy.MaterialCapsule, capsule)
        assertEquals(12.dp, capsule.horizontalInset)
        assertNull(capsule.artworkCorner)
        assertEquals(MiniPlayerAnatomy.CompactPill, skinMiniPlayerAnatomy(AppSkin.BITCHORD))
        assertEquals(56.dp, MiniPlayerAnatomy.CompactPill.height)
        assertEquals(8.dp, MiniPlayerAnatomy.CompactPill.artworkCorner)
        assertEquals(false, MiniPlayerAnatomy.CompactPill.showsProgressRing)
        // Two parents share each panel, and the two panels are genuinely different - a picker where
        // one of them was a second name for the other would be a choice that does nothing.
        assertEquals(skinMiniPlayerAnatomy(AppSkin.VIVI), capsule)
        assertEquals(skinMiniPlayerAnatomy(AppSkin.SIMPMUSIC), MiniPlayerAnatomy.CompactPill)
        assertNotEquals(capsule, MiniPlayerAnatomy.CompactPill)
    }

    @Test
    fun `a Material skin reserves Material's bar, not the pill's`() {
        // What the bar costs the screens around it. A Metrolist or Vivi screen draws Material's own
        // `NavigationBar` - taller than the pill, flush with the window's bottom inset, and the same
        // height whether the player is showing or not - while every screen reserves room for it from
        // these numbers. The pill's numbers against a Material bar is what cut the last line off the
        // content on the reporting device, so the two are pinned apart here rather than left to be
        // noticed on a phone.
        val material = skinNavigationAnatomy(AppSkin.METROLIST)
        val pill = skinNavigationAnatomy(AppSkin.HUSH)

        assertEquals(NavigationBarAnatomy.MaterialBarHeight, material.height)
        assertTrue(material.height > pill.height)
        assertEquals(material.height, material.compactHeight)
        assertEquals(0.dp, material.bottomPadding)
        assertEquals(0.dp, material.compactBottomPadding)
        assertTrue(material.consumesSystemInset)
        assertEquals(skinNavigationAnatomy(AppSkin.VIVI), material)
        // Hush's own bar is exactly what it was: 64dp floating 8dp above the system inset, 56dp with
        // the player showing, and the screens behind it reserve that inset themselves.
        assertEquals(64.dp, pill.height)
        assertEquals(56.dp, pill.compactHeight)
        assertEquals(8.dp, pill.bottomPadding)
        assertEquals(6.dp, pill.compactBottomPadding)
        assertFalse(pill.consumesSystemInset)
        // BitChord draws a floating pill as well - the one its own bottom bar is - so it reserves the
        // pill's numbers, and is not left padded for a bar it does not draw.
        assertEquals(pill, skinNavigationAnatomy(AppSkin.BITCHORD))
    }

    @Test
    fun `SimpMusic reserves its capsule, and the capsule is not the pill`() {
        // The third bar, and the reason it is a value of its own: a 56dp capsule that floats 12dp
        // above the window's inset with Search outside it, against the pill's 64dp and its 8dp. A
        // screen that reserved the pill's numbers behind this bar would leave its last line under
        // the bar; one that reserved these behind the pill would float it over the content.
        val capsule = skinNavigationAnatomy(AppSkin.SIMPMUSIC)

        assertEquals(NavigationBarAnatomy.SplitCapsule, capsule)
        assertEquals(56.dp, capsule.height)
        assertEquals(12.dp, capsule.bottomPadding)
        assertFalse(capsule.consumesSystemInset)
        assertNotEquals(skinNavigationAnatomy(AppSkin.HUSH), capsule)
        assertNotEquals(skinNavigationAnatomy(AppSkin.METROLIST), capsule)
    }

    @Test
    fun `a skin names the app the way its parent's top bar does`() {
        // The header is the other half of the chrome, and it is not something the bar implies: the
        // two Skins with Material's bar are the two that name the screen, and the one with its own
        // bar names the app. Pinned here because a header and a bar that disagreed would put the
        // app's name over a screen the parent names, which is exactly the mix-up this fixed.
        assertEquals(SkinHeader.BRAND, AppSkin.HUSH.spec.header)
        assertEquals(SkinHeader.BRAND, AppSkin.BITCHORD.spec.header)
        assertEquals(SkinHeader.SCREEN_TITLE, AppSkin.METROLIST.spec.header)
        assertEquals(SkinHeader.SCREEN_TITLE, AppSkin.VIVI.spec.header)
        assertEquals(SkinHeader.APP_NAME, AppSkin.SIMPMUSIC.spec.header)
        // Vivi leads its bar with the app's mark; Metrolist's has none, and neither has SimpMusic's.
        assertTrue(AppSkin.VIVI.spec.headerMark)
        assertFalse(AppSkin.METROLIST.spec.headerMark)
        assertFalse(AppSkin.SIMPMUSIC.spec.headerMark)
        // The shipped look keeps its mark, which is what `HushTheme` draws today.
        assertTrue(AppSkin.HUSH.spec.headerMark)
    }

    @Test
    fun `the app's loose actions are drawn where the skin's own bar keeps them`() {
        // One decision, two halves: a skin whose bar is a parent's bar also has that parent's bar
        // *contents*, and those parents keep recognition, listening together and shuffle out of the
        // bar. Hush's pill carries them inline beside its destinations, so the shipped skin is the
        // one that keeps them in the bar - and BitChord, whose parent's bar is a pill of tabs.
        assertFalse(skinHeaderHostsActions(AppSkin.HUSH))
        assertFalse(skinHeaderHostsActions(AppSkin.BITCHORD))
        assertTrue(skinHeaderHostsActions(AppSkin.METROLIST))
        assertTrue(skinHeaderHostsActions(AppSkin.VIVI))
        assertTrue(skinHeaderHostsActions(AppSkin.SIMPMUSIC))
        // And the rule really is the header, not the bar: nothing with a parent's bar has its
        // actions in the bar, which is what six crowded items on one bar used to be.
        AppSkin.entries.forEach { skin ->
            val parentBar =
                skin.spec.nav != SkinNav.FLOATING
            assertEquals(
                "${skin.label} draws its own bar yet keeps the app's actions in it",
                parentBar,
                skinHeaderHostsActions(skin),
            )
        }
    }

    @Test
    fun `the radius scale reaches the shared radii`() {
        val before = HushDesign.ItemCornerRadius
        val beforeCard = HushDesign.CardCornerRadius
        try {
            HushDesign.applyRadiusScale(0.5f)
            assertEquals(before * 0.5f, HushDesign.ItemCornerRadius)
            assertEquals(beforeCard * 0.5f, HushDesign.CardCornerRadius)
            // Non-radius sizes are not corners and must not move with the scale: the header action
            // is a touch target, and shrinking it would make a control hard to hit.
            assertEquals(56.dp, HushDesign.HeaderActionSize)
            HushDesign.applyRadiusScale(99f)
            assertEquals(before * MaxRadiusScale, HushDesign.ItemCornerRadius)
        } finally {
            HushDesign.applyRadiusScale(1f)
        }
        assertEquals(before, HushDesign.ItemCornerRadius)
    }

    @Test
    fun `the heavy scale goes up in weight and never up in size`() {
        val heavy = skinTypography(AppSkin.BITCHORD, brand)

        fun weight(style: androidx.compose.ui.text.TextStyle) = (style.fontWeight ?: FontWeight.Normal).weight

        listOf(
            "displayLarge" to (brand.displayLarge to heavy.displayLarge),
            "headlineMedium" to (brand.headlineMedium to heavy.headlineMedium),
            "titleLarge" to (brand.titleLarge to heavy.titleLarge),
            "bodyLarge" to (brand.bodyLarge to heavy.bodyLarge),
            "labelSmall" to (brand.labelSmall to heavy.labelSmall),
        ).forEach { (name, styles) ->
            val (baseStyle, heavyStyle) = styles
            assertTrue("$name must not get lighter", weight(heavyStyle) >= weight(baseStyle))
            assertTrue(
                "$name must not get bigger - a heavier face at the same size already reads larger",
                heavyStyle.fontSize.value <= baseStyle.fontSize.value,
            )
        }
        // The display sizes are the ones it pulls in, so the change is visible rather than subtle.
        assertTrue(heavy.displayLarge.fontSize.value < brand.displayLarge.fontSize.value)
    }

    @Test
    fun `the material scale is Material's own, not the app's`() {
        val material = skinTypography(AppSkin.METROLIST, brand)
        val baseline = Typography()

        assertEquals(baseline.displayLarge, material.displayLarge)
        assertEquals(baseline.bodyMedium, material.bodyMedium)
        // And it really is a different scale from the brand one, rather than a relabelled copy:
        // the whole point of a Material-first skin is that it does not wear Hush's type.
        assertNotEquals(brand.displayLarge, material.displayLarge)
    }
}
