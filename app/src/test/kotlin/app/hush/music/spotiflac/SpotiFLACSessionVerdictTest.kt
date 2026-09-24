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
        declaredTypes: List<String> = emptyList(),
    ) = SpotiFLACSessionVerdictReport.row(
        authState = authState,
        remainingSeconds = remainingSeconds,
        verdict = verdict,
        currentSessionId = currentSessionId,
        formatRemaining = { seconds -> "${seconds / 3600L}h ${(seconds % 3600L) / 60L}m" },
        declaredTypes = declaredTypes,
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
        assertEquals("Verified — renews automatically (5h 0m left)", renewed.text)
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
            SpotiFLACSessionVerdictReport.Row("No verification needed", healthy = false, needsCheck = false, checkable = false),
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
        assertEquals("No verification needed", metadataOnly.text)
        assertFalse(metadataOnly.needsCheck)
    }

    /**
     * The two sources that were reported as missing from this list, read from their own manifests.
     *
     * They were absent because the list filtered on `download_provider`, which is decided by the
     * registry rather than by the row - and the wording that made their absence confusing said the
     * same sentence for a download provider with nothing to authorise and for an extension that has
     * no download at all.
     */
    @Test
    fun `a source that only resolves metadata says so instead of looking unverified`() {
        val spotifyWeb = row(
            authState = SpotiFLACSourceAuthState.NOT_REQUIRED,
            remainingSeconds = null,
            declaredTypes = listOf("metadata_provider"),
        )
        assertEquals("Metadata only — no session needed", spotifyWeb.text)
        assertFalse(spotifyWeb.needsCheck)

        val appleMusic = row(
            authState = SpotiFLACSourceAuthState.NOT_REQUIRED,
            remainingSeconds = null,
            declaredTypes = listOf("metadata_provider", "lyrics_provider"),
        )
        assertEquals("Metadata & lyrics only — no session needed", appleMusic.text)
        assertFalse(appleMusic.needsCheck)
    }

    /** A download provider that needs no session keeps the plain sentence, case notwithstanding. */
    @Test
    fun `a download provider without a session reads the plain line`() {
        val soundCloud = row(
            authState = SpotiFLACSourceAuthState.NOT_REQUIRED,
            remainingSeconds = null,
            declaredTypes = listOf("metadata_provider", "Download_Provider"),
        )
        assertEquals("No verification needed", soundCloud.text)
        assertFalse(soundCloud.needsCheck)
    }

    /** Nothing read yet must not be described as metadata-only: that would be an invention. */
    @Test
    fun `an unread source is not described by its role`() {
        val unread = row(authState = SpotiFLACSourceAuthState.NOT_REQUIRED, remainingSeconds = null)
        assertEquals("No verification needed", unread.text)
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
}
