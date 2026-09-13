/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player used to show one opaque label, which made "SpotiFLAC ... cached" and the
 * separate media3 "downloaded" badge read as the same fact. These pin the distinction:
 * which engine, which provider, and whether the audio is coming off the disk or the network.
 */
class PlaybackSourceInfoTest {
    @Test
    fun `a cached spotiflac track reports the device cache`() {
        val info = PlaybackSourceLabels.parse("SpotiFLAC • deezer • cached")
        assertEquals(PlaybackEngine.SPOTIFLAC, info.engine)
        assertEquals("deezer", info.provider)
        assertEquals(PlaybackDelivery.DEVICE_CACHE, info.delivery)
        assertTrue(info.isFromDeviceFile)
    }

    @Test
    fun `a freshly resolved spotiflac track is not reported as a cache hit`() {
        val info = PlaybackSourceLabels.parse("SpotiFLAC • deezer")
        assertEquals(PlaybackEngine.SPOTIFLAC, info.engine)
        assertEquals("deezer", info.provider)
        assertEquals(PlaybackDelivery.FETCHED_FOR_PLAY, info.delivery)
        assertTrue(info.isFromDeviceFile)
    }

    @Test
    fun `the hyphenated resolver label also parses`() {
        val info = PlaybackSourceLabels.parse("SpotiFLAC - Deezer")
        assertEquals(PlaybackEngine.SPOTIFLAC, info.engine)
        assertEquals("Deezer", info.provider)
        assertEquals(PlaybackDelivery.FETCHED_FOR_PLAY, info.delivery)
    }

    @Test
    fun `a cached label with no provider still reports the cache`() {
        val info = PlaybackSourceLabels.parse("SpotiFLAC • cached")
        assertEquals(PlaybackEngine.SPOTIFLAC, info.engine)
        assertNull(info.provider)
        assertEquals(PlaybackDelivery.DEVICE_CACHE, info.delivery)
    }

    @Test
    fun `youtube is never a device file`() {
        listOf("YouTube • Web", "YouTube • VR", "YouTube").forEach { raw ->
            val info = PlaybackSourceLabels.parse(raw)
            assertEquals(raw, PlaybackEngine.YOUTUBE, info.engine)
            assertEquals(raw, PlaybackDelivery.LIVE_STREAM, info.delivery)
            assertEquals(raw, false, info.isFromDeviceFile)
        }
    }

    @Test
    fun `a bare youtube client name is recognised and named`() {
        val info = PlaybackSourceLabels.parse("WEB_REMIX")
        assertEquals(PlaybackEngine.YOUTUBE, info.engine)
        assertEquals("Web", info.provider)
        assertEquals(PlaybackDelivery.LIVE_STREAM, info.delivery)
        assertEquals("VR", PlaybackSourceLabels.parse("ANDROID_VR").provider)
    }

    @Test
    fun `hi-res is reported as its own engine coming off disk`() {
        val info = PlaybackSourceLabels.parse("Hi-Res • tidal")
        assertEquals(PlaybackEngine.HIRES, info.engine)
        assertEquals("tidal", info.provider)
        assertTrue(info.isFromDeviceFile)
    }

    @Test
    fun `an unknown label claims nothing about delivery`() {
        val info = PlaybackSourceLabels.parse("something new")
        assertEquals(PlaybackEngine.UNKNOWN, info.engine)
        assertNull(info.delivery)
        assertEquals(false, info.isFromDeviceFile)
    }

    @Test
    fun `blank and null labels are handled`() {
        listOf(null, "", "   ").forEach { raw ->
            val info = PlaybackSourceLabels.parse(raw)
            assertEquals(PlaybackEngine.UNKNOWN, info.engine)
            assertNull(info.delivery)
            assertNull(info.provider)
        }
    }

    @Test
    fun `a provider named cached is still treated as the cache attribute`() {
        // Guard against the split accidentally promoting "cached" into the provider slot.
        val info = PlaybackSourceLabels.parse("SpotiFLAC • cached • deezer")
        assertEquals("deezer", info.provider)
        assertEquals(PlaybackDelivery.DEVICE_CACHE, info.delivery)
    }
}
