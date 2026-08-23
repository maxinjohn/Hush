/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback.queues

import androidx.media3.common.MediaItem
import java.util.concurrent.atomic.AtomicInteger
import app.hush.music.models.MediaMetadata

/**
 * A queue over a live list that can grow after playback starts (e.g. a playlist
 * screen that keeps loading more songs while the user scrolls). The [itemsProvider]
 * is re-read on every page request, and [nextPage] only hands out the items that
 * were added since the last snapshot — so the player's timeline extends without
 * duplicating anything already queued.
 */
class GrowingListQueue(
    private val title: String? = null,
    private val itemsProvider: () -> List<MediaItem>,
    private val startIndex: Int = 0,
    override val preloadItem: MediaMetadata? = null,
) : Queue {
    private val handedOutCount = AtomicInteger(0)

    override suspend fun getInitialStatus(): Queue.Status {
        val items = itemsProvider()
        handedOutCount.set(items.size)
        val safeStartIndex = if (items.isEmpty()) 0 else startIndex.coerceIn(items.indices)
        return Queue.Status(
            title = title,
            items = items,
            mediaItemIndex = safeStartIndex,
        )
    }

    override fun hasNextPage(): Boolean = itemsProvider().size > handedOutCount.get()

    override suspend fun nextPage(): List<MediaItem> {
        val items = itemsProvider()
        while (true) {
            val start = handedOutCount.get()
            if (items.size <= start) return emptyList()
            if (handedOutCount.compareAndSet(start, items.size)) {
                return items.subList(start, items.size).toList()
            }
        }
    }
}
