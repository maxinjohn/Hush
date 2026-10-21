package app.hush.music.playback

import app.hush.music.db.entities.formattedBitrate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A device-file FLAC must be able to state its own rate.
 *
 * The case this exists for was measured on a phone: a 24-bit FLAC served off disk drew
 * `FLAC - Unknown` on the codec row, because Media3's FLAC extractor reports no bitrate and the row
 * that describes a cached file deliberately carried none. The two rates that had been tried before
 * were both wrong - the cache entry's `sampleRate` (three entries claimed `29539`) and
 * `sampleRate * bitDepth * 2`, a decode rate that a 24/96 file cannot be compressed to.
 *
 * The numbers below are built by writing a real header, so what is asserted is the parse and the
 * arithmetic rather than a constant: the file states its sample count, and the rate is the audio bytes
 * over the duration that count implies.
 */
class FlacFileRateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** A FLAC file with a real `STREAMINFO` block, [audioBytes] of audio, and optional padding. */
    private fun flacFile(
        totalSamples: Long,
        sampleRate: Int = 44_100,
        bitsPerSample: Int = 16,
        channels: Int = 2,
        audioBytes: Int = 1_000_000,
        paddingBytes: Int = 0,
        name: String = "track.flac",
    ): File {
        val out = File(tmp.root, name)
        val bytes = ArrayList<Byte>(64)
        bytes.addAll("fLaC".toByteArray().toList())
        // STREAMINFO is first; it is the last block only when nothing follows it.
        bytes.add((if (paddingBytes > 0) 0x00 else 0x80).toByte())
        bytes.addAll(byteArrayOf(0x00, 0x00, 0x22).toList())
        // Two 16-bit block sizes, two 24-bit frame sizes: fixed at a realistic 4096 / 12 bytes.
        bytes.addAll(byteArrayOf(0x10, 0x00, 0x10, 0x00).toList())
        bytes.addAll(byteArrayOf(0x00, 0x00, 0x0C, 0x00, 0x00, 0x0C).toList())
        val packed =
            ((sampleRate.toLong() and 0xFFFFF) shl 44) or
                (((channels - 1).toLong() and 0x7) shl 41) or
                (((bitsPerSample - 1).toLong() and 0x1F) shl 36) or
                (totalSamples and 0xFFFFFFFFFL)
        for (shift in 56 downTo 0 step 8) {
            bytes.add(((packed ushr shift) and 0xFF).toByte())
        }
        repeat(16) { bytes.add(0) } // MD5 of the unencoded audio, unused here.
        if (paddingBytes > 0) {
            bytes.add(0x81.toByte()) // last block, type 1 (padding)
            bytes.add(((paddingBytes ushr 16) and 0xFF).toByte())
            bytes.add(((paddingBytes ushr 8) and 0xFF).toByte())
            bytes.add((paddingBytes and 0xFF).toByte())
            repeat(paddingBytes) { bytes.add(0) }
        }
        out.writeBytes(bytes.toByteArray() + ByteArray(audioBytes))
        return out
    }

    @Test
    fun `a cd-quality file reports the rate its own bytes are served at`() {
        // 180 seconds, 30 MB of audio: 30_000_000 x 8 / 180 = 1_333_333 bps.
        val file = flacFile(totalSamples = 44_100L * 180L, audioBytes = 30_000_000)

        assertEquals(1_333_333, FlacFileRate.bitrateBpsOf(file))
    }

    @Test
    fun `a 24 bit file is not credited with its decode rate`() {
        val file =
            flacFile(
                totalSamples = 96_000L * 120L,
                sampleRate = 96_000,
                bitsPerSample = 24,
                audioBytes = 45_000_000,
            )

        // 45 MB over 120 s = 3_000 kbps; the nominal decode rate would have said 4_608 kbps.
        assertEquals(3_000_000, FlacFileRate.bitrateBpsOf(file))
        assertNotEquals(96_000 * 24 * 2, FlacFileRate.bitrateBpsOf(file) ?: 0)
    }

    @Test
    fun `embedded artwork is not counted as audio`() {
        val plain = flacFile(totalSamples = 44_100L * 60L, audioBytes = 10_000_000, name = "a.flac")
        val withArt =
            flacFile(
                totalSamples = 44_100L * 60L,
                audioBytes = 10_000_000,
                paddingBytes = 400_000,
                name = "b.flac",
            )

        assertTrue("the padded file is genuinely larger", withArt.length() > plain.length() + 399_000L)
        assertEquals(FlacFileRate.bitrateBpsOf(plain), FlacFileRate.bitrateBpsOf(withArt))
    }

    @Test
    fun `the header's own fields are read back`() {
        val info = FlacFileRate.streamInfoOf(flacFile(totalSamples = 48_000L * 10L, sampleRate = 48_000))

        assertEquals(48_000, info?.sampleRate)
        assertEquals(2, info?.channels)
        assertEquals(16, info?.bitsPerSample)
        assertEquals(48_000L * 10L, info?.totalSamples)
        assertEquals(10_000L, info?.durationMs)
    }

    @Test
    fun `a stream that does not state its sample count has no rate`() {
        assertNull(FlacFileRate.bitrateBpsOf(flacFile(totalSamples = 0L)))
    }

    @Test
    fun `a file that is not a flac is not read as one`() {
        val notFlac = File(tmp.root, "song.mp3")
        notFlac.writeBytes(ByteArray(4096) { 0x41 })

        assertNull(FlacFileRate.streamInfoOf(notFlac))
        assertNull(FlacFileRate.bitrateBpsOf(notFlac))
    }

    @Test
    fun `a file too short to hold a header is refused rather than guessed at`() {
        val stub = File(tmp.root, "stub.flac")
        stub.writeBytes("fLaC".toByteArray())

        assertNull(FlacFileRate.streamInfoOf(stub))
        assertNull(FlacFileRate.parseStreamInfoBody(ByteArray(12)))
    }

    @Test
    fun `a missing file is a reason to show no rate, not an error`() {
        assertNull(FlacFileRate.bitrateBpsOf(File(tmp.root, "absent.flac")))
        assertNull(FlacFileRate.bitrateBpsOf(tmp.root))
    }

    @Test
    fun `the row for a flac file states the rate the file measured`() {
        val file = flacFile(totalSamples = 44_100L * 180L, audioBytes = 30_000_000)

        val row = SpotiFLACFileFormat.row("mediaId", codec = "flac", extension = "media", sizeBytes = file.length(), file = file)

        assertEquals(1_333_333, row.bitrate)
        assertEquals("1333 kbps", row.formattedBitrate())
        // The container is still the one the codec names, not the cache's own extension.
        assertEquals("audio/flac", row.mimeType)
    }

    @Test
    fun `a row for a container whose header says nothing keeps no rate`() {
        val file = flacFile(totalSamples = 44_100L * 180L, audioBytes = 30_000_000)

        val row = SpotiFLACFileFormat.row("mediaId", codec = "aac", extension = "media", sizeBytes = file.length(), file = file)

        assertEquals(0, row.bitrate)
        assertEquals("Unknown", row.formattedBitrate())
    }

    @Test
    fun `a row built without the file behaves exactly as before`() {
        val row = SpotiFLACFileFormat.row("mediaId", codec = "flac", extension = "media", sizeBytes = 30_000_000L)

        assertEquals(0, row.bitrate)
        assertNull(row.sampleRate)
    }
}
