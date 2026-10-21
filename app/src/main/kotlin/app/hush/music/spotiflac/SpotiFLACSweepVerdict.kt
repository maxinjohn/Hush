/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import java.util.Locale

/**
 * What one SpotiFLAC provider sweep established.
 *
 * The distinction is the whole point. [NO_MATCH] is a statement about the providers'
 * catalogues and earns a long retention in [SpotiFLACMissPolicy]; [UNAVAILABLE] says the
 * sweep never got to ask, so it earns the short one.
 */
enum class SpotiFLACSweepOutcome {
    RESOLVED,
    NO_MATCH,
    UNAVAILABLE,
}

/**
 * Whether a *finished* sweep actually reached a verdict, given why its sources failed.
 *
 * The runtime reports every empty outcome as an exception, so the reason text is the only
 * evidence of whether the catalogues were consulted or the sweep was blocked. That makes
 * this a heuristic, and it is written to fail in the safe direction: anything
 * unrecognised is treated as *not* a verdict, and a blocked marker always wins over a
 * catalogue marker.
 *
 * The asymmetry is deliberate. Getting this wrong toward [NO_MATCH] means a track the
 * providers do have is hidden from SpotiFLAC for hours - the user sees real audio refused
 * because of one moment of bad connectivity, and waiting does not obviously fix it.
 * Getting it wrong the other way costs one extra provider sweep. The honest cost of
 * guessing "no match" is much higher than the honest cost of guessing "try again later".
 */
object SpotiFLACSweepVerdict {
    /** Answers that mean "my catalogue does not contain this track". */
    private val catalogueMarkers =
        listOf(
            "no results",
            "no result",
            "no match",
            "no matches",
            "not found",
            "could not find",
            "cannot find",
            "no track",
            "unsupported",
        )

    /** Conditions that mean the question was never answered. */
    private val blockedMarkers =
        listOf(
            "verification",
            "signed-session",
            "signed session",
            "session",
            "temporarily unavailable",
            "unavailable for download",
            "too many requests",
            "rate limit",
            "timed out",
            "timeout",
            "connection",
            "connect",
            "socket",
            "network",
            "unable to resolve host",
            "download failed",
            // An addon that refused service answered `403 (Unavailable)` before it named any track:
            // that says nothing about a catalogue, and reading it as one would hide a track the
            // addon might have had for hours. Kept beside 429 because they are the same statement.
            "401",
            "403",
            "429",
            "500",
            "502",
            "503",
            "504",
        )

    /**
     * Answers that mean the source refused to serve at all, rather than answering about a track.
     *
     * The words are the ones Hush itself writes, and each is a statement about the *service*: an
     * addon's own refusal travels in the walk's aggregate (`Tido answered HTTP 403 (Unavailable)`),
     * and a sweep emptied before it asked anything carries the bridge's sentence. Reporting one of
     * these as the catalogue's answer is how the player came to say "No source has this track ·
     * Tido" for a service that had refused every request it was given.
     */
    private val refusalMarkers =
        listOf(
            "refused service",
            "answered http 401",
            "answered http 403",
            "answered http 429",
        )

    /**
     * True when [message] says a source refused service instead of answering about a track.
     *
     * Read by the player's own line, so a refusal is stated as one: the difference between "this
     * source does not have the song" and "this source is not serving anyone" is the whole of what a
     * listener can act on.
     */
    fun isRefusal(message: String?): Boolean {
        val text = message?.lowercase(Locale.US)?.trim().orEmpty()
        if (text.isEmpty()) return false
        return refusalMarkers.any { text.contains(it) }
    }

    /**
     * True only when every failure reads as a catalogue answer rather than a blocked
     * sweep. [message] is the aggregate of the sources' own errors.
     */
    fun isCatalogueVerdict(message: String?): Boolean {
        val text = message?.lowercase(Locale.US)?.trim().orEmpty()
        if (text.isEmpty()) return false
        // A blocked marker anywhere disqualifies the whole sweep: one source timing out
        // says nothing about whether another source's "no results" was the reason the
        // sweep ended empty.
        if (blockedMarkers.any { text.contains(it) }) return false
        return catalogueMarkers.any { text.contains(it) }
    }

    /**
     * The outcome for a sweep that produced no file, from the error it ended on.
     *
     * A verification failure is never a verdict: it means a source wants a signed
     * session, so the sweep could not ask it - the opposite of a catalogue answer.
     */
    fun forFailure(error: Throwable?): SpotiFLACSweepOutcome =
        when {
            error == null -> SpotiFLACSweepOutcome.UNAVAILABLE
            error is SpotiFLACVerificationRequiredException -> SpotiFLACSweepOutcome.UNAVAILABLE
            else -> forFailureMessage(error.message)
        }

    fun forFailureMessage(message: String?): SpotiFLACSweepOutcome =
        if (isCatalogueVerdict(message)) {
            SpotiFLACSweepOutcome.NO_MATCH
        } else {
            SpotiFLACSweepOutcome.UNAVAILABLE
        }
}
