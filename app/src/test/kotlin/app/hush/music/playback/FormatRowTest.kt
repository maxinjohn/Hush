package app.hush.music.playback

import app.hush.music.db.entities.FormatEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The row must be able to name its own evidence, and [FormatRowEvidence.NOTHING] must never be able to
 * name one - that is the state that means "there is nothing to show", and it is what routes the player
 * to the audio Media3 is decoding instead.
 */
class FormatRowTest {
    private val format =
        FormatEntity(
            id = "n5Eo9uCMijM",
            itag = 251,
            mimeType = "audio/webm",
            codecs = "opus",
            bitrate = 151_000,
            sampleRate = 48_000,
            contentLength = 3_000_000,
            loudnessDb = null,
            perceptualLoudnessDb = null,
            playbackUrl = null,
        )

    @Test
    fun `a resolve in this session is named as such`() {
        assertEquals(
            FormatRowSource.SESSION_RESOLVE,
            FormatRowEvidence.SERVED_NOW.toFormatRowSource(StoredBytes()),
        )
    }

    @Test
    fun `a download behind the row is named as a file on this device`() {
        assertEquals(
            FormatRowSource.DEVICE_FILE,
            FormatRowEvidence.LOCAL_BYTES.toFormatRowSource(StoredBytes(userDownload = true)),
        )
    }

    @Test
    fun `a cached playback file behind the row is named as a file on this device`() {
        assertEquals(
            FormatRowSource.DEVICE_FILE,
            FormatRowEvidence.LOCAL_BYTES.toFormatRowSource(StoredBytes(spotiflacCacheFile = true)),
        )
    }

    @Test
    fun `a streamed copy behind the row is named as an earlier resolve, not a file`() {
        assertEquals(
            FormatRowSource.SAVED_RESOLVE,
            FormatRowEvidence.LOCAL_BYTES.toFormatRowSource(StoredBytes(streamingCache = true)),
        )
    }

    @Test
    fun `an earlier resolve's saved row is named as one`() {
        assertEquals(
            FormatRowSource.SAVED_RESOLVE,
            FormatRowEvidence.STREAMING_NOW.toFormatRowSource(StoredBytes()),
        )
    }

    @Test
    fun `having nothing to show names no source at all`() {
        assertNull(FormatRowEvidence.NOTHING.toFormatRowSource(StoredBytes(streamingCache = true)))
    }

    /**
     * The invariant behind "the row always names its evidence": every evidence except NOTHING has a
     * name, whatever the stores say, and NOTHING never does - because NOTHING is what routes the
     * player to the decoded audio, and a name there would describe bytes no store holds.
     */
    @Test
    fun `every evidence that can back a row names a source, and NOTHING never does`() {
        val stores =
            listOf(
                StoredBytes(),
                StoredBytes(userDownload = true),
                StoredBytes(userDownload = true, downloadIsSpotiFLAC = true),
                StoredBytes(spotiflacCacheFile = true),
                StoredBytes(streamingCache = true),
                StoredBytes(userDownload = true, spotiflacCacheFile = true, streamingCache = true),
            )

        for (evidence in FormatRowEvidence.entries) {
            for (bytes in stores) {
                val source = evidence.toFormatRowSource(bytes)
                if (evidence == FormatRowEvidence.NOTHING) {
                    assertNull("NOTHING backed by $bytes", source)
                } else {
                    assertNotNull("$evidence backed by $bytes", source)
                }
            }
        }
    }

    @Test
    fun `a resolve that served a download is named as the file, not as the resolve`() {
        // The case measured on the phone: the URL resolves, then the downloaded file is served.
        assertEquals(
            FormatRowSource.DEVICE_FILE,
            servedRowSource(StoredBytes(userDownload = true)),
        )
    }

    @Test
    fun `a resolve that served a spotiflac playback file is named as the file`() {
        assertEquals(
            FormatRowSource.DEVICE_FILE,
            servedRowSource(StoredBytes(spotiflacCacheFile = true)),
        )
    }

    @Test
    fun `a resolve that served a stream is named as the resolve`() {
        assertEquals(FormatRowSource.SESSION_RESOLVE, servedRowSource(StoredBytes()))
        // Media3's cache fills while a stream plays, and it must not promote that to a device file.
        assertEquals(
            FormatRowSource.SESSION_RESOLVE,
            servedRowSource(StoredBytes(streamingCache = true)),
        )
    }

    @Test
    fun `a row carries its format and its source together`() {
        val row = FormatRow(format, FormatRowSource.DECODED_AUDIO)

        assertEquals(format, row.format)
        assertEquals(FormatRowSource.DECODED_AUDIO, row.source)
    }
}
