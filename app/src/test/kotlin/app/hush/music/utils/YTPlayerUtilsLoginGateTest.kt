/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & 5
 */

package app.hush.music.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins which of YouTube's refusals count as "signing in can fix this".
 *
 * The cost of getting this wrong is paid twice over. Treating a gate as a plain failure is what made a
 * gated track read as a broken one - "No stream available" over a video YouTube was asking to have the
 * user signed in for, with the one action that would have fixed it hidden behind a generic error.
 * Treating a plain failure as a gate is nearly as bad: it sends a user to YouTube Music to confirm a
 * track that was never coming back, and spends a `LoginRequiredForPlaybackException` on a dead video.
 *
 * The cases below are the ones the reporting device actually produced for a single track, across all
 * fifteen clients the sweep tries.
 */
class YTPlayerUtilsLoginGateTest {

    @Test
    fun `a login-required status is a gate whatever wording comes with it`() {
        // This is the exact pair the music clients returned, and the phrase list missed it.
        assertTrue(YTPlayerUtils.isLoginRecoveryError("LOGIN_REQUIRED", "Please sign in"))
        // YouTube's wording here has changed before; the status is the part that is contractual.
        assertTrue(YTPlayerUtils.isLoginRecoveryError("LOGIN_REQUIRED", ""))
        assertTrue(YTPlayerUtils.isLoginRecoveryError("login_required", "Something new"))
    }

    @Test
    fun `the age-related refusals still count under their own status`() {
        assertTrue(
            YTPlayerUtils.isLoginRecoveryError("UNPLAYABLE", "Sign in to confirm your age"),
        )
        assertTrue(YTPlayerUtils.isLoginRecoveryError("UNPLAYABLE", "This video is age-restricted"))
        assertTrue(
            YTPlayerUtils.isLoginRecoveryError("UNPLAYABLE", "This video is inappropriate for some users"),
        )
        assertTrue(YTPlayerUtils.isLoginRecoveryError("UNPLAYABLE", "Please sign in to continue"))
    }

    @Test
    fun `a video YouTube will not serve to anyone is not a gate`() {
        // Also from the same sweep, and the distinction the user needs: a gate is worth acting on, a
        // video that is simply unavailable is not - and telling them to go and confirm it would be a
        // wild goose chase. Both statuses here are what the web clients answered.
        assertFalse(YTPlayerUtils.isLoginRecoveryError("UNPLAYABLE", "Video unavailable"))
        assertFalse(YTPlayerUtils.isLoginRecoveryError("UNPLAYABLE", "This video is not available"))
        assertFalse(YTPlayerUtils.isLoginRecoveryError("ERROR", "This video is not available"))
        assertFalse(YTPlayerUtils.isLoginRecoveryError("UNPLAYABLE", null.orEmpty()))
    }

    @Test
    fun `a successful status is never a gate`() {
        assertFalse(YTPlayerUtils.isLoginRecoveryError("OK", ""))
        assertFalse(YTPlayerUtils.isLoginRecoveryError("OK", "Please sign in"))
    }
}
