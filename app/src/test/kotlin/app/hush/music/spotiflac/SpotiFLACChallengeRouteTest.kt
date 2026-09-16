/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The browser route has to finish without the user copying anything, which it does by reading the
 * grant back out of the challenge page. Everything here was measured against the live page:
 * both the shapes it publishes and the one callback scheme it will accept.
 */
class SpotiFLACChallengeRouteTest {

    /** The page as it renders after the browser solved the check but nothing consumed the grant. */
    private fun solvedPage(grant: String = "gr_HUOjqI3yVR9cV6cvdJOR"): String = """
        <script>
        var challengeId = "chl_qBDGMaGRsFWYg2CLTPH0";
        var callbackUrl = "spotiflac://session-grant?cb_version=v2grant&state=probe";
        var providerState = "probe";
        var challengeUsed = "1" === "1";
        var redeliverGrant = "$grant";
        </script>
    """.trimIndent()

    @Test
    fun `the grant is read from a solved challenge page`() {
        assertEquals(
            "gr_HUOjqI3yVR9cV6cvdJOR",
            SpotiFLACChallengeRoute.grantFromChallengePage(solvedPage()),
        )
    }

    @Test
    fun `an unsolved challenge publishes no grant`() {
        // Before the check is solved the page has no grant to publish, and matching loosely here
        // would hand the app a token it never earned.
        val unsolved = solvedPage().replace("""var challengeUsed = "1" === "1";""", """var challengeUsed = "" === "1";""")
            .replace("""var redeliverGrant = "gr_HUOjqI3yVR9cV6cvdJOR";""", """var redeliverGrant = "";""")
        assertNull(SpotiFLACChallengeRoute.grantFromChallengePage(unsolved))
    }

    @Test
    fun `a grant is found when only the fallback text carries it`() {
        val page = """
            <div id="fallbackUrl">spotiflac://session-grant?cb_version=v2grant&grant=gr_zTkIYcbG3oA0MAzgHxcA</div>
            <script>var challengeUsed = "1" === "1"; var redeliverGrant = "";</script>
        """.trimIndent()
        assertEquals("gr_zTkIYcbG3oA0MAzgHxcA", SpotiFLACChallengeRoute.grantFromChallengePage(page))
    }

    @Test
    fun `a spent challenge yields nothing to apply`() {
        val spent = solvedPage().replace(
            """var redeliverGrant = "gr_HUOjqI3yVR9cV6cvdJOR";""",
            """var redeliverGrant = "";""",
        )
        assertNull(SpotiFLACChallengeRoute.grantFromChallengePage(spent))
    }

    @Test
    fun `an unreadable or error page yields nothing`() {
        assertNull(SpotiFLACChallengeRoute.grantFromChallengePage(null))
        assertNull(SpotiFLACChallengeRoute.grantFromChallengePage(""))
        assertNull(SpotiFLACChallengeRoute.grantFromChallengePage("<html>403 Forbidden</html>"))
    }

    @Test
    fun `only the spotiflac scheme is accepted by the gateway`() {
        // Measured: the gateway reflects the callback into the page only for `spotiflac://...`.
        // Anything else - including the `hush://spotiflac-grant` form this app used to send -
        // arrives as an empty callbackUrl, which leaves the page with no target at all and a
        // browser-solved challenge with no way back but its copy button.
        assertTrue(
            SpotiFLACChallengeRoute.callbackBelongsToScheme(
                "spotiflac://session-grant?cb_version=v2grant&state=deezer",
            ),
        )
        assertTrue(SpotiFLACChallengeRoute.callbackBelongsToScheme("spotiflac://whatever?x=1"))
        assertFalse(SpotiFLACChallengeRoute.callbackBelongsToScheme("hush://spotiflac-grant?cb_version=v2grant"))
        assertFalse(SpotiFLACChallengeRoute.callbackBelongsToScheme("https://example.com/cb"))
        assertFalse(SpotiFLACChallengeRoute.callbackBelongsToScheme("not a url"))
    }

    @Test
    fun `the app builds the callback the gateway accepts`() {
        // The relay path used to hand the gateway `hush://spotiflac-grant`, which is rejected; a
        // rejected callback never reaches the page, so no browser could ever return to Hush.
        val callback = SpotiFLACSessionManager.callbackUrlFor("spotiflac")
        assertTrue(
            "callback the relay hands the gateway must be accepted: $callback",
            SpotiFLACChallengeRoute.callbackBelongsToScheme(callback),
        )
        // The state is what lets Hush route the grant to the right source.
        assertTrue(callback.contains("state="))
        assertEquals("spotiflac://session-grant?cb_version=v2grant&state=deezer", SpotiFLACSessionManager.callbackUrlFor("deezer"))
    }

    @Test
    fun `the relay echoes the nonce the gateway issued as its callback state`() {
        // The gateway hands out a nonce with each challenge and matches the solved grant back
        // through it. Sending the literal placeholder for every challenge gave it nothing to match.
        assertEquals("nonce-abc123", SpotiFLACSessionManager.relayCallbackState("nonce-abc123"))
        assertEquals("nonce-abc123", SpotiFLACSessionManager.relayCallbackState("  nonce-abc123  "))
        // A gateway that sends no nonce still produces a usable callback rather than "state=null".
        assertEquals("spotiflac", SpotiFLACSessionManager.relayCallbackState(null))
        assertEquals("spotiflac", SpotiFLACSessionManager.relayCallbackState("   "))
    }

    @Test
    fun `a spent challenge is recognised before anything is opened`() {
        // Captured from the live gateway for a challenge whose grant was already exchanged. The
        // page keeps `challengeUsed = 1` but publishes no grant, and its own script refuses to
        // re-verify such a challenge (that is what returns "Invalid request"). Handing this URL to
        // a browser is a guaranteed dead end, so it must be classified as spent, not pending.
        val spent = """<script>var challengeUsed = "1"; var redeliverGrant = "";</script>"""
        assertEquals(SpotiFLACChallengeRoute.PageState.Spent, SpotiFLACChallengeRoute.pageState(spent))
    }

    @Test
    fun `a solved challenge still publishing its grant is usable`() {
        val solved =
            """<script>var challengeUsed = "1"; var redeliverGrant = "gr_zTkIYcbG3oA0MAzgHxcA";</script>"""
        assertEquals(
            SpotiFLACChallengeRoute.PageState.Grant("gr_zTkIYcbG3oA0MAzgHxcA"),
            SpotiFLACChallengeRoute.pageState(solved),
        )
    }

    @Test
    fun `an unsolved challenge is pending, and an unreadable page is not treated as spent`() {
        assertEquals(
            SpotiFLACChallengeRoute.PageState.Pending,
            SpotiFLACChallengeRoute.pageState("""<script>var challengeUsed = "0";</script>"""),
        )
        // A page that carries no marker at all (a network hiccup, a captive portal) must not be
        // mistaken for a finished challenge - that would refuse a verification that could work.
        assertEquals(
            SpotiFLACChallengeRoute.PageState.Pending,
            SpotiFLACChallengeRoute.pageState("<html>nothing here</html>"),
        )
        assertEquals(SpotiFLACChallengeRoute.PageState.Pending, SpotiFLACChallengeRoute.pageState(null))
    }
}
