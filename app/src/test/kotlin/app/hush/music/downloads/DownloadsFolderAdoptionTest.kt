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

    // The whole-folder sweep is the same rule asked once for the entire library at startup,
    // instead of once per track at play time. What these pin is that it adopts exactly what the
    // one-at-a-time path would have, and that two tracks can never end up sharing one file.

    @Test
    fun `a sweep adopts every track the folder holds a file for`() {
        val first = file("A. R. Rahman - Kun Fayakun.flac", 4096)
        val second = file("Some Other Song.m4a", 2048)
        val adopted =
            DownloadsFolderAdoption.sweep(
                files = listOf(first, second),
                tracks =
                    listOf(
                        DownloadTrack("id-1", "Kun Fayakun", "A. R. Rahman"),
                        DownloadTrack("id-2", "Some Other Song", null),
                    ),
                alreadyRecorded = emptySet(),
                claimedNames = emptySet(),
            )
        assertEquals(setOf("id-1" to first, "id-2" to second), adopted.map { it.track.mediaId to it.file }.toSet())
    }

    @Test
    fun `a sweep leaves a track that already has a record alone`() {
        val existing = file("Artist - Song.flac", 9000)
        val adopted =
            DownloadsFolderAdoption.sweep(
                files = listOf(existing),
                tracks = listOf(DownloadTrack("id-1", "Song", "Artist")),
                alreadyRecorded = setOf("id-1"),
                claimedNames = setOf(existing.name),
            )
        assertTrue(adopted.isEmpty())
    }

    @Test
    fun `a sweep adopts a record the index does not have without touching the files its records own`() {
        // The folder holds two songs; the index already records the first. The sweep must add the
        // second, and must not re-adopt the first's file for the first track.
        val recorded = file("Artist - One.flac", 9000)
        val missing = file("Artist - Two.flac", 5000)
        val adopted =
            DownloadsFolderAdoption.sweep(
                files = listOf(recorded, missing),
                tracks =
                    listOf(
                        DownloadTrack("id-1", "One", "Artist"),
                        DownloadTrack("id-2", "Two", "Artist"),
                    ),
                alreadyRecorded = setOf("id-1"),
                claimedNames = setOf(recorded.name),
            )
        assertEquals(listOf("id-2" to missing), adopted.map { it.track.mediaId to it.file })
    }

    @Test
    fun `two tracks with the same name never share one file`() {
        // Only one file carries the name, so only the first track in the library order can adopt
        // it; the second finding nothing is the correct answer, not a duplicate record.
        val shared = file("Artist - Song.flac", 9000)
        val adopted =
            DownloadsFolderAdoption.sweep(
                files = listOf(shared),
                tracks =
                    listOf(
                        DownloadTrack("id-1", "Song", "Artist"),
                        DownloadTrack("id-2", "Song", "Artist"),
                    ),
                alreadyRecorded = emptySet(),
                claimedNames = emptySet(),
            )
        assertEquals(listOf("id-1" to shared), adopted.map { it.track.mediaId to it.file })
    }

    @Test
    fun `a sweep ignores files no track is named for`() {
        val unrelated = file("Some Other Song.flac", 5000)
        val adopted =
            DownloadsFolderAdoption.sweep(
                files = listOf(unrelated),
                tracks = listOf(DownloadTrack("id-1", "Kun Fayakun", "A. R. Rahman")),
                alreadyRecorded = emptySet(),
                claimedNames = emptySet(),
            )
        assertTrue(adopted.isEmpty())
    }

    @Test
    fun `a sweep adopts the largest copy when a name is duplicated`() {
        val truncated = file("Artist - Song.flac", 128)
        val complete = file("Song.m4a", 9000)
        val adopted =
            DownloadsFolderAdoption.sweep(
                files = listOf(truncated, complete),
                tracks = listOf(DownloadTrack("id-1", "Song", "Artist")),
                alreadyRecorded = emptySet(),
                claimedNames = emptySet(),
            )
        assertEquals(listOf("id-1" to complete), adopted.map { it.track.mediaId to it.file })
    }

    @Test
    fun `a track with no usable name adopts nothing`() {
        val file = file("Anything.flac", 4096)
        val adopted =
            DownloadsFolderAdoption.sweep(
                files = listOf(file),
                tracks = listOf(DownloadTrack("id-1", "///", "Artist"), DownloadTrack("", "Song", "Artist")),
                alreadyRecorded = emptySet(),
                claimedNames = emptySet(),
            )
        assertTrue(adopted.isEmpty())
    }
}
