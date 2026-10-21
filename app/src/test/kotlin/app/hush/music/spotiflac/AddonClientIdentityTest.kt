/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 and Section 5
 */

package app.hush.music.spotiflac

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who Hush says it is, and the second name it falls back to.
 *
 * The product token is a client name one family of addon hosts checks for, and a host outside that
 * family may refuse it outright: `tido.tido.tido.dpdns.org` answers a Cloudflare block page to any
 * user agent starting with `BitChord/`, while its manifest is public to Hush's own name. That is
 * what made a public repository read as "403 - private or blocks Hush", so which identity is used,
 * and when the second one is tried, is a decision worth pinning rather than a detail of one request.
 */
class AddonClientIdentityTest {
    private val tido = "https://tido.tido.tido.dpdns.org/4bd14d1e2fa848febf1e9c03cdc237c0/manifest.json"
    private val gglos = "https://gglos.ggottes.deno.net/manifest.json"

    @After
    fun forgetWhatWasRefused() = AddonClientIdentity.forgetRefusals()

    /** The token goes first, because it is the identity that gets past a BitChord host's check. */
    @Test
    fun `every host is asked as the addon client first`() {
        assertEquals(AddonClientIdentity.userAgent, AddonClientIdentity.userAgentFor(tido))
        assertTrue(AddonClientIdentity.userAgent.startsWith("BitChord/"))
        // And the second name is Hush's own, not a browser's: a host that refuses the token is not
        // asking Hush to pretend to be something else.
        assertEquals(AddonClientIdentity.neutralUserAgent, AddonClientIdentity.alternativeFor(tido))
        assertTrue(AddonClientIdentity.neutralUserAgent.startsWith("Hush/"))
    }

    /** A refusal is remembered per host, so twenty asks do not become forty requests. */
    @Test
    fun `a host that refused the token is asked as Hush from then on`() {
        AddonClientIdentity.noteProductTokenRefused(tido)

        assertEquals(AddonClientIdentity.neutralUserAgent, AddonClientIdentity.userAgentFor(tido))
        // Nothing left to retry: the retry has happened, and a third request would only cost the
        // caller another round trip to learn the same thing.
        assertNull(AddonClientIdentity.alternativeFor(tido))
    }

    /** One host's refusal is not another's: gglos still needs the token it was written against. */
    @Test
    fun `a refusal on one host leaves every other host alone`() {
        AddonClientIdentity.noteProductTokenRefused(tido)

        assertEquals(AddonClientIdentity.userAgent, AddonClientIdentity.userAgentFor(gglos))
        assertEquals(AddonClientIdentity.neutralUserAgent, AddonClientIdentity.alternativeFor(gglos))
    }

    /** The host is read from the address, so a path or a query cannot hide which host answered. */
    @Test
    fun `the remembered host is the address's host, not its path`() {
        AddonClientIdentity.noteProductTokenRefused(
            "https://tido.tido.tido.dpdns.org/a48bf403dcfc40ae94f668c562d08201/manifest.json",
        )

        assertEquals(
            AddonClientIdentity.neutralUserAgent,
            AddonClientIdentity.userAgentFor("https://TIDO.tido.tido.dpdns.org/other/manifest.json"),
        )
        // An address with no host at all is left on the first identity rather than crashing a
        // repository check.
        assertEquals(AddonClientIdentity.userAgent, AddonClientIdentity.userAgentFor("not a url"))
    }
}
