/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.hush.music.LocalPlayerAwareWindowInsets
import app.hush.music.R
import app.hush.music.constants.ArtistSeparatorsKey
import app.hush.music.constants.AudioNormalizationKey
import app.hush.music.constants.AudioOffload
import app.hush.music.constants.AudioQuality
import app.hush.music.constants.AudioQualityKey

import app.hush.music.constants.AutoDownloadOnLikeKey
import app.hush.music.constants.AutoSkipNextOnErrorKey
import app.hush.music.constants.AutoStartOnBluetoothKey
import app.hush.music.constants.CrossfadeDurationKey
import app.hush.music.constants.CrossfadeEnabledKey
import app.hush.music.constants.CrossfadeGaplessKey
import app.hush.music.constants.DeviceMutePlaybackRecoveryVolumeKey
import app.hush.music.constants.ExternalDownloaderEnabledKey
import app.hush.music.constants.ExternalDownloaderPackageKey
import app.hush.music.constants.HISTORY_DURATION_DEFAULT
import app.hush.music.constants.HistoryDuration
import app.hush.music.constants.LoudnessLevel
import app.hush.music.constants.LoudnessLevelKey
import app.hush.music.constants.LowDataModeKey
import app.hush.music.constants.PauseOnDeviceMuteKey
import app.hush.music.constants.PermanentShuffleKey
import app.hush.music.constants.PersistentQueueKey
import app.hush.music.constants.PlayerStreamClient
import app.hush.music.constants.PlayerStreamClientKey
import app.hush.music.constants.PrefetchCountKey
import app.hush.music.constants.SeekExtraSeconds
import app.hush.music.constants.SkipSilenceKey
import app.hush.music.constants.StopMusicOnTaskClearKey
import app.hush.music.constants.WakelockKey
import app.hush.music.ui.component.ArtistSeparatorsDialog
import app.hush.music.ui.component.CrossfadeSliderPreference
import app.hush.music.ui.component.EnumListPreference
import app.hush.music.ui.component.IconButton
import app.hush.music.ui.component.ListPreference
import app.hush.music.ui.component.NumberPickerPreference
import app.hush.music.ui.component.PreferenceEntry
import app.hush.music.ui.component.PreferenceGroup
import app.hush.music.ui.component.SliderPreference
import app.hush.music.ui.component.SwitchPreference
import app.hush.music.ui.component.TagsManagementDialog
import app.hush.music.ui.component.TextFieldDialog
import androidx.compose.ui.input.nestedscroll.nestedScroll
import app.hush.music.ui.theme.HushAmbientBackground
import app.hush.music.ui.utils.backToMain
import app.hush.music.utils.rememberEnumPreference
import app.hush.music.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val (lowDataMode, onLowDataModeChange) =
        rememberPreference(
            LowDataModeKey,
            defaultValue = false,
        )
    val (persistentQueue, onPersistentQueueChange) =
        rememberPreference(
            PersistentQueueKey,
            defaultValue = true,
        )
    val (permanentShuffle, onPermanentShuffleChange) =
        rememberPreference(
            PermanentShuffleKey,
            defaultValue = false,
        )
    val (skipSilence, onSkipSilenceChange) =
        rememberPreference(
            SkipSilenceKey,
            defaultValue = false,
        )
    val (audioNormalization, onAudioNormalizationChange) =
        rememberPreference(
            AudioNormalizationKey,
            defaultValue = true,
        )
    val (loudnessLevel, onLoudnessLevelChange) =
        rememberEnumPreference(
            LoudnessLevelKey,
            defaultValue = LoudnessLevel.BALANCED,
        )
    val (audioOffload, onAudioOffloadChange) =
        rememberPreference(
            AudioOffload,
            defaultValue = false,
        )

    val (seekExtraSeconds, onSeekExtraSeconds) =
        rememberPreference(
            SeekExtraSeconds,
            defaultValue = false,
        )

    val (autoDownloadOnLike, onAutoDownloadOnLikeChange) =
        rememberPreference(
            AutoDownloadOnLikeKey,
            defaultValue = false,
        )
    val (autoSkipNextOnError, onAutoSkipNextOnErrorChange) =
        rememberPreference(
            AutoSkipNextOnErrorKey,
            defaultValue = false,
        )
    val (pauseOnDeviceMute, onPauseOnDeviceMuteChange) =
        rememberPreference(
            PauseOnDeviceMuteKey,
            defaultValue = false,
        )
    val (
        deviceMutePlaybackRecoveryVolume,
        onDeviceMutePlaybackRecoveryVolumeChange,
    ) =
        rememberPreference(
            DeviceMutePlaybackRecoveryVolumeKey,
            defaultValue = 0,
        )
    val (autoStartOnBluetooth, onAutoStartOnBluetoothChange) =
        rememberPreference(
            AutoStartOnBluetoothKey,
            defaultValue = false,
        )
    val (stopMusicOnTaskClear, onStopMusicOnTaskClearChange) =
        rememberPreference(
            StopMusicOnTaskClearKey,
            defaultValue = false,
        )
    val (historyDuration, onHistoryDurationChange) =
        rememberPreference(
            HistoryDuration,
            defaultValue = HISTORY_DURATION_DEFAULT,
        )

    val (crossfadeEnabled, onCrossfadeEnabledChange) =
        rememberPreference(
            CrossfadeEnabledKey,
            defaultValue = false,
        )
    val (crossfadeDurationSeconds, onCrossfadeDurationSecondsChange) =
        rememberPreference(
            CrossfadeDurationKey,
            defaultValue = 5f,
        )
    val (crossfadeGapless, onCrossfadeGaplessChange) =
        rememberPreference(
            CrossfadeGaplessKey,
            defaultValue = true,
        )
    val (prefetchCount, onPrefetchCountChange) =
        rememberPreference(
            PrefetchCountKey,
            defaultValue = 2,
        )

    val (artistSeparators, onArtistSeparatorsChange) =
        rememberPreference(
            ArtistSeparatorsKey,
            defaultValue = ",;/&",
        )
    val (externalDownloaderEnabled, onExternalDownloaderEnabledChange) =
        rememberPreference(
            ExternalDownloaderEnabledKey,
            defaultValue = false,
        )
    val (externalDownloaderPackage, onExternalDownloaderPackageChange) =
        rememberPreference(
            ExternalDownloaderPackageKey,
            defaultValue = "",
        )

    val (wakelockEnabled, onWakelockChange) =
        rememberPreference(
            WakelockKey,
            defaultValue = false,
        )
    var showArtistSeparatorsDialog by remember { mutableStateOf(false) }
    var showTagsManagementDialog by remember { mutableStateOf(false) }
    var showExternalDownloaderPackageDialog by remember { mutableStateOf(false) }

    if (showArtistSeparatorsDialog) {
        ArtistSeparatorsDialog(
            currentSeparators = artistSeparators,
            onDismiss = { showArtistSeparatorsDialog = false },
            onSave = { newSeparators ->
                onArtistSeparatorsChange(newSeparators)
                showArtistSeparatorsDialog = false
            },
        )
    }

    if (showTagsManagementDialog) {
        TagsManagementDialog(
            onDismiss = { showTagsManagementDialog = false },
        )
    }

    if (showExternalDownloaderPackageDialog) {
        TextFieldDialog(
            initialTextFieldValue =
                androidx.compose.ui.text.input
                    .TextFieldValue(externalDownloaderPackage),
            onDone = { pkg ->
                onExternalDownloaderPackageChange(pkg)
                showExternalDownloaderPackageDialog = false
            },
            onDismiss = { showExternalDownloaderPackageDialog = false },
            singleLine = true,
            maxLines = 1,
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        HushAmbientBackground(
            heightFraction = 0.55f,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .verticalScroll(rememberScrollState())
            .padding(bottom = SettingsDimensions.ScreenBottomPadding),
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Top,
                ),
            ),
        )

        PreferenceGroup(title = stringResource(R.string.audio_source)) {
            item {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.stream_quality)) },
                    description = stringResource(R.string.stream_quality_desc),
                    icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
                    onClick = { navController.navigate("settings/player/stream_quality") },
                )
            }

            item {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.stream_sources_and_client)) },
                    description = stringResource(R.string.stream_sources_and_client_desc),
                    icon = { Icon(painterResource(R.drawable.integration), null) },
                    onClick = { navController.navigate("settings/player/stream_sources") },
                )
            }
        }

        PreferenceGroup(title = stringResource(R.string.playback_settings)) {
            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.low_data_mode_title)) },
                    description = stringResource(R.string.low_data_mode_description),
                    icon = { Icon(painterResource(R.drawable.android_cell), null) },
                    checked = lowDataMode,
                    onCheckedChange = onLowDataModeChange,
                )
            }

            item {
                SliderPreference(
                    title = { Text(stringResource(R.string.history_duration)) },
                    icon = { Icon(painterResource(R.drawable.history), null) },
                    value = historyDuration,
                    onValueChange = onHistoryDurationChange,
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.audio_crossfade_title)) },
                    description = stringResource(R.string.audio_crossfade_description),
                    icon = { Icon(painterResource(R.drawable.animation), null) },
                    checked = crossfadeEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            onAudioOffloadChange(false)
                        }
                        onCrossfadeEnabledChange(enabled)
                    },
                )
            }

            item {
                CrossfadeSliderPreference(
                    valueSeconds = crossfadeDurationSeconds,
                    onValueChange = onCrossfadeDurationSecondsChange,
                    isEnabled = crossfadeEnabled,
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.crossfade_gapless_title)) },
                    description = stringResource(R.string.crossfade_gapless_description),
                    icon = { Icon(painterResource(R.drawable.fast_forward), null) },
                    checked = crossfadeGapless,
                    onCheckedChange = onCrossfadeGaplessChange,
                    isEnabled = crossfadeEnabled,
                )
            }

            item {
                NumberPickerPreference(
                    title = { Text(stringResource(R.string.prefetch_count)) },
                    icon = { Icon(painterResource(R.drawable.download), null) },
                    value = prefetchCount,
                    onValueChange = onPrefetchCountChange,
                    minValue = 0,
                    maxValue = 20,
                    valueText = { if (it == 0) "Off" else "$it songs" },
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.skip_silence)) },
                    icon = { Icon(painterResource(R.drawable.fast_forward), null) },
                    checked = skipSilence,
                    onCheckedChange = onSkipSilenceChange,
                    isEnabled = !audioOffload,
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.audio_normalization)) },
                    icon = { Icon(painterResource(R.drawable.volume_up), null) },
                    checked = audioNormalization,
                    onCheckedChange = onAudioNormalizationChange,
                )
            }

            item(visible = audioNormalization) {
                ListPreference(
                    title = { Text(stringResource(R.string.loudness_level)) },
                    icon = { Icon(painterResource(R.drawable.volume_up), null) },
                    selectedValue = loudnessLevel,
                    values = LoudnessLevel.entries,
                    valueText = {
                        when (it) {
                            LoudnessLevel.AGGRESSIVE -> stringResource(R.string.loudness_level_aggressive)
                            LoudnessLevel.LOUD -> stringResource(R.string.loudness_level_loud)
                            LoudnessLevel.BALANCED -> stringResource(R.string.loudness_level_balanced)
                            LoudnessLevel.QUIET -> stringResource(R.string.loudness_level_quiet)
                        }
                    },
                    onValueSelected = onLoudnessLevelChange,
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.audio_offload)) },
                    description = stringResource(R.string.audio_offload_desc),
                    icon = { Icon(painterResource(R.drawable.speed), null) },
                    checked = audioOffload,
                    onCheckedChange = { enabled ->
                        onAudioOffloadChange(enabled)
                        if (enabled) {
                            onSkipSilenceChange(false)
                            onCrossfadeEnabledChange(false)
                        }
                    },
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.seek_seconds_addup)) },
                    description = stringResource(R.string.seek_seconds_addup_description),
                    icon = { Icon(painterResource(R.drawable.arrow_forward), null) },
                    checked = seekExtraSeconds,
                    onCheckedChange = onSeekExtraSeconds,
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.pause_on_device_mute)) },
                    description = stringResource(R.string.pause_on_device_mute_desc),
                    icon = { Icon(painterResource(R.drawable.volume_off), null) },
                    checked = pauseOnDeviceMute,
                    onCheckedChange = onPauseOnDeviceMuteChange,
                )
            }

            item(visible = pauseOnDeviceMute) {
                val context = LocalContext.current
                val disabledLabel = stringResource(R.string.device_mute_recovery_volume_disabled)
                val recoveryVolumeText =
                    remember(context, disabledLabel) {
                        { value: Int ->
                            if (value == 0) {
                                disabledLabel
                            } else {
                                context.getString(R.string.percentage_format, value)
                            }
                        }
                    }
                NumberPickerPreference(
                    title = { Text(stringResource(R.string.device_mute_recovery_volume)) },
                    icon = { Icon(painterResource(R.drawable.volume_up), null) },
                    value = deviceMutePlaybackRecoveryVolume,
                    onValueChange = onDeviceMutePlaybackRecoveryVolumeChange,
                    minValue = 0,
                    maxValue = 100,
                    valueText = recoveryVolumeText,
                    isEnabled = pauseOnDeviceMute,
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.auto_start_on_bluetooth)) },
                    description = stringResource(R.string.auto_start_on_bluetooth_desc),
                    icon = { Icon(painterResource(R.drawable.bluetooth), null) },
                    checked = autoStartOnBluetooth,
                    onCheckedChange = onAutoStartOnBluetoothChange,
                )
            }
        }

        PreferenceGroup(title = stringResource(R.string.queue)) {
            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.persistent_queue)) },
                    description = stringResource(R.string.persistent_queue_desc),
                    icon = { Icon(painterResource(R.drawable.queue_music), null) },
                    checked = persistentQueue,
                    onCheckedChange = onPersistentQueueChange,
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.permanent_shuffle)) },
                    description = stringResource(R.string.permanent_shuffle_desc),
                    icon = { Icon(painterResource(R.drawable.shuffle), null) },
                    checked = permanentShuffle,
                    onCheckedChange = onPermanentShuffleChange,
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.auto_download_on_like)) },
                    description = stringResource(R.string.auto_download_on_like_desc),
                    icon = { Icon(painterResource(R.drawable.download), null) },
                    checked = autoDownloadOnLike,
                    onCheckedChange = onAutoDownloadOnLikeChange,
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.auto_skip_next_on_error)) },
                    description = stringResource(R.string.auto_skip_next_on_error_desc),
                    icon = { Icon(painterResource(R.drawable.skip_next), null) },
                    checked = autoSkipNextOnError,
                    onCheckedChange = onAutoSkipNextOnErrorChange,
                )
            }
        }

        PreferenceGroup(title = stringResource(R.string.misc)) {
            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.stop_music_on_task_clear)) },
                    icon = { Icon(painterResource(R.drawable.clear_all), null) },
                    checked = stopMusicOnTaskClear,
                    onCheckedChange = onStopMusicOnTaskClearChange,
                )
            }

            item {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.alarm)) },
                    description = stringResource(R.string.alarm_playlist_helper),
                    icon = { Icon(painterResource(R.drawable.bedtime), null) },
                    onClick = { navController.navigate("settings/alarm") },
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.wakelock)) },
                    description = stringResource(R.string.wakelock_desc),
                    icon = { Icon(painterResource(R.drawable.bolt), null) },
                    checked = wakelockEnabled,
                    onCheckedChange = onWakelockChange,
                )
            }

            item {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.artist_separators)) },
                    description = artistSeparators.map { "\"$it\"" }.joinToString("  "),
                    icon = { Icon(painterResource(R.drawable.artist), null) },
                    onClick = { showArtistSeparatorsDialog = true },
                )
            }

            item {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.manage_playlist_tags)) },
                    description = stringResource(R.string.manage_playlist_tags_desc),
                    icon = { Icon(painterResource(R.drawable.style), null) },
                    onClick = { showTagsManagementDialog = true },
                )
            }

            item {
                SwitchPreference(
                    title = { Text(stringResource(R.string.external_downloader)) },
                    description = stringResource(R.string.external_downloader_desc),
                    icon = { Icon(painterResource(R.drawable.download), null) },
                    checked = externalDownloaderEnabled,
                    onCheckedChange = onExternalDownloaderEnabledChange,
                )
            }

            item {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.external_downloader_package)) },
                    description = externalDownloaderPackage.ifEmpty { stringResource(R.string.external_downloader_package_desc) },
                    icon = { Icon(painterResource(R.drawable.integration), null) },
                    onClick = { showExternalDownloaderPackageDialog = true },
                    isEnabled = externalDownloaderEnabled,
                )
            }
        }
    }

        TopAppBar(
            modifier = Modifier.align(Alignment.TopCenter),
            title = { Text(stringResource(R.string.player_and_audio)) },
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
            scrollBehavior = scrollBehavior,
            colors =
                TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
        )
    }
}
