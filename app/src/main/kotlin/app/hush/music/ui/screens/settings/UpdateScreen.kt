/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.hush.music.ui.screens.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import app.hush.music.BuildConfig
import app.hush.music.LocalPlayerAwareWindowInsets
import app.hush.music.R
import app.hush.music.constants.EnableUpdateNotificationKey
import app.hush.music.constants.UpdateChannel
import app.hush.music.constants.UpdateChannelKey
import app.hush.music.defaultUpdateChannel
import app.hush.music.ui.component.BottomSheetPage
import app.hush.music.ui.component.BottomSheetPageState
import app.hush.music.ui.component.IconButton
import app.hush.music.ui.component.MarkdownText
import app.hush.music.ui.theme.HushAmbientBackground
import app.hush.music.ui.utils.backToMain
import app.hush.music.utils.AppUpdateInstaller
import app.hush.music.utils.UpdateNotificationManager
import app.hush.music.utils.Updater
import app.hush.music.utils.rememberEnumPreference
import app.hush.music.utils.rememberPreference
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    onUpToDate: () -> Unit = {},
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()
    val nightlyInstallUrl = remember { Updater.getLatestNightlyDownloadUrl() }
    val experimentalChannelsAvailable = remember { Updater.supportsExperimentalUpdateChannels() }

    val (enableUpdateNotification, onEnableUpdateNotificationChange) =
        rememberPreference(
            EnableUpdateNotificationKey,
            defaultValue = false,
        )
    val (updateChannel, onUpdateChannelChange) =
        rememberEnumPreference(
            UpdateChannelKey,
            defaultValue = defaultUpdateChannel,
        )

    LaunchedEffect(experimentalChannelsAvailable, updateChannel) {
        if (!experimentalChannelsAvailable && updateChannel != UpdateChannel.STABLE) {
            onUpdateChannelChange(UpdateChannel.STABLE)
        }
    }

    var latestVersion by remember { mutableStateOf<String?>(null) }
    var showNightlyChannelConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var showDailyNightlyChannelConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var showEnableUpdateNotificationConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            },
        )
    }
    val isNightlyChannel = experimentalChannelsAvailable && updateChannel == UpdateChannel.NIGHTLY
    val isUpdateAvailable by remember(latestVersion) {
        derivedStateOf {
            BuildConfig.UPDATER_AVAILABLE &&
                (latestVersion?.let { Updater.isUpdateAvailable(it, BuildConfig.VERSION_NAME) } ?: false)
        }
    }
    val updateSheetState = remember { BottomSheetPageState() }
    var updateSheetLoading by remember { mutableStateOf(false) }
    var updateSheetVersion by remember { mutableStateOf<String?>(null) }
    var updateSheetNotes by remember { mutableStateOf<String?>(null) }
    var updateSheetError by remember { mutableStateOf<String?>(null) }
    var updateSheetIsSameVersion by remember { mutableStateOf(false) }
    var showUpdateUpToDateDialog by remember { mutableStateOf(false) }
    var showUpdateErrorDialog by remember { mutableStateOf(false) }
    var updateDownloadProgress by remember { mutableStateOf<Float?>(null) }
    var updateDownloadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var showUpdateDownloadDialog by remember { mutableStateOf(false) }
    val useInAppUpdateInstaller = BuildConfig.DISTRIBUTION in setOf("gms", "foss")
    val snackbarHostState = remember { SnackbarHostState() }

    val openUpdateUrl: (String) -> Unit = { url ->
        try {
            uriHandler.openUri(url)
        } catch (_: Exception) {
        }
    }

    val installUpdate: (String) -> Unit = { url ->
        if (!useInAppUpdateInstaller) {
            openUpdateUrl(url)
        } else if (updateDownloadJob?.isActive != true) {
            updateDownloadProgress = null
            updateSheetError = null
            showUpdateErrorDialog = false
            showUpdateDownloadDialog = true
            updateDownloadJob =
                coroutineScope.launch {
                    AppUpdateInstaller
                        .downloadAndInstall(context, url) { progress ->
                            updateDownloadProgress = progress.fraction
                        }.onSuccess {
                            showUpdateDownloadDialog = false
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.download_complete),
                            )
                        }.onFailure { error ->
                            showUpdateDownloadDialog = false
                            updateSheetError = error.message ?: context.getString(R.string.error_unknown)
                            showUpdateErrorDialog = true
                        }
                }
        }
    }

    val updateSheetContent: @Composable ColumnScope.() -> Unit = {
        Text(
            text = stringResource(R.string.new_update_available),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(top = 16.dp),
        )

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = {},
            contentPadding = PaddingValues(horizontal = 5.dp, vertical = 5.dp),
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(
                text = updateSheetVersion ?: "",
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Spacer(Modifier.height(12.dp))

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
        ) {
            val notes = updateSheetNotes
            if (notes != null && notes.isNotBlank()) {
                MarkdownText(
                    markdown = notes,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(end = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                Text(
                    text = stringResource(R.string.release_notes_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        val downloadUrl =
            when (updateChannel) {
                UpdateChannel.DAILY_NIGHTLY -> Updater.getLatestDailyNightlyDownloadUrl()
                UpdateChannel.NIGHTLY -> Updater.getLatestNightlyDownloadUrl()
                else -> Updater.getLatestDownloadUrl()
            }

        Button(
            onClick = { installUpdate(downloadUrl) },
            modifier = Modifier.fillMaxWidth(),
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(text = stringResource(R.string.update_text))
        }

        Spacer(Modifier.height(12.dp))
    }

    val onCheckForUpdate: () -> Unit = {
        updateSheetLoading = true
        updateSheetVersion = null
        updateSheetNotes = null
        updateSheetError = null
        updateSheetIsSameVersion = false
        showUpdateUpToDateDialog = false
        showUpdateErrorDialog = false

        coroutineScope.launch {
            val versionResult =
                when (updateChannel) {
                    UpdateChannel.DAILY_NIGHTLY -> {
                        Updater.getLatestDailyNightlyReleaseNotes().onSuccess { notes ->
                            updateSheetNotes = notes
                        }
                        Updater.getLatestDailyNightlyVersionName()
                    }

                    else -> {
                        Updater.getLatestReleaseNotes(forceRefresh = true).onSuccess { notes ->
                            updateSheetNotes = notes
                        }
                        Updater.getLatestVersionName(forceRefresh = true)
                    }
                }

            updateSheetLoading = false

            versionResult
                .onSuccess { version ->
                    updateSheetIsSameVersion = !Updater.isUpdateAvailable(version, BuildConfig.VERSION_NAME)
                    updateSheetVersion = version

                    if (updateSheetIsSameVersion) {
                        showUpdateUpToDateDialog = true
                        onUpToDate()
                    } else {
                        updateSheetState.show(updateSheetContent)
                    }
                }.onFailure { e ->
                    updateSheetError = e.message ?: context.getString(R.string.error_unknown)
                    showUpdateErrorDialog = true
                }
        }
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            hasNotificationPermission = isGranted
            if (isGranted) {
                onEnableUpdateNotificationChange(true)
                UpdateNotificationManager.schedulePeriodicUpdateCheck(context)
            }
        }

    if (showEnableUpdateNotificationConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showEnableUpdateNotificationConfirmDialog = false },
            title = { Text(stringResource(R.string.enable_update_notification)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.updates_channel_warning_intro),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.updates_channel_warning_stable_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.updates_channel_warning_stable_source),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = stringResource(R.string.updates_channel_warning_stable_desc),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.updates_channel_warning_nightly_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.updates_nightly_hosting_description),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = stringResource(R.string.updates_channel_warning_nightly_risk),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Text(
                        text = stringResource(R.string.updates_channel_warning_nightly_unstable),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(R.string.updates_channel_warning_acknowledgement),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEnableUpdateNotificationConfirmDialog = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            onEnableUpdateNotificationChange(true)
                            UpdateNotificationManager.schedulePeriodicUpdateCheck(context)
                        }
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEnableUpdateNotificationConfirmDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showNightlyChannelConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showNightlyChannelConfirmDialog = false },
            title = { Text(stringResource(R.string.channel_nightly)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.updates_channel_warning_intro),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.updates_channel_warning_stable_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.updates_channel_warning_stable_source),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = stringResource(R.string.updates_channel_warning_stable_desc),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.updates_channel_warning_nightly_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.updates_nightly_hosting_description),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = stringResource(R.string.updates_channel_warning_nightly_risk),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Text(
                        text = stringResource(R.string.updates_channel_warning_nightly_unstable),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(R.string.updates_channel_warning_acknowledgement),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNightlyChannelConfirmDialog = false
                        onUpdateChannelChange(UpdateChannel.NIGHTLY)
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNightlyChannelConfirmDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showDailyNightlyChannelConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDailyNightlyChannelConfirmDialog = false },
            title = { Text(stringResource(R.string.channel_daily_nightly)) },
            text = {
                Text(
                    text = stringResource(R.string.updates_daily_channel_confirmation),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDailyNightlyChannelConfirmDialog = false
                        onUpdateChannelChange(UpdateChannel.DAILY_NIGHTLY)
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDailyNightlyChannelConfirmDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    LaunchedEffect(updateChannel) {
        if (!BuildConfig.UPDATER_AVAILABLE) {
            return@LaunchedEffect
        }

        val versionResult =
            when (updateChannel) {
                UpdateChannel.DAILY_NIGHTLY -> Updater.getLatestDailyNightlyVersionName()
                else -> Updater.getLatestVersionName()
            }
        versionResult.onSuccess {
            latestVersion = it
            if (!Updater.isUpdateAvailable(it, BuildConfig.VERSION_NAME)) {
                onUpToDate()
            }
        }
    }
    val topBarSubtitle = stringResource(R.string.updates_subtitle_stable)

    Box(modifier = Modifier.fillMaxSize()) {
        HushAmbientBackground(
            heightFraction = 0.55f,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        Scaffold(
        modifier =
            Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.updates),
                        fontWeight = FontWeight.Bold,
                    )
                },
                subtitle = {
                    Text(
                        text = topBarSubtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
                        )
                    }
                },
                actions = {},
                scrollBehavior = scrollBehavior,
                colors =
                    TopAppBarDefaults.largeTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .windowInsetsPadding(
                        LocalPlayerAwareWindowInsets.current.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                        ),
                    ).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                UpdateSummaryCard(
                    currentVersion = BuildConfig.VERSION_NAME,
                    latestVersion = latestVersion,
                    isUpdateAvailable = isUpdateAvailable,
                )
            }

            item {
                UpdateActionPanel(
                    onOpenChangelog = {
                        navController.navigate("settings/changelog?channel=${UpdateChannel.STABLE}")
                    },
                    onCheckForUpdate = onCheckForUpdate,
                )
            }

            item {
                UpdateControlsPanel(
                    enableUpdateNotification = enableUpdateNotification,
                    onUpdateNotificationChange = { enabled ->
                        if (enabled) {
                            showEnableUpdateNotificationConfirmDialog = true
                        } else {
                            onEnableUpdateNotificationChange(false)
                            UpdateNotificationManager.cancelPeriodicUpdateCheck(context)
                        }
                    },
                )
            }

            item {
                AnimatedVisibility(visible = isNightlyChannel) {
                    NightlyInstallPanel(
                        onInstallNightly = { installUpdate(nightlyInstallUrl) },
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(SettingsDimensions.ScreenBottomPadding))
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        BottomSheetPage(
            state = updateSheetState,
            modifier = Modifier.align(Alignment.BottomCenter),
            contentWindowInsets = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom),
        )
    }

    if (updateSheetLoading) {
        AlertDialog(
            onDismissRequest = {},
            icon = {
                LoadingIndicator(
                    modifier = Modifier.size(24.dp),
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.updates_status_checking),
                    style = MaterialTheme.typography.headlineSmall,
                )
            },
            confirmButton = {},
        )
    }

    if (showUpdateDownloadDialog) {
        val progress = updateDownloadProgress
        val animatedProgress by animateFloatAsState(
            targetValue = progress ?: 0f,
            animationSpec = WavyProgressIndicatorDefaults.ProgressAnimationSpec,
            label = "updateDownloadProgress",
        )
        val centeredDialogContentModifier = remember { Modifier.fillMaxWidth() }
        val determinateProgressModifier = remember { Modifier.size(96.dp) }
        val determinateIndicatorModifier = remember { Modifier.fillMaxSize() }
        val indeterminateIndicatorModifier = remember { Modifier.size(72.dp) }

        val downloadTitle = buildString {
            when (updateChannel) {
                UpdateChannel.DAILY_NIGHTLY -> append("${context.getString(R.string.app_name)} Nightly")
                else -> append(context.getString(R.string.app_name))
            }
            append(' ')
            append(updateSheetVersion ?: "?")
        }

        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(
                    text = downloadTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = centeredDialogContentModifier,
                )
            },
            text = {
                Column(
                    modifier = centeredDialogContentModifier,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (progress != null) {
                        Box(
                            modifier = determinateProgressModifier,
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularWavyProgressIndicator(
                                progress = { animatedProgress },
                                modifier = determinateIndicatorModifier,
                            )
                            Text(
                                text =
                                    stringResource(
                                        R.string.download_progress_percent,
                                        (animatedProgress * 100f).roundToInt().coerceIn(0, 100),
                                    ),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    } else {
                        CircularWavyProgressIndicator(
                            modifier = indeterminateIndicatorModifier,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        updateDownloadJob?.cancel()
                        updateDownloadJob = null
                        updateDownloadProgress = null
                        showUpdateDownloadDialog = false
                    },
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showUpdateUpToDateDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateUpToDateDialog = false },
            icon = {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.check),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            },
            title = {
                Text(
                    text = stringResource(R.string.updates_status_current),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            },
            text = {
                Text(
                    text = updateSheetVersion ?: BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            },
            confirmButton = {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    OutlinedButton(
                        onClick = { showUpdateUpToDateDialog = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(android.R.string.ok))
                    }
                }
            },
        )
    }
    }

    if (showUpdateErrorDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateErrorDialog = false },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.error),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.error_loading_changelog),
                    style = MaterialTheme.typography.headlineSmall,
                )
            },
            text = {
                Text(
                    text = updateSheetError ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = { showUpdateErrorDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}

@Composable
private fun UpdateSummaryCard(
    currentVersion: String,
    latestVersion: String?,
    isUpdateAvailable: Boolean,
) {
    val supportingText =
        when {
            latestVersion == null -> stringResource(R.string.updates_status_checking)
            isUpdateAvailable -> stringResource(R.string.latest_version_format, latestVersion)
            else -> stringResource(R.string.updates_status_current)
        }
    val statusContainerColor =
        if (isUpdateAvailable) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }
    val statusContentColor =
        if (isUpdateAvailable) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .widthIn(max = 840.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(modifier = Modifier.padding(top = 2.dp)) {
                    FeatureIcon(
                        iconRes = R.drawable.update,
                        containerColor = statusContainerColor,
                        contentColor = statusContentColor,
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.current_version),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        text = currentVersion,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateActionPanel(
    onOpenChangelog: () -> Unit,
    onCheckForUpdate: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .widthIn(max = 840.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = onOpenChangelog,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.update),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.view_changelog))
            }

            OutlinedButton(
                onClick = onCheckForUpdate,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.sync),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.check_for_update))
            }
        }
    }
}

@Composable
private fun UpdateControlsPanel(
    enableUpdateNotification: Boolean,
    onUpdateNotificationChange: (Boolean) -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .widthIn(max = 840.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        ListItem(
            headlineContent = {
                Text(text = stringResource(R.string.enable_update_notification))
            },
            supportingContent = {
                Text(text = stringResource(R.string.enable_update_notification_desc))
            },
            leadingContent = {
                FeatureIcon(
                    iconRes = R.drawable.new_release,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            },
            trailingContent = {
                Switch(
                    checked = enableUpdateNotification,
                    onCheckedChange = onUpdateNotificationChange,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun NightlyInstallPanel(
    onInstallNightly: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .widthIn(max = 840.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ListItem(
                overlineContent = {
                    Text(text = stringResource(R.string.channel_nightly))
                },
                headlineContent = {
                    Text(
                        text = stringResource(R.string.updates_nightly_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                supportingContent = {
                    Text(text = stringResource(R.string.updates_nightly_description))
                },
                leadingContent = {
                    FeatureIcon(
                        iconRes = R.drawable.download,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )

            OutlinedButton(
                onClick = onInstallNightly,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.download),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.download))
            }
        }
    }
}

@Composable
private fun FeatureIcon(
    @DrawableRes iconRes: Int,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = containerColor,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = contentColor,
            modifier =
                Modifier
                    .padding(12.dp)
                    .size(22.dp),
        )
    }
}
