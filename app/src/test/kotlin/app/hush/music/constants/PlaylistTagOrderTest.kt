package app.hush.music.constants

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistTagOrderTest {
    @Test
    fun `saved order comes first and ids not in the library are dropped`() {
        val available = listOf("a", "b", "c")
        val saved = "c,ghost,b"

        assertEquals(listOf("c", "b", "a"), saved.toPlaylistTagOrder(available))
    }

    @Test
    fun `tags missing from the saved preference are appended in source order`() {
        val available = listOf("new1", "old", "new2")
        val saved = "old"

        assertEquals(listOf("old", "new1", "new2"), saved.toPlaylistTagOrder(available))
    }

    @Test
    fun `a blank preference keeps the source order untouched`() {
        val available = listOf("x", "y")

        assertEquals(available, "".toPlaylistTagOrder(available))
        assertEquals(available, "   ".toPlaylistTagOrder(available))
    }

    @Test
    fun `duplicated saved ids collapse to one position`() {
        val available = listOf("a", "b")

        assertEquals(listOf("b", "a"), "b,b,b".toPlaylistTagOrder(available))
    }

    @Test
    fun `whitespace around saved ids is ignored`() {
        val available = listOf("a", "b")

        assertEquals(listOf("b", "a"), " b , a ".toPlaylistTagOrder(available))
    }

    @Test
    fun `preference round-trips through join and parse`() {
        val ids = listOf("a", "b", "c")

        assertEquals(ids, ids.toPlaylistTagPreference().toPlaylistTagOrder(ids))
        assertEquals("a,b,c", ids.toPlaylistTagPreference())
    }

    @Test
    fun `preference drops duplicates`() {
        assertEquals("a,b", listOf("a", "b", "a").toPlaylistTagPreference())
    }
}
