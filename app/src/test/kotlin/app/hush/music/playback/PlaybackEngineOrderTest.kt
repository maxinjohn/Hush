package app.hush.music.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackEngineOrderTest {

    @Test
    fun `both engines follow the saved order`() {
        assertEquals(
            listOf("YOUTUBE", "SPOTIFLAC"),
            PlaybackEngineOrder.effective(
                savedOrder = listOf("YOUTUBE", "SPOTIFLAC"),
                spotiflacAvailable = true,
                youtubeAvailable = true,
            ),
        )
        assertEquals(
            listOf("SPOTIFLAC", "YOUTUBE"),
            PlaybackEngineOrder.effective(
                savedOrder = listOf("SPOTIFLAC", "YOUTUBE"),
                spotiflacAvailable = true,
                youtubeAvailable = true,
            ),
        )
    }

    @Test
    fun `youtube only when spotiflac is disabled`() {
        assertEquals(
            listOf("YOUTUBE"),
            PlaybackEngineOrder.effective(
                savedOrder = listOf("SPOTIFLAC", "YOUTUBE"),
                spotiflacAvailable = false,
                youtubeAvailable = true,
            ),
        )
    }

    @Test
    fun `spotiflac only when youtube is disabled`() {
        // Even if the saved order still puts YouTube first, it cannot be used.
        assertEquals(
            listOf("SPOTIFLAC"),
            PlaybackEngineOrder.effective(
                savedOrder = listOf("YOUTUBE", "SPOTIFLAC"),
                spotiflacAvailable = true,
                youtubeAvailable = false,
            ),
        )
    }

    @Test
    fun `an enabled engine missing from the saved order is still usable`() {
        assertEquals(
            listOf("SPOTIFLAC", "YOUTUBE"),
            PlaybackEngineOrder.effective(
                savedOrder = listOf("SPOTIFLAC"),
                spotiflacAvailable = true,
                youtubeAvailable = true,
            ),
        )
        assertEquals(
            listOf("YOUTUBE"),
            PlaybackEngineOrder.effective(
                savedOrder = emptyList(),
                spotiflacAvailable = false,
                youtubeAvailable = true,
            ),
        )
    }

    @Test
    fun `unknown and duplicate entries are ignored`() {
        assertEquals(
            listOf("SPOTIFLAC", "YOUTUBE"),
            PlaybackEngineOrder.effective(
                savedOrder = listOf("spotiflac", "GARBAGE", "SPOTIFLAC", ""),
                spotiflacAvailable = true,
                youtubeAvailable = true,
            ),
        )
    }

    @Test
    fun `both disabled yields no engines`() {
        assertEquals(
            emptyList<String>(),
            PlaybackEngineOrder.effective(
                savedOrder = listOf("SPOTIFLAC", "YOUTUBE"),
                spotiflacAvailable = false,
                youtubeAvailable = false,
            ),
        )
    }

    @Test
    fun `a cached youtube url must not short-circuit while spotiflac is first`() {
        // This is the regression that made the priority list look ignored: a YouTube URL
        // cached hours earlier kept winning every play until it expired.
        assertEquals(
            false,
            PlaybackEngineOrder.cachedValueMayShortCircuit(
                spotiflacFirst = true,
                cachedIsYouTubeStream = true,
            ),
        )
    }

    @Test
    fun `a cached youtube url may short-circuit when youtube is first`() {
        assertEquals(
            true,
            PlaybackEngineOrder.cachedValueMayShortCircuit(
                spotiflacFirst = false,
                cachedIsYouTubeStream = true,
            ),
        )
    }

    @Test
    fun `a cached youtube url is reused once spotiflac is known to have no match`() {
        // Without this escape hatch, a track no SpotiFLAC source can match would spend
        // the whole provider timeout on every single replay.
        assertEquals(
            true,
            PlaybackEngineOrder.cachedValueMayShortCircuit(
                spotiflacFirst = true,
                cachedIsYouTubeStream = true,
                spotiflacRecentlyMissed = true,
            ),
        )
    }

    @Test
    fun `cached youtube bytes must not answer for the first engine`() {
        // The regression this rule was extracted for: with the third-party engine moved to the top of
        // the priority list, a fresh track swept its sources and played lossless while the same track
        // after one YouTube play kept playing from YouTube, because the bytes were already on the
        // device and nothing ever asked the sources again. Measured on the device: a complete entry in
        // Media3's streaming cache is served with no resolver call at all, so the URL-cache rule could
        // not help - the bytes path never asks for a URL.
        assertEquals(
            false,
            PlaybackEngineOrder.cachedBytesMayShortCircuitResolve(
                spotiflacFirst = true,
                bytesAreSpotiFLACs = false,
            ),
        )
    }

    @Test
    fun `cached bytes answer as before when youtube is first`() {
        assertEquals(
            true,
            PlaybackEngineOrder.cachedBytesMayShortCircuitResolve(
                spotiflacFirst = false,
                bytesAreSpotiFLACs = false,
            ),
        )
    }

    @Test
    fun `bytes the third-party engine produced are always served`() {
        // A file it fetched is already what the order would return, and it never expires: re-resolving
        // it would spend a sweep on bytes the device holds.
        assertEquals(
            true,
            PlaybackEngineOrder.cachedBytesMayShortCircuitResolve(
                spotiflacFirst = true,
                bytesAreSpotiFLACs = true,
            ),
        )
        assertEquals(
            true,
            PlaybackEngineOrder.cachedBytesMayShortCircuitResolve(
                spotiflacFirst = false,
                bytesAreSpotiFLACs = true,
            ),
        )
    }

    @Test
    fun `cached bytes are reused once the first engine is known to have no match`() {
        // The same escape hatch the URL rule has, and for the same reason: without it a track no source
        // can match would spend the whole provider budget on every replay to reach the answer the memo
        // already holds.
        assertEquals(
            true,
            PlaybackEngineOrder.cachedBytesMayShortCircuitResolve(
                spotiflacFirst = true,
                bytesAreSpotiFLACs = false,
                spotiflacRecentlyMissed = true,
            ),
        )
    }

    @Test
    fun `youtube-sourced cached bytes do not play while youtube is disabled`() {
        // The regression: a YouTube stream cached under the track's mediaId kept being
        // served - instantly, with no resolver call - after YouTube was switched off,
        // while every label on screen still reported SpotiFLAC.
        assertEquals(
            false,
            PlaybackEngineOrder.cachedBytesMayBeServed(
                youtubeEnabled = false,
                bytesCameFromSpotiFLAC = false,
            ),
        )
    }

    @Test
    fun `spotiflac-sourced cached bytes stay playable offline`() {
        // Downloads whose content is a SpotiFLAC file must remain usable with YouTube
        // off, otherwise disabling YouTube would break offline playback outright.
        assertEquals(
            true,
            PlaybackEngineOrder.cachedBytesMayBeServed(
                youtubeEnabled = false,
                bytesCameFromSpotiFLAC = true,
            ),
        )
    }

    @Test
    fun `cached bytes always play while youtube is enabled`() {
        assertEquals(
            true,
            PlaybackEngineOrder.cachedBytesMayBeServed(
                youtubeEnabled = true,
                bytesCameFromSpotiFLAC = false,
            ),
        )
        assertEquals(
            true,
            PlaybackEngineOrder.cachedBytesMayBeServed(
                youtubeEnabled = true,
                bytesCameFromSpotiFLAC = true,
            ),
        )
    }

    @Test
    fun `a spotiflac-sourced download is served with youtube off`() {
        // The regression this decision exists for. Media3 never records a download's
        // origin on Hush's path (it only stores a redirect when the URI it opened
        // differs from the one it was asked for, and Hush hands it the resolved URI), so
        // origin used to come back unknown and every download was refused whenever
        // YouTube was switched off - including the SpotiFLAC ones that are the whole
        // point of downloading.
        assertEquals(
            StoredBytesDecision.SERVE,
            PlaybackEngineOrder.storedBytesDecision(
                youtubeEnabled = false,
                origin = StoredBytesOrigin.SPOTIFLAC,
            ),
        )
    }

    @Test
    fun `a youtube-sourced download is refused with youtube off, and says why`() {
        assertEquals(
            StoredBytesDecision.REFUSE_ENGINE_DISABLED,
            PlaybackEngineOrder.storedBytesDecision(
                youtubeEnabled = false,
                origin = StoredBytesOrigin.YOUTUBE,
            ),
        )
    }

    @Test
    fun `an unrecorded origin is refused but not blamed on youtube`() {
        // These are two different problems and the user-facing log has to say which one
        // it is: one is a toggle they can flip, the other is a record Hush does not have.
        assertEquals(
            StoredBytesDecision.REFUSE_UNKNOWN_ORIGIN,
            PlaybackEngineOrder.storedBytesDecision(
                youtubeEnabled = false,
                origin = StoredBytesOrigin.UNKNOWN,
            ),
        )
        assertEquals(
            StoredBytesDecision.REFUSE_ENGINE_DISABLED,
            PlaybackEngineOrder.storedBytesDecision(
                youtubeEnabled = false,
                origin = StoredBytesOrigin.YOUTUBE,
            ),
        )
    }

    @Test
    fun `every origin is served while youtube is enabled`() {
        StoredBytesOrigin.entries.forEach { origin ->
            assertEquals(
                origin.name,
                StoredBytesDecision.SERVE,
                PlaybackEngineOrder.storedBytesDecision(youtubeEnabled = true, origin = origin),
            )
        }
    }

    @Test
    fun `the boolean rule matches the decision rule`() {
        val origins = StoredBytesOrigin.entries
        listOf(false, true).forEach { youtubeEnabled ->
            origins.forEach { origin ->
                assertEquals(
                    PlaybackEngineOrder.storedBytesDecision(youtubeEnabled, origin) ==
                        StoredBytesDecision.SERVE,
                    PlaybackEngineOrder.storedBytesMayBeServed(youtubeEnabled, origin),
                )
            }
            // And the older boolean-in form still agrees with both.
            assertEquals(
                PlaybackEngineOrder.storedBytesMayBeServed(youtubeEnabled, StoredBytesOrigin.SPOTIFLAC),
                PlaybackEngineOrder.cachedBytesMayBeServed(youtubeEnabled, bytesCameFromSpotiFLAC = true),
            )
            assertEquals(
                PlaybackEngineOrder.storedBytesMayBeServed(youtubeEnabled, StoredBytesOrigin.UNKNOWN),
                PlaybackEngineOrder.cachedBytesMayBeServed(youtubeEnabled, bytesCameFromSpotiFLAC = false),
            )
        }
    }

    @Test
    fun `a cached local spotiflac file always short-circuits`() {
        // Local files are not YouTube streams: they never expire, and re-resolving them
        // on every seek stalled playback. Both orderings must keep serving them.
        assertEquals(
            true,
            PlaybackEngineOrder.cachedValueMayShortCircuit(
                spotiflacFirst = true,
                cachedIsYouTubeStream = false,
            ),
        )
        assertEquals(
            true,
            PlaybackEngineOrder.cachedValueMayShortCircuit(
                spotiflacFirst = false,
                cachedIsYouTubeStream = false,
            ),
        )
    }
}
