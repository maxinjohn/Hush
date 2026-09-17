/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The skip path cancels resolutions keyed by media id, so the key format is load-bearing: get
 * it wrong and the track now playing is either protected from a legitimate cancel, or has its
 * own in-flight resolve cancelled and its load fails for no reason.
 */
class PlaybackResolutionKeysTest {

    @Test
    fun `a key carries the media id and the client it was asked for`() {
        assertEquals(
            "abc123|default",
            PlaybackResolutionKeys.of(mediaId = "abc123", preferredClientOverride = null),
        )
        assertEquals(
            "abc123|ANDROID_VR",
            PlaybackResolutionKeys.of(mediaId = "abc123", preferredClientOverride = "ANDROID_VR"),
        )
    }

    @Test
    fun `the media id is recovered from either client's key`() {
        val keys =
            listOf(
                PlaybackResolutionKeys.of("abc123", null),
                PlaybackResolutionKeys.of("abc123", "ANDROID_VR"),
                PlaybackResolutionKeys.of("xyz789", "HI_RES_LOSSLESS"),
            )

        assertEquals(
            listOf("abc123", "abc123", "xyz789"),
            keys.map(PlaybackResolutionKeys::mediaIdOf),
        )
    }

    @Test
    fun `a media id containing the separator still round-trips`() {
        val mediaId = "odd|id|value"

        assertEquals(mediaId, PlaybackResolutionKeys.mediaIdOf(PlaybackResolutionKeys.of(mediaId, null)))
        assertEquals(
            mediaId,
            PlaybackResolutionKeys.mediaIdOf(PlaybackResolutionKeys.of(mediaId, "ANDROID_VR")),
        )
    }

    @Test
    fun `every client's request for the skipped track is abandoned, not just the default one`() {
        val inFlight =
            listOf(
                PlaybackResolutionKeys.of("left", null),
                PlaybackResolutionKeys.of("left", "ANDROID_VR"),
                PlaybackResolutionKeys.of("now", null),
                PlaybackResolutionKeys.of("now", "ANDROID_VR"),
            )

        val abandoned = PlaybackResolutionKeys.abandoned(inFlight, keepMediaIds = setOf("now"))

        assertEquals(
            listOf(
                PlaybackResolutionKeys.of("left", null),
                PlaybackResolutionKeys.of("left", "ANDROID_VR"),
            ),
            abandoned,
        )
    }

    @Test
    fun `the track now playing keeps both of its requests`() {
        val inFlight =
            listOf(
                PlaybackResolutionKeys.of("now", null),
                PlaybackResolutionKeys.of("now", "ANDROID_VR"),
            )

        assertTrue(PlaybackResolutionKeys.abandoned(inFlight, keepMediaIds = setOf("now")).isEmpty())
    }

    @Test
    fun `nothing in flight means nothing to abandon`() {
        assertTrue(
            PlaybackResolutionKeys.abandoned(emptyList(), keepMediaIds = setOf("now")).isEmpty(),
        )
    }

    @Test
    fun `a timeline with no current item abandons everything`() {
        val inFlight = listOf(PlaybackResolutionKeys.of("a", null), PlaybackResolutionKeys.of("b", null))

        assertEquals(inFlight, PlaybackResolutionKeys.abandoned(inFlight, keepMediaIds = emptySet()))
    }
}
