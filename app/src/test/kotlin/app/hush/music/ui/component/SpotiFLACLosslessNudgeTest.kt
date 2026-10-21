/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.component

import app.hush.music.constants.SpotiFLACEnabledDefault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the rule that decides whether the launch-time lossless offer may be made.
 *
 * The rule is one line and every wrong direction is a user-visible annoyance that a device test
 * would take a reinstall per case to find: offering the feature to someone who dismissed it,
 * offering it a third time on an install that has had its two - the same banner every launch until
 * it is dismissed by reflex - or, the one that wastes the user's time rather than the app's, offering
 * it in a build that has no runtime, which sends them to a screen whose switch cannot do anything.
 *
 * The cap is checked at both ends of its boundary and past it, because an off-by-one here is the
 * difference between "twice" and "every launch". The runtime half is checked with the offer
 * otherwise due, because "available" is the only input a caller could accidentally stop passing.
 */
class SpotiFLACLosslessNudgeTest {
    @Test
    fun `offered on the first and second ask when the feature is off and unanswered`() {
        assertTrue(losslessOfferDue(enabled = false, handled = false, asksMade = 0, runtimeAvailable = true))
        assertTrue(losslessOfferDue(enabled = false, handled = false, asksMade = 1, runtimeAvailable = true))
    }

    @Test
    fun `not offered once the two asks are spent`() {
        assertFalse(losslessOfferDue(enabled = false, handled = false, asksMade = 2, runtimeAvailable = true))
        assertFalse(losslessOfferDue(enabled = false, handled = false, asksMade = 3, runtimeAvailable = true))
    }

    @Test
    fun `the cap is two asks`() {
        // The promise the user was given, and the reason the boundary above is where it is.
        assertEquals(2, LosslessNudgeMaxAsks)
    }

    @Test
    fun `not offered to someone who is already using it`() {
        assertFalse(losslessOfferDue(enabled = true, handled = false, asksMade = 0, runtimeAvailable = true))
    }

    @Test
    fun `a fresh install is not offered the engine it is already running`() {
        // The offer is decided from the stored preference, which a fresh install does not have - and
        // that absence reads as on, so the install the offer used to chase is the one that never
        // sees it. Reading the absence as off in this one place would put the banner back on the
        // launch where lossless is already playing.
        assertTrue(SpotiFLACEnabledDefault)
        assertFalse(
            losslessOfferDue(
                enabled = SpotiFLACEnabledDefault,
                handled = false,
                asksMade = 0,
                runtimeAvailable = true,
            ),
        )
    }

    @Test
    fun `not offered again once it has been dismissed`() {
        assertFalse(losslessOfferDue(enabled = false, handled = true, asksMade = 0, runtimeAvailable = true))
        assertFalse(losslessOfferDue(enabled = false, handled = true, asksMade = 1, runtimeAvailable = true))
    }

    @Test
    fun `not offered in a build without the lossless runtime`() {
        assertFalse(losslessOfferDue(enabled = false, handled = false, asksMade = 0, runtimeAvailable = false))
        assertFalse(losslessOfferDue(enabled = true, handled = true, asksMade = 2, runtimeAvailable = false))
    }

    @Test
    fun `the offer is held back before it is shown and stays up longer than a status pill`() {
        // The reveal delay exists so the offer does not land in the launch frame; a delay of zero
        // would put it back there, and an auto-hide shorter than the status banners' 2.5s would cut
        // off the two lines of text it exists to show. The auto-hide ending an appearance is fine
        // now that the appearance itself is what the cap counts.
        assertTrue(RevealDelayMillis > 0)
        assertTrue(AutoHideMillis > 2_500L)
    }
}
