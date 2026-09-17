/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

internal object PlaybackResumptionPlanner {
    data class PersistedItems<T>(
        val items: List<T>,
        val mediaItemIndex: Int,
        val positionMs: Long,
    )

    data class Result<T>(
        val items: List<T>,
        val startIndex: Int,
        val startPositionMs: Long,
    )

    /**
     * @param fallbackItems what to play when there is nothing loaded *and* nothing persisted - the
     *   state a fresh install, a cleared data directory, or a reinstalled head unit leaves behind.
     *   The caller supplies it (recently played, in its own order) and it is only consulted for a
     *   request that is about to play something, never for a browse one.
     */
    fun <T> resolve(
        currentItems: List<T>,
        currentIndex: Int,
        currentPositionMs: Long,
        persistedItems: PersistedItems<T>?,
        isForPlayback: Boolean,
        fallbackItems: List<T> = emptyList(),
    ): Result<T> {
        val persisted = persistedItems
        val usePersistedItems =
            persisted != null &&
                persisted.items.isNotEmpty() &&
                (
                    currentItems.isEmpty() || (
                        isForPlayback &&
                            currentItems.size == 1 &&
                            persisted.items.size > 1 &&
                            currentItems.first() == persisted.items[persisted.mediaItemIndex.coerceIn(persisted.items.indices)]
                    )
                )

        if (usePersistedItems && persisted != null) {
            return persisted.items.toResult(
                currentIndex = persisted.mediaItemIndex,
                positionMs = persisted.positionMs,
                isForPlayback = isForPlayback,
            )
        }

        if (currentItems.isNotEmpty()) {
            return currentItems.toResult(
                currentIndex = currentIndex,
                positionMs = currentPositionMs,
                isForPlayback = isForPlayback,
            )
        }

        // With nothing loaded and nothing persisted, a request that is about to play is exactly
        // the one that used to be answered with an empty timeline - the transport press that did
        // nothing. A request that is only browsing is left alone, so a controller connecting to
        // display the queue does not cause playback.
        if (isForPlayback && fallbackItems.isNotEmpty()) {
            return fallbackItems.toResult(
                currentIndex = 0,
                positionMs = 0L,
                isForPlayback = true,
            )
        }

        return Result(emptyList(), 0, 0L)
    }

    private fun <T> List<T>.toResult(
        currentIndex: Int,
        positionMs: Long,
        isForPlayback: Boolean,
    ): Result<T> {
        val safeIndex = currentIndex.coerceIn(indices)
        val safePosition = positionMs.coerceAtLeast(0L)
        if (isForPlayback) {
            return Result(
                items = this,
                startIndex = safeIndex,
                startPositionMs = safePosition,
            )
        }

        return Result(
            items = listOf(this[safeIndex]),
            startIndex = 0,
            startPositionMs = safePosition,
        )
    }
}
