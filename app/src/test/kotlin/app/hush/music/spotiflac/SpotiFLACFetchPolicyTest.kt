package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Pins the two halves of the per-source fetch setting: how it is stored, and which failures a retry
 * can actually fix.
 *
 * The storage half matters because the value is one JSON string shared by every source, so a bug in
 * the encoding is a bug in every source's windows at once - and because the defaults have to survive
 * being round-tripped through a preference the user may never have opened.
 *
 * The classification half is what keeps the default of three attempts from turning every catalogue
 * miss into three identical requests. A stall, a dropped socket or a timeout is a statement about
 * *this attempt* and is worth repeating; "no results", a quality refusal and an unplayable answer are
 * statements about the request, and repeating the request reaches the same answer.
 */
class SpotiFLACFetchPolicyTest {

    // ------------------------------------------------------------------ storage

    @Test
    fun `a source with nothing stored gets the shipped policy`() {
        assertEquals(SpotiFLACFetchPolicy.DEFAULT, SpotiFLACFetchPolicies.policyFor(null, "deezer"))
        assertEquals(SpotiFLACFetchPolicy.DEFAULT, SpotiFLACFetchPolicies.policyFor("", "deezer"))
        assertEquals(5_000L, SpotiFLACFetchPolicies.policyFor(null, "deezer").connectTimeoutMs)
        assertEquals(60_000L, SpotiFLACFetchPolicies.policyFor(null, "deezer").downloadTimeoutMs)
        assertEquals(3, SpotiFLACFetchPolicies.policyFor(null, "deezer").maxAttempts)
    }

    @Test
    fun `a saved policy comes back for the source it was saved for, and only that one`() {
        val policy = SpotiFLACFetchPolicy(connectTimeoutSeconds = 12, downloadTimeoutSeconds = 180, maxAttempts = 4)
        val raw = SpotiFLACFetchPolicies.updated(raw = null, sourceId = "qobuz-web", policy = policy)

        assertEquals(policy, SpotiFLACFetchPolicies.policyFor(raw, "qobuz-web"))
        // An id is an id whatever the case its caller happens to have.
        assertEquals(policy, SpotiFLACFetchPolicies.policyFor(raw, "Qobuz-Web"))
        assertEquals(SpotiFLACFetchPolicy.DEFAULT, SpotiFLACFetchPolicies.policyFor(raw, "deezer"))
        assertEquals(1, SpotiFLACFetchPolicies.customizedCount(raw))
    }

    @Test
    fun `saving twice replaces the entry rather than growing a second one`() {
        var raw = SpotiFLACFetchPolicies.updated(null, "deezer", SpotiFLACFetchPolicy(maxAttempts = 2))
        raw = SpotiFLACFetchPolicies.updated(raw, "deezer", SpotiFLACFetchPolicy(maxAttempts = 5))

        assertEquals(5, SpotiFLACFetchPolicies.policyFor(raw, "deezer").maxAttempts)
        assertEquals(1, SpotiFLACFetchPolicies.decode(raw).size)
    }

    @Test
    fun `a policy saved back at the default is stored as nothing`() {
        var raw = SpotiFLACFetchPolicies.updated(null, "deezer", SpotiFLACFetchPolicy(maxAttempts = 2))
        assertEquals(1, SpotiFLACFetchPolicies.customizedCount(raw))

        raw = SpotiFLACFetchPolicies.updated(raw, "deezer", SpotiFLACFetchPolicy.DEFAULT)
        assertEquals(0, SpotiFLACFetchPolicies.customizedCount(raw))
        assertEquals(SpotiFLACFetchPolicy.DEFAULT, SpotiFLACFetchPolicies.policyFor(raw, "deezer"))
    }

    @Test
    fun `resetting a source clears its entry`() {
        var raw = SpotiFLACFetchPolicies.updated(null, "deezer", SpotiFLACFetchPolicy(maxAttempts = 2))
        raw = SpotiFLACFetchPolicies.updated(raw, "deezer", null)

        assertEquals(0, SpotiFLACFetchPolicies.customizedCount(raw))
        assertEquals(SpotiFLACFetchPolicy.DEFAULT, SpotiFLACFetchPolicies.policyFor(raw, "deezer"))
    }

    @Test
    fun `the stored value round-trips through encode and decode`() {
        val policies =
            mapOf(
                "deezer" to SpotiFLACFetchPolicy(8, 120, 2),
                "qobuz-web" to SpotiFLACFetchPolicy(20, 300, 5),
            )
        val raw = SpotiFLACFetchPolicies.encode(policies)

        assertEquals(policies, SpotiFLACFetchPolicies.decode(raw))
    }

    @Test
    fun `a value the app cannot read is treated as no policies rather than as a crash`() {
        assertEquals(emptyMap<String, SpotiFLACFetchPolicy>(), SpotiFLACFetchPolicies.decode("not json"))
        assertEquals(emptyMap<String, SpotiFLACFetchPolicy>(), SpotiFLACFetchPolicies.decode("[1,2,3]"))
        // The shipped policy answers for every source, so a corrupt value costs a default, not a source.
        assertEquals(
            SpotiFLACFetchPolicy.DEFAULT,
            SpotiFLACFetchPolicies.policyFor("not json", "deezer"),
        )
    }

    @Test
    fun `a stored value is clamped and written sorted, so it is stable between saves`() {
        val raw =
            SpotiFLACFetchPolicies.encode(
                mapOf(
                    "zebra" to SpotiFLACFetchPolicy(connectTimeoutSeconds = 1_000, maxAttempts = 42),
                    "amazon" to SpotiFLACFetchPolicy(downloadTimeoutSeconds = 1),
                ),
            )

        assertEquals(
            SpotiFLACFetchPolicy.MAX_CONNECT_SECONDS,
            SpotiFLACFetchPolicies.policyFor(raw, "zebra").connectTimeoutSeconds,
        )
        assertEquals(SpotiFLACFetchPolicy.MAX_ATTEMPTS, SpotiFLACFetchPolicies.policyFor(raw, "zebra").maxAttempts)
        assertEquals(
            SpotiFLACFetchPolicy.MIN_DOWNLOAD_SECONDS,
            SpotiFLACFetchPolicies.policyFor(raw, "amazon").downloadTimeoutSeconds,
        )
        // Re-encoding the decoded map produces the identical string.
        assertEquals(raw, SpotiFLACFetchPolicies.encode(SpotiFLACFetchPolicies.decode(raw)))
        assertTrue(raw.indexOf("amazon") < raw.indexOf("zebra"))
    }

    // ------------------------------------------------------- retry classification

    @Test
    fun `a stall is worth asking again`() {
        val stalled =
            SpotiFLACProviderStalledException(
                sourceId = "qobuz-web",
                stage = "downloading",
                stalledMillis = 60_000L,
            )
        assertTrue(SpotiFLACFetchRetry.isTransient(stalled))
    }

    @Test
    fun `a transport failure is worth asking again`() {
        assertTrue(SpotiFLACFetchRetry.isTransient(IOException("unexpected end of stream")))
        assertTrue(SpotiFLACFetchRetry.isTransient(SocketTimeoutException("read timed out")))
        assertTrue(
            SpotiFLACFetchRetry.isTransient(
                SpotiFLACException("download failed for deezer", RuntimeException("connection reset")),
            ),
        )
    }

    @Test
    fun `an answer is not asked for twice`() {
        // Catalogue verdicts, quality refusals and unplayable answers are all answers. Repeating the
        // request reaches the same one, so the walk moves on instead.
        assertFalse(SpotiFLACFetchRetry.isTransient(SpotiFLACException("no results returned")))
        assertFalse(SpotiFLACFetchRetry.isTransient(SpotiFLACException("track not found in catalogue")))
        assertFalse(
            SpotiFLACFetchRetry.isTransient(
                SpotiFLACException("provider 'deezer' has no compatible lossless quality for \"LOSSLESS\""),
            ),
        )
        assertFalse(
            SpotiFLACFetchRetry.isTransient(
                SpotiFLACException(
                    "provider 'amazon' ${SpotiFLACQualityCascade.UNPLAYABLE_FORMAT_MARKER} (Dolby ac4)",
                ),
            ),
        )
        assertFalse(SpotiFLACFetchRetry.isTransient(SpotiFLACException("HTTP 404 for this item")))
        assertFalse(SpotiFLACFetchRetry.isTransient(null))
    }

    @Test
    fun `a verification demand and a relay block are not retried`() {
        assertFalse(
            SpotiFLACFetchRetry.isTransient(
                SpotiFLACVerificationRequiredException("deezer", "Verification required for deezer"),
            ),
        )
        assertFalse(
            SpotiFLACFetchRetry.isTransient(
                SpotiFLACRelayBlockedException(remainingMs = 60_000L, reason = "HTTP 429"),
            ),
        )
    }

    @Test
    fun `the backoff grows with the attempt and stays short`() {
        assertTrue(SpotiFLACFetchRetry.backoffMs(2) > SpotiFLACFetchRetry.backoffMs(1))
        assertTrue(SpotiFLACFetchRetry.backoffMs(3) <= 2_000L)
        assertTrue(SpotiFLACFetchRetry.backoffMs(50) <= 2_000L)
        assertTrue(SpotiFLACFetchRetry.backoffMs(0) > 0L)
    }
}
