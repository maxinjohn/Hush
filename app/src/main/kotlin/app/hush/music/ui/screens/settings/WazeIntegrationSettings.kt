package app.hush.music.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavController
import app.hush.music.LocalPlayerAwareWindowInsets
import app.hush.music.R
import app.hush.music.constants.WazeIntegrationEnabledKey
import app.hush.music.constants.WazeIntegrationPackageKey
import app.hush.music.ui.component.IconButton
import app.hush.music.ui.theme.HushAmbientBackground
import app.hush.music.ui.utils.backToMain
import app.hush.music.utils.dataStore
import app.hush.music.waze.WazeBridgeDefinition
import app.hush.music.waze.WazeBridgeInspection
import app.hush.music.waze.WazeBridgeManager
import app.hush.music.waze.WazeBridgeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

private fun WazeBridgeInspection.statusText(): String = when (status) {
    WazeBridgeStatus.NOT_INSTALLED -> "Not installed"
    WazeBridgeStatus.UP_TO_DATE -> "Up to date"
    WazeBridgeStatus.UPDATE_AVAILABLE -> "Update available"
    WazeBridgeStatus.UPDATE_REQUIRED -> "Update required"
    WazeBridgeStatus.INSTALLED_VERSION_NEWER -> "Installed version is newer"
    WazeBridgeStatus.PACKAGE_CONFLICT -> "Package belongs to another application"
    WazeBridgeStatus.SIGNATURE_MISMATCH -> "Package signature does not match Hush Bridge"
    WazeBridgeStatus.UNKNOWN -> "Unable to verify"
}

private fun WazeBridgeInspection.versionText(versionName: String?, versionCode: Long?): String =
    when {
        versionName.isNullOrBlank() && versionCode == null -> "Unavailable"
        versionName.isNullOrBlank() -> "($versionCode)"
        versionCode == null -> versionName
        else -> "$versionName ($versionCode)"
    }

private fun WazeBridgeInspection.updateMessage(): String = when (status) {
    WazeBridgeStatus.UPDATE_REQUIRED ->
        "The installed ${definition.displayName} is not fully compatible with this version of Hush. " +
            "Play, Pause, Next, Previous, metadata, or playback progress may not work correctly until the bridge is updated."

    else ->
        "Your installed ${definition.displayName} is version ${versionText(installedVersionName, installedVersionCode)}. " +
            "This version of Hush includes ${definition.displayName} " +
            "${versionText(bundledVersionName, bundledVersionCode)}. Updating is recommended for improved Waze controls, " +
            "playback progress synchronization, and compatibility."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WazeIntegrationSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var bridgeInspections by remember { mutableStateOf<List<WazeBridgeInspection>>(emptyList()) }
    var selectedPackageName by rememberSaveable { mutableStateOf<String?>(null) }
    var bridgeEnabled by rememberSaveable { mutableStateOf(false) }
    var pendingBridgePackage by rememberSaveable { mutableStateOf<String?>(null) }
    var updatePromptPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var removeBridgePackage by rememberSaveable { mutableStateOf<String?>(null) }

    fun inspectionFor(definition: WazeBridgeDefinition): WazeBridgeInspection =
        bridgeInspections.firstOrNull { it.definition.packageName == definition.packageName }
            ?: WazeBridgeInspection(
                definition = definition,
                status = WazeBridgeStatus.NOT_INSTALLED,
                requiredProtocolVersion = definition.protocolVersion,
            )

    fun maybeShowUpdatePrompt(inspections: List<WazeBridgeInspection>, selected: String?) {
        scope.launch {
            val candidate = inspections
                .sortedBy { if (it.definition.packageName == selected) 0 else 1 }
                .firstOrNull {
                    it.isInstalled &&
                        (it.status == WazeBridgeStatus.UPDATE_AVAILABLE || it.status == WazeBridgeStatus.UPDATE_REQUIRED)
                } ?: return@launch
            if (!WazeBridgeManager.isUpdateDismissed(context, candidate)) {
                updatePromptPackage = candidate.definition.packageName
            }
        }
    }

    fun refreshBridges(showUpdatePrompt: Boolean = false) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val preferences = context.dataStore.data.first()
                val selected = preferences[WazeIntegrationPackageKey]
                val enabled = preferences[WazeIntegrationEnabledKey] ?: false
                Triple(
                    selected,
                    enabled,
                    WazeBridgeManager.inspectRelevantBridges(context, selected),
                )
            }
            selectedPackageName = result.first
            bridgeEnabled = result.second
            bridgeInspections = result.third
            if (showUpdatePrompt) maybeShowUpdatePrompt(result.third, result.first)
        }
    }

    fun confirmBridgeInstall(packageName: String) {
        val definition = WazeBridgeManager.definitionForPackage(packageName) ?: return
        scope.launch {
            var finalInspection = withContext(Dispatchers.IO) {
                WazeBridgeManager.inspectBridge(context, definition)
            }
            for (attempt in 0 until 10) {
                finalInspection = withContext(Dispatchers.IO) {
                    WazeBridgeManager.inspectBridge(context, definition)
                }
                if (finalInspection.status == WazeBridgeStatus.UP_TO_DATE) break
                delay(500)
            }
            isProcessing = false
            pendingBridgePackage = null
            File(context.cacheDir, definition.assetPath).delete()
            refreshBridges()
            statusMessage = if (finalInspection.status == WazeBridgeStatus.UP_TO_DATE) {
                context.sendBroadcast(
                    Intent("app.hush.music.waze.ACTION_RECONNECT").apply {
                        setPackage(definition.packageName)
                    },
                    "app.hush.music.permission.WAZE_BRIDGE_CONTROL",
                )
                "${definition.displayName} is up to date. Open Waze to reconnect the bridge."
            } else {
                "${definition.displayName} was not updated. ${finalInspection.statusText()}."
            }
        }
    }

    val installLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val packageName = pendingBridgePackage
        if (packageName != null) {
            confirmBridgeInstall(packageName)
        } else {
            isProcessing = false
        }
    }

    fun installOrUpdateBridge(inspection: WazeBridgeInspection) {
        isProcessing = true
        statusMessage = "Preparing ${inspection.definition.displayName}..."
        pendingBridgePackage = inspection.definition.packageName
        scope.launch(Dispatchers.IO) {
            val verifiedInspection = WazeBridgeManager.inspectBridge(context, inspection.definition)
            val apk = WazeBridgeManager.extractInstallableBridge(context, verifiedInspection)
            withContext(Dispatchers.Main) {
                if (apk == null) {
                    isProcessing = false
                    pendingBridgePackage = null
                    statusMessage = when (verifiedInspection.status) {
                        WazeBridgeStatus.PACKAGE_CONFLICT,
                        WazeBridgeStatus.SIGNATURE_MISMATCH,
                        -> "This package identity is currently used by another application. Choose another Waze Bridge."

                        WazeBridgeStatus.UP_TO_DATE -> "${inspection.definition.displayName} is already up to date."
                        WazeBridgeStatus.INSTALLED_VERSION_NEWER -> "The installed bridge version is newer. Hush will not downgrade it."
                        else -> "Unable to verify ${inspection.definition.displayName}."
                    }
                    refreshBridges()
                    return@withContext
                }
                val apkUri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.FileProvider",
                    apk,
                )
                try {
                    installLauncher.launch(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(apkUri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                } catch (error: Exception) {
                    Timber.e(error, "Failed to launch bridge installer")
                    isProcessing = false
                    pendingBridgePackage = null
                    statusMessage = "No installer found on this device."
                }
            }
        }
    }

    fun selectBridge(definition: WazeBridgeDefinition) {
        scope.launch {
            context.dataStore.edit { preferences ->
                preferences[WazeIntegrationPackageKey] = definition.packageName
                preferences[WazeIntegrationEnabledKey] = true
            }
            selectedPackageName = definition.packageName
            bridgeEnabled = true
            refreshBridges(showUpdatePrompt = true)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refreshBridges()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        HushAmbientBackground(
            heightFraction = 0.55f,
            modifier = Modifier.fillMaxSize(),
        )

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
                    Text(
                        text = stringResource(R.string.waze_integration),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Waze Bridge: ${if (bridgeEnabled) "Enabled" else "Disabled"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Active bridge: " + (
                            WazeBridgeManager.definitionForPackage(selectedPackageName)?.displayName ?: "None"
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.waze_integration_pick_app_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            WazeBridgeManager.definitions.forEach { definition ->
                val inspection = inspectionFor(definition)
                val selected = definition.packageName == selectedPackageName
                val statusColor = when (inspection.status) {
                    WazeBridgeStatus.UP_TO_DATE -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    WazeBridgeStatus.UPDATE_AVAILABLE,
                    WazeBridgeStatus.UPDATE_REQUIRED,
                    WazeBridgeStatus.PACKAGE_CONFLICT,
                    WazeBridgeStatus.SIGNATURE_MISMATCH,
                    -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)

                    else -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = statusColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = definition.displayName + if (selected) " (Active)" else "",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            )
                            if (inspection.isInstalled) {
                                Text(
                                    text = "Installed version: ${inspection.versionText(inspection.installedVersionName, inspection.installedVersionCode)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    text = "Bridge protocol: ${inspection.installedProtocolVersion ?: "Unavailable"}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Text(
                                text = "Bundled version: ${inspection.versionText(inspection.bundledVersionName, inspection.bundledVersionCode)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = "Required protocol: ${inspection.requiredProtocolVersion}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = "Status: ${inspection.statusText()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (
                                    inspection.status == WazeBridgeStatus.PACKAGE_CONFLICT ||
                                    inspection.status == WazeBridgeStatus.SIGNATURE_MISMATCH
                                ) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            if (inspection.status == WazeBridgeStatus.PACKAGE_CONFLICT ||
                                inspection.status == WazeBridgeStatus.SIGNATURE_MISMATCH
                            ) {
                                Text(
                                    text = "This package identity is currently used by another application. Hush cannot install or update this bridge. Choose another Waze Bridge.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            if (inspection.status == WazeBridgeStatus.NOT_INSTALLED) {
                                TextButton(
                                    enabled = !isProcessing,
                                    onClick = { installOrUpdateBridge(inspection) },
                                ) {
                                    Text("Install")
                                }
                            }
                            if (inspection.canUpdate) {
                                TextButton(
                                    enabled = !isProcessing,
                                    onClick = { installOrUpdateBridge(inspection) },
                                ) {
                                    Text("Update Bridge")
                                }
                            }
                            if (inspection.isInstalled &&
                                inspection.status != WazeBridgeStatus.PACKAGE_CONFLICT &&
                                inspection.status != WazeBridgeStatus.SIGNATURE_MISMATCH
                            ) {
                                if (!selected) {
                                    TextButton(
                                        enabled = !isProcessing,
                                        onClick = { selectBridge(definition) },
                                    ) {
                                        Text("Use")
                                    }
                                }
                                TextButton(
                                    enabled = !isProcessing,
                                    onClick = { removeBridgePackage = definition.packageName },
                                ) {
                                    Text("Remove", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }

            if (statusMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.info),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(SettingsDimensions.ScreenBottomPadding))
        }

        TopAppBar(
            title = {
                Text(
                    stringResource(R.string.waze_integration),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain,
                ) {
                    Icon(
                        painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            scrollBehavior = scrollBehavior,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
    }

    val updateInspection = updatePromptPackage?.let(WazeBridgeManager::definitionForPackage)?.let(::inspectionFor)
    if (updateInspection != null &&
        (updateInspection.status == WazeBridgeStatus.UPDATE_AVAILABLE ||
            updateInspection.status == WazeBridgeStatus.UPDATE_REQUIRED)
    ) {
        AlertDialog(
            onDismissRequest = { updatePromptPackage = null },
            title = {
                Text(
                    "${updateInspection.definition.displayName} " +
                        if (updateInspection.status == WazeBridgeStatus.UPDATE_REQUIRED) {
                            "update required"
                        } else {
                            "update available"
                        },
                )
            },
            text = { Text(updateInspection.updateMessage()) },
            confirmButton = {
                if (updateInspection.canUpdate) {
                    TextButton(onClick = {
                        updatePromptPackage = null
                        installOrUpdateBridge(updateInspection)
                    }) {
                        Text("Update Bridge")
                    }
                }
            },
            dismissButton = {
                Row {
                    if (updateInspection.status == WazeBridgeStatus.UPDATE_REQUIRED) {
                        TextButton(onClick = {
                            updatePromptPackage = null
                            scope.launch {
                                context.dataStore.edit { preferences ->
                                    preferences[WazeIntegrationEnabledKey] = false
                                }
                                bridgeEnabled = false
                            }
                        }) {
                            Text("Disable Waze Bridge")
                        }
                    }
                    TextButton(onClick = {
                        updatePromptPackage = null
                        scope.launch { WazeBridgeManager.dismissUpdate(context, updateInspection) }
                    }) {
                        Text("Later")
                    }
                }
            },
        )
    }

    val removeDefinition = removeBridgePackage?.let(WazeBridgeManager::definitionForPackage)
    if (removeDefinition != null) {
        AlertDialog(
            onDismissRequest = { removeBridgePackage = null },
            title = { Text("Remove ${removeDefinition.displayName}?") },
            text = { Text("Waze will no longer see Hush through this bridge.") },
            confirmButton = {
                TextButton(onClick = {
                    removeBridgePackage = null
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${removeDefinition.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    } catch (error: Exception) {
                        Timber.e(error, "Failed to open bridge app settings")
                        statusMessage = "Unable to open app settings."
                    }
                }) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { removeBridgePackage = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}
