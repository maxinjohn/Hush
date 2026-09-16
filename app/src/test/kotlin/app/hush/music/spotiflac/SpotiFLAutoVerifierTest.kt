/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The automatic verification queue must advance (a stuck source would block every
 * later one) and must not spin on a source that just failed.
 */
class SpotiFLAutoVerifierTest {

    @After
    fun tearDown() {
        SpotiFLAutoVerifier.cancel()
        SpotiFLACVerificationRequest.dismiss()
    }

    @Test
    fun `enqueue starts the first source and queues the rest in order`() {
        SpotiFLAutoVerifier.enqueue(listOf("deezer", "qobuz-web", "tidal-web"), "prewarm")
        assertEquals("deezer", SpotiFLAutoVerifier.active.value)
        assertEquals(listOf("deezer", "qobuz-web", "tidal-web"), SpotiFLAutoVerifier.queued())
    }

    @Test
    fun `finish advances to the next queued source`() {
        SpotiFLAutoVerifier.enqueue(listOf("deezer", "qobuz-web"), "test")
        SpotiFLAutoVerifier.finish("deezer", verified = true)
        assertEquals("qobuz-web", SpotiFLAutoVerifier.active.value)
        SpotiFLAutoVerifier.finish("qobuz-web", verified = true)
        assertNull(SpotiFLAutoVerifier.active.value)
        assertTrue(SpotiFLAutoVerifier.queued().isEmpty())
    }

    @Test
    fun `a failed source is not retried immediately but still lets the queue advance`() {
        SpotiFLAutoVerifier.enqueue(listOf("deezer", "qobuz-web"), "test")
        SpotiFLAutoVerifier.finish("deezer", verified = false)
        assertEquals("qobuz-web", SpotiFLAutoVerifier.active.value)

        // Re-enqueuing the failed source right away must be refused, or an
        // interactive challenge would loop forever.
        SpotiFLAutoVerifier.enqueue(listOf("deezer"), "playback")
        assertFalse(SpotiFLAutoVerifier.queued().contains("deezer"))
    }

    /**
     * A parked track is a concrete reason to try again now, so the hold path bypasses
     * the cooldown - otherwise a verification that failed while the phone was offline
     * would keep playback parked for ten minutes after the network came back.
     */
    @Test
    fun `a forced enqueue ignores the failure cooldown`() {
        val now = System.currentTimeMillis()
        SpotiFLAutoVerifier.recordFailureForTest("deezer", now)
        SpotiFLAutoVerifier.enqueue(listOf("deezer"), "held", force = true)
        assertEquals(listOf("deezer"), SpotiFLAutoVerifier.queued())
    }

    @Test
    fun `a successful source can be enqueued again immediately`() {
        SpotiFLAutoVerifier.enqueue(listOf("deezer"), "test")
        SpotiFLAutoVerifier.finish("deezer", verified = true)
        SpotiFLAutoVerifier.enqueue(listOf("deezer"), "playback")
        assertEquals(listOf("deezer"), SpotiFLAutoVerifier.queued())
    }

    @Test
    fun `duplicate enqueues are collapsed`() {
        SpotiFLAutoVerifier.enqueue(listOf("deezer", "deezer"), "test")
        assertEquals(listOf("deezer"), SpotiFLAutoVerifier.queued())
    }

    @Test
    fun `the cooldown expires`() {
        val now = 1_800_000_000_000L
        SpotiFLAutoVerifier.recordFailureForTest("deezer", now)
        assertTrue(SpotiFLAutoVerifier.inCooldown("deezer", now + 60_000L))
        assertFalse(SpotiFLAutoVerifier.inCooldown("deezer", now + 11 * 60_000L))
        // An unknown source was never attempted, so it is never in cooldown.
        assertFalse(SpotiFLAutoVerifier.inCooldown("tidal-web", now))
    }

    @Test
    fun `a verification completed outside the queue still wakes playback`() {
        // The Audio Sources screen hosts its own challenge, and a source verified there was never
        // in this queue - yet the track playback parked on it still has to be resumed, which is
        // what the ticker and the listener exist for.
        val before = SpotiFLAutoVerifier.verifiedTicker.value
        SpotiFLAutoVerifier.notifyVerified("deezer")
        assertNull(SpotiFLAutoVerifier.active.value)
        assertEquals(before + 1, SpotiFLAutoVerifier.verifiedTicker.value)
    }

    @Test
    fun `a verification clears the pending request it answers`() {
        SpotiFLACVerificationRequest.request("qobuz-web")
        assertEquals("qobuz-web", SpotiFLACVerificationRequest.pending.value)
        SpotiFLAutoVerifier.notifyVerified("qobuz-web")
        assertNull(SpotiFLACVerificationRequest.pending.value)
    }

    @Test
    fun `a verified source outside the queue does not disturb a run in flight`() {
        SpotiFLAutoVerifier.enqueue(listOf("deezer", "qobuz-web"), "test")
        SpotiFLAutoVerifier.notifyVerified("tidal-web")
        // The source being worked on is untouched: this reports someone else's success, not a
        // completion of the active challenge.
        assertEquals("deezer", SpotiFLAutoVerifier.active.value)
    }

    @Test
    fun `cancel clears the queue and the active source`() {
        SpotiFLAutoVerifier.enqueue(listOf("deezer", "qobuz-web"), "test")
        SpotiFLAutoVerifier.cancel()
        assertNull(SpotiFLAutoVerifier.active.value)
        assertTrue(SpotiFLAutoVerifier.queued().isEmpty())
        assertFalse(SpotiFLAutoVerifier.isRunning)
    }
}
