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
    fun `the run is a checklist with the first source being solved and the rest waiting`() {
        // Forced, like the settings screen's own run, so this asserts the checklist rather than
        // whatever cooldown another test happened to leave behind.
        SpotiFLAutoVerifier.enqueue(listOf("deezer", "qobuz-web", "tidal-web"), "settings-verify-all", force = true)

        assertEquals(
            listOf(
                SpotiFLAutoVerifier.Step("deezer", SpotiFLAutoVerifier.Step.State.SOLVING),
                SpotiFLAutoVerifier.Step("qobuz-web", SpotiFLAutoVerifier.Step.State.WAITING),
                SpotiFLAutoVerifier.Step("tidal-web", SpotiFLAutoVerifier.Step.State.WAITING),
            ),
            SpotiFLAutoVerifier.steps.value,
        )
    }

    @Test
    fun `each source keeps its own outcome as the run moves on`() {
        SpotiFLAutoVerifier.enqueue(listOf("deezer", "qobuz-web", "tidal-web"), "settings-verify-all", force = true)
        SpotiFLAutoVerifier.finish("deezer", verified = true)
        SpotiFLAutoVerifier.finish("qobuz-web", verified = false)
        // The cooldown this records is real and outlives the test; hand it back so later tests queue
        // the source normally.
        SpotiFLAutoVerifier.clearFailureForTest("qobuz-web")

        val states = SpotiFLAutoVerifier.steps.value.associate { it.sourceId to it.state }
        // A source that landed must not read as waiting for its turn, and one that gave up must not
        // read as done - the two are the whole point of showing this.
        assertEquals(SpotiFLAutoVerifier.Step.State.VERIFIED, states["deezer"])
        assertEquals(SpotiFLAutoVerifier.Step.State.NEEDS_CHECK, states["qobuz-web"])
        assertEquals(SpotiFLAutoVerifier.Step.State.SOLVING, states["tidal-web"])
    }

    @Test
    fun `one action's marks survive until the next one starts`() {
        SpotiFLAutoVerifier.enqueue(listOf("deezer", "qobuz-web"), "settings-verify-all", force = true)
        SpotiFLAutoVerifier.finish("deezer", verified = true)
        SpotiFLAutoVerifier.finish("qobuz-web", verified = true)

        // The run is over, and what it did is still on the screen: clearing here would erase the
        // only report the user gets about a button that may have taken a minute per source.
        assertEquals(2, SpotiFLAutoVerifier.steps.value.size)
        assertTrue(SpotiFLAutoVerifier.steps.value.all { it.state == SpotiFLAutoVerifier.Step.State.VERIFIED })

        // A run started later replaces it, so the checklist can never describe the run before it.
        SpotiFLAutoVerifier.enqueue(listOf("amazon", "deezer"), "settings-verify-all", force = true)
        assertEquals(listOf("amazon", "deezer"), SpotiFLAutoVerifier.steps.value.map { it.sourceId })
    }

    @Test
    fun `a prewarm joining a run adds itself without disturbing it`() {
        SpotiFLAutoVerifier.enqueue(listOf("deezer", "qobuz-web"), "settings-verify-all", force = true)
        SpotiFLAutoVerifier.finish("deezer", verified = true)

        SpotiFLAutoVerifier.enqueue(listOf("tidal-web"), SpotiFLAutoVerifier.BACKGROUND_REASON, force = true)

        assertEquals(
            listOf("deezer", "qobuz-web", "tidal-web"),
            SpotiFLAutoVerifier.steps.value.map { it.sourceId },
        )
        assertEquals(SpotiFLAutoVerifier.Step.State.VERIFIED, SpotiFLAutoVerifier.steps.value.first().state)
    }

    @Test
    fun `a source that needs no check never appears in the checklist`() {
        SpotiFLAutoVerifier.enqueue(listOf("soundcloud", "deezer"), "settings-verify-all", force = true)
        SpotiFLAutoVerifier.finishNotRequired("soundcloud")

        // It was never asked for a check, so claiming it as verified would be a solved challenge
        // that never happened.
        assertTrue(SpotiFLAutoVerifier.steps.value.none { it.sourceId == "soundcloud" })
        assertEquals(listOf("deezer"), SpotiFLAutoVerifier.steps.value.map { it.sourceId })
    }

    @Test
    fun `cancelling drops the checklist with the run`() {
        SpotiFLAutoVerifier.enqueue(listOf("deezer", "qobuz-web"), "settings-verify-all", force = true)
        SpotiFLAutoVerifier.cancel()
        assertTrue(SpotiFLAutoVerifier.steps.value.isEmpty())
    }

    @Test
    fun `the progress line counts what the run did, not what is left`() {
        assertNull(SpotiFLACVerificationChecklist.progressLine(emptyList()))
        assertEquals(
            "0 of 3 checked",
            SpotiFLACVerificationChecklist.progressLine(
                listOf(
                    SpotiFLAutoVerifier.Step("a", SpotiFLAutoVerifier.Step.State.SOLVING),
                    SpotiFLAutoVerifier.Step("b", SpotiFLAutoVerifier.Step.State.WAITING),
                    SpotiFLAutoVerifier.Step("c", SpotiFLAutoVerifier.Step.State.WAITING),
                ),
            ),
        )
        // "Checked" counts the ones the run is done with, failures included: a line that stops
        // moving when a source fails is the run a user is watching most closely.
        assertEquals(
            "3 of 3 checked · 1 needs a check",
            SpotiFLACVerificationChecklist.progressLine(
                listOf(
                    SpotiFLAutoVerifier.Step("a", SpotiFLAutoVerifier.Step.State.VERIFIED),
                    SpotiFLAutoVerifier.Step("b", SpotiFLAutoVerifier.Step.State.VERIFIED),
                    SpotiFLAutoVerifier.Step("c", SpotiFLAutoVerifier.Step.State.NEEDS_CHECK),
                ),
            ),
        )
        assertEquals(
            "3 of 3 checked · 2 need a check",
            SpotiFLACVerificationChecklist.progressLine(
                listOf(
                    SpotiFLAutoVerifier.Step("a", SpotiFLAutoVerifier.Step.State.VERIFIED),
                    SpotiFLAutoVerifier.Step("b", SpotiFLAutoVerifier.Step.State.NEEDS_CHECK),
                    SpotiFLAutoVerifier.Step("c", SpotiFLAutoVerifier.Step.State.NEEDS_CHECK),
                ),
            ),
        )
    }

    @Test
    fun `the checklist is drawn only while a run is happening`() {
        // The marks outlive the run, so this is the rule that keeps "Solving…" off a screen whose
        // run finished ten minutes ago.
        assertFalse(SpotiFLACVerificationChecklist.isRunning(active = null))
        assertTrue(SpotiFLACVerificationChecklist.isRunning(active = "deezer"))

        assertEquals("Solving…", SpotiFLACVerificationChecklist.text(SpotiFLAutoVerifier.Step.State.SOLVING))
        assertEquals("Waiting", SpotiFLACVerificationChecklist.text(SpotiFLAutoVerifier.Step.State.WAITING))
        assertEquals("Verified", SpotiFLACVerificationChecklist.text(SpotiFLAutoVerifier.Step.State.VERIFIED))
        assertEquals("Needs a check", SpotiFLACVerificationChecklist.text(SpotiFLAutoVerifier.Step.State.NEEDS_CHECK))
        assertEquals(
            SpotiFLACVerificationChecklist.Tone.ATTENTION,
            SpotiFLACVerificationChecklist.tone(SpotiFLAutoVerifier.Step.State.NEEDS_CHECK),
        )
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
