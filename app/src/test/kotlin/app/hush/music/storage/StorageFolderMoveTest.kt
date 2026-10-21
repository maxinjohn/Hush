/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The rules that carry a storage folder's contents to a new one.
 *
 * These matter because the downloads folder is read by *name* to decide whether a track is
 * already downloaded: a copy wearing a song's real name before its bytes are all there would
 * be adopted and served as the song, which is worse than a file that is merely absent.
 */
class StorageFolderMoveTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun file(
        parent: File,
        name: String,
        bytes: Int,
    ): File = File(parent, name).apply {
        parentFile?.mkdirs()
        writeBytes(ByteArray(bytes) { index -> index.toByte() })
    }

    @Test
    fun `a copy is whole and keeps no part file`() {
        val source = file(folder.root, "song.flac", 4096)
        val target = File(folder.newFolder("new"), "song.flac")

        assertTrue(StorageFolderMove.copyFile(source, target))

        assertEquals(4096L, target.length())
        assertTrue(source.readBytes().contentEquals(target.readBytes()))
        assertFalse(
            "a part file left behind is litter in the user's own folder",
            File(target.parentFile, target.name + StorageFolderMove.PART_SUFFIX).exists(),
        )
    }

    @Test
    fun `a copy that cannot be written leaves nothing behind`() {
        val source = file(folder.root, "song.flac", 128)
        // A target whose parent is a file, not a directory: the copy cannot be made at all.
        val blocker = file(folder.root, "blocked", 1)
        val target = File(blocker, "song.flac")

        assertFalse(StorageFolderMove.copyFile(source, target))

        assertFalse(target.exists())
        assertFalse(File(blocker, "song.flac" + StorageFolderMove.PART_SUFFIX).exists())
        assertTrue("the source is the authority until its copy lands", source.exists())
    }

    @Test
    fun `the real download name is never worn by a part file`() {
        val source = file(folder.root, "song.flac", 64)
        val targetDirectory = folder.newFolder("new")
        val target = File(targetDirectory, "song.flac")

        StorageFolderMove.copyFile(source, target)

        // Only the finished file carries the name a download is matched on.
        val names = targetDirectory.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        assertEquals(setOf("song.flac"), names)
    }

    @Test
    fun `files are listed by relative path with directories left out`() {
        val root = folder.newFolder("source")
        file(root, "one.m4a", 10)
        file(File(root, "nested"), "two.flac", 20)
        File(root, "empty").mkdirs()

        assertEquals(
            listOf("nested/two.flac", "one.m4a"),
            StorageFolderMove.relativeFiles(root),
        )
    }

    @Test
    fun `the byte count is what the progress percentage is out of`() {
        val root = folder.newFolder("source")
        file(root, "one.m4a", 10)
        file(File(root, "nested"), "two.flac", 20)

        val relatives = StorageFolderMove.relativeFiles(root)
        assertEquals(30L, StorageFolderMove.byteCount(root, relatives))
    }

    @Test
    fun `an empty folder is a finished move, and a folder that cannot be listed is not`() {
        val empty = folder.newFolder("empty")
        assertTrue(
            "a folder with nothing in it is readable, and has nothing to carry",
            StorageFolderMove.isReadable(empty),
        )
        assertEquals(emptyList<String>(), StorageFolderMove.relativeFiles(empty))

        val unreadable = folder.newFolder("unreadable")
        file(unreadable, "song.flac", 16)
        assertTrue(StorageFolderMove.isReadable(unreadable))
        assertFalse(
            "a folder that is gone has nothing to carry either, but it is not the same answer",
            StorageFolderMove.isReadable(File(folder.root, "never-existed")),
        )
    }

    @Test
    fun `a folder this app may not look inside reports as unreadable, not as empty`() {
        val source = folder.newFolder("source")
        file(source, "song.flac", 16)

        // Listing a directory the app has no access to returns nothing at all, which is exactly
        // what an empty folder returns - so the two are told apart by asking whether it can be
        // listed, and a move that reports success is never a move that could not start.
        assumeTrue("the filesystem will not drop read access here", source.setReadable(false))
        try {
            assertEquals(emptyList<String>(), StorageFolderMove.relativeFiles(source))
            assertFalse(StorageFolderMove.isReadable(source))
        } finally {
            source.setReadable(true)
        }
    }

    @Test
    fun `pruning removes the directories a move emptied but keeps the root`() {
        val root = folder.newFolder("source")
        val emptied = File(root, "was-full").apply { mkdirs() }
        val kept = File(root, "still-full").apply { mkdirs() }
        file(kept, "song.flac", 8)

        StorageFolderMove.pruneEmptyDirectories(root)

        assertFalse("a directory the move emptied is gone", emptied.exists())
        assertTrue("a directory with a file in it stays", kept.exists())
        assertTrue("the folder the user picked is never this move's to delete", root.exists())
    }

    // What the storage screen shows while the move runs, and what its Cancel button stops. The job
    // is found by tag, because the screen does not know the unique name the move was enqueued
    // under - so these pin that a tag names one job and says which folder kind it is carrying.

    @Test
    fun `a job's tags name the folder kind it is moving`() {
        assertEquals(
            StorageFolderKind.DOWNLOADS,
            StorageFolderMove.kindForTags(
                setOf(StorageFolderMove.WORK_TAG, StorageFolderMove.workTagFor(StorageFolderKind.DOWNLOADS)),
            ),
        )
        assertEquals(
            StorageFolderKind.SONG_CACHE,
            StorageFolderMove.kindForTags(
                setOf(StorageFolderMove.WORK_TAG, StorageFolderMove.workTagFor(StorageFolderKind.SONG_CACHE)),
            ),
        )
    }

    @Test
    fun `the shared tag alone names no kind`() {
        // A queued job has published no progress yet, so its tags are the only thing to read; if
        // they named no kind, the move would be invisible exactly when the user first looks for it.
        assertNull(StorageFolderMove.kindForTags(setOf(StorageFolderMove.WORK_TAG)))
        assertNull(StorageFolderMove.kindForTags(emptySet()))
    }

    @Test
    fun `a tag for something else names no kind`() {
        assertNull(StorageFolderMove.kindForTags(setOf("some_other_work")))
        assertNull(StorageFolderMove.kindForTags(setOf("${StorageFolderMove.WORK_TAG}_NOT_A_KIND")))
    }

    @Test
    fun `the remaining count never goes below none left`() {
        assertEquals(120, StorageFolderMove.filesRemaining(total = 120, handled = 0))
        assertEquals(119, StorageFolderMove.filesRemaining(total = 120, handled = 1))
        assertEquals(0, StorageFolderMove.filesRemaining(total = 120, handled = 120))
        assertEquals(
            "a file appearing in the folder mid-move must not read as a negative count",
            0,
            StorageFolderMove.filesRemaining(total = 120, handled = 121),
        )
    }
}
