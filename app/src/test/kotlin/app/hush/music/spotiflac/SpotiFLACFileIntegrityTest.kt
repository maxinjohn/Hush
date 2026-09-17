/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import app.hush.music.spotiflac.SpotiFLACFileIntegrity.Action
import app.hush.music.spotiflac.SpotiFLACFileIntegrity.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotiFLACFileIntegrityTest {
    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private val flacHead = "fLaC".toByteArray() + ByteArray(12)
    private val oggHead = "OggS".toByteArray() + ByteArray(12)
    private val id3Head = "ID3".toByteArray() + ByteArray(13)
    private val m4aHead = bytes(0, 0, 0, 0x20) + "ftypM4A ".toByteArray()
    private val mp3FrameHead = bytes(0xFF, 0xFB, 0x90, 0x00)
    private val webmHead = bytes(0x1A, 0x45, 0xDF, 0xA3, 0x01, 0x00)

    @Test
    fun `every container the sources return is recognised`() {
        assertEquals("flac", SpotiFLACFileIntegrity.containerOf(flacHead))
        assertEquals("ogg", SpotiFLACFileIntegrity.containerOf(oggHead))
        assertEquals("mp3", SpotiFLACFileIntegrity.containerOf(id3Head))
        assertEquals("mpeg", SpotiFLACFileIntegrity.containerOf(mp3FrameHead))
        assertEquals("m4a", SpotiFLACFileIntegrity.containerOf(m4aHead))
        assertEquals("webm", SpotiFLACFileIntegrity.containerOf(webmHead))
        assertNull(SpotiFLACFileIntegrity.containerOf(bytes(0x00, 0x11, 0x22)))
    }

    @Test
    fun `a matching size and signature is playable`() {
        assertEquals(
            Verdict.OK,
            SpotiFLACFileIntegrity.verdict(
                fileLength = 35_722_757L,
                recordedLength = 35_722_757L,
                head = flacHead,
            ),
        )
    }

    @Test
    fun `a file shorter than what was downloaded is truncated`() {
        // The realistic failure: the download was interrupted, so the file is a prefix
        // of the audio. It reads fine and the signature is correct, which is exactly why
        // a length check has to come before the signature check.
        assertEquals(
            Verdict.TRUNCATED,
            SpotiFLACFileIntegrity.verdict(
                fileLength = 1_048_576L,
                recordedLength = 35_722_757L,
                head = flacHead,
            ),
        )
    }

    @Test
    fun `a file that grew is still served`() {
        // Containers are sometimes rewritten to their real extension, so a size that no
        // longer matches exactly must not be treated as damage.
        assertEquals(
            Verdict.OK,
            SpotiFLACFileIntegrity.verdict(
                fileLength = 36_000_000L,
                recordedLength = 35_722_757L,
                head = flacHead,
            ),
        )
    }

    @Test
    fun `an error page written to the audio path is not audio`() {
        val html = "<!DOCTYPE html>\n<html>".toByteArray()
        assertEquals(
            Verdict.NOT_AUDIO,
            SpotiFLACFileIntegrity.verdict(
                fileLength = html.size.toLong(),
                recordedLength = 0L,
                head = html,
            ),
        )
        val json = """{"error":"session expired"}""".toByteArray()
        assertEquals(
            Verdict.NOT_AUDIO,
            SpotiFLACFileIntegrity.verdict(json.size.toLong(), 0L, json),
        )
    }

    @Test
    fun `an unrecorded size still gets the signature check`() {
        // Entries written before the size was recorded must not be trusted blindly.
        assertEquals(Verdict.OK, SpotiFLACFileIntegrity.verdict(1_000L, 0L, flacHead))
        assertEquals(Verdict.NOT_AUDIO, SpotiFLACFileIntegrity.verdict(1_000L, 0L, "no".toByteArray()))
    }

    @Test
    fun `a missing or unreadable file is missing, not not-audio`() {
        assertEquals(Verdict.MISSING, SpotiFLACFileIntegrity.verdict(0L, 0L, flacHead))
        assertEquals(Verdict.MISSING, SpotiFLACFileIntegrity.verdict(10L, 0L, null))
        assertEquals(Verdict.MISSING, SpotiFLACFileIntegrity.verdict(10L, 0L, ByteArray(0)))
        // MISSING is handled by adopting a sibling file, so it must stay distinct from
        // NOT_AUDIO, which discards the entry outright.
        assertTrue(Verdict.MISSING != Verdict.NOT_AUDIO)
    }

    @Test
    fun `a complete recognisable file is served`() {
        assertEquals(
            Action.SERVE,
            SpotiFLACFileIntegrity.actionFor(Verdict.OK, rewriting = false, pinned = false),
        )
    }

    @Test
    fun `being rewritten outranks every verdict`() {
        // The precedence that matters: a re-download writes over the same path as the
        // entry it replaces, so mid-write the index still records the previous, larger
        // size and the verdict really is TRUNCATED. Discarding on that would delete the
        // file an in-progress download is writing.
        val verdicts = Verdict.values().toList()
        verdicts.forEach { verdict ->
            assertEquals(
                "a file being rewritten must never be served or discarded (verdict=$verdict)",
                Action.RESOLVE,
                SpotiFLACFileIntegrity.actionFor(verdict, rewriting = true, pinned = false),
            )
        }
    }

    @Test
    fun `damage is discarded for a cached copy but kept for a user download`() {
        assertEquals(
            Action.DISCARD,
            SpotiFLACFileIntegrity.actionFor(Verdict.TRUNCATED, rewriting = false, pinned = false),
        )
        assertEquals(
            Action.DISCARD,
            SpotiFLACFileIntegrity.actionFor(Verdict.NOT_AUDIO, rewriting = false, pinned = false),
        )
        // A pinned entry is the user's file: playing it must not delete it.
        assertEquals(
            Action.SERVE_KEEP,
            SpotiFLACFileIntegrity.actionFor(Verdict.TRUNCATED, rewriting = false, pinned = true),
        )
    }

    @Test
    fun `a missing file resolves, so a sibling container can be adopted`() {
        // MISSING must not become DISCARD: the recorded path being gone usually means the
        // runtime wrote the real container beside it, and discarding would lose a valid
        // download instead of adopting it.
        assertEquals(
            Action.RESOLVE,
            SpotiFLACFileIntegrity.actionFor(Verdict.MISSING, rewriting = false, pinned = false),
        )
        assertTrue(Action.RESOLVE != Action.DISCARD)
    }

    @Test
    fun `a short head is never read past its end`() {
        assertFalse(SpotiFLACFileIntegrity.looksLikeAudio(ByteArray(0)))
        assertFalse(SpotiFLACFileIntegrity.looksLikeAudio(bytes(0x66, 0x4C))) // "fL"
        assertEquals("flac", SpotiFLACFileIntegrity.containerOf("fLaC".toByteArray()))
    }

    @Test
    fun `a Dolby stream is named from its bytes`() {
        // A bare AC-3/E-AC-3 sync frame: Amazon's extension can return this where a lossless
        // request was made, and it is the case that plays with no audio on a head unit.
        val dolbyFrame = bytes(0x0B, 0x77, 0x00, 0x00) + ByteArray(28)
        assertEquals("eac3", SpotiFLACFileIntegrity.dolbyFormatOf(dolbyFrame))

        // Inside an MP4 the codec is named by its sample entry, which sits past the container
        // signature - so the probe has to be read further than the head for this to be seen.
        fun mp4(sampleEntry: String): ByteArray {
            val head = bytes(0, 0, 0, 0x20) + "ftypM4A ".toByteArray()
            return head + ByteArray(16) + sampleEntry.toByteArray() + ByteArray(16)
        }
        assertEquals("ac4", SpotiFLACFileIntegrity.dolbyFormatOf(mp4("ac-4")))
        assertEquals("eac3", SpotiFLACFileIntegrity.dolbyFormatOf(mp4("ec-3")))
        assertEquals("eac3", SpotiFLACFileIntegrity.dolbyFormatOf(mp4("dec3")))
        assertEquals("ac3", SpotiFLACFileIntegrity.dolbyFormatOf(mp4("ac-3")))
    }

    @Test
    fun `ordinary audio is never mistaken for Dolby`() {
        // The rejection this feeds is what stops a silent download from being played, so a false
        // positive would throw away working lossless audio - the containers the sources return
        // have to pass.
        assertNull(SpotiFLACFileIntegrity.dolbyFormatOf(flacHead))
        assertNull(SpotiFLACFileIntegrity.dolbyFormatOf(oggHead))
        assertNull(SpotiFLACFileIntegrity.dolbyFormatOf(mp3FrameHead))
        assertNull(SpotiFLACFileIntegrity.dolbyFormatOf(webmHead))
        // An ordinary AAC-in-MP4 track is a valid answer to a lossy request.
        assertNull(SpotiFLACFileIntegrity.dolbyFormatOf(m4aHead))
        val aacInMp4 = bytes(0, 0, 0, 0x20) + "ftypM4A ".toByteArray() +
            ByteArray(16) + "mp4a".toByteArray() + ByteArray(16)
        assertNull(SpotiFLACFileIntegrity.dolbyFormatOf(aacInMp4))
        assertNull(SpotiFLACFileIntegrity.dolbyFormatOf(ByteArray(0)))
    }

    @Test
    fun `an encrypted payload is recognised and never mistaken for audio`() {
        // Amazon hands the runtime an encrypted ISO-BMFF stream plus a key. When the decryption does
        // not happen, the file is exactly the right size and has no decodable audio - the reported
        // "plays but silent" case - so this has to be seen rather than played.
        fun mp4(vararg boxes: String): ByteArray {
            val head = bytes(0, 0, 0, 0x20) + "ftypM4A ".toByteArray()
            return head + ByteArray(8) +
                boxes.joinToString("|") { it }.toByteArray() + ByteArray(16)
        }
        assertTrue(SpotiFLACFileIntegrity.isEncryptedStream(mp4("enca") + "sinf".toByteArray()))
        assertTrue(SpotiFLACFileIntegrity.isEncryptedStream(mp4("schm")))
        assertTrue(SpotiFLACFileIntegrity.isEncryptedStream(mp4("tenc")))

        // An encrypted payload is not audio: an entry cached before the download path learned to
        // check this must be discarded rather than served as silence on every replay.
        val encrypted = mp4("enca") + "sinf".toByteArray()
        assertFalse(SpotiFLACFileIntegrity.looksLikeAudio(encrypted))
        assertEquals(
            Verdict.NOT_AUDIO,
            SpotiFLACFileIntegrity.verdict(
                fileLength = 11_938_211L,
                recordedLength = 11_938_211L,
                head = encrypted,
            ),
        )
        assertEquals(
            Action.DISCARD,
            SpotiFLACFileIntegrity.actionFor(
                SpotiFLACFileIntegrity.verdict(11_938_211L, 11_938_211L, encrypted),
                rewriting = false,
                pinned = false,
            ),
        )

        // An ordinary, decrypted MP4 must not be caught by it: that would reject every good download.
        val plain = bytes(0, 0, 0, 0x20) + "ftypM4A ".toByteArray() +
            ByteArray(16) + "mp4a".toByteArray() + ByteArray(16)
        assertFalse(SpotiFLACFileIntegrity.isEncryptedStream(plain))
        assertFalse(SpotiFLACFileIntegrity.isEncryptedStream(flacHead))
        // The check is a property of the ISO-BMFF head, so a non-MP4 is never "encrypted".
        assertFalse(SpotiFLACFileIntegrity.isEncryptedStream(oggHead))
        assertFalse(SpotiFLACFileIntegrity.isEncryptedStream(ByteArray(0)))
    }

    @Test
    fun `a runtime-reported codec name is read too`() {
        assertEquals("ac4", SpotiFLACFileIntegrity.dolbyFormatOfCodecName("ac-4"))
        assertEquals("ac4", SpotiFLACFileIntegrity.dolbyFormatOfCodecName("AC4"))
        assertEquals("eac3", SpotiFLACFileIntegrity.dolbyFormatOfCodecName("ec-3"))
        assertEquals("eac3", SpotiFLACFileIntegrity.dolbyFormatOfCodecName("eac3"))
        assertEquals("ac3", SpotiFLACFileIntegrity.dolbyFormatOfCodecName("ac3"))
        assertNull(SpotiFLACFileIntegrity.dolbyFormatOfCodecName("flac"))
        assertNull(SpotiFLACFileIntegrity.dolbyFormatOfCodecName("opus"))
        assertNull(SpotiFLACFileIntegrity.dolbyFormatOfCodecName(null))
    }
}
