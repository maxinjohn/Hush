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
 * Reading a provider's own health, and what a sweep does with it.
 *
 * The payload in the first test is the one this device produced while Deezer was down on the
 * gateway's side - the case every sweep used to pay for and misreport as a bad track.
 */
class SpotiFLACProviderHealthTest {

    private val deezerOffline = """
        {"extension_id":"deezer","status":"offline","checked_at":"2026-09-19T22:28:38Z",
         "checks":[{"id":"zarz-api","label":"Zarz API","url":"https://api.zarz.moe/v1/health",
         "method":"GET","ok":false,"error":"fetch failed"}]}
    """.trimIndent()

    @Test
    fun `a provider that reports itself offline is unavailable, named by its failing check`() {
        val verdict = SpotiFLACProviderHealth.verdict("deezer", deezerOffline)
        assertEquals(
            SpotiFLACProviderHealth.Verdict.Unavailable("Zarz API: fetch failed"),
            verdict,
        )
    }

    /**
     * The same outage, as the *installed* packages report it: a `status` plus a `message` rather
     * than `ok` plus `error`. Both are real payloads from the reporting device, and reading only the
     * older shape left this one with no failing check to name - so the reason fell back to the bare
     * status word while the Test on the same bytes named the check.
     */
    private val deezerOfflineCurrentShape = """
        {"extension_id":"deezer","status":"offline","checked_at":"2026-09-20T18:13:06Z",
         "checks":[{"id":"zarz-api","label":"Zarz API","url":"https://api.zarz.moe/v1/health",
         "method":"GET","service_key":"deezer","required":true,"status":"offline",
         "http_status":200,"latency_ms":288,"message":"Deezer: fetch failed"}]}
    """.trimIndent()

    @Test
    fun `the shape the installed packages send is named by its own message`() {
        assertEquals(
            SpotiFLACProviderHealth.Verdict.Unavailable("Deezer: fetch failed"),
            SpotiFLACProviderHealth.verdict("deezer", deezerOfflineCurrentShape),
        )
    }

    @Test
    fun `a check that was never run is not reported as the reason`() {
        val payload = """
            {"extension_id":"deezer","status":"offline","checks":[
             {"id":"zarz-api","label":"Zarz API","status":"skipped"},
             {"id":"catalogue","label":"Catalogue","status":"offline","message":"Deezer: fetch failed"}]}
        """.trimIndent()
        assertEquals(
            SpotiFLACProviderHealth.Verdict.Unavailable("Deezer: fetch failed"),
            SpotiFLACProviderHealth.verdict("deezer", payload),
        )
    }

    @Test
    fun `a healthy provider is available`() {
        assertEquals(
            SpotiFLACProviderHealth.Verdict.Available,
            SpotiFLACProviderHealth.verdict("tidal-web", """{"extension_id":"tidal-web","status":"ok"}"""),
        )
    }

    @Test
    fun `an unreadable answer demotes nobody`() {
        assertNull(SpotiFLACProviderHealth.verdict("deezer", null))
        assertNull(SpotiFLACProviderHealth.verdict("deezer", ""))
        assertNull(SpotiFLACProviderHealth.verdict("deezer", "not json at all"))
        assertNull(SpotiFLACProviderHealth.verdict("deezer", """{"extension_id":"deezer"}"""))
        assertNull(SpotiFLACProviderHealth.verdict("deezer", """{"status":"something-new"}"""))
    }

    @Test
    fun `a degraded provider counts as unavailable`() {
        val verdict = SpotiFLACProviderHealth.verdict("qobuz-web", """{"status":"degraded"}""")
        assertEquals(SpotiFLACProviderHealth.Verdict.Unavailable("degraded"), verdict)
    }

    @Test
    fun `an offline provider moves last, behind one that only stalled`() {
        val now = 10_000_000L
        val ordered = SpotiFLACProviderStallPolicy.orderAvailable(
            candidates = listOf("deezer", "tidal-web", "qobuz-web"),
            stalledAtMs = mapOf("tidal-web" to now - 1_000L),
            rateLimitedAtMs = emptyMap(),
            nowMs = now,
            unavailableAtMs = mapOf("deezer" to now - 1_000L),
        )
        assertEquals(listOf("qobuz-web", "tidal-web", "deezer"), ordered)
    }

    @Test
    fun `a provider whose report has expired takes its place back`() {
        val now = 10_000_000L
        val expired = now - SpotiFLACProviderStallPolicy.UNAVAILABLE_COOLDOWN_MS - 1
        val ordered = SpotiFLACProviderStallPolicy.orderAvailable(
            candidates = listOf("deezer", "tidal-web"),
            stalledAtMs = emptyMap(),
            rateLimitedAtMs = emptyMap(),
            nowMs = now,
            unavailableAtMs = mapOf("deezer" to expired),
        )
        assertEquals(listOf("deezer", "tidal-web"), ordered)
    }

    @Test
    fun `when every provider reports itself down the full chain is still asked`() {
        val now = 10_000_000L
        val down = listOf("deezer", "pandora").associateWith { now }
        val ordered = SpotiFLACProviderStallPolicy.orderAvailable(
            candidates = listOf("deezer", "pandora"),
            stalledAtMs = emptyMap(),
            rateLimitedAtMs = emptyMap(),
            nowMs = now,
            unavailableAtMs = down,
        )
        assertEquals(listOf("deezer", "pandora"), ordered)
    }

    @Test
    fun `the tail of a sweep keeps the answered prefix and demotes the down provider`() {
        val now = 10_000_000L
        val reordered = SpotiFLACProviderStallPolicy.orderRemainingAvailable(
            candidates = listOf("amazon", "deezer", "tidal-web"),
            attemptedCount = 1,
            stalledAtMs = emptyMap(),
            rateLimitedAtMs = emptyMap(),
            nowMs = now,
            unavailableAtMs = mapOf("deezer" to now),
        )
        assertEquals(listOf("amazon", "tidal-web", "deezer"), reordered)
    }

    @Test
    fun `a source is only demoted while its report is inside the cooldown`() {
        val now = 1_000_000L
        assertTrue(SpotiFLACProviderStallPolicy.isUnavailable(now - 1_000L, now))
        assertFalse(SpotiFLACProviderStallPolicy.isUnavailable(null, now))
        assertFalse(SpotiFLACProviderStallPolicy.isUnavailable(now - 601_000L, now))
    }
}
