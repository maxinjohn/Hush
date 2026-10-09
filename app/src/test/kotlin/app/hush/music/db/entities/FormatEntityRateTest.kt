package app.hush.music.db.entities

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A rate the player cannot render must not be rendered at all.
 *
 * The field is in bps and the rendering is in kbps, so any value below 1 kbps rounds to `0 kbps` - and
 * that is not a rate the player ever measured, it is a unit mistake that reached the field. One cached
 * file carried `705` (a nominal PCM figure stored in kbps) and the codec line read `0 kbps` for a
 * track that was decoding perfectly well.
 */
class FormatEntityRateTest {
    private fun row(bitrate: Int, sampleRate: Int? = null): FormatEntity =
        FormatEntity(
            id = "0tjVtYtZ384",
            itag = 251,
            mimeType = "audio/mp4",
            codecs = "mp4a.40.5",
            bitrate = bitrate,
            sampleRate = sampleRate,
            contentLength = 6_210_956L,
            loudnessDb = null,
            perceptualLoudnessDb = null,
            playbackUrl = null,
        )

    @Test
    fun `a real rate is rendered in kbps`() {
        assertEquals("48 kbps", row(48_017).formattedBitrate())
        assertEquals("1411 kbps", row(1_411_200).formattedBitrate())
    }

    @Test
    fun `an absent rate says nothing rather than zero`() {
        assertEquals("Unknown", row(0).formattedBitrate())
        // The value that was measured on the phone: kbps in a bps field.
        assertEquals("Unknown", row(705).formattedBitrate())
        assertEquals("Unknown", row(-1).formattedBitrate())
    }

    @Test
    fun `the automatic display falls back to the sample rate, not to a zero bitrate`() {
        assertEquals("44.1 kHz", row(705, sampleRate = 44_100).autoRateDisplay())
        assertEquals("Unknown", row(705, sampleRate = null).autoRateDisplay())
        assertEquals("44.1 kHz", row(705, sampleRate = 44_100).autoRateDisplay(RatePriority.SAMPLE_RATE_FIRST))
        // A rate that *can* be rendered is still preferred by the bitrate-first display.
        assertEquals("1411 kbps", row(1_411_200, sampleRate = 44_100).autoRateDisplay())
        assertEquals("44.1 kHz", row(1_411_200, sampleRate = 44_100).autoRateDisplay(RatePriority.SAMPLE_RATE_FIRST))
    }
}
