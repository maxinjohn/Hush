package app.hush.music.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavController
import app.hush.music.ui.component.HushProgressSpinner
import app.hush.music.LocalPlayerAwareWindowInsets
import app.hush.music.R
import app.hush.music.ui.component.IconButton
import app.hush.music.ui.theme.HushAmbientBackground
import app.hush.music.ui.utils.backToMain
import app.hush.music.waze.WazeBridgeDefinition
import app.hush.music.waze.WazeBridgeInstallAttempt
import app.hush.music.waze.WazeBridgeInstallOutcome
import app.hush.music.waze.WazeBridgeInspection
import app.hush.music.waze.WazeBridgeManager
import app.hush.music.waze.WazeBridgeRepair
import app.hush.music.waze.WazeBridgeState
import app.hush.music.waze.WazeBridgeUninstall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * How long a repair waits for an unanswered uninstall confirmation before letting go.
 *
 * Generous on purpose: the confirmation is a system dialog the user may well step away from, and
 * the only cost of waiting is that this screen's buttons stay disabled.
 */
private const val WAZE_UNINSTALL_CONFIRMATION_TIMEOUT_MS = 5 * 60 * 1000L

/**
 * How long an uninstall is watched for, in one-second steps.
 *
 * Covers the whole round trip - the platform's confirmation, the removal, and the return to Hush -
 * because nothing else reports it: the system's uninstall screen finishes without a result on some
 * devices, and the platform's own request is refused outright on others. The Bridge's card used to
 * keep saying "Active" until the user left the screen and came back.
 */
private const val WAZE_UNINSTALL_WATCH_SECONDS = 180

private fun WazeBridgeInspection.statusText(): String = when (state) {
    WazeBridgeState.NOT_INSTALLED -> "Not installed"
    WazeBridgeState.ORIGINAL_APP_INSTALLED -> "Original app installed"
    WazeBridgeState.BRIDGE_CURRENT -> "Up to date"
    WazeBridgeState.BRIDGE_UPDATE_AVAILABLE -> "Update available"
    WazeBridgeState.BRIDGE_UPDATE_REQUIRED -> "Update required"
    WazeBridgeState.BRIDGE_NEWER_THAN_BUNDLED -> "Installed version is newer"
    // Distinct from the provider's original app: this one is ours, from a build signed with a
    // different key, and Android will refuse to replace it until it is removed.
    WazeBridgeState.BRIDGE_SIGNATURE_MISMATCH -> "Needs repair - signed with a different key"
    WazeBridgeState.BUNDLED_APK_MISSING,
    WazeBridgeState.BUNDLED_APK_INVALID,
    -> "Bundled Bridge unavailable"

    WazeBridgeState.UNKNOWN -> "Unable to verify"
}

private fun WazeBridgeInspection.versionText(versionName: String?, versionCode: Long?): String =
    when {
        versionName.isNullOrBlank() && versionCode == null -> "Unavailable"
        versionName.isNullOrBlank() -> "($versionCode)"
        versionCode == null -> versionName
        else -> "$versionName ($versionCode)"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WazeIntegrationSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var processingPackage by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf("") }
    var inspections by remember { mutableStateOf<List<WazeBridgeInspection>>(emptyList()) }
    var pendingInstallPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingUninstallPackage by rememberSaveable { mutableStateOf<String?>(null) }
    // Watches the Bridge an uninstall was started for. Keyed on the attempt counter rather than on a
    // transient view: the drop from `processingPackage` is not the removal.
    var uninstallWatchedPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var uninstallAttempt by rememberSaveable { mutableStateOf(0) }
    // Repair = remove the unreplaceable Bridge, then install the bundled one the moment Android
    // reports the removal finished. A repair therefore spans two user prompts, so the intent to
    // continue is carried in state rather than in a callback: the uninstall result arrives after
    // this composable's function order, and the install it triggers must survive that hop.
    // The repair in flight lives in WazeBridgeRepair, not here: it spans two system dialogs and can
    // be started without a screen at all (the debug automation entry point does exactly that).
    val repairTarget by WazeBridgeRepair.target.collectAsState()
    var installAfterUninstall by rememberSaveable { mutableStateOf<String?>(null) }
    // The last uninstall result this screen has already acted on. Kept in saveable state because the
    // flow replays its current value, and a replayed result must not restart a finished repair.
    var handledUninstallSequence by rememberSaveable { mutableStateOf(0L) }
    // Whether the platform's own confirmation was ever raised for the attempt in flight.
    var uninstallConfirmationSeen by rememberSaveable { mutableStateOf(false) }

    fun inspectionFor(definition: WazeBridgeDefinition): WazeBridgeInspection =
        inspections.firstOrNull { it.definition.packageName == definition.packageName }
            ?: WazeBridgeInspection(
                definition = definition,
                state = WazeBridgeState.UNKNOWN,
                requiredProtocolVersion = definition.requiredProtocolVersion,
            )

    /**
     * Finishes a repair whose removal has happened.
     *
     * The half of a repair that removes the old Bridge runs in the system's own uninstall UI, and the
     * result it reports back is not dependable across devices - some builds finish without one, and a
     * user who confirms the dialog after the screen was recreated never had a result to wait for. So
     * the package's own state is the signal: a repair is complete once the Bridge it targeted is
     * confirmed gone, whichever route removed it.
     */
    fun completeRepairIfRemoved(refreshed: List<WazeBridgeInspection>) {
        val target = repairTarget
        val installed = refreshed.filter { it.isInstalled }.map { it.definition.packageName }.toSet()
        if (WazeBridgeRepair.stepForRepairCheck(target, installed) != WazeBridgeRepair.Step.COMPLETE_WITH_INSTALL) {
            return
        }
        Timber.tag("WazeBridge").d("Repair: %s is gone; installing the bundled build", target ?: "?")
        WazeBridgeRepair.clearRepair()
        installAfterUninstall = target
    }

    fun refreshBridges() {
        scope.launch {
            val refreshed = withContext(Dispatchers.IO) {
                WazeBridgeManager.definitions.map { WazeBridgeManager.inspectBridge(context, it) }
            }
            inspections = refreshed
            Timber.tag("WazeBridge").d(
                "Refreshed bridges: %s",
                refreshed.joinToString { "${it.definition.packageName}=${it.state}" },
            )
            completeRepairIfRemoved(refreshed)
        }
    }

    fun reconnectBridge(definition: WazeBridgeDefinition) {
        context.sendBroadcast(
            Intent("app.hush.music.waze.ACTION_RECONNECT").apply {
                setPackage(definition.packageName)
            },
            "app.hush.music.permission.WAZE_BRIDGE_CONTROL",
        )
    }

    fun confirmBridgeInstall(packageName: String, installerResultCode: Int = -1) {
        val definition = WazeBridgeManager.definitionForPackage(packageName) ?: return
        scope.launch {
            val preInspection = withContext(Dispatchers.IO) {
                WazeBridgeManager.inspectBridge(context, definition)
            }
            val wasInstalled = preInspection.isInstalled
            val previousVersionCode = preInspection.installedVersionCode
            val operation = if (wasInstalled) "Update" else "Install"

            // Show immediate feedback
            statusMessage = if (wasInstalled) "Updating ${definition.displayName}..." else "Installing ${definition.displayName}..."

            val verificationEnabled = WazeBridgeManager.isInstallVerificationEnabled(context)
            val outcome = WazeBridgeInstallAttempt.run(
                wasInstalled = wasInstalled,
                previousVersionCode = previousVersionCode,
                verificationEnabled = verificationEnabled,
                inspect = {
                    withContext(Dispatchers.IO) {
                        WazeBridgeManager.inspectBridge(context, definition)
                    }
                },
                // Raised through the manager rather than this screen's launcher: the platform does not
                // report the installer's result dependably, so the retry's outcome is read from the
                // package itself - and going round the launcher keeps it to one retry per tap.
                relaunch = { retryInspection ->
                    withContext(Dispatchers.IO) {
                        WazeBridgeManager.launchBridgeInstall(context, retryInspection)
                    }
                },
                // An install that has not landed after a few seconds is waiting on a prompt, so the
                // status says so while the attempt keeps watching - the old screen simply stayed on
                // "Installing..." and then reported a failure.
                onStillWaiting = {
                    statusMessage = WazeBridgeInstallOutcome.waitingMessage(
                        displayName = definition.displayName,
                        verificationEnabled = verificationEnabled,
                    )
                },
            )
            val finalInspection = outcome.finalInspection

            processingPackage = null
            pendingInstallPackage = null
            // Clean up both the shared inspection cache file and the dedicated
            // install staging file (extractInstallableBridge serves the latter).
            File(context.cacheDir, "waze-bridge-${definition.id}.apk").delete()
            File(context.cacheDir, "waze-bridge-install-${definition.id}.apk").delete()
            refreshBridges()

            val message = WazeBridgeInstallOutcome.message(
                kind = outcome.kind,
                displayName = definition.displayName,
                fallbackText = finalInspection.statusText(),
                verificationEnabled = verificationEnabled,
                alreadyRetried = outcome.retrySpent,
            )

            statusMessage = message

            if (outcome.landed) {
                reconnectBridge(definition)
            }

            Timber.tag("WazeBridge").d(
                "Install result: bridge=%s package=%s operation=%s " +
                "resultCode=%d prevVersion=%s bundledVersion=%s " +
                "finalInstalled=%b finalVersion=%s state=%s retried=%b message=%s",
                definition.displayName, definition.packageName, operation,
                installerResultCode,
                previousVersionCode?.toString() ?: "none",
                preInspection.bundledVersionCode?.toString() ?: "none",
                finalInspection.isInstalled,
                finalInspection.installedVersionCode?.toString() ?: "none",
                finalInspection.state,
                outcome.retrySpent,
                message,
            )
        }
    }

    val installLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val pkg = pendingInstallPackage
        if (pkg != null) {
            confirmBridgeInstall(pkg, installerResultCode = result.resultCode)
        } else {
            processingPackage = null
        }
    }

    // Fallback route: the App Info screen, used only when the platform refuses a direct uninstall.
    val uninstallLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val repairing = repairTarget
        Timber.tag("WazeBridge").d(
            "Uninstall: the system's uninstall screen returned; repairTarget=%s",
            repairing ?: "none",
        )
        WazeBridgeRepair.clearRepair()
        pendingUninstallPackage = null
        processingPackage = null
        refreshBridges()
        statusMessage = ""
        // Hands a repair off to the effect below, which confirms the removal before installing.
        installAfterUninstall = repairing
    }

    fun installOrUpdateBridge(inspection: WazeBridgeInspection) {
        Timber.tag("WazeBridge").d(
            "Install: preparing bridge=%s state=%s canInstall=%b canUpdate=%b",
            inspection.definition.packageName,
            inspection.state,
            inspection.canInstall,
            inspection.canUpdate,
        )
        processingPackage = inspection.definition.packageName
        statusMessage = "Preparing ${inspection.definition.displayName}..."
        pendingInstallPackage = inspection.definition.packageName
        scope.launch(Dispatchers.IO) {
            val verified = WazeBridgeManager.inspectBridge(context, inspection.definition)
            val apk = WazeBridgeManager.extractInstallableBridge(context, verified)
            withContext(Dispatchers.Main) {
                if (apk == null) {
                    processingPackage = null
                    pendingInstallPackage = null
                    statusMessage = when (verified.state) {
                        WazeBridgeState.ORIGINAL_APP_INSTALLED ->
                            "Original ${verified.definition.providerName} app is already installed."

                        WazeBridgeState.BRIDGE_CURRENT -> "${verified.definition.displayName} is already up to date."
                        WazeBridgeState.BRIDGE_NEWER_THAN_BUNDLED -> "The installed Bridge version is newer. Hush will not downgrade it."
                        WazeBridgeState.BRIDGE_SIGNATURE_MISMATCH ->
                            "${verified.definition.displayName} was signed by a different key. Tap Repair to replace it."

                        WazeBridgeState.BUNDLED_APK_MISSING,
                        WazeBridgeState.BUNDLED_APK_INVALID,
                        -> "The bundled ${verified.definition.displayName} is unavailable."

                        else -> "Unable to verify ${verified.definition.displayName}."
                    }
                    refreshBridges()
                    return@withContext
                }
                try {
                    installLauncher.launch(WazeBridgeManager.installerIntent(context, apk))
                } catch (error: Exception) {
                    Timber.e(error, "Failed to launch Bridge installer")
                    processingPackage = null
                    pendingInstallPackage = null
                    statusMessage = "No installer found on this device."
                }
            }
        }
    }

    /**
     * Opens the system's uninstall for a Bridge: its confirmation dialog first, App Info only if that
     * cannot be opened at all.
     *
     * A repair has to remove the old Bridge before it can install the new one, and the shortest route
     * to that is the platform's own "do you want to uninstall this app?" - one dialog, raised on top
     * of Hush. App Info is a screen the user then has to search for the Uninstall button on, so it is
     * only worth falling back to when nothing will answer the delete intent.
     */
    fun uninstallBridgeViaSystemUi(inspection: WazeBridgeInspection) {
        val installedPackageName = inspection.definition.packageName

        Timber.tag("WazeBridge").d(
            "Uninstall: bridge=%s expectedPackage=%s",
            inspection.definition.displayName,
            installedPackageName,
        )

        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(installedPackageName, 0)
        }.getOrNull()

        Timber.tag("WazeBridge").d(
            "Uninstall: package=%s found=%b",
            installedPackageName,
            packageInfo != null,
        )

        if (packageInfo == null) {
            // Already gone, so a repair in flight can go straight to installing.
            installAfterUninstall = repairTarget
            WazeBridgeRepair.clearRepair()
            refreshBridges()
            statusMessage = "${inspection.definition.displayName} is no longer installed."
            return
        }

        pendingUninstallPackage = installedPackageName

        try {
            uninstallLauncher.launch(
                Intent(Intent.ACTION_DELETE).apply {
                    data = Uri.parse("package:$installedPackageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            Timber.tag("WazeBridge").d("Uninstall: launched the system uninstall dialog for %s", installedPackageName)
        } catch (_: Exception) {
            Timber.tag("WazeBridge").d("Uninstall: delete intent failed, fallback to App Info for %s", installedPackageName)
            try {
                uninstallLauncher.launch(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$installedPackageName")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            } catch (e: Exception) {
                Timber.tag("WazeBridge").e(e, "Uninstall: both delete and App Info failed for %s", installedPackageName)
                pendingUninstallPackage = null
                WazeBridgeRepair.clearRepair()
                statusMessage = "Unable to open the uninstall screen for ${inspection.definition.displayName}."
            }
        }
    }

    /**
     * Asks the platform to remove a Bridge, falling back to the system's uninstall when it refuses.
     *
     * The direct request is the only route that can remove a Bridge without leaving Hush: the platform
     * shows its own confirmation, and the outcome arrives through [WazeBridgeUninstall.results].
     * Whether it is even allowed depends on the firmware and on Hush being the installer of record, so
     * a refusal - and a refusal is instant and silent, with no dialog and no error message - has to
     * lead somewhere. It leads to the system's delete dialog, which is what the user would have had to
     * reach by hand.
     */
    fun startUninstall(inspection: WazeBridgeInspection) {
        val definition = inspection.definition
        // Reset per attempt: it is what distinguishes "the platform never asked the user anything"
        // from "the user was asked and said no".
        uninstallConfirmationSeen = false
        // Starts (or restarts) the watch that refreshes this Bridge's card once its state settles.
        uninstallWatchedPackage = definition.packageName
        uninstallAttempt += 1
        if (WazeBridgeUninstall.request(context, definition.packageName)) {
            statusMessage = "Confirm the uninstall of the old ${definition.displayName}."
            return
        }
        statusMessage = "Confirm the uninstall of the old ${definition.displayName}."
        uninstallBridgeViaSystemUi(inspection)
    }

    /** The plain Uninstall action: no repair follows, so the removal is all the user asked for. */
    fun uninstallBridge(inspection: WazeBridgeInspection) {
        WazeBridgeRepair.clearRepair()
        processingPackage = inspection.definition.packageName
        startUninstall(inspection)
    }

    /**
     * Removes a Bridge Android will not let Hush replace, then installs the bundled one.
     *
     * A Bridge signed with a key Hush does not hold - one from a build signed by a per-machine
     * debug key - can never be updated in place: the platform compares certificates before any of
     * Hush's code runs, and fails the install with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. The only way
     * forward is remove-then-install, and it has to be one tap, because leaving the user to find the
     * uninstall themselves is what made every release of Hush need a manual uninstall and reinstall.
     */
    fun repairBridge(inspection: WazeBridgeInspection) {
        when (WazeBridgeRepair.stepForRequest(installed = inspection.isInstalled)) {
            WazeBridgeRepair.Step.INSTALL_BUNDLED -> {
                // Nothing to remove: this is a plain install.
                WazeBridgeRepair.clearRepair()
                installOrUpdateBridge(inspection)
                return
            }

            else -> Unit
        }

        Timber.tag("WazeBridge").d(
            "Repair: bridge=%s removing the differently-signed build before installing",
            inspection.definition.packageName,
        )
        WazeBridgeRepair.beginRepair(inspection.definition.packageName)
        processingPackage = inspection.definition.packageName
        statusMessage =
            "Removing the old ${inspection.definition.displayName}. Confirm the uninstall and Hush " +
                "installs the current one automatically."
        startUninstall(inspection)
    }

    // The outcome of a direct uninstall. A repair continues from here once the removal is real, and a
    // cancelled or refused removal says so instead of leaving the buttons disabled forever.
    val uninstallResult by WazeBridgeUninstall.results.collectAsState()
    LaunchedEffect(uninstallResult) {
        val result = uninstallResult ?: return@LaunchedEffect
        if (result.sequence == handledUninstallSequence) return@LaunchedEffect
        handledUninstallSequence = result.sequence
        val packageName = result.packageName
        val definition = WazeBridgeManager.definitionForPackage(packageName)
        val step =
            WazeBridgeRepair.stepForUninstallResult(
                status = result.status,
                confirmationSeen = uninstallConfirmationSeen,
                isRepair = WazeBridgeRepair.isRepairing(packageName),
            )

        when (step) {
            WazeBridgeRepair.Step.AWAIT_CONFIRMATION -> {
                uninstallConfirmationSeen = true
                statusMessage =
                    "Confirm the uninstall of the old ${definition?.displayName ?: "Bridge"} to continue."
            }

            WazeBridgeRepair.Step.COMPLETE_WITH_INSTALL -> {
                WazeBridgeRepair.clearRepair()
                refreshBridges()
                // Installs the bundled Bridge, which is the other half of a repair.
                installAfterUninstall = packageName
            }

            WazeBridgeRepair.Step.REPORT_REMOVED -> {
                refreshBridges()
                processingPackage = null
                statusMessage = "${definition?.displayName ?: "Bridge"} was uninstalled."
            }

            WazeBridgeRepair.Step.OPEN_SYSTEM_UNINSTALL -> {
                // A refusal, not a decision: no dialog was ever shown, so the user was never asked.
                // Reach for the system's own uninstall instead of ending the attempt here.
                Timber.tag("WazeBridge").d(
                    "Uninstall: %s was refused without a confirmation; using the system's uninstall",
                    packageName ?: "?",
                )
                if (definition != null) uninstallBridgeViaSystemUi(inspectionFor(definition))
            }

            WazeBridgeRepair.Step.REPORT_CANCELLED -> {
                WazeBridgeRepair.clearRepair()
                processingPackage = null
                refreshBridges()
                statusMessage =
                    "${definition?.displayName ?: "Bridge"} was not uninstalled" +
                        (result.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ". Tap Repair to try again.")
                Timber.tag("WazeBridge").d(
                    "Uninstall: %s was cancelled; repair can be retried",
                    packageName ?: "?",
                )
            }

            else -> Unit
        }
    }

    // A confirmation left unanswered would otherwise keep the Bridge's buttons disabled for the life
    // of the screen, with no hint that anything is waiting. The repair itself is deliberately left
    // pending: the user may still answer that dialog, and then the repair should still finish.
    LaunchedEffect(installAfterUninstall, repairTarget) {
        val pendingPackage = repairTarget ?: return@LaunchedEffect
        delay(WAZE_UNINSTALL_CONFIRMATION_TIMEOUT_MS)
        if (repairTarget != pendingPackage) return@LaunchedEffect
        processingPackage = null
        statusMessage =
            "Hush is still waiting for that uninstall to be confirmed. Confirm it, or tap Repair again."
        Timber.tag("WazeBridge").d("Repair: %s waiting on an unanswered uninstall confirmation", pendingPackage)
    }

    // The second half of a repair. Runs once the system's uninstall screen closes, and installs only
    // when the removal actually happened - a cancelled uninstall must not start an install that
    // Android would reject for the same signature reason.
    LaunchedEffect(installAfterUninstall) {
        val packageName = installAfterUninstall ?: return@LaunchedEffect
        installAfterUninstall = null
        val repairedDefinition = WazeBridgeManager.definitionForPackage(packageName) ?: return@LaunchedEffect

        val verified = withContext(Dispatchers.IO) {
            WazeBridgeManager.inspectBridge(context, repairedDefinition)
        }
        if (verified.needsRepair) {
            processingPackage = null
            statusMessage =
                "${repairedDefinition.displayName} is still installed. Finish the uninstall, then tap Repair."
            Timber.tag("WazeBridge").d("Repair: %s still installed; awaiting uninstall", packageName)
        } else {
            Timber.tag("WazeBridge").d(
                "Repair: %s removed (state=%s); installing the bundled build",
                packageName,
                verified.state,
            )
            installOrUpdateBridge(verified)
        }
    }

    // Watches the Bridge an uninstall was started for until its state settles.
    //
    // This is keyed on the attempt, not on the progress flag: the system's uninstall screen closes
    // and reports back *before* the package is actually gone, so a single refresh at that moment
    // reads the old state and leaves the card saying "Active" until the screen is re-opened. The
    // install path already polls for its result for the same reason; this is the other half.
    LaunchedEffect(uninstallAttempt) {
        if (uninstallAttempt == 0) return@LaunchedEffect
        val watchedPackage = uninstallWatchedPackage ?: return@LaunchedEffect
        val watchedDefinition = WazeBridgeManager.definitionForPackage(watchedPackage) ?: return@LaunchedEffect

        repeat(WAZE_UNINSTALL_WATCH_SECONDS) {
            delay(1000)
            val stillInstalled = withContext(Dispatchers.IO) {
                WazeBridgeManager.inspectBridge(context, watchedDefinition).isInstalled
            }
            if (stillInstalled) return@repeat

            Timber.tag("WazeBridge").d(
                "Uninstall: %s is gone; refreshing the bridge list",
                watchedPackage,
            )
            uninstallWatchedPackage = null
            // A repair continues through this same refresh: completeRepairIfRemoved sees the target
            // gone and hands the install to the effect below, which keeps processingPackage set.
            refreshBridges()
            if (!WazeBridgeRepair.isRepairing(watchedPackage)) {
                processingPackage = null
                statusMessage = "${watchedDefinition.displayName} was uninstalled."
            }
            return@LaunchedEffect
        }

        // Nothing was removed within the window (a cancelled dialog, most likely). Refresh once so the
        // cards show the truth either way, and stop showing progress.
        uninstallWatchedPackage = null
        refreshBridges()
        processingPackage = null
        Timber.tag("WazeBridge").d("Uninstall: %s was not removed; showing the current state", watchedPackage)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refreshBridges()
    }

    val installedBridges = inspections.filter { it.isValidBridge }
    val installedCount = installedBridges.size

    Box(modifier = Modifier.fillMaxSize()) {
        HushAmbientBackground(heightFraction = 0.55f, modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                    ),
                )
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(TopAppBarDefaults.TopAppBarExpandedHeight + 24.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Bridge Status", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Installed bridges: $installedCount",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (installedCount > 0) {
                        val label = if (installedCount == 1) "Active bridge" else "Active bridges"
                        Text(
                            "$label: ${installedBridges.joinToString(", ") { it.definition.displayName }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "No active bridges",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Told once, plainly: a Bridge from a Hush build signed with a different key cannot be
            // updated in place, and that is why every previous Hush release demanded an uninstall.
            // Repairing each one here is what makes later releases update silently again.
            val repairable = inspections.filter { it.needsRepair }
            if (repairable.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            if (repairable.size == 1) "1 bridge needs repair" else "${repairable.size} bridges need repair",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "These were installed by a Hush build signed with a different key, so Android " +
                                "refuses to replace them in place. Tap Repair on each one: Hush removes the old " +
                                "build and installs the current one. After that, future Hush releases update " +
                                "the bridges without uninstalling anything.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            WazeBridgeManager.definitions.forEach { definition ->
                val inspection = inspectionFor(definition)
                val isValid = inspection.isValidBridge
                val isInstalled = inspection.isInstalled
                val isError = inspection.state in setOf(
                    WazeBridgeState.ORIGINAL_APP_INSTALLED,
                    WazeBridgeState.BRIDGE_SIGNATURE_MISMATCH,
                    WazeBridgeState.BRIDGE_UPDATE_REQUIRED,
                    WazeBridgeState.BUNDLED_APK_MISSING,
                    WazeBridgeState.BUNDLED_APK_INVALID,
                )
                val isProcessing = processingPackage == definition.packageName
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = when {
                        isValid -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
                    },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                definition.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            if (isValid) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Text(
                                        " Active ",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))

                        if (inspection.state == WazeBridgeState.ORIGINAL_APP_INSTALLED) {
                            Text(
                                "Original ${definition.providerName} app is already installed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                "To install ${definition.displayName}, uninstall the original ${definition.providerName} app from this phone or choose another supported Waze Bridge.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            if (isInstalled) {
                                Text(
                                    "Installed version: ${inspection.versionText(inspection.installedVersionName, inspection.installedVersionCode)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (inspection.bundledVersionCode != null) {
                                Text(
                                    "Bundled version: ${inspection.versionText(inspection.bundledVersionName, inspection.bundledVersionCode)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (isInstalled) {
                                Text(
                                    "Protocol: ${inspection.installedProtocolVersion ?: "Unavailable"} / Required: ${inspection.requiredProtocolVersion}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            val statusColor = when {
                                isError -> MaterialTheme.colorScheme.error
                                isValid -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            val statusPrefix = if (isValid) "Active \u2022 " else ""
                            Text(
                                "Status: $statusPrefix${inspection.statusText()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = statusColor,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        when (inspection.state) {
                            WazeBridgeState.NOT_INSTALLED -> Row {
                                TextButton(
                                    enabled = !isProcessing,
                                    onClick = { installOrUpdateBridge(inspection) },
                                ) { Text("Install") }
                            }

                            WazeBridgeState.ORIGINAL_APP_INSTALLED -> Row {
                                TextButton(
                                    enabled = !isProcessing,
                                    onClick = { uninstallBridge(inspection) },
                                ) { Text("Uninstall", color = MaterialTheme.colorScheme.error) }
                            }

                            // One tap: remove the unreplaceable Bridge, then install the fresh one
                            // as soon as the system reports the removal finished.
                            WazeBridgeState.BRIDGE_SIGNATURE_MISMATCH -> Row {
                                TextButton(
                                    enabled = !isProcessing,
                                    onClick = { repairBridge(inspection) },
                                ) { Text("Repair") }
                                Spacer(modifier = Modifier.width(8.dp))
                                TextButton(
                                    enabled = !isProcessing,
                                    onClick = { uninstallBridge(inspection) },
                                ) { Text("Uninstall", color = MaterialTheme.colorScheme.error) }
                            }

                            WazeBridgeState.BRIDGE_UPDATE_AVAILABLE,
                            WazeBridgeState.BRIDGE_UPDATE_REQUIRED,
                            -> Row {
                                if (inspection.canUpdate) {
                                    TextButton(
                                        enabled = !isProcessing,
                                        onClick = { installOrUpdateBridge(inspection) },
                                    ) { Text("Update") }
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                TextButton(
                                    enabled = !isProcessing,
                                    onClick = { uninstallBridge(inspection) },
                                ) { Text("Uninstall", color = MaterialTheme.colorScheme.error) }
                            }

                            WazeBridgeState.BRIDGE_CURRENT,
                            WazeBridgeState.BRIDGE_NEWER_THAN_BUNDLED,
                            -> Row {
                                TextButton(
                                    enabled = !isProcessing,
                                    onClick = { uninstallBridge(inspection) },
                                ) { Text("Uninstall", color = MaterialTheme.colorScheme.error) }
                            }

                            WazeBridgeState.BUNDLED_APK_MISSING,
                            WazeBridgeState.BUNDLED_APK_INVALID,
                            WazeBridgeState.UNKNOWN,
                            -> if (isInstalled) {
                                Row {
                                    TextButton(
                                        enabled = !isProcessing,
                                        onClick = { uninstallBridge(inspection) },
                                    ) { Text("Uninstall", color = MaterialTheme.colorScheme.error) }
                                }
                            } else {
                                Unit
                            }
                        }
                    }
                }
            }

            if (statusMessage.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val isLoading = processingPackage != null
                        if (isLoading) {
                            HushProgressSpinner(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(painterResource(R.drawable.info), null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(statusMessage, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(modifier = Modifier.height(SettingsDimensions.ScreenBottomPadding))
        }

        TopAppBar(
            title = { Text(stringResource(R.string.waze_integration)) },
            navigationIcon = {
                IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                    Icon(painterResource(R.drawable.arrow_back), null)
                }
            },
            scrollBehavior = scrollBehavior,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
        )
    }
}