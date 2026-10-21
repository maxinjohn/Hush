/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.db.entities

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tail of the codec line, built once.
 *
 * The line is a bitrate plus a second string appended after it, and that second string used to be
 * assembled at each layout: most passed `sample rate • size`, while the strip under Aura's controls -
 * the only design this app ships - passed the size alone. A FLAC file served from this device has no
 * bitrate Media3 can report, so that strip drew `24 MB` and nothing else while the decoder had
 * already measured `44100`. Measured on the reporting device through the player's own row log:
 * `codec row mediaId=BEhJ0uKOp2w source=DEVICE_FILE mime=audio/flac bitrate=0 sr=44100 size=25137319`,
 * drawn on screen as `24 MB • 3rd-party • Deezer (fetched)`.
 *
 * These cases pin the tail's contents and its order, which is the whole point of it being one
 * function: two layouts cannot disagree about what a row can say.
 */
class CodecRowDetailTest {

    private fun row(
        bitrate: Int = 0,
        sampleRate: Int? = null,
        contentLength: Long = 0L,
    ): FormatEntity =
        FormatEntity(
            id = "BEhJ0uKOp2w",
            itag = 0,
            mimeType = "audio/flac",
            codecs = "flac",
            bitrate = bitrate,
            sampleRate = sampleRate,
            contentLength = contentLength,
            loudnessDb = null,
            perceptualLoudnessDb = null,
            playbackUrl = null,
        )

    @Test
    fun `a device file says its sample rate before its size`() {
        // The row that was drawn as `24 MB`: the rate was there and never printed.
        assertEquals("44.1 kHz • 24 MB", row(sampleRate = 44_100, contentLength = 25_137_319L).codecRowDetail())
    }

    @Test
    fun `a rate with no size still speaks`() {
        // A stream the decoder measured but whose length has not arrived yet.
        assertEquals("44.1 kHz", row(sampleRate = 44_100).codecRowDetail())
    }

    @Test
    fun `a size with no rate still speaks, and says only what it knows`() {
        assertEquals("24 MB", row(contentLength = 25_137_319L).codecRowDetail())
    }

    @Test
    fun `a row with neither fact adds nothing to the line`() {
        // No dangling separator: the caller joins this with " • " against the bitrate.
        assertEquals("", row().codecRowDetail())
        assertEquals("", row(sampleRate = 0, contentLength = 0L).codecRowDetail())
    }

    @Test
    fun `a sample rate rounds to the tenth every surface already shows`() {
        assertEquals("22.1 kHz", row(sampleRate = 22_050).codecRowDetail())
        assertEquals("48.0 kHz", row(sampleRate = 48_000).codecRowDetail())
    }
}
