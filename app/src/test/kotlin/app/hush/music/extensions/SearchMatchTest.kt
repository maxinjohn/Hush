package app.hush.music.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMatchTest {
    @Test
    fun `normalize lowercases trims and strips diacritics`() {
        assertEquals("cafe", "Café".normalizeForSearch())
        assertEquals("beyonce", "  Beyoncé ".normalizeForSearch())
        assertEquals("sigur ros", "Sigur Rós".normalizeForSearch())
        assertEquals("resume", "Résumé".normalizeForSearch())
    }

    @Test
    fun `a blank query matches everything`() {
        assertTrue(matchesNormalizedQuery("", "anything"))
        assertTrue(matchesNormalizedQuery("   ", "anything"))
        assertTrue(matchesNormalizedQuery(""))
        assertTrue(matchesNormalizedQuery("", null))
    }

    @Test
    fun `matching is case and diacritics insensitive`() {
        assertTrue(matchesNormalizedQuery("café", "Cafe Latte"))
        assertTrue(matchesNormalizedQuery("BEYONCE", "Beyoncé"))
        assertTrue(matchesNormalizedQuery("sigur", "Sigur Rós"))
        assertTrue(matchesNormalizedQuery("gur ro", "Sigur Rós"))
        assertFalse(matchesNormalizedQuery("dark side", "Abbey Road", "The Beatles"))
    }

    @Test
    fun `a match in any value counts`() {
        assertTrue(matchesNormalizedQuery("abbey", "Some Album", "Abbey Road", "The Beatles"))
        assertTrue(matchesNormalizedQuery("beatles", "Some Album", "Abbey Road", "The Beatles"))
        assertFalse(matchesNormalizedQuery("dark side", "Abbey Road", "The Beatles"))
    }

    @Test
    fun `null values never match`() {
        assertFalse(matchesNormalizedQuery("query", null))
        assertFalse(matchesNormalizedQuery("query", null, null))
        assertTrue(matchesNormalizedQuery("query", null, "a query within"))
    }

    @Test
    fun `partial words match as substrings`() {
        assertTrue(matchesNormalizedQuery("road", "Abbey Road"))
        assertFalse(matchesNormalizedQuery("roady", "Abbey Road"))
    }
}
