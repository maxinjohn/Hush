/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.constants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards which player design the app offers, because the two halves of that answer have to agree.
 *
 * Aura is the design the app ships with; Expressive (V6) is still implemented but is no longer
 * offered, so it has to be treated as a legacy style - if it is not, an install that stored V6 keeps
 * rendering a layout whose settings entry no longer exists and whose row therefore shows nothing
 * selected. These assertions are the ones a careless edit breaks in silence: the settings screen
 * reads `selectableValues` and the player reads `normalized()`, so a default and a selectable list
 * that disagree produce a player nobody can name.
 */
class PlayerDesignStyleTest {
    @Test
    fun `Aura is the design the app ships with`() {
        assertEquals(PlayerDesignStyle.AURA, PlayerDesignStyle.DEFAULT)
    }

    @Test
    fun `Aura is an active style that keeps its identity`() {
        assertFalse(PlayerDesignStyle.AURA.isDeprecated)
        assertEquals(PlayerDesignStyle.AURA, PlayerDesignStyle.AURA.normalized())
    }

    @Test
    fun `Expressive is no longer offered and migrates to the shipped design`() {
        assertTrue("V6 must be treated as legacy", PlayerDesignStyle.V6.isDeprecated)
        assertEquals(PlayerDesignStyle.AURA, PlayerDesignStyle.V6.normalized())
    }

    @Test
    fun `legacy styles still normalize to the default`() {
        val legacy =
            listOf(
                PlayerDesignStyle.V1,
                PlayerDesignStyle.V2,
                PlayerDesignStyle.V3,
                PlayerDesignStyle.V4,
                PlayerDesignStyle.V5,
                PlayerDesignStyle.V6,
                PlayerDesignStyle.V7,
                PlayerDesignStyle.V8,
                PlayerDesignStyle.V9,
            )
        legacy.forEach { style ->
            assertTrue("$style should be deprecated", style.isDeprecated)
            assertEquals(PlayerDesignStyle.DEFAULT, style.normalized())
        }
    }

    @Test
    fun `settings offers Aura and nothing else`() {
        assertEquals(
            listOf(PlayerDesignStyle.AURA),
            PlayerDesignStyle.selectableValues,
        )
    }

    @Test
    fun `every selectable style is one the app can actually render`() {
        PlayerDesignStyle.selectableValues.forEach { style ->
            assertFalse("$style is selectable but deprecated", style.isDeprecated)
            assertEquals("$style would not survive normalization", style, style.normalized())
        }
    }

    @Test
    fun `no deprecated style is selectable`() {
        PlayerDesignStyle.entries
            .filter { it.isDeprecated }
            .forEach { assertFalse(it in PlayerDesignStyle.selectableValues) }
    }
}
