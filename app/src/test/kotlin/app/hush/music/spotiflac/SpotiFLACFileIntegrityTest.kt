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
}
