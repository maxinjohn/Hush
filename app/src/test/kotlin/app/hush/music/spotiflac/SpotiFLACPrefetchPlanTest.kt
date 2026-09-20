/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The count comes from the user's "Prefetch upcoming songs" setting, so these pin the two
 * mistakes that would otherwise be silent: warming the track that is already playing, and
 * warming a different number than the user asked for.
 */
class SpotiFLACPrefetchPlanTest {

    @Test
    fun `the configured count is the number of tracks warmed`() {
        for (count in 1..4) {
            assertEquals(
                "count=$count",
                (1..count).toList(),
                SpotiFLACPrefetchPlan.upcomingIndices(total = 50, currentIndex = 0, count = count),
            )
        }
    }

    @Test
    fun `zero means off, not one`() {
        assertTrue(SpotiFLACPrefetchPlan.upcomingIndices(total = 50, currentIndex = 3, count = 0).isEmpty())
    }

    @Test
    fun `a looping queue warms across the wrap`() {
        assertEquals(
            listOf(8, 9, 0, 1),
            SpotiFLACPrefetchPlan.upcomingIndices(total = 10, currentIndex = 7, count = 4),
        )
    }

    /**
     * The other half of the wrap rule. A queue that ends stops there, because the positions
     * at the front are not what plays next - and on the SpotiFLAC side warming them means
     * downloading tracks nobody will hear.
     */
    @Test
    fun `a queue that ends stops at its last track`() {
        assertEquals(
            listOf(8, 9),
            SpotiFLACPrefetchPlan.upcomingIndices(
                total = 10,
                currentIndex = 7,
                count = 4,
                wrap = false,
            ),
        )
    }

    @Test
    fun `away from the end, wrapping and not wrapping agree`() {
        for (currentIndex in 0..4) {
            assertEquals(
                "currentIndex=$currentIndex",
                SpotiFLACPrefetchPlan.upcomingIndices(10, currentIndex, 4, wrap = true),
                SpotiFLACPrefetchPlan.upcomingIndices(10, currentIndex, 4, wrap = false),
            )
        }
    }

    @Test
    fun `not wrapping on the last track warms nothing after it`() {
        assertTrue(
            SpotiFLACPrefetchPlan
                .upcomingIndices(total = 10, currentIndex = 9, count = 4, wrap = false)
                .isEmpty(),
        )
    }

    @Test
    fun `a one-item queue warms nothing, because the only track is the one playing`() {
        assertTrue(SpotiFLACPrefetchPlan.upcomingIndices(total = 1, currentIndex = 0, count = 4).isEmpty())
    }

    @Test
    fun `a short queue stops before the track that is playing`() {
        // Three items, four asked for: two are ahead, the third is the one on.
        assertEquals(
            listOf(1, 2),
            SpotiFLACPrefetchPlan.upcomingIndices(total = 3, currentIndex = 0, count = 4),
        )
    }

    @Test
    fun `a position is never planned twice`() {
        val plan = SpotiFLACPrefetchPlan.upcomingIndices(total = 3, currentIndex = 1, count = 4)

        assertEquals(listOf(2, 0), plan)
        assertEquals(plan.distinct().size, plan.size)
    }

    @Test
    fun `an empty or unpositioned queue warms nothing`() {
        assertTrue(SpotiFLACPrefetchPlan.upcomingIndices(total = 0, currentIndex = 0, count = 2).isEmpty())
        assertTrue(SpotiFLACPrefetchPlan.upcomingIndices(total = 5, currentIndex = -1, count = 2).isEmpty())
    }
}
