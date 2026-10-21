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
        assertEquals(0.4f, downloading.fetchFraction("track-1", isAudiblyPlaying = false)!!, 0.0001f)
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
        assertNull(sweep.fetchFraction("track-1", isAudiblyPlaying = false))
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
        assertNull(stale.fetchFraction("track-1", isAudiblyPlaying = true))
    }

    /** Another track's transfer, a finished one, a cache replay and no transfer: none has a fill. */
    @Test
    fun `only a live transfer of this track has a fraction`() {
        val other =
            PlaybackDownloadProgress(mediaId = "track-2", percent = 40, bytesTotal = 30_000_000L)
        assertNull(other.fetchFraction("track-1", isAudiblyPlaying = false))

        val finished =
            PlaybackDownloadProgress(
                mediaId = "track-1",
                percent = 100,
                bytesTotal = 30_000_000L,
                status = "completed",
            )
        assertNull(finished.fetchFraction("track-1", isAudiblyPlaying = false))

        val cached =
            PlaybackDownloadProgress(mediaId = "track-1", percent = 55, fromCache = true)
        assertNull(cached.fetchFraction("track-1", isAudiblyPlaying = false))

        val nothing: PlaybackDownloadProgress? = null
        assertNull(nothing.fetchFraction("track-1", isAudiblyPlaying = false))
    }

    //
    // What the codec row under the controls says. Its stored facts (bitrate, size, source,
    // delivery) describe the copy the engine is holding, so during a fetch of a track that has no
    // copy yet they describe the *old* one - reported on device as a "33 MB · Deezer (device
    // cache)" line sitting over a track that was still being downloaded.
    //

    /** A provider sweep is the state the row must name, not "Downloading 0%". */
    @Test
    fun `a sweep replaces the stored line as resolving`() {
        val sweep =
            PlaybackDownloadProgress(
                mediaId = "track-1",
                sourceId = "qobuz-web",
                percent = 0,
                stage = "resolving_stream",
                status = "preparing",
            )
        assertEquals(CodecRowState.RESOLVING, sweep.codecRowState())
    }

    /**
     * The reported window: the total size arrives before the first byte, and for that moment the
     * stored facts are still the old copy. "Under 100" has to include the opening sample.
     */
    @Test
    fun `a download replaces the stored line from its first sample`() {
        val justStarted =
            PlaybackDownloadProgress(
                mediaId = "track-1",
                sourceId = "deezer",
                percent = 0,
                bytesReceived = 0L,
                bytesTotal = 33_000_000L,
            )
        assertEquals(CodecRowState.DOWNLOADING, justStarted.codecRowState())

        val midway = justStarted.copy(percent = 42, bytesReceived = 13_000_000L)
        assertEquals(CodecRowState.DOWNLOADING, midway.codecRowState())
    }

    /** A cache replay has a real copy on disk, so the stored line *is* the truth for it. */
    @Test
    fun `a cache replay keeps the stored line`() {
        val cached =
            PlaybackDownloadProgress(
                mediaId = "track-1",
                sourceId = "deezer",
                percent = 100,
                bytesReceived = 33_000_000L,
                bytesTotal = 33_000_000L,
                fromCache = true,
            )
        assertEquals(CodecRowState.STORED, cached.codecRowState())
    }

    /** A progress entry that lingers past the last byte must not keep the row off the facts. */
    @Test
    fun `a finished transfer keeps the stored line`() {
        val completed =
            PlaybackDownloadProgress(
                mediaId = "track-1",
                sourceId = "tidal-web",
                percent = 100,
                bytesReceived = 30_000_000L,
                bytesTotal = 30_000_000L,
                status = "completed",
            )
        assertEquals(CodecRowState.STORED, completed.codecRowState())
    }

    /** No transfer at all is the ordinary case: nothing to replace the facts with. */
    @Test
    fun `no transfer keeps the stored line`() {
        val nothing: PlaybackDownloadProgress? = null
        assertEquals(CodecRowState.STORED, nothing.codecRowState())
    }

    /**
     * The reported bug: "stuck at 14%" while the track had finished and started playing.
     *
     * A SpotiFLAC file is complete before Media3 is handed it, so a percentage over audible audio
     * describes nothing on screen. Whatever left the sample behind - a terminal state that lost a
     * race with the transfer meter, or a clear that never ran - the row must not believe it.
     */
    @Test
    fun `a sample left behind while the track plays does not claim a download`() {
        val stale =
            PlaybackDownloadProgress(
                mediaId = "track-1",
                sourceId = "deezer",
                percent = 14,
                bytesReceived = 4_000_000L,
                bytesTotal = 30_000_000L,
            )
        assertEquals(CodecRowState.DOWNLOADING, stale.codecRowState())
        assertEquals(CodecRowState.STORED, stale.codecRowState(isAudiblyPlaying = true))

        val elevenPercent = stale.copy(percent = 11, bytesReceived = 3_300_000L)
        assertEquals(CodecRowState.STORED, elevenPercent.codecRowState(isAudiblyPlaying = true))
    }

    /**
     * The other side of that rule, and the bug it caused when it was written with the wrong flag.
     *
     * A player that has the item and `playWhenReady` set reports itself as "playing" for the whole
     * time the track is being fetched, before Media3 has a single byte to hand it - so a guard that
     * asked "is it playing?" answered yes for exactly the window the row exists for. Measured on
     * the device: `progress routed player=true ... pct=99` beside `row draw state=STORED`, and the
     * codec line never once said "Downloading". Audible playback is [STATE_READY]; a track still
     * waiting for audio is not audible, and a live transfer is a download.
     */
    @Test
    fun `a download for a track that is only waiting to play is still a download`() {
        val live =
            PlaybackDownloadProgress(
                mediaId = "track-1",
                sourceId = "gglos-ggottes-deno-net:gg.gglos.addon",
                percent = 42,
                bytesReceived = 12_582_912L,
                bytesTotal = 29_999_104L,
                speedMbps = 1.6,
                stage = "downloading",
            )
        // Not audibly playing: Media3 is buffering this item, so the fetch is the real answer.
        assertEquals(CodecRowState.DOWNLOADING, live.codecRowState(isAudiblyPlaying = false))
        assertEquals(0.42f, live.fetchFraction("track-1", isAudiblyPlaying = false)!!, 0.0001f)
    }

    //
    // What the one progress line says. "Downloading 42%" alone answers neither question a listener
    // waiting asks - is it moving, and how much is left - so the line carries the speed, the bytes
    // received against the total, and the source, in that order.
    //

    /** A live transfer reports all three, in the order the line prints them. */
    @Test
    fun `a download line carries speed, bytes and source`() {
        val downloading =
            PlaybackDownloadProgress(
                mediaId = "track-1",
                sourceId = "deezer",
                sourceLabel = "Deezer",
                percent = 42,
                bytesReceived = 12_582_912L,
                bytesTotal = 29_999_104L,
                speedMbps = 1.6,
            )
        assertEquals(
            listOf("1.6 MB/s", "12.0 MB of 28.6 MB", "Deezer"),
            downloading.downloadDetailParts(),
        )
    }

    /** A provider that reports no speed still gets its bytes and its name. */
    @Test
    fun `missing parts are left out rather than printed as zero`() {
        val starting =
            PlaybackDownloadProgress(
                mediaId = "track-1",
                sourceId = "qobuz-web",
                percent = 1,
                bytesTotal = 29_999_104L,
            )
        assertEquals(listOf("28.6 MB", "qobuz-web"), starting.downloadDetailParts())

        val nothing = PlaybackDownloadProgress(mediaId = "track-1", percent = 1)
        assertEquals(emptyList<String>(), nothing.downloadDetailParts())
    }

    /** A sweep that is still remembered while the audio plays is equally wrong to show. */
    @Test
    fun `a sweep left behind while the track plays is not resolving`() {
        val sweep =
            PlaybackDownloadProgress(
                mediaId = "track-1",
                sourceId = "qobuz-web",
                percent = 0,
                stage = "resolving_stream",
                status = "preparing",
            )
        assertEquals(CodecRowState.RESOLVING, sweep.codecRowState())
        assertEquals(CodecRowState.STORED, sweep.codecRowState(isAudiblyPlaying = true))
    }

}
