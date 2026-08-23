/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import androidx.media3.common.MediaItem
import app.hush.music.playback.queues.GrowingListQueue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrowingListQueueTest {
    private fun mediaItems(ids: List<String>): List<MediaItem> =
        ids.map { id -> MediaItem.Builder().setMediaId(id).build() }

    @Test
    fun initialStatusSnapshotsCurrentItemsWithStartIndex() = runBlocking {
        val songs = mutableListOf("a", "b", "c")
        val queue =
            GrowingListQueue(
                title = "Liked music",
                itemsProvider = { mediaItems(songs) },
                startIndex = 1,
            )

        val status = queue.getInitialStatus()

        assertEquals("Liked music", status.title)
        assertEquals(listOf("a", "b", "c"), status.items.map { it.mediaId })
        assertEquals(1, status.mediaItemIndex)
        assertFalse(queue.hasNextPage())
    }

    @Test
    fun nextPageReturnsOnlyNewlyAddedItems() = runBlocking {
        val songs = mutableListOf("a", "b", "c")
        val queue =
            GrowingListQueue(
                itemsProvider = { mediaItems(songs) },
                startIndex = 0,
            )

        queue.getInitialStatus()
        assertFalse(queue.hasNextPage())

        songs += listOf("d", "e")
        assertTrue(queue.hasNextPage())

        val page = queue.nextPage()
        assertEquals(listOf("d", "e"), page.map { it.mediaId })
        assertFalse(queue.hasNextPage())
    }

    @Test
    fun multipleGrowthChunksAreHandedOutOnce() = runBlocking {
        val songs = mutableListOf("a", "b")
        val queue = GrowingListQueue(itemsProvider = { mediaItems(songs) })

        queue.getInitialStatus()

        songs += listOf("c", "d", "e")
        assertEquals(listOf("c", "d", "e"), queue.nextPage().map { it.mediaId })

        songs += listOf("f")
        assertEquals(listOf("f"), queue.nextPage().map { it.mediaId })

        assertFalse(queue.hasNextPage())
        assertTrue(queue.nextPage().isEmpty())
    }

    @Test
    fun emptyProviderYieldsEmptyStatusAndSafeStartIndex() = runBlocking {
        val queue =
            GrowingListQueue(
                itemsProvider = { emptyList() },
                startIndex = 3,
            )

        val status = queue.getInitialStatus()

        assertTrue(status.items.isEmpty())
        assertEquals(0, status.mediaItemIndex)
        assertFalse(queue.hasNextPage())
    }

    @Test
    fun shrinkingListStopsPagingWithoutCrashing() = runBlocking {
        val songs = mutableListOf("a", "b", "c", "d")
        val queue = GrowingListQueue(itemsProvider = { mediaItems(songs) })

        queue.getInitialStatus()
        // A refresh replaces the list with fewer items.
        songs.clear()
        songs += listOf("z")

        assertFalse(queue.hasNextPage())
        assertTrue(queue.nextPage().isEmpty())
    }
}
