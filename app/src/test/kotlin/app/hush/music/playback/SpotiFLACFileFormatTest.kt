package app.hush.music.playback

import app.hush.music.db.entities.FormatEntity
import app.hush.music.db.entities.codecLabel
import app.hush.music.db.entities.containerLabel
import app.hush.music.db.entities.formattedBitrate
import app.hush.music.spotiflac.SpotiFLACServedRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The row for a file this device serves must be made of facts the device can check.
 *
 * The values here are the ones measured on a device whose codec row read `MEDIA - 0 kbps` for a cached
 * track: the playback cache writes every file as `<key>.media` (so `audio/media` was never a
 * container), the entry's AAC rate was `44100` for one file and `22050` for the next, three FLAC
 * entries claimed `29539`, and Media3 decoded the file as `48_017 bps / 44_100 Hz`. The invariant
 * asserted throughout is that the row names the container, the codec and the size, and that any rate
 * in it came from the decoder rather than from those figures.
 */
class SpotiFLACFileFormatTest {
    private fun row(
        codec: String? = "aac",
        extension: String? = "media",
        sizeBytes: Long = 6_210_956L,
    ): FormatEntity =
        SpotiFLACFileFormat.row(
            mediaId = "0tjVtYtZ384",
            codec = codec,
            extension = extension,
            sizeBytes = sizeBytes,
        )

    private fun decoded(
        bitrate: Int = 48_017,
        sampleRate: Int? = 44_100,
    ): FormatEntity =
        DecodedAudioRow.row(
            mediaId = "0tjVtYtZ384",
            sampleMimeType = "audio/mp4a-latm",
            containerMimeType = null,
            codecs = "mp4a.40.5",
            bitrate = bitrate,
            averageBitrate = 0,
            sampleRate = sampleRate ?: 0,
        )

    @Test
    fun `a cached aac file is named by its codec, not by its file extension`() {
        val entity = row(codec = "aac", extension = "media")

        assertEquals("audio/mp4", entity.mimeType)
        assertEquals("MP4", entity.containerLabel())
        assertEquals("AAC", entity.codecLabel())
        assertNotEquals("MEDIA", entity.containerLabel())
    }

    @Test
    fun `every codec the cache holds gets a container of its own`() {
        val expected =
            mapOf(
                "aac" to "audio/mp4",
                "opus" to "audio/ogg",
                "flac" to "audio/flac",
                "mp3" to "audio/mpeg",
            )

        for ((codec, mime) in expected) {
            val entity = row(codec = codec)
            assertEquals("container for $codec", mime, entity.mimeType)
            assertNotEquals("label for $codec", "MEDIA", entity.containerLabel())
            assertTrue("label for $codec", entity.containerLabel().isNotBlank())
        }
    }

    @Test
    fun `the cache's own extension can never be read as a container`() {
        assertEquals("audio/flac", SpotiFLACFileFormat.containerMime("flac", "media"))
        assertEquals("audio/mp4", SpotiFLACFileFormat.containerMime("aac", "media"))
        // No codec at all, and still nothing that names the file's extension a format.
        assertEquals("audio/mp4", SpotiFLACFileFormat.containerMime(null, "media"))
        assertNotEquals("MEDIA", row(codec = null).containerLabel())
    }

    @Test
    fun `a container is still recognised from a file's own extension`() {
        assertEquals("audio/flac", SpotiFLACFileFormat.containerMime(null, "flac"))
        assertEquals("audio/ogg", SpotiFLACFileFormat.containerMime(null, "opus"))
        assertEquals("audio/mpeg", SpotiFLACFileFormat.containerMime(null, "MP3"))
    }

    @Test
    fun `the row states the file's own size and its spotiflac provenance`() {
        val entity = row(sizeBytes = 23_152_218L)

        assertEquals(23_152_218L, entity.contentLength)
        assertEquals(SpotiFLACServedRow.ITAG, entity.itag)
        assertEquals("0tjVtYtZ384", entity.id)
    }

    /**
     * The complaint this row was fixed for: a rate nobody measured, rendered as `0 kbps`. The row says
     * nothing about the rate instead, and the player omits an `Unknown` rate rather than printing it.
     */
    @Test
    fun `no rate is claimed for a file nothing measured`() {
        val entity = row()

        assertEquals(0, entity.bitrate)
        assertNull(entity.sampleRate)
        assertEquals("Unknown", entity.formattedBitrate())
    }

    @Test
    fun `a missing codec still leaves the line readable`() {
        val entity = row(codec = null)

        assertEquals("", entity.codecs)
        assertEquals("MP4", entity.containerLabel())
    }

    @Test
    fun `the decoder's own rate fills the gap a file row cannot`() {
        val entity = DecodedAudioRow.withDecodedRate(row(), decoded())

        assertEquals(48_017, entity.bitrate)
        assertEquals(44_100, entity.sampleRate)
        // Only the rate changes: the row still names the same file, the same way.
        assertEquals("audio/mp4", entity.mimeType)
        assertEquals("aac", entity.codecs)
        assertEquals(6_210_956L, entity.contentLength)
        assertEquals(SpotiFLACServedRow.ITAG, entity.itag)
    }

    @Test
    fun `a decoder that reported no rate leaves the row exactly as it was`() {
        val before = row()

        assertSame(before, DecodedAudioRow.withDecodedRate(before, null))
        assertSame(before, DecodedAudioRow.withDecodedRate(before, decoded(bitrate = 0, sampleRate = null)))
    }

    @Test
    fun `a rate the decoder did not report does not erase one the row already had`() {
        val streamed =
            FormatEntity(
                id = "0tjVtYtZ384",
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

        val entity = DecodedAudioRow.withDecodedRate(streamed, decoded(bitrate = 0, sampleRate = null))

        assertEquals(151_000, entity.bitrate)
        assertEquals(48_000, entity.sampleRate)
    }
}
