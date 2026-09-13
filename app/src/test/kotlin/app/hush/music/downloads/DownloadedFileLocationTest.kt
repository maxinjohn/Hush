package app.hush.music.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The downloads folder is a setting the user can change, so a download record has to stay
 * valid across a move. These tests pin the property that made the difference: a record is
 * identified by its name inside whichever folder is current, not by where it was written.
 */
class DownloadedFileLocationTest {

    @Test
    fun `the recorded name is preferred over the recorded path`() {
        val entry =
            entry(
                fileName = "Marino - Rip Out Your Eyes.flac",
                path = "/old/place/Marino - Rip Out Your Eyes.flac",
            )
        assertEquals("Marino - Rip Out Your Eyes.flac", DownloadedFileLocation.storedName(entry))
    }

    @Test
    fun `a record written before the index became relative is named from its path`() {
        // Records on disk from an older build have no `fileName`; the file name of the
        // absolute path is the same name, so such a record is repaired, not discarded.
        val legacy = entry(fileName = "", path = "/storage/emulated/0/Download/Hush/Downloads/Song.flac")
        assertEquals("Song.flac", DownloadedFileLocation.storedName(legacy))
    }

    @Test
    fun `a record with no name and no path has no location`() {
        val empty = entry(fileName = "", path = "")
        assertNull(DownloadedFileLocation.storedName(empty))
        assertNull(DownloadedFileLocation.resolve(dir("nowhere"), empty))
    }

    @Test
    fun `a download is still found after the downloads folder is changed`() {
        // This is the regression: the audio moves with the setting, so a record that only
        // remembered where the file used to be was dropped on load - the badge disappeared,
        // playback went back to the network, and removal could no longer delete the file.
        val before = writeFile(dir("before"), "Zavyre - Don't Let Go.flac", 16_820_523L)
        val entry =
            entry(
                fileName = "Zavyre - Don't Let Go.flac",
                path = before.absolutePath,
                bytes = 16_820_523L,
            )

        val after = moveInto(dir("after"), before)

        assertEquals(after, DownloadedFileLocation.resolve(after.parentFile, entry))
        assertNull(DownloadedFileLocation.resolve(before.parentFile, entry))
    }

    @Test
    fun `a legacy record is found at its own path when the folder did not change`() {
        // `fileName` is empty here, so resolution has to fall back to `path` rather than
        // treat the record as lost.
        val file = writeFile(dir("in-place"), "Track.flac", 1_024L)
        val legacy = entry(fileName = "", path = file.absolutePath, bytes = 1_024L)

        assertEquals(file, DownloadedFileLocation.resolve(dir("somewhere-else"), legacy))
    }

    @Test
    fun `a same-named file of a different length is not adopted`() {
        // Adoption is by name, and a picked folder can already hold files of its own. The
        // recorded byte count is what keeps someone else's `Track.flac` from being served
        // as this download.
        val stranger = writeFile(dir("elsewhere"), "Track.flac", 4_096L)
        val record = entry(fileName = "Track.flac", path = "/gone/Track.flac", bytes = 1_024L)

        assertNull(DownloadedFileLocation.resolve(stranger.parentFile, record))
    }

    @Test
    fun `a record with no known length is adopted by name`() {
        // Older entries may not carry a byte count; refusing them would lose real downloads.
        val file = writeFile(dir("unmeasured"), "Track.flac", 2_048L)
        val record = entry(fileName = "Track.flac", path = "/gone/Track.flac", bytes = 0L)

        assertEquals(file, DownloadedFileLocation.resolve(file.parentFile, record))
    }

    @Test
    fun `an empty file is not a download`() {
        val file = writeFile(dir("truncated"), "Track.flac", 0L)
        val record = entry(fileName = "Track.flac", path = file.absolutePath, bytes = 0L)

        assertNull(DownloadedFileLocation.resolve(file.parentFile, record))
    }

    @Test
    fun `every download survives a folder change together`() {
        // The whole point, stated as the user experiences it: move the folder, keep the
        // library. A partial answer here would show as some badges silently disappearing.
        val before = dir("library-before")
        val after = dir("library-after")
        val names = listOf("A.flac", "B.flac", "C - spaced (From _Film_).flac")
        val entries =
            names.mapIndexed { index, name ->
                val written = writeFile(before, name, 1_000L + index)
                moveInto(after, written)
                entry(fileName = name, path = written.absolutePath, bytes = 1_000L + index)
            }

        val resolved = entries.map { DownloadedFileLocation.resolve(after, it) }

        assertEquals(names.size, resolved.count { it != null })
        assertEquals(names, resolved.map { it?.name })
    }

    private fun entry(
        fileName: String,
        path: String,
        bytes: Long = 0L,
    ) = DownloadedFile(
        mediaId = "media-id",
        path = path,
        origin = "SPOTIFLAC",
        fileName = fileName,
        bytes = bytes,
    )

    private fun dir(name: String): File =
        File(
            File(System.getProperty("java.io.tmpdir"), "downloads-location-${System.nanoTime()}"),
            name,
        ).apply { mkdirs() }

    private fun writeFile(
        directory: File,
        name: String,
        bytes: Long,
    ): File =
        File(directory, name).apply {
            parentFile?.mkdirs()
            // Only the length matters to resolution, and a real download's size should not
            // have to be written out to be tested: RandomAccessFile sets the exact byte
            // count without holding the bytes (Android's File has no setLength).
            java.io.RandomAccessFile(this, "rw").use { it.setLength(bytes) }
        }

    private fun moveInto(
        directory: File,
        file: File,
    ): File {
        val moved = File(directory, file.name)
        if (!file.renameTo(moved)) {
            file.copyTo(moved, overwrite = true)
            file.delete()
        }
        return moved
    }
}
