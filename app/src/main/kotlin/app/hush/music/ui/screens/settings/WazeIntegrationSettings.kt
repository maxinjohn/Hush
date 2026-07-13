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
import androidx.compose.material3.CircularProgressIndicator
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
import app.hush.music.LocalPlayerAwareWindowInsets
import app.hush.music.R
import app.hush.music.ui.component.IconButton
import app.hush.music.ui.theme.HushAmbientBackground
import app.hush.music.ui.utils.backToMain
import app.hush.music.waze.WazeBridgeDefinition
import app.hush.music.waze.WazeBridgeInspection
import app.hush.music.waze.WazeBridgeManager
import app.hush.music.waze.WazeBridgeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

private fun WazeBridgeInspection.statusText(): String = when (state) {
    WazeBridgeState.NOT_INSTALLED -> "Not installed"
    WazeBridgeState.ORIGINAL_APP_INSTALLED -> "Original app installed"
    WazeBridgeState.BRIDGE_CURRENT -> "Up to date"
    WazeBridgeState.BRIDGE_UPDATE_AVAILABLE -> "Update available"
    WazeBridgeState.BRIDGE_UPDATE_REQUIRED -> "Update required"
    WazeBridgeState.BRIDGE_NEWER_THAN_BUNDLED -> "Installed version is newer"
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

    fun inspectionFor(definition: WazeBridgeDefinition): WazeBridgeInspection =
        inspections.firstOrNull { it.definition.packageName == definition.packageName }
            ?: WazeBridgeInspection(
                definition = definition,
                state = WazeBridgeState.UNKNOWN,
                requiredProtocolVersion = definition.requiredProtocolVersion,
            )

    fun refreshBridges() {
        scope.launch {
            val refreshed = withContext(Dispatchers.IO) {
                WazeBridgeManager.definitions.map { WazeBridgeManager.inspectBridge(context, it) }
            }
            inspections = refreshed
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

    fun determineInstallResultMessage(
        definition: WazeBridgeDefinition,
        wasInstalled: Boolean,
        previousVersionCode: Long?,
        finalInspection: WazeBridgeInspection,
    ): String = when {
        !wasInstalled && finalInspection.isInstalled ->
            "${definition.displayName} installed successfully."

        wasInstalled && finalInspection.isInstalled &&
            finalInspection.installedVersionCode != previousVersionCode ->
            "${definition.displayName} updated successfully."

        finalInspection.state == WazeBridgeState.BRIDGE_CURRENT ->
            "${definition.displayName} is installed and up to date."

        !wasInstalled && !finalInspection.isInstalled ->
            "${definition.displayName} was not installed."

        wasInstalled && finalInspection.isInstalled ->
            "${definition.displayName} was not updated."

        else -> "${definition.displayName}: ${finalInspection.statusText()}."
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

            val delays = listOf(0L, 300L, 700L, 1200L)
            var finalInspection = preInspection

            for (delay in delays) {
                if (delay > 0) delay(delay)
                finalInspection = withContext(Dispatchers.IO) {
                    WazeBridgeManager.inspectBridge(context, definition)
                }
                Timber.tag("WazeBridge").d(
                    "Post-install retry: bridge=%s delay=%dms installed=%b version=%s",
                    definition.displayName, delay, finalInspection.isInstalled,
                    finalInspection.installedVersionCode?.toString() ?: "none",
                )
                if (finalInspection.isInstalled) break
            }

            processingPackage = null
            pendingInstallPackage = null
            File(context.cacheDir, "waze-bridge-${definition.id}.apk").delete()
            refreshBridges()

            val message = determineInstallResultMessage(
                definition = definition,
                wasInstalled = wasInstalled,
                previousVersionCode = previousVersionCode,
                finalInspection = finalInspection,
            )

            statusMessage = message

            if (finalInspection.isInstalled) {
                reconnectBridge(definition)
            }

            Timber.tag("WazeBridge").d(
                "Install result: bridge=%s package=%s operation=%s " +
                "resultCode=%d prevVersion=%s bundledVersion=%s " +
                "finalInstalled=%b finalVersion=%s state=%s message=%s",
                definition.displayName, definition.packageName, operation,
                installerResultCode,
                previousVersionCode?.toString() ?: "none",
                preInspection.bundledVersionCode?.toString() ?: "none",
                finalInspection.isInstalled,
                finalInspection.installedVersionCode?.toString() ?: "none",
                finalInspection.state,
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

    val uninstallLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        pendingUninstallPackage = null
        refreshBridges()
        statusMessage = ""
    }

    fun installOrUpdateBridge(inspection: WazeBridgeInspection) {
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
                        WazeBridgeState.BUNDLED_APK_MISSING,
                        WazeBridgeState.BUNDLED_APK_INVALID,
                        -> "The bundled ${verified.definition.displayName} is unavailable."

                        else -> "Unable to verify ${verified.definition.displayName}."
                    }
                    refreshBridges()
                    return@withContext
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.FileProvider",
                    apk,
                )
                try {
                    installLauncher.launch(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                } catch (error: Exception) {
                    Timber.e(error, "Failed to launch Bridge installer")
                    processingPackage = null
                    pendingInstallPackage = null
                    statusMessage = "No installer found on this device."
                }
            }
        }
    }

    fun uninstallBridge(inspection: WazeBridgeInspection) {
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
            refreshBridges()
            statusMessage = "${inspection.definition.displayName} is no longer installed."
            return
        }

        pendingUninstallPackage = installedPackageName

        try {
            uninstallLauncher.launch(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$installedPackageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            Timber.tag("WazeBridge").d("Uninstall: launched App Info for %s", installedPackageName)
        } catch (_: Exception) {
            Timber.tag("WazeBridge").d("Uninstall: App Info failed, fallback to ACTION_DELETE for %s", installedPackageName)
            try {
                uninstallLauncher.launch(
                    Intent(Intent.ACTION_DELETE).apply {
                        data = Uri.parse("package:$installedPackageName")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            } catch (e: Exception) {
                Timber.tag("WazeBridge").e(e, "Uninstall: both App Info and ACTION_DELETE failed for %s", installedPackageName)
                pendingUninstallPackage = null
                statusMessage = "Unable to open uninstall screen for ${inspection.definition.displayName}."
            }
        }
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

            WazeBridgeManager.definitions.forEach { definition ->
                val inspection = inspectionFor(definition)
                val isValid = inspection.isValidBridge
                val isInstalled = inspection.isInstalled
                val isError = inspection.state in setOf(
                    WazeBridgeState.ORIGINAL_APP_INSTALLED,
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

                            WazeBridgeState.ORIGINAL_APP_INSTALLED -> Unit

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
                            -> Unit
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
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
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
