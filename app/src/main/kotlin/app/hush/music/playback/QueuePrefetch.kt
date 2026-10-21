/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import app.hush.music.utils.PlaybackDownloadProgress

/**
 * What the lookahead is working on, in the shape the queue screen reads it.
 *
 * The player's own progress signal is deliberately one item wide: [app.hush.music.utils.PlaybackDownloadProgress]
 * is published for the track that is playing, because the transport and the codec row are about that
 * track. The lookahead is the one thing in the app that fetches *other* tracks, so its progress has
 * nowhere to go - which is why it used to be dropped for anyone looking at the screen, and why a
 * queue being warmed ahead of the listener looked exactly like a queue doing nothing.
 *
 * Two fields rather than one, because they answer different questions and are known at different
 * times: [planned] is known the moment a lookahead starts (and shrinks as each track lands), while
 * [active] only exists once the runtime is fetching one of them. A single "currently fetching" value
 * could not show the three tracks behind it, and a plan with no progress could not show *which* one
 * is moving.
 */
data class LookaheadPrefetch(
    /** Media ids this lookahead intends to fetch, in the order it will reach them. */
    val planned: List<String> = emptyList(),
    /** The planned track being fetched right now, with whatever the runtime has measured for it. */
    val active: PlaybackDownloadProgress? = null,
) {
    companion object {
        /** Nothing planned and nothing running: where every screen starts and ends. */
        val None = LookaheadPrefetch()
    }
}

/**
 * What one queue row says about the lookahead's work on the track it draws.
 *
 * Kept as its own small vocabulary rather than as numbers in the composable, because this is the
 * rule that decides *which row says what* - and the one thing it must never do (describe the track
 * that is playing) is exactly the mistake that made a prefetch look like the player fetching the
 * song already in the user's ears. A decision buried in Compose can only be checked by looking at a
 * phone; here it is pinned by `QueuePrefetchTest`.
 */
sealed interface QueuePrefetchState {
    /** Planned, not reached yet: the rows behind the one being fetched. */
    data object Queued : QueuePrefetchState

    /**
     * Being fetched, before any byte has moved.
     *
     * The provider stages (`checking_session`, `resolving_metadata`) have no fraction to report -
     * the same reason the player says "Fetching from <source>…" rather than "0%" - so the row says
     * that it is being worked on instead of drawing an empty ring.
     */
    data object Fetching : QueuePrefetchState

    /** Being fetched, with the fraction the runtime measured. */
    data class Downloading(
        val percent: Int,
    ) : QueuePrefetchState
}

/**
 * What [mediaId]'s row should say, or null for a row that says nothing.
 *
 * The playing track is refused before anything else, because that is the whole point: the row for
 * the song in the user's ears already has the transport, the fraction ring and the codec row
 * describing it, and a prefetch running *behind* it must not appear there. Everything else is
 * "is this track part of the lookahead's work, and how far along is it".
 *
 * A finished fetch says nothing. Percent 100 arrives before the lookahead has finished recording the
 * track as done, so a row that read it would claim a download was running over a track that is
 * already on the device.
 */
fun queuePrefetchState(
    mediaId: String?,
    currentMediaId: String?,
    prefetch: LookaheadPrefetch?,
): QueuePrefetchState? {
    val id = mediaId?.takeIf { it.isNotBlank() } ?: return null
    if (currentMediaId != null && id == currentMediaId) return null

    val active = prefetch?.active?.takeIf { it.mediaId == id }
    if (active != null) {
        // A finished transfer - and a cache hit, which the runtime reports as a completed entry - is
        // not a fetch in progress, so the row says nothing. The plan is only updated once the
        // lookahead has recorded the track, which is a moment later; falling through to it here
        // would put "Queued" over a track that is already on the device.
        if (active.fromCache || active.percent >= 100) return null
        return if (active.percent >= 1) {
            QueuePrefetchState.Downloading(active.percent)
        } else {
            QueuePrefetchState.Fetching
        }
    }

    return if (prefetch?.planned?.contains(id) == true) QueuePrefetchState.Queued else null
}
