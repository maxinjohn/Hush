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
 * The engine is paused exactly when it cannot serve, and never for a reason that verifying a source
 * would not fix. Both mistakes are expensive: a paused engine drops lossless playback on the floor,
 * and an engine left "ready" spends a whole sweep budget before the track fails on a device where
 * YouTube could have played it at once.
 */
class SpotiFLACAvailabilityTest {

    @Test
    fun `a source with a valid session keeps the engine ready`() {
        val status = SpotiFLACAvailability.of(
            enabled = true,
            verifiedSources = 1,
            sourcesNeedingCheck = 3,
        )
        assertEquals(SpotiFLACEngineState.READY, status.state)
        assertTrue(status.usable)
        assertEquals("1 verified · 3 still need a check", status.detail)
    }

    @Test
    fun `sources still needing a check do not pause an engine that has nothing to verify`() {
        // SoundCloud and the YouTube Music provider sign in with their own service: they have no
        // session to lose, so they must never be the reason lossless playback is switched off.
        val status = SpotiFLACAvailability.of(
            enabled = true,
            verifiedSources = 0,
            sourcesNeedingCheck = 0,
        )
        assertEquals(SpotiFLACEngineState.READY, status.state)
        assertTrue(status.usable)
    }

    @Test
    fun `every session-bearing source unverified pauses the engine`() {
        val status = SpotiFLACAvailability.of(
            enabled = true,
            verifiedSources = 0,
            sourcesNeedingCheck = 4,
        )
        assertEquals(SpotiFLACEngineState.NEEDS_VERIFICATION, status.state)
        assertFalse(status.usable)
        assertEquals("Paused — no source has a valid session", status.headline)
        assertTrue(status.detail.contains("4 sources need a check"))
    }

    @Test
    fun `one source needing a check is described in the singular`() {
        val status = SpotiFLACAvailability.of(
            enabled = true,
            verifiedSources = 0,
            sourcesNeedingCheck = 1,
        )
        assertTrue(status.detail.startsWith("1 source needs a check"))
    }

    @Test
    fun `the user's own switch beats every session state`() {
        val status = SpotiFLACAvailability.of(
            enabled = false,
            verifiedSources = 4,
            sourcesNeedingCheck = 0,
            blockedRemainingMs = 60_000,
        )
        assertEquals(SpotiFLACEngineState.DISABLED, status.state)
        assertFalse(status.usable)
    }

    @Test
    fun `a gateway block pauses the engine and is not reported as a missing session`() {
        // Verifying a source cannot lift a client-wide block, so sending the user to a Cloudflare
        // check for it would be a dead end dressed up as a fix.
        val status = SpotiFLACAvailability.of(
            enabled = true,
            verifiedSources = 2,
            sourcesNeedingCheck = 0,
            blockedRemainingMs = 20_700_000L,
            blockedReason = "gateway is refusing this connection (HTTP 429)",
        )
        assertEquals(SpotiFLACEngineState.GATEWAY_BLOCKED, status.state)
        assertFalse(status.usable)
        // The gateway's own wording is not pasted in whole: it restates the headline and carries no
        // punctuation, which read as one run-on sentence on the device.
        assertEquals(
            "The gateway answered HTTP 429. Playback uses YouTube until it lifts (5h 45m left).",
            status.detail,
        )
    }

    @Test
    fun `an expired block does not pause the engine`() {
        val status = SpotiFLACAvailability.of(
            enabled = true,
            verifiedSources = 1,
            sourcesNeedingCheck = 0,
            blockedRemainingMs = 0L,
        )
        assertEquals(SpotiFLACEngineState.READY, status.state)
        assertTrue(status.usable)
    }

    @Test
    fun `a block with no stored reason still says what it is`() {
        val status = SpotiFLACAvailability.of(
            enabled = true,
            verifiedSources = 1,
            sourcesNeedingCheck = 0,
            blockedRemainingMs = 90_000L,
        )
        assertEquals(
            "SpotiFLAC's gateway is rate-limiting this device. " +
                "Playback uses YouTube until it lifts (2m left).",
            status.detail,
        )
    }

    @Test
    fun `waits are labelled in hours, minutes or seconds`() {
        assertEquals("5h 45m", SpotiFLACAvailability.remainingLabel(20_700_000L))
        assertEquals("2h", SpotiFLACAvailability.remainingLabel(7_200_000L))
        assertEquals("12m", SpotiFLACAvailability.remainingLabel(720_000L))
        assertEquals("40s", SpotiFLACAvailability.remainingLabel(39_500L))
        assertEquals("0s", SpotiFLACAvailability.remainingLabel(-5L))
    }
}
