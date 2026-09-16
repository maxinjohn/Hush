/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.waze

import app.hush.music.waze.WazeBridgeInstallOutcome.Kind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chain an install attempt runs: settle, classify, retry once when the device's package verifier
 * is what interrupted it, then report the *retry's* outcome.
 *
 * The polling delays are emptied and the pauses made no-ops, so these run in milliseconds while
 * exercising the real ordering.
 */
class WazeBridgeInstallAttemptTest {

    private val definition = WazeBridgeDefinition(
        id = "deezer",
        displayName = "Hush-Bridge (Deezer)",
        providerName = "Deezer",
        packageName = "deezer.android.app",
        assetPath = "waze-shims/waze-shim-deezer-release.apk",
        requiredProtocolVersion = 1,
    )

    private fun inspection(
        state: WazeBridgeState,
        installedVersionCode: Long? = null,
        bundledVersionCode: Long? = 1711,
    ) = WazeBridgeInspection(
        definition = definition,
        state = state,
        installedVersionCode = installedVersionCode,
        bundledVersionCode = bundledVersionCode,
        requiredProtocolVersion = definition.requiredProtocolVersion,
    )

    /** A reader that walks through the given states and then repeats the last one. */
    private class States(private vararg val states: WazeBridgeInspection) {
        var reads = 0
            private set

        suspend fun read(): WazeBridgeInspection = states[minOf(reads++, states.size - 1)]
    }

    private fun runAttempt(
        states: States,
        wasInstalled: Boolean,
        previousVersionCode: Long?,
        verificationEnabled: Boolean,
        relaunchRaised: Boolean = true,
        pollDelaysMs: List<Long> = emptyList(),
    ): Pair<WazeBridgeInstallAttempt.Outcome, Int> {
        var relaunches = 0
        val outcome = runBlocking {
            WazeBridgeInstallAttempt.run(
                wasInstalled = wasInstalled,
                previousVersionCode = previousVersionCode,
                verificationEnabled = verificationEnabled,
                inspect = { states.read() },
                relaunch = {
                    relaunches++
                    relaunchRaised
                },
                pollDelaysMs = pollDelaysMs,
                pause = { },
            )
        }
        return outcome to relaunches
    }

    @Test
    fun `an install that lands is reported installed and is never retried`() {
        val states = States(
            inspection(WazeBridgeState.BRIDGE_CURRENT, installedVersionCode = 1711),
        )
        val (outcome, relaunches) = runAttempt(
            states = states,
            wasInstalled = false,
            previousVersionCode = null,
            verificationEnabled = true,
        )
        assertEquals(Kind.INSTALLED, outcome.kind)
        assertTrue(outcome.landed)
        assertFalse(outcome.retrySpent)
        assertEquals(0, relaunches)
    }

    @Test
    fun `an install the verifier held is retried and the retry's outcome is what is reported`() {
        // First attempt: the installer screen closed and the package is not there. The retry is raised;
        // by the time it settles the verifier has been answered and the package exists.
        val states = States(
            inspection(WazeBridgeState.NOT_INSTALLED),
            inspection(WazeBridgeState.NOT_INSTALLED),
            inspection(WazeBridgeState.BRIDGE_CURRENT, installedVersionCode = 1711),
        )
        val (outcome, relaunches) = runAttempt(
            states = states,
            wasInstalled = false,
            previousVersionCode = null,
            verificationEnabled = true,
        )
        assertEquals(Kind.INSTALLED, outcome.kind)
        assertTrue(outcome.landed)
        assertTrue(outcome.retrySpent)
        assertEquals(1, relaunches)
    }

    @Test
    fun `a slow install reports the wait once, and still reports the landing`() {
        var waitingNotices = 0
        val states = States(
            inspection(WazeBridgeState.NOT_INSTALLED),
            inspection(WazeBridgeState.NOT_INSTALLED),
            inspection(WazeBridgeState.BRIDGE_CURRENT, installedVersionCode = 1711),
        )
        val outcome = runBlocking {
            WazeBridgeInstallAttempt.run(
                wasInstalled = false,
                previousVersionCode = null,
                verificationEnabled = true,
                inspect = { states.read() },
                relaunch = { true },
                onStillWaiting = { waitingNotices++ },
                pollDelaysMs = listOf(2000L, 2000L, 2000L),
                pause = { },
            )
        }
        assertEquals(Kind.INSTALLED, outcome.kind)
        assertFalse(outcome.retrySpent)
        // Reported once, and the attempt kept watching until the install actually landed.
        assertEquals(1, waitingNotices)
    }

    @Test
    fun `an install that lands promptly never reports a wait`() {
        var waitingNotices = 0
        val states = States(inspection(WazeBridgeState.BRIDGE_CURRENT, installedVersionCode = 1711))
        val outcome = runBlocking {
            WazeBridgeInstallAttempt.run(
                wasInstalled = false,
                previousVersionCode = null,
                verificationEnabled = true,
                inspect = { states.read() },
                relaunch = { true },
                onStillWaiting = { waitingNotices++ },
                pollDelaysMs = listOf(2000L, 2000L),
                pause = { },
            )
        }
        assertEquals(Kind.INSTALLED, outcome.kind)
        assertEquals(0, waitingNotices)
    }

    @Test
    fun `a retry the verifier held too is reported once, with the retry spent`() {
        val states = States(inspection(WazeBridgeState.NOT_INSTALLED))
        val (outcome, relaunches) = runAttempt(
            states = states,
            wasInstalled = false,
            previousVersionCode = null,
            verificationEnabled = true,
            pollDelaysMs = listOf(1L, 1L),
        )
        assertEquals(Kind.FAILED_INSTALL, outcome.kind)
        assertFalse(outcome.landed)
        assertTrue(outcome.retrySpent)
        // At most one retry, however many times the state was re-read.
        assertEquals(1, relaunches)
        assertTrue(states.reads >= 2)
    }

    @Test
    fun `a device that does not verify sideloads gets no retry`() {
        val states = States(inspection(WazeBridgeState.NOT_INSTALLED))
        val (outcome, relaunches) = runAttempt(
            states = states,
            wasInstalled = false,
            previousVersionCode = null,
            verificationEnabled = false,
        )
        assertEquals(Kind.FAILED_INSTALL, outcome.kind)
        assertFalse(outcome.retrySpent)
        assertEquals(0, relaunches)
    }

    @Test
    fun `an update is settled by the version changing, not by the package being present`() {
        // The package is installed throughout an update, so presence alone would have declared this
        // update finished before it started.
        val states = States(
            inspection(WazeBridgeState.BRIDGE_UPDATE_AVAILABLE, installedVersionCode = 1700),
            inspection(WazeBridgeState.BRIDGE_CURRENT, installedVersionCode = 1711),
        )
        val (outcome, relaunches) = runAttempt(
            states = states,
            wasInstalled = true,
            previousVersionCode = 1700,
            verificationEnabled = true,
            pollDelaysMs = listOf(1L),
        )
        assertEquals(Kind.UPDATED, outcome.kind)
        assertTrue(outcome.landed)
        assertEquals(0, relaunches)
        assertEquals(2, states.reads)
    }

    @Test
    fun `an update the verifier held is retried on a device that verifies sideloads`() {
        val states = States(
            inspection(WazeBridgeState.BRIDGE_UPDATE_AVAILABLE, installedVersionCode = 1700),
        )
        val (outcome, relaunches) = runAttempt(
            states = states,
            wasInstalled = true,
            previousVersionCode = 1700,
            verificationEnabled = true,
        )
        assertEquals(Kind.FAILED_UPDATE, outcome.kind)
        assertTrue(outcome.retrySpent)
        assertEquals(1, relaunches)
    }

    @Test
    fun `an installer that cannot be raised leaves the first outcome and the retry spent`() {
        val states = States(inspection(WazeBridgeState.NOT_INSTALLED))
        val (outcome, relaunches) = runAttempt(
            states = states,
            wasInstalled = false,
            previousVersionCode = null,
            verificationEnabled = true,
            relaunchRaised = false,
        )
        assertEquals(Kind.FAILED_INSTALL, outcome.kind)
        assertTrue(outcome.retrySpent)
        assertEquals(1, relaunches)
    }

    @Test
    fun `a bridge that became installable on retry is not re-raised when it needs no install`() {
        // Landed between attempts (another route installed it): nothing to raise, nothing to wait for.
        val states = States(
            inspection(WazeBridgeState.NOT_INSTALLED),
            inspection(WazeBridgeState.BRIDGE_NEWER_THAN_BUNDLED, installedVersionCode = 1800),
        )
        val (outcome, relaunches) = runAttempt(
            states = states,
            wasInstalled = false,
            previousVersionCode = null,
            verificationEnabled = true,
        )
        assertEquals(Kind.FAILED_INSTALL, outcome.kind)
        assertTrue(outcome.retrySpent)
        assertEquals(0, relaunches)
    }
}
