/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeMusicLauncherTest {

    @Test
    fun `a Hush install is never the app that opens a YouTube Music link`() {
        // The shape this phone reports: `cmd package query-activities` lists the release build,
        // the debug build, and only then the activity that belongs to a patched YouTube Music
        // (installed under a package name that is not the one Google ships).
        val activities =
            listOf(
                "app.hush.music" to "app.hush.music.MainActivity",
                "app.hush.music.debug" to "app.hush.music.MainActivity",
                "com.amazon.mp3" to "com.google.android.apps.youtube.music.deeplink.MusicServiceDeepLinkActivity",
            )

        val opener =
            firstExternalOpener(activities, HUSH_PACKAGES_FOR_TEST + "app.hush.music.debug")

        assertEquals(
            "com.amazon.mp3" to "com.google.android.apps.youtube.music.deeplink.MusicServiceDeepLinkActivity",
            opener,
        )
    }

    @Test
    fun `with nothing but Hush able to answer there is no fallback to try`() {
        val activities =
            listOf(
                "app.hush.music" to "app.hush.music.MainActivity",
                "app.hush.music.debug" to "app.hush.music.MainActivity",
            )

        assertNull(firstExternalOpener(activities, HUSH_PACKAGES_FOR_TEST))
    }

    @Test
    fun `a browser still counts as an answer when no player claims the link`() {
        val activities = listOf("com.android.chrome" to "com.android.chrome.IntentDispatcher")

        assertEquals(
            "com.android.chrome" to "com.android.chrome.IntentDispatcher",
            firstExternalOpener(activities, HUSH_PACKAGES_FOR_TEST),
        )
    }

    private companion object {
        /** Mirrors the constant the launcher uses; a debug build adds its own package name. */
        val HUSH_PACKAGES_FOR_TEST = setOf("app.hush.music", "app.hush.music.debug")
    }
}
