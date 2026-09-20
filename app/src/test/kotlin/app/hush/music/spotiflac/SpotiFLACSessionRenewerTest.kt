package app.hush.music.spotiflac

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    // Background cadence — a session is only renewable while it is valid,
    // so the interval is how many attempts a window contains
    // ------------------------------------------------------------------

    @Test
    fun `the background renewer gets several attempts inside the renewal window`() {
        val windowMinutes = SpotiFLACSessionRenewer.RENEW_WINDOW_SECONDS / 60
        assertTrue(
            "an interval that matches the window leaves a single, deferrable attempt",
            SpotiFLACSessionRenewer.BACKGROUND_INTERVAL_MINUTES <= windowMinutes / 3,
        )
    }

    @Test
    fun `the flex window stays below the interval`() {
        assertTrue(
            SpotiFLACSessionRenewer.BACKGROUND_FLEX_MINUTES <
                SpotiFLACSessionRenewer.BACKGROUND_INTERVAL_MINUTES,
        )
    }

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

    @Test
    fun `a remembered refusal stops the schedule but never a user's own tap`() {
        val now = 1_000_000L
        val until = now + 3 * 3_600_000L
        // The background renewer leaves a refused source alone...
        assertTrue(SpotiFLACSessionRenewer.renewalRefusalApplies(until, now, userAsked = false))
        // ...and "Renew now" still asks. On device this is the difference between one source
        // renewing and its timer moving, and three being reported as refused without ever being
        // called - up to six hours after a single refusal.
        assertFalse(SpotiFLACSessionRenewer.renewalRefusalApplies(until, now, userAsked = true))
        // An elapsed refusal is not in anyone's way.
        assertFalse(SpotiFLACSessionRenewer.renewalRefusalApplies(now - 1, now, userAsked = false))
        assertFalse(SpotiFLACSessionRenewer.renewalRefusalApplies(0L, now, userAsked = false))
    }

    // ------------------------------------------------------------------
    // Classifying a failed renewal
    // ------------------------------------------------------------------

    @Test
    fun `a dead session is told apart from one the gateway will not rotate`() {
        // Measured on device for deezer: the gateway names the case itself.
        val dead = SpotiFLACSessionRenewer.classifyRenewalFailure(
            SpotiFLACSessionRenewer.SpotiFLACRenewalRefusedException(
                401,
                "{\"error\":\"Unauthorized\",\"code\":\"SESSION_INVALID\",\"origin\":\"gateway\",\"action\":\"bootstrap_session\"}",
            ),
        )
        assertTrue(dead.needsVerification)
        assertTrue(dead.remember)
        assertFalse(dead.refused)
        assertTrue(dead.detail.contains("verify this source again"))

        // A 403 signature refusal is not a dead session: it may work later.
        val refused = SpotiFLACSessionRenewer.classifyRenewalFailure(
            SpotiFLACSessionRenewer.SpotiFLACRenewalRefusedException(403, "{\"error\":\"Forbidden\"}"),
        )
        assertFalse(refused.needsVerification)
        assertTrue(refused.refused)
        assertTrue(refused.remember)

        // A bare 401 is still remembered - it will not start working in the next minute - but it is
        // not presented as a source the user has to verify again.
        val signature = SpotiFLACSessionRenewer.classifyRenewalFailure(
            SpotiFLACSessionRenewer.SpotiFLACRenewalRefusedException(401, "{}"),
        )
        assertFalse(signature.needsVerification)
        assertTrue(signature.refused)
        assertTrue(signature.remember)

        // A transport failure is neither, and must not be remembered: it is worth retrying as soon
        // as the network is back.
        val offline = SpotiFLACSessionRenewer.classifyRenewalFailure(
            java.net.UnknownHostException("api.zarz.moe"),
        )
        assertFalse(offline.needsVerification)
        assertFalse(offline.refused)
        assertFalse(offline.remember)
        assertTrue(offline.detail.startsWith("request failed"))
    }

    /**
     * The gateway blocks the whole client, and says for how long - and that answer was ignored.
     *
     * Measured on the reporting device: `HTTP 429 {"error":"Temporarily blocked. Please try again
     * later.","retry_after":78542}` for every source, and because a 429 fell through to the generic
     * branch (``remember = false``) the scheduled renewer asked again an hour later and the playback
     * path asked on every track. Asking inside a block is the one thing that extends it, so a source
     * could report itself verified while nothing it asked for ever came back.
     */
    @Test
    fun `a gateway block is remembered, with the wait the gateway asked for`() {
        val blocked = SpotiFLACSessionRenewer.classifyRenewalFailure(
            SpotiFLACSessionRenewer.SpotiFLACRenewalRefusedException(
                429,
                "{\"error\":\"Temporarily blocked. Please try again later.\",\"retry_after\":78542}",
            ),
        )
        assertTrue(blocked.remember)
        assertTrue(blocked.refused)
        // Not a Cloudflare matter: sending the user to a verification for this is a dead end.
        assertFalse(blocked.needsVerification)
        assertEquals(78_542_000L, blocked.retryAfterMs)
        assertTrue(blocked.detail.contains("blocking renewals"))
        assertTrue(blocked.detail.contains("22h"))
    }

    /** A block with no stated wait is still a block, and still remembered. */
    @Test
    fun `a block with no stated wait falls back to a remembered refusal`() {
        val blocked = SpotiFLACSessionRenewer.classifyRenewalFailure(
            SpotiFLACSessionRenewer.SpotiFLACRenewalRefusedException(429, "{\"error\":\"Too many requests\"}"),
        )
        assertTrue(blocked.remember)
        assertTrue(blocked.refused)
        assertFalse(blocked.needsVerification)
        assertNull(blocked.retryAfterMs)
        assertTrue(blocked.detail.contains("429"))
    }

    /**
     * A `retry_after` that cannot be trusted is clamped, never discarded.
     *
     * The answer is always "not yet" when it is given at all, so the only question is how long: a
     * negative, a zero or a fortnight must not become "never", and must not become "immediately"
     * either - which would put the app back to asking inside the block.
     */
    @Test
    fun `an unreasonable block length is clamped rather than trusted`() {
        assertEquals(null, SpotiFLACSessionRenewer.retryAfterMs("not json"))
        assertEquals(null, SpotiFLACSessionRenewer.retryAfterMs("{\"retry_after\":0}"))
        assertEquals(null, SpotiFLACSessionRenewer.retryAfterMs("{\"retry_after\":-60}"))
        // A minute is a floor, not a real wait: nothing is ever released that fast.
        assertEquals(5 * 60_000L, SpotiFLACSessionRenewer.retryAfterMs("{\"retry_after\":60}"))
        // A week is a ceiling, so a wrong number cannot take a source out of use for good.
        assertEquals(24 * 3600_000L, SpotiFLACSessionRenewer.retryAfterMs("{\"retry_after\":604800}"))
        // The hyphenated spelling is accepted too, because the same answer is sometimes given there.
        assertEquals(10 * 60_000L, SpotiFLACSessionRenewer.retryAfterMs("{\"retry-after\":600}"))
    }

    /**
     * A run that asked nothing must not overwrite what the gateway last said. Without this the
     * refusal memo - which reports itself as a refusal - replaced a real "this session is dead" on
     * the next app start, and the row went back to promising a renewal.
     */
    @Test
    fun `only a run that learned something records a verdict`() {
        fun result(
            detail: String = "",
            renewed: Boolean = false,
            needsVerification: Boolean = false,
            refused: Boolean = false,
            contactedGateway: Boolean = false,
        ) = SpotiFLACSessionRenewer.RenewResult(
            extensionId = "deezer",
            renewed = renewed,
            detail = detail,
            needsVerification = needsVerification,
            refused = refused,
            contactedGateway = contactedGateway,
        )

        // The gateway answered. Whatever it said is worth keeping.
        assertTrue(SpotiFLACSessionRenewer.verdictIsWorthRecording(result(renewed = true, contactedGateway = true)))
        assertTrue(
            SpotiFLACSessionRenewer.verdictIsWorthRecording(
                result(needsVerification = true, contactedGateway = true),
            ),
        )
        assertTrue(
            SpotiFLACSessionRenewer.verdictIsWorthRecording(
                result(refused = true, contactedGateway = true),
            ),
        )

        // Worked out without asking: the record cannot serve this source at all.
        assertTrue(SpotiFLACSessionRenewer.verdictIsWorthRecording(result(needsVerification = true)))

        // No news: not due, or the memo answering for the gateway.
        assertFalse(SpotiFLACSessionRenewer.verdictIsWorthRecording(result(detail = "not due (355m left)")))
        assertFalse(
            SpotiFLACSessionRenewer.verdictIsWorthRecording(
                result(detail = "gateway refuses renewal (retry in 353m)", refused = true),
            ),
        )
        assertFalse(SpotiFLACSessionRenewer.verdictIsWorthRecording(result(detail = "unreadable session record")))
        assertFalse(SpotiFLACSessionRenewer.verdictIsWorthRecording(result(detail = "incomplete session record")))
    }

    /**
     * A renewal result and a row verdict must not be able to disagree: the row is built from what
     * this maps to, and a result reported as dead on the renew line while the row says "renews
     * automatically" is the whole bug this pairing exists to stop.
     */
    @Test
    fun `each renewal outcome maps to the verdict the row reads`() {
        fun result(
            renewed: Boolean = false,
            needsVerification: Boolean = false,
            refused: Boolean = false,
        ) = SpotiFLACSessionRenewer.RenewResult(
            extensionId = "deezer",
            renewed = renewed,
            detail = "",
            needsVerification = needsVerification,
            refused = refused,
        )

        assertEquals(
            SpotiFLACSessionVerdict.Outcome.RENEWED,
            SpotiFLACSessionRenewer.verdictOutcome(result(renewed = true)),
        )
        assertEquals(
            SpotiFLACSessionVerdict.Outcome.NEEDS_VERIFICATION,
            SpotiFLACSessionRenewer.verdictOutcome(result(needsVerification = true)),
        )
        assertEquals(
            SpotiFLACSessionVerdict.Outcome.REFUSED,
            SpotiFLACSessionRenewer.verdictOutcome(result(refused = true)),
        )
        // Not due, superseded, nothing asked: says nothing about the session either way.
        assertEquals(
            SpotiFLACSessionVerdict.Outcome.SKIPPED,
            SpotiFLACSessionRenewer.verdictOutcome(result()),
        )
    }

    /**
     * The gateway's own request for a verification, which is the one thing a renewal cannot
     * satisfy. Measured with a session whose signature it accepts:
     * `428 {"error":"VERIFY_REQUIRED","action":"verify"}` from both `/v2/tickets` and
     * `/v2/session/refresh`. Reported as `request failed: HTTP 428` it named neither the cause nor
     * the fix.
     */
    @Test
    fun `the gateway asking for a verification is reported as one`() {
        val verify = SpotiFLACSessionRenewer.classifyRenewalFailure(
            SpotiFLACSessionRenewer.SpotiFLACRenewalRefusedException(
                428,
                "{\"error\":\"VERIFY_REQUIRED\",\"code\":\"VERIFY_REQUIRED\",\"origin\":\"gateway\",\"action\":\"verify\"}",
            ),
        )
        assertTrue(verify.needsVerification)
        assertFalse(verify.refused)
        assertTrue(verify.remember)
        assertTrue(verify.detail.contains("verify this source"))

        // The status alone is enough: a gateway that decides differently for another source must not
        // turn into a transport failure.
        val bare = SpotiFLACSessionRenewer.classifyRenewalFailure(
            SpotiFLACSessionRenewer.SpotiFLACRenewalRefusedException(428, ""),
        )
        assertTrue(bare.needsVerification)
        assertFalse(bare.refused)
    }

    @Test
    fun `a manual renew names what happened to every source`() {
        fun result(id: String, renewed: Boolean, needsVerification: Boolean = false, refused: Boolean = false) =
            SpotiFLACSessionRenewer.RenewResult(
                extensionId = id,
                renewed = renewed,
                detail = "",
                needsVerification = needsVerification,
                refused = refused,
            )

        assertTrue(SpotiFLACSessionRenewer.summarise(emptyList()).startsWith("No verified sources"))
        assertEquals(
            "Renewed amazon",
            SpotiFLACSessionRenewer.summarise(listOf(result("amazon", renewed = true))),
        )
        assertEquals(
            "Renewed all 3 sessions",
            SpotiFLACSessionRenewer.summarise(
                listOf(
                    result("amazon", true),
                    result("deezer", true),
                    result("qobuz-web", true),
                ),
            ),
        )
        // The exact run measured on device: one renewed, one dead, two refused.
        val mixed = SpotiFLACSessionRenewer.summarise(
            listOf(
                result("amazon", renewed = true),
                result("deezer", renewed = false, needsVerification = true),
                result("qobuz-web", renewed = false, refused = true),
                result("tidal-web", renewed = false, refused = true),
            ),
        )
        assertEquals(
            "Renewed amazon · deezer needs a new verification · qobuz-web and tidal-web refused by " +
                "the gateway, try again later",
            mixed,
        )
        // A long list stays a line: the first two are named, the rest counted.
        val many = SpotiFLACSessionRenewer.summarise(
            (1..4).map { result("source-$it", renewed = false, refused = true) },
        )
        assertEquals(
            "Source-1, source-2 and 2 more refused by the gateway, try again later",
            many,
        )
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

    // ------------------------------------------------------------------
    // The gateway's client-wide block — a 429 is a statement about the
    // address, not about one source's session, and everything that would
    // ask the gateway has to wait on it
    // ------------------------------------------------------------------

    @Test
    fun `a block in force reports what is left of it`() {
        val now = 1_000_000L
        val block = SpotiFLACSessionRenewer.relayBlockAt(
            untilMs = now + 20 * 3600_000L,
            storedReason = "gateway is blocking renewals for ~20h",
            nowMs = now,
        )
        assertEquals(now + 20 * 3600_000L, block?.untilMs)
        assertEquals(20 * 3600_000L, block?.remainingMs)
        assertEquals("gateway is blocking renewals for ~20h", block?.reason)
    }

    @Test
    fun `a block whose wait is over reads as no block`() {
        assertNull(
            "a deadline in the past must not hold playback back",
            SpotiFLACSessionRenewer.relayBlockAt(
                untilMs = 999_000L,
                storedReason = "gateway is blocking renewals for ~20h",
                nowMs = 1_000_000L,
            ),
        )
    }

    @Test
    fun `a block stored without wording still says how long is left`() {
        // The deadline is the fact and the wording is only how it is said, so a record written by an
        // older build (or half-written) must still report a wait rather than read as no block.
        val block = SpotiFLACSessionRenewer.relayBlockAt(
            untilMs = 1_000_000L + 90 * 60_000L,
            storedReason = null,
            nowMs = 1_000_000L,
        )
        assertEquals(90 * 60_000L, block?.remainingMs)
        assertTrue(block?.reason?.isNotBlank() == true)
    }

    @Test
    fun `a block earned on the route still in use keeps holding`() {
        val block = SpotiFLACSessionRenewer.relayBlockAt(
            untilMs = 1_000_000L + 20 * 3600_000L,
            storedReason = null,
            nowMs = 1_000_000L,
        )
        val route = SpotiFLACRouteFingerprint.of(
            proxyEnabled = false,
            proxyType = null,
            proxyHost = null,
            proxyPort = null,
            network = "cell",
        )
        assertEquals(
            block,
            SpotiFLACSessionRenewer.relayBlockHeldAgainst(
                block = block,
                recordedRoute = route,
                currentRoute = route,
            ),
        )
    }

    @Test
    fun `a block earned on another route stops holding playback back`() {
        // The measured case this exists for: the same install the gateway refused with a ~21h wait was
        // served normally the moment a VPN came up. The block was still on disk and still "valid" by
        // its own clock, and it kept suppressing every source for the rest of the day.
        val block = SpotiFLACSessionRenewer.relayBlockAt(
            untilMs = 1_000_000L + 20 * 3600_000L,
            storedReason = null,
            nowMs = 1_000_000L,
        )
        val direct = SpotiFLACRouteFingerprint.of(false, null, null, null, "cell")
        val throughVpn = SpotiFLACRouteFingerprint.of(false, null, null, null, "vpn+cell")
        assertNull(
            SpotiFLACSessionRenewer.relayBlockHeldAgainst(
                block = block,
                recordedRoute = direct,
                currentRoute = throughVpn,
            ),
        )
    }

    @Test
    fun `a block earned on another address stops holding playback back`() {
        // The move the durable route cannot see, and the one that outlived the network it was about:
        // both Wi-Fi networks are `exit=direct net=wifi`, so the route half still "describes" the one in
        // use and the wait would keep suppressing every source on a network that was never refused.
        val block = SpotiFLACSessionRenewer.relayBlockAt(
            untilMs = 1_000_000L + 20 * 3600_000L,
            storedReason = null,
            nowMs = 1_000_000L,
        )
        val wifi = SpotiFLACRouteFingerprint.of(false, null, null, null, "wifi")
        val atHome = SpotiFLACRouteFingerprint.liveAddressOf(wifi, "Network{100}|192.168.1.24")
        val inTheCar = SpotiFLACRouteFingerprint.liveAddressOf(wifi, "Network{101}|192.168.8.3")
        assertNull(
            SpotiFLACSessionRenewer.relayBlockHeldAgainst(
                block = block,
                recordedRoute = wifi,
                recordedAddress = atHome,
                currentRoute = wifi,
                currentAddress = inTheCar,
            ),
        )
    }

    @Test
    fun `a network revalidating does not retire the block`() {
        // The settling flag belongs to "is this route worth asking about again", not to "is this the
        // address the gateway refused": a network throwing its validated flag while it reconnects is the
        // same address, and treating it as a move would throw away a block that is still true.
        val block = SpotiFLACSessionRenewer.relayBlockAt(
            untilMs = 1_000_000L + 20 * 3600_000L,
            storedReason = null,
            nowMs = 1_000_000L,
        )
        val wifi = SpotiFLACRouteFingerprint.of(false, null, null, null, "wifi")
        val address = SpotiFLACRouteFingerprint.liveAddressOf(wifi, "Network{100}|192.168.1.24")
        assertEquals(
            block,
            SpotiFLACSessionRenewer.relayBlockHeldAgainst(
                block = block,
                recordedRoute = wifi,
                recordedAddress = address,
                currentRoute = wifi,
                currentAddress = address,
            ),
        )
    }

    @Test
    fun `a block with no recorded address is still held`() {
        val block = SpotiFLACSessionRenewer.relayBlockAt(
            untilMs = 1_000_000L + 20 * 3600_000L,
            storedReason = null,
            nowMs = 1_000_000L,
        )
        val wifi = SpotiFLACRouteFingerprint.of(false, null, null, null, "wifi")
        val address = SpotiFLACRouteFingerprint.liveAddressOf(wifi, "Network{100}|192.168.1.24")
        assertEquals(
            block,
            SpotiFLACSessionRenewer.relayBlockHeldAgainst(
                block = block,
                recordedRoute = wifi,
                recordedAddress = null,
                currentRoute = wifi,
                currentAddress = address,
            ),
        )
    }

    @Test
    fun `a block of unknown provenance still counts as a block`() {
        // A record written before routes were kept, or written while the network could not be read.
        // Treating "unknown" as "different route" would turn every such record into a fresh ask at a
        // gateway that had already refused the client - which is how a block is extended, not cleared.
        val block = SpotiFLACSessionRenewer.relayBlockAt(
            untilMs = 1_000_000L + 20 * 3600_000L,
            storedReason = null,
            nowMs = 1_000_000L,
        )
        val current = SpotiFLACRouteFingerprint.of(false, null, null, null, "wifi")
        assertEquals(
            block,
            SpotiFLACSessionRenewer.relayBlockHeldAgainst(
                block = block,
                recordedRoute = null,
                currentRoute = current,
            ),
        )
        assertEquals(
            block,
            SpotiFLACSessionRenewer.relayBlockHeldAgainst(
                block = block,
                recordedRoute = current,
                currentRoute = null,
            ),
        )
    }

    @Test
    fun `only a renewal proves a route change actually cleared the block`() {
        // Three states, not two - and the third is the one that matters here, because a route change
        // often happens *because* the network is moving: a request that died in a tunnel must not be
        // read as "the gateway is serving us again", or the app replays a track into the same wall.
        val block =
            SpotiFLACSessionRenewer.RelayBlock(
                untilMs = 1_000_000L,
                reason = "gateway is refusing this connection (HTTP 429)",
                remainingMs = 1_000L,
            )
        assertFalse(
            "nothing came back at all",
            SpotiFLACSessionRenewer.ReaskOutcome(
                block = null,
                renewedAny = false,
                asked = false,
                answering = false,
            ).servedAgain,
        )
        assertFalse(
            "asked, but the request died before the gateway saw it",
            SpotiFLACSessionRenewer.ReaskOutcome(
                block = null,
                renewedAny = false,
                asked = true,
                answering = false,
            ).servedAgain,
        )
        assertFalse(
            "renewed something and still refused is still blocked",
            SpotiFLACSessionRenewer.ReaskOutcome(
                block = block,
                renewedAny = true,
                asked = true,
                answering = false,
            ).servedAgain,
        )
        assertTrue(
            "a relay that answered this route without refusing is the proof",
            SpotiFLACSessionRenewer.ReaskOutcome(
                block = null,
                renewedAny = false,
                asked = true,
                answering = true,
            ).servedAgain,
        )
    }

    @Test
    fun `only a session the gateway actually renewed clears the block`() {
        fun result(
            renewed: Boolean = false,
            contacted: Boolean = false,
            refused: Boolean = false,
        ) = SpotiFLACSessionRenewer.RenewResult(
            extensionId = "deezer",
            renewed = renewed,
            detail = "detail",
            refused = refused,
            contactedGateway = contacted,
        )

        // A run that asked nothing (not due, or answered out of a memo) proves nothing.
        assertFalse(
            SpotiFLACSessionRenewer.relayBlockClearedBy(listOf(result(contacted = false, refused = true))),
        )
        assertFalse(
            SpotiFLACSessionRenewer.relayBlockClearedBy(listOf(result(contacted = false))),
        )
        assertFalse(
            "a refusal is the block, not its end",
            SpotiFLACSessionRenewer.relayBlockClearedBy(listOf(result(contacted = true, refused = true))),
        )
        // The rule this replaces: "any answer that was not a refusal". Read off the device, one run
        // held three refusals carrying the gateway's own ~20h wait while a fourth source answered out
        // of its own record - and that single non-failure threw away the block the other three had
        // just established. A sweep then re-ran the whole provider chain inside the block.
        assertFalse(
            "a source that merely did not fail proves nothing about the client",
            SpotiFLACSessionRenewer.relayBlockClearedBy(
                listOf(
                    result(contacted = true, refused = true),
                    result(contacted = true, refused = true),
                    result(contacted = true, refused = true),
                    result(contacted = true),
                ),
            ),
        )
        assertTrue(
            "a renewal is the gateway serving this client again",
            SpotiFLACSessionRenewer.relayBlockClearedBy(listOf(result(renewed = true, contacted = true))),
        )
    }

    @Test
    fun `the wait is spoken in hours and minutes`() {
        assertEquals("19h 12m", SpotiFLACSessionRenewer.formatBlockRemaining(19 * 3600_000L + 12 * 60_000L))
        assertEquals("1h 0m", SpotiFLACSessionRenewer.formatBlockRemaining(3600_000L))
        assertEquals("30m", SpotiFLACSessionRenewer.formatBlockRemaining(30 * 60_000L))
        // Rounded up, so a wait of a few seconds never reads as "0m" while it is still blocking.
        assertEquals("1m", SpotiFLACSessionRenewer.formatBlockRemaining(1_000L))
        assertEquals("0m", SpotiFLACSessionRenewer.formatBlockRemaining(0L))
    }

    @Test
    fun `a blocked sweep is an unfinished sweep, never a catalogue verdict`() {
        val failure = relayBlockFailure(
            SpotiFLACSessionRenewer.relayBlockAt(
                untilMs = 1_000_000L + 20 * 3600_000L,
                storedReason = "gateway is blocking renewals for ~20h",
                nowMs = 1_000_000L,
            ),
        )
        assertTrue("a block must fail the sweep", failure != null)
        assertTrue(
            "the reason has to survive to the user",
            failure!!.message!!.contains("429"),
        )
        assertTrue(
            "the reason names the wait",
            failure.message!!.contains("20h"),
        )
        // The distinction that decides whether the track is retried soon or hidden from SpotiFLAC
        // for hours: a blocked sweep never reached the catalogues, so it is not a miss.
        assertEquals(
            SpotiFLACSweepOutcome.UNAVAILABLE,
            SpotiFLACSweepVerdict.forFailure(failure),
        )
    }

    @Test
    fun `an unblocked client is not refused a sweep`() {
        assertNull(relayBlockFailure(SpotiFLACSessionRenewer.relayBlockAt(0L, null, 1_000_000L)))
        assertNull(relayBlockFailure(null))
    }

    @Test
    fun `a health answer names the wait when the relay gives one`() {
        // The exact body the relay returns while blacklisting an address, captured from the host -
        // unclamped, because ~20.9h sits inside the bounds and the wait is the gateway's own number.
        assertEquals(
            75_358 * 1000L,
            SpotiFLACSessionRenewer.blockWaitFromAnswer(
                429,
                """{"error":"Temporarily blocked. Please try again later.","retry_after":75358}""",
            ),
        )
    }

    @Test
    fun `a health answer without a wait still counts as a block`() {
        // A 429 is the client's answer even when the gateway does not say for how long; ignoring it
        // is what leaves every sweep in the block re-asking. The shortest honest wait is used, and
        // the next probe either extends it with real timing or clears it.
        assertEquals(
            SpotiFLACSessionRenewer.BLOCK_MIN_MS_FOR_TEST,
            SpotiFLACSessionRenewer.blockWaitFromAnswer(429, """{"error":"busy"}"""),
        )
        assertEquals(
            SpotiFLACSessionRenewer.BLOCK_MIN_MS_FOR_TEST,
            SpotiFLACSessionRenewer.blockWaitFromAnswer(429, ""),
        )
    }

    @Test
    fun `anything but a 429 from health is not a block`() {
        // The endpoint is unauthenticated, so a 401 or a 404 says nothing about whether this
        // connection is being refused - reading either as a block would pause SpotiFLAC for a wait
        // nobody asked for.
        assertNull(SpotiFLACSessionRenewer.blockWaitFromAnswer(200, """{"status":"ok"}"""))
        assertNull(SpotiFLACSessionRenewer.blockWaitFromAnswer(401, """{"error":"unauthorized"}"""))
        assertNull(SpotiFLACSessionRenewer.blockWaitFromAnswer(404, "not found"))
        assertNull(SpotiFLACSessionRenewer.blockWaitFromAnswer(503, """{"retry_after":600}"""))
    }
}
