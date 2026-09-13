/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import app.hush.music.playback.TransportSkipPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * These three cases are exactly the ones that used to look like a broken button: an
 * empty timeline after a restart, and the last track of a finite queue.
 */
class TransportSkipPolicyTest {
    @Test
    fun `an empty timeline recovers the persisted queue`() {
        assertEquals(Action.RECOVER_QUEUE, TransportSkipPolicy.nextAction(0, hasNext = false, repeatEnabled = false))
        assertEquals(Action.RECOVER_QUEUE, TransportSkipPolicy.previousAction(0))
    }

    @Test
    fun `a normal skip skips`() {
        assertEquals(Action.SKIP, TransportSkipPolicy.nextAction(10, hasNext = true, repeatEnabled = false))
        assertEquals(Action.SKIP, TransportSkipPolicy.previousAction(10))
    }

    @Test
    fun `the last track of a finite queue grows the queue instead of doing nothing`() {
        assertEquals(Action.EXTEND_QUEUE, TransportSkipPolicy.nextAction(1, hasNext = false, repeatEnabled = false))
        assertEquals(Action.EXTEND_QUEUE, TransportSkipPolicy.nextAction(40, hasNext = false, repeatEnabled = false))
    }

    @Test
    fun `repeat all wraps, so the player handles it`() {
        assertEquals(Action.SKIP, TransportSkipPolicy.nextAction(1, hasNext = false, repeatEnabled = true))
        assertEquals(Action.SKIP, TransportSkipPolicy.nextAction(40, hasNext = false, repeatEnabled = true))
    }
}
