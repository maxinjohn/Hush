/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the schedule and the stopping rule of the background re-check.
 *
 * The two facts that matter and cannot be read off the app: an outage that clears in a moment is
 * noticed in that moment rather than at the end of the cooldown, and an outage that lasts all evening
 * costs a bounded trickle of probes instead of a poll.
 */
class SpotiFLACAddonRecoveryTest {

    @Test
    fun `the first probe is early and the spacing then doubles to the cap`() {
        assertEquals(30_000L, SpotiFLACAddonRecoveryPolicy.nextProbeMs(0))
        assertEquals(60_000L, SpotiFLACAddonRecoveryPolicy.nextProbeMs(1))
        assertEquals(120_000L, SpotiFLACAddonRecoveryPolicy.nextProbeMs(2))
        assertEquals(240_000L, SpotiFLACAddonRecoveryPolicy.nextProbeMs(3))
        assertEquals(300_000L, SpotiFLACAddonRecoveryPolicy.nextProbeMs(4))
        // Capped, not growing: the cooldown is ten minutes, so a probe every five is the thinnest
        // sampling that still learns about the recovery before the source is back in the walk.
        assertEquals(SpotiFLACAddonRecoveryPolicy.MAX_PROBE_MS, SpotiFLACAddonRecoveryPolicy.nextProbeMs(5))
        assertEquals(SpotiFLACAddonRecoveryPolicy.MAX_PROBE_MS, SpotiFLACAddonRecoveryPolicy.nextProbeMs(64))
        // A counter that somehow ran away must not overflow into a negative or absurd wait.
        assertTrue(SpotiFLACAddonRecoveryPolicy.nextProbeMs(Int.MAX_VALUE) > 0L)
    }

    @Test
    fun `every wait is inside the bounds the schedule promises`() {
        (0..40).forEach { attempt ->
            val wait = SpotiFLACAddonRecoveryPolicy.nextProbeMs(attempt)
            assertTrue(
                "attempt $attempt waited ${wait}ms",
                wait >= SpotiFLACAddonRecoveryPolicy.FIRST_PROBE_MS &&
                    wait <= SpotiFLACAddonRecoveryPolicy.MAX_PROBE_MS,
            )
        }
    }

    @Test
    fun `a source that answers on the second probe is reported once, and the watch ends`() = runTest {
        var probes = 0
        val waited = mutableListOf<Long>()
        var recovered = 0

        val answered =
            SpotiFLACAddonRecovery.watch(
                stillRefused = { true },
                probe = {
                    probes++
                    probes >= 2
                },
                onRecovered = { recovered++ },
                sleep = { waited.add(it) },
            )

        assertTrue(answered)
        assertEquals(2, probes)
        assertEquals(listOf(30_000L, 60_000L), waited)
        assertEquals(1, recovered)
    }

    @Test
    fun `a source that stopped being refused is dropped instead of being asked`() = runTest {
        var probes = 0
        var refused = false

        val answered =
            SpotiFLACAddonRecovery.watch(
                stillRefused = { refused },
                probe = {
                    probes++
                    true
                },
                onRecovered = {},
                sleep = { refused = false },
            )

        assertFalse(answered)
        // The record is gone - its cooldown lapsed and a sweep took it, or the user switched it off
        // - so there is nothing to recover and nobody to tell.
        assertEquals(0, probes)
    }

    @Test
    fun `a source that never answers gives up after its attempts without reporting`() = runTest {
        var probes = 0
        var recovered = 0

        val answered =
            SpotiFLACAddonRecovery.watch(
                stillRefused = { true },
                probe = {
                    probes++
                    false
                },
                onRecovered = { recovered++ },
                sleep = {},
                maxAttempts = 3,
            )

        assertFalse(answered)
        assertEquals(3, probes)
        assertEquals(0, recovered)
    }

    @Test
    fun `a probe that throws is a probe that did not answer, and the watch carries on`() = runTest {
        var probes = 0
        var recovered = 0

        val answered =
            SpotiFLACAddonRecovery.watch(
                stillRefused = { true },
                probe = {
                    probes++
                    if (probes == 1) throw IllegalStateException("connection reset") else true
                },
                onRecovered = { recovered++ },
                sleep = {},
            )

        assertTrue(answered)
        assertEquals(2, probes)
        assertEquals(1, recovered)
    }

    @Test
    fun `each probe is reported with its attempt number`() = runTest {
        val seen = mutableListOf<Pair<Int, Boolean>>()

        SpotiFLACAddonRecovery.watch(
            stillRefused = { true },
            probe = { false },
            onRecovered = {},
            onProbe = { attempt, answered -> seen.add(attempt to answered) },
            sleep = {},
            maxAttempts = 3,
        )

        assertEquals(listOf(1 to false, 2 to false, 3 to false), seen)
    }

    @Test
    fun `the words for a recovery name the source`() {
        assertEquals("Tido is answering again", SpotiFLACAddonRecoveryNotifier.text("Tido"))
    }
}
