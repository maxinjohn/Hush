/*
 * Hush — ported from Vivi Music v6.0.3 (GPL-3.0)
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.ArchiveTuneCanvasKey
import moe.rukamori.archivetune.constants.CanvasSource
import moe.rukamori.archivetune.constants.CanvasSourceKey
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference

@Composable
fun CanvasSelection(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val (archiveTuneCanvasEnabled) = rememberPreference(ArchiveTuneCanvasKey, defaultValue = false)
    val (canvasSource, onCanvasSourceChange) =
        rememberEnumPreference(CanvasSourceKey, defaultValue = CanvasSource.AUTO)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.canvas_source),
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
            Text(
                text = stringResource(R.string.archivetune_canvas_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (!archiveTuneCanvasEnabled) {
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
            }
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
