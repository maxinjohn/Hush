/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import androidx.media3.common.PlaybackException

/**
 * No engine that is currently enabled can serve this track.
 *
 * This is deliberately not an IO failure. Retrying cannot change the outcome, because the
 * reason is a setting rather than a transient condition: YouTube is switched off, the
 * YouTube fallback is turned off, or every SpotiFLAC source declined the track. Without a
 * distinct type the player spent its whole retry budget re-running a provider sweep (up to
 * the fallback timeout each time) and the UI reported the result as a generic
 * "Unknown error", which reads like a crash instead of an instruction.
 */
class NoPlayableSourceException(
    message: String,
    /**
     * When an earlier attempt already established this result, if this failure *is* that
     * remembered answer rather than a fresh one. The player says so, because "we already
     * checked" and "we just checked and it failed" deserve different wording: only the
     * second one invites the user to wait and try again.
     */
    val rememberedMissAtMs: Long? = null,
) : PlaybackException(message, null, PlaybackException.ERROR_CODE_REMOTE_ERROR)

/**
 * Finds a [NoPlayableSourceException] anywhere in a cause chain.
 *
 * Media3 wraps whatever the data source throws, so the type is never at the top.
 */
fun Throwable.findNoPlayableSourceException(): NoPlayableSourceException? {
    var current: Throwable? = this
    while (current != null) {
        if (current is NoPlayableSourceException) return current
        current = current.cause
    }
    return null
}
