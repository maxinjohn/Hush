/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import androidx.media3.common.MediaItem
import app.hush.music.playback.queues.Queue
import app.hush.music.playback.queues.filterBlockedArtists
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Blocked artists are now filtered in the playback queue as well as on browse screens.
 *
 * Dropping a track that sits before the selected one shifts every later position, so the
 * filter has to move [Queue.Status.mediaItemIndex] with the items or playback starts on
 * the wrong song. That re-counting lives in `Status.filterItems`, which every status
 * filter (explicit, video, blocked artists) delegates to; these tests pin it down using
 * media ids, because a MediaItem cannot carry metadata in a plain JVM unit test.
 */
class QueueBlockedArtistFilterTest {
    private fun items(ids: List<String>): List<MediaItem> =
        ids.map { id -> MediaItem.Builder().setMediaId(id).build() }

    private fun status(ids: List<String>, index: Int) =
        Queue.Status(title = null, items = items(ids), mediaItemIndex = index)

    @Test
    fun `dropping a track before the selection keeps the same song selected`() {
        val filtered = status(listOf("a", "b", "c"), index = 2).filterItems { it.mediaId != "a" }

        assertEquals(listOf("b", "c"), filtered.items.map { it.mediaId })
        assertEquals(1, filtered.mediaItemIndex)
        assertEquals("c", filtered.items[filtered.mediaItemIndex].mediaId)
    }

    @Test
    fun `dropping a track after the selection does not move the selection`() {
        val filtered = status(listOf("a", "b", "c"), index = 1).filterItems { it.mediaId != "c" }

        assertEquals(listOf("a", "b"), filtered.items.map { it.mediaId })
        assertEquals(1, filtered.mediaItemIndex)
    }

    @Test
    fun `dropping several tracks before the selection counts them all`() {
        val filtered =
            status(listOf("a", "b", "c", "d"), index = 3).filterItems { it.mediaId !in setOf("a", "b") }

        assertEquals(listOf("c", "d"), filtered.items.map { it.mediaId })
        assertEquals(1, filtered.mediaItemIndex)
    }

    @Test
    fun `dropping the selected track clamps to a valid position instead of crashing`() {
        val filtered = status(listOf("a", "b"), index = 1).filterItems { it.mediaId != "b" }

        assertEquals(listOf("a"), filtered.items.map { it.mediaId })
        assertEquals(0, filtered.mediaItemIndex)
    }

    @Test
    fun `dropping everything yields an empty status rather than an invalid index`() {
        val filtered = status(listOf("a", "b"), index = 1).filterItems { false }

        assertEquals(emptyList<String>(), filtered.items.map { it.mediaId })
        assertEquals(0, filtered.mediaItemIndex)
    }

    @Test
    fun `an out of range selection is clamped rather than crashing`() {
        val filtered = status(listOf("a", "b"), index = 9).filterItems { true }

        assertEquals(listOf("a", "b"), filtered.items.map { it.mediaId })
        assertEquals(1, filtered.mediaItemIndex)
    }

    @Test
    fun `no blocked artists is a no-op`() {
        val original = status(listOf("a", "b"), index = 1)
        val filtered = original.filterBlockedArtists(emptySet())

        assertEquals(listOf("a", "b"), filtered.items.map { it.mediaId })
        assertEquals(1, filtered.mediaItemIndex)
    }

    @Test
    fun `empty queue filtering stays empty`() {
        val filtered = status(emptyList(), index = 0).filterItems { false }

        assertEquals(emptyList<String>(), filtered.items.map { it.mediaId })
        assertEquals(0, filtered.mediaItemIndex)
    }

    @Test
    fun `list filter with no blocked artists is a no-op`() {
        val original = items(listOf("a", "b"))

        assertEquals(listOf("a", "b"), original.filterBlockedArtists(emptySet()).map { it.mediaId })
    }
}
