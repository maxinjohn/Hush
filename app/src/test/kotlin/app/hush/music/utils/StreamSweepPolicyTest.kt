/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 and Section 5
 */

package app.hush.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Separating "this address is being challenged" from "this track cannot be served".
 *
 * The reporting device's log had both in the same sweep: three families answered `LOGIN_REQUIRED`
 * ("Please sign in"), one client said the video was unavailable, WEB_REMIX produced formats this
 * device cannot decipher - and the track was abandoned and skipped. The gates were about the VPN
 * exit, and the same requests answered fully minutes later, so a retry is what turns a skip back
 * into playback. A track that is genuinely gone must still fail immediately, without the wait.
 */
class StreamSweepPolicyTest {

    @Test
    fun `a sweep that produced a stream has nothing to retry`() {
        assertEquals(
            StreamSweepOutcome.RESOLVED,
            StreamSweepPolicy.outcome(gatedFamilies = setOf("ANDROID_MUSIC"), producedCandidate = true),
        )
    }

    @Test
    fun `an address refusal is worth asking again for`() {
        assertEquals(
            StreamSweepOutcome.ADDRESS_CHALLENGED,
            StreamSweepPolicy.outcome(gatedFamilies = setOf("ANDROID_MUSIC", "IOS_MUSIC")),
        )
    }

    @Test
    fun `a track nobody refused by address is not retried`() {
        // Nothing was gated and nothing resolved: every client answered about the track itself, so a
        // second sweep asks the same question and makes the user wait for the same answer.
        assertEquals(
            StreamSweepOutcome.UNANSWERED,
            StreamSweepPolicy.outcome(gatedFamilies = emptySet()),
        )
    }

    @Test
    fun `one gated family among many failures is still treated as the address`() {
        // The gated family is the only client that said *why*, and its reason is about the address.
        // Retrying is capped and cheap; abandoning the track is not recoverable by the user.
        assertEquals(
            StreamSweepOutcome.ADDRESS_CHALLENGED,
            StreamSweepPolicy.outcome(gatedFamilies = setOf("ANDROID_UNPLUGGED")),
        )
    }

    @Test
    fun `the retries are short enough to be worth waiting for`() {
        val first = StreamSweepPolicy.retryDelayMs(1)
        val second = StreamSweepPolicy.retryDelayMs(2)

        assertEquals(2_000L, first)
        assertEquals(6_000L, second)
        assertTrue("a retry must not outlast a sweep", (second ?: 0L) < 20_000L)
    }

    @Test
    fun `the retries run out instead of hammering a challenged address`() {
        assertNull(StreamSweepPolicy.retryDelayMs(0))
        assertNull(StreamSweepPolicy.retryDelayMs(StreamSweepPolicy.MAX_ADDRESS_RETRIES + 1))
    }

    @Test
    fun `an address challenge carries what would have been reported`() {
        val underlying = YTPlayerUtils.BadStreamPlayerResponseException("gnrSpwb2Ax4")
        val error =
            StreamAddressChallengedException(
                videoId = "gnrSpwb2Ax4",
                clientFamilies = setOf("ANDROID_MUSIC", "IOS_MUSIC"),
                underlying = underlying,
            )

        assertEquals(underlying, error.underlying)
        assertEquals(setOf("ANDROID_MUSIC", "IOS_MUSIC"), error.clientFamilies)
        assertTrue(error.message.orEmpty().contains("gnrSpwb2Ax4"))
    }
}
