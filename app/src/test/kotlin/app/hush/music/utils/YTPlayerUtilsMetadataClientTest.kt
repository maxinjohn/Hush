/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 and Section 5
 */

package app.hush.music.utils

import app.hush.music.constants.PlayerStreamClient

import app.hush.music.innertube.models.YouTubeClient.Companion.WEB_REMIX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The first resolve of a session must not queue behind the BotGuard WebView.
 *
 * Measured on the reporting device, the Web PoToken bootstrap took 8.6s from app start
 * (`Page loaded` 19.898 → `Minter ready` 28.460) and the first track's metadata request waited the whole
 * of it, because the metadata client was WEB_REMIX and Web clients cannot be asked without a token.
 * The direct-URL clients that actually served the stream need no token at all, so the wait bought
 * nothing that could not have been fetched immediately.
 */
class YTPlayerUtilsMetadataClientTest {

    @Test
    fun `with the token in hand the metadata client is what it always was`() {
        listOf(
            PlayerStreamClient.ANDROID_VR,
            PlayerStreamClient.IOS,
            PlayerStreamClient.TVHTML5,
            PlayerStreamClient.WEB_REMIX,
            PlayerStreamClient.ANDROID_MUSIC,
        ).forEach { choice ->
            assertEquals(
                "$choice must not change the metadata client once the token is ready",
                WEB_REMIX,
                YTPlayerUtils.resolveMetadataClient(choice, webPoTokenReady = true),
            )
        }
    }

    @Test
    fun `without the token the metadata comes from the client that needs none`() {
        val client = YTPlayerUtils.resolveMetadataClient(PlayerStreamClient.ANDROID_VR, webPoTokenReady = false)

        assertEquals("ANDROID_VR", client.clientName)
        assertFalse(
            "a fallback that needs a Web PoToken would wait for the same bootstrap it is avoiding",
            client.useWebPoTokens,
        )
    }

    @Test
    fun `a user who chose a web client keeps it, because there is nothing token-free to offer`() {
        // Waiting is then the only way to answer at all - the same behaviour as before this change.
        assertEquals(
            WEB_REMIX,
            YTPlayerUtils.resolveMetadataClient(PlayerStreamClient.WEB_REMIX, webPoTokenReady = false),
        )
    }

    @Test
    fun `the fallback is the client the sweep will start with, so its answer is reused`() {
        listOf(
            PlayerStreamClient.ANDROID_VR,
            PlayerStreamClient.IOS,
            PlayerStreamClient.TVHTML5,
            PlayerStreamClient.ANDROID_MUSIC,
        ).forEach { choice ->
            assertEquals(
                YTPlayerUtils.resolveDirectPlaybackClient(choice),
                YTPlayerUtils.resolveMetadataClient(choice, webPoTokenReady = false),
            )
        }
    }

    @Test
    fun `every direct choice maps to a client that needs no Web PoToken`() {
        val directChoices =
            listOf(
                PlayerStreamClient.ANDROID_VR,
                PlayerStreamClient.IOS,
                PlayerStreamClient.TVHTML5,
                PlayerStreamClient.ANDROID_MUSIC,
            )

        directChoices.forEach { choice ->
            assertFalse(
                "$choice maps to a client that needs a Web PoToken",
                YTPlayerUtils.resolveDirectPlaybackClient(choice).useWebPoTokens,
            )
        }
    }
}
