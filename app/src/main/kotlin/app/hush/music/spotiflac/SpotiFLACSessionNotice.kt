/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import android.content.Context
import app.hush.music.constants.SpotiFLACEnabledDefault
import app.hush.music.constants.SpotiFLACEnabledKey
import app.hush.music.utils.dataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * The launch-time answer to "SpotiFLAC is on, but nothing can be fetched with it".
 *
 * The engine's state is already computed for the settings screen and the resolve path, and both of
 * those are places the user has to go looking. What was missing is the one moment the app knows the
 * answer without being asked: app start, right after the renewer has had its turn. Without it a
 * user who enabled SpotiFLAC and never opened Audio Sources hears YouTube for every track with no
 * statement anywhere that lossless is parked - which reads as the engine being broken rather than
 * as a session needing one check.
 *
 * Kept here rather than in the screen because it has to be reachable from the places that *change*
 * the answer (a verification landing, the renewer running) and read from the one place that draws
 * it, and because "is a session missing" is a question about the engine, not about a screen.
 *
 * Dismissal lasts for the process. The notice is a launch-time prompt about a state the user may
 * not know about yet, so it comes back on the next launch while the state is unchanged - hiding a
 * genuine "nothing can play losslessly" forever would be worse than showing it once per start. The
 * one thing that silences it for good is the state actually clearing.
 */
object SpotiFLACSessionNotice {

    /**
     * What to tell the user, and which sources the one action should be aimed at.
     *
     * @param sourceIds the sources that need the user - the units a verification works in, so the
     *   action can name exactly them rather than running a full sweep the user did not ask for.
     * @param sourceNames what those sources are called, with the repository each came from, in the
     *   order of [sourceIds]. Carried rather than derived where the card is drawn, because the card
     *   is a composable and the names are a fact about the list.
     */
    data class Notice(
        val sourceIds: List<String>,
        val headline: String,
        val detail: String,
        val sourceNames: List<String> = emptyList(),
    ) {
        /**
         * The one-line body, naming the sources rather than their ids.
         *
         * A registry id is not something a user can look up, and this sentence is the one place the
         * app asks them to act on a specific source - "Needs a check: qobuz-web" is an instruction
         * written in the registries' vocabulary. The names are the same words the Audio Sources list
         * uses, so the card and the switch it points at agree about which source is meant.
         */
        val body: String
            get() =
                if (sourceIds.isEmpty()) {
                    detail
                } else {
                    val named = sourceNames.filter { it.isNotBlank() }.ifEmpty { sourceIds }
                    "$detail Needs a check: ${named.joinToString(", ")}."
                }
    }

    /**
     * What one evaluation saw.
     *
     * The reason exists for the log. A null notice has half a dozen causes and only one of them is
     * a fact about the engine; without the reason, a launch that stayed silent because it ran before
     * its inputs existed reads exactly like a launch that had nothing to report.
     */
    private data class Evaluation(
        val notice: Notice?,
        val reason: String,
    )

    private val _notice = MutableStateFlow<Notice?>(null)

    /** The notice to draw, or null when there is nothing to say. */
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    @Volatile
    private var dismissed = false

    /**
     * Re-reads the engine and publishes (or clears) the notice.
     *
     * Safe to call whenever the answer may have moved: app start, a completed verification, a
     * setting flipped. It touches the extensions directory and the session records, so it is a
     * suspend function on IO rather than something a composable does while drawing.
     */
    suspend fun refresh(context: Context) {
        val appContext = context.applicationContext
        val computed =
            withContext(Dispatchers.IO) {
                runCatching { evaluate(appContext) }
                    .onFailure { SpotiFLACDiag.log("session notice evaluate failed: ${it.message}") }
                    .getOrNull()
            }
        // A dismissal is the user saying "not now", and it outranks a recomputation: without this a
        // settings screen opening, or a verification landing for an unrelated source, would put the
        // card back in front of someone who had just closed it.
        val notice = computed?.notice
        _notice.value = notice.takeUnless { dismissed }
        // The reason goes in the log with the outcome, because "the notice is correct to be silent"
        // and "this evaluation ran before its inputs existed" are indistinguishable on screen - both
        // draw nothing. Every launch used to write the same sentence whether the engine was healthy
        // or the check had simply found no bridge yet, which is how a card that never appeared went
        // unnoticed for as long as it did.
        SpotiFLACDiag.log(
            when {
                computed == null -> "session notice: the evaluation failed"
                notice == null -> "session notice: nothing to report (${computed.reason})"
                dismissed -> "session notice: ${notice.sourceIds.joinToString(",")} need a check " +
                    "(already dismissed for this session)"
                else -> "session notice raised: ${notice.sourceIds.joinToString(",")} need a check"
            },
        )
    }

    /**
     * Which sources the notice should name, or null when there is nothing to raise.
     *
     * Pure so the rule can be pinned by tests rather than by waiting for four real sessions to
     * lapse: every direction here is a user-visible outcome, and the two that are easy to get wrong
     * are opposite. Nagging while a source can still download burns a launch-time card on an engine
     * that works; staying silent while none can leaves lossless playback parked with no statement
     * anywhere. Waiting for the real state to drift into each one is how a wrong rule ships.
     *
     * The three silent cases, each for its own reason:
     * - **nothing could be read** - the runtime is absent, or a manifest is still being read. "Unread"
     *   is not evidence of a missing session, and the engine's own state says the same.
     * - **any source is verified with a live record** - the engine can download, so there is nothing
     *   to act on. A source whose record has lapsed does not count, whatever it reads as.
     * - **no source needs a check** - a soundcloud-style source with no session contract is not a
     *   problem to report, and an empty answer here is what keeps that from reading as one.
     *
     * @param states the states that could be read, as (source id, state) - an unreadable source is
     *   left out rather than passed as UNKNOWN, so the two cases cannot be confused downstream.
     * @param expiredSourceIds sources whose session record exists and has lapsed. Kept as a separate
     *   input because the runtime's preflight is local: a lapsed record can still read VERIFIED to
     *   the bridge for a moment, but it can never serve a download - so it is also what decides that
     *   such a read does not count as a working engine.
     */
    internal fun targetsNeedingNotice(
        states: List<Pair<String, SpotiFLACSourceAuthState>>,
        expiredSourceIds: List<String>,
    ): List<String>? {
        if (states.isEmpty()) return null
        val expired =
            expiredSourceIds
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        // A lapsed record outranks the runtime's own read of it. That read is a local preflight, so a
        // source whose session has just gone can still answer VERIFIED for a moment - and one such
        // answer used to silence the whole notice, which is how a launch where every session had
        // lapsed still produced no card. A source stands in as "the engine works" only while its
        // record is live.
        val serving =
            states.filter { (id, state) ->
                state == SpotiFLACSourceAuthState.VERIFIED && id !in expired
            }
        if (serving.isNotEmpty()) return null
        val needingCheck =

            states
                .filter { it.second == SpotiFLACSourceAuthState.NEEDS_VERIFICATION }
                .map { it.first }
        return (needingCheck + expired)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .takeIf { it.isNotEmpty() }
    }

    /** Hides the notice for the rest of the process. */
    fun dismiss() {
        dismissed = true
        _notice.value = null
    }

    /** Forgets a dismissal: used by tests, and by a state change that makes the notice wanted again. */
    internal fun resetForTest() {
        dismissed = false
        _notice.value = null
    }

    /**
     * The engine's state as the notice's inputs, with the reason whenever there is nothing to say.
     *
     * A null notice is the common answer and it has several distinct meanings, all of which must stay
     * silent: SpotiFLAC is off (the user has already answered), the runtime bridge does not exist in
     * this build (nothing can be claimed about sessions), no source is enabled (nothing to name), and
     * at least one source can serve (the engine works). They are reported separately rather than
     * collapsed because only the last of them is a fact about the engine - the rest are facts about
     * *when* this ran, and a launch that says nothing at all has to say which it was.
     *
     * The switch is read the same way the route watch reads it, and for the same reason: an absent
     * key is a fresh install and reads as on ([SpotiFLACEnabledDefault]), while a store that threw is
     * a read that failed and stays silent. The card asks the user to go and check a source - raising
     * it off a preference nobody could read would be asking on a fact the app does not have, which is
     * the mistake the guarded read above exists to avoid. A failed read gets its own [Evaluation.reason]
     * too: reported as "SpotiFLAC is off" it would send whoever reads the log to a switch that was
     * never thrown.
     */
    private suspend fun evaluate(context: Context): Evaluation {
        val stored =
            runCatching { context.dataStore.data.first() }
                .getOrElse { return Evaluation(null, "the preferences could not be read") }
        val enabled = stored[SpotiFLACEnabledKey] ?: SpotiFLACEnabledDefault
        if (!enabled) return Evaluation(null, "SpotiFLAC is off")
        val bridge =
            SpotiFLACNativeRuntimeBridgeHolder.instance
                ?: return Evaluation(null, "the runtime bridge has not been built yet")
        val sourceIds =
            runCatching { ExtensionRepositoryManager.getInstance().getEnabledSourceIds() }
                .getOrDefault(emptyList())
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        if (sourceIds.isEmpty()) return Evaluation(null, "no enabled source is known yet")

        // Only sources whose state could actually be read take part in the decision: "unread" is not
        // evidence of a missing session, and pausing (or nagging) over a slow first read would turn
        // a momentary gap into a prompt about a source that is fine. This is the same rule the
        // engine's own state follows, so the two cannot disagree.
        // Only the sources that are switched on take part - on both halves of the question. The
        // lapsed records used to be read from every extracted extension, which is a fact about the
        // *disk* and not about what the user is using: on the reporting device every download source
        // was switched off, three metadata providers were on, and the card still said "Needs a check:
        // qobuz-web" about a source the user had deliberately turned off. A source that cannot be
        // swept cannot be parked by a session, so raising a card over one asks for a check that
        // changes nothing audible.
        val switchedOn = sourceIds.toSet()
        val states = sourceIds.mapNotNull { id -> runCatching { bridge.sourceAuthState(id) }.getOrNull()?.let { id to it } }
        if (states.isEmpty()) return Evaluation(null, "no source state could be read")
        val expired =
            runCatching { SpotiFLACSessionRenewer.expired(context).map { it.extensionId } }
                .getOrDefault(emptyList())
                .filter { it in switchedOn }
        val targets =
            targetsNeedingNotice(states, expired)
                ?: return Evaluation(null, "every source that is switched on is healthy or needs no session")

        val status =
            SpotiFLACAvailability.current(
                context = context,
                enabled = true,
                sources = sourceIds,
            )
        return Evaluation(
            notice =
                Notice(
                    sourceIds = targets,
                    headline = status.headline,
                    detail = status.detail,
                    sourceNames =
                        targets.map { id ->
                            runCatching { ExtensionRepositoryManager.getInstance().sourceLabel(id) }
                                .getOrNull()
                                ?.takeIf { it.isNotBlank() }
                                ?: id
                        },
                ),
            reason = "",
        )
    }
}
