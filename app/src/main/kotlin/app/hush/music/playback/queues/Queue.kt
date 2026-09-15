/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback.queues

import androidx.media3.common.MediaItem
import app.hush.music.extensions.ExtraIsMusicVideo
import app.hush.music.extensions.metadata
import app.hush.music.models.MediaMetadata

interface Queue {
    val preloadItem: MediaMetadata?

    suspend fun getInitialStatus(): Status

    fun shouldExpandToFullQueueWhenAutoLoadMoreDisabled(): Boolean = false

    fun hasNextPage(): Boolean

    suspend fun nextPage(): List<MediaItem>

    data class Status(
        val title: String?,
        val items: List<MediaItem>,
        val mediaItemIndex: Int,
        val position: Long = 0L,
    ) {
        // Must go through filterItems: dropping a track that sits before the current
        // one shifts every later index, and a plain copy(items = ...) would leave
        // mediaItemIndex pointing at a different song than the caller selected.
        fun filterBlockedArtists(blockedArtistIds: Set<String>) =
            if (blockedArtistIds.isEmpty()) {
                this
            } else {
                filterItems { item -> item.metadata?.artists?.any { it.id in blockedArtistIds } != true }
            }

        fun filterExplicit(enabled: Boolean = true) =
            if (enabled) {
                filterItems { it.metadata?.explicit != true }
            } else {
                this
            }

        fun filterVideo(enabled: Boolean = true) =
            if (enabled) {
                filterItems { it.mediaMetadata.extras?.getBoolean(ExtraIsMusicVideo, false) != true }
            } else {
                this
            }

        /**
         * Drops items the [keep] test rejects, moving [mediaItemIndex] with them.
         *
         * Every status filter goes through here for that reason: the selected item must
         * stay selected, so the index has to be re-counted rather than copied over.
         * The item list is not otherwise reordered.
         */
        internal fun filterItems(keep: (MediaItem) -> Boolean): Status {
            if (items.isEmpty()) return this

            val currentIndex = mediaItemIndex.coerceIn(items.indices)
            var filteredIndex = 0
            val filteredItems =
                buildList(items.size) {
                    items.forEachIndexed { index, item ->
                        if (keep(item)) {
                            if (index < currentIndex) {
                                filteredIndex++
                            }
                            add(item)
                        }
                    }
                }

            if (filteredItems.isEmpty()) {
                return copy(items = emptyList(), mediaItemIndex = 0)
            }

            return copy(
                items = filteredItems,
                mediaItemIndex = filteredIndex.coerceIn(filteredItems.indices),
            )
        }
    }
}

fun List<MediaItem>.filterExplicit(enabled: Boolean = true) =
    if (enabled) {
        filterNot {
            it.metadata?.explicit == true
        }
    } else {
        this
    }

fun List<MediaItem>.filterVideo(enabled: Boolean = true) =
    if (enabled) {
        filterNot {
            it.mediaMetadata.extras?.getBoolean(ExtraIsMusicVideo, false) == true
        }
    } else {
        this
    }

fun List<MediaItem>.filterBlockedArtists(blockedArtistIds: Set<String>) =
    if (blockedArtistIds.isEmpty()) this
    else filterNot { item -> item.metadata?.artists?.any { it.id in blockedArtistIds } == true }
