/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The manual route: solve one source's challenge in the device's own browser, and let Hush
 * collect the grant by itself.
 *
 * Automatic verification is unchanged - when the WebView can run Cloudflare's widget the
 * challenge still solves itself in the app, with no user involved. This exists for the case
 * where that route cannot finish at all: a head unit whose embedded WebView is older than
 * Cloudflare supports never produces a token, so an automatic run can only ever time out. The
 * user gets a button, the challenge opens in a browser that *can* solve it, and the grant comes
 * back on its own ([SpotiFLACChallengeRoute.awaitBrowserGrant]) - no code to copy, which is the
 * part that never worked on a car.
 *
 * It is also the escape hatch on a capable device: if the in-app attempt is stuck or refused,
 * the same button completes the verification without touching the clipboard.
 */
object SpotiFLACBrowserVerification {

    private val gate = Mutex()

    /**
     * Scope for a run started without a screen, i.e. from the notification action.
     *
     * Such a run outlives its trigger by design - it waits for the user to solve a challenge in
     * another app - so it cannot live on a receiver's few seconds. Hush keeps a foreground
     * service alive while it owns playback, which is exactly the situation this is for.
     */
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _running = MutableStateFlow(false)

    /** True while a browser verification is in flight, for progress UI. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)

    /** Human-readable progress of the manual run, or the reason it could not finish. */
    val status: StateFlow<String?> = _status.asStateFlow()

    /**
     * Whether the in-app WebView route is unusable on this device, which makes this the only
     * route that can complete - and therefore the one the button should offer.
     */
    fun isOnlyRoute(): Boolean =
        !SpotiFLACChallengeEngine.canSolveCloudflare(SpotiFLACChallengeEngine.current())

    /**
     * Opens [extensionId]'s challenge in a browser and applies the grant it publishes.
     *
     * @return true when the source is verified afterwards.
     */
    suspend fun run(
        context: Context,
        owner: LifecycleOwner?,
        extensionId: String?,
    ): Boolean {
        val id = extensionId?.trim().orEmpty()
        if (id.isEmpty()) return false
        // One at a time: two challenges for one source would spend the single-use grant twice.
        return gate.withLock {
            _running.value = true
            _status.value = "Preparing the challenge…"
            try {
                runLocked(context.applicationContext, owner, id)
            } finally {
                _running.value = false
            }
        }
    }

    /**
     * Runs the manual route from outside the UI, for a device whose screen is a car head unit.
     *
     * A notification action is the only entry point that is reachable without opening Hush, which
     * is what makes the manual fallback usable while driving.
     */
    fun startInProcess(context: Context, extensionId: String?) {
        val id = extensionId?.trim().orEmpty()
        if (id.isEmpty()) return
        val appContext = context.applicationContext
        processScope.launch { run(appContext, owner = null, extensionId = id) }
    }

    private suspend fun runLocked(context: Context, owner: LifecycleOwner?, id: String): Boolean {
        val bridge = SpotiFLACNativeRuntimeBridgeHolder.instance
        if (bridge == null || !bridge.isRuntimeAvailable) {
            _status.value = "SpotiFLAC runtime is unavailable in this build"
            return false
        }
        if (bridge.isSourceVerified(id)) {
            _status.value = "$id is already verified"
            markVerified(id)
            return true
        }
        // Raises the challenge when the runtime had none yet, so this works even when the
        // failure that led here never got as far as registering one.
        val pending = withContext(Dispatchers.IO) { bridge.ensureChallenge(id) }
        if (pending == null) {
            _status.value = "No verification challenge for $id"
            SpotiFLACDiag.log("manual verification for $id: the runtime raised no challenge")
            return false
        }

        // The runtime hands back the same challenge it already registered, so a check the user
        // solved earlier - in a browser, or before Hush's process was killed - is still sitting
        // on that page as an unspent grant. Reading it first means a repeat request completes
        // instantly instead of opening a browser at a challenge that was already passed.
        when (val state = SpotiFLACChallengeRoute.readPageState(pending.authUrl)) {
            is SpotiFLACChallengeRoute.PageState.Grant -> {
                if (withContext(Dispatchers.IO) { applyGrant(bridge, id, state.token) }) {
                    _status.value = "$id verified"
                    markVerified(id)
                    return true
                }
            }
            // Finished and spent: its grant has already been exchanged, so a browser could only
            // reach a page that answers "Invalid request". Say so instead of opening it.
            SpotiFLACChallengeRoute.PageState.Spent -> {
                _status.value =
                    "The check for $id was already completed - reopen verification for a fresh one"
                SpotiFLACDiag.log("manual verification for $id: the runtime's challenge is spent")
                SpotiFLACVerificationNotifier.clear(context, id)
                return false
            }
            else -> Unit
        }

        SpotiFLACDiag.log(
            "manual verification for $id: opening ${pending.authUrl} in the browser",
        )
        _status.value = "Solve the check in your browser - Hush finishes on its own"
        SpotiFLACChallengeRoute.openInBrowser(context, pending.authUrl)

        val grant = SpotiFLACChallengeRoute.awaitBrowserGrant(
            owner = owner,
            context = context,
            challengeUrl = pending.authUrl,
        )
        if (grant == null) {
            _status.value = "The browser did not finish the check - try again"
            SpotiFLACDiag.log("manual verification for $id: no grant within the wait window")
            return false
        }

        val ok = withContext(Dispatchers.IO) { applyGrant(bridge, id, grant) }
        if (ok) markVerified(id)
        _status.value = if (ok) {
            "$id verified"
        } else {
            "Verification for $id did not complete - try again"
        }
        return ok
    }

    /**
     * Hands a grant to the runtime for every source that challenge refreshes.
     *
     * A solved challenge can renew more than the source it was raised for, so the grant is
     * applied to the whole set instead of making the user repeat the check per source.
     */
    private suspend fun applyGrant(
        bridge: SpotiFLACNativeRuntimeBridge,
        extensionId: String,
        grant: String,
    ): Boolean {
        val targets = bridge.grantTargetSourceIds(extensionId).ifEmpty { listOf(extensionId) }
        bridge.deliverGrant(grant, targets)
        val verified = bridge.isSourceVerified(extensionId)
        SpotiFLACDiag.log(
            "manual verification for $extensionId: authenticated=$verified " +
                "targets=${targets.joinToString(",")}",
        )
        return verified
    }

    /**
     * Clears the prompt and wakes anything waiting on this source.
     *
     * Playback parks a track whose only usable source needed verification, and resumes on the
     * auto-verifier's ticker - so a manual verification has to report through the same channel,
     * or the track would sit there until something else happened.
     */
    private fun markVerified(extensionId: String) {
        SpotiFLAutoVerifier.notifyVerified(extensionId)
    }

    /** Clears the last status, for a dialog or card that has just been dismissed. */
    fun clearStatus() {
        _status.value = null
    }
}
