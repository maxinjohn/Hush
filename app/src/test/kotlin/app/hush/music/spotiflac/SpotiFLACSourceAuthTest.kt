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
 * Guards the classification that decides whether a SpotiFLAC source is asked for
 * a Cloudflare challenge, and whether its expired session is refreshed or reused.
 */
class SpotiFLACSourceAuthTest {

    /** Between the expired record's expiry and the live one's, like the real device. */
    private val now = 1_789_300_000_000L

    private val gatewayManifest = """
        {
          "name": "deezer",
          "type": ["metadata_provider", "download_provider"],
          "signedSession": {
            "namespace": "zarz-v2",
            "baseUrl": "https://api.zarz.moe/v2",
            "appVersion": "deezer@1.3.5",
            "platform": "extension"
          }
        }
    """.trimIndent()

    /** SoundCloud and the YouTube Music provider authenticate themselves. */
    private val selfSignedManifest = """
        {
          "name": "soundcloud",
          "type": ["metadata_provider", "download_provider"],
          "permissions": { "network": ["soundcloud.com"] }
        }
    """.trimIndent()

    private fun record(expiresAt: String?, secret: String? = "secret"): String = """
        {
          "install_id": "33894ee68f2492788d5a6fd8572e363b",
          "session_id": "sess_live",
          "session_secret": ${if (secret == null) "null" else "\"$secret\""},
          "expires_at": ${if (expiresAt == null) "null" else "\"$expiresAt\""}
        }
    """.trimIndent()

    @Test
    fun `manifest without a signed session needs no verification`() {
        assertFalse(SpotiFLACSourceAuth.requiresSignedSession(selfSignedManifest))
        assertEquals(
            SpotiFLACSourceAuthState.NOT_REQUIRED,
            SpotiFLACSourceAuth.state(selfSignedManifest, null, now),
        )
    }

    /**
     * The regression that made a fresh install report nothing to verify: the auth state is read
     * from the extension's extracted manifest, and on a first run the packages have not been
     * extracted yet - so every source, including the ones that do demand a Cloudflare check, was
     * classified as needing nothing. The automatic verification queue stayed empty, a download then
     * failed with `verification_required`, and the user was sent to solve a check the app had just
     * said was unnecessary. "Not read" has to be its own answer.
     */
    @Test
    fun `an unreadable manifest is unknown, never nothing to verify`() {
        val unreadable = listOf(
            null,
            "",
            "   ",
            "not json at all",
            "{\"signedSession\":\"oops\"}", // the block is not the object it claims to be
            "{\"signedSession\":{\"namespace\":\"zarz-v2\"}}", // no base url to scope a session
        )
        unreadable.forEach { manifest ->
            val state = SpotiFLACSourceAuth.state(manifest, null, now)
            assertEquals(
                "manifest ${manifest ?: "<null>"} must not claim a source needs nothing",
                SpotiFLACSourceAuthState.UNKNOWN,
                state,
            )
            // Unknown is still attempted: the runtime is the authority on whether a source can serve.
            assertTrue(state.isUsable)
        }
        // A readable manifest that declares no session block is the *other* answer, and it is the
        // only one that may be reported as "nothing to verify" - this is what SoundCloud and the
        // YouTube Music provider genuinely look like.
        assertEquals(
            SpotiFLACSourceAuthState.NOT_REQUIRED,
            SpotiFLACSourceAuth.state("{\"name\":\"soundcloud\",\"type\":[\"download_provider\"]}", null, now),
        )
    }

    @Test
    fun `only a readable manifest with no session block is skipped as nothing to verify`() {
        assertTrue(SpotiFLACSourceAuth.declaresNoSignedSession("{\"name\":\"soundcloud\"}"))
        // An unread one must not be skipped for: that is how a grant was lost for a package that
        // had simply not been extracted yet.
        assertFalse(SpotiFLACSourceAuth.declaresNoSignedSession(null))
        assertFalse(SpotiFLACSourceAuth.declaresNoSignedSession("not json"))
        assertFalse(SpotiFLACSourceAuth.declaresNoSignedSession(gatewayManifest))
    }

    @Test
    fun `an unexpired record verifies the source`() {
        assertEquals(
            SpotiFLACSourceAuthState.VERIFIED,
            SpotiFLACSourceAuth.state(gatewayManifest, record("2026-09-14T00:42:10.393Z"), now),
        )
    }

    /**
     * The regression that left an expired provider in the fallback chain: the old
     * check only looked for a non-blank id and secret, so an expired record read as
     * verified forever and the provider aborted every download it was walked into.
     */
    @Test
    fun `an expired record needs verification even though id and secret are present`() {
        val expired = record("2026-09-13T02:04:33.364Z")
        assertEquals("sess_live", SpotiFLACSourceAuth.recordSessionId(expired))
        assertFalse(SpotiFLACSourceAuth.recordUsable(expired, now))
        assertEquals(
            SpotiFLACSourceAuthState.NEEDS_VERIFICATION,
            SpotiFLACSourceAuth.state(gatewayManifest, expired, now),
        )
    }

    @Test
    fun `a missing record needs verification`() {
        assertEquals(
            SpotiFLACSourceAuthState.NEEDS_VERIFICATION,
            SpotiFLACSourceAuth.state(gatewayManifest, null, now),
        )
        assertEquals(
            SpotiFLACSourceAuthState.NEEDS_VERIFICATION,
            SpotiFLACSourceAuth.state(gatewayManifest, record(null, secret = null), now),
        )
    }

    /** The runtime only checks id and secret, so an unknown expiry is left alone. */
    @Test
    fun `a record whose expiry cannot be read is accepted`() {
        assertTrue(SpotiFLACSourceAuth.recordUsable(record(null), now))
        assertEquals(
            SpotiFLACSourceAuthState.VERIFIED,
            SpotiFLACSourceAuth.state(gatewayManifest, record("not-a-date"), now),
        )
        assertNull(SpotiFLACSourceAuth.recordExpiryMillis(record("not-a-date")))
    }

    @Test
    fun `only an unverified source is unusable`() {
        assertTrue(SpotiFLACSourceAuthState.VERIFIED.isUsable)
        assertTrue(SpotiFLACSourceAuthState.NOT_REQUIRED.isUsable)
        assertTrue(SpotiFLACSourceAuthState.UNKNOWN.isUsable)
        assertFalse(SpotiFLACSourceAuthState.NEEDS_VERIFICATION.isUsable)
    }

    @Test
    fun `expiry is read from an RFC3339 timestamp`() {
        assertEquals(
            1_789_346_530_393L,
            SpotiFLACSourceAuth.recordExpiryMillis(record("2026-09-14T00:42:10.393Z")),
        )
    }
}
