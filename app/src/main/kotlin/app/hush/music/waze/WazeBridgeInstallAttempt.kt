/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.waze

import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * One Bridge install attempt, watched to its conclusion.
 *
 * This used to be a polling loop inside the settings screen. It waited for the package to appear and
 * reported what it found - which could not express the thing that actually happens on a real device:
 * the package verifier (Google Play Protect) holds a sideloaded install *after* the installer screen
 * has closed, so ten seconds of polling found nothing and a successful install was reported as a
 * failure while the prompt blocking it stayed on screen, unmentioned.
 *
 * Two rules live here, and both are exercised by tests rather than by tapping: an attempt is over
 * when the package *settles* (a version change for an update, not mere presence - during an update the
 * package is present the whole time, so the old loop declared live updates "not updated"
 * immediately), and a failure that the verifier could explain is retried once, because the answer to
 * its prompt only reaches an install that is asked for a second time.
 */
object WazeBridgeInstallAttempt {

    /**
     * How often the package's state is re-read - about 46 seconds in total.
     *
     * Deliberately much longer than the ten seconds this used to allow, and longer still than the ~33
     * seconds it allowed next. The thing being waited for is a person working through what the platform
     * puts in front of them, and on a real device that is more than one screen: measured on a OnePlus
     * NE2211, a Bridge install is the installer dialog, then Play Protect's "Scan app" prompt, then the
     * vendor's own `InstallGuideActivity` ("Continue installation"). Answering three prompts took longer
     * than 33 seconds per pass, so the attempt gave up on an install that then completed when asked for
     * again - the retry rescued it, at the cost of a spurious failure message first. The wait costs
     * nothing when an install lands promptly, because the polling stops the moment it does; it only
     * decides how patient Hush is when the user is the slow part.
     */
    val DEFAULT_POLL_DELAYS_MS = listOf(
        0L, 500L, 1000L, 1500L, 2500L, 3500L, 5000L, 5000L, 5000L, 5000L, 5000L, 5000L, 6000L, 6000L,
    )

    /** How long an attempt may stay unsettled before the wait itself is reported to the user. */
    const val WAITING_NOTICE_AFTER_MS = 3500L

    const val TAG = "WazeBridge"

    data class Outcome(
        val kind: WazeBridgeInstallOutcome.Kind,
        val finalInspection: WazeBridgeInspection,

        /**
         * True once the one automatic retry is spent - either because it was raised, or because there
         * was nothing left to raise. It changes what the user is told: waiting on the verifier, versus
         * having to start the install again.
         */
        val retrySpent: Boolean,
    ) {
        val landed: Boolean get() = WazeBridgeInstallOutcome.landed(kind)
    }

    /**
     * Waits for the attempt in flight to settle, then says what happened.
     *
     * [inspect] reads the Bridge's state afresh, [relaunch] hands the installer to the platform again
     * and reports whether it could be raised at all.
     */
    suspend fun run(
        wasInstalled: Boolean,
        previousVersionCode: Long?,
        verificationEnabled: Boolean,
        inspect: suspend () -> WazeBridgeInspection,
        relaunch: suspend (WazeBridgeInspection) -> Boolean,
        onStillWaiting: suspend () -> Unit = {},
        pollDelaysMs: List<Long> = DEFAULT_POLL_DELAYS_MS,
        pause: suspend (Long) -> Unit = { if (it > 0) delay(it) },
    ): Outcome {
        val settled: (WazeBridgeInspection) -> Boolean = if (wasInstalled) {
            { it.installedVersionCode != previousVersionCode }
        } else {
            { it.isInstalled }
        }

        fun classify(inspection: WazeBridgeInspection): WazeBridgeInstallOutcome.Kind =
            WazeBridgeInstallOutcome.kind(wasInstalled, previousVersionCode, inspection)

        suspend fun awaitSettled(): WazeBridgeInspection {
            var inspection = inspect()
            var waitedMs = 0L
            var reportedWaiting = false
            for (delayMs in pollDelaysMs) {
                if (settled(inspection)) return inspection
                pause(delayMs)
                waitedMs += delayMs
                // An install that has not landed after a few seconds is usually waiting on the user.
                // Saying so - while still watching - is what turns a silent stall into a prompt they
                // know to answer.
                if (!reportedWaiting && waitedMs >= WAITING_NOTICE_AFTER_MS) {
                    reportedWaiting = true
                    onStillWaiting()
                }
                inspection = inspect()
            }
            return inspection
        }

        val first = awaitSettled()
        val firstKind = classify(first)

        if (!WazeBridgeInstallOutcome.shouldRetry(firstKind, verificationEnabled, alreadyRetried = false)) {
            return Outcome(firstKind, first, retrySpent = false)
        }

        val retryInspection = inspect()
        if (!retryInspection.canInstall && !retryInspection.canUpdate) {
            // Nothing left to raise: it settled somewhere that needs no install after all.
            Timber.tag(TAG).d(
                "Install retry: %s has nothing to re-raise (state=%s)",
                retryInspection.definition.packageName,
                retryInspection.state,
            )
            return Outcome(firstKind, first, retrySpent = true)
        }

        Timber.tag(TAG).d(
            "Install retry: %s did not land and this device verifies sideloads; re-raising the installer once",
            retryInspection.definition.packageName,
        )
        if (!relaunch(retryInspection)) return Outcome(firstKind, first, retrySpent = true)

        val second = awaitSettled()
        return Outcome(classify(second), second, retrySpent = true)
    }
}
