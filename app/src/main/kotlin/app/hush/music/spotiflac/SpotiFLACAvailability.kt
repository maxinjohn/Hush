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
 * A source with no session contract (SoundCloud, the YouTube Music provider, a credential-less module
 * or addon) is not "waiting on the user" - it has nothing to verify - so it is never the reason an
 * engine is paused, and it is the reason an engine with unverified neighbours stays in play: those
 * sources can still fetch a track, and pausing on the session count took them out of the walk too.
 */
enum class SpotiFLACEngineState {
    /** At least one source can actually download. */
    READY,

    /** The user switched the engine off; nothing to say about sessions. */
    DISABLED,

    /**
     * Nothing switched on can fetch audio: every source left is a metadata provider (or nothing is
     * on at all).
     *
     * Distinct from [NEEDS_VERIFICATION] because no check can fix it - a metadata provider never had
     * a session to lose - and distinct from [READY] because an engine in this state cannot serve a
     * single track. It used to read READY: a metadata source signs in with its own service, so the
     * "has nothing to verify" rule counted it as healthy, playback was routed to SpotiFLAC first, and
     * the sweep came back empty (measured on the reporting device: `no source the switches name can
     * download (chosen=spoiti-lists); nothing else is swept`, then an "all sources failed" verdict
     * and a fallback to YouTube - a slower path to the same track, and a miss memo recorded for it).
     */
    NO_AUDIO_SOURCE,

    /** Low data mode is on: the engine is paused so no lossless download is pulled over a metered link. */
    LOW_DATA,

    /**
     * Every source that could serve audio needs a session and none has one, so a verification is
     * what fixes this.
     *
     * Deliberately not "some source needs a check": the unverified sources are left out of the walk
     * by the sweep itself, so the engine only has to stand down when there is nothing left to ask.
     */
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
     * Order is the order of authority: a switch the user threw beats everything; low data mode next,
     * because it is the one state where pulling a lossless file at all is the wrong answer; a gateway
     * that has refused the client is not something verifying a source can fix, and saying "verify" for it
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
        /**
         * How many switched-on sources can serve audio at all, or null when that is not known yet.
         *
         * Null is not zero on purpose: before the registry has been read the live source list is
         * empty, and pausing the engine on that empty list would drop lossless playback on every
         * cold start - the sweep is still able to fall back to the installed packages there. Unknown
         * therefore leaves the answer as it was, and only a count that was actually read can say
         * there is nothing to play.
         */
        audioSources: Int?,
        blockedRemainingMs: Long? = null,
        blockedReason: String? = null,
        lowDataActive: Boolean = false,
        /**
         * How many switched-on sources that can serve audio need **no** check at all.
         *
         * This is the difference between "every source is waiting on the user" and "the sources
         * that are waiting are not the only ones". A credential-less module, a server addon and a
         * provider that signs in with its own service have no session to lose, so a lapsed session on
         * a neighbouring source says nothing about whether the engine can fetch a track - and the
         * engine was paused on exactly that count, which took every one of those sources out of the
         * walk with it. Measured on the reporting device with eight sources switched on: one lapsed
         * session (`tidal-web`) and a zero verified count paused the engine, and every track resolved
         * through YouTube while the credential-less modules and SoundCloud were enabled and working.
         */
        sourcesWithoutSession: Int = 0,
    ): SpotiFLACEngineStatus {
        val sessionFree = sourcesWithoutSession.coerceAtLeast(0)
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

            // Low data mode outranks everything the engine could say about itself: while it
            // holds, no lossless byte may be pulled over a metered link, so the engine is not
            // consulted at all and playback runs on the other source. It sits above the gateway
            // block on purpose - a block is something verifying a source could lift, and low
            // data mode is not, so the line has to name the switch the user can actually flip.
            lowDataActive -> SpotiFLACEngineStatus(
                state = SpotiFLACEngineState.LOW_DATA,
                verifiedSources = verified,
                sourcesNeedingCheck = needing,
                headline = "Off — low data mode",
                detail = "Low data mode is on, so SpotiFLAC is paused and playback uses the other source. " +
                    "Turn low data mode off to use SpotiFLAC.",
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

            // Above the session count, because a metadata provider has no session to verify: with
            // only those switched on the two counts below are both zero, which used to read as
            // "nothing to verify" and left the engine ready to spend a sweep on nothing.
            audioSources != null && audioSources <= 0 -> SpotiFLACEngineStatus(
                state = SpotiFLACEngineState.NO_AUDIO_SOURCE,
                verifiedSources = verified,
                sourcesNeedingCheck = needing,
                headline = "Paused — no audio source",
                detail = "Nothing switched on can fetch audio, so playback uses the other source. " +
                    "Switch on a source that serves audio.",
            )

            // The session count pauses the engine only when it is the whole story: a source that
            // needs no check is asked by the sweep whether or not a neighbour's session lapsed, and
            // skipping those turned "one source needs a check" into "nothing can play". Unverified
            // sources are left out of the walk by the sweep itself - see SpotiFLACVerifiedOnlyKey -
            // so the engine does not have to be paused to keep them from costing the user a timeout.
            verified == 0 && needing > 0 && sessionFree == 0 -> SpotiFLACEngineStatus(
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
                    // Sources that need no session are the reason the engine is still in play here,
                    // and the line has to say so: "Nothing to verify" described an install with
                    // unverified sources on it as one that had nothing outstanding at all.
                    verified == 0 && needing > 0 -> "$needing source${if (needing == 1) "" else "s"} " +
                        "need${if (needing == 1) "s" else ""} a check; the rest need none and still play."
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
        lowDataActive: Boolean = false,
    ): SpotiFLACEngineStatus {
        if (!enabled) {
            return of(
                enabled = false,
                verifiedSources = 0,
                sourcesNeedingCheck = 0,
                audioSources = null,
            )
        }
        val bridge = SpotiFLACNativeRuntimeBridgeHolder.instance
        val ids = sources.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        // Keyed in lowercase because the two lists this reader is handed spell ids the way their
        // own source object does: a lookup that missed on case would silently count a source as
        // unreadable, which is the one answer that changes nothing either way.
        val states =
            ids.associateBy({ it.lowercase() }) { id ->
                runCatching { bridge?.sourceAuthState(id) }.getOrNull()
            }
        val block = runCatching { SpotiFLACSessionRenewer.relayBlock(context) }.getOrNull()
        // Read from the live source list rather than counted from [sources], because [sources] is
        // whatever the caller had at hand - the resolve path hands over the runtime's own ids and
        // the settings screen the playable ones - while the question is about the switches. Null
        // (no live list yet, or a read that failed) keeps the previous answer rather than pausing
        // an engine that has not been described to us.
        val audioSources =
            runCatching { ExtensionRepositoryManager.getInstance().enabledAudioSourceIds() }
                .getOrNull()
        // Asked of the audio sources rather than of every switched-on source: a metadata provider
        // also needs no check, and counting those would keep the engine ready on an install whose
        // only sources can never fetch a track - which is what NO_AUDIO_SOURCE is for. A source whose
        // state cannot be read is not counted here, so an unread runtime cannot hold the engine open.
        val sourcesWithoutSession =
            audioSources?.count { id ->
                val state = states[id.lowercase()]
                    ?: runCatching { bridge?.sourceAuthState(id) }.getOrNull()
                state == SpotiFLACSourceAuthState.NOT_REQUIRED
            } ?: 0
        return of(
            enabled = true,
            verifiedSources = states.values.count { it == SpotiFLACSourceAuthState.VERIFIED },
            sourcesNeedingCheck = states.values.count { it == SpotiFLACSourceAuthState.NEEDS_VERIFICATION },
            audioSources = audioSources?.size,
            blockedRemainingMs = block?.remainingMs,
            blockedReason = block?.reason,
            lowDataActive = lowDataActive,
            sourcesWithoutSession = sourcesWithoutSession,
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
