package app.hush.music.downloads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFolderHygieneTest {

    @Test
    fun `real download names are never cache artifacts`() {
        // This sweeper runs in a folder full of the user's music, so the negative cases are
        // the important ones.
        assertFalse(DownloadFolderHygiene.isMedia3CacheArtifact("Marino - Rip Out Your Eyes.flac"))
        assertFalse(DownloadFolderHygiene.isMedia3CacheArtifact("Zavyre - Don't Let Go.flac"))
        assertFalse(DownloadFolderHygiene.isMedia3CacheArtifact("Slayyyter - crank 2.flac"))
        assertFalse(
            DownloadFolderHygiene.isMedia3CacheArtifact(
                "G.V. Prakash Kumar, Ken Karunaas - Mutta Kalakki (From _Youth_).flac",
            ),
        )
        assertFalse(DownloadFolderHygiene.isMedia3CacheArtifact("track.m4a"))
        assertFalse(DownloadFolderHygiene.isMedia3CacheArtifact("track.webm"))
        assertFalse(DownloadFolderHygiene.isMedia3CacheArtifact("cover.jpg"))
        assertFalse(DownloadFolderHygiene.isMedia3CacheArtifact("01 - Song.flac"))
    }

    @Test
    fun `a hand-named file that only looks similar is left alone`() {
        // A user's own file must not match, which is why the patterns are anchored and the
        // hex run is length-bounded.
        assertFalse(DownloadFolderHygiene.isMedia3CacheArtifact("notes.uid"))
        assertFalse(DownloadFolderHygiene.isMedia3CacheArtifact("abc.uid"))
        assertFalse(DownloadFolderHygiene.isMedia3CacheArtifact("song.exo"))
        assertFalse(DownloadFolderHygiene.isMedia3CacheArtifact("my track.exo"))
        assertFalse(DownloadFolderHygiene.isMedia3CacheArtifact("12.flac"))
        assertFalse(DownloadFolderHygiene.isMedia3CacheArtifact("uid"))
    }

    @Test
    fun `media3 identity files are recognised`() {
        assertTrue(DownloadFolderHygiene.isMedia3CacheArtifact("5bccea7197bafb27.uid"))
        assertTrue(DownloadFolderHygiene.isMedia3CacheArtifact("AB12CD34EF56.uid"))
    }

    @Test
    fun `media3 span fragments are recognised`() {
        // The exact names found in the download folder before this was fixed.
        assertTrue(DownloadFolderHygiene.isMedia3CacheArtifact("15.0.1789300859597.v3.exo"))
        assertTrue(DownloadFolderHygiene.isMedia3CacheArtifact("15.5242880.1789300859626.v3.exo"))
        assertTrue(DownloadFolderHygiene.isMedia3CacheArtifact("15.10485760.1789300859647.v3.exo"))
        // Older layouts used a length instead of a timestamp.
        assertTrue(DownloadFolderHygiene.isMedia3CacheArtifact("15.0.5242880.exo"))
        assertTrue(DownloadFolderHygiene.isMedia3CacheArtifact("7.0.exo"))
    }

    @Test
    fun `purge removes cache artifacts and keeps the music`() {
        val folder = createTempDir(prefix = "downloads")
        try {
            val song = folder.resolve("Zavyre - Don't Let Go.flac").apply { writeText("audio") }
            val otherSong = folder.resolve("Slayyyter - crank 2.flac").apply { writeText("audio") }
            val staleUid = folder.resolve("5bccea7197bafb27.uid").apply { writeText("id") }
            val shard = folder.resolve("0").apply { mkdirs() }
            val staleSpan = shard.resolve("15.0.1789300859597.v3.exo").apply { writeText("bytes") }
            val keptByHand = folder.resolve("notes.uid").apply { writeText("mine") }

            val removed = DownloadFolderHygiene.purge(folder)

            assertTrue("expected the uid and the span to go", removed == 2)
            assertFalse(staleUid.exists())
            assertFalse(staleSpan.exists())
            assertFalse("an emptied shard directory should go", shard.exists())
            assertTrue(song.exists())
            assertTrue(otherSong.exists())
            assertTrue("a user file that is not cache bookkeeping must stay", keptByHand.exists())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `purge keeps a shard directory that still holds a real download`() {
        val folder = createTempDir(prefix = "downloads")
        try {
            val shard = folder.resolve("9").apply { mkdirs() }
            val song = shard.resolve("Song.flac").apply { writeText("audio") }
            val stale = shard.resolve("15.0.1.v3.exo").apply { writeText("bytes") }

            DownloadFolderHygiene.purge(folder)

            assertFalse(stale.exists())
            assertTrue(song.exists())
            assertTrue("the directory is not empty, so it stays", shard.exists())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `purge on a missing folder is a no-op`() {
        val folder = java.io.File(createTempDir(prefix = "gone").apply { deleteRecursively() }, "absent")
        assertTrue(DownloadFolderHygiene.purge(folder) == 0)
    }

    /** JUnit4 gives no temp-dir rule here, and the folder has to exist for the test. */
    private fun createTempDir(prefix: String): java.io.File =
        java.io.File(
            System.getProperty("java.io.tmpdir"),
            "$prefix-${System.nanoTime()}",
        ).apply { mkdirs() }
}
