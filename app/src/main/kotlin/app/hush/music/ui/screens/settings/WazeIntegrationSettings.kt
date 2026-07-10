package app.hush.music.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
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
import androidx.compose.runtime.mutableIntStateOf
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
import app.hush.music.constants.WazeTargetApp
import app.hush.music.ui.component.IconButton
import app.hush.music.ui.theme.HushAmbientBackground
import app.hush.music.ui.utils.backToMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.zip.ZipFile

private fun getWazeAppLabel(context: android.content.Context, app: WazeTargetApp): String = when (app) {
    WazeTargetApp.SPOTIFY -> context.getString(R.string.waze_integration_spotify)
    WazeTargetApp.YOUTUBE_MUSIC -> context.getString(R.string.waze_integration_youtube_music)
}

private fun getRealAppName(app: WazeTargetApp): String = when (app) {
    WazeTargetApp.SPOTIFY -> "Spotify"
    WazeTargetApp.YOUTUBE_MUSIC -> "YouTube Music"
}

private fun isAppInstalled(context: android.content.Context, packageName: String): Boolean {
    return try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

private fun isOurShim(context: android.content.Context, packageName: String): Boolean {
    if (!isAppInstalled(context, packageName)) return false
    return try {
        val appInfo = context.packageManager.getApplicationInfo(
            packageName,
            PackageManager.GET_META_DATA,
        )
        appInfo.metaData?.getBoolean("app.hush.music.waze.SHIM", false) == true ||
            appInfo.loadLabel(context.packageManager).toString() == getLegacyShimLabel(packageName)
    } catch (_: Exception) {
        false
    }
}

private fun getLegacyShimLabel(packageName: String): String = when (packageName) {
    WazeTargetApp.SPOTIFY.packageName -> "Hush (Spotify)"
    WazeTargetApp.YOUTUBE_MUSIC.packageName -> "Hush (YouTube Music)"
    else -> ""
}

private fun isRealAppInstalled(context: android.content.Context, packageName: String): Boolean {
    if (!isAppInstalled(context, packageName)) return false
    return !isOurShim(context, packageName)
}

private fun getShimApkBytes(context: android.content.Context, flavorName: String): ByteArray? {
    return try {
        val zipFile = File(context.cacheDir, "waze-shims.zip")
        context.assets.open("waze-shims.zip").use { input ->
            zipFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        val targetEntry = "waze-shim-$flavorName-release.apk"
        ZipFile(zipFile).use { zip ->
            val entry = zip.getEntry(targetEntry) ?: return null
            zip.getInputStream(entry).use { apkInput ->
                apkInput.readBytes()
            }
        }
    } catch (e: Exception) {
        Timber.e(e, "Failed to extract companion APK from zip")
        null
    }
}

private fun getFlavorName(app: WazeTargetApp): String = when (app) {
    WazeTargetApp.SPOTIFY -> "spotify"
    WazeTargetApp.YOUTUBE_MUSIC -> "youtubeMusic"
}

object ShimInstallCallback {
    var onResult: ((Boolean) -> Unit)? = null
}

class ShimInstallReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: Intent) {
        val status = intent.getIntExtra(
            android.content.pm.PackageInstaller.EXTRA_STATUS,
            -1,
        )
        ShimInstallCallback.onResult?.invoke(status == android.content.pm.PackageInstaller.STATUS_SUCCESS)
        ShimInstallCallback.onResult = null
    }
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
    var showUninstallConfirm by remember { mutableStateOf(false) }
    var uninstallTargetLabel by remember { mutableStateOf("") }
    var uninstallTargetPackage by remember { mutableStateOf("") }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    fun refreshNow() { refreshTrigger++ }

    fun checkShimState(app: WazeTargetApp, expectedInstalled: Boolean, onResult: (Boolean) -> Unit) {
        if (isOurShim(context, app.packageName) == expectedInstalled) {
            refreshNow()
            onResult(true)
            return
        }
        scope.launch {
            repeat(5) {
                delay(300)
                if (isOurShim(context, app.packageName) == expectedInstalled) {
                    refreshNow()
                    onResult(true)
                    return@launch
                }
            }
            refreshNow()
            onResult(false)
        }
    }

    fun installedShimPackages(): Set<String> {
        return WazeTargetApp.entries
            .filter { isOurShim(context, it.packageName) }
            .map { it.packageName }
            .toSet()
    }

    var pendingUninstallApp by rememberSaveable { mutableStateOf<WazeTargetApp?>(null) }
    val uninstallLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val app = pendingUninstallApp
        pendingUninstallApp = null
        if (app != null) {
            checkShimState(app, expectedInstalled = false) { removed ->
                isProcessing = false
                statusMessage = if (removed) {
                    context.getString(R.string.waze_integration_uninstalled)
                } else {
                    "Uninstall cancelled."
                }
            }
        } else {
            isProcessing = false
        }
    }

    fun doUninstall(targetApp: WazeTargetApp) {
        isProcessing = true
        statusMessage = "${context.getString(R.string.waze_integration_uninstalling)} ${getWazeAppLabel(context, targetApp).lowercase()}"
        pendingUninstallApp = targetApp

        try {
            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
                data = Uri.parse("package:${targetApp.packageName}")
            }
            uninstallLauncher.launch(intent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to launch package uninstaller")
            isProcessing = false
            pendingUninstallApp = null
            statusMessage = "Unable to open the package uninstaller."
        }
    }

    fun doInstall(targetApp: WazeTargetApp) {
        if (
            android.os.Build.VERSION.SDK_INT >= 33 &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            statusMessage = "Enable 'Install unknown apps' permission for Hush in Settings."
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        }
        isProcessing = true
        statusMessage = "${context.getString(R.string.waze_integration_installing)} ${getWazeAppLabel(context, targetApp).lowercase()}"
        scope.launch(Dispatchers.IO) {
            val apkBytes = getShimApkBytes(context, getFlavorName(targetApp))
            withContext(Dispatchers.Main) {
                if (apkBytes != null) {
                    try {
                        val params = PackageInstaller.SessionParams(
                            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
                        )
                        params.setAppPackageName(targetApp.packageName)
                        val sessionId = context.packageManager.packageInstaller.createSession(params)
                        val session = context.packageManager.packageInstaller.openSession(sessionId)
                        session.openWrite("shim", 0, apkBytes.size.toLong()).use { out ->
                            out.write(apkBytes)
                        }
                        val pendingIntent = android.app.PendingIntent.getBroadcast(
                            context,
                            sessionId,
                            Intent(context, ShimInstallReceiver::class.java),
                            android.app.PendingIntent.FLAG_IMMUTABLE or
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                        )
                        ShimInstallCallback.onResult = { success ->
                            isProcessing = false
                            if (success) {
                                checkShimState(targetApp, expectedInstalled = true) {}
                                statusMessage = context.getString(R.string.waze_integration_installed)
                            } else {
                                statusMessage = "Install failed."
                            }
                        }
                        session.commit(pendingIntent.intentSender)
                    } catch (e: SecurityException) {
                        Timber.e(e, "Missing install permission")
                        isProcessing = false
                        statusMessage = "Enable 'Install unknown apps' for Hush in Settings."
                        val intent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Timber.e(e, "PackageInstaller failed")
                        isProcessing = false
                        statusMessage = "Install failed: ${e.message}"
                    }
                } else {
                    isProcessing = false
                    statusMessage = "Companion APK not found. Rebuild the app."
                }
            }
        }
    }

    val installedPackages = remember(refreshTrigger) { installedShimPackages() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refreshTrigger++
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
                ).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(TopAppBarDefaults.TopAppBarExpandedHeight + 24.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                ) {
                    Text(
                        text = stringResource(R.string.waze_integration),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.waze_integration_pick_app_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            WazeTargetApp.entries.forEach { app ->
                val appLabel = remember(app, refreshTrigger) { getWazeAppLabel(context, app) }
                val realAppName = remember(app) { getRealAppName(app) }
                val isInstalled = remember(app, refreshTrigger) { app.packageName in installedPackages }
                val realAppInstalled = remember(app, refreshTrigger) { isRealAppInstalled(context, app.packageName) }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isInstalled) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
                    },
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = appLabel,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isInstalled) FontWeight.Medium else FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                if (isInstalled) {
                                    Text(
                                        text = "  \u2022 Active",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                            if (realAppInstalled) {
                                Text(
                                    text = "$realAppName is installed. Uninstall it first to use this Bridge.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }

                        if (isInstalled) {
                            TextButton(
                                onClick = {
                                    uninstallTargetLabel = appLabel
                                    uninstallTargetPackage = app.packageName
                                    showUninstallConfirm = true
                                },
                                enabled = !isProcessing,
                            ) {
                                Text(
                                    "Remove",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        } else if (realAppInstalled && !isProcessing) {
                            TextButton(
                                onClick = {
                                    try {
                                        context.startActivity(
                                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = Uri.parse("package:${app.packageName}")
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        )
                                    } catch (_: Exception) {}
                                },
                            ) {
                                Text("Uninstall")
                            }
                        } else if (!isProcessing) {
                            TextButton(
                                onClick = { doInstall(app) },
                            ) {
                                Text("Install")
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

    if (showUninstallConfirm) {
        AlertDialog(
            onDismissRequest = { showUninstallConfirm = false },
            title = { Text(stringResource(R.string.waze_integration)) },
            text = { Text("Remove the $uninstallTargetLabel bridge? Waze will no longer see Hush via this app.") },
            confirmButton = {
                TextButton(onClick = {
                    showUninstallConfirm = false
                    if (!isProcessing && uninstallTargetPackage.isNotEmpty()) {
                        WazeTargetApp.entries
                            .firstOrNull { it.packageName == uninstallTargetPackage }
                            ?.let(::doUninstall)
                    }
                }) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}
