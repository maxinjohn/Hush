package app.hush.music.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The rules that put a download the app no longer has a record of back into its index.
 *
 * These matter because the alternative to a match is a network fetch of a song the device
 * already holds, and because a *wrong* match would serve one song's bytes as another's.
 */
class DownloadsFolderAdoptionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun file(name: String, bytes: Int): File =
        folder.newFile(name).apply { writeBytes(ByteArray(bytes)) }

    @Test
    fun `both shapes a download can be named with are offered`() {
        val stems =
            DownloadsFolderAdoption.candidateFileStems(
                title = "Kun Fayakun",
                artist = "A. R. Rahman",
            )
        assertEquals(
            setOf("a. r. rahman - kun fayakun", "kun fayakun"),
            stems,
        )
    }

    @Test
    fun `a track with no usable title has nothing to match on`() {
        // Naming turns an unusable title into a placeholder so a file always has *some* name,
        // but that placeholder identifies no track: matching a file called it would adopt a song
        // on the strength of both being nameless.
        assertTrue(DownloadsFolderAdoption.candidateFileStems("", "Artist").isEmpty())
        assertTrue(DownloadsFolderAdoption.candidateFileStems(null, null).isEmpty())
        assertTrue(DownloadsFolderAdoption.candidateFileStems("///", "Artist").isEmpty())
    }

    @Test
    fun `an unknown artist is left out instead of being searched for`() {
        assertEquals(
            setOf("kun fayakun"),
            DownloadsFolderAdoption.candidateFileStems("Kun Fayakun", ""),
        )
    }

    @Test
    fun `a file named for the track is matched regardless of its container`() {
        // The container is whatever the source served (flac, m4a, webm...), and it is exactly
        // what an index lost in a reinstall can no longer tell us - so the extension is not
        // part of the match.
        val flac = file("A. R. Rahman - Kun Fayakun.flac", 4096)
        val stems = DownloadsFolderAdoption.candidateFileStems("Kun Fayakun", "A. R. Rahman")
        assertEquals(flac, DownloadsFolderAdoption.match(listOf(flac), emptySet(), stems))
    }

    @Test
    fun `a title-only file is matched`() {
        val bare = file("Kun Fayakun.m4a", 2048)
        val stems = DownloadsFolderAdoption.candidateFileStems("Kun Fayakun", null)
        assertEquals(bare, DownloadsFolderAdoption.match(listOf(bare), emptySet(), stems))
    }

    @Test
    fun `the largest copy wins when an interrupted attempt is still lying around`() {
        val truncated = file("Artist - Song.flac", 128)
        val complete = file("Artist - Song (2).flac", 9000)
        // The second name is what a name collision produces; only the first can match, so the
        // test uses two names that both match the track to pin the size rule.
        val alsoComplete = file("Song.flac", 9000)
        val stems = DownloadsFolderAdoption.candidateFileStems("Song", "Artist")
        assertEquals(
            alsoComplete,
            DownloadsFolderAdoption.match(listOf(truncated, alsoComplete), emptySet(), stems),
        )
        assertTrue(complete.exists())
    }

    @Test
    fun `a file another download already claims is left alone`() {
        // Two tracks legitimately share a name; the entry that wrote the file owns it.
        val claimed = file("Artist - Song.flac", 9000)
        val stems = DownloadsFolderAdoption.candidateFileStems("Song", "Artist")
        assertNull(
            DownloadsFolderAdoption.match(
                files = listOf(claimed),
                claimedNames = setOf(claimed.name),
                stems = stems,
            ),
        )
    }

    @Test
    fun `an empty file is not adopted`() {
        // A download that was interrupted before any bytes landed is not a song.
        val empty = file("Artist - Song.flac", 0)
        val stems = DownloadsFolderAdoption.candidateFileStems("Song", "Artist")
        assertNull(DownloadsFolderAdoption.match(listOf(empty), emptySet(), stems))
    }

    @Test
    fun `an unrelated file in the folder is ignored`() {
        val other = folder.newFile("Some Other Song.flac").apply { writeBytes(ByteArray(5000)) }
        val stems = DownloadsFolderAdoption.candidateFileStems("Kun Fayakun", "A. R. Rahman")
        assertNull(DownloadsFolderAdoption.match(listOf(other), emptySet(), stems))
    }

    @Test
    fun `matching is case insensitive because filenames are`() {
        val lowercased = folder.newFile("a. r. rahman - kun fayakun.flac")
            .apply { writeBytes(ByteArray(1024)) }
        val stems = DownloadsFolderAdoption.candidateFileStems("Kun Fayakun", "A. R. Rahman")
        assertEquals(lowercased, DownloadsFolderAdoption.match(listOf(lowercased), emptySet(), stems))
    }

    @Test
    fun `no stems means no adoption`() {
        val file = file("Anything.flac", 4096)
        assertNull(DownloadsFolderAdoption.match(listOf(file), emptySet(), emptySet()))
    }
}
