/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Runs SpotiFLAC source verification on its own, one source at a time.
 *
 * A source's gateway session cannot be shared between sources: `/bootstrap`
 * answers every app version with its own challenge, and the grant a challenge
 * returns is spent by the first exchange (a second exchange against the same grant
 * is answered with HTTP 403). So there is no "one verification covers everything"
 * shortcut - but there is also no reason the user should drive it. Cloudflare's
 * managed challenge solves itself in the WebView, which is why the manual screen
 * used to disappear on its own after a second or two.
 *
 * This owns the queue and the ordering; the WebView that actually solves a
 * challenge lives in [app.hush.music.ui.component.SpotiFLACVerificationOverlay],
 * which observes [active] and reports back through [finish]. Nothing here touches
 * playback: the player keeps its own source order, and a source that cannot be
 * verified yet is simply skipped until it can be.
 */
object SpotiFLAutoVerifier {

    /** How long one source's challenge may take before the queue moves on. */
    const val CHALLENGE_TIMEOUT_MS = 75_000L

    /** The reason a run carries when nothing is waiting on it, so it never prompts the user. */
    const val BACKGROUND_REASON = "prewarm"

    /**
     * A source that just failed verification is not retried immediately: an
     * interactive challenge would otherwise loop, and each attempt is a network
     * round trip against Cloudflare.
     */
    private const val RETRY_COOLDOWN_MS = 10 * 60_000L

    private val _active = MutableStateFlow<String?>(null)

    /** The source whose challenge should be solved right now, or null. */
    val active: StateFlow<String?> = _active.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)

    /** Human-readable progress of the automatic run, for the UI and diagnostics. */
    val status: StateFlow<String?> = _status.asStateFlow()

    /**
     * One source's place in the run the verifier is working through.
     *
     * @param state what has happened to this source *in this run*, which is the one thing the
     *   source's own session record cannot say: a challenge being solved right now and one that has
     *   not been started look identical on disk, and a source that just failed looks exactly like
     *   one that was never tried.
     */
    data class Step(val sourceId: String, val state: State) {
        enum class State {
            /** Its challenge is being solved right now. */
            SOLVING,

            /** Handed to the verifier and not started yet. */
            WAITING,

            /** Finished, and the source is usable. */
            VERIFIED,

            /** Tried and given up on: this one needs the user. */
            NEEDS_CHECK,
        }
    }

    private val _steps = MutableStateFlow<List<Step>>(emptyList())

    /**
     * The current run as a checklist, in the order the verifier works through it.
     *
     * A flow rather than a read of [queued]: the queue deliberately forgets a source the moment it
     * finishes, so reading it can only ever answer "still to do" - it cannot say which source is
     * being solved, nor which ones already landed. That is the question this answers, and it is the
     * one a user watching four dead sources presses Verify to get answered.
     *
     * Cleared when a *new* run starts (not when one ends): the last run's marks are what the screen
     * shows until the next one replaces them, so a source that just became usable does not vanish
     * from the list the instant it succeeded.
     */
    val steps: StateFlow<List<Step>> = _steps.asStateFlow()

    /**
     * Moves a source to [state], adding it to the checklist if this run has not seen it yet.
     *
     * Through [update] rather than a read-modify-write, because this is written from two directions:
     * the caller that enqueues a run and the verifier thread working through the previous one. A
     * lost update here is a row that never shows its state, or shows the state a source had two
     * sources ago.
     */
    private fun recordStep(
        sourceId: String,
        state: Step.State,
    ) {
        _steps.update { current ->
            val index = current.indexOfFirst { it.sourceId == sourceId }
            if (index >= 0) {
                current.toMutableList().apply { set(index, Step(sourceId, state)) }
            } else {
                current + Step(sourceId, state)
            }
        }
    }

    /**
     * Drops a source from the checklist.
     *
     * Used for a source that needs no check at all: listing it as verified would claim a check was
     * solved when nothing was ever asked of it.
     */
    private fun forgetStep(sourceId: String) {
        _steps.update { steps -> steps.filterNot { it.sourceId == sourceId } }
    }

    private val _verifiedTicker = MutableStateFlow(0)

    /**
     * Increments every time a source becomes usable.
     *
     * Playback watches this so a track that stopped because every source was
     * waiting for verification can start again by itself, instead of the queue
     * skipping past it.
     */
    val verifiedTicker: StateFlow<Int> = _verifiedTicker.asStateFlow()

    /**
     * Called whenever a source becomes usable.
     *
     * Playback registers this to resume a track it parked waiting for exactly this.
     * A direct callback rather than only a flow: the resume must not depend on a
     * collector's lifecycle, because a missed emission leaves the track parked with
     * nothing to wake it.
     */
    @Volatile var onSourceVerified: (() -> Unit)? = null

    /**
     * Application context used to preserve what a verification just earned.
     *
     * Set once at app start. Only needed for the vault mirror below, so a null
     * value degrades to "no extra bookkeeping" rather than any user-visible change.
     */
    @Volatile var appContext: Context? = null

    /**
     * Carries work that must not block whichever thread reported a verification.
     *
     * A process-wide scope rather than a caller's: a verification can be reported from an
     * Activity, a receiver, or composition, all of which may be gone a moment later, and the vault
     * write has to survive whichever of them happened to be holding the thread.
     */
    private val vaultScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Guards the three collections below.
     *
     * They were plain collections with no protection, and this object is called from five places on
     * five threads: playback (its own coroutine context), the runtime bridge's preflight (IO), the
     * browser route (a process-wide `Dispatchers.Default` scope, because it outlives any screen), the
     * overlay and Audio Sources screens (main), and the notification receiver (main). A
     * `LinkedHashSet`/`HashMap` mutated from two of those at once can lose an entry or throw
     * `ConcurrentModificationException` out of `queued()`, which is how the queue silently wedges -
     * a source left `active` forever, so every later one waits behind it and verification appears to
     * do nothing. The lock is held only around the collections; listeners and flows are always
     * touched outside it, so a callback that re-enters this object cannot deadlock.
     */
    private val lock = Any()

    private val queue = LinkedHashSet<String>()
    private val lastFailedAt = HashMap<String, Long>()
    private val lastAttemptAt = HashMap<String, Long>()

    /**
     * Why each queued source is being verified, keyed by source.
     *
     * Read at the point an attempt fails, because that is where the difference matters: a run the
     * user asked for, or one a held track is waiting on, is worth a prompt; a background sweep
     * nobody requested must never pop one. Without this the two were indistinguishable in [finish],
     * so a launch-time run that could not finish unattended raised the "needs verification" notice
     * on every app open - a dialog asking for a check for a source that was not in use.
     */
    private val reasons = HashMap<String, String>()

    /**
     * Sources whose run is allowed to drive the browser itself.
     *
     * A device whose embedded WebView cannot run Cloudflare's check has exactly one route left:
     * the user's own browser. Automatic runs never took it - they abandoned the source instead,
     * because opening a browser unprompted is not something a background prewarm may do. When the
     * user asks for *all* sources in one action, that consent exists, so the challenge is opened
     * for them and the grant comes back on its own. Per source rather than per queue, so a
     * prewarm that joins the run never inherits it.
     */
    private val browserFallbackSources = HashSet<String>()

    /**
     * Asks for these sources to be verified in the background.
     *
     * @param reason short label for diagnostics ("playback", "prewarm", ...).
     * @param force ignore the failure cooldown. Used when a track is being held
     *   waiting for exactly this verification: the cooldown exists to stop a failed
     *   attempt from spinning, but a parked track is a concrete reason to try again
     *   now, and making the user wait out ten minutes is worse than one extra call.
     */
    fun enqueue(
        sourceIds: List<String>,
        reason: String,
        force: Boolean = false,
        browserFallback: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        var freshRun = false
        val addedIds = mutableListOf<String>()
        synchronized(lock) {
            // Whether this call begins a run or joins one: a run begins when nothing is in flight
            // and nothing is waiting, and the checklist it replaces is the previous run's.
            freshRun = _active.value == null && queue.isEmpty()
            sourceIds.forEach { id ->
                if (id.isBlank()) return@forEach
                if (browserFallback) browserFallbackSources.add(id)
                // An in-flight source is normally left alone: it is already being worked on, and
                // queueing it again would run the same challenge twice. An explicit all-sources
                // pass is the exception - the permission above has to actually be used, so the
                // source is queued and its attempt re-runs under it.
                if (id == _active.value && !browserFallback) return@forEach
                val cooled = !force && lastFailedAt[id]?.let { now - it < RETRY_COOLDOWN_MS } ?: false
                if (cooled) return@forEach
                if (queue.add(id)) {
                    addedIds.add(id)
                    reasons[id] = reason
                }
            }
        }
        if (addedIds.isEmpty()) return
        if (freshRun) _steps.value = emptyList()
        addedIds.forEach { id -> recordStep(id, Step.State.WAITING) }
        SpotiFLACDiag.log(
            "auto-verify enqueue ($reason): ${sourceIds.joinToString(",")} queued=${queue.size}",
        )
        startNext()
    }

    /** The sources waiting for a challenge, in order. */
    fun queued(): List<String> = synchronized(lock) { queue.toList() }

    /**
     * True when [sourceId]'s run may open the browser itself instead of giving up.
     *
     * Asked by the challenge host at the moment it discovers this device's WebView cannot run
     * Cloudflare's check - the one point where the two routes diverge.
     */
    fun allowsBrowserFallback(sourceId: String): Boolean =
        synchronized(lock) { sourceId in browserFallbackSources }

    /** How many sources a run still has to get through, for one progress line. */
    fun remainingCount(): Int = synchronized(lock) { queue.size }

    /**
     * Marks the active source's attempt finished and moves to the next.
     *
     * Called by the challenge host once a grant has been delivered (or the attempt
     * gave up). A failure starts a cooldown so the queue cannot spin.
     */
    fun finish(sourceId: String, verified: Boolean) {
        if (_active.value == sourceId) _active.value = null
        // The success path's bookkeeping is one critical section: the ticker is a read-modify-write,
        // and two surfaces can report the same verification at almost the same moment (a manual run
        // and the browser route it started), which used to be able to lose one of the increments -
        // and with it a parked track's only wake-up.
        var hadBrowserFallback = false
        var reason: String? = null
        synchronized(lock) {
            lastAttemptAt[sourceId] = System.currentTimeMillis()
            hadBrowserFallback = browserFallbackSources.remove(sourceId)
            reason = reasons.remove(sourceId)
            if (verified) {
                queue.remove(sourceId)
                lastFailedAt.remove(sourceId)
                _verifiedTicker.value += 1
            } else {
                queue.remove(sourceId)
                lastFailedAt[sourceId] = System.currentTimeMillis()
            }
        }
        if (verified) {
            // Recorded before the bookkeeping below, which can launch work: the checklist is what
            // the screen draws from, and a source must read "done" the moment it is.
            recordStep(sourceId, Step.State.VERIFIED)
            SpotiFLACDiag.log("auto-verify done: $sourceId verified")
            rememberVerifiedMaterial(sourceId)
            appContext?.let { SpotiFLACVerificationNotifier.clear(it, sourceId) }
            runCatching { onSourceVerified?.invoke() }
                .onFailure { SpotiFLACDiag.log("verified listener failed: ${it.message}") }
        } else {
            recordStep(sourceId, Step.State.NEEDS_CHECK)
            SpotiFLACDiag.log("auto-verify gave up: $sourceId (cooldown ${RETRY_COOLDOWN_MS / 60_000}m)")
            // Automatic could not do it on this device. On one whose WebView is older than
            // Cloudflare supports, an automatic run can only ever time out, so the manual route
            // is the only one there is - and it is offered as a notification because a car user
            // may not have Hush in front of them. On a device where the in-app route works, a
            // failure is far more likely to be a transient one (no network in a tunnel), so the
            // in-app notice carries the same browser button and no notification is raised.
            appContext?.let { context ->
                if (
                    !hadBrowserFallback &&
                    !SpotiFLACChallengeEngine.canSolveCloudflare(SpotiFLACChallengeEngine.current())
                ) {
                    SpotiFLACVerificationNotifier.notify(context, sourceId)
                }
            }
        }
        startNext()
        // Nothing left to try automatically: hand the one source the user can still
        // act on to the manual notice, rather than leaving playback parked with no
        // explanation. An automatic run that succeeded never reaches here.
        if (queueEmpty() && _active.value == null && !verified) {
            _status.value = "Verification for $sourceId needs a manual check"
            // A background sweep nobody asked for stops here. It already says what happened in the
            // diagnostic log and the source keeps its row in Audio Sources; what it must not do is
            // put a dialog in front of a user who was not trying to play anything. A run started by
            // playback, or by the user's own check, still raises the prompt - in those two cases
            // somebody is waiting on the answer.
            if (reason == BACKGROUND_REASON) {
                SpotiFLACDiag.log(
                    "auto-verify: $sourceId still needs a manual check - no prompt raised " +
                        "(nobody asked for this run)",
                )
            } else {
                SpotiFLACVerificationRequest.request(sourceId)
            }
        }
    }

    /**
     * Reports that [sourceId] became usable, from whichever surface verified it.
     *
     * There are three: the automatic overlay, the browser route, and the challenge hosted by the
     * Audio Sources screen. The first two already reported through here; the third did not, and the
     * consequences were not cosmetic - a track parked waiting for that source was never resumed
     * (nothing incremented [verifiedTicker], so [onSourceVerified] never fired), the "SpotiFLAC needs
     * verification" card stayed on screen for a source that was already fine, and the freshly
     * verified session was never mirrored into the vault. One entry point is what keeps a fourth
     * surface from repeating the omission.
     *
     * Safe for a source the queue never held: finishing a run that is not in flight only records the
     * success and wakes playback.
     */
    fun notifyVerified(sourceId: String) {
        SpotiFLACVerificationRequest.dismiss()
        finish(sourceId, verified = true)
    }

    /**
     * Marks the active source as needing nothing, and moves to the next.
     *
     * A source that signs in with its own service has no Cloudflare check, so an attempt at one
     * is not a failure: treating it as one put it in the retry cooldown, logged a give-up, and
     * - on a device whose WebView cannot run the check - offered the user a manual verification
     * for a source that was never broken.
     */
    fun finishNotRequired(sourceId: String) {
        if (_active.value == sourceId) _active.value = null
        synchronized(lock) {
            queue.remove(sourceId)
            lastFailedAt.remove(sourceId)
            browserFallbackSources.remove(sourceId)
        }
        // Nothing was ever asked of this source, so it has no place in a checklist of what was
        // checked - marking it verified would claim a check was solved that never existed.
        forgetStep(sourceId)
        SpotiFLACDiag.log("auto-verify skipped: $sourceId needs no verification")
        startNext()
    }

    /**
     * Copies a freshly earned session into the durable vault at the moment it is
     * known-good.
     *
     * Verification is the one event that can never be repeated for free: the grant
     * is single-use and the challenge behind it is manual. Capturing it here means
     * the session survives the extension's version moving, its record being cleared
     * after expiry, and - when the vault is restored - a reinstall.
     */
    private fun rememberVerifiedMaterial(sourceId: String) {
        val context = appContext ?: return
        // On IO, and off the caller: this is reached from the main thread by every surface that
        // reports a verification (the settings screen, the overlay, the deep-link handler), and it
        // lists the extensions directory and reads a record file before writing the vault. The
        // grant is what makes the wait worth hiding - the UI has already been told the source is
        // verified by the ticker above, and this only has to land before the process can die.
        vaultScope.launch {
            runCatching {
                val record = SpotiFLACSessionRenewer.sessions(context)
                    .firstOrNull { it.extensionId == sourceId }
                    ?.recordFile ?: return@launch
                SpotiFLACSessionVault.remember(context, record, sourceId)
                SpotiFLACDiag.log("session vault updated for $sourceId")
            }.onFailure { SpotiFLACDiag.log("session vault update failed for $sourceId: ${it.message}") }
        }
    }

    /**
     * Drops the run, the passive notice and every manual offer, because SpotiFLAC is off now.
     *
     * Called when the preference flips, so the app stops asking for something it will not use -
     * including the notification a car user would otherwise still be prompted to tap.
     */
    fun disableForPreferenceChange() {
        val affected = synchronized(lock) {
            (queue + listOfNotNull(_active.value)).toSet()
        } + listOfNotNull(SpotiFLACVerificationRequest.pending.value)
        cancel()
        SpotiFLACVerificationRequest.dismiss()
        val context = appContext ?: return
        affected.forEach { id -> SpotiFLACVerificationNotifier.clear(context, id) }
        SpotiFLACDiag.log("auto-verify cleared for disable (${affected.size} source(s))")
    }

    /** Drops everything: used when the user cancels or SpotiFLAC is turned off. */
    fun cancel() {
        val had = synchronized(lock) {
            val size = queue.size
            queue.clear()
            browserFallbackSources.clear()
            size
        }
        if (had > 0 || _active.value != null) {
            SpotiFLACDiag.log("auto-verify cancelled (queued=$had)")
        }
        _active.value = null
        _status.value = null
        _steps.value = emptyList()
    }

    /** True while a source is being verified right now. */
    val isRunning: Boolean get() = _active.value != null

    private fun startNext() {
        // Claiming the next source and setting the active one is a single step: two threads finishing
        // at once used to be able to both see an empty active slot and start the same source twice.
        var next: String? = null
        var queuedCount = 0
        var attempted = 0
        synchronized(lock) {
            if (_active.value == null) {
                next = queue.firstOrNull()
                if (next != null) _active.value = next
            }
            queuedCount = queue.size
            attempted = lastAttemptAt.size
        }
        val started = next
        if (started == null) {
            if (_active.value == null) _status.value = null
            return
        }
        recordStep(started, Step.State.SOLVING)
        _status.value = "Verifying $started…"
        SpotiFLACDiag.log(
            "auto-verify start: $started (queued=$queuedCount attempted=$attempted)",
        )
    }

    private fun queueEmpty(): Boolean = synchronized(lock) { queue.isEmpty() }

    /** Exposed for tests: whether a source is inside its retry cooldown. */
    internal fun inCooldown(sourceId: String, nowMillis: Long): Boolean =
        synchronized(lock) { lastFailedAt[sourceId]?.let { nowMillis - it < RETRY_COOLDOWN_MS } ?: false }

    internal fun recordFailureForTest(sourceId: String, atMillis: Long) {
        synchronized(lock) { lastFailedAt[sourceId] = atMillis }
    }

    /**
     * Forgets a source's retry cooldown.
     *
     * Exposed because [cancel] deliberately does not clear it - a user cancelling a run must not
     * make the app retry a source the gateway just turned down - which leaves a test that fails a
     * source holding a ten-minute cooldown against every later test in the same JVM. That is not
     * hypothetical: it silently made unrelated queue tests see a source that would not queue.
     */
    internal fun clearFailureForTest(sourceId: String) {
        synchronized(lock) { lastFailedAt.remove(sourceId) }
    }
}
