/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NoPlayableSourceExceptionTest {

    @Test
    fun `found through the wrapper media3 actually reports`() {
        // Media3 wraps whatever a data source throws, so the player only ever sees this
        // shape: an IO-coded PlaybackException caused by the loader's UnexpectedLoaderException
        // caused by the marker. Detection has to walk the chain, because the error code alone
        // says IO_UNSPECIFIED - which is what made this look like a transient failure and sent
        // the player into a retry loop that re-ran a provider sweep each time.
        val marker = NoPlayableSourceException("SpotiFLAC has no match for this track")
        val wrapped =
            PlaybackException(
                "Source error",
                RuntimeException("Unexpected PlaybackException: ...", marker),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            )

        val found = wrapped.findNoPlayableSourceException()

        assertNotNull(found)
        assertEquals("SpotiFLAC has no match for this track", found?.message)
        // The code that reaches the player is the misleading one, which is the whole reason
        // the marker type exists.
        assertEquals(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, wrapped.errorCode)
    }

    @Test
    fun `ordinary io failures are not mistaken for a source problem`() {
        val error =
            PlaybackException(
                "connect timed out",
                java.io.IOException("timeout"),
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            )

        assertNull(error.findNoPlayableSourceException())
    }

    @Test
    fun `a bare marker is found directly`() {
        val marker = NoPlayableSourceException("no source")

        assertEquals(marker, marker.findNoPlayableSourceException())
    }

    @Test
    fun `deep chains are searched, not just the first cause`() {
        val marker = NoPlayableSourceException("no source")
        var nested: Throwable = marker
        repeat(6) { nested = RuntimeException("layer", nested) }
        val error = PlaybackException("Source error", nested, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)

        assertNotNull(error.findNoPlayableSourceException())
    }
}
