/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import androidx.media3.common.PlaybackException
import app.hush.music.utils.YTPlayerUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PlaybackConfirmationRequiredTest {

    /**
     * The exact shape the device produced for a video every stream client answered
     * `LOGIN_REQUIRED` / "Please sign in" for: media3's loader wraps the marker in an
     * IO-coded PlaybackException, so the code the player inspects is the misleading one.
     */
    private fun asReportedByMedia3(marker: Throwable): PlaybackException =
        PlaybackException(
            "Source error",
            RuntimeException("Unexpected PlaybackException: ${marker.message}", marker),
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )

    @Test
    fun `a login demand is found through the wrapper that hides it behind an IO code`() {
        val marker =
            YTPlayerUtils.LoginRequiredForPlaybackException(
                videoId = "RArAS0WMgKw",
                targetUrl = "https://music.youtube.com/watch?v=RArAS0WMgKw",
                reason = "Please sign in",
            )

        val wrapped = asReportedByMedia3(marker)

        assertSame(marker, wrapped.playbackConfirmationRequiredCause())
        // The code that reaches the player is the one a dropped connection produces, which is
        // why the error code alone cannot decide this and the chain has to be walked.
        assertEquals(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, wrapped.errorCode)
    }

    @Test
    fun `a rejected login context is the same demand reached another way`() {
        val marker =
            YTPlayerUtils.InvalidPlaybackLoginContextException(
                videoId = "RArAS0WMgKw",
                targetUrl = "https://music.youtube.com/watch?v=RArAS0WMgKw",
                cause = IllegalStateException("login context rejected"),
            )

        assertSame(marker, asReportedByMedia3(marker).playbackConfirmationRequiredCause())
    }

    @Test
    fun `a demand buried deeper than one wrapper is still found`() {
        val marker =
            YTPlayerUtils.LoginRequiredForPlaybackException(
                videoId = "RArAS0WMgKw",
                targetUrl = "https://music.youtube.com/watch?v=RArAS0WMgKw",
                reason = "Please sign in",
            )
        val wrapped =
            PlaybackException(
                "Source error",
                IllegalStateException("loader", RuntimeException("inner", marker)),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            )

        assertSame(marker, wrapped.playbackConfirmationRequiredCause())
    }

    @Test
    fun `failures that are not a confirmation are left to their own recovery`() {
        // Bot detection and an empty client sweep both have their own retry path (a fresh PO
        // token / dropped playback auth), so claiming them here would disable that recovery.
        val botDetection =
            asReportedByMedia3(
                YTPlayerUtils.BotDetectionPlaybackException("RArAS0WMgKw", setOf("WEB", "IOS")),
            )
        val noPlayableResponse =
            asReportedByMedia3(YTPlayerUtils.BadStreamPlayerResponseException("RArAS0WMgKw"))
        val switchedOffSource = asReportedByMedia3(NoPlayableSourceException("no engine enabled"))
        val droppedConnection =
            PlaybackException(
                "Source error",
                java.net.ConnectException("Connection reset"),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            )

        assertNull(botDetection.playbackConfirmationRequiredCause())
        assertNull(noPlayableResponse.playbackConfirmationRequiredCause())
        assertNull(switchedOffSource.playbackConfirmationRequiredCause())
        assertNull(droppedConnection.playbackConfirmationRequiredCause())
    }
}
