/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import android.view.KeyEvent
import androidx.media3.common.MediaItem
import app.hush.music.db.MusicDatabase
import app.hush.music.db.entities.EventWithSong
import app.hush.music.extensions.toMediaItem
import kotlinx.coroutines.flow.first
import kotlin.coroutines.cancellation.CancellationException

/**
 * The commands a cold-start recovery may answer by starting music, and nothing else.
 *
 * An empty player is a legitimate state for every transport command: the car shim sends
 * `pause` and `stop` before anything is loaded, and a stale `sync` arrives on every connect.
 * Treating those as "the user wants music" would make pausing a device that is already silent
 * start playing, so the distinction is explicit rather than implied by "a command arrived".
 */
internal object TransportRecoveryPolicy {

    fun requestsPlayback(command: String?): Boolean =
        command?.trim()?.lowercase() in PLAYBACK_COMMANDS

    /**
     * The same question for a hardware or headset key.
     *
     * Key codes rather than command names because the two arrive by different routes: a car's
     * head unit sends a named command, a headset or a steering-wheel button sends a mediasession
     * key event. Both must be judged by the same rule - is this press asking for music?
     */
    fun requestsPlaybackForKeyCode(keyCode: Int): Boolean = keyCode in PLAYBACK_KEY_CODES

    private val PLAYBACK_COMMANDS =
        setOf(
            "play",
            "next",
            "previous",
            "play_pause",
            "skip_to_queue_item",
        )

    private val PLAYBACK_KEY_CODES =
        setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
            KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
            KeyEvent.KEYCODE_HEADSETHOOK,
        )
}

/**
 * Rebuilds a queue out of what the device still knows, for a cold start with nothing persisted.
 *
 * Pure so the ordering can be pinned down: this is the difference between resuming the session
 * the user left and playing their history in a surprising order. History arrives newest-first
 * (it is ordered by insert order descending), and that order is kept - the track they stopped on
 * is the one they expect to hear, and the ones behind it are what "next" should walk through.
 */
internal object HistoryRecoveryQueue {

    /** How much of the recent session a cold-start recovery rebuilds as a queue. */
    const val DEFAULT_LIMIT = 50

    fun mediaItems(
        events: List<EventWithSong>,
        blockedArtistIds: Set<String>,
        limit: Int,
    ): List<MediaItem> {
        if (limit <= 0) return emptyList()
        val seen = HashSet<String>(events.size.coerceAtMost(limit * 4))
        val items = ArrayList<MediaItem>(limit)
        for (event in events) {
            val song = event.song
            // Every play is an event, so the same track appears many times; the queue keeps its
            // most recent appearance, which newest-first means is the one encountered first.
            if (!seen.add(song.id)) continue
            if (song.artists.any { it.id in blockedArtistIds }) continue
            items.add(song.toMediaItem())
            if (items.size == limit) break
        }
        return items
    }
}

/**
 * The recently played items a cold start can rebuild a queue from, or empty when there are none.
 *
 * Every *failure* is swallowed into "nothing to recover": a cold-start fallback that throws would
 * turn a dead transport button into a crash, and there is no recovery it could perform anyway.
 * Cancellation is not a failure and is re-thrown, so a cancelled service scope does not go on to
 * issue the database queries behind this one.
 */
internal suspend fun MusicDatabase.historyRecoveryItems(limit: Int = HistoryRecoveryQueue.DEFAULT_LIMIT): List<MediaItem> {
    val events =
        runCatching { events().first() }.getOrElse { error ->
            if (error is CancellationException) throw error
            emptyList()
        }
    if (events.isEmpty()) return emptyList()
    val blockedArtistIds =
        runCatching { getBlockedArtistIds().toSet() }.getOrElse { error ->
            if (error is CancellationException) throw error
            emptySet()
        }
    return HistoryRecoveryQueue.mediaItems(
        events = events,
        blockedArtistIds = blockedArtistIds,
        limit = limit,
    )
}
