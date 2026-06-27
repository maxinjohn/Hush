/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.screens.settings

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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AutoBackupEnabledKey
import moe.rukamori.archivetune.constants.EnableBackupBeforeUpdateKey
import moe.rukamori.archivetune.constants.EnableWeeklyAutoBackupKey
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.ui.utils.formatFileSize
import moe.rukamori.archivetune.utils.AutoBackupHelper
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.viewmodels.BackupRestoreViewModel
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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
    val (weeklyBackupEnabled, onWeeklyBackupEnabledChange) =
        rememberPreference(
            EnableWeeklyAutoBackupKey,
            defaultValue = false,
        )
    val (backupBeforeUpdateEnabled, onBackupBeforeUpdateEnabledChange) =
        rememberPreference(
            EnableBackupBeforeUpdateKey,
            defaultValue = true,
        )

    var backupsList by remember { mutableStateOf(emptyList<File>()) }
    var backupToDelete by remember { mutableStateOf<File?>(null) }
    var backupToRestore by remember { mutableStateOf<File?>(null) }

    fun reloadBackups() {
        backupsList = AutoBackupHelper.getAutoBackups(context)
    }

    LaunchedEffect(Unit) {
        reloadBackups()
    }

    LaunchedEffect(autoBackupEnabled, weeklyBackupEnabled) {
        AutoBackupHelper.updateWeeklyBackupWork(context, autoBackupEnabled && weeklyBackupEnabled)
    }

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
                    SwitchPreference(
                        title = { Text(stringResource(R.string.weekly_backup)) },
                        description = stringResource(R.string.weekly_backup_desc),
                        checked = weeklyBackupEnabled,
                        onCheckedChange = onWeeklyBackupEnabledChange,
                        isEnabled = autoBackupEnabled,
                    )
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
            else -> context.getString(R.string.backup_type_weekly)
        }

    return Pair(formattedTime, type)
}
