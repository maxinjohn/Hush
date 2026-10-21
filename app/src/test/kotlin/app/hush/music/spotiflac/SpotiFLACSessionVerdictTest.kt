/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the rule that decides when the gateway's last answer still counts, and what the row says.
 *
 * The failure this pins was measured on the device: deezer and qobuz each held an unexpired session
 * record while the gateway answered `401 SESSION_INVALID` for it, and both rows read "Verified —
 * renews automatically (48m left)".
 */
class SpotiFLACSessionVerdictTest {

    private val now = 1_789_300_000_000L

    private fun verdict(
        outcome: SpotiFLACSessionVerdict.Outcome,
        sessionId: String? = "sess_live",
        sourceId: String = "deezer",
    ) = SpotiFLACSessionVerdict(
        sourceId = sourceId,
        sessionId = sessionId,
        outcome = outcome,
        checkedAtMs = now,
        detail = "detail",
    )

    private fun row(
        authState: SpotiFLACSourceAuthState? = SpotiFLACSourceAuthState.VERIFIED,
        remainingSeconds: Long? = 5 * 3600L,
        verdict: SpotiFLACSessionVerdict? = null,
        currentSessionId: String? = "sess_live",
        enabled: Boolean = true,
    ) = SpotiFLACSessionVerdictReport.row(
        authState = authState,
        remainingSeconds = remainingSeconds,
        verdict = verdict,
        currentSessionId = currentSessionId,
        formatRemaining = { seconds -> "${seconds / 3600L}h ${(seconds % 3600L) / 60L}m" },
        enabled = enabled,
    )

    @Test
    fun `a session the gateway turned down stops claiming it renews`() {
        val rejected = row(verdict = verdict(SpotiFLACSessionVerdict.Outcome.NEEDS_VERIFICATION))
        assertEquals("Gateway rejected this session — verify again", rejected.text)
        // Not shown in the healthy colour, and the check the line asks for is actually offered.
        assertFalse(rejected.healthy)
        assertTrue(rejected.needsCheck)
    }

    @Test
    fun `a refused renewal keeps its nuance but is not promised either`() {
        val refused = row(verdict = verdict(SpotiFLACSessionVerdict.Outcome.REFUSED))
        assertEquals("Gateway refused the last renewal — verify or wait for the retry", refused.text)
        assertFalse(refused.healthy)
        assertTrue(refused.needsCheck)
    }

    /**
     * The lifetime rule: the verdict describes one session, so a verification that mints a new one
     * retires it. Without this the row would keep demanding a check the user has already passed -
     * the loop this whole area exists to avoid.
     */
    @Test
    fun `a verdict lapses when a new session replaces the one it judged`() {
        val old = verdict(SpotiFLACSessionVerdict.Outcome.NEEDS_VERIFICATION, sessionId = "sess_old")
        assertNull(SpotiFLACSessionVerdictReport.applies(old, "sess_new"))
        assertEquals(old, SpotiFLACSessionVerdictReport.applies(old, "sess_old"))

        val renewed = row(verdict = old, currentSessionId = "sess_new")
        assertEquals("Verified — renews automatically", renewed.text)
        // The countdown is carried beside the sentence rather than inside it, so the row can clip the
        // sentence without losing the time - see `SpotiFLACSessionVerdictReport.Row.remaining`.
        assertEquals("5h 0m left", renewed.remaining)
        assertTrue(renewed.healthy)
    }

    @Test
    fun `a verdict that judged no session never applies`() {
        val judgedNothing = verdict(SpotiFLACSessionVerdict.Outcome.NEEDS_VERIFICATION, sessionId = null)
        assertNull(SpotiFLACSessionVerdictReport.applies(judgedNothing, "sess_live"))
        assertNull(SpotiFLACSessionVerdictReport.applies(judgedNothing, null))
        // A source with no session at all already reads "Verification needed" from the record, so a
        // remembered refusal about some other session could only add noise.
        assertNull(SpotiFLACSessionVerdictReport.applies(verdict(SpotiFLACSessionVerdict.Outcome.NEEDS_VERIFICATION), null))
    }

    @Test
    fun `only a refusal outlives its run`() {
        assertNull(
            SpotiFLACSessionVerdictReport.applies(
                verdict(SpotiFLACSessionVerdict.Outcome.RENEWED),
                "sess_live",
            ),
        )
        assertNull(
            SpotiFLACSessionVerdictReport.applies(
                verdict(SpotiFLACSessionVerdict.Outcome.SKIPPED),
                "sess_live",
            ),
        )
        assertNull(SpotiFLACSessionVerdictReport.applies(null, "sess_live"))
    }

    /** A source with nothing to keep says nothing about a countdown. */
    @Test
    fun `only a live session carries a countdown`() {
        assertNull(row(authState = SpotiFLACSourceAuthState.NEEDS_VERIFICATION, remainingSeconds = null).remaining)
        assertNull(row(remainingSeconds = -60L).remaining)
        assertNull(row(remainingSeconds = null).remaining)
        // The wording is the caller's own formatter, so the row only has to carry it through and
        // add the unit.
        assertEquals("0h 12m left", row(remainingSeconds = 12 * 60L).remaining)
    }

    /**
     * Every state the row could be in, unchanged where it was already right.
     *
     * The expectation carries all four answers together on purpose: text, colour, whether a check is
     * what the source needs, and whether one applies at all. They are read from one place, so a change
     * to any one of them has to face the others here rather than on a phone.
     */
    @Test
    fun `the rows that were already correct are untouched`() {
        assertEquals(
            SpotiFLACSessionVerdictReport.Row("No session", healthy = false, needsCheck = false, checkable = false),
            row(authState = SpotiFLACSourceAuthState.NOT_REQUIRED, remainingSeconds = null),
        )
        assertEquals(
            SpotiFLACSessionVerdictReport.Row("Checking session…", healthy = false, needsCheck = false, checkable = false),
            row(authState = null),
        )
        assertEquals(
            SpotiFLACSessionVerdictReport.Row("Checking session…", healthy = false, needsCheck = false, checkable = false),
            row(authState = SpotiFLACSourceAuthState.UNKNOWN),
        )
        assertEquals(
            SpotiFLACSessionVerdictReport.Row("Verification needed", healthy = false, needsCheck = true, checkable = true),
            row(authState = SpotiFLACSourceAuthState.NEEDS_VERIFICATION, remainingSeconds = null),
        )
        assertEquals(
            SpotiFLACSessionVerdictReport.Row("Session expired — verify again", healthy = false, needsCheck = true, checkable = true),
            row(remainingSeconds = -60L),
        )
        assertEquals(
            SpotiFLACSessionVerdictReport.Row("Verified", healthy = true, needsCheck = false, checkable = true),
            row(remainingSeconds = null),
        )
    }

    /** A source that does not download can never be asked for a check, whatever was recorded. */
    @Test
    fun `a metadata-only source is never asked to verify`() {
        val metadataOnly = row(
            authState = SpotiFLACSourceAuthState.NOT_REQUIRED,
            remainingSeconds = null,
            verdict = verdict(SpotiFLACSessionVerdict.Outcome.NEEDS_VERIFICATION),
        )
        assertEquals("No session", metadataOnly.text)
        assertFalse(metadataOnly.needsCheck)
    }

    /**
     * A source with no session says the one thing the row needs from it: that it has none.
     *
     * The line used to repeat the source's role (`Metadata only — no session needed`), which the
     * role line directly above it now states in one word - and on a 360dp row the repetition pushed
     * the answer itself into the ellipsis.
     */
    @Test
    fun `a source with no session says so without repeating its role`() {
        val metadata = row(authState = SpotiFLACSourceAuthState.NOT_REQUIRED, remainingSeconds = null)
        assertEquals("No session", metadata.text)
        assertFalse(metadata.needsCheck)
    }

    /** A download provider that needs no session keeps the plain sentence, case notwithstanding. */
    @Test
    fun `a download provider without a session reads the plain line`() {
        val soundCloud = row(
            authState = SpotiFLACSourceAuthState.NOT_REQUIRED,
            remainingSeconds = null,
        )
        assertEquals("No session", soundCloud.text)
        assertFalse(soundCloud.needsCheck)
    }

    /** Nothing read yet must not be described as metadata-only: that would be an invention. */
    @Test
    fun `an unread source is not described by its role`() {
        val unread = row(authState = SpotiFLACSourceAuthState.NOT_REQUIRED, remainingSeconds = null)
        assertEquals("No session", unread.text)
    }

    /**
     * The sentence above the list is counted from the rows, never from the session records.
     *
     * Counting records is what printed "Every source is verified — nothing to check" directly above
     * two rows asking to be verified, which is the contradiction the single action now avoids.
     */
    @Test
    fun `the summary describes the rows under it`() {
        assertEquals("2 sources need a check · 4 renew automatically", SpotiFLACSessionVerdictReport.summary(healthy = 4, needsCheck = 2))
        assertEquals("1 source needs a check · 1 renews automatically", SpotiFLACSessionVerdictReport.summary(healthy = 1, needsCheck = 1))
        assertEquals("1 source needs a check", SpotiFLACSessionVerdictReport.summary(healthy = 0, needsCheck = 1))
        assertEquals("All 5 sessions are healthy and renew automatically.", SpotiFLACSessionVerdictReport.summary(healthy = 5, needsCheck = 0))
        assertEquals("No source needs a session right now.", SpotiFLACSessionVerdictReport.summary(healthy = 0, needsCheck = 0))
    }

    /**
     * The row's overflow is for re-asking, so a session that looks healthy must offer it too.
     *
     * This is the one case where [SpotiFLACSessionVerdictReport.Row.checkable] and `needsCheck`
     * deliberately disagree, and it is the case the action exists for: a track held at "verification
     * required" while the row underneath says the session renews automatically.
     */
    @Test
    fun `a healthy session can still be re-checked on its own`() {
        val healthy = row()
        assertTrue(healthy.healthy)
        assertFalse(healthy.needsCheck)
        assertTrue(healthy.checkable)
    }

    @Test
    fun `a session that needs a check is checkable`() {
        assertTrue(row(authState = SpotiFLACSourceAuthState.NEEDS_VERIFICATION, remainingSeconds = null).checkable)
        assertTrue((row(verdict = verdict(SpotiFLACSessionVerdict.Outcome.REFUSED))).checkable)
        assertTrue(row(remainingSeconds = 0L).checkable)
    }

    /**
     * A source with no signed-session contract is not offered the action, because the only answer a
     * check can give it is "needs no verification" - a control whose sole outcome is the message
     * saying it never applied.
     */
    @Test
    fun `a source needing no session is not offered a check`() {
        assertFalse(
            row(authState = SpotiFLACSourceAuthState.NOT_REQUIRED, remainingSeconds = null).checkable,
        )
    }

    /**
     * An unread source and an absent one are left out for the same reason the count above the list
     * leaves them out: the app has not read what the source needs, so offering its check would be a
     * guess rather than a control.
     */
    @Test
    fun `an unread or unknown source is not offered a check`() {
        assertFalse(row(authState = SpotiFLACSourceAuthState.UNKNOWN, remainingSeconds = null).checkable)
        assertFalse(row(authState = null, remainingSeconds = null).checkable)
    }

    /**
     * A source that is switched off is not checked, so its row must not say it is being checked.
     *
     * Measured on the reporting device: thirteen switched-off sources each read "Checking session…"
     * for as long as the screen stayed open. The state map held only the enabled sources' ids, and a
     * miss was drawn as "not read yet" - a check that was never going to happen, on a row whose own
     * switch said the source was off.
     */
    @Test
    fun `a switched-off source is never described as being checked`() {
        val unread = row(authState = null, remainingSeconds = null, enabled = false)
        assertEquals("Off · not read yet", unread.text)
        assertFalse(unread.healthy)
        assertFalse(unread.needsCheck)
        assertFalse(unread.checkable)

        val unknown = row(authState = SpotiFLACSourceAuthState.UNKNOWN, enabled = false)
        assertEquals("Off · not read yet", unknown.text)
    }

    /**
     * The verdict still stands while a source is off - it is what the user has to do once it is
     * switched back on - but it is not counted as something the app can act on now.
     */
    @Test
    fun `a switched-off source that needs a check says so without asking for one now`() {
        val needsCheck = row(
            authState = SpotiFLACSourceAuthState.NEEDS_VERIFICATION,
            remainingSeconds = null,
            enabled = false,
        )
        // The switch leads the line: the row draws one ellipsized line, and a fact appended to a
        // long sentence is the part that gets cut.
        assertEquals("Off · verification needed", needsCheck.text)
        assertFalse(needsCheck.needsCheck)
        assertFalse(needsCheck.healthy)
        // Deliberately not offered: verifying a source nothing plays through is a challenge and a
        // request spent on a source the user turned off.
        assertFalse(needsCheck.checkable)

        // Never "renews automatically" while it is off - renewals skip a switched-off source, so
        // the countdown is the honest half of that sentence and it is still carried.
        val verified = row(enabled = false)
        assertEquals("Off · verified", verified.text)
        assertEquals("5h 0m left", verified.remaining)
        assertFalse(verified.healthy)
        assertFalse(verified.needsCheck)
        assertFalse(verified.checkable)
    }

    /** A source with no session contract has nothing to add about being off. */
    @Test
    fun `a switched-off source with nothing to verify keeps the plain line`() {
        val noSession = row(
            authState = SpotiFLACSourceAuthState.NOT_REQUIRED,
            remainingSeconds = null,
            enabled = false,
        )
        assertEquals("No session", noSession.text)
        assertFalse(noSession.needsCheck)
        assertFalse(noSession.healthy)
    }
}
