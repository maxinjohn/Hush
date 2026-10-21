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
    fun `a youtube label carrying a download is a device file, not a live stream`() {
        // The exact shape Hush publishes when it serves a downloaded file after resolving the URL.
        val info = PlaybackSourceLabels.parse("YouTube • Vision • downloaded")
        assertEquals(PlaybackEngine.YOUTUBE, info.engine)
        assertEquals("Vision", info.provider)
        assertEquals(PlaybackDelivery.DEVICE_DOWNLOAD, info.delivery)
        assertTrue(info.isFromDeviceFile)
    }

    @Test
    fun `a bare download label still says the audio is on the device`() {
        val info = PlaybackSourceLabels.parse(PlaybackSourceLabels.DOWNLOADED)
        assertEquals(PlaybackEngine.UNKNOWN, info.engine)
        assertNull(info.provider)
        assertEquals(PlaybackDelivery.DEVICE_DOWNLOAD, info.delivery)
        assertTrue(info.isFromDeviceFile)
    }

    @Test
    fun `a provider named downloaded is still treated as the attribute`() {
        // Guard against the split promoting "downloaded" into the provider slot, exactly like "cached".
        val info = PlaybackSourceLabels.parse("YouTube • downloaded • Vision")
        assertEquals("Vision", info.provider)
        assertEquals(PlaybackDelivery.DEVICE_DOWNLOAD, info.delivery)
    }

    @Test
    fun `a provider named cached is still treated as the cache attribute`() {
        // Guard against the split accidentally promoting "cached" into the provider slot.
        val info = PlaybackSourceLabels.parse("SpotiFLAC • cached • deezer")
        assertEquals("deezer", info.provider)
        assertEquals(PlaybackDelivery.DEVICE_CACHE, info.delivery)
    }

    @Test
    fun `a download replaces the cache attribute instead of adding to it`() {
        // The exact bug: fetching a track for playback writes the cache file, pressing Download
        // keeps the same bytes, and the cache's own label used to win - so a downloaded song read
        // "(device cache)" while it played.
        val label = PlaybackSourceLabels.withDownloadAttribute("3rd-party • deezer • cached")
        assertEquals("3rd-party • deezer • downloaded", label)
        val info = PlaybackSourceLabels.parse(label)
        assertEquals(PlaybackEngine.SPOTIFLAC, info.engine)
        assertEquals("deezer", info.provider)
        assertEquals(PlaybackDelivery.DEVICE_DOWNLOAD, info.delivery)
    }

    @Test
    fun `a label that already says downloaded is not given a second attribute`() {
        assertEquals(
            "YouTube • Web • downloaded",
            PlaybackSourceLabels.withDownloadAttribute("YouTube • Web • downloaded"),
        )
    }

    @Test
    fun `a stale cache-only label becomes a download on its own`() {
        assertEquals(PlaybackSourceLabels.DOWNLOADED, PlaybackSourceLabels.withDownloadAttribute("cached"))
        assertEquals(PlaybackSourceLabels.DOWNLOADED, PlaybackSourceLabels.withDownloadAttribute(""))
        assertEquals(
            PlaybackDelivery.DEVICE_DOWNLOAD,
            PlaybackSourceLabels.parse(PlaybackSourceLabels.withDownloadAttribute("SpotiFLAC - Deezer"))
                .delivery,
        )
    }

    /**
     * A source named for the service it streams *and* the addon that serves it, which is what the
     * player publishes for a server addon that named itself after its host (`Tidal · gglos`). The
     * row prints the provider as it is, so `3rd-party · Tidal · gglos (fetched)` is what the codec
     * bar reads for a track the addon fetched.
     */
    @Test
    fun `a provider that names its service and its addon parses whole`() {
        val info = PlaybackSourceLabels.parse("3rd-party • Tidal · gglos • cached")
        assertEquals(PlaybackEngine.SPOTIFLAC, info.engine)
        assertEquals("Tidal · gglos", info.provider)
        assertEquals(PlaybackDelivery.DEVICE_CACHE, info.delivery)
    }

    /**
     * Two sources of one service, told apart on the bar by the kind and not by dropping the provider:
     * the Qobuz addon publishes `Qobuz`, the name the bundled Qobuz extension already reads, so its
     * label carries `· addon`. A track either source fetched still says `Qobuz` where a listener looks
     * for it, and `3rd-party · Qobuz · addon (fetched)` says which of the two it came through.
     */
    @Test
    fun `two sources of one service read apart on the codec bar`() {
        val addon = PlaybackSourceLabels.parse("3rd-party • Qobuz · addon • cached")
        assertEquals(PlaybackEngine.SPOTIFLAC, addon.engine)
        assertEquals("Qobuz · addon", addon.provider)
        assertEquals(PlaybackDelivery.DEVICE_CACHE, addon.delivery)

        val extension = PlaybackSourceLabels.parse("3rd-party • Qobuz • cached")
        assertEquals("Qobuz", extension.provider)
    }

    /**
     * The old hyphenated separator must not eat a hyphen that is part of the provider: a bulleted
     * label splits on bullets only, so an addon whose own name carries a hyphen is still named
     * whole rather than truncated at the hyphen.
     */
    @Test
    fun `a hyphen inside a bulleted provider is not a separator`() {
        val info = PlaybackSourceLabels.parse("3rd-party • Tidal · gglos-vr • cached")
        assertEquals("Tidal · gglos-vr", info.provider)
        assertEquals(PlaybackDelivery.DEVICE_CACHE, info.delivery)
    }

    @Test
    fun `a hyphenated label keeps its provider when the download attribute is added`() {
        val info = PlaybackSourceLabels.parse(PlaybackSourceLabels.withDownloadAttribute("SpotiFLAC - Deezer"))
        assertEquals("Deezer", info.provider)
        assertEquals(PlaybackDelivery.DEVICE_DOWNLOAD, info.delivery)
    }
}
