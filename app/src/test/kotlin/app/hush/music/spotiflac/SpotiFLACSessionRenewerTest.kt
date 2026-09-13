package app.hush.music.spotiflac

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * These pin the parts of session renewal that are easy to get silently wrong:
 * the HMAC scheme (must match the Go runtime byte for byte), the session-record
 * file naming (must match what the runtime reads), and the merge policy that
 * stops a stale renewal from clobbering a newer session.
 */
class SpotiFLACSessionRenewerTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ------------------------------------------------------------------
    // Signing — golden values computed from the Go implementation
    // ------------------------------------------------------------------

    @Test
    fun `signed headers match the Go ZARZ-HMAC-V1 scheme`() {
        val headers = SpotiFLACRequestSigner.signedHeaders(
            method = "POST",
            path = "/session/refresh",
            body = """{"install_id":"33894ee68f2492788d5a6fd8572e363b"}""",
            sessionId = "sess_test123",
            sessionSecret = "secret_test456",
            appVersion = "deezer@1.3.5",
            platform = "extension",
            schemeLabel = "ZARZ-HMAC-V1",
            headerPrefix = "X-Zarz-",
            timeWindowSeconds = 300,
            instant = Instant.parse("2026-09-12T21:00:00Z"),
            nonce = "abcdef012345",
        )

        assertEquals("sess_test123", headers["X-Zarz-Session"])
        assertEquals("2026-09-12T21:00:00.000Z", headers["X-Zarz-Timestamp"])
        assertEquals("abcdef012345", headers["X-Zarz-Nonce"])
        assertEquals(
            "9981a203224050ec6f4f6db9766d50dc045bb385ee39f09dbc9364e3db19a02e",
            headers["X-Zarz-Body-SHA256"],
        )
        assertEquals("deezer@1.3.5", headers["X-Zarz-App-Version"])
        assertEquals("extension", headers["X-Zarz-Platform"])
        assertEquals("l9qBgto97Bv-6-YZjorgJxin69r5Hb9GXiC-PVkdCcU", headers["X-Zarz-Signature"])
    }

    @Test
    fun `rolling window advances every five minutes`() {
        // 21:00:00 is a window boundary; the window holds until 21:04:59.
        val boundary = SpotiFLACRequestSigner.rollingWindow(Instant.parse("2026-09-12T21:00:00Z"), 300)
        assertEquals(
            boundary,
            SpotiFLACRequestSigner.rollingWindow(Instant.parse("2026-09-12T21:04:59Z"), 300),
        )
        assertEquals(
            boundary + 1,
            SpotiFLACRequestSigner.rollingWindow(Instant.parse("2026-09-12T21:05:00Z"), 300),
        )
        assertEquals(
            boundary - 1,
            SpotiFLACRequestSigner.rollingWindow(Instant.parse("2026-09-12T20:59:59Z"), 300),
        )
    }

    @Test
    fun `signing is deterministic for one instant and nonce`() {
        fun sign() = SpotiFLACRequestSigner.signedHeaders(
            method = "POST",
            path = "/session/refresh",
            body = "{}",
            sessionId = "sess",
            sessionSecret = "secret",
            appVersion = "app@1.0.0",
            instant = Instant.parse("2026-09-12T21:00:00Z"),
            nonce = "fixed-nonce",
        )
        assertEquals(sign(), sign())
    }

    @Test
    fun `session secret and nonce both affect the signature`() {
        fun sign(secret: String, nonce: String) = SpotiFLACRequestSigner.signedHeaders(
            method = "POST",
            path = "/session/refresh",
            body = "{}",
            sessionId = "sess",
            sessionSecret = secret,
            appVersion = "app@1.0.0",
            instant = Instant.parse("2026-09-12T21:00:00Z"),
            nonce = nonce,
        )["X-Zarz-Signature"]

        assertTrue(sign("secret-a", "n") != sign("secret-b", "n"))
        assertTrue(sign("secret-a", "n1") != sign("secret-a", "n2"))
    }

    @Test
    fun `header prefix and scheme label are honored`() {
        val headers = SpotiFLACRequestSigner.signedHeaders(
            method = "POST",
            path = "/session/refresh",
            body = "{}",
            sessionId = "s",
            sessionSecret = "k",
            appVersion = "a",
            schemeLabel = "OTHER-HMAC-V2",
            headerPrefix = "X-Other-",
            instant = Instant.parse("2026-09-12T21:00:00Z"),
            nonce = "n",
        )
        assertTrue(headers.containsKey("X-Other-Signature"))
        assertTrue(!headers.containsKey("X-Zarz-Signature"))
    }

    @Test
    fun `query strings are excluded from the signed path`() {
        val headers = SpotiFLACRequestSigner.signedHeaders(
            method = "GET",
            path = "/v2/search?q=abc",
            body = "",
            sessionId = "s",
            sessionSecret = "k",
            appVersion = "a",
            instant = Instant.parse("2026-09-12T21:00:00Z"),
            nonce = "n",
        )
        val withoutQuery = SpotiFLACRequestSigner.signedHeaders(
            method = "GET",
            path = "/v2/search",
            body = "",
            sessionId = "s",
            sessionSecret = "k",
            appVersion = "a",
            instant = Instant.parse("2026-09-12T21:00:00Z"),
            nonce = "n",
        )
        assertEquals(withoutQuery["X-Zarz-Signature"], headers["X-Zarz-Signature"])
    }

    // ------------------------------------------------------------------
    // Session record naming — must match the runtime's own files
    // ------------------------------------------------------------------

    @Test
    fun `record file name matches the runtime's hashed scope`() {
        // Names verified against real records written by the native runtime on a
        // device: extension_data/signed_sessions/<namespace>-<hash>.json
        assertEquals(
            "zarz-v2-57befc3493a9748e.json",
            SpotiFLACRequestSigner.sessionRecordFileName(
                namespace = "zarz-v2",
                baseUrl = "https://api.zarz.moe/v2",
                appVersion = "deezer@1.3.5",
                platform = "extension",
            ),
        )
        assertEquals(
            "zarz-v2-f1889c8a7eb52c25.json",
            SpotiFLACRequestSigner.sessionRecordFileName(
                namespace = "zarz-v2",
                baseUrl = "https://api.zarz.moe/v2",
                appVersion = "tidal-web@1.2.5",
                platform = "extension",
            ),
        )
    }

    @Test
    fun `record name is insensitive to url and version casing`() {
        val upper = SpotiFLACRequestSigner.sessionRecordFileName(
            "zarz-v2",
            "HTTPS://API.ZARZ.MOE/V2",
            "DEEZER@1.3.5",
            "EXTENSION",
        )
        val lower = SpotiFLACRequestSigner.sessionRecordFileName(
            "zarz-v2",
            "https://api.zarz.moe/v2",
            "deezer@1.3.5",
            "extension",
        )
        assertEquals(lower, upper)
    }

    @Test
    fun `namespace sanitizing mirrors the runtime`() {
        assertEquals("zarz-v2", SpotiFLACRequestSigner.sanitizeNamespace("  ZARZ-v2  "))
        assertEquals("my.ns_1", SpotiFLACRequestSigner.sanitizeNamespace("my.ns_1"))
        assertEquals("abc", SpotiFLACRequestSigner.sanitizeNamespace("--abc--"))
    }

    // ------------------------------------------------------------------
    // Renewal policy
    // ------------------------------------------------------------------

    @Test
    fun `renewal is due inside the window and skipped outside it`() {
        assertTrue(SpotiFLACSessionRenewer.renewalDue(60, false))
        assertTrue(SpotiFLACSessionRenewer.renewalDue(SpotiFLACSessionRenewer.RENEW_WINDOW_SECONDS, false))
        assertFalse(SpotiFLACSessionRenewer.renewalDue(SpotiFLACSessionRenewer.RENEW_WINDOW_SECONDS + 1, false))
        assertTrue(SpotiFLACSessionRenewer.renewalDue(24 * 3600, true))
    }

    @Test
    fun `unknown expiry is treated as due so a session is never silently skipped`() {
        assertTrue(SpotiFLACSessionRenewer.renewalDue(null, false))
    }

    @Test
    fun `expired sessions are still attempted`() {
        assertTrue(SpotiFLACSessionRenewer.renewalDue(-10, false))
    }

    @Test
    fun `renew window is narrower than a session lifetime`() {
        assertTrue(SpotiFLACSessionRenewer.RENEW_WINDOW_SECONDS >= 3600)
    }

    @Test
    fun `expiry parsing accepts RFC3339 millis and rejects junk`() {
        assertEquals(
            Instant.parse("2026-09-13T02:04:34.250Z").toEpochMilli(),
            SpotiFLACSessionRenewer.parseExpiryMillis("2026-09-13T02:04:34.250Z"),
        )
        assertEquals(
            Instant.parse("2026-09-13T02:04:34Z").toEpochMilli(),
            SpotiFLACSessionRenewer.parseExpiryMillis("2026-09-13T02:04:34Z"),
        )
        assertEquals(null, SpotiFLACSessionRenewer.parseExpiryMillis("not-a-date"))
    }

    // ------------------------------------------------------------------
    // Gateway-refusal backoff
    // ------------------------------------------------------------------

    @Test
    fun `a refusal is remembered against the session generation it happened on`() {
        val until = System.currentTimeMillis() + 60_000
        val stored = "sess_current|$until"
        assertTrue(SpotiFLACSessionRenewer.renewalRejectionActive(stored, "sess_current"))
        // A newly verified session must be tried again straight away.
        assertFalse(SpotiFLACSessionRenewer.renewalRejectionActive(stored, "sess_reverified"))
    }

    @Test
    fun `an elapsed refusal no longer blocks renewal`() {
        val stored = "sess_current|${System.currentTimeMillis() - 1}"
        assertFalse(SpotiFLACSessionRenewer.renewalRejectionActive(stored, "sess_current"))
    }

    @Test
    fun `malformed refusal state never blocks renewal`() {
        assertFalse(SpotiFLACSessionRenewer.renewalRejectionActive(null, "sess"))
        assertFalse(SpotiFLACSessionRenewer.renewalRejectionActive("", "sess"))
        assertFalse(SpotiFLACSessionRenewer.renewalRejectionActive("garbage", "sess"))
        assertFalse(SpotiFLACSessionRenewer.renewalRejectionActive("sess|not-a-number", "sess"))
        assertFalse(SpotiFLACSessionRenewer.renewalRejectionActive("|123", "sess"))
    }

    @Test
    fun `refusal state round-trips through its stored form`() {
        val parsed = SpotiFLACSessionRenewer.parseRenewalRejection("sess_1|1800000000000")
        assertEquals("sess_1" to 1_800_000_000_000L, parsed)
    }

    // ------------------------------------------------------------------
    // Merge policy
    // ------------------------------------------------------------------

    @Test
    fun `refresh merges session fields and preserves the rest of the record`() {
        val existing = json.parseToJsonElement(
            """
            {
              "install_id": "33894ee68f2492788d5a6fd8572e363b",
              "session_id": "sess_old",
              "session_secret": "old-secret",
              "expires_at": "2026-09-13T02:04:34.250Z",
              "namespace": "zarz-v2",
              "base_url": "https://api.zarz.moe/v2",
              "app_version": "deezer@1.3.5",
              "platform": "extension"
            }
            """.trimIndent(),
        ).jsonObject

        val merged = SpotiFLACSessionRenewer.mergeForTest(
            existing,
            mapOf(
                "session_id" to "sess_new",
                "session_secret" to "new-secret",
                "expires_at" to "2026-09-13T08:00:00.000Z",
            ),
        )

        assertEquals("sess_new", merged["session_id"])
        assertEquals("new-secret", merged["session_secret"])
        assertEquals("2026-09-13T08:00:00.000Z", merged["expires_at"])
        // Untouched fields survive: the runtime still needs them to load the record.
        assertEquals("33894ee68f2492788d5a6fd8572e363b", merged["install_id"])
        assertEquals("zarz-v2", merged["namespace"])
        assertEquals("https://api.zarz.moe/v2", merged["base_url"])
        assertEquals("deezer@1.3.5", merged["app_version"])
        assertEquals("extension", merged["platform"])
    }

    @Test
    fun `blank gateway fields never overwrite a working session`() {
        val existing = json.parseToJsonElement(
            """{"session_id":"sess_old","session_secret":"old-secret","expires_at":"2030-01-01T00:00:00Z"}""",
        ).jsonObject

        val merged = SpotiFLACSessionRenewer.mergeForTest(
            existing,
            mapOf("session_id" to "", "session_secret" to "", "expires_at" to "2031-01-01T00:00:00Z"),
        )

        assertEquals("sess_old", merged["session_id"])
        assertEquals("old-secret", merged["session_secret"])
        assertEquals("2031-01-01T00:00:00Z", merged["expires_at"])
    }

    @Test
    fun `an empty refresh leaves the record untouched`() {
        val existing = json.parseToJsonElement(
            """{"session_id":"sess_old","session_secret":"old-secret"}""",
        ).jsonObject
        assertEquals(
            mapOf("session_id" to "sess_old", "session_secret" to "old-secret"),
            SpotiFLACSessionRenewer.mergeForTest(existing, emptyMap()),
        )
    }
}
