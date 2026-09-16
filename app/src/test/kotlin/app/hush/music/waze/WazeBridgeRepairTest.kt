/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.waze

import app.hush.music.waze.WazeBridgeRepair.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules a Bridge repair follows.
 *
 * These pin the two failure modes that made a repair either dead-end or stop halfway: a platform
 * refusal that never asked the user, and a repair that waited for an uninstall result its device
 * never sent.
 */
class WazeBridgeRepairTest {

    @Test
    fun `a repair started moments ago is still worth resuming`() {
        // The capture: the app was killed between the system uninstall and the install, so the target
        // has to survive in storage - but only while it is still the user's session.
        assertTrue(WazeBridgeRepair.isRepairFresh(startedAtMs = 1_000L, nowMs = 60_000L))
    }

    @Test
    fun `a repair older than the window is abandoned, not resumed`() {
        val started = 1_000L
        assertFalse(
            WazeBridgeRepair.isRepairFresh(
                startedAtMs = started,
                nowMs = started + WazeBridgeRepair.MAX_AGE_MS + 1,
            ),
        )
    }

    @Test
    fun `a repair with no recorded start is not resumed`() {
        // A target written by an older build has no timestamp; guessing it is fresh would install a
        // bridge from an unrelated session.
        assertFalse(WazeBridgeRepair.isRepairFresh(startedAtMs = 0L, nowMs = 60_000L))
    }

    @Test
    fun `a clock that moved backwards does not make a repair immortal`() {
        assertFalse(WazeBridgeRepair.isRepairFresh(startedAtMs = 60_000L, nowMs = 1_000L))
    }

    @Test
    fun `a bridge that is installed has to be removed first`() {
        assertEquals(Step.START_UNINSTALL, WazeBridgeRepair.stepForRequest(installed = true))
    }

    @Test
    fun `a bridge that is absent needs no removal`() {
        assertEquals(Step.INSTALL_BUNDLED, WazeBridgeRepair.stepForRequest(installed = false))
    }

    @Test
    fun `a confirmation from the platform means waiting for the outcome`() {
        assertEquals(
            Step.AWAIT_CONFIRMATION,
            WazeBridgeRepair.stepForUninstallResult(
                status = WazeBridgeUninstall.Status.PENDING_CONFIRMATION,
                confirmationSeen = true,
                isRepair = true,
            ),
        )
    }

    @Test
    fun `a refusal without a confirmation opens the system uninstall instead of dead-ending`() {
        assertEquals(
            Step.OPEN_SYSTEM_UNINSTALL,
            WazeBridgeRepair.stepForUninstallResult(
                status = WazeBridgeUninstall.Status.FAILED,
                confirmationSeen = false,
                isRepair = true,
            ),
        )
    }

    @Test
    fun `a cancellation after the confirmation is not retried automatically`() {
        assertEquals(
            Step.REPORT_CANCELLED,
            WazeBridgeRepair.stepForUninstallResult(
                status = WazeBridgeUninstall.Status.FAILED,
                confirmationSeen = true,
                isRepair = true,
            ),
        )
    }

    @Test
    fun `a removal during a repair continues into installing the bundled bridge`() {
        assertEquals(
            Step.COMPLETE_WITH_INSTALL,
            WazeBridgeRepair.stepForUninstallResult(
                status = WazeBridgeUninstall.Status.SUCCEEDED,
                confirmationSeen = true,
                isRepair = true,
            ),
        )
    }

    @Test
    fun `a removal that was not a repair installs nothing`() {
        assertEquals(
            Step.REPORT_REMOVED,
            WazeBridgeRepair.stepForUninstallResult(
                status = WazeBridgeUninstall.Status.SUCCEEDED,
                confirmationSeen = true,
                isRepair = false,
            ),
        )
    }

    @Test
    fun `a repair waits while its bridge is still installed`() {
        assertEquals(
            Step.WAIT,
            WazeBridgeRepair.stepForRepairCheck(
                target = "deezer.android.app",
                installedPackages = setOf("deezer.android.app", "com.spotify.music"),
            ),
        )
    }

    @Test
    fun `a repair completes once its bridge is gone, whichever route removed it`() {
        // The system's uninstall screen does not report back on every device, so the package's own
        // state is what decides this - not an uninstall result.
        assertEquals(
            Step.COMPLETE_WITH_INSTALL,
            WazeBridgeRepair.stepForRepairCheck(
                target = "deezer.android.app",
                installedPackages = setOf("com.spotify.music"),
            ),
        )
    }

    @Test
    fun `a repair with no bridge gone and no target does nothing`() {
        assertEquals(Step.WAIT, WazeBridgeRepair.stepForRepairCheck(target = null, installedPackages = emptySet()))
        assertEquals(
            Step.WAIT,
            WazeBridgeRepair.stepForRepairCheck(target = null, installedPackages = setOf("deezer.android.app")),
        )
    }

    @Test
    fun `the repair target records which bridge is mid-repair`() {
        WazeBridgeRepair.clearRepair()
        assertFalse(WazeBridgeRepair.isRepairing("deezer.android.app"))

        WazeBridgeRepair.beginRepair("deezer.android.app")
        assertTrue(WazeBridgeRepair.isRepairing("deezer.android.app"))
        assertFalse(WazeBridgeRepair.isRepairing("com.spotify.music"))
        assertFalse(WazeBridgeRepair.isRepairing(null))

        WazeBridgeRepair.clearRepair()
        assertFalse(WazeBridgeRepair.isRepairing("deezer.android.app"))
    }
}
