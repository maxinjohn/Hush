/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the one reading that may extend a sweep's clock, and the two bounds on it.
 *
 * The device measurement this exists for is in the rule's own documentation: with seventeen sources
 * switched on the walk is fourteen candidates, the budget is capped at 96s, and a provider 70%
 * through a 26 MB lossless file was cancelled at the cap - then recorded as `sweep unfinished` and
 * the track played from YouTube. The clock has to tell that apart from a chain that is genuinely
 * stuck, and the difference is whether the transfer is still *growing*: every surface keeps the last
 * progress sample it was given, so "bytes were seen" cannot distinguish a live download from a dead
 * one. Four readings cover the rules that follow from that.
 */
class SpotiFLACSweepBudgetTest {

    private val budget = SpotiFLACSweepBudget

    @Test
    fun `a fresh clock runs for exactly its budget`() {
        val clock = budget.start(nowMs = 1_000L, budgetMs = 96_000L)

        assertEquals(1_000L, clock.startedAtMs)
        assertEquals(97_000L, clock.deadlineMs)
        assertEquals(96_000L, clock.remainingMs(1_000L))
        assertFalse(clock.expired(96_999L))
        assertTrue(clock.expired(97_000L))
    }

    @Test
    fun `a transfer that is still growing moves the clock`() {
        val clock = budget.start(nowMs = 0L, budgetMs = 96_000L)

        val extended = budget.afterProgress(clock, nowMs = 90_000L, transferredBytes = 18_677_580L)

        assertEquals(90_000L + budget.TRANSFER_GRACE_MS, extended.deadlineMs)
        assertTrue(extended.deadlineMs > clock.deadlineMs)
        assertTrue(budget.extended(extended))
    }

    @Test
    fun `the same reading twice does not move it`() {
        // This is the whole reason the rule is "growing" and not "present": the meter keeps its last
        // sample, so a finished or dead transfer would otherwise hold the sweep open forever.
        val clock = budget.start(nowMs = 0L, budgetMs = 96_000L)
        val moved = budget.afterProgress(clock, nowMs = 10_000L, transferredBytes = 5_000_000L)

        val again = budget.afterProgress(moved, nowMs = 40_000L, transferredBytes = 5_000_000L)

        assertEquals(moved, again)
    }

    @Test
    fun `the next attempt starting its own count moves it too`() {
        // Measured on the reporting device: one provider trickled 47MB over two minutes and then went
        // quiet, the clock ran out at 168s, and every source below it in the user's list was never
        // asked - the failure the budget exists to prevent, one level down. A new attempt is the walk
        // continuing, so a lower reading is activity exactly as a higher one is.
        val clock = budget.start(nowMs = 0L, budgetMs = 96_000L)
        val moved = budget.afterProgress(clock, nowMs = 10_000L, transferredBytes = 47_000_000L)

        val afterNewAttempt = budget.afterProgress(moved, nowMs = 40_000L, transferredBytes = 16_384L)

        assertEquals(40_000L + budget.TRANSFER_GRACE_MS, afterNewAttempt.deadlineMs)
    }

    @Test
    fun `an attempt that has not moved a byte does not hold the clock open`() {
        // Zero is a real reading about this track - an attempt that has started and read nothing yet -
        // but it is the *same* zero on every poll, so a wedged attempt spends the clock it was given
        // and the walk moves on. Only a change moves it.
        val clock = budget.start(nowMs = 0L, budgetMs = 96_000L)

        val started = budget.afterProgress(clock, nowMs = 5_000L, transferredBytes = 0L)
        val stillStarted = budget.afterProgress(started, nowMs = 20_000L, transferredBytes = 0L)

        assertEquals(clock, started)
        assertEquals(clock, stillStarted)
    }

    @Test
    fun `the clock can never be moved past its absolute lid`() {
        // A chain of providers that each trickle a few bytes must not be able to hold playback open:
        // past the lid the walk ends as it always did, whatever the meter says.
        var clock = budget.start(nowMs = 0L, budgetMs = 96_000L)
        var transferred = 0L
        var now = 0L
        while (now < budget.MAX_TOTAL_MS * 2) {
            now += 1_000L
            transferred += 1_000L
            clock = budget.afterProgress(clock, nowMs = now, transferredBytes = transferred)
        }

        assertEquals(budget.MAX_TOTAL_MS, clock.deadlineMs)
        assertTrue(clock.expired(budget.MAX_TOTAL_MS))
    }

    @Test
    fun `a grace is longer than one attempt's own ceiling`() {
        // Otherwise this clock would be deciding something the per-attempt ceiling already decides,
        // and a provider downloading normally would still be cut off - one level up. The ceiling is
        // the source's own two windows (connect + download) under the policy the user set.
        assertTrue(budget.TRANSFER_GRACE_MS > SpotiFLACFetchPolicy.DEFAULT.attemptCeilingMs)
    }
}
