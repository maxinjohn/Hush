/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import kotlinx.serialization.Serializable

/**
 * What the gateway last said about one source's signed session.
 *
 * The source row is built from the session *record* - a session id, a secret and an expiry - and a
 * record that looks perfect is indistinguishable from one the gateway has already thrown away. That
 * is not hypothetical: measured on device, deezer and qobuz each held an unexpired record while the
 * gateway answered `401 SESSION_INVALID` for it, and the row above went on reading "Verified —
 * renews automatically (48m left)". Only the gateway can tell those apart, so its answer has to be
 * kept and shown.
 *
 * The verdict is tied to the session it judged ([sessionId]) rather than to the source, which is
 * what makes it expire by itself: a re-verification mints a new session id, so the old verdict
 * stops applying the moment the thing it described is gone.
 */
@Serializable
data class SpotiFLACSessionVerdict(
    val sourceId: String,
    /** The session the verdict was reached about; null when no session was read at all. */
    val sessionId: String? = null,
    val outcome: Outcome,
    val checkedAtMs: Long,
    /** The renewer's own one-line reason, for diagnostics. */
    val detail: String? = null,
) {
    enum class Outcome {
        /** The gateway rotated the session; its expiry moved forward. */
        RENEWED,

        /** The gateway will not use this session again - a verification is the only fix. */
        NEEDS_VERIFICATION,

        /** The gateway rejected the request but may accept one later; nothing to re-verify yet. */
        REFUSED,

        /** Nothing was asked: not due yet, or there was no session to ask about. */
        SKIPPED,
    }
}

/**
 * The wording of a session row, and the rule that decides when a verdict still counts.
 *
 * Pure, and separate from the screen, because the rule it encodes is not a phrase but a lifetime:
 * a kept verdict must expire exactly when the session it judged is replaced. Judging that by eye on
 * a settings screen is how a source would end up demanding a verification it had already passed.
 */
object SpotiFLACSessionVerdictReport {

    /** One row's text, whether it may be shown as healthy, and whether a check can be offered. */
    data class Row(
        val text: String,
        val healthy: Boolean,
        /**
         * True when a verification is the thing that would fix this source.
         *
         * Decided here with the text, because the two must not disagree: a row that reads "verify
         * again" with no button next to it is a dead end, and a button offered for a healthy source
         * only answers "already verified", which reads as broken.
         */
        val needsCheck: Boolean,

        /**
         * True when this source can be checked on its own, if the user asks for that one source.
         *
         * This is *not* the same question as [needsCheck], which asks whether a check is what the
         * source needs. It asks whether one applies at all, so a row can offer the action without
         * the row itself becoming a control: a session that looks healthy is exactly when someone
         * wants to re-ask - a track held at "verification required" while this row reads "renews
         * automatically" is the report this action exists for.
         *
         * A source with no signed-session contract is left out, because a check on it answers "needs
         * no verification": the action would exist only to say it was never applicable. So is one
         * whose manifest has not been read yet - the app does not know what it needs, and the count
         * above the list leaves it out for the same reason.
         */
        val checkable: Boolean,
    )

    /**
     * The verdict that still applies to the session on disk, or null.
     *
     * Only the two outcomes that describe a session being *refused* can outlive their run, and only
     * while they name the very session that is there now. Anything else - a renewal, a skipped run,
     * a verdict about a session that has since been replaced - says nothing about the current
     * session, and a verdict that judges no session at all can never apply: there is nothing for it
     * to be about.
     *
     * A source with no session id on disk is deliberately not matched either. That is the state a
     * fresh install and a lapsed verification share, and in both the row already says "Verification
     * needed" from the record itself, so a remembered refusal could only add noise.
     */
    fun applies(verdict: SpotiFLACSessionVerdict?, currentSessionId: String?): SpotiFLACSessionVerdict? {
        if (verdict == null) return null
        val refused = verdict.outcome == SpotiFLACSessionVerdict.Outcome.NEEDS_VERIFICATION ||
            verdict.outcome == SpotiFLACSessionVerdict.Outcome.REFUSED
        if (!refused) return null
        val judged = verdict.sessionId?.trim().orEmpty()
        val current = currentSessionId?.trim().orEmpty()
        if (judged.isEmpty() || current.isEmpty()) return null
        return verdict.takeIf { judged == current }
    }

    /**
     * The sentence above the session list, from the rows underneath it.
     *
     * It has to be derived from the rows rather than from the session records, because those two
     * answers disagree exactly when it matters: the record says "verified, 48m left" for a session
     * the gateway has thrown away. Counting records here once produced "Every source is verified —
     * nothing to check" printed above two rows asking to be verified.
     */
    fun summary(healthy: Int, needsCheck: Int): String = when {
        needsCheck > 0 ->
            "$needsCheck source${if (needsCheck == 1) "" else "s"} need${if (needsCheck == 1) "s" else ""} " +
                "a check" +
                when {
                    healthy == 0 -> ""
                    healthy == 1 -> " · 1 renews automatically"
                    else -> " · $healthy renew automatically"
                }
        healthy > 0 -> "All $healthy sessions are healthy and renew automatically."
        else -> "No source needs a session right now."
    }

    /**
     * The line under a source in the session list.
     *
     * The order of the checks is the order of authority: what the manifest declares beats
     * everything, then what the gateway last said, then what the record looks like. A verdict about
     * the current session is the strongest evidence there is about that session - stronger than an
     * unexpired record, because the gateway has already answered for it.
     */
    fun row(
        authState: SpotiFLACSourceAuthState?,
        remainingSeconds: Long?,
        verdict: SpotiFLACSessionVerdict?,
        currentSessionId: String?,
        formatRemaining: (Long) -> String,
        declaredTypes: List<String> = emptyList(),
    ): Row {
        val verified = authState == SpotiFLACSourceAuthState.VERIFIED
        val expired = remainingSeconds != null && remainingSeconds <= 0
        val refused = applies(verdict, currentSessionId)
        val text = when {
            // Reported from the manifest: this source signs in with the service itself, so there is
            // no Cloudflare check to offer. Which is not one situation, and saying one sentence for
            // both is what made two of these sources look missing from the list rather than
            // deliberately not part of it: a *download* provider without a session (SoundCloud, the
            // YouTube Music provider) fetches audio on its own, while an extension that only resolves
            // and enriches metadata has no download to authorise at all - Apple Music searches,
            // validates ISRCs and supplies lyrics, and Spotify Web turns a Spotify link into a match.
            authState == SpotiFLACSourceAuthState.NOT_REQUIRED ->
                // A source whose roles nobody has stated must fall back to the neutral wording:
                // claiming "metadata only" about a source that has not been read would be an
                // invention, and this card is read precisely when a source looks unfamiliar. The
                // roles arrive from the registry in [app.hush.music.spotiflac.ExtensionSource.declaredRoles],
                // which reports a download provider, a metadata provider with or without lyrics, or
                // nothing at all.
                if (declaredTypes.isEmpty() ||
                    declaredTypes.any { it.equals("download_provider", ignoreCase = true) }
                ) {
                    "No verification needed"
                } else if (declaredTypes.any { it.equals("lyrics_provider", ignoreCase = true) }) {
                    "Metadata & lyrics only — no session needed"
                } else {
                    "Metadata only — no session needed"
                }
            // Not read yet is not the same answer as "needs a check".
            authState == null || authState == SpotiFLACSourceAuthState.UNKNOWN -> "Checking session…"
            refused?.outcome == SpotiFLACSessionVerdict.Outcome.NEEDS_VERIFICATION ->
                "Gateway rejected this session — verify again"
            // A signature refusal may be transient, so the wording keeps that possibility - but on
            // an unexpired record it used to read "renews automatically", which is a promise the
            // gateway has already contradicted. A check is still offered: it is the only thing that
            // certainly fixes a record the gateway will not sign for.
            refused?.outcome == SpotiFLACSessionVerdict.Outcome.REFUSED ->
                "Gateway refused the last renewal — verify or wait for the retry"
            !verified -> "Verification needed"
            expired -> "Session expired — verify again"
            remainingSeconds == null -> "Verified"
            else -> "Verified — renews automatically (${formatRemaining(remainingSeconds)} left)"
        }
        // What the manifest declares beats everything, and what the gateway answered beats the
        // record; only then does the record itself get to decide.
        val needsCheck = when {
            authState == null || authState == SpotiFLACSourceAuthState.UNKNOWN -> false
            authState == SpotiFLACSourceAuthState.NOT_REQUIRED -> false
            refused != null -> true
            else -> !verified || expired
        }
        return Row(
            text = text,
            healthy = verified && !expired && refused == null,
            needsCheck = needsCheck,
            checkable = authState != null &&
                authState != SpotiFLACSourceAuthState.UNKNOWN &&
                authState != SpotiFLACSourceAuthState.NOT_REQUIRED,
        )
    }
}
