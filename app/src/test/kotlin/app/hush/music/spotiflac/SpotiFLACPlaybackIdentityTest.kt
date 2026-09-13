/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The download path reads this to resolve the same track on the same source that
 * playback used, so the recorded identity has to survive intact and stay bounded.
 */
class SpotiFLACPlaybackIdentityTest {
    private fun record(
        mediaId: String,
        title: String = "Nightcall",
        isrc: String? = "USUM71703861",
        spotifyTrackId: String? = "6dGnYIeXmHdcikdzNNDMm2",
    ) = SpotiFLACPlaybackIdentity.record(
        mediaId = mediaId,
        title = title,
        artist = "Kavinsky",
        album = "OutRun",
        durationMs = 258_000L,
        isrc = isrc,
        spotifyTrackId = spotifyTrackId,
        quality = "LOSSLESS",
        coverUrl = "https://example.invalid/cover.jpg",
    )

    @Test
    fun `remembers the strong identifiers playback used`() {
        record("abc123")
        val identity = SpotiFLACPlaybackIdentity.get("abc123")
        assertNotNull(identity)
        assertEquals("Nightcall", identity!!.title)
        assertEquals("Kavinsky", identity.artist)
        assertEquals("USUM71703861", identity.isrc)
        assertEquals("6dGnYIeXmHdcikdzNNDMm2", identity.spotifyTrackId)
        assertEquals(258_000L, identity.durationMs)
    }

    @Test
    fun `a track without a title is not recorded`() {
        SpotiFLACPlaybackIdentity.record(
            mediaId = "untitled",
            title = "   ",
            artist = null,
            album = null,
            durationMs = 0L,
            isrc = null,
            spotifyTrackId = null,
            quality = "LOSSLESS",
        )
        assertNull(SpotiFLACPlaybackIdentity.get("untitled"))
    }

    @Test
    fun `blank identifiers are stored as absent rather than empty strings`() {
        record("blankIds", isrc = "", spotifyTrackId = "   ")
        val identity = SpotiFLACPlaybackIdentity.get("blankIds")
        assertNotNull(identity)
        assertNull(identity!!.isrc)
        assertNull(identity.spotifyTrackId)
    }

    @Test
    fun `blank media ids are ignored`() {
        record("")
        assertNull(SpotiFLACPlaybackIdentity.get(""))
    }

    @Test
    fun `the store stays bounded and evicts the least recently used`() {
        val before = SpotiFLACPlaybackIdentity.size()
        repeat(600) { index -> record("eviction-$index") }
        // Bounded: a long session cannot grow this without limit.
        assertEquals(256, SpotiFLACPlaybackIdentity.size())
        // The earliest of the new entries has been evicted…
        assertNull(SpotiFLACPlaybackIdentity.get("eviction-0"))
        // …while the newest survives.
        assertNotNull(SpotiFLACPlaybackIdentity.get("eviction-599"))
        assertEquals(before.coerceAtMost(256), SpotiFLACPlaybackIdentity.size().coerceAtMost(before))
    }
}
