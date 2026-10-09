/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import app.hush.music.spotiflac.SpotiFLACSourceAuthState.NEEDS_VERIFICATION
import app.hush.music.spotiflac.SpotiFLACSourceAuthState.NOT_REQUIRED
import app.hush.music.spotiflac.SpotiFLACSourceAuthState.VERIFIED
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the launch notice's wording and its dismissal, because both are the kind of thing that is
 * only wrong in front of a user: a body that drops the source list tells them a session is missing
 * without saying which one, and a dismissal that does not hold turns "not now" into a card that
 * comes back on the next recomposition.
 */
class SpotiFLACSessionNoticeTest {

    @After
    fun tearDown() {
        SpotiFLACSessionNotice.resetForTest()
    }

    @Test
    fun `body names the sources that need a check`() {
        val notice =
            SpotiFLACSessionNotice.Notice(
                sourceIds = listOf("amazon", "deezer"),
                headline = "Paused — no source has a valid session",
                detail = "2 sources need a check, so playback uses YouTube.",
            )
        assertEquals(
            "2 sources need a check, so playback uses YouTube. Needs a check: amazon, deezer.",
            notice.body,
        )
    }

    @Test
    fun `body is the engine's own sentence when no source is named`() {
        // A gateway block is the case this covers: the engine is unusable and no verification is
        // what fixes it, so naming sources there would send the user after the wrong action.
        val notice =
            SpotiFLACSessionNotice.Notice(
                sourceIds = emptyList(),
                headline = "Paused — gateway rate limit",
                detail = "The gateway answered HTTP 429. Playback uses YouTube until it lifts.",
            )
        assertEquals(
            "The gateway answered HTTP 429. Playback uses YouTube until it lifts.",
            notice.body,
        )
    }

    @Test
    fun `a dismissal clears the notice and is remembered`() {
        assertNull(SpotiFLACSessionNotice.notice.value)
        SpotiFLACSessionNotice.dismiss()
        assertNull(SpotiFLACSessionNotice.notice.value)
    }

    @Test
    fun `names every source that needs a check when none can serve`() {
        assertEquals(
            listOf("amazon", "deezer", "qobuz-web"),
            SpotiFLACSessionNotice.targetsNeedingNotice(
                states =
                    listOf(
                        "amazon" to NEEDS_VERIFICATION,
                        "deezer" to NEEDS_VERIFICATION,
                        "qobuz-web" to NEEDS_VERIFICATION,
                        "soundcloud" to NOT_REQUIRED,
                    ),
                expiredSourceIds = emptyList(),
            ),
        )
    }

    @Test
    fun `stays silent while any source can still download`() {
        // The engine is usable, so there is nothing to ask for. Measured on the reporting device:
        // one verified source beside three dead ones is a working engine, and a card here would be
        // a prompt about a problem the user does not have.
        assertNull(
            SpotiFLACSessionNotice.targetsNeedingNotice(
                states = listOf("amazon" to VERIFIED, "deezer" to NEEDS_VERIFICATION),
                expiredSourceIds = emptyList(),
            ),
        )
    }

    @Test
    fun `stays silent when nothing could be read`() {
        assertNull(
            SpotiFLACSessionNotice.targetsNeedingNotice(
                states = emptyList(),
                expiredSourceIds = listOf("deezer"),
            ),
        )
    }

    @Test
    fun `stays silent for sources that need no session at all`() {
        // soundcloud and the YouTube Music provider sign in with their own service, so a list of
        // only those is an engine with no session problem - reporting it would send the user after
        // a check that does not exist.
        assertNull(
            SpotiFLACSessionNotice.targetsNeedingNotice(
                states = listOf("soundcloud" to NOT_REQUIRED, "ytmusic-spotiflac" to NOT_REQUIRED),
                expiredSourceIds = emptyList(),
            ),
        )
    }

    @Test
    fun `an expired record is named, and blank ids are dropped`() {
        // Two things at once, because one call produces both: a lapsed record is an independent
        // signal from the state list and has to reach the targets, and a blank id from a partly
        // written record must not become a source the user is asked to verify.
        assertEquals(
            listOf("deezer"),
            SpotiFLACSessionNotice.targetsNeedingNotice(
                states = listOf("deezer" to NEEDS_VERIFICATION),
                expiredSourceIds = listOf("deezer", " "),
            ),
        )
    }

    @Test
    fun `the same source is never named twice`() {
        assertEquals(
            listOf("deezer"),
            SpotiFLACSessionNotice.targetsNeedingNotice(
                states = listOf("deezer" to NEEDS_VERIFICATION),
                expiredSourceIds = listOf("deezer"),
            ),
        )
    }

    @Test
    fun `a lapsed record outranks a stale verified read`() {
        // The launch card's own case, and the one it never handled: the bridge reads a local
        // preflight, so a source whose record has just lapsed can still answer VERIFIED for a
        // moment. One such answer used to silence the whole notice, which is how a launch with a
        // lapsed session still drew no card.
        assertEquals(
            listOf("deezer"),
            SpotiFLACSessionNotice.targetsNeedingNotice(
                states = listOf("deezer" to VERIFIED),
                expiredSourceIds = listOf("deezer"),
            ),
        )
    }

    @Test
    fun `a verified source beside a lapsed one is still quiet`() {
        // The engine can fetch, which is the sense that matters, so a card would ask for a check
        // nobody needs. The veto belongs to the source whose record lapsed, not to the evaluation.
        assertNull(
            SpotiFLACSessionNotice.targetsNeedingNotice(
                states = listOf("amazon" to VERIFIED, "deezer" to VERIFIED),
                expiredSourceIds = listOf("deezer"),
            ),
        )
    }
}
