/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Cover for the decision every network path makes before doing work: does this device already own
 * the track as a file?
 *
 * The direction that matters is asymmetric. Saying "yes" wrongly means a track is never fetched
 * again and plays whatever bytes are there - including a download that was half written, or one
 * deleted outside the app. Saying "no" wrongly only costs a fetch.
 */
class LocalPlaybackFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun realFile(
        name: String,
        bytes: Int = 64,
    ): File = tmp.newFile(name).apply { writeBytes(ByteArray(bytes)) }

    @Test
    fun `a real file with bytes is owned`() {
        val download = realFile("Song.flac")
        assertEquals(download, LocalPlaybackFiles.usable(download))
    }

    @Test
    fun `an empty file is not owned`() {
        // A download that has only just been created must not stop the track being fetched:
        // the alternative to fetching would be serving an empty file.
        assertNull(LocalPlaybackFiles.usable(realFile("Empty.flac", bytes = 0)))
    }

    @Test
    fun `a missing file is not owned`() {
        assertNull(LocalPlaybackFiles.usable(File(tmp.root, "gone.flac")))
        assertNull(LocalPlaybackFiles.usable(null))
    }

    @Test
    fun `a directory is not owned`() {
        assertNull(LocalPlaybackFiles.usable(tmp.newFolder("LooksLikeAFile")))
    }

    @Test
    fun `the user's download wins over a cached copy`() {
        val download = realFile("Song.flac")
        val cached = realFile("abc123.media")
        assertEquals(download, LocalPlaybackFiles.owned(downloaded = download, cached = cached))
    }

    @Test
    fun `a cached copy answers when there is no download`() {
        val cached = realFile("abc123.media")
        assertEquals(cached, LocalPlaybackFiles.owned(downloaded = null, cached = cached))
    }

    @Test
    fun `a half-written download falls through to the cached copy`() {
        val halfWritten = realFile("Song.flac", bytes = 0)
        val cached = realFile("abc123.media")
        assertEquals(cached, LocalPlaybackFiles.owned(downloaded = halfWritten, cached = cached))
    }

    @Test
    fun `nothing is owned when neither store has usable bytes`() {
        assertNull(
            LocalPlaybackFiles.owned(
                downloaded = realFile("Empty.flac", bytes = 0),
                cached = File(tmp.root, "gone.media"),
            ),
        )
    }
}
