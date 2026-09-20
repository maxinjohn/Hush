/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import app.hush.music.utils.YTPlayerUtils

/**
 * The cause in this chain that says YouTube wants the listener to confirm playback, or null
 * when the failure has nothing to do with a confirmation.
 *
 * Two deliberate marker types count, because they are the same demand reached two ways:
 * [YTPlayerUtils.LoginRequiredForPlaybackException] when every stream client answered
 * `LOGIN_REQUIRED` / "Please sign in", and
 * [YTPlayerUtils.InvalidPlaybackLoginContextException] when the stored login context was
 * rejected. Both are `IllegalStateException`s raised by the resolver, not conditions that
 * pass, so retrying cannot change the answer - which is what the player has to know.
 *
 * It has to walk the chain because media3 wraps whatever the data source throws, and the
 * wrapper carries `ERROR_CODE_IO_UNSPECIFIED` - the very code a dropped connection produces.
 * Without this, a demand for a confirmation looked like a flaky stream: the player spent its
 * retry budget clearing caches and re-resolving with rotated stream clients (each round
 * re-running the whole client sweep, and logging the same demand again), and the panel naming
 * the one action that works arrived late or was replaced by an auto-skip.
 */
fun Throwable.playbackConfirmationRequiredCause(): Throwable? {
    var current: Throwable? = this
    while (current != null) {
        if (current is YTPlayerUtils.LoginRequiredForPlaybackException ||
            current is YTPlayerUtils.InvalidPlaybackLoginContextException
        ) {
            return current
        }
        current = current.cause
    }
    return null
}
