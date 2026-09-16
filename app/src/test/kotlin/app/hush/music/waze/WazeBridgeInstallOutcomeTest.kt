/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.waze

import app.hush.music.waze.WazeBridgeInstallOutcome.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How an install attempt is classified and reported.
 *
 * The case that mattered in practice is the last two: Google Play Protect holds a sideloaded install
 * after the installer screen closes, the package is not there yet, and the user used to be told a
 * bare "not installed" while the prompt blocking it sat on screen unmentioned.
 */
class WazeBridgeInstallOutcomeTest {

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
    ) = WazeBridgeInspection(
        definition = definition,
        state = state,
        installedVersionCode = installedVersionCode,
        requiredProtocolVersion = definition.requiredProtocolVersion,
    )

    private fun kindOf(
        wasInstalled: Boolean,
        previousVersionCode: Long?,
        finalInspection: WazeBridgeInspection,
    ) = WazeBridgeInstallOutcome.kind(wasInstalled, previousVersionCode, finalInspection)

    private fun messageOf(
        kind: Kind,
        verificationEnabled: Boolean,
        alreadyRetried: Boolean,
    ) = WazeBridgeInstallOutcome.message(
        kind = kind,
        displayName = definition.displayName,
        fallbackText = "Not installed",
        verificationEnabled = verificationEnabled,
        alreadyRetried = alreadyRetried,
    )

    @Test
    fun `an install that landed is reported as installed`() {
        val kind = kindOf(
            wasInstalled = false,
            previousVersionCode = null,
            finalInspection = inspection(WazeBridgeState.BRIDGE_CURRENT, installedVersionCode = 1711),
        )
        assertEquals(Kind.INSTALLED, kind)
        assertTrue(WazeBridgeInstallOutcome.landed(kind))
        assertEquals("Hush-Bridge (Deezer) installed successfully.", messageOf(kind, true, false))
    }

    @Test
    fun `an update that landed is reported as updated`() {
        val kind = kindOf(
            wasInstalled = true,
            previousVersionCode = 1700,
            finalInspection = inspection(WazeBridgeState.BRIDGE_CURRENT, installedVersionCode = 1711),
        )
        assertEquals(Kind.UPDATED, kind)
        assertTrue(WazeBridgeInstallOutcome.landed(kind))
        assertEquals("Hush-Bridge (Deezer) updated successfully.", messageOf(kind, true, false))
    }

    @Test
    fun `a bridge that was already current is neither installed nor retried`() {
        val kind = kindOf(
            wasInstalled = true,
            previousVersionCode = 1711,
            finalInspection = inspection(WazeBridgeState.BRIDGE_CURRENT, installedVersionCode = 1711),
        )
        assertEquals(Kind.ALREADY_CURRENT, kind)
        assertFalse(WazeBridgeInstallOutcome.landed(kind))
        assertFalse(WazeBridgeInstallOutcome.shouldRetry(kind, verificationEnabled = true, alreadyRetried = false))
    }

    @Test
    fun `an install that did not land is a failed install`() {
        val kind = kindOf(
            wasInstalled = false,
            previousVersionCode = null,
            finalInspection = inspection(WazeBridgeState.NOT_INSTALLED),
        )
        assertEquals(Kind.FAILED_INSTALL, kind)
        assertFalse(WazeBridgeInstallOutcome.landed(kind))
    }

    @Test
    fun `an update that did not take is a failed update, not a fresh install`() {
        val kind = kindOf(
            wasInstalled = true,
            previousVersionCode = 1700,
            finalInspection = inspection(WazeBridgeState.BRIDGE_UPDATE_AVAILABLE, installedVersionCode = 1700),
        )
        assertEquals(Kind.FAILED_UPDATE, kind)
        // With a verifier present the same failure is reported as the Play Protect wait instead.
        assertEquals("Hush-Bridge (Deezer) was not updated.", messageOf(kind, verificationEnabled = false, alreadyRetried = false))
    }

    @Test
    fun `an interrupted install is retried once on a device that verifies sideloads`() {
        val kind = Kind.FAILED_INSTALL
        assertTrue(WazeBridgeInstallOutcome.mayBeAwaitingVerifier(kind, verificationEnabled = true))
        assertTrue(WazeBridgeInstallOutcome.shouldRetry(kind, verificationEnabled = true, alreadyRetried = false))
        assertFalse(WazeBridgeInstallOutcome.shouldRetry(kind, verificationEnabled = true, alreadyRetried = true))
    }

    @Test
    fun `a device that does not verify sideloads gets no Play Protect story and no retry`() {
        val kind = Kind.FAILED_INSTALL
        assertFalse(WazeBridgeInstallOutcome.mayBeAwaitingVerifier(kind, verificationEnabled = false))
        assertFalse(WazeBridgeInstallOutcome.shouldRetry(kind, verificationEnabled = false, alreadyRetried = false))
        assertEquals("Hush-Bridge (Deezer) was not installed.", messageOf(kind, false, false))
    }

    @Test
    fun `an install waiting on the verifier says what to choose`() {
        val message = messageOf(Kind.FAILED_INSTALL, verificationEnabled = true, alreadyRetried = false)
        assertTrue(message, message.contains("Play Protect"))
        assertTrue(message, message.contains("Scan app"))
    }

    @Test
    fun `an install the verifier held through the retry says to install again`() {
        val message = messageOf(Kind.FAILED_INSTALL, verificationEnabled = true, alreadyRetried = true)
        assertTrue(message, message.contains("Scan app"))
        assertTrue(message, message.contains("install again"))
    }

    @Test
    fun `an update held by the verifier is phrased as an install problem`() {
        val message = messageOf(Kind.FAILED_UPDATE, verificationEnabled = true, alreadyRetried = true)
        assertTrue(message, message.contains("Scan app"))
    }

    @Test
    fun `an unrecognised ending falls back to the bridge's own state`() {
        // The installed version disappeared during the attempt - no case above describes that.
        val kind = kindOf(
            wasInstalled = true,
            previousVersionCode = 1711,
            finalInspection = inspection(WazeBridgeState.NOT_INSTALLED),
        )
        assertEquals(Kind.OTHER, kind)
        assertEquals("Hush-Bridge (Deezer): Not installed.", messageOf(kind, true, false))
    }

    @Test
    fun `only a failure that could be the verifier earns a retry`() {
        assertFalse(WazeBridgeInstallOutcome.shouldRetry(Kind.INSTALLED, true, false))
        assertFalse(WazeBridgeInstallOutcome.shouldRetry(Kind.UPDATED, true, false))
        assertFalse(WazeBridgeInstallOutcome.shouldRetry(Kind.ALREADY_CURRENT, true, false))
        assertFalse(WazeBridgeInstallOutcome.shouldRetry(Kind.OTHER, true, false))
    }
}
