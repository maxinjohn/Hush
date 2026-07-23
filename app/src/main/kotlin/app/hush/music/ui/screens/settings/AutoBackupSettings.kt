/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.hush.music.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import app.hush.music.LocalPlayerAwareWindowInsets
import app.hush.music.R
import app.hush.music.constants.AutoBackupDayOfWeekKey
import app.hush.music.constants.AutoBackupBeforeUpdateRetentionKey
import app.hush.music.constants.AutoBackupDailyRetentionKey
import app.hush.music.constants.AutoBackupEnabledKey
import app.hush.music.constants.AutoBackupFrequency
import app.hush.music.constants.AutoBackupFrequencyKey
import app.hush.music.constants.AutoBackupHourKey
import app.hush.music.constants.AutoBackupMinuteKey
import app.hush.music.constants.AutoBackupWeeklyRetentionKey
import app.hush.music.constants.EnableBackupBeforeUpdateKey
import app.hush.music.constants.EnableWeeklyAutoBackupKey
import app.hush.music.ui.component.DefaultDialog
import app.hush.music.ui.component.IconButton
import app.hush.music.ui.component.ListPreference
import app.hush.music.ui.component.PreferenceEntry
import app.hush.music.ui.component.PreferenceGroup
import app.hush.music.ui.component.SwitchPreference
import app.hush.music.ui.utils.backToMain
import app.hush.music.ui.utils.formatFileSize
import app.hush.music.utils.AutoBackupHelper
import app.hush.music.utils.AutoBackupRetention
import app.hush.music.utils.AutoBackupType
import app.hush.music.utils.rememberPreference
import app.hush.music.viewmodels.BackupRestoreViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoBackupSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: BackupRestoreViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    val (autoBackupEnabled, onAutoBackupEnabledChange) =
        rememberPreference(
            AutoBackupEnabledKey,
            defaultValue = true,
        )
    val (weeklyBackupEnabled) =
        rememberPreference(
            EnableWeeklyAutoBackupKey,
            defaultValue = false,
        )
    val (backupFrequencyRaw, onBackupFrequencyChange) =
        rememberPreference(
            AutoBackupFrequencyKey,
            defaultValue = AutoBackupFrequency.OFF.name,
        )
    val backupFrequency =
        remember(backupFrequencyRaw, weeklyBackupEnabled) {
            runCatching { AutoBackupFrequency.valueOf(backupFrequencyRaw) }.getOrNull()
                ?: if (weeklyBackupEnabled) AutoBackupFrequency.WEEKLY else AutoBackupFrequency.OFF
        }
    val (backupHour, onBackupHourChange) = rememberPreference(AutoBackupHourKey, defaultValue = 2)
    val (backupMinute, onBackupMinuteChange) = rememberPreference(AutoBackupMinuteKey, defaultValue = 0)
    val (backupDayOfWeek, onBackupDayOfWeekChange) = rememberPreference(AutoBackupDayOfWeekKey, defaultValue = 1)
    val (backupBeforeUpdateEnabled, onBackupBeforeUpdateEnabledChange) =
        rememberPreference(
            EnableBackupBeforeUpdateKey,
            defaultValue = true,
        )
    val (dailyRetention, onDailyRetentionChange) =
        rememberPreference(AutoBackupDailyRetentionKey, defaultValue = AutoBackupRetention.DEFAULT_LIMIT)
    val (weeklyRetention, onWeeklyRetentionChange) =
        rememberPreference(AutoBackupWeeklyRetentionKey, defaultValue = AutoBackupRetention.DEFAULT_LIMIT)
    val (beforeUpdateRetention, onBeforeUpdateRetentionChange) =
        rememberPreference(AutoBackupBeforeUpdateRetentionKey, defaultValue = AutoBackupRetention.DEFAULT_LIMIT)

    var showTimePicker by remember { mutableStateOf(false) }
    var backupsList by remember { mutableStateOf(emptyList<File>()) }
    var backupToDelete by remember { mutableStateOf<File?>(null) }
    var backupToRestore by remember { mutableStateOf<File?>(null) }
    val coroutineScope = rememberCoroutineScope()

    fun reloadBackups() {
        coroutineScope.launch {
            backupsList = withContext(Dispatchers.IO) { AutoBackupHelper.getAutoBackups(context) }
        }
    }

    fun updateRetention(
        backupType: AutoBackupType,
        retainedCount: Int,
        onRetainedCountChange: (Int) -> Unit,
    ) {
        onRetainedCountChange(retainedCount)
        coroutineScope.launch {
            backupsList =
                withContext(Dispatchers.IO) {
                    AutoBackupHelper.pruneBackups(context, backupType, retainedCount)
                    AutoBackupHelper.getAutoBackups(context)
                }
        }
    }

    LaunchedEffect(Unit) {
        reloadBackups()
    }

    LaunchedEffect(autoBackupEnabled, backupFrequency, backupHour, backupMinute, backupDayOfWeek) {
        val effectiveFrequency =
            if (!autoBackupEnabled) {
                AutoBackupFrequency.OFF
            } else {
                backupFrequency
            }
        AutoBackupHelper.updateScheduledBackupWork(
            context = context,
            enabled = autoBackupEnabled,
            frequency = effectiveFrequency,
            hour = backupHour,
            minute = backupMinute,
            dayOfWeek = backupDayOfWeek,
        )
    }

    val formattedBackupTime =
        remember(backupHour, backupMinute) {
            LocalDateTime
                .now()
                .withHour(backupHour.coerceIn(0, 23))
                .withMinute(backupMinute.coerceIn(0, 59))
                .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        }

    val backupDays =
        listOf(
            1 to R.string.backup_schedule_day_monday,
            2 to R.string.backup_schedule_day_tuesday,
            3 to R.string.backup_schedule_day_wednesday,
            4 to R.string.backup_schedule_day_thursday,
            5 to R.string.backup_schedule_day_friday,
            6 to R.string.backup_schedule_day_saturday,
            7 to R.string.backup_schedule_day_sunday,
        )

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                    ),
                )
                .verticalScroll(rememberScrollState())
                .padding(bottom = SettingsDimensions.ScreenBottomPadding),
        ) {
            Spacer(
                Modifier.windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top),
                ),
            )

            Text(
                text = stringResource(R.string.automatic_backup_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )

            PreferenceGroup(title = stringResource(R.string.automatic_backup)) {
                item {
                    SwitchPreference(
                        title = { Text(stringResource(R.string.enable_automatic_backup)) },
                        checked = autoBackupEnabled,
                        onCheckedChange = onAutoBackupEnabledChange,
                    )
                }
            }

            PreferenceGroup(title = stringResource(R.string.options)) {
                item {
                    ListPreference(
                        title = { Text(stringResource(R.string.backup_schedule_frequency)) },
                        description = stringResource(R.string.weekly_backup_desc),
                        selectedValue = backupFrequency,
                        values =
                            listOf(
                                AutoBackupFrequency.OFF,
                                AutoBackupFrequency.DAILY,
                                AutoBackupFrequency.WEEKLY,
                            ),
                        onValueSelected = { selected ->
                            onBackupFrequencyChange(selected.name)
                        },
                        isEnabled = autoBackupEnabled,
                        valueText = {
                            when (it) {
                                AutoBackupFrequency.OFF -> stringResource(R.string.backup_schedule_off)
                                AutoBackupFrequency.DAILY -> stringResource(R.string.backup_schedule_daily)
                                AutoBackupFrequency.WEEKLY -> stringResource(R.string.backup_schedule_weekly)
                            }
                        },
                    )
                }
                item {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.backup_schedule_time)) },
                        description = stringResource(R.string.backup_schedule_time_desc),
                        trailingContent = { Text(formattedBackupTime) },
                        onClick = { showTimePicker = true },
                        isEnabled = autoBackupEnabled && backupFrequency != AutoBackupFrequency.OFF,
                    )
                }
                if (backupFrequency == AutoBackupFrequency.WEEKLY) {
                    item {
                        ListPreference(
                            title = { Text(stringResource(R.string.backup_schedule_day)) },
                            description = stringResource(R.string.backup_schedule_day_desc),
                            selectedValue = backupDayOfWeek,
                            values = backupDays.map { it.first },
                            onValueSelected = onBackupDayOfWeekChange,
                            isEnabled = autoBackupEnabled,
                            valueText = { day ->
                                backupDays.firstOrNull { it.first == day }?.second?.let { stringResource(it) }
                                    ?: day.toString()
                            },
                        )
                    }
                }
                item {
                    SwitchPreference(
                        title = { Text(stringResource(R.string.backup_before_update)) },
                        description = stringResource(R.string.backup_before_update_desc),
                        checked = backupBeforeUpdateEnabled,
                        onCheckedChange = onBackupBeforeUpdateEnabledChange,
                        isEnabled = autoBackupEnabled,
                    )
                }
            }

            PreferenceGroup(title = stringResource(R.string.backup_retention)) {
                when (backupFrequency) {
                    AutoBackupFrequency.DAILY -> {
                        item {
                            ListPreference(
                                title = { Text(stringResource(R.string.backup_retention_daily)) },
                                description = stringResource(R.string.backup_retention_desc),
                                selectedValue = dailyRetention,
                                values = AutoBackupRetention.selectableLimits,
                                onValueSelected = { retainedCount ->
                                    updateRetention(AutoBackupType.DAILY, retainedCount, onDailyRetentionChange)
                                },
                                valueText = { retainedCount ->
                                    pluralStringResource(R.plurals.backup_retention_count, retainedCount, retainedCount)
                                },
                            )
                        }
                    }
                    AutoBackupFrequency.WEEKLY -> {
                        item {
                            ListPreference(
                                title = { Text(stringResource(R.string.backup_retention_weekly)) },
                                description = stringResource(R.string.backup_retention_desc),
                                selectedValue = weeklyRetention,
                                values = AutoBackupRetention.selectableLimits,
                                onValueSelected = { retainedCount ->
                                    updateRetention(AutoBackupType.WEEKLY, retainedCount, onWeeklyRetentionChange)
                                },
                                valueText = { retainedCount ->
                                    pluralStringResource(R.plurals.backup_retention_count, retainedCount, retainedCount)
                                },
                            )
                        }
                    }
                    AutoBackupFrequency.OFF -> {
                        item {
                            PreferenceEntry(
                                title = { Text(stringResource(R.string.backup_retention_disabled)) },
                                description = stringResource(R.string.backup_retention_disabled_desc),
                                isEnabled = false,
                            )
                        }
                    }
                }
                if (backupBeforeUpdateEnabled) {
                    item {
                        ListPreference(
                            title = { Text(stringResource(R.string.backup_retention_before_update)) },
                            description = stringResource(R.string.backup_retention_before_update_desc),
                            selectedValue = beforeUpdateRetention,
                            values = AutoBackupRetention.selectableLimits,
                            onValueSelected = { retainedCount ->
                                updateRetention(AutoBackupType.BEFORE_UPDATE, retainedCount, onBeforeUpdateRetentionChange)
                            },
                            valueText = { retainedCount ->
                                pluralStringResource(R.plurals.backup_retention_count, retainedCount, retainedCount)
                            },
                            isEnabled = autoBackupEnabled,
                        )
                    }
                }
            }

            PreferenceGroup(title = stringResource(R.string.stored_backups)) {
                if (backupsList.isEmpty()) {
                    item {
                        PreferenceEntry(
                            title = { Text(stringResource(R.string.no_stored_backups)) },
                            isEnabled = false,
                        )
                    }
                } else {
                    backupsList.forEach { backupFile ->
                        val (dateStr, typeStr) = parseBackupFilename(backupFile, context)
                        item {
                            PreferenceEntry(
                                title = { Text(dateStr) },
                                description = "$typeStr • ${formatFileSize(backupFile.length())}",
                                onClick = { backupToRestore = backupFile },
                                trailingContent = {
                                    IconButton(
                                        onClick = { backupToDelete = backupFile },
                                        onLongClick = {},
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.delete),
                                            contentDescription = stringResource(R.string.delete),
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        TopAppBar(
            title = { Text(stringResource(R.string.automatic_backup)) },
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
        )
    }

    if (showTimePicker) {
        val timePickerState =
            rememberTimePickerState(
                initialHour = backupHour.coerceIn(0, 23),
                initialMinute = backupMinute.coerceIn(0, 59),
                is24Hour = android.text.format.DateFormat.is24HourFormat(context),
            )
        DefaultDialog(
            onDismiss = { showTimePicker = false },
            title = { Text(stringResource(R.string.backup_schedule_time)) },
            buttons = {
                TextButton(onClick = { showTimePicker = false }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = {
                        onBackupHourChange(timePickerState.hour)
                        onBackupMinuteChange(timePickerState.minute)
                        showTimePicker = false
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        ) {
            TimePicker(state = timePickerState)
        }
    }

    backupToDelete?.let { file ->
        DefaultDialog(
            onDismiss = { backupToDelete = null },
            title = { Text(stringResource(R.string.delete)) },
            buttons = {
                TextButton(onClick = { backupToDelete = null }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = {
                        AutoBackupHelper.deleteBackup(context, file)
                        reloadBackups()
                        backupToDelete = null
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(
                        stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
        ) {
            Text(
                text = stringResource(R.string.delete_backup_confirm),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    backupToRestore?.let { file ->
        DefaultDialog(
            onDismiss = { backupToRestore = null },
            title = { Text(stringResource(R.string.action_restore)) },
            buttons = {
                TextButton(onClick = { backupToRestore = null }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = {
                        viewModel.restoreFromFile(context, file)
                        backupToRestore = null
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.action_restore))
                }
            },
        ) {
            Text(
                text = stringResource(R.string.restore_backup_confirm),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun parseBackupFilename(
    file: File,
    context: Context,
): Pair<String, String> {
    val name = file.name
    val timestampRegex = Regex("""(\d{8}_\d{6})\.backup$""")
    val timestampMatch = timestampRegex.find(name)
    val formattedTime =
        if (timestampMatch != null) {
            val ts = timestampMatch.groupValues[1]
            try {
                val formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                val dateTime = LocalDateTime.parse(ts, formatter)
                val displayFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy, h:mm a")
                dateTime.format(displayFormatter)
            } catch (_: Exception) {
                ts
            }
        } else {
            "Unknown Date"
        }

    val type =
        when {
            name.contains("before_update") -> {
                val startIdx = name.indexOf("before_update_") + "before_update_".length
                val endIdx = name.lastIndexOf('_')
                val versionStr =
                    if (startIdx in 0 until endIdx) {
                        name.substring(startIdx, endIdx)
                    } else {
                        ""
                    }
                if (versionStr.isNotEmpty()) {
                    "${context.getString(R.string.backup_type_before_update)} ($versionStr)"
                } else {
                    context.getString(R.string.backup_type_before_update)
                }
            }
            name.contains("_daily_") -> context.getString(R.string.backup_type_daily)
            else -> context.getString(R.string.backup_type_weekly)
        }

    return Pair(formattedTime, type)
}
