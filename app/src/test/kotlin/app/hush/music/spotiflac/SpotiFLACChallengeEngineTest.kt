/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rule that decides whether a device runs the challenge in its embedded WebView or
 * is routed to a real browser.
 *
 * The case these exist for is a car head unit whose WebView is frozen at Chrome 83: Cloudflare
 * never hands that engine a token, so every attempt had to time out before anything happened.
 */
class SpotiFLACChallengeEngineTest {

    private fun engine(version: String?) = SpotiFLACChallengeEngine.Engine(
        packageName = "com.android.webview",
        versionName = version,
        major = SpotiFLACChallengeEngine.parseMajor(version),
    )

    @Test
    fun `parses the major version out of a webview version name`() {
        assertEquals(83, SpotiFLACChallengeEngine.parseMajor("83.0.4103.120"))
        assertEquals(151, SpotiFLACChallengeEngine.parseMajor("151.0.7922.173"))
        assertEquals(130, SpotiFLACChallengeEngine.parseMajor(" 130.0.0.0 "))
    }

    @Test
    fun `unreadable versions parse to null rather than a wrong number`() {
        assertEquals(null, SpotiFLACChallengeEngine.parseMajor(null))
        assertEquals(null, SpotiFLACChallengeEngine.parseMajor(""))
        assertEquals(null, SpotiFLACChallengeEngine.parseMajor("   "))
        assertEquals(null, SpotiFLACChallengeEngine.parseMajor("unknown"))
    }

    @Test
    fun `the car head unit's frozen engine cannot solve cloudflare`() {
        assertFalse(SpotiFLACChallengeEngine.canSolveCloudflare(engine("83.0.4103.120")))
    }

    @Test
    fun `a current webview still takes the in-app route`() {
        assertTrue(SpotiFLACChallengeEngine.canSolveCloudflare(engine("151.0.7922.173")))
        assertTrue(SpotiFLACChallengeEngine.canSolveCloudflare(engine("130.0.0.0")))
    }

    @Test
    fun `the floor itself is accepted`() {
        val floor = SpotiFLACChallengeEngine.MIN_ENGINE_MAJOR
        assertTrue(SpotiFLACChallengeEngine.canSolveCloudflare(engine("$floor.0.0.0")))
        assertFalse(SpotiFLACChallengeEngine.canSolveCloudflare(engine("${floor - 1}.0.0.0")))
    }

    @Test
    fun `an unreadable engine is still attempted in the webview`() {
        // Refusing to try because a version string could not be read would break devices
        // where verification works today.
        assertTrue(SpotiFLACChallengeEngine.canSolveCloudflare(engine(null)))
        assertTrue(SpotiFLACChallengeEngine.canSolveCloudflare(engine("not-a-version")))
    }

    @Test
    fun `describes the engine for the notice and the log`() {
        assertEquals(
            "WebView 83.0.4103.120",
            SpotiFLACChallengeEngine.describe(engine("83.0.4103.120")),
        )
        assertEquals(
            "WebView com.android.webview",
            SpotiFLACChallengeEngine.describe(
                SpotiFLACChallengeEngine.Engine("com.android.webview", null, null),
            ),
        )
        assertEquals(
            "the system WebView",
            SpotiFLACChallengeEngine.describe(
                SpotiFLACChallengeEngine.Engine(null, null, null),
            ),
        )
    }

    @Test
    fun `recognises the page's own copied callback link`() {
        assertTrue(
            SpotiFLACChallengeEngine.isVerificationCallback(
                "spotiflac://session-grant?grant=abc123",
            ),
        )
        assertTrue(
            SpotiFLACChallengeEngine.isVerificationCallback(
                "hush://spotiflac-grant?cb_version=v2grant&state=spotiflac&grant=abc123",
            ),
        )
        assertTrue(SpotiFLACChallengeEngine.isVerificationCallback("grant=abc123"))
    }

    @Test
    fun `ignores clipboard content that is not a callback`() {
        assertFalse(SpotiFLACChallengeEngine.isVerificationCallback(null))
        assertFalse(SpotiFLACChallengeEngine.isVerificationCallback(""))
        assertFalse(SpotiFLACChallengeEngine.isVerificationCallback("   "))
        assertFalse(SpotiFLACChallengeEngine.isVerificationCallback("https://example.com/song"))
        // A pasted wall of text is not a grant, even when it mentions one.
        assertFalse(
            SpotiFLACChallengeEngine.isVerificationCallback("x".repeat(9000) + "grant=abc"),
        )
    }
}
