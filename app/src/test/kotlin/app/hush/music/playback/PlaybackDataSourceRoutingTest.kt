/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the invariant behind the playback chain's ordering.
 *
 * The bug this guards against: a track's media item URI is a bare song id (no scheme),
 * so if routing is applied to *that*, every track takes the cache path - including a
 * track the resolver turns into a local SpotiFLAC file, whose bytes then get copied
 * into Media3's cache as a duplicate.
 */
class PlaybackDataSourceRoutingTest {
    @Test
    fun `a resolved spotiflac file is read directly and never through the cache`() {
        assertEquals(
            PlaybackByteSource.LOCAL_FILE,
            PlaybackDataSourceRouting.routeResolvedUri("file"),
        )
    }

    /**
     * The exact regression. A bare song id has no scheme, so the *media item* scheme is
     * not local - but the URI the resolver produced is, and that is the one that decides.
     */
    @Test
    fun `routing the media item scheme would send a resolved file to the cache`() {
        assertEquals(
            PlaybackByteSource.CACHED,
            PlaybackDataSourceRouting.routeResolvedUri(null),
        )
        assertEquals(
            PlaybackByteSource.LOCAL_FILE,
            PlaybackDataSourceRouting.routeResolvedUri("file"),
        )
    }

    @Test
    fun `local media schemes are all read directly`() {
        listOf("file", "content", "android.resource").forEach { scheme ->
            assertEquals(
                "scheme=$scheme",
                PlaybackByteSource.LOCAL_FILE,
                PlaybackDataSourceRouting.routeResolvedUri(scheme),
            )
        }
    }

    @Test
    fun `remote schemes still go through the cache`() {
        listOf("https", "http", "rtsp", "data").forEach { scheme ->
            assertEquals(
                "scheme=$scheme",
                PlaybackByteSource.CACHED,
                PlaybackDataSourceRouting.routeResolvedUri(scheme),
            )
        }
    }

    @Test
    fun `scheme matching is case and whitespace insensitive`() {
        listOf("FILE", "File", " file ", "CONTENT", "Android.Resource").forEach { scheme ->
            assertTrue("scheme=$scheme", PlaybackDataSourceRouting.isLocalFileScheme(scheme))
        }
    }

    @Test
    fun `an absent or unknown scheme is never treated as a local file`() {
        assertFalse(PlaybackDataSourceRouting.isLocalFileScheme(null))
        assertFalse(PlaybackDataSourceRouting.isLocalFileScheme(""))
        assertFalse(PlaybackDataSourceRouting.isLocalFileScheme("   "))
        // Must fail closed: a resolution that did not happen is not a local file.
        assertFalse(PlaybackDataSourceRouting.isLocalFileScheme("lGsabQ1Szvk"))
        assertFalse(PlaybackDataSourceRouting.isLocalFileScheme("filesystem"))
        assertFalse(PlaybackDataSourceRouting.isLocalFileScheme("filex"))
    }
}
