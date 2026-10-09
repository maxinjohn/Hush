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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The one "try again" action behind every Renew in the app.
 *
 * Renewing and checking are two jobs, but "try again" is one thing to ask for, and doing them in
 * that order is what the answer has to be. The gateway will rotate every session it still accepts;
 * whatever it turns down cannot be renewed at all - a record bound to an extension version that has
 * since moved answers a bare `403` from `/v2/session/refresh`, and a source with no record has
 * nothing to refresh - so those are handed to the verifier, which is the only thing that can mint a
 * replacement. Renewing without the second step leaves a source that no amount of renewing can fix;
 * checking without the first spends a browser trip on a session the gateway would have rotated for
 * free.
 *
 * It lives here rather than inside either screen because both call it - the launch notice's Renew
 * and Audio Sources' Renew - and two copies of "what Renew does" is exactly how the two came to
 * disagree. One implementation, one [Outcome], so the two surfaces cannot report different things
 * about the same run.
 *
 * Audio Sources still offers its own Verify beside this. That is deliberate and unchanged: a check
 * can be asked for on its own, without renewing anything, and that button is where it lives.
 *
 * ## Why the work runs on its own scope
 *
 * A run must outlive whichever surface asked for it. It used to be launched from the launch
 * notice's own `rememberCoroutineScope()`, and that notice disappears the instant its one action is
 * taken - the composable left the composition, which cancels the scope it had just launched on, and
 * every step after the renewal was dropped. Measured on device: the log carried
 * `session renew (launch-notice): renewed=0 of 4` and then nothing at all - no `auto-verify enqueue`,
 * no notice refresh, both of which are logged unconditionally when they run. So the renewal really
 * happened and the check it promised never did, which is what "I pressed it and nothing happened" is.
 *
 * Holding the scope here also means the same is true of the settings screen: leaving that screen
 * mid-run used to cancel the reads that report the result.
 */
object SpotiFLACSessionRecovery {

    /**
     * What one run did, in the words a surface shows.
     *
     * @param summary the renewal's own line, from [SpotiFLACSessionRenewer.summarise], which already
     *   distinguishes a renewed source from one that needs a check from one the gateway refused.
     * @param checked the sources the renewal left unusable, handed to the verifier in this run.
     */
    data class Outcome(
        val summary: String,
        val checked: List<String>,
    ) {
        /**
         * The renewal line plus what was raised for it.
         *
         * Named as "raised a check" rather than "verifying" because the check has not happened yet
         * when this is drawn: the verifier solves it afterwards, one source at a time, and reports
         * each landing through [SpotiFLAutoVerifier.steps] and the app's own verified banner.
         */
        val line: String
            get() =
                if (checked.isEmpty()) summary
                else "$summary \u00b7 raised a check for ${checked.joinToString(", ")}"
    }

    /**
     * Carries the run, not the caller.
     *
     * IO because every step is file reads, a keychain read and network calls; supervised so one
     * failed source cannot take the scope down with it and silently stop every later Renew.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _running = MutableStateFlow(false)

    /** True while a run is in flight, so a button can say so without owning the work. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _outcome = MutableStateFlow<Outcome?>(null)

    /** The last run's outcome, for whichever surface draws one. */
    val outcome: StateFlow<Outcome?> = _outcome.asStateFlow()

    /**
     * Renews every session the gateway will still rotate, then raises a check for whatever is left.
     *
     * Safe to call from a tap: a second tap while a run is in flight joins it rather than starting a
     * second one. Two runs would renew the same sessions twice, and - worse - the second would clear
     * the first's outcome before it had been read.
     *
     * @param reason short label for diagnostics ("launch-notice", "settings-renew").
     */
    fun renewAndCheck(
        context: Context,
        reason: String,
    ) {
        val appContext = context.applicationContext
        if (!_running.compareAndSet(expect = false, update = true)) {
            SpotiFLACDiag.log("session recovery ($reason): joined the run already in flight")
            return
        }
        _outcome.value = null
        scope.launch {
            try {
                val results =
                    runCatching {
                        SpotiFLACSessionRenewer.renewAll(appContext, force = true, reason = reason)
                    }
                        .onFailure { SpotiFLACDiag.log("session recovery ($reason) failed: ${it.message}") }
                        .getOrDefault(emptyList())
                val checked = sourcesNeedingCheck(results, enabledSourceIds())
                if (checked.isNotEmpty()) {
                    SpotiFLAutoVerifier.enqueue(
                        sourceIds = checked,
                        reason = reason,
                        force = true,
                    )
                }
                _outcome.value = Outcome(SpotiFLACSessionRenewer.summarise(results), checked)
                SpotiFLACDiag.log(
                    "session recovery ($reason): renewed=${results.count { it.renewed }} " +
                        "raised=${checked.size} [${checked.joinToString(",")}]",
                )
                // The launch notice is the surface that reports this state, so it is re-read on
                // every run: one that fixed the engine clears it, and one that did not keeps it on
                // screen with the run's own line under it. Skipping this is why a run that failed
                // changed nothing the user could see.
                SpotiFLACSessionNotice.refresh(appContext)
            } finally {
                _running.value = false
            }
        }
    }

    /** Forgets the last outcome, so a screen does not open onto a run that is already over. */
    fun clear() {
        _outcome.value = null
    }

    /**
     * The sources one renewal left needing the user.
     *
     * Read from the renewal's own answers rather than from the bridge's auth state, and that is the
     * point: the runtime's preflight is *local*, so a record with a future expiry passes it and a
     * source the gateway has already thrown away still reads as usable for a moment. Only the
     * gateway's answer distinguishes "this session is gone" from "this session is fine", and
     * [SpotiFLACSessionRenewer.RenewResult.needsVerification] is where it lands.
     *
     * [enabledSourceIds] bounds the run to sources that are switched on: a check raises a browser
     * trip and a gateway round trip, and spending one on a source the user has turned off is work
     * nobody asked for. An empty list means the caller has no opinion (the repository has not been
     * read yet), which must not silently drop every check - so it filters nothing.
     */
    internal fun sourcesNeedingCheck(
        results: List<SpotiFLACSessionRenewer.RenewResult>,
        enabledSourceIds: List<String>,
    ): List<String> =
        results
            .filter { !it.renewed && it.needsVerification }
            .map { it.extensionId }
            .filter { id -> enabledSourceIds.isEmpty() || id in enabledSourceIds }
            .distinct()

    /**
     * The sources this install has switched on.
     *
     * Best-effort on purpose: the repository is built at app start, and a run that reaches here
     * before it exists must still renew. An empty answer therefore means "no opinion" rather than
     * "nothing is on" - see [sourcesNeedingCheck].
     */
    private fun enabledSourceIds(): List<String> =
        runCatching { ExtensionRepositoryManager.getInstance().getEnabledSourceIds() }
            .getOrDefault(emptyList())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}
