/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import app.hush.music.spotiflac.SpotiFLACSessionVault.Origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A verified source must cost one Cloudflare challenge and then never again, so
 * everything that decides whether earned session material can still be found and
 * reused is load-bearing: the vault key (which must ignore the version the
 * extension now ships), the scope match (which must not adopt another
 * extension's session), and the ranking (which must prefer a session that can be
 * refreshed over one that has lapsed).
 */
class SpotiFLACSessionVaultTest {

    private val now = 1_800_000_000_000L

    private fun record(
        sessionId: String = "sess_abc",
        secret: String = "secret-value",
        installId: String = "3eef709b604e90e66eecb1322eb82f98",
        appVersion: String = "deezer@1.3.5",
        expiresAt: String? = "2027-01-01T00:00:00Z",
        namespace: String = "zarz-v2",
        baseUrl: String = "https://api.zarz.moe/v2",
        platform: String = "extension",
    ): String {
        val expiry = expiresAt?.let { """"expires_at":"$it",""" } ?: ""
        return """{"install_id":"$installId","session_id":"$sessionId","session_secret":"$secret",""" +
            expiry +
            """"namespace":"$namespace","base_url":"$baseUrl","app_version":"$appVersion",""" +
            """"platform":"$platform"}"""
    }

    private fun candidate(
        text: String,
        modifiedAt: Long = 0L,
        origin: Origin = Origin.SIBLING_RECORD,
        extension: String = "deezer@1.4.0",
        scope: String = SpotiFLACSessionVault.scopeKey(
            "zarz-v2",
            "https://api.zarz.moe/v2",
            "extension",
        ),
    ) = SpotiFLACSessionVault.candidateFromText(
        text = text,
        modifiedAtMillis = modifiedAt,
        origin = origin,
        source = File("/tmp/record.json"),
        scope = scope,
        currentAppVersion = extension,
        fallbackInstallId = "fallback-install-id",
    )

    // ------------------------------------------------------------ vault keying

    @Test
    fun `the vault keeps one entry per extension, not one per gateway`() {
        // Measured on device: keying on namespace+baseUrl+platform alone collapsed
        // all four extensions into a single file, so the last one mirrored won and
        // the others would be revived from a stranger's session.
        val deezer = SpotiFLACSessionVault.vaultFileName("deezer", "zarz-v2", "https://api.zarz.moe/v2", "extension")
        val amazon = SpotiFLACSessionVault.vaultFileName("amazon", "zarz-v2", "https://api.zarz.moe/v2", "extension")
        val tidal = SpotiFLACSessionVault.vaultFileName("tidal-web", "zarz-v2", "https://api.zarz.moe/v2", "extension")
        assertEquals(3, setOf(deezer, amazon, tidal).size)
        // The same extension on a different gateway is a different session.
        assertNotEquals(
            deezer,
            SpotiFLACSessionVault.vaultFileName("deezer", "zarz-v2", "https://api.zarz.moe/v3", "extension"),
        )
    }

    @Test
    fun `an extension key survives its version and separates extensions`() {
        assertEquals("deezer", SpotiFLACSessionVault.extensionKeyOf("deezer@1.3.5"))
        assertEquals("deezer", SpotiFLACSessionVault.extensionKeyOf("deezer@1.4.0"))
        assertEquals("amzn", SpotiFLACSessionVault.extensionKeyOf("amzn@2.3.8"))
        assertNotEquals(
            SpotiFLACSessionVault.extensionKeyOf("amzn@2.3.8"),
            SpotiFLACSessionVault.extensionKeyOf("deezer@1.3.5"),
        )
    }

    @Test
    fun `an unversioned app version only matches itself`() {
        // "ext-1.0" carries no extension identity, so it may only ever reuse a
        // record that is exactly itself - guessing would risk another source's
        // session, which is what the bug below is.
        assertTrue(SpotiFLACSessionVault.sameExtension("ext-1.0", "ext-1.0"))
        assertFalse(SpotiFLACSessionVault.sameExtension("ext-1.0", "other-1.0"))
    }

    @Test
    fun `scope key is case insensitive on the parts the gateway lowercases`() {
        assertEquals(
            SpotiFLACSessionVault.scopeKey("zarz-v2", "https://API.Zarz.Moe/V2", "Extension"),
            SpotiFLACSessionVault.scopeKey("zarz-v2", "https://api.zarz.moe/v2", "extension"),
        )
    }

    // -------------------------------------------------------------- scope match

    @Test
    fun `a record from a different scope is never adopted`() {
        val other = record(namespace = "zarz-v3")
        assertNull(candidate(other))
    }

    @Test
    fun `another extension's session is never adopted, even though the gateway scope matches`() {
        // Caught on device: amazon's record was revived into deezer's slot because
        // every extension shares namespace, baseUrl and platform. The source then
        // looks verified while every request through it is refused with 403.
        assertNull(candidate(record(appVersion = "amzn@2.3.8")))
        assertNull(candidate(record(appVersion = "tidal-web@1.2.5")))
        assertNull(candidate(record(appVersion = "qobuz-web@1.2.15")))
    }

    @Test
    fun `a record for the same extension but an older version is adoptable`() {
        // The registry-bump case: the extension now ships deezer@1.4.0 while its
        // verified session was minted as deezer@1.3.5.
        val picked = candidate(record(appVersion = "deezer@1.3.5"))
        assertNotNull(picked)
        assertEquals("deezer@1.3.5", picked!!.appVersion)
        assertEquals("deezer", picked.extensionKey)
    }

    @Test
    fun `a record without a usable session is ignored`() {
        assertNull(candidate(record(sessionId = "")))
        assertNull(candidate(record(secret = "")))
        assertNull(candidate("not json"))
    }

    @Test
    fun `an install id is borrowed from the app when the record has none`() {
        val picked = candidate(record(installId = ""))
        assertEquals("fallback-install-id", picked?.installId)
    }

    // ------------------------------------------------------------------ ranking

    @Test
    fun `a usable session beats one that has lapsed`() {
        val lapsed = candidate(record(sessionId = "sess_old", expiresAt = "2025-01-01T00:00:00Z"))!!
        val live = candidate(record(sessionId = "sess_new", expiresAt = "2027-01-01T00:00:00Z"))!!
        assertEquals("sess_new", SpotiFLACSessionVault.pickCandidate(listOf(lapsed, live), now)?.sessionId)
        // Order must not matter.
        assertEquals("sess_new", SpotiFLACSessionVault.pickCandidate(listOf(live, lapsed), now)?.sessionId)
    }

    @Test
    fun `among lapsed sessions the one that expired last wins`() {
        val older = candidate(record(sessionId = "sess_older", expiresAt = "2025-01-01T00:00:00Z"))!!
        val newer = candidate(record(sessionId = "sess_newer", expiresAt = "2025-06-01T00:00:00Z"))!!
        assertEquals("sess_newer", SpotiFLACSessionVault.pickCandidate(listOf(older, newer), now)?.sessionId)
    }

    @Test
    fun `without expiries the most recently written record wins`() {
        val stale = candidate(record(sessionId = "sess_stale", expiresAt = null), modifiedAt = 10L)!!
        val fresh = candidate(record(sessionId = "sess_fresh", expiresAt = null), modifiedAt = 20L)!!
        assertEquals("sess_fresh", SpotiFLACSessionVault.pickCandidate(listOf(stale, fresh), now)?.sessionId)
    }

    @Test
    fun `an unknown expiry counts as usable, matching the runtime preflight`() {
        val picked = candidate(record(expiresAt = null))!!
        assertTrue(SpotiFLACSessionVault.candidateUsable(picked, now))
        assertEquals(picked, SpotiFLACSessionVault.pickCandidate(listOf(picked), now))
    }

    @Test
    fun `no candidate means no adoption`() {
        assertNull(SpotiFLACSessionVault.pickCandidate(emptyList(), now))
    }

    // ------------------------------------------------------------------ payload

    @Test
    fun `the revived record keeps the version the session was minted under`() {
        val picked = candidate(record(appVersion = "deezer@1.3.5"))!!
        val payload = SpotiFLACSessionVault.revivalPayload(picked, "zarz-v2", "https://api.zarz.moe/v2", "extension")
        // The manifest may now say deezer@1.4.0; the gateway binds the session to
        // the minting version, so that is what has to be written back.
        assertTrue(payload.contains(""""app_version":"deezer@1.3.5""""))
        assertTrue(payload.contains(""""session_id":"sess_abc""""))
        assertTrue(payload.contains(""""install_id":"3eef709b604e90e66eecb1322eb82f98""""))
        assertTrue(payload.contains(""""namespace":"zarz-v2""""))
    }

    @Test
    fun `an expiry is rendered back in the format the runtime parses`() {
        val picked = candidate(record(expiresAt = "2027-01-01T00:00:00Z"))!!
        val payload = SpotiFLACSessionVault.revivalPayload(picked, "zarz-v2", "https://api.zarz.moe/v2", "extension")
        assertEquals(
            1_798_761_600_000L, // 2027-01-01T00:00:00Z
            SpotiFLACSourceAuth.recordExpiryMillis(payload),
        )
    }
    // -------------------------------------------------------------- sign order

    @Test
    fun `a renewal is signed with the minting version first and tried again after a version bump`() {
        assertEquals(
            listOf("deezer@1.3.5", "deezer@1.4.0"),
            SpotiFLACSessionRenewer.signVersionsFor("deezer@1.3.5", "deezer@1.4.0"),
        )
    }

    @Test
    fun `an unchanged extension is signed exactly once`() {
        assertEquals(
            listOf("deezer@1.3.5"),
            SpotiFLACSessionRenewer.signVersionsFor("deezer@1.3.5", "deezer@1.3.5"),
        )
    }

    @Test
    fun `a record with no minting version falls back to the extension version`() {
        assertEquals(
            listOf("deezer@1.3.5"),
            SpotiFLACSessionRenewer.signVersionsFor("", "deezer@1.3.5"),
        )
    }
}
