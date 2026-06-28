/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AndroidAutoSectionsOrderKey
import moe.rukamori.archivetune.constants.AndroidAutoTargetPlaylistKey
import moe.rukamori.archivetune.constants.AndroidAutoYouTubePlaylistsKey
import moe.rukamori.archivetune.constants.MediaSessionConstants
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.ListPreference
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.rememberPreference
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

enum class AndroidAutoSection(val id: String) {
    LIKED("liked"),
    SONGS("songs"),
    ARTISTS("artists"),
    ALBUMS("albums"),
    PLAYLISTS("playlists"),
}

@Composable
fun AndroidAutoSection.label(): String =
    when (this) {
        AndroidAutoSection.LIKED -> stringResource(R.string.liked_songs)
        AndroidAutoSection.SONGS -> stringResource(R.string.songs)
        AndroidAutoSection.ARTISTS -> stringResource(R.string.artists)
        AndroidAutoSection.ALBUMS -> stringResource(R.string.albums)
        AndroidAutoSection.PLAYLISTS -> stringResource(R.string.playlists)
    }

fun serializeSections(sections: List<Pair<AndroidAutoSection, Boolean>>): String =
    sections.joinToString(",") { (section, enabled) -> "${section.id}:$enabled" }

fun deserializeSections(raw: String): List<Pair<AndroidAutoSection, Boolean>> {
    if (raw.isBlank()) return AndroidAutoSection.entries.map { it to true }
    return raw.split(",").mapNotNull { token ->
        val parts = token.split(":")
        if (parts.size != 2) return@mapNotNull null
        val section = AndroidAutoSection.entries.find { it.id == parts[0] } ?: return@mapNotNull null
        val enabled = parts[1].toBooleanStrictOrNull() ?: true
        section to enabled
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroidAutoSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val haptic = LocalHapticFeedback.current
    val database = LocalDatabase.current

    val userPlaylists by database.playlistsByCreateDateAsc().collectAsStateWithLifecycle(initialValue = emptyList())

    val (youtubePlaylistsEnabled, onYoutubePlaylistsChange) =
        rememberPreference(
            key = AndroidAutoYouTubePlaylistsKey,
            defaultValue = false,
        )

    val (sectionsRaw, onSectionsChange) =
        rememberPreference(
            key = AndroidAutoSectionsOrderKey,
            defaultValue = serializeSections(AndroidAutoSection.entries.map { it to true }),
        )

    val (targetPlaylist, onTargetPlaylistChange) =
        rememberPreference(
            key = AndroidAutoTargetPlaylistKey,
            defaultValue = MediaSessionConstants.TARGET_PLAYLIST_AUTO,
        )

    var sections by remember(sectionsRaw) {
        mutableStateOf(deserializeSections(sectionsRaw))
    }

    val lazyListState = rememberLazyListState()
    val reorderableState =
        rememberReorderableLazyListState(lazyListState) { from, to ->
            val sectionHeaderOffset = 2
            val fromReal = from.index - sectionHeaderOffset
            val toReal = to.index - sectionHeaderOffset
            if (fromReal in sections.indices && toReal in sections.indices) {
                sections =
                    sections.toMutableList().apply {
                        add(toReal, removeAt(fromReal))
                    }
                onSectionsChange(serializeSections(sections))
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }

    val playlistOptions = listOf(MediaSessionConstants.TARGET_PLAYLIST_AUTO) + userPlaylists.map { it.id }

    val playlistLabel: @Composable (String) -> String = { id ->
        if (id == MediaSessionConstants.TARGET_PLAYLIST_AUTO) {
            stringResource(R.string.android_auto_target_playlist_auto)
        } else {
            userPlaylists.find { it.id == id }?.playlist?.name ?: id
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .windowInsetsPadding(
                        LocalPlayerAwareWindowInsets.current.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                        ),
                    ),
        ) {
            item {
                Spacer(
                    Modifier.windowInsetsPadding(
                        LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top),
                    ),
                )
            }

            item {
                PreferenceGroup(title = stringResource(R.string.android_auto_visible_sections)) {
                    item {
                        PreferenceEntry(
                            title = { Text(stringResource(R.string.android_auto_reorder_hint)) },
                            onClick = null,
                        )
                    }
                }
            }

            items(sections, key = { (section, _) -> section.id }) { (section, enabled) ->
                ReorderableItem(reorderableState, key = section.id) {
                    PreferenceGroup {
                        item {
                            PreferenceEntry(
                                icon = {
                                    Icon(
                                        painterResource(
                                            when (section) {
                                                AndroidAutoSection.LIKED -> R.drawable.favorite
                                                AndroidAutoSection.SONGS -> R.drawable.music_note
                                                AndroidAutoSection.ARTISTS -> R.drawable.artist
                                                AndroidAutoSection.ALBUMS -> R.drawable.album
                                                AndroidAutoSection.PLAYLISTS -> R.drawable.queue_music
                                            },
                                        ),
                                        contentDescription = null,
                                    )
                                },
                                title = { Text(section.label()) },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            painter = painterResource(R.drawable.drag_handle),
                                            contentDescription = null,
                                            modifier =
                                                Modifier
                                                    .size(24.dp)
                                                    .draggableHandle(
                                                        onDragStarted = {
                                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        },
                                                    ),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Switch(
                                            checked = enabled,
                                            onCheckedChange = { newValue ->
                                                sections =
                                                    sections.map { (s, e) ->
                                                        if (s == section) s to newValue else s to e
                                                    }
                                                onSectionsChange(serializeSections(sections))
                                            },
                                            thumbContent = {
                                                Icon(
                                                    painter = painterResource(
                                                        if (enabled) R.drawable.check else R.drawable.close,
                                                    ),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                                )
                                            },
                                        )
                                    }
                                },
                                onClick = {
                                    sections =
                                        sections.map { (s, e) ->
                                            if (s == section) s to !e else s to e
                                        }
                                    onSectionsChange(serializeSections(sections))
                                },
                            )
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(SettingsDimensions.SectionSpacing))
            }

            item {
                PreferenceGroup(title = stringResource(R.string.android_auto_target_playlist)) {
                    item {
                        ListPreference(
                            title = { Text(stringResource(R.string.android_auto_target_playlist)) },
                            description = stringResource(R.string.android_auto_target_playlist_desc),
                            icon = { Icon(painterResource(R.drawable.playlist_add), null) },
                            selectedValue = targetPlaylist,
                            values = playlistOptions,
                            valueText = playlistLabel,
                            onValueSelected = onTargetPlaylistChange,
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(SettingsDimensions.SectionSpacing))
            }

            item {
                PreferenceGroup(title = stringResource(R.string.mixes)) {
                    item {
                        SwitchPreference(
                            title = { Text(stringResource(R.string.android_auto_youtube_playlists)) },
                            description = stringResource(R.string.android_auto_youtube_playlists_desc),
                            icon = { Icon(painterResource(R.drawable.queue_music), null) },
                            checked = youtubePlaylistsEnabled,
                            onCheckedChange = onYoutubePlaylistsChange,
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(SettingsDimensions.ScreenBottomPadding))
            }
        }

        TopAppBar(
            title = { Text(stringResource(R.string.android_auto)) },
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
}
