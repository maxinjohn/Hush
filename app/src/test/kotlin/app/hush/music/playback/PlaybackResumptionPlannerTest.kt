/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fallback exists for one state only: nothing loaded, nothing persisted, and a request that is
 * about to play. The tests around it are about *not* widening that - a browse request gaining a
 * queue, or a fallback outranking something the user actually had, would both be worse than the
 * dead button this replaces.
 */
class PlaybackResumptionPlannerTest {

    private val fallback = listOf("recent-1", "recent-2", "recent-3")

    @Test
    fun `a playback request with nothing loaded and nothing persisted plays the fallback`() {
        val result =
            PlaybackResumptionPlanner.resolve(
                currentItems = emptyList<String>(),
                currentIndex = 0,
                currentPositionMs = 0L,
                persistedItems = null,
                isForPlayback = true,
                fallbackItems = fallback,
            )

        assertEquals(fallback, result.items)
        assertEquals(0, result.startIndex)
        assertEquals(0L, result.startPositionMs)
    }

    @Test
    fun `a browse request with nothing loaded is left empty`() {
        val result =
            PlaybackResumptionPlanner.resolve(
                currentItems = emptyList<String>(),
                currentIndex = 0,
                currentPositionMs = 0L,
                persistedItems = null,
                isForPlayback = false,
                fallbackItems = fallback,
            )

        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `no fallback leaves the empty timeline empty`() {
        val result =
            PlaybackResumptionPlanner.resolve(
                currentItems = emptyList<String>(),
                currentIndex = 0,
                currentPositionMs = 0L,
                persistedItems = null,
                isForPlayback = true,
            )

        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `a persisted queue still wins over the fallback`() {
        val result =
            PlaybackResumptionPlanner.resolve(
                currentItems = emptyList<String>(),
                currentIndex = 0,
                currentPositionMs = 0L,
                persistedItems =
                    PlaybackResumptionPlanner.PersistedItems(
                        items = listOf("was-queued-1", "was-queued-2"),
                        mediaItemIndex = 1,
                        positionMs = 4321L,
                    ),
                isForPlayback = true,
                fallbackItems = fallback,
            )

        assertEquals(listOf("was-queued-1", "was-queued-2"), result.items)
        assertEquals(1, result.startIndex)
        assertEquals(4321L, result.startPositionMs)
    }

    @Test
    fun `what is already loaded still wins over the fallback`() {
        val result =
            PlaybackResumptionPlanner.resolve(
                currentItems = listOf("playing"),
                currentIndex = 0,
                currentPositionMs = 2000L,
                persistedItems = null,
                isForPlayback = true,
                fallbackItems = fallback,
            )

        assertEquals(listOf("playing"), result.items)
        assertEquals(2000L, result.startPositionMs)
    }
}
