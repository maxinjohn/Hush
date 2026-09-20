/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.player

import app.hush.music.utils.PlaybackDownloadProgress
import app.hush.music.utils.fetchFraction
import app.hush.music.utils.isFetchingTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    //
    // The transport's loading state. A SpotiFLAC track is downloaded before media3 is handed
    // anything, so `STATE_BUFFERING` is false for that whole window: the play/pause button used
    // to sit there showing a play glyph for a track that was audibly still being fetched.
    //

    /** The reported bug: a track being fetched for playback has to read as working. */
    @Test
    fun `the track being fetched for playback counts as loading`() {
        val resolving =
            PlaybackDownloadProgress(
                mediaId = "track-1",
                sourceId = "deezer",
                percent = 0,
                stage = "resolving_stream",
                status = "preparing",
            )
        assertTrue(resolving.isFetchingTrack("track-1"))

        val downloading =
            PlaybackDownloadProgress(
                mediaId = "track-1",
                sourceId = "tidal-web",
                percent = 40,
                bytesReceived = 12_000_000L,
                bytesTotal = 30_000_000L,
            )
        assertTrue(downloading.isFetchingTrack("track-1"))
    }

    /** Prefetching the next track must not spin the button for the one that is playing. */
    @Test
    fun `a prefetch of another track is not this track's loading state`() {
        val prefetch =
            PlaybackDownloadProgress(
                mediaId = "track-2",
                sourceId = "qobuz-web",
                percent = 10,
                bytesReceived = 1_000_000L,
                bytesTotal = 30_000_000L,
            )
        assertFalse(prefetch.isFetchingTrack("track-1"))
    }

    /** Nothing to wait for: a replay off the device cache is not a fetch. */
    @Test
    fun `a cache replay is not loading`() {
        val cached =
            PlaybackDownloadProgress(
                mediaId = "track-1",
                sourceId = "deezer",
                percent = 100,
                fromCache = true,
            )
        assertFalse(cached.isFetchingTrack("track-1"))
    }

    /** A progress entry that lingers past the last byte must not pin the spinner on. */
    @Test
    fun `a finished transfer is not loading`() {
        val completed =
            PlaybackDownloadProgress(
                mediaId = "track-1",
                sourceId = "tidal-web",
                percent = 100,
                bytesReceived = 30_000_000L,
                bytesTotal = 30_000_000L,
                status = "completed",
            )
        assertFalse(completed.isFetchingTrack("track-1"))
    }

    /** No transfer at all, and an unnamed transfer, are the two remaining cases. */
    @Test
    fun `no progress is not loading, and an unnamed transfer is`() {
        val nothing: PlaybackDownloadProgress? = null
        assertFalse(nothing.isFetchingTrack("track-1"))

        // The runtime only leaves the id blank for the transfer it is running right now.
        val unnamed =
            PlaybackDownloadProgress(
                mediaId = "",
                sourceId = "amazon",
                percent = 5,
                bytesTotal = 30_000_000L,
            )
        assertTrue(unnamed.isFetchingTrack("track-1"))
    }

    //
    // The ring's fill. The transport's indicator is indeterminate, and an indeterminate indicator
    // is motion - which a device with animations turned off does not have, so it painted its first
    // frame and stayed there while a listener waited on it. A fraction needs no animation at all:
    // the arc grows because the download did.
    //

    /** Bytes moving is a number the ring can be drawn from. */
    @Test
    fun `a download reports the fraction of the ring to fill`() {
        val downloading =
            PlaybackDownloadProgress(
                mediaId = "track-1",
                sourceId = "tidal-web",
                percent = 40,
                bytesReceived = 12_000_000L,
                bytesTotal = 30_000_000L,
            )
        assertEquals(0.4f, downloading.fetchFraction("track-1", isPlaying = false)!!, 0.0001f)
    }

    /** A sweep has no bytes behind it, so it has no fraction: the ring stays indeterminate. */
    @Test
    fun `a sweep with no bytes has no fraction`() {
        val sweep =
            PlaybackDownloadProgress(
                mediaId = "track-1",
                sourceId = "qobuz-web",
                percent = 0,
                stage = "resolving_stream",
                status = "preparing",
            )
        assertNull(sweep.fetchFraction("track-1", isPlaying = false))
    }

    /** The reported bug: the ring stayed on a song that was already playing. */
    @Test
    fun `nothing is filled while the track is playing`() {
        val stale =
            PlaybackDownloadProgress(
                mediaId = "track-1",
                sourceId = "deezer",
                percent = 40,
                bytesReceived = 12_000_000L,
                bytesTotal = 30_000_000L,
            )
        assertNull(stale.fetchFraction("track-1", isPlaying = true))
    }

    /** Another track's transfer, a finished one, a cache replay and no transfer: none has a fill. */
    @Test
    fun `only a live transfer of this track has a fraction`() {
        val other =
            PlaybackDownloadProgress(mediaId = "track-2", percent = 40, bytesTotal = 30_000_000L)
        assertNull(other.fetchFraction("track-1", isPlaying = false))

        val finished =
            PlaybackDownloadProgress(
                mediaId = "track-1",
                percent = 100,
                bytesTotal = 30_000_000L,
                status = "completed",
            )
        assertNull(finished.fetchFraction("track-1", isPlaying = false))

        val cached =
            PlaybackDownloadProgress(mediaId = "track-1", percent = 55, fromCache = true)
        assertNull(cached.fetchFraction("track-1", isPlaying = false))

        val nothing: PlaybackDownloadProgress? = null
        assertNull(nothing.fetchFraction("track-1", isPlaying = false))
    }

}
