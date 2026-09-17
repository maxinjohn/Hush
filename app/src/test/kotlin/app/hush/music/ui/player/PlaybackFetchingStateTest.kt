/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.player

import app.hush.music.utils.PlaybackDownloadProgress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the distinction between "a source is still being tried" and "bytes are downloading".
 *
 * On device an uncached track sat at 0:00 for 18 seconds - and a whole sweep can spend 45s
 * before falling back to YouTube - while the player's source row showed either a stale
 * YouTube format from the previous track or nothing at all. The resolving window is the
 * long one, so it is the one that has to be described honestly.
 */
class PlaybackFetchingStateTest {

    /** The runtime reports a sweep as progress with no bytes behind it. */
    @Test
    fun `a source sweep with no bytes counts as resolving`() {
        val sweep =
            PlaybackDownloadProgress(
                mediaId = "abc",
                sourceId = "qobuz-web",
                percent = 0,
                bytesReceived = 0L,
                bytesTotal = 0L,
                stage = "resolving_stream",
                status = "preparing",
            )
        assertTrue(sweep.isResolvingSource)
    }

    /** Once bytes are moving, a percentage is the true statement instead. */
    @Test
    fun `a download with bytes does not count as resolving`() {
        val downloading =
            PlaybackDownloadProgress(
                mediaId = "abc",
                sourceId = "tidal-web",
                percent = 42,
                bytesReceived = 12_000_000L,
                bytesTotal = 30_000_000L,
                stage = "downloading",
                status = "downloading",
            )
        assertFalse(downloading.isResolvingSource)
    }

    /** A known size with no progress yet is a download that has started, not a sweep. */
    @Test
    fun `a known total size is not a sweep`() {
        val starting =
            PlaybackDownloadProgress(
                mediaId = "abc",
                sourceId = "amazon",
                percent = 0,
                bytesTotal = 30_000_000L,
            )
        assertFalse(starting.isResolvingSource)
    }

    /** A cache hit is never a fetch, whatever else it carries. */
    @Test
    fun `a cache hit is not resolving`() {
        val cached =
            PlaybackDownloadProgress(
                mediaId = "abc",
                sourceId = "deezer",
                percent = 100,
                bytesReceived = 30_000_000L,
                bytesTotal = 30_000_000L,
                fromCache = true,
            )
        assertFalse(cached.isResolvingSource)
    }

    /** A finished fetch must not read as still working. */
    @Test
    fun `a completed fetch is not resolving`() {
        val completed =
            PlaybackDownloadProgress(
                mediaId = "abc",
                sourceId = "tidal-web",
                percent = 100,
                bytesReceived = 30_000_000L,
                bytesTotal = 30_000_000L,
                status = "completed",
            )
        assertFalse(completed.isResolvingSource)
    }
}
