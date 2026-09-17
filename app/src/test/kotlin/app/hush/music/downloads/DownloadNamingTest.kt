package app.hush.music.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DownloadNamingTest {

    @Test
    fun `extension follows the container the source served`() {
        // These are the two YouTube audio containers Hush actually downloads: itag 140 is
        // audio/mp4 and itag 251 is audio/webm. Naming either of them for the other is how
        // a playable file comes to look corrupt.
        assertEquals("m4a", DownloadNaming.extensionForMimeType("audio/mp4; codecs=\"mp4a.40.2\""))
        assertEquals("webm", DownloadNaming.extensionForMimeType("audio/webm; codecs=\"opus\""))
        assertEquals("mp3", DownloadNaming.extensionForMimeType("audio/mpeg"))
        assertEquals("flac", DownloadNaming.extensionForMimeType("audio/flac"))
    }

    @Test
    fun `unknown mime type still names the file after the source`() {
        // A wrong extension reads as corruption, so an unfamiliar type falls back to its own
        // subtype rather than to a container it is not.
        assertEquals("x-whatever", DownloadNaming.extensionForMimeType("audio/x-whatever"))
        assertEquals("aiff", DownloadNaming.extensionForMimeType("audio/aiff"))
    }

    @Test
    fun `missing mime type falls back without inventing a format`() {
        assertEquals("audio", DownloadNaming.extensionForMimeType(null))
        assertEquals("audio", DownloadNaming.extensionForMimeType(""))
        assertEquals("audio", DownloadNaming.extensionForMimeType("audio/"))
    }

    @Test
    fun `artist and title are joined into one name`() {
        assertEquals(
            "A. R. Rahman - Kun Fayakun.flac",
            DownloadNaming.fileName(
                title = "Kun Fayakun",
                artist = "A. R. Rahman",
                extension = "flac",
            ),
        )
    }

    @Test
    fun `a missing artist does not produce a dangling separator`() {
        assertEquals("Kun Fayakun.m4a", DownloadNaming.fileName("Kun Fayakun", null, "m4a"))
        assertEquals("Kun Fayakun.m4a", DownloadNaming.fileName("Kun Fayakun", "", "m4a"))
        assertEquals("Kun Fayakun.m4a", DownloadNaming.fileName("Kun Fayakun", "   ", "m4a"))
    }

    @Test
    fun `illegal characters cannot escape the folder or split the name`() {
        // A slash would silently create a directory, and `..` would move the file out of the
        // download folder entirely, so both have to be neutralised rather than kept.
        val name = DownloadNaming.fileName("AC/DC: Back?in*Black", "Rock\\Band", "flac")
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertFalse(name.contains(':'))
        assertFalse(name.contains('?'))
        assertFalse(name.contains('*'))
        assertEquals("Rock_Band - AC_DC_ Back_in_Black.flac", name)
    }

    @Test
    fun `illegal characters are replaced rather than dropped so names stay distinct`() {
        assertFalse(
            DownloadNaming.sanitizeComponent("AC/DC") == DownloadNaming.sanitizeComponent("ACDC"),
        )
    }

    @Test
    fun `trailing dots and spaces are trimmed`() {
        // Some filesystems discard them, which would make the recorded path disagree with the
        // file that actually exists.
        assertEquals("Song", DownloadNaming.sanitizeComponent("Song..."))
        assertEquals("Song", DownloadNaming.sanitizeComponent("  Song   "))
        assertEquals("Song", DownloadNaming.sanitizeComponent("Song . "))
    }

    @Test
    fun `control characters are removed and whitespace collapses`() {
        assertEquals("A B", DownloadNaming.sanitizeComponent("A\u0000\u0007   B"))
    }

    @Test
    fun `an empty or unusable field becomes a placeholder instead of an empty name`() {
        assertEquals("Unknown", DownloadNaming.sanitizeComponent(""))
        assertEquals("Unknown", DownloadNaming.sanitizeComponent(null))
        assertEquals("Unknown", DownloadNaming.sanitizeComponent("///"))
    }

    @Test
    fun `a very long title is capped`() {
        val capped = DownloadNaming.sanitizeComponent("x".repeat(500))
        assertEquals(80, capped.length)
    }

    @Test
    fun `extensions are normalised`() {
        assertEquals("flac", DownloadNaming.sanitizeExtension(".FLAC"))
        assertEquals("m4a", DownloadNaming.sanitizeExtension("m4a"))
        assertEquals("audio", DownloadNaming.sanitizeExtension(""))
        assertEquals("audio", DownloadNaming.sanitizeExtension("???"))
    }

    @Test
    fun `an unused name is returned unchanged`() {
        assertEquals("Song.flac", DownloadNaming.uniqueFileName("Song.flac") { false })
    }

    @Test
    fun `colliding names get a numbered suffix`() {
        val taken = setOf("Song.flac", "Song (2).flac")
        assertEquals("Song (3).flac", DownloadNaming.uniqueFileName("Song.flac") { it in taken })
    }

    @Test
    fun `collision handling keeps the extension in place`() {
        assertEquals("Song (2).flac", DownloadNaming.uniqueFileName("Song.flac") { it == "Song.flac" })
        // A name with no extension must not gain one.
        assertEquals("Song (2)", DownloadNaming.uniqueFileName("Song") { it == "Song" })
        // A leading dot is part of the name, not an extension separator.
        assertEquals(".hidden (2)", DownloadNaming.uniqueFileName(".hidden") { it == ".hidden" })
    }

    @Test
    fun `unique naming terminates even when everything is taken`() {
        val name = DownloadNaming.uniqueFileName("Song.flac") { true }
        assertTrue(name.startsWith("Song ("))
        assertTrue(name.endsWith(".flac"))
    }

    @Test
    fun `the extension follows the bytes, not the name the file was given`() {
        // Measured on device: a track whose source had no lossless match came back as MP4/AAC
        // but was written as `.flac`, and every player read the result as corrupt. The header
        // is the only evidence that cannot be wrong about what a file contains.
        assertEquals("flac", extensionOf(bytes = "fLaC\u0000\u0000\u0000\u0022", named = ".media"))
        assertEquals(
            "m4a",
            extensionOf(bytes = "\u0000\u0000\u0000\u0018ftypmp42isom", named = ".flac"),
        )
        assertEquals("webm", extensionOf(bytes = "\u001aEß£\u0001\u0000\u0000\u0000", named = ".flac"))
        assertEquals("ogg", extensionOf(bytes = "OggS\u0000\u0002\u0000\u0000", named = ".media"))
        assertEquals("wav", extensionOf(bytes = "RIFF\u0000\u0000\u0000\u0000WAVE", named = ".media"))
        // ID3-tagged MP3, and the bare MPEG frame sync the same files carry mid-stream.
        assertEquals("mp3", extensionOf(bytes = "ID3\u0004\u0000\u0000", named = ".flac"))
        assertEquals("mp3", extensionOf(bytes = "\u00ff\u00fb\u0090\u0064", named = ".flac"))
        // ADTS AAC: same 0xFFFx sync, but layer bits 00 mark it as AAC rather than MPEG audio.
        assertEquals("aac", extensionOf(bytes = "\u00ff\u00f1\u0050\u0080", named = ".flac"))
    }

    @Test
    fun `an unreadable header never invents a container`() {
        // The runtime writes `.media` before it knows what it produced. Trusting that name
        // would put `.media` on a finished download; guessing `.flac` is the bug this fixes.
        assertEquals("audio", extensionOf(bytes = "not-a-container!", named = ".media"))
        assertEquals("audio", extensionOf(bytes = "", named = ".media"))
    }

    @Test
    fun `a known container name is the fallback when the header is not recognised`() {
        assertEquals("opus", extensionOf(bytes = "?????", named = ".opus"))
    }

    /** Writes [bytes] into a temp file named for [named] and asks for its extension. */
    private fun extensionOf(
        bytes: String,
        named: String,
    ): String {
        val file = File.createTempFile("naming", named)
        try {
            file.writeBytes(bytes.toByteArray(Charsets.ISO_8859_1))
            return DownloadNaming.extensionForFile(file)
        } finally {
            file.delete()
        }
    }
}
