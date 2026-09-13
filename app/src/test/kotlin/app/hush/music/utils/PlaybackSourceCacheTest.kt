/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Regression cover for the seek/replay bug: local playback files must not be
 * validated against the YouTube auth fingerprint, or every Media3 re-open (i.e.
 * every seek) re-resolved and re-downloaded the track.
 */
class PlaybackSourceCacheTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun localFileEntry(file: File) =
        AuthScopedCacheValue(
            url = "file://${file.absolutePath}",
            // Already expired on purpose: local files never expire.
            expiresAtMs = 0L,
            authFingerprint = "spotiflac-native:tidal-web",
            playbackClientLabel = "SpotiFLAC • tidal-web",
            isYouTubeStream = false,
        )

    @Test
    fun `local playback urls are recognised`() {
        assertTrue("/data/user/0/app/files/x.media".let { "file://$it" }.isLocalPlaybackUrl())
        assertTrue("content://media/external/audio/1".isLocalPlaybackUrl())
        assertTrue("android.resource://app.hush.music/raw/beep".isLocalPlaybackUrl())
        assertTrue("FILE:///data/x.mp3".isLocalPlaybackUrl())
    }

    @Test
    fun `remote playback urls are not local`() {
        assertFalse("https://rr1.googlevideo.com/videoplayback?id=1".isLocalPlaybackUrl())
        assertFalse("http://example.com/a.flac".isLocalPlaybackUrl())
        assertFalse("/data/user/0/app/files/x.media".isLocalPlaybackUrl())
        assertFalse("".isLocalPlaybackUrl())
        assertFalse(null.isLocalPlaybackUrl())
    }

    @Test
    fun `local file entries stay valid for any auth fingerprint while the file exists`() {
        val file = tempFolder.newFile("track.media")
        val entry = localFileEntry(file)
        assertTrue(entry.isLocalFileStream)
        assertTrue(entry.isValidFor(authFingerprint = "youtube-fingerprint-abc"))
        assertTrue(
            entry.isValidFor(
                authFingerprint = "youtube-fingerprint-abc",
                minimumRemainingMs = YTPlayerUtils.STREAM_URL_EXPIRY_SAFETY_MS,
            ),
        )
    }

    @Test
    fun `local file entries are invalid once the file is gone`() {
        val file = tempFolder.newFile("gone.media")
        val entry = localFileEntry(file)
        assertTrue(file.delete())
        assertFalse(entry.isValidFor(authFingerprint = "youtube-fingerprint-abc"))
    }

    @Test
    fun `remote entries still require the matching fingerprint and a live expiry`() {
        val url = "https://rr1.googlevideo.com/videoplayback?id=1"
        val valid =
            AuthScopedCacheValue(
                url = url,
                expiresAtMs = System.currentTimeMillis() + 60 * 60 * 1000L,
                authFingerprint = "youtube-fingerprint-abc",
            )
        assertFalse(valid.isLocalFileStream)
        assertTrue(valid.isValidFor(authFingerprint = "youtube-fingerprint-abc"))
        assertFalse(valid.isValidFor(authFingerprint = "different-fingerprint"))

        val expired = valid.copy(expiresAtMs = System.currentTimeMillis() - 1L)
        assertFalse(expired.isValidFor(authFingerprint = "youtube-fingerprint-abc"))
    }

    @Test
    fun `download progress labels only appear when the values are known`() {
        val empty = PlaybackDownloadProgress(mediaId = "abc", percent = 12)
        assertNull(empty.speedLabel)
        assertNull(empty.sizeLabel)

        val active =
            PlaybackDownloadProgress(
                mediaId = "abc",
                percent = 42,
                bytesReceived = 10_000_000L,
                bytesTotal = 25_000_000L,
                speedMbps = 8.24,
            )
        assertEquals("8.2 MB/s", active.speedLabel)
        assertEquals("23.8 MB", active.sizeLabel)
    }
}
