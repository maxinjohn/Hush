/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.waze

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import app.hush.music.BuildConfig

/**
 * Drives a Bridge repair without a hand on the screen. **Debug builds only.**
 *
 * A repair spans two system dialogs, and the app's own half of it - remove, notice the removal,
 * install the bundled Bridge - is what needs proving. That half can be exercised without any tap by
 * pairing this receiver with the shell: start a repair, remove the Bridge the way the system's
 * uninstall dialog would (`adb shell pm uninstall <package>`), then ask Hush to re-check. What the
 * app decides and launches at each point is in logcat under `WazeBridge`, and the step it takes comes
 * from [WazeBridgeRepair], which the unit tests pin directly.
 *
 * Registered in `src/debug/AndroidManifest.xml`, so it exists in nothing that ships.
 *
 * ```
 * adb shell am broadcast -a app.hush.music.action.WAZE_REPAIR_DEBUG \
 *     --es op check --es package deezer.android.app
 * ```
 *
 * ## The verdict contract
 *
 * Every operation logs exactly one machine-readable line
 * (`smoke step=<name> bridge=<package> verdict=<verdict> …`) in addition to its human-readable
 * output, so `scripts/waze-bridge-lifecycle-smoke.sh` can gate a release on the outcome without
 * parsing prose. Verdicts are `ok`, `PASS`, `FAIL`, `SKIPPED` or a request state; a step that cannot
 * be decided is `SKIPPED` with the reason, never a silent pass.
 */
class WazeBridgeRepairDebugReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        Log.d(TAG, "received action=${intent.action} debug=${BuildConfig.DEBUG}")
        if (!BuildConfig.DEBUG) return
        if (intent.action != ACTION_REPAIR_DEBUG) return

        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        if (packageName != null && WazeBridgeManager.definitionForPackage(packageName) == null) {
            log("unknown bridge package=%s", packageName)
            return
        }

        when (val operation = intent.getStringExtra(EXTRA_OPERATION) ?: OP_STATUS) {
            OP_STATUS -> logStatus(context)
            OP_REPAIR -> startRepair(context, packageName)
            OP_CHECK -> completeRepair(context)
            OP_INSTALL -> installBundled(context, packageName)
            OP_UNINSTALL -> requestUninstall(context, packageName)
            OP_VERIFY -> verifyState(
                context = context,
                packageName = packageName,
                expectation = intent.getStringExtra(EXTRA_EXPECT),
                expectedVersionCode = intent.getStringExtra(EXTRA_VERSION)?.toLongOrNull(),
            )
            OP_CLEAR -> {
                WazeBridgeRepair.clearRepair()
                log("cleared the repair target")
            }

            else -> log("unknown operation=%s", operation)
        }
    }

    /** Starts a repair the way the screen's Repair button does, minus the tap. */
    private fun startRepair(
        context: Context,
        packageName: String?,
    ) {
        val definition = packageName?.let { WazeBridgeManager.definitionForPackage(it) }
        val inspection = definition?.let { WazeBridgeManager.inspectBridge(context, it) }
            ?: return log("repair needs a package")

        log(
            "start package=%s state=%s installerOfRecord=%b verifiesSideloads=%b",
            definition.packageName,
            inspection.state,
            isInstallerOfRecord(context, definition.packageName),
            WazeBridgeManager.isInstallVerificationEnabled(context),
        )
        when (WazeBridgeRepair.stepForRequest(installed = inspection.isInstalled)) {
            WazeBridgeRepair.Step.INSTALL_BUNDLED -> {
                WazeBridgeRepair.clearRepair()
                log(
                    "smoke step=repair bridge=%s verdict=STARTED_WITH_INSTALL state=%s",
                    definition.packageName,
                    inspection.state,
                )
                installBundled(context, definition.packageName)
            }

            else -> {
                WazeBridgeRepair.beginRepair(definition.packageName)
                val requested = WazeBridgeUninstall.request(context, definition.packageName)
                log("uninstall requested=%b (a refusal arrives on the results flow)", requested)
                log(
                    "smoke step=repair bridge=%s verdict=%s state=%s",
                    definition.packageName,
                    if (requested) "STARTED_WITH_UNINSTALL" else "UNINSTALL_REFUSED",
                    inspection.state,
                )
            }
        }
    }

    /**
     * The completion rule, run against the Bridge's real state.
     *
     * This is the step a repair could previously lose: it must install the bundled Bridge once the
     * one it targeted is gone, and it has to reach that conclusion from an inspection rather than from
     * an uninstall result the platform may never send.
     */
    private fun completeRepair(context: Context) {
        val target = WazeBridgeRepair.target.value
        val installed = WazeBridgeManager.definitions
            .map { WazeBridgeManager.inspectBridge(context, it) }
            .filter { it.isInstalled }
            .map { it.definition.packageName }
            .toSet()
        log("check target=%s installed=%s", target ?: "none", installed.joinToString(","))
        when (WazeBridgeRepair.stepForRepairCheck(target = target, installedPackages = installed)) {
            WazeBridgeRepair.Step.COMPLETE_WITH_INSTALL -> {
                val definition = target?.let { WazeBridgeManager.definitionForPackage(it) }
                WazeBridgeRepair.clearRepair()
                log("repair complete: %s is gone; installing the bundled bridge", target ?: "?")
                log(
                    "smoke step=check bridge=%s verdict=COMPLETE_WITH_INSTALL",
                    target ?: "none",
                )
                definition?.let { installBundled(context, it.packageName) }
            }

            else -> {
                log("repair waiting: %s is still installed", target ?: "no target")
                log(
                    "smoke step=check bridge=%s verdict=WAITING_FOR_UNINSTALL",
                    target ?: "none",
                )
            }
        }
    }

    /**
     * Stages the bundled Bridge, hands it to the system installer, and reports how the attempt ended.
     *
     * It runs the same attempt the settings screen runs, so the retry that follows an install
     * interrupted by the device's package verifier can be observed from a shell without any taps.
     */
    private fun installBundled(
        context: Context,
        packageName: String?,
    ) {
        val definition = packageName?.let { WazeBridgeManager.definitionForPackage(it) }
            ?: return log("install needs a package")
        val inspection = WazeBridgeManager.inspectBridge(context, definition)
        val verificationEnabled = WazeBridgeManager.isInstallVerificationEnabled(context)
        val launched = WazeBridgeManager.launchBridgeInstall(context, inspection)
        log(
            "install package=%s state=%s launched=%b verifiesSideloads=%b",
            definition.packageName,
            inspection.state,
            launched,
            verificationEnabled,
        )
        if (!launched) return

        // The attempt outlives this callback, so the receiver is kept alive for it.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val outcome = WazeBridgeInstallAttempt.run(
                    wasInstalled = inspection.isInstalled,
                    previousVersionCode = inspection.installedVersionCode,
                    verificationEnabled = verificationEnabled,
                    inspect = { WazeBridgeManager.inspectBridge(context, definition) },
                    relaunch = { WazeBridgeManager.launchBridgeInstall(context, it) },
                )
                log(
                    "install outcome package=%s kind=%s retrySpent=%b installed=%b version=%s",
                    definition.packageName,
                    outcome.kind,
                    outcome.retrySpent,
                    outcome.finalInspection.isInstalled,
                    outcome.finalInspection.installedVersionCode?.toString() ?: "none",
                )
                log(
                    "smoke step=%s bridge=%s verdict=%s kind=%s retrySpent=%b installed=%b " +
                        "version=%s bundledVersion=%s",
                    if (inspection.isInstalled) "update" else "install",
                    definition.packageName,
                    if (outcome.landed) "PASS" else "FAIL",
                    outcome.kind,
                    outcome.retrySpent,
                    outcome.finalInspection.isInstalled,
                    outcome.finalInspection.installedVersionCode?.toString() ?: "none",
                    outcome.finalInspection.bundledVersionCode?.toString() ?: "none",
                )
            } finally {
                pending.finish()
            }
        }
    }

    private fun logStatus(context: Context) {
        WazeBridgeRepair.target.value?.let { log("smoke step=status bridge=repair-target verdict=ok target=%s", it) }
        WazeBridgeManager.definitions.forEach { definition ->
            val inspection = WazeBridgeManager.inspectBridge(context, definition)
            log(
                "status %s state=%s installedVersion=%s bundledVersion=%s installerOfRecord=%b",
                definition.packageName,
                inspection.state,
                inspection.installedVersionCode?.toString() ?: "none",
                inspection.bundledVersionCode?.toString() ?: "none",
                isInstallerOfRecord(context, definition.packageName),
            )
            log(
                "smoke step=status bridge=%s verdict=ok state=%s installedVersion=%s " +
                    "bundledVersion=%s installerOfRecord=%b",
                definition.packageName,
                inspection.state,
                inspection.installedVersionCode?.toString() ?: "none",
                inspection.bundledVersionCode?.toString() ?: "none",
                isInstallerOfRecord(context, definition.packageName),
            )
        }
    }

    /**
     * Asks the platform to remove a Bridge - the route Hush's Uninstall button takes.
     *
     * A refusal is a result here, not a failure: it is what the button has to fall back from, and the
     * script decides whether to accept it or remove the Bridge another way.
     */
    private fun requestUninstall(
        context: Context,
        packageName: String?,
    ) {
        val definition = packageName?.let { WazeBridgeManager.definitionForPackage(it) }
            ?: return log("smoke step=uninstall bridge=none verdict=FAIL reason=no-package")
        if (!WazeBridgeManager.inspectBridge(context, definition).isInstalled) {
            return log(
                "smoke step=uninstall bridge=%s verdict=SKIPPED reason=not-installed",
                definition.packageName,
            )
        }
        val requested = WazeBridgeUninstall.request(context, definition.packageName)
        log(
            "smoke step=uninstall bridge=%s verdict=%s",
            definition.packageName,
            if (requested) "REQUESTED" else "REFUSED",
        )
    }

    /**
     * Asserts a Bridge's state against an expectation, for the script's gate.
     *
     * `--es expect installed|absent` (optionally `--es version <code>`). Written as an explicit check
     * so a lifecycle step's success is decided by the app's own inspection rather than by the script
     * guessing what "worked" looks like.
     */
    private fun verifyState(
        context: Context,
        packageName: String?,
        expectation: String?,
        expectedVersionCode: Long?,
    ) {
        val definition = packageName?.let { WazeBridgeManager.definitionForPackage(it) }
            ?: return log("smoke step=verify bridge=none verdict=FAIL reason=no-package")
        val inspection = WazeBridgeManager.inspectBridge(context, definition)
        val versionMatches = expectedVersionCode == null || inspection.installedVersionCode == expectedVersionCode
        val matches = when (expectation?.lowercase()) {
            "installed" -> inspection.isInstalled && versionMatches
            "absent" -> !inspection.isInstalled
            else -> false
        }

        log(
            "smoke step=verify bridge=%s verdict=%s expect=%s state=%s installed=%b version=%s " +
                "bundledVersion=%s",
            definition.packageName,
            if (matches) "PASS" else "FAIL",
            expectation ?: "unspecified",
            inspection.state,
            inspection.isInstalled,
            inspection.installedVersionCode?.toString() ?: "none",
            inspection.bundledVersionCode?.toString() ?: "none",
        )
    }

    private fun isInstallerOfRecord(
        context: Context,
        packageName: String,
    ): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(packageName)
        }
    }.getOrNull() == context.packageName

    /**
     * Logged through [Log] on purpose: this receiver is the way the repair is driven from a shell,
     * and its output has to be readable whatever the app's logging configuration happens to be.
     */
    private fun log(
        message: String,
        vararg args: Any?,
    ) {
        Log.d(TAG, if (args.isEmpty()) message else message.format(*args))
    }

    companion object {
        private const val TAG = "WazeBridgeDebug"

        const val ACTION_REPAIR_DEBUG = "app.hush.music.action.WAZE_REPAIR_DEBUG"
        const val EXTRA_OPERATION = "op"
        const val EXTRA_PACKAGE = "package"

        const val EXTRA_EXPECT = "expect"
        const val EXTRA_VERSION = "version"

        const val OP_STATUS = "status"
        const val OP_REPAIR = "repair"
        const val OP_CHECK = "check"
        const val OP_INSTALL = "install"
        const val OP_UNINSTALL = "uninstall"
        const val OP_VERIFY = "verify"
        const val OP_CLEAR = "clear"
    }
}
