/*
 * Hush — ported from Vivi Music v6.0.3 (GPL-3.0)
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package app.hush.music.ui.screens.settings

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
import app.hush.music.LocalPlayerAwareWindowInsets
import app.hush.music.R
import app.hush.music.constants.HushCanvasKey
import app.hush.music.constants.CanvasSource
import app.hush.music.constants.CanvasSourceKey
import app.hush.music.ui.component.IconButton
import app.hush.music.ui.component.PreferenceEntry
import app.hush.music.ui.component.PreferenceGroup
import app.hush.music.ui.utils.backToMain
import app.hush.music.utils.rememberEnumPreference
import app.hush.music.utils.rememberPreference

@Composable
fun CanvasSelection(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val (hushCanvasEnabled) = rememberPreference(HushCanvasKey, defaultValue = false)
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
                text = stringResource(R.string.hush_canvas_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

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
