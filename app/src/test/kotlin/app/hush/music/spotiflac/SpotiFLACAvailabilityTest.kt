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

    /** A switch list with one source that can actually fetch a file. */
    private val oneAudioSource = 1

    @Test
    fun `a source with a valid session keeps the engine ready`() {
        val status = SpotiFLACAvailability.of(
            enabled = true,
            verifiedSources = 1,
            sourcesNeedingCheck = 3,
            audioSources = oneAudioSource,
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
            audioSources = oneAudioSource,
        )
        assertEquals(SpotiFLACEngineState.READY, status.state)
        assertTrue(status.usable)
    }

    /**
     * The report this state exists for: everything switched on is a metadata provider.
     *
     * A metadata source signs in with its own service, so it passes the "nothing to verify" rule -
     * which is how the engine read `Active` and routed a track into a sweep whose every candidate
     * was dropped as unable to download. Its detail must say what would fix it, because verifying a
     * source cannot.
     */
    @Test
    fun `an engine with only metadata providers switched on is paused`() {
        val status = SpotiFLACAvailability.of(
            enabled = true,
            verifiedSources = 0,
            sourcesNeedingCheck = 0,
            audioSources = 0,
        )
        assertEquals(SpotiFLACEngineState.NO_AUDIO_SOURCE, status.state)
        assertFalse(status.usable)
        assertEquals("Paused — no audio source", status.headline)
        assertTrue(status.detail.contains("Switch on a source that serves audio"))
    }

    /**
     * A switch list read before the registry has landed is not evidence of anything, so it must not
     * pause the engine: the sweep can still fall back to the installed packages, and dropping
     * lossless playback on every cold start is the more expensive mistake.
     */
    @Test
    fun `an unread source list leaves the engine ready rather than pausing it`() {
        val status = SpotiFLACAvailability.of(
            enabled = true,
            verifiedSources = 0,
            sourcesNeedingCheck = 0,
            audioSources = null,
        )
        assertEquals(SpotiFLACEngineState.READY, status.state)
        assertTrue(status.usable)
    }

    /** Nothing switched on at all reads the same way, and says what to do about it. */
    @Test
    fun `an install with no source switched on is paused`() {
        val status = SpotiFLACAvailability.of(
            enabled = true,
            verifiedSources = 0,
            sourcesNeedingCheck = 0,
            audioSources = 0,
        )
        assertEquals(SpotiFLACEngineState.NO_AUDIO_SOURCE, status.state)
        assertFalse(status.usable)
    }

    @Test
    fun `every session-bearing source unverified pauses the engine`() {
        val status = SpotiFLACAvailability.of(
            enabled = true,
            verifiedSources = 0,
            sourcesNeedingCheck = 4,
            audioSources = oneAudioSource,
        )
        assertEquals(SpotiFLACEngineState.NEEDS_VERIFICATION, status.state)
        assertFalse(status.usable)
        assertEquals("Paused — no source has a valid session", status.headline)
        assertTrue(status.detail.contains("4 sources need a check"))
    }

    /**
     * The regression: one lapsed session used to pause the whole engine, taking every source that
     * needs no check out of the walk with it. Measured on the reporting device with eight sources
     * switched on - `tidal-web` unverified, the credential-less modules and SoundCloud enabled and
     * working - every track resolved through YouTube while the sources that could serve it were
     * switched on and never asked.
     */
    @Test
    fun `a source that needs no check keeps the engine in play beside unverified ones`() {
        val status = SpotiFLACAvailability.of(
            enabled = true,
            verifiedSources = 0,
            sourcesNeedingCheck = 3,
            audioSources = oneAudioSource + 2,
            sourcesWithoutSession = 2,
        )
        assertEquals(SpotiFLACEngineState.READY, status.state)
        assertTrue(status.usable)
        assertEquals("Active", status.headline)
        // The line must not claim there is nothing outstanding: these are sources the user can still
        // act on, and the ones beside them are why the track plays in the meantime.
        assertEquals("3 sources need a check; the rest need none and still play.", status.detail)
    }

    /** Singular, because one row reading "1 sources need a check" is how this stayed unnoticed. */
    @Test
    fun `one session-free source and one needing a check reads in the singular`() {
        val status = SpotiFLACAvailability.of(
            enabled = true,
            verifiedSources = 0,
            sourcesNeedingCheck = 1,
            audioSources = 2,
            sourcesWithoutSession = 1,
        )
        assertEquals("1 source needs a check; the rest need none and still play.", status.detail)
    }

    /**
     * And the state still pauses when the session-free sources are not there to be asked: the rule is
     * about what is left to try, not about the count of unverified sources on its own.
     */
    @Test
    fun `unverified sources with nothing session-free left still pause the engine`() {
        val status = SpotiFLACAvailability.of(
            enabled = true,
            verifiedSources = 0,
            sourcesNeedingCheck = 4,
            audioSources = 4,
            sourcesWithoutSession = 0,
        )
        assertEquals(SpotiFLACEngineState.NEEDS_VERIFICATION, status.state)
        assertFalse(status.usable)
    }

    @Test
    fun `one source needing a check is described in the singular`() {
        val status = SpotiFLACAvailability.of(
            enabled = true,
            verifiedSources = 0,
            sourcesNeedingCheck = 1,
            audioSources = oneAudioSource,
        )
        assertTrue(status.detail.startsWith("1 source needs a check"))
    }

    /**
     * A verified source beside unverified ones says both, whatever the session-free count is: the
     * counts are independent facts and the line already had a shape for that.
     */
    @Test
    fun `a verified source beside needing ones keeps the existing wording`() {
        val status = SpotiFLACAvailability.of(
            enabled = true,
            verifiedSources = 2,
            sourcesNeedingCheck = 1,
            audioSources = 4,
            sourcesWithoutSession = 1,
        )
        assertEquals("2 verified · 1 still need a check", status.detail)
    }

    @Test
    fun `the user's own switch beats every session state`() {
        val status = SpotiFLACAvailability.of(
            enabled = false,
            verifiedSources = 4,
            sourcesNeedingCheck = 0,
            audioSources = oneAudioSource,
            blockedRemainingMs = 60_000,
        )
        assertEquals(SpotiFLACEngineState.DISABLED, status.state)
        assertFalse(status.usable)
    }

    @Test
    fun `low data mode pauses the engine even with verified sources and the switch on`() {
        // Every SpotiFLAC play is a whole lossless file, so low data mode outranks sessions:
        // the engine is not consulted and the other source plays instead.
        val status = SpotiFLACAvailability.of(
            enabled = true,
            verifiedSources = 4,
            sourcesNeedingCheck = 0,
            audioSources = oneAudioSource,
            lowDataActive = true,
        )
        assertEquals(SpotiFLACEngineState.LOW_DATA, status.state)
        assertFalse(status.usable)
        assertEquals("Off — low data mode", status.headline)
        assertTrue(status.detail.contains("Turn low data mode off"))
    }

    @Test
    fun `low data mode is named rather than a gateway block or a missing session`() {
        // Both of those are reasons verifying could fix; low data mode is not, so it has to be
        // the one on screen or the user is sent to solve a check that changes nothing.
        val status = SpotiFLACAvailability.of(
            enabled = true,
            verifiedSources = 0,
            sourcesNeedingCheck = 4,
            audioSources = oneAudioSource,
            blockedRemainingMs = 60_000,
            blockedReason = "gateway is refusing this connection (HTTP 429)",
            lowDataActive = true,
        )
        assertEquals(SpotiFLACEngineState.LOW_DATA, status.state)
        assertFalse(status.usable)
    }

    @Test
    fun `a switched-off engine is still off under low data mode, for its own reason`() {
        val status = SpotiFLACAvailability.of(
            enabled = false,
            verifiedSources = 2,
            sourcesNeedingCheck = 0,
            audioSources = oneAudioSource,
            lowDataActive = true,
        )
        assertEquals(SpotiFLACEngineState.DISABLED, status.state)
        assertEquals("Off", status.headline)
    }

    @Test
    fun `low data mode lapses and the engine is ready again without touching the switch`() {
        val status = SpotiFLACAvailability.of(
            enabled = true,
            verifiedSources = 2,
            sourcesNeedingCheck = 0,
            audioSources = oneAudioSource,
            lowDataActive = false,
        )
        assertEquals(SpotiFLACEngineState.READY, status.state)
        assertTrue(status.usable)
    }

    @Test
    fun `a gateway block pauses the engine and is not reported as a missing session`() {
        // Verifying a source cannot lift a client-wide block, so sending the user to a Cloudflare
        // check for it would be a dead end dressed up as a fix.
        val status = SpotiFLACAvailability.of(
            enabled = true,
            verifiedSources = 2,
            sourcesNeedingCheck = 0,
            audioSources = oneAudioSource,
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
            audioSources = oneAudioSource,
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
            audioSources = oneAudioSource,
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
