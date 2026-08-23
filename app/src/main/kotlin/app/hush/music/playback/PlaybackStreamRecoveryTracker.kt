/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

internal class PlaybackStreamRecoveryTracker {
    private var attemptedMediaId: String? = null
    private var attemptCount = 0

    /** Allow up to 4 retries per media item so stream-client rotation gets a chance. */
    private val maxAttempts = 4

    fun registerRetryAttempt(mediaId: String): Boolean {
        if (attemptedMediaId == mediaId) {
            if (attemptCount >= maxAttempts) return false
            attemptCount++
            return true
        }
        attemptedMediaId = mediaId
        attemptCount = 1
        return true
    }

    /** Current retry count for [mediaId], or 0 if no retries have been registered. */
    fun retryCountFor(mediaId: String): Int =
        if (attemptedMediaId == mediaId) attemptCount else 0

    fun onPlaybackRecovered(mediaId: String?) {
        if (mediaId != null && attemptedMediaId == mediaId) {
            attemptedMediaId = null
            attemptCount = 0
        }
    }

    fun onMediaItemChanged(currentMediaId: String?) {
        if (attemptedMediaId != currentMediaId) {
            attemptedMediaId = null
            attemptCount = 0
        }
    }
}
