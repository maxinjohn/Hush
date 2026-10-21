/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The one-line "this source is usable again" notice, drawn as an in-app banner.
 *
 * A verification is the only thing in the lossless side of the app that costs the user something -
 * a Cloudflare check - and it is also the moment the engine goes from "nothing can be fetched" to
 * "this source can". Until now that landing was reported only where a screen happened to look: Audio
 * Sources moved a row, the launch notice re-read itself, and a user who was simply listening saw
 * nothing at all. It is drawn as a banner because that is the shape the app already uses for a state
 * change the user did not ask about and does not have to act on, exactly like "Back online".
 *
 * Reported from [SpotiFLAutoVerifier.finish], which is the single place a source becomes usable
 * whatever verified it - the automatic overlay, the browser route, Audio Sources' own challenge, or
 * the grant deep link - so no surface can verify a source without saying so.
 *
 * One message at a time and it clears itself: a run works through several sources in sequence, so
 * each landing replaces the last, and a banner that stayed would be a claim about the present that
 * the next failure would contradict.
 */
object SpotiFLACVerifiedBanner {

    /** What to draw: one source, named as the user knows it. */
    data class Message(
        val sourceId: String,
        val text: String,
    )

    /**
     * How long one landing stays up.
     *
     * The same order as the network banner's own "Back online" (2.5s), because the two share a slot
     * and are both news the user reads at a glance rather than acts on.
     */
    internal const val DURATION_MS = 2_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _message = MutableStateFlow<Message?>(null)

    /** The banner to draw, or null when there is nothing to say. */
    val message: StateFlow<Message?> = _message.asStateFlow()

    /**
     * Raises the banner for a source that just became usable.
     *
     * Held for [DURATION_MS] and replaced by whichever source lands next. A later message must not
     * be cut short by an earlier one's timer, so each show carries an increasing token and only the
     * newest one is allowed to clear the slot.
     */
    fun showVerified(
        sourceId: String,
        displayName: String? = null,
    ) {
        val id = sourceId.trim()
        if (id.isEmpty()) return
        show(id, verifiedText(id, displayName ?: knownDisplayName(id)))
    }

    /**
     * Raises the banner for a source that had refused and now answers.
     *
     * The same slot and the same shape as a verification landing, because it is the same kind of
     * news - a source going from unusable to usable without the user doing anything - and because
     * the two can land within seconds of each other on a run that verifies a source the walk had
     * left out.
     */
    fun showAnswering(
        sourceId: String,
        displayName: String? = null,
    ) {
        val id = sourceId.trim()
        if (id.isEmpty()) return
        val name = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: knownDisplayName(id) ?: id
        show(id, SpotiFLACAddonRecoveryNotifier.text(name))
    }

    /** Shows one message and lets it expire on its own; see [showVerified] for the token rule. */
    private fun show(
        sourceId: String,
        text: String,
    ) {
        val token = nextToken()
        _message.value = Message(sourceId, text)
        scope.launch {
            delay(DURATION_MS)
            if (token == nowToken()) _message.value = null
        }
    }

    /** Hides the banner now: used when SpotiFLAC is switched off under it. */
    fun clear() {
        nextToken()
        _message.value = null
    }

    /**
     * The words for one landing.
     *
     * The source is named rather than counted, because a run verifies one source at a time and "1
     * source verified" does not tell the user which of the four they were waiting for is now usable.
     * The extension registry's own name is preferred and the id is the fallback, which is the same
     * wording the launch notice already uses when it lists what needs a check.
     */
    internal fun verifiedText(
        sourceId: String,
        displayName: String?,
    ): String = "${displayName?.trim()?.takeIf { it.isNotEmpty() } ?: sourceId} verified"

    private fun knownDisplayName(sourceId: String): String? =
        runCatching {
            ExtensionRepositoryManager.getInstance().sources.value
                .firstOrNull { it.source.id == sourceId }
                ?.source
                ?.name
        }.getOrNull()

    private val tokenLock = Any()
    private var token = 0

    private fun nextToken(): Int = synchronized(tokenLock) { ++token }

    private fun nowToken(): Int = synchronized(tokenLock) { token }
}
