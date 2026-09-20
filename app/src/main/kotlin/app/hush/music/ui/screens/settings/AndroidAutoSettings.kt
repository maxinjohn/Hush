/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.screens.settings

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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.hush.music.LocalDatabase
import app.hush.music.LocalPlayerAwareWindowInsets
import app.hush.music.R
import app.hush.music.constants.AndroidAutoSectionsOrderKey
import app.hush.music.constants.AndroidAutoSpotifyPlaylistsKey
import app.hush.music.constants.AndroidAutoTargetPlaylistKey
import app.hush.music.constants.AndroidAutoYouTubePlaylistsKey
import app.hush.music.constants.MediaSessionConstants
import app.hush.music.playback.AndroidAutoPlaylists
import app.hush.music.spotify.SpotifyAccountViewModel
import app.hush.music.spotify.SpotifyLibraryViewModel
import app.hush.music.ui.component.IconButton
import app.hush.music.ui.component.ListPreference
import app.hush.music.ui.component.PreferenceEntry
import app.hush.music.ui.component.PreferenceGroup
import app.hush.music.ui.component.SwitchPreference
import app.hush.music.ui.utils.backToMain
import app.hush.music.utils.rememberPreference
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

typealias AndroidAutoSection = AndroidAutoPlaylists.Section

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
    AndroidAutoPlaylists.serializeSections(
        sections.map { (section, enabled) -> AndroidAutoPlaylists.SectionState(section, enabled) },
    )

fun deserializeSections(raw: String): List<Pair<AndroidAutoSection, Boolean>> =
    AndroidAutoPlaylists
        .deserializeSections(raw)
        .map { (section, enabled) -> section to enabled }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroidAutoSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    spotifyAccountViewModel: SpotifyAccountViewModel = hiltViewModel(),
    spotifyLibraryViewModel: SpotifyLibraryViewModel = hiltViewModel(),
) {
    val haptic = LocalHapticFeedback.current
    val database = LocalDatabase.current

    val userPlaylists by database.playlistsByCreateDateAsc().collectAsStateWithLifecycle(initialValue = emptyList())
    val spotifyState by spotifyAccountViewModel.uiState.collectAsStateWithLifecycle()
    val spotifyPlaylists by spotifyLibraryViewModel.playlists.collectAsStateWithLifecycle()

    // A connected account is the only reason these two rows mean anything. The account is restored
    // asynchronously, so until that finishes the answer is "not known yet" rather than "no".
    val spotifyConnected = !spotifyState.isLoading && spotifyState.isAuthenticated

    LaunchedEffect(spotifyConnected) {
        // Connected with an empty library: the Spotify screen would have to be opened on the phone
        // before a car could list it, so ask once here instead.
        if (spotifyConnected && spotifyPlaylists.isEmpty()) {
            spotifyLibraryViewModel.refreshPlaylists()
        }
    }

    val (youtubePlaylistsEnabled, onYoutubePlaylistsChange) =
        rememberPreference(
            key = AndroidAutoYouTubePlaylistsKey,
            // On by default, which is also what a car used to show before this switch was read at
            // all - turning it into a real setting must not quietly empty the car's folders.
            defaultValue = true,
        )

    val (spotifyPlaylistsEnabled, onSpotifyPlaylistsChange) =
        rememberPreference(
            key = AndroidAutoSpotifyPlaylistsKey,
            defaultValue = true,
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

    // Spotify playlists are offered under the same media id the car's browse tree publishes for
    // them, so a destination picked here is exactly the thing the save button looks up.
    val spotifyDestinations =
        if (spotifyConnected) {
            spotifyPlaylists.map { playlist ->
                AndroidAutoPlaylists.spotifyMediaId(playlist.id) to playlist.name
            }
        } else {
            emptyList()
        }

    val playlistOptions =
        listOf(MediaSessionConstants.TARGET_PLAYLIST_AUTO) +
            userPlaylists.map { it.id } +
            spotifyDestinations.map { (id, _) -> id }

    val playlistLabel: @Composable (String) -> String = { id ->
        if (id == MediaSessionConstants.TARGET_PLAYLIST_AUTO) {
            stringResource(R.string.android_auto_target_playlist_auto)
        } else {
            userPlaylists.find { it.id == id }?.playlist?.name
                ?: spotifyDestinations.find { (destination, _) -> destination == id }?.second
                ?: id
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

                    if (spotifyConnected) {
                        item {
                            SwitchPreference(
                                title = { Text(stringResource(R.string.android_auto_spotify_playlists)) },
                                description = stringResource(R.string.android_auto_spotify_playlists_desc),
                                icon = { Icon(painterResource(R.drawable.queue_music), null) },
                                checked = spotifyPlaylistsEnabled,
                                onCheckedChange = onSpotifyPlaylistsChange,
                            )
                        }
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
