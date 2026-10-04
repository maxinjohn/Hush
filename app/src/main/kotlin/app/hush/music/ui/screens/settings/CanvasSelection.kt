/*
 * Hush — ported from Vivi Music v6.0.3 (GPL-3.0)
 * Consolidated canvas settings ported from ArchiveTune v15.1.0 CanvasSettings.kt (GPL-3.0)
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package app.hush.music.ui.screens.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.hush.music.LocalPlayerAwareWindowInsets
import app.hush.music.R
import app.hush.music.canvas.CanvasHealth
import app.hush.music.canvas.CanvasHealthInputs
import app.hush.music.canvas.CanvasHealthStatus
import app.hush.music.canvas.SpotifyCanvasSession
import app.hush.music.canvas.checkCanvasHealth
import app.hush.music.canvas.pendingCanvasHealth
import app.hush.music.constants.CanvasSource
import app.hush.music.constants.CanvasSourceKey
import app.hush.music.constants.CanvasWifiOnlyKey
import app.hush.music.constants.HushCanvasKey
import app.hush.music.constants.LowDataModeKey
import app.hush.music.constants.MaxCanvasCacheSizeKey
import app.hush.music.ui.component.ActionPromptDialog
import app.hush.music.ui.component.IconButton
import app.hush.music.ui.component.ListPreference
import app.hush.music.ui.component.PreferenceEntry
import app.hush.music.ui.component.PreferenceGroup
import app.hush.music.ui.component.SwitchPreference
import app.hush.music.ui.player.CanvasArtworkPlaybackCache
import app.hush.music.ui.player.CanvasNetworkPolicy
import app.hush.music.ui.utils.backToMain
import app.hush.music.ui.utils.formatFileSize
import app.hush.music.utils.rememberEnumPreference
import app.hush.music.utils.rememberNetworkConnectivity
import app.hush.music.utils.rememberPreference
import app.hush.music.viewmodels.StorageSettingsViewModel

@Composable
fun CanvasSelection(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: StorageSettingsViewModel = hiltViewModel(),
) {
    val (hushCanvasEnabled, onHushCanvasEnabledChange) =
        rememberPreference(HushCanvasKey, defaultValue = false)
    val (canvasSource, onCanvasSourceChange) =
        rememberEnumPreference(CanvasSourceKey, defaultValue = CanvasSource.AUTO)
    val (canvasWifiOnly, onCanvasWifiOnlyChange) =
        rememberPreference(CanvasWifiOnlyKey, defaultValue = false)
    val (maxCanvasCacheSize, onMaxCanvasCacheSizeChange) =
        rememberPreference(MaxCanvasCacheSizeKey, defaultValue = 256)
    val (lowDataModeEnabled) = rememberPreference(LowDataModeKey, defaultValue = false)
    val connectivity = rememberNetworkConnectivity()

    var spotifyConnected by remember { mutableStateOf(false) }
    var canvasCacheBytes by remember { mutableStateOf(0L) }
    var health by remember { mutableStateOf(CanvasHealthStatus()) }
    var healthChecking by remember { mutableStateOf(false) }
    var clearCanvasCacheDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val healthInputs =
        CanvasHealthInputs(
            enabled = hushCanvasEnabled,
            source = canvasSource,
            wifiOnly = canvasWifiOnly,
            lowDataMode = lowDataModeEnabled,
            connectivity = connectivity,
            spotifyConnected = spotifyConnected,
        )

    suspend fun refreshHealth() {
        healthChecking = true
        try {
            health = pendingCanvasHealth(healthInputs)
            health = checkCanvasHealth(healthInputs)
        } finally {
            healthChecking = false
        }
    }

    // Push the Wi-Fi-only policy so canvas video downloads stop when it refuses the network.
    LaunchedEffect(canvasWifiOnly, connectivity) {
        CanvasNetworkPolicy.update(
            wifiOnly = canvasWifiOnly,
            wifiActive = connectivity.wifi,
        )
        if (!CanvasNetworkPolicy.allowed) {
            CanvasArtworkPlaybackCache.cancelDownloads()
        }
    }

    LaunchedEffect(maxCanvasCacheSize) {
        CanvasArtworkPlaybackCache.setMaxSize(maxCanvasCacheSize)
        if (maxCanvasCacheSize == 0) {
            viewModel.clearCanvasCache(showFeedback = false)
        }
    }

    LaunchedEffect(Unit) {
        spotifyConnected = SpotifyCanvasSession.isConnected()
        while (isActive) {
            canvasCacheBytes =
                withContext(Dispatchers.IO) {
                    CanvasArtworkPlaybackCache.byteSize()
                }
            delay(CanvasCacheRefreshIntervalMillis)
        }
    }

    LaunchedEffect(healthInputs) {
        refreshHealth()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.canvas),
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
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                .verticalScroll(rememberScrollState())
                .padding(bottom = SettingsDimensions.ScreenBottomPadding),
        ) {
            PreferenceGroup(title = stringResource(R.string.general)) {
                item {
                    SwitchPreference(
                        title = { Text(stringResource(R.string.hush_canvas)) },
                        description = stringResource(R.string.hush_canvas_desc),
                        icon = { Icon(painterResource(R.drawable.motion_photos_on), null) },
                        checked = hushCanvasEnabled,
                        onCheckedChange = onHushCanvasEnabledChange,
                    )
                }

                item {
                    SwitchPreference(
                        title = { Text(stringResource(R.string.canvas_wifi_only)) },
                        description = stringResource(R.string.canvas_wifi_only_desc),
                        icon = { Icon(painterResource(R.drawable.wifi_proxy), null) },
                        checked = canvasWifiOnly,
                        onCheckedChange = onCanvasWifiOnlyChange,
                    )
                }
            }

            if (!hushCanvasEnabled) {
                Text(
                    text = stringResource(R.string.canvas_source_enable_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier.padding(
                            horizontal = 16.dp,
                            vertical = 4.dp,
                        ),
                )
            }

            PreferenceGroup(title = stringResource(R.string.canvas_source)) {
                item {
                    CanvasSourceOption(
                        title = stringResource(R.string.canvas_source_auto),
                        description = stringResource(R.string.canvas_source_auto_desc),
                        selected = canvasSource == CanvasSource.AUTO,
                        onClick = { onCanvasSourceChange(CanvasSource.AUTO) },
                    )
                }

                item {
                    CanvasSourceOption(
                        title = stringResource(R.string.canvas_source_apple_music),
                        description = stringResource(R.string.canvas_source_apple_music_desc),
                        selected = canvasSource == CanvasSource.APPLE_MUSIC,
                        onClick = { onCanvasSourceChange(CanvasSource.APPLE_MUSIC) },
                    )
                }

                item {
                    CanvasSourceOption(
                        title = stringResource(R.string.canvas_source_hush_canvas),
                        description = stringResource(R.string.canvas_source_hush_canvas_desc),
                        selected = canvasSource == CanvasSource.HUSH_CANVAS,
                        onClick = { onCanvasSourceChange(CanvasSource.HUSH_CANVAS) },
                    )
                }

                item {
                    CanvasSourceOption(
                        title = stringResource(R.string.canvas_source_tidal),
                        description = stringResource(R.string.canvas_source_tidal_desc),
                        selected = canvasSource == CanvasSource.TIDAL,
                        onClick = { onCanvasSourceChange(CanvasSource.TIDAL) },
                    )
                }

                item {
                    CanvasSourceOption(
                        title = stringResource(R.string.canvas_source_spotify),
                        description = stringResource(R.string.canvas_source_spotify_desc),
                        selected = canvasSource == CanvasSource.SPOTIFY,
                        onClick = { onCanvasSourceChange(CanvasSource.SPOTIFY) },
                    )
                }
            }

            PreferenceGroup(title = stringResource(R.string.canvas_provider_health)) {
                item {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.refresh)) },
                        icon = { Icon(painterResource(R.drawable.sync), null) },
                        trailingContent = {
                            if (healthChecking) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        },
                        onClick = {
                            coroutineScope.launch {
                                refreshHealth()
                            }
                        },
                        isEnabled = !healthChecking,
                    )
                }

                item {
                    canvasHealthPreference(
                        title = stringResource(R.string.canvas_source_hush_canvas),
                        health = health.hush,
                        iconRes = R.drawable.motion_photos_on,
                    )
                }
                item {
                    canvasHealthPreference(
                        title = stringResource(R.string.canvas_source_apple_music),
                        health = health.appleMusic,
                        iconRes = R.drawable.music_note,
                    )
                }
                item {
                    canvasHealthPreference(
                        title = stringResource(R.string.canvas_source_tidal),
                        health = health.tidal,
                        iconRes = R.drawable.music_note,
                    )
                }
                item {
                    canvasHealthPreference(
                        title = stringResource(R.string.canvas_source_spotify),
                        health = health.spotify,
                        iconRes = R.drawable.spotify_icon,
                    )
                }
            }

            val maxCanvasCacheSizeBytes =
                if (maxCanvasCacheSize > 0) {
                    cacheSizeMegabytesToBytes(maxCanvasCacheSize)
                } else {
                    0L
                }
            val canvasCacheProgress by animateFloatAsState(
                targetValue =
                    if (maxCanvasCacheSizeBytes > 0) {
                        (canvasCacheBytes.toFloat() / maxCanvasCacheSizeBytes).coerceIn(0f, 1f)
                    } else {
                        0f
                    },
                label = "canvasCacheProgress",
            )

            PreferenceGroup(title = stringResource(R.string.canvas_cache)) {
                item {
                    ListPreference(
                        title = { Text(stringResource(R.string.max_cache_size)) },
                        description =
                            when {
                                maxCanvasCacheSize < 0 -> {
                                    stringResource(R.string.size_used, formatFileSize(canvasCacheBytes))
                                }

                                maxCanvasCacheSize > 0 -> {
                                    stringResource(
                                        R.string.storage_size_ratio,
                                        formatFileSize(canvasCacheBytes),
                                        formatFileSize(cacheSizeMegabytesToBytes(maxCanvasCacheSize)),
                                    )
                                }

                                else -> {
                                    stringResource(R.string.disable)
                                }
                            },
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.motion_photos_on),
                                contentDescription = null,
                            )
                        },
                        selectedValue = maxCanvasCacheSize,
                        values = CanvasCacheSizeValues,
                        valueText = {
                            when (it) {
                                0 -> stringResource(R.string.disable)
                                -1 -> stringResource(R.string.unlimited)
                                else -> formatFileSize(cacheSizeMegabytesToBytes(it))
                            }
                        },
                        onValueSelected = onMaxCanvasCacheSizeChange,
                    )
                }
                item(visible = maxCanvasCacheSize > 0) {
                    CanvasCacheUsagePreference(progress = canvasCacheProgress, usedBytes = canvasCacheBytes)
                }
                item {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.clear_canvas_cache)) },
                        onClick = { clearCanvasCacheDialog = true },
                    )
                }
            }
        }

        if (clearCanvasCacheDialog) {
            ActionPromptDialog(
                title = stringResource(R.string.clear_canvas_cache),
                onDismiss = { clearCanvasCacheDialog = false },
                onConfirm = {
                    // No feedback flow on this screen: skipping it keeps the clear out of the
                    // storage snackbar pipeline, and the usage bar below drains on next poll.
                    viewModel.clearCanvasCache(showFeedback = false)
                    clearCanvasCacheDialog = false
                },
                onCancel = { clearCanvasCacheDialog = false },
                content = {
                    Text(text = stringResource(R.string.clear_canvas_cache_dialog))
                },
            )
        }
    }
}

@Composable
private fun CanvasSourceOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val radioColors =
        RadioButtonDefaults.colors(
            selectedColor = MaterialTheme.colorScheme.primary,
            unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )

    PreferenceEntry(
        title = {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        description = description,
        icon = {
            Icon(
                painter = painterResource(R.drawable.motion_photos_on),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = radioColors,
            )
        },
        onClick = onClick,
    )
}

@Composable
private fun canvasHealthPreference(
    title: String,
    health: CanvasHealth,
    iconRes: Int,
) {
    PreferenceEntry(
        title = { Text(title) },
        description = health.label(),
        icon = { Icon(painterResource(iconRes), null) },
        trailingContent = {
            if (health == CanvasHealth.CHECKING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            }
        },
    )
}

@Composable
private fun CanvasHealth.label(): String =
    when (this) {
        CanvasHealth.NOT_CHECKED -> stringResource(R.string.canvas_health_not_checked)
        CanvasHealth.CHECKING -> stringResource(R.string.canvas_health_checking)
        CanvasHealth.AVAILABLE -> stringResource(R.string.canvas_health_available)
        CanvasHealth.UNAVAILABLE -> stringResource(R.string.canvas_health_unavailable)
        CanvasHealth.NOT_SELECTED -> stringResource(R.string.canvas_health_not_selected)
        CanvasHealth.NOT_CONNECTED -> stringResource(R.string.spotify_not_connected)
        CanvasHealth.DISABLED -> stringResource(R.string.canvas_health_disabled)
        CanvasHealth.OFFLINE -> stringResource(R.string.canvas_health_offline)
        CanvasHealth.WIFI_REQUIRED -> stringResource(R.string.canvas_health_wifi_required)
        CanvasHealth.LOW_DATA_MODE -> stringResource(R.string.canvas_health_low_data)
    }

@Composable
private fun CanvasCacheUsagePreference(
    progress: Float,
    usedBytes: Long,
) {
    PreferenceEntry(
        title = {
            Text(stringResource(R.string.size_used, formatFileSize(usedBytes)))
        },
        content = {
            Spacer(Modifier.padding(top = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearWavyProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        },
    )
}

private val CanvasCacheSizeValues = listOf(0, 64, 128, 256, 512, 1024, 2048, 4096, 8192, -1)

private const val CanvasCacheRefreshIntervalMillis = 2_000L

private fun cacheSizeMegabytesToBytes(sizeMegabytes: Int): Long =
    sizeMegabytes.toLong().coerceAtLeast(0L) * 1024L * 1024L
