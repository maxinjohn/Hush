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

    @Test
    fun `unreadable manifest is treated as not requiring a session`() {
        assertEquals(
            SpotiFLACSourceAuthState.NOT_REQUIRED,
            SpotiFLACSourceAuth.state("not json at all", null, now),
        )
        assertEquals(
            SpotiFLACSourceAuthState.NOT_REQUIRED,
            SpotiFLACSourceAuth.state(null, null, now),
        )
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
