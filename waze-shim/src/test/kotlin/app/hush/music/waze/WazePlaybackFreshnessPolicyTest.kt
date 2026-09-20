package app.hush.music.waze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rule that stops the bridge advertising music that is not playing.
 *
 * The failure this guards against was measured on a device: Hush was killed while playing, the
 * bridge kept advertising `PLAYING` forever, and Waze therefore drew a pause button for silence,
 * so the first play after a relaunch did nothing.
 */
class WazePlaybackFreshnessPolicyTest {

    @Test
    fun `a brief silence is not treated as the player being gone`() {
        // Hush publishes about once a second while playing, so an ordinary gap must not flip the
        // button under the user - that would be a pause/play flicker mid-track.
        assertFalse(WazePlaybackFreshnessPolicy.isAbandoned(1_000L, advertisingPlayback = true))
        assertFalse(WazePlaybackFreshnessPolicy.isAbandoned(4_999L, advertisingPlayback = true))
    }

    @Test
    fun `silence past the grace period drops a claimed playback`() {
        assertTrue(
            WazePlaybackFreshnessPolicy.isAbandoned(
                WazePlaybackFreshnessPolicy.SNAPSHOT_SILENCE_MS,
                advertisingPlayback = true,
            ),
        )
        assertTrue(WazePlaybackFreshnessPolicy.isAbandoned(60_000L, advertisingPlayback = true))
    }

    @Test
    fun `a session that is not claiming playback is left alone`() {
        // Nothing is advertised, so there is nothing false to withdraw: a paused bridge with a
        // silent Hush is the correct, expected state and must not be rewritten.
        assertFalse(WazePlaybackFreshnessPolicy.isAbandoned(60_000L, advertisingPlayback = false))
        assertFalse(WazePlaybackFreshnessPolicy.isAbandoned(0L, advertisingPlayback = false))
    }

    @Test
    fun `a pause with recent evidence behind it is forwarded without waiting`() {
        // Hush publishes about once a second while playing, so a tap during real playback always
        // has a fresh snapshot: it must be obeyed immediately, with no probe and no delay.
        assertFalse(WazePlaybackFreshnessPolicy.pauseNeedsProbe(0L))
        assertFalse(WazePlaybackFreshnessPolicy.pauseNeedsProbe(300L))
        assertFalse(
            WazePlaybackFreshnessPolicy.pauseNeedsProbe(
                WazePlaybackFreshnessPolicy.PAUSE_PROBE_SILENCE_MS - 1,
            ),
        )
    }

    @Test
    fun `a pause with nothing recent behind it is probed`() {
        // Measured on a device: Hush was killed while playing and Waze's (pause) button was tapped
        // a couple of seconds later. A couple of seconds of silence is also what a healthy player
        // looks like between publishes, so this is the case that cannot be decided by a threshold
        // - the player has to be asked.
        assertTrue(
            WazePlaybackFreshnessPolicy.pauseNeedsProbe(
                WazePlaybackFreshnessPolicy.PAUSE_PROBE_SILENCE_MS,
            ),
        )
        assertTrue(WazePlaybackFreshnessPolicy.pauseNeedsProbe(3_000L))
        assertTrue(WazePlaybackFreshnessPolicy.pauseNeedsProbe(60_000L))
        assertTrue(WazePlaybackFreshnessPolicy.pauseNeedsProbe(Long.MAX_VALUE))
    }

    @Test
    fun `a probed pause starts playback when the player did not answer`() {
        // The claim the button was drawn from came from a player that is gone, so the toggle's only
        // outcome that changes anything is starting playback - the alternative is starting Hush up
        // only to tell it to pause, which is the reported dead button.
        assertEquals(
            TransportPause.PLAY,
            WazePlaybackFreshnessPolicy.commandAfterPauseProbe(playerAnsweredWhilePlaying = false),
        )
    }

    @Test
    fun `a probed pause is obeyed when the player answered while playing`() {
        // The music is real and the user asked to stop it, so the tap means what it says.
        assertEquals(
            TransportPause.PAUSE,
            WazePlaybackFreshnessPolicy.commandAfterPauseProbe(playerAnsweredWhilePlaying = true),
        )
    }

    @Test
    fun `the check runs often enough to clear a stale button quickly`() {
        // The watchdog only acts on a check, so the interval has to be well inside the grace
        // period; otherwise the stale pause button survives for another poll cycle after the
        // grace has already elapsed.
        assertTrue(
            WazePlaybackFreshnessPolicy.CHECK_INTERVAL_MS <
                WazePlaybackFreshnessPolicy.SNAPSHOT_SILENCE_MS,
        )
        assertTrue(WazePlaybackFreshnessPolicy.CHECK_INTERVAL_MS <= 2_500L)
    }
}
