/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.waze

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The decisions a Bridge repair makes, kept apart from the Android calls that carry them out.
 *
 * A repair is a conversation with the platform: ask it to remove an unreplaceable Bridge, find out
 * whether it asked the user at all, fall back to the system's own uninstall when it did not, and
 * install the bundled Bridge once the old one is really gone. Every one of those steps used to live
 * inside one composable, where the only way to test it was to tap through two system dialogs.
 *
 * Splitting the decisions out makes the interesting part testable, and it is the part that broke:
 * a refusal with no dialog used to dead-end, and a repair used to depend on an uninstall result that
 * some devices never send. The rules below are covered by [WazeBridgeRepairTest]; the callers only
 * perform the step they are handed.
 */
object WazeBridgeRepair {

    /** What a repair should do next. */
    enum class Step {
        /** Nothing is installed, so there is nothing to remove: install the bundled Bridge. */
        INSTALL_BUNDLED,

        /** An unreplaceable Bridge is installed: start removing it. */
        START_UNINSTALL,

        /** The platform is asking the user; the outcome is still to come. */
        AWAIT_CONFIRMATION,

        /**
         * The platform refused without asking anyone - an instant, silent failure.
         *
         * This is a refusal, not a decision, so the user still has to be offered the system's own
         * uninstall rather than being told to try again by hand.
         */
        OPEN_SYSTEM_UNINSTALL,

        /** The removal happened: install the bundled Bridge, which is the other half of a repair. */
        COMPLETE_WITH_INSTALL,

        /** A plain uninstall finished; no repair follows. */
        REPORT_REMOVED,

        /** The user was asked and declined, or the removal failed for a reason we can report. */
        REPORT_CANCELLED,

        /** Nothing to do yet: the Bridge a repair is waiting on is still installed. */
        WAIT,
    }

    /**
     * The Bridge a repair is in the middle of.
     *
     * Held here rather than in the screen's own state because a repair outlives the screen: it spans
     * two system dialogs, the process can be recreated between them, and the automation entry point
     * in the debug build starts one without a screen at all.
     */
    private val _target = MutableStateFlow<String?>(null)
    val target: StateFlow<String?> = _target.asStateFlow()

    /**
     * Where the repair in flight is written down, so it also outlives the *process*.
     *
     * That is not a theoretical worry: the first half of a repair is a system uninstall, which puts
     * Hush in the background while the platform's dialog is up, and an app in the background can be
     * killed. Measured on a OnePlus NE2211, the process was recreated between the removal and the
     * install, and the repair - which lived only in memory - was simply forgotten: the Bridge was gone
     * and nothing installed the bundled one. Persisting the target is what makes the second half run
     * on the next inspection, whichever process performs it.
     */
    @Volatile
    private var prefs: SharedPreferences? = null

    /** A repair older than this is abandoned rather than resumed: it belongs to a stale session. */
    const val MAX_AGE_MS: Long = 15 * 60 * 1000L

    private const val PREFS_NAME = "waze_bridge_repair"
    private const val KEY_TARGET = "target"
    private const val KEY_STARTED_AT = "started_at"

    /**
     * Connects the repair to persistent storage and restores one that was in flight.
     *
     * Optional on purpose: without it the repair still works for the lifetime of the process, which
     * is what the unit tests exercise.
     */
    fun attach(context: Context) {
        val store = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = store
        val stored = store.getString(KEY_TARGET, null)
        if (stored != null && isRepairFresh(store.getLong(KEY_STARTED_AT, 0L), System.currentTimeMillis())) {
            _target.value = stored
        } else if (stored != null) {
            clearRepair()
        }
    }

    /**
     * Whether a repair started at [startedAtMs] is still worth resuming at [nowMs].
     *
     * Pure, so the staleness rule is tested rather than assumed: resuming a repair from an hour ago
     * would install a Bridge the user has long since dealt with by hand.
     */
    fun isRepairFresh(
        startedAtMs: Long,
        nowMs: Long,
    ): Boolean = startedAtMs > 0L && nowMs - startedAtMs in 0..MAX_AGE_MS

    fun beginRepair(packageName: String) {
        _target.value = packageName
        prefs?.edit()
            ?.putString(KEY_TARGET, packageName)
            ?.putLong(KEY_STARTED_AT, System.currentTimeMillis())
            ?.apply()
    }

    fun clearRepair() {
        _target.value = null
        prefs?.edit()?.remove(KEY_TARGET)?.remove(KEY_STARTED_AT)?.apply()
    }

    /** True when the repair in flight is for [packageName]. */
    fun isRepairing(packageName: String?): Boolean =
        packageName != null && packageName == _target.value

    /** Whether a repair has to remove a Bridge first, or can go straight to installing. */
    fun stepForRequest(installed: Boolean): Step =
        if (installed) Step.START_UNINSTALL else Step.INSTALL_BUNDLED

    /**
     * What an uninstall attempt's outcome means.
     *
     * [confirmationSeen] is what separates a refusal from a cancellation: if the platform never
     * raised its own confirmation, the user was never asked and the repair has to find another way.
     */
    fun stepForUninstallResult(
        status: WazeBridgeUninstall.Status,
        confirmationSeen: Boolean,
        isRepair: Boolean,
    ): Step = when (status) {
        WazeBridgeUninstall.Status.PENDING_CONFIRMATION -> Step.AWAIT_CONFIRMATION
        WazeBridgeUninstall.Status.SUCCEEDED ->
            if (isRepair) Step.COMPLETE_WITH_INSTALL else Step.REPORT_REMOVED

        WazeBridgeUninstall.Status.FAILED ->
            if (confirmationSeen) Step.REPORT_CANCELLED else Step.OPEN_SYSTEM_UNINSTALL
    }

    /**
     * Whether a repair waiting on [target] can continue, given the packages still installed.
     *
     * This is the completion rule, and it deliberately reads the package's own state instead of
     * waiting for an uninstall result: some firmware finishes the system uninstall screen without
     * reporting one, and a user who answers the dialog after the screen was recreated never had a
     * result to send.
     */
    fun stepForRepairCheck(
        target: String?,
        installedPackages: Set<String>,
    ): Step = when {
        target == null -> Step.WAIT
        target in installedPackages -> Step.WAIT
        else -> Step.COMPLETE_WITH_INSTALL
    }
}
