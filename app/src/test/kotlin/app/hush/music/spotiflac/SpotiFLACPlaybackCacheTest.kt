/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * The real-world failure this key has to survive, kept as an executable record.
     *
     * Measured on device for one song (Toto - Africa): the resolve that downloaded the
     * file had no duration available and wrote it under the first key, and the next
     * lookup of the same track had the real 4:32 duration and computed the second - so a
     * track already on disk was downloaded again. The fix is that the callers never let
     * an unknown duration win (`SpotiFLACPlaybackIdentity.bestDurationMs`) and that a
     * drifted identity still finds its file by media id. If either hash here changes,
     * that evidence no longer describes the code and this test is the place to say so.
     */
    @Test
    fun `an unknown duration is a different identity from the real one`() {
        val downloadedWithoutDuration =
            key(title = "Africa", artist = "Toto", album = "", durationMs = 0L)
        val lookedUpWithDuration =
            key(title = "Africa", artist = "Toto", album = "", durationMs = 272_000L)
        assertEquals("7ef9999c294d75b68b411469", downloadedWithoutDuration)
        assertEquals("fa3557adc6967c1d3adeb437", lookedUpWithDuration)
        assertNotEquals(downloadedWithoutDuration, lookedUpWithDuration)
    }

    /**
     * The media-id lookup has no quality component, so the bucket is checked separately:
     * a hi-res request must not be answered with the lossless file already on disk, while
     * the lossless default may use an entry from before the bucket was recorded.
     */
    @Test
    fun `a file only answers the quality it was fetched for`() {
        assertTrue(SpotiFLACPlaybackCache.bucketSatisfies("lossless", "lossless"))
        assertTrue(SpotiFLACPlaybackCache.bucketSatisfies("hires", "hires"))
        assertFalse(SpotiFLACPlaybackCache.bucketSatisfies("lossless", "hires"))
        assertFalse(SpotiFLACPlaybackCache.bucketSatisfies("hires", "lossless"))
        // Unknown bucket: a legacy entry, usable only for the default request.
        assertTrue(SpotiFLACPlaybackCache.bucketSatisfies(null, "lossless"))
        assertFalse(SpotiFLACPlaybackCache.bucketSatisfies(null, "hires"))
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
