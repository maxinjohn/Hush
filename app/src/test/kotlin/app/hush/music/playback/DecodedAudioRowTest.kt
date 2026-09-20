package app.hush.music.playback

import app.hush.music.db.entities.FormatEntity
import app.hush.music.db.entities.containerLabel
import app.hush.music.db.entities.formattedFileSize
import app.hush.music.spotiflac.SpotiFLACServedRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The row exists so a track never renders an empty codec line. What the UI needs from it is exactly
 * [FormatEntity.mimeType] or [FormatEntity.codecs] being non-blank - the label is built from those two
 * and from nothing else - so the invariant asserted throughout is that pair, not the wording.
 */
class DecodedAudioRowTest {
    private fun row(
        sampleMimeType: String? = null,
        containerMimeType: String? = null,
        codecs: String? = null,
        bitrate: Int = -1,
        averageBitrate: Int = 0,
        sampleRate: Int = 0,
    ): FormatEntity =
        DecodedAudioRow.row(
            mediaId = "n5Eo9uCMijM",
            sampleMimeType = sampleMimeType,
            containerMimeType = containerMimeType,
            codecs = codecs,
            bitrate = bitrate,
            averageBitrate = averageBitrate,
            sampleRate = sampleRate,
        )

    /** The label the player's codec line ends up with, per the UI's own construction. */
    private fun uiLabel(entity: FormatEntity): String {
        val codec = entity.codecs.takeIf { it.isNotBlank() } ?: entity.containerLabel()
        val container = entity.containerLabel()
        return if (container.isNotBlank() && !codec.equals(container, ignoreCase = true)) {
            "$codec ($container)"
        } else {
            codec
        }
    }

    private fun assertNeverEmpty(entity: FormatEntity) {
        assertTrue(
            "a decoded row must give the line something to say",
            entity.mimeType.isNotBlank() || entity.codecs.isNotBlank(),
        )
        assertTrue("the line's label must not be blank", uiLabel(entity).isNotBlank())
    }

    @Test
    fun `a streamed YouTube format describes its codec, container and rate`() {
        val entity = row(sampleMimeType = "audio/webm", codecs = "opus", bitrate = 151_000)

        assertNeverEmpty(entity)
        assertEquals("audio/webm", entity.mimeType)
        assertEquals("opus", entity.codecs)
        assertEquals("WEBM", entity.containerLabel())
        assertEquals(151_000, entity.bitrate)
    }

    @Test
    fun `a container with no codec string still labels itself`() {
        val entity = row(sampleMimeType = "audio/mp4")

        assertNeverEmpty(entity)
        assertEquals("MP4", uiLabel(entity))
    }

    @Test
    fun `the container mime type is used when the sample mime is absent`() {
        val entity = row(containerMimeType = "audio/flac")

        assertNeverEmpty(entity)
        assertEquals("audio/flac", entity.mimeType)
        assertEquals("FLAC", uiLabel(entity))
    }

    @Test
    fun `a codec with no mime type still labels itself`() {
        val entity = row(codecs = "mp4a.40.2")

        assertNeverEmpty(entity)
        assertEquals("", entity.mimeType)
        assertEquals("mp4a.40.2", uiLabel(entity))
    }

    @Test
    fun `a format Media3 describes with nothing at all still says so`() {
        val entity = row()

        assertNeverEmpty(entity)
        assertEquals(DecodedAudioRow.UNKNOWN_MIME, entity.mimeType)
        assertEquals("UNKNOWN", uiLabel(entity))
    }

    @Test
    fun `average bitrate stands in when the format carries no bitrate`() {
        val entity = row(sampleMimeType = "audio/webm", bitrate = 0, averageBitrate = 128_000)

        assertEquals(128_000, entity.bitrate)
    }

    @Test
    fun `a missing sample rate is left absent rather than reported as zero`() {
        assertNull(row(sampleMimeType = "audio/webm", sampleRate = 0).sampleRate)
        assertEquals(44_100, row(sampleMimeType = "audio/webm", sampleRate = 44_100).sampleRate)
    }

    @Test
    fun `no size is claimed, because Media3's format has none`() {
        assertEquals("", row(sampleMimeType = "audio/webm", bitrate = 151_000).formattedFileSize())
    }

    @Test
    fun `the row can never be read as a SpotiFLAC lossless claim`() {
        val entity = row(sampleMimeType = "audio/webm", codecs = "opus")

        assertNotEquals(SpotiFLACServedRow.ITAG, entity.itag)
        assertTrue(DecodedAudioRow.isDecodedRow(entity))
        assertFalse(DecodedAudioRow.isDecodedRow(null))
        assertFalse(DecodedAudioRow.isDecodedRow(entity.copy(itag = SpotiFLACServedRow.ITAG)))
    }
}
