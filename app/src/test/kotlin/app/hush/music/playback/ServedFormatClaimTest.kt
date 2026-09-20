/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 and Section 5
 */

package app.hush.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The row the player draws about a track's audio has to be about bytes that exist. The failure this
 * pins was measured on device: a queue-restored track read `FLAC • 1411 kbps • 20 MB` while the
 * SpotiFLAC cache index was empty and its directory did not exist, and the track then failed - the
 * player advertising lossless audio it could not serve.
 *
 * The second half is that *any* bytes are not enough: a `FLAC` row riding on the streamed YouTube
 * copy of the same track is the same lie told through the other store, so the claim is checked
 * against the store that holds the bytes.
 */
class ServedFormatClaimTest {

    private fun evidence(
        servedNow: Boolean = false,
        trackIsLocalFile: Boolean = false,
        bytes: StoredBytes = StoredBytes(),
        rowClaimsSpotiFLAC: Boolean = false,
        streamingNow: Boolean = false,
    ) = ServedFormatClaim.evidence(
        servedNow = servedNow,
        trackIsLocalFile = trackIsLocalFile,
        bytes = bytes,
        rowClaimsSpotiFLAC = rowClaimsSpotiFLAC,
        streamingNow = streamingNow,
    )

    @Test
    fun `a restored queue item with no local bytes is described by nothing`() {
        val result = evidence()
        assertEquals(FormatRowEvidence.NOTHING, result)
        assertFalse(ServedFormatClaim.mayShowStoredRow(result))
    }

    @Test
    fun `a track this session resolved is described by the resolve`() {
        val result = evidence(servedNow = true)
        assertEquals(FormatRowEvidence.SERVED_NOW, result)
        assertTrue(ServedFormatClaim.mayShowStoredRow(result))
    }

    @Test
    fun `a resolve beats the stored row rather than being merged with it`() {
        // The published format is what is playing; the stored row is only ever a guess about it, so
        // the two must not both be offered as though they were the same claim.
        assertEquals(
            FormatRowEvidence.SERVED_NOW,
            evidence(
                servedNow = true,
                trackIsLocalFile = true,
                bytes = StoredBytes(userDownload = true, downloadIsSpotiFLAC = true),
            ),
        )
    }

    @Test
    fun `a downloaded track is described by its stored row`() {
        val result = evidence(bytes = StoredBytes(userDownload = true))
        assertEquals(FormatRowEvidence.LOCAL_BYTES, result)
        assertTrue(ServedFormatClaim.mayShowStoredRow(result))
    }

    @Test
    fun `a scanned local song is described by its stored row`() {
        // Its bytes are on the device by definition, and the scanner is what wrote the row.
        val result = evidence(trackIsLocalFile = true)
        assertEquals(FormatRowEvidence.LOCAL_BYTES, result)
        assertTrue(ServedFormatClaim.mayShowStoredRow(result))
    }

    @Test
    fun `a lossless row with no lossless bytes anywhere is hidden`() {
        // The reported symptom: the row says FLAC and 1411 kbps, the file is gone, and nothing has
        // been served. Claiming a size and a bitrate for audio that is not here is the whole bug.
        val result = evidence(rowClaimsSpotiFLAC = true)
        assertEquals(FormatRowEvidence.NOTHING, result)
        assertFalse(ServedFormatClaim.mayShowStoredRow(result))
    }

    @Test
    fun `a lossless row riding on the streamed youtube copy is hidden`() {
        // Same track, same media id: the last resolve was SpotiFLAC, its file has gone, and this
        // session is streaming the YouTube copy. The streamed bytes cannot be lossless, so the row
        // may not describe them as such.
        val result =
            evidence(
                rowClaimsSpotiFLAC = true,
                bytes = StoredBytes(streamingCache = true),
            )
        assertEquals(FormatRowEvidence.NOTHING, result)
        assertFalse(ServedFormatClaim.mayShowStoredRow(result))
    }

    @Test
    fun `a lossless row backed by a spotiflac cache file is shown`() {
        val result =
            evidence(
                rowClaimsSpotiFLAC = true,
                bytes = StoredBytes(spotiflacCacheFile = true),
            )
        assertEquals(FormatRowEvidence.LOCAL_BYTES, result)
        assertTrue(ServedFormatClaim.mayShowStoredRow(result))
    }

    @Test
    fun `a lossless row backed by a spotiflac download is shown`() {
        val result =
            evidence(
                rowClaimsSpotiFLAC = true,
                bytes = StoredBytes(userDownload = true, downloadIsSpotiFLAC = true),
            )
        assertEquals(FormatRowEvidence.LOCAL_BYTES, result)
        assertTrue(ServedFormatClaim.mayShowStoredRow(result))
    }

    @Test
    fun `a lossless row backed by a youtube download of the same track is hidden`() {
        // The download holds real bytes, just not lossless ones - so it still does not back a FLAC
        // claim. Where the bytes came from is Hush's own record, not the file's name.
        val result =
            evidence(
                rowClaimsSpotiFLAC = true,
                bytes = StoredBytes(userDownload = true, downloadIsSpotiFLAC = false),
            )
        assertEquals(FormatRowEvidence.NOTHING, result)
        assertFalse(ServedFormatClaim.mayShowStoredRow(result))
    }

    @Test
    fun `a hidden lossless row says its bytes are missing, not that they are mismatched`() {
        // The two cases read the same on screen - no row - so the reason has to be the thing that
        // tells them apart, and it is only available where the row was refused.
        assertEquals(
            "the row claims lossless audio and none of it is here - no playback file, " +
                "no download, no streamed copy",
            ServedFormatClaim.explain(StoredBytes(), rowClaimsSpotiFLAC = true),
        )
    }

    @Test
    fun `a hidden lossless row names the streamed bytes it refused to describe`() {
        assertEquals(
            "the row claims lossless audio but the only bytes here are not lossless " +
                "(streamed=true download=false)",
            ServedFormatClaim.explain(StoredBytes(streamingCache = true), rowClaimsSpotiFLAC = true),
        )
    }

    @Test
    fun `a youtube row describes the stream being played right now`() {
        // The ordinary case, and the one the first version of this rule hid: nothing is on disk until
        // Media3's song cache fills, and the cache is written after the row is asked for, so the codec
        // row vanished for the whole of an ordinary YouTube track.
        val result = evidence(streamingNow = true)
        assertEquals(FormatRowEvidence.STREAMING_NOW, result)
        assertTrue(ServedFormatClaim.mayShowStoredRow(result))
    }

    @Test
    fun `a lossless row cannot ride on a stream, however loudly it is playing`() {
        // Streaming is checked *after* the lossless claim, so this case is unchanged from the original
        // bug: a FLAC row over a YouTube stream is still refused.
        val result = evidence(rowClaimsSpotiFLAC = true, streamingNow = true)
        assertEquals(FormatRowEvidence.NOTHING, result)
        assertFalse(ServedFormatClaim.mayShowStoredRow(result))
    }

    @Test
    fun `a stream only describes the track it is playing`() {
        // The streaming flag is about the current track; a row for any other media id still needs its
        // own evidence, which the caller establishes by only passing true for the current one.
        val other = evidence(streamingNow = false)
        assertEquals(FormatRowEvidence.NOTHING, other)
    }

    @Test
    fun `bytes on the device and a stream are different reasons to draw the same row`() {
        assertTrue(ServedFormatClaim.mayShowStoredRow(evidence(bytes = StoredBytes(streamingCache = true))))
        assertTrue(ServedFormatClaim.mayShowStoredRow(evidence(streamingNow = true)))
        assertEquals(
            FormatRowEvidence.LOCAL_BYTES,
            evidence(bytes = StoredBytes(streamingCache = true)),
        )
    }

    @Test
    fun `a youtube row backed by the streaming cache is shown`() {
        // The store the streamed copy lives in still counts: the row describes real audio this
        // device can play, which is what the row is for. Leaving it out would hide truthful rows.
        val result = evidence(bytes = StoredBytes(streamingCache = true))
        assertEquals(FormatRowEvidence.LOCAL_BYTES, result)
        assertTrue(ServedFormatClaim.mayShowStoredRow(result))
    }
}
