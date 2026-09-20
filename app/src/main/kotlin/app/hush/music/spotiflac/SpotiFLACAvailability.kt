/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import android.content.Context

/**
 * Whether the SpotiFLAC engine can serve anything right now, and why not when it cannot.
 *
 * The switch in Audio Sources is the user's *intent*; this is the engine's *state*. They are not the
 * same thing, and treating them as the same is what produced the failure this exists to stop: on a
 * device where every source's session had lapsed, `spotiflac=true` put SpotiFLAC first in the resolve
 * order, the sweep spent its budget on four sources the runtime refuses to download with, and the
 * track ended in "no source could serve this" even though YouTube was enabled and could have played
 * it immediately.
 *
 * So an engine with nothing usable but sources waiting on the user is *paused*: it is not consulted,
 * and playback runs on the other engine. It does not get to burn a sweep and hand back a failure.
 * A source with no session contract (SoundCloud, the YouTube Music provider) is not "waiting on the
 * user" - it has nothing to verify - so it is never the reason an engine is paused.
 */
enum class SpotiFLACEngineState {
    /** At least one source can actually download. */
    READY,

    /** The user switched the engine off; nothing to say about sessions. */
    DISABLED,

    /** Every source that needs a session is without one, so a verification is what fixes this. */
    NEEDS_VERIFICATION,

    /** The gateway is refusing this client outright - a rate limit or a block, not a missing session. */
    GATEWAY_BLOCKED,
}

/** The engine's state, with the wording every surface shows for it. */
data class SpotiFLACEngineStatus(
    val state: SpotiFLACEngineState,
    /** Sources holding a valid signed session. */
    val verifiedSources: Int,
    /** Sources that declare a session and do not have a usable one. */
    val sourcesNeedingCheck: Int,
    /** One line: what the engine is doing. */
    val headline: String,
    /** One line: what that means for playback, and what the user can do about it. */
    val detail: String,
) {
    /**
     * Whether playback may attempt SpotiFLAC at all.
     *
     * The single rule the resolve order, the sweep and the UI all read, so the three can never
     * disagree about whether the engine is in play.
     */
    val usable: Boolean get() = state == SpotiFLACEngineState.READY
}

/**
 * The decision itself, kept pure, plus one thin reader for the live state.
 *
 * Pure because it is the rule that decides whether a user hears music: an engine paused by mistake
 * silently drops lossless playback, and an engine left "ready" by mistake costs a sweep and a failed
 * track. Both directions are pinned by tests rather than by reading the settings screen.
 */
object SpotiFLACAvailability {

    private val STATUS_CODE = Regex("\\(HTTP (\\d{3})\\)")

    /**
     * The engine's state from what is known about it.
     *
     * Order is the order of authority: a switch the user threw beats everything; a gateway that has
     * refused the client is not something verifying a source can fix, and saying "verify" for it
     * would send the user to solve a check that changes nothing; and only then does a missing session
     * pause the engine.
     *
     * A gateway block is checked *before* the session count because of what the block means: while it
     * is in force every request is refused, so even a source that looks verified cannot serve, and the
     * honest state is the block.
     */
    fun of(
        enabled: Boolean,
        verifiedSources: Int,
        sourcesNeedingCheck: Int,
        blockedRemainingMs: Long? = null,
        blockedReason: String? = null,
    ): SpotiFLACEngineStatus {
        val verified = verifiedSources.coerceAtLeast(0)
        val needing = sourcesNeedingCheck.coerceAtLeast(0)
        val blocked = blockedRemainingMs != null && blockedRemainingMs > 0
        return when {
            !enabled -> SpotiFLACEngineStatus(
                state = SpotiFLACEngineState.DISABLED,
                verifiedSources = verified,
                sourcesNeedingCheck = needing,
                headline = "Off",
                detail = "SpotiFLAC is switched off, so playback uses the other source.",
            )

            blocked -> SpotiFLACEngineStatus(
                state = SpotiFLACEngineState.GATEWAY_BLOCKED,
                verifiedSources = verified,
                sourcesNeedingCheck = needing,
                headline = "Paused — gateway rate limit",
                // The gateway's own sentence is kept only for the status code inside it: it is written
                // without trailing punctuation and restates the headline, so pasting it whole produced
                // "Paused — the gateway is refusing this connection" followed by "gateway is refusing
                // this connection (HTTP 429)".
                detail = buildString {
                    val code = statusCodeIn(blockedReason)
                    if (code != null) {
                        append("The gateway answered HTTP $code. ")
                    } else {
                        append("SpotiFLAC's gateway is rate-limiting this device. ")
                    }
                    append("Playback uses YouTube until it lifts ")
                    append("(${remainingLabel(blockedRemainingMs ?: 0L)} left).")
                },
            )

            verified == 0 && needing > 0 -> SpotiFLACEngineStatus(
                state = SpotiFLACEngineState.NEEDS_VERIFICATION,
                verifiedSources = 0,
                sourcesNeedingCheck = needing,
                headline = "Paused — no source has a valid session",
                detail = "$needing source${if (needing == 1) "" else "s"} " +
                    "need${if (needing == 1) "s" else ""} a check, so playback uses YouTube. " +
                    "Verify one to turn lossless playback back on.",
            )

            else -> SpotiFLACEngineStatus(
                state = SpotiFLACEngineState.READY,
                verifiedSources = verified,
                sourcesNeedingCheck = needing,
                headline = "Active",
                detail = when {
                    verified == 0 -> "Nothing to verify — these sources sign in with their own service."
                    needing == 0 -> "$verified source${if (verified == 1) "" else "s"} verified, " +
                        "renewing in the background."
                    else -> "$verified verified · $needing still need a check"
                },
            )
        }
    }

    /**
     * The live state for the enabled sources, read from the runtime and the gateway's own record.
     *
     * A source whose state cannot be read at all (no runtime in this build, an unread manifest) is
     * counted as neither verified nor needing a check - deliberately, because "unread" is not
     * evidence of a missing session and pausing the engine over it would turn a slow first read into
     * a silent loss of lossless playback.
     *
     * The block is read through [SpotiFLACSessionRenewer.relayBlock] - "does the gateway's answer still
     * apply to the address this device is on" - and not as the raw record it is stored as. That is the
     * difference between the two, and it is the whole failure this reader caused: a block earned on one
     * network was read as a block on *this* one, so the engine stayed `GATEWAY_BLOCKED` (and therefore
     * unusable, and therefore not even consulted) on a network it was never refused on. Measured on the
     * reporting device: the switch was turned back on after a network move, the first play logged
     * `routing spotiflac=false yt=true engines=YOUTUBE`, and the screen said "2h 54m left" about a wait
     * from somewhere else. The recorded wait is still on disk and is still shown while it applies; what
     * it no longer does is pause an engine on the strength of an address that has gone.
     */
    fun current(
        context: Context,
        enabled: Boolean,
        sources: List<String>,
    ): SpotiFLACEngineStatus {
        if (!enabled) return of(enabled = false, verifiedSources = 0, sourcesNeedingCheck = 0)
        val bridge = SpotiFLACNativeRuntimeBridgeHolder.instance
        val ids = sources.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val states = ids.mapNotNull { id ->
            runCatching { bridge?.sourceAuthState(id) }.getOrNull()
        }
        val block = runCatching { SpotiFLACSessionRenewer.relayBlock(context) }.getOrNull()
        return of(
            enabled = true,
            verifiedSources = states.count { it == SpotiFLACSourceAuthState.VERIFIED },
            sourcesNeedingCheck = states.count { it == SpotiFLACSourceAuthState.NEEDS_VERIFICATION },
            blockedRemainingMs = block?.remainingMs,
            blockedReason = block?.reason,
        )
    }

    /** The HTTP status inside a stored reason ("... refusing this connection (HTTP 429)"), or null. */
    private fun statusCodeIn(reason: String?): String? =
        STATUS_CODE.find(reason.orEmpty())?.groupValues?.getOrNull(1)

    /**
     * "5h 45m" / "12m" / "40s", for a wait the user is being asked to sit out.
     *
     * Each unit rounds *up*, so 90 seconds reads as "2m" and never as "1m" - a countdown that names a
     * unit it has already passed reads as a stale number rather than as a wait, which is the one thing
     * this label is for.
     */
    fun remainingLabel(ms: Long): String {
        val seconds = (ms.coerceAtLeast(0L) + 999L) / 1000L
        return when {
            seconds >= 3600 -> {
                val hours = seconds / 3600
                val minutes = ((seconds % 3600) + 59L) / 60L
                if (minutes == 0L) "${hours}h" else "${hours}h ${minutes}m"
            }
            seconds >= 60 -> "${(seconds + 59L) / 60L}m"
            else -> "${seconds}s"
        }
    }
}
