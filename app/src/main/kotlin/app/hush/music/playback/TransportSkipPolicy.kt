/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

/**
 * What a next/previous press should do, given the player's timeline.
 *
 * Skip buttons used to call `seekToNext()` unconditionally, and on a player with no
 * timeline (a restart whose queue has not been restored yet) or with a single track
 * that call is a *successful no-op*: nothing moves, no error, no feedback. That reads
 * as a dead button — the "next/previous do nothing until I pick a playlist" report.
 *
 * Keeping the decision here makes the two useful recoveries explicit and testable:
 * restore the persisted queue, or extend the queue when there is nothing ahead.
 */
object TransportSkipPolicy {
    enum class Action {
        /** The player can move on its own. */
        SKIP,

        /** No timeline at all: recover the persisted queue first. */
        RECOVER_QUEUE,

        /** At the end of a finite queue: grow it so "next" has a destination. */
        EXTEND_QUEUE,
    }

    /**
     * @param mediaItemCount items currently on the timeline.
     * @param hasNext whether the player reports a following item.
     * @param repeatEnabled whether repeat (all) is on, in which case the player wraps
     *   around and a skip is always meaningful.
     */
    fun nextAction(
        mediaItemCount: Int,
        hasNext: Boolean,
        repeatEnabled: Boolean,
    ): Action =
        when {
            mediaItemCount <= 0 -> Action.RECOVER_QUEUE
            hasNext -> Action.SKIP
            repeatEnabled -> Action.SKIP
            else -> Action.EXTEND_QUEUE
        }

    /** Previous never needs to grow the queue: at the start it restarts the track. */
    fun previousAction(mediaItemCount: Int): Action =
        if (mediaItemCount <= 0) Action.RECOVER_QUEUE else Action.SKIP
}
