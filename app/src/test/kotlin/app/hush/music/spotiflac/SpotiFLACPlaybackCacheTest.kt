/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The playback cache key decides whether a track is fetched again or replayed from
 * disk, so its stability and its quality separation are both load-bearing.
 */
class SpotiFLACPlaybackCacheTest {
    private fun key(
        title: String = "Nightcall",
        artist: String = "Kavinsky",
        album: String? = "OutRun",
        durationMs: Long = 258_000L,
        isrc: String? = null,
        spotifyTrackId: String? = null,
        quality: String? = "LOSSLESS",
    ) = SpotiFLACPlaybackCache.cacheKeyFor(
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        isrc = isrc,
        spotifyTrackId = spotifyTrackId,
        quality = quality,
    )

    @Test
    fun `same track resolves to the same key`() {
        assertEquals(key(), key())
    }

    @Test
    fun `key is a short hash rather than raw metadata`() {
        val value = key()
        assertEquals(24, value.length)
        assertTrue(value.all { it in "0123456789abcdef" })
    }

    @Test
    fun `metadata matching ignores case and surrounding spaces`() {
        assertEquals(key(title = " Nightcall "), key(title = "nightcall"))
        assertEquals(key(artist = "KAVINSKY"), key(artist = "kavinsky"))
    }

    @Test
    fun `different tracks get different keys`() {
        assertNotEquals(key(title = "Nightcall"), key(title = "Nightcall (Live)"))
        assertNotEquals(key(), key(artist = "Kavinsky & Lovefoxxx"))
        assertNotEquals(key(), key(durationMs = 300_000L))
    }

    @Test
    fun `hi-res requests never reuse a lossless file`() {
        val lossless = key(quality = "LOSSLESS")
        val hiRes = key(quality = "HI_RES_LOSSLESS")
        assertNotEquals(lossless, hiRes)
        assertEquals("lossless", SpotiFLACPlaybackCache.qualityBucket("LOSSLESS"))
        assertEquals("hires", SpotiFLACPlaybackCache.qualityBucket("HI_RES_LOSSLESS"))
        assertEquals("hires", SpotiFLACPlaybackCache.qualityBucket("hires"))
        assertEquals("hires", SpotiFLACPlaybackCache.qualityBucket("HI_RES"))
        // Unknown / missing quality must not silently land in the hi-res bucket.
        assertEquals("lossless", SpotiFLACPlaybackCache.qualityBucket(null))
        assertEquals("lossless", SpotiFLACPlaybackCache.qualityBucket(""))
        assertEquals("lossless", SpotiFLACPlaybackCache.qualityBucket("HIGH"))
    }

    @Test
    fun `provider ids win over metadata so the same song collapses to one file`() {
        val bySpotifyId = key(title = "Whatever", spotifyTrackId = "6dGnYIeXmHdcikdzNNDMm2")
        val sameIdDifferentMetadata =
            key(title = "Whatever (Deluxe)", spotifyTrackId = "6dGnYIeXmHdcikdzNNDMm2")
        assertEquals(bySpotifyId, sameIdDifferentMetadata)

        val byIsrc = key(title = "Whatever", isrc = "USUM71703861")
        val sameIsrcDifferentTitle = key(title = "Nightcall", isrc = "USUM71703861")
        assertEquals(byIsrc, sameIsrcDifferentTitle)

        // Spotify id outranks ISRC when both are present.
        assertNotEquals(byIsrc, key(isrc = "USUM71703861", spotifyTrackId = "6dGnYIeXmHdcikdzNNDMm2"))
    }

    @Test
    fun `blank provider ids fall back to metadata matching`() {
        assertEquals(key(), key(isrc = "", spotifyTrackId = "   "))
    }

    /**
     * SpotiFLAC playback files follow the Storage screen's "Max song cache size"
     * rather than a setting of their own, so the mapping from that one preference
     * has to be exact: 0 (off) and -1 (unlimited) both disable cap-based eviction.
     */
    @Test
    fun `storage song cache size maps to the shared byte cap`() {
        assertEquals(0L, SpotiFLACPlaybackCache.limitBytesForMegabytes(0))
        assertEquals(0L, SpotiFLACPlaybackCache.limitBytesForMegabytes(-1))
        assertEquals(1024L * 1024L, SpotiFLACPlaybackCache.limitBytesForMegabytes(1))
        assertEquals(2L * 1024L * 1024L * 1024L, SpotiFLACPlaybackCache.limitBytesForMegabytes(2048))
        assertEquals(
            SpotiFLACPlaybackCache.limitBytesForMegabytes(SpotiFLACPlaybackCache.DEFAULT_LIMIT_MB),
            1024L * 1024L * 1024L,
        )
    }
}
