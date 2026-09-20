package app.hush.music.waze

/**
 * Decides when the bridge has to stop advertising playback because Hush stopped talking.
 *
 * The bridge is the player Waze sees, so it has to answer one question honestly: is music
 * playing right now? It learns that only from Hush's snapshots, and Hush only sends them while
 * it is driving playback (about once a second) or when something changes.
 *
 * Measured on a device: with a track playing, the bridge advertised `PLAYING`; Hush's process
 * was then killed, and the bridge kept advertising `PLAYING` — frozen at the same position —
 * indefinitely. Waze derives its transport button from that state, so it drew a *pause* button
 * for music that was not playing. A user tapping it sent a pause to a player that was already
 * silent, so nothing happened, and the only way to start music from Waze again was to start it
 * from Hush first and let the snapshots resume. That is the reported "the first play from Waze
 * does nothing after I relaunch Hush".
 *
 * The silence itself is the signal: Hush publishes once a second while playing, so a gap of
 * several seconds while we claim to be playing means the player is gone (relaunched, killed by
 * the head unit, or crashed), not that it briefly stopped talking.
 *
 * Framework-free so it can be unit-tested: callers pass plain numbers and booleans.
 */
object WazePlaybackFreshnessPolicy {

    /**
     * How long Hush may stay silent before a claimed playback is treated as gone.
     *
     * Comfortably longer than the roughly one-second cadence Hush publishes while playing, and
     * short enough that a user who taps Waze straight after a restart does not hit a stale
     * button. Deliberately not larger: the cost of being wrong here is one pause→play flicker,
     * while the cost of being slow is the dead-button bug this exists to remove.
     */
    const val SNAPSHOT_SILENCE_MS = 6_000L

    /** How often the bridge checks the silence, while it is advertising playback. */
    const val CHECK_INTERVAL_MS = 2_000L

    /**
     * True when a session that advertises playback has been silent for the grace period.
     *
     * @param msSinceLastSnapshot milliseconds since the last snapshot was actually applied.
     * @param advertisingPlayback whether the session currently claims playing or buffering.
     */
    fun isAbandoned(msSinceLastSnapshot: Long, advertisingPlayback: Boolean): Boolean =
        advertisingPlayback && msSinceLastSnapshot >= SNAPSHOT_SILENCE_MS

    /**
     * How old the last snapshot may be before a transport pause is no longer forwarded blind.
     *
     * A little over the publishing cadence, so an ordinary tap during real playback - where Hush
     * has spoken within the last second - is forwarded with no waiting at all, and only a tap that
     * arrives with nothing recent behind it costs a probe.
     */
    const val PAUSE_PROBE_SILENCE_MS = 1_200L

    /**
     * How long the probe may wait for the player to speak before the silence is read as absence.
     *
     * The one second of publishing cadence plus slack: this is the delay a user pays on a tap made
     * when the bridge has nothing recent to go on, so it is deliberately short - and paying it is
     * what stops the tap from being forwarded to a player that is no longer there.
     */
    const val PAUSE_PROBE_TIMEOUT_MS = 1_400L

    /**
     * True when a transport pause must be confirmed by the player before it can be forwarded.
     *
     * The watchdog withdraws a stale claim after [SNAPSHOT_SILENCE_MS], which leaves the window
     * between Hush going quiet and the next check - and a tap landing inside that window is
     * answered from a button drawn out of the stale claim. Measured on a device: Hush was killed
     * while playing, Waze was opened a couple of seconds later and its (pause) button was tapped,
     * and the bridge forwarded `pause` to a player that was not running, so Hush was started up
     * only to be told to pause: the panel stayed silent and the report "tapping play in Waze does
     * nothing" followed.
     *
     * No staleness threshold can close that window on its own, because a couple of seconds of
     * silence is also what a perfectly healthy player looks like between publishes. So the bridge
     * asks instead of guessing: a pause that arrives without recent evidence makes the player speak
     * first (see [commandAfterPauseProbe]).
     */
    fun pauseNeedsProbe(msSinceLastSnapshot: Long): Boolean =
        msSinceLastSnapshot >= PAUSE_PROBE_SILENCE_MS

    /**
     * What to forward for a transport pause once the probe has been answered, or has timed out.
     *
     * A transport button is a toggle, and the honest reading of it is not the verb it was drawn as
     * but the state it was drawn from. A player that answers *while playing* backs that state, so
     * the tap means what it says. A player that says nothing is the stale case - the claim came
     * from a player that is gone - and then the toggle's only outcome that changes anything is
     * starting playback.
     */
    fun commandAfterPauseProbe(playerAnsweredWhilePlaying: Boolean): TransportPause =
        if (playerAnsweredWhilePlaying) TransportPause.PAUSE else TransportPause.PLAY
}

/** What a transport pause from Waze should be forwarded to Hush as. */
enum class TransportPause {
    /** The bridge's claim of playback was not backed by the player, so the toggle means "start". */
    PLAY,

    /** The player is there and playing, so the toggle means what it says. */
    PAUSE,
}
