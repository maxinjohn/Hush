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

    /**
     * The launch-time dialog: a background sweep that cannot finish unattended must report itself
     * in the log and in the sources list, and must not raise the "needs verification" prompt.
     */
    @Test
    fun `a background run that fails does not raise the manual notice`() {
        SpotiFLACVerificationRequest.dismiss()
        val id = "background-check-source"
        SpotiFLAutoVerifier.enqueue(listOf(id), SpotiFLAutoVerifier.BACKGROUND_REASON)
        SpotiFLAutoVerifier.finish(id, verified = false)
        assertNull(SpotiFLACVerificationRequest.pending.value)
    }

    /** A run somebody is waiting on still asks, because otherwise playback stays parked silently. */
    @Test
    fun `a run started by playback still raises the manual notice when it gives up`() {
        SpotiFLACVerificationRequest.dismiss()
        val id = "held-track-source"
        SpotiFLAutoVerifier.enqueue(listOf(id), "playback")
        SpotiFLAutoVerifier.finish(id, verified = false)
        assertEquals(id, SpotiFLACVerificationRequest.pending.value)
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
    fun `concurrent verification surfaces cannot corrupt the queue`() {
        // Regression for a real race: this queue is finished from five places on five threads - the
        // overlay and Audio Sources screen on main, the browser route on Dispatchers.Default, playback
        // and the runtime bridge on their own contexts - and the collections behind it used to be
        // plain, unsynchronized ones. A lost update leaves a source active forever, so every later one
        // waits behind it and verification looks like it does nothing. Any ConcurrentModification or
        // lost state shows up as a thrown exception here.
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = (1..8).map { index ->
            Thread {
                try {
                    repeat(40) { round ->
                        SpotiFLAutoVerifier.enqueue(listOf("src$index"), "race", force = true)
                        SpotiFLAutoVerifier.finish("src$index", verified = round % 2 == 0)
                        SpotiFLAutoVerifier.queued()
                        SpotiFLAutoVerifier.isRunning
                    }
                } catch (t: Throwable) {
                    failures.add(t)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue("the queue raced: ${failures.firstOrNull()}", failures.isEmpty())
        // Nothing can be left over: every source was finished as often as it was queued.
        assertTrue(SpotiFLAutoVerifier.queued().isEmpty())
    }

    @Test
    fun `cancel clears the queue and the active source`() {
        SpotiFLAutoVerifier.enqueue(listOf("deezer", "qobuz-web"), "test")
        SpotiFLAutoVerifier.cancel()
        assertNull(SpotiFLAutoVerifier.active.value)
        assertTrue(SpotiFLAutoVerifier.queued().isEmpty())
        assertFalse(SpotiFLAutoVerifier.isRunning)
    }

    @Test
    fun `turning SpotiFLAC off drops the run and the pending notice`() {
        // The preference flipping to off is the one moment the app must stop asking purely on its
        // own initiative: everything queued and everything already shown was raised for playback
        // that will no longer route through SpotiFLAC, and on a device whose WebView cannot run
        // Cloudflare's check the leftover offer is a browser tab a car user is asked to open for
        // nothing. `appContext` is deliberately not set here, which is the same degraded path the
        // verifier takes when it has no context: no notification bookkeeping, but never a crash.
        SpotiFLAutoVerifier.enqueue(listOf("amazon", "tidal-web"), "prewarm")
        SpotiFLACVerificationRequest.request("deezer")
        assertEquals("deezer", SpotiFLACVerificationRequest.pending.value)

        SpotiFLAutoVerifier.disableForPreferenceChange()

        assertNull(SpotiFLAutoVerifier.active.value)
        assertTrue(SpotiFLAutoVerifier.queued().isEmpty())
        assertNull(SpotiFLACVerificationRequest.pending.value)
    }

    /**
     * The browser route may only ever be opened on a user's behalf when the user asked for it.
     *
     * A background prewarm that opened browser tabs by itself would be worse than the failure it
     * was trying to remove, so the permission is granted per run and per source - never inherited
     * by a later prewarm that happens to queue the same source.
     */
    @Test
    fun `an all-sources run may open the browser, and a later prewarm may not`() {
        SpotiFLAutoVerifier.enqueue(listOf("deezer"), "settings-verify-all", force = true, browserFallback = true)
        assertTrue(SpotiFLAutoVerifier.allowsBrowserFallback("deezer"))
        // A source that was not part of the run is untouched.
        assertFalse(SpotiFLAutoVerifier.allowsBrowserFallback("qobuz-web"))

        SpotiFLAutoVerifier.finish("deezer", verified = false)
        assertFalse(SpotiFLAutoVerifier.allowsBrowserFallback("deezer"))

        // The next, ordinary run for the same source carries no such permission.
        SpotiFLAutoVerifier.enqueue(listOf("deezer"), "prewarm", force = true)
        assertFalse(SpotiFLAutoVerifier.allowsBrowserFallback("deezer"))
    }

    /**
     * A source already being worked on is normally left alone, but an explicit all-sources run has
     * to reach it: the permission it grants would otherwise never be used, and the user's one tap
     * would do nothing for the source that happened to be first.
     */
    @Test
    fun `an all-sources run re-queues the source that is currently in flight`() {
        SpotiFLAutoVerifier.enqueue(listOf("deezer", "qobuz-web"), "prewarm")
        assertEquals("deezer", SpotiFLAutoVerifier.active.value)

        SpotiFLAutoVerifier.enqueue(
            listOf("deezer", "qobuz-web"),
            "settings-verify-all",
            force = true,
            browserFallback = true,
        )

        assertTrue(SpotiFLAutoVerifier.allowsBrowserFallback("deezer"))
        assertTrue(SpotiFLAutoVerifier.queued().contains("deezer"))
    }

    @Test
    fun `cancelling drops a granted browser fallback`() {
        SpotiFLAutoVerifier.enqueue(listOf("deezer"), "settings-verify-all", force = true, browserFallback = true)
        SpotiFLAutoVerifier.cancel()
        assertFalse(SpotiFLAutoVerifier.allowsBrowserFallback("deezer"))
    }
}
