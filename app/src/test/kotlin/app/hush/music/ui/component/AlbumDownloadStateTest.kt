/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.component

import androidx.media3.exoplayer.offline.Download.STATE_COMPLETED
import androidx.media3.exoplayer.offline.Download.STATE_DOWNLOADING
import androidx.media3.exoplayer.offline.Download.STATE_QUEUED
import androidx.media3.exoplayer.offline.Download.STATE_STOPPED
import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumDownloadStateTest {
    private fun state(
        songIds: List<String>,
        vararg entries: Pair<String, Int>,
    ): Int {
        val states = entries.toMap()
        return albumDownloadState(songIds) { states[it] }
    }

    @Test
    fun `an album with no songs is not downloaded`() {
        assertEquals(STATE_STOPPED, state(emptyList()))
    }

    @Test
    fun `every song completed is completed`() {
        assertEquals(
            STATE_COMPLETED,
            state(listOf("a", "b"), "a" to STATE_COMPLETED, "b" to STATE_COMPLETED),
        )
    }

    @Test
    fun `one song left out means not completed`() {
        assertEquals(STATE_STOPPED, state(listOf("a", "b"), "a" to STATE_COMPLETED))
    }

    @Test
    fun `a queued song makes the album downloading`() {
        assertEquals(
            STATE_DOWNLOADING,
            state(listOf("a", "b"), "a" to STATE_COMPLETED, "b" to STATE_QUEUED),
        )
    }

    @Test
    fun `a downloading song makes the album downloading`() {
        assertEquals(
            STATE_DOWNLOADING,
            state(listOf("a", "b"), "a" to STATE_DOWNLOADING, "b" to STATE_QUEUED),
        )
    }

    @Test
    fun `a song that stopped part way is not a download in progress`() {
        assertEquals(
            STATE_STOPPED,
            state(listOf("a", "b"), "a" to STATE_COMPLETED, "b" to STATE_STOPPED),
        )
    }
}
