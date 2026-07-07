/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.screens

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.hush.music.BuildConfig
import app.hush.music.R
import app.hush.music.constants.DarkModeKey
import app.hush.music.constants.PureBlackKey
import app.hush.music.constants.UpdateChannel
import app.hush.music.defaultUpdateChannel
import app.hush.music.musicrecognition.MusicRecognitionRoute
import app.hush.music.ui.component.BottomSheet
import app.hush.music.ui.component.BottomSheetMenu
import app.hush.music.ui.component.LocalMenuState
import app.hush.music.ui.component.rememberBottomSheetState
import app.hush.music.ui.screens.BrowseScreen
import app.hush.music.ui.screens.artist.ArtistAlbumsScreen
import app.hush.music.ui.screens.artist.ArtistItemsScreen
import app.hush.music.ui.screens.artist.ArtistScreen
import app.hush.music.ui.screens.artist.ArtistSongsScreen
import app.hush.music.ui.screens.library.LibraryScreen
import app.hush.music.ui.screens.library.LocalSongScreen
import app.hush.music.ui.screens.musicrecognition.MusicRecognitionScreen
import app.hush.music.ui.screens.playlist.AutoPlaylistScreen
import app.hush.music.ui.screens.playlist.CachePlaylistScreen
import app.hush.music.ui.screens.playlist.LocalPlaylistScreen
import app.hush.music.ui.screens.playlist.OnlinePlaylistScreen
import app.hush.music.ui.screens.podcast.OnlinePodcastScreen
import app.hush.music.ui.screens.playlist.SpotifyPlaylistScreen
import app.hush.music.ui.screens.playlist.TopPlaylistScreen
import app.hush.music.ui.screens.search.OnlineSearchResult
import app.hush.music.ui.screens.search.OnlineSearchResultArgument
import app.hush.music.ui.screens.search.OnlineSearchResultRoute
import app.hush.music.ui.screens.search.OnlineSearchResultRoutePrefix
import app.hush.music.ui.screens.search.SearchScreen
import app.hush.music.ui.screens.settings.AboutScreen
import app.hush.music.ui.screens.settings.AccountSettings
import app.hush.music.ui.screens.settings.AiIntegrationSettings
import app.hush.music.ui.screens.settings.AndroidAutoSettings
import app.hush.music.ui.screens.settings.AodCustomizedScreen
import app.hush.music.ui.screens.settings.AppearanceSettings
import app.hush.music.ui.screens.settings.FontSelectionScreen
import app.hush.music.ui.screens.settings.AutoBackupSettings
import app.hush.music.ui.screens.settings.BackupAndRestore
import app.hush.music.ui.screens.settings.ChangelogScreen
import app.hush.music.ui.screens.settings.ContentSettings
import app.hush.music.ui.screens.settings.CustomizeBackground
import app.hush.music.ui.screens.settings.DarkMode
import app.hush.music.ui.screens.settings.DebugSettings
import app.hush.music.ui.screens.settings.HiddenPlaylistsScreen
import app.hush.music.ui.screens.settings.IconScreen
import app.hush.music.ui.screens.settings.IntegrationScreen
import app.hush.music.ui.screens.settings.InternetSettings
import app.hush.music.ui.screens.settings.LastFMSettings
import app.hush.music.ui.screens.settings.CanvasSelection
import app.hush.music.ui.screens.settings.LyricsAnimationSettings
import app.hush.music.ui.screens.settings.AlarmSettings
import app.hush.music.ui.screens.settings.LyricsSettings
import app.hush.music.ui.screens.settings.MusicTogetherScreen
import app.hush.music.ui.screens.settings.PalettePickerScreen
import app.hush.music.ui.screens.settings.JioSaavnSettings
import app.hush.music.ui.screens.settings.PlayerSettings
import app.hush.music.ui.screens.settings.StreamSourcesSettings
import app.hush.music.ui.screens.settings.PoTokenScreen
import app.hush.music.ui.screens.settings.PrivacySettings
import app.hush.music.ui.screens.settings.SettingsScreen
import app.hush.music.ui.screens.settings.StorageSettings
import app.hush.music.ui.screens.settings.ThemeCreatorScreen
import app.hush.music.ui.screens.settings.UpdateScreen
import app.hush.music.ui.utils.ShowMediaInfo
import app.hush.music.utils.rememberEnumPreference
import app.hush.music.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
fun NavGraphBuilder.navigationBuilder(
    navController: NavHostController,
    scrollBehavior: TopAppBarScrollBehavior,
    latestVersionName: () -> String,
    disableAnimations: Boolean = false,
    onClearUpdateBadge: () -> Unit = {},
) {
    composable(Screens.ROUTE_HOME) {
        HomeScreen(navController)
    }
    composable(
        Screens.ROUTE_LIBRARY,
    ) {
        LibraryScreen(navController)
    }
    composable(Screens.ROUTE_SEARCH) {
        SearchScreen(
            navController = navController,
            onSearchClick = {
                navController.currentBackStackEntry
                    ?.savedStateHandle
                    ?.set("openSearch", true)
            },
        )
    }
    composable("local_songs") {
        LocalSongScreen(navController)
    }
    composable("history") {
        HistoryScreen(navController)
    }
    composable("stats") {
        StatsScreen(navController)
    }
    composable(
        route = "year_in_music?year={year}",
        arguments =
            listOf(
                navArgument("year") {
                    type = NavType.IntType
                    defaultValue = -1
                },
            ),
    ) { backStackEntry ->
        val selectedYear = backStackEntry.arguments?.getInt("year")?.takeIf { it > 0 }
        YearInMusicScreen(
            navController = navController,
            initialYear = selectedYear,
        )
    }
    composable(MusicRecognitionRoute) {
        MusicRecognitionScreen(navController)
    }
    composable(Screens.ROUTE_MOOD_AND_GENRES) {
        MoodAndGenresScreen(navController)
    }
    composable("account") {
        AccountScreen(navController, scrollBehavior)
    }
    composable("new_release") {
        NewReleaseScreen(navController, scrollBehavior)
    }
    composable("charts_screen") {
        ChartsScreen(navController)
    }
    composable(
        route = "browse/{browseId}",
        arguments =
            listOf(
                navArgument("browseId") {
                    type = NavType.StringType
                },
            ),
    ) {
        BrowseScreen(
            navController,
            scrollBehavior,
            it.arguments?.getString("browseId"),
        )
    }
    composable(
        route = OnlineSearchResultRoute,
        arguments =
            listOf(
                navArgument(OnlineSearchResultArgument) {
                    type = NavType.StringType
                },
            ),
        enterTransition = {
            if (disableAnimations) {
                fadeIn(tween(0))
            } else {
                fadeIn(tween(250))
            }
        },
        exitTransition = {
            if (disableAnimations) {
                fadeOut(tween(0))
            } else if (targetState.destination.route?.startsWith(OnlineSearchResultRoutePrefix) == true) {
                fadeOut(tween(200))
            } else {
                fadeOut(tween(200)) + slideOutHorizontally { -it / 2 }
            }
        },
        popEnterTransition = {
            if (disableAnimations) {
                fadeIn(tween(0))
            } else if (initialState.destination.route?.startsWith(OnlineSearchResultRoutePrefix) == true) {
                fadeIn(tween(250))
            } else {
                fadeIn(tween(250)) + slideInHorizontally { -it / 2 }
            }
        },
        popExitTransition = {
            if (disableAnimations) {
                fadeOut(tween(0))
            } else {
                fadeOut(tween(200))
            }
        },
    ) {
        OnlineSearchResult(navController)
    }
    composable(
        route = "album/{albumId}",
        arguments =
            listOf(
                navArgument("albumId") {
                    type = NavType.StringType
                },
            ),
    ) {
        AlbumScreen(navController, scrollBehavior)
    }
    composable(
        route = "artist/{artistId}",
        arguments =
            listOf(
                navArgument("artistId") {
                    type = NavType.StringType
                },
            ),
    ) {
        ArtistScreen(navController, scrollBehavior)
    }
    composable(
        route = "artist/{artistId}/songs",
        arguments =
            listOf(
                navArgument("artistId") {
                    type = NavType.StringType
                },
            ),
    ) {
        ArtistSongsScreen(navController, scrollBehavior)
    }
    composable(
        route = "artist/{artistId}/albums",
        arguments =
            listOf(
                navArgument("artistId") {
                    type = NavType.StringType
                },
            ),
    ) {
        ArtistAlbumsScreen(navController, scrollBehavior)
    }
    composable(
        route = "artist/{artistId}/items?browseId={browseId}&params={params}",
        arguments =
            listOf(
                navArgument("artistId") {
                    type = NavType.StringType
                },
                navArgument("browseId") {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument("params") {
                    type = NavType.StringType
                    nullable = true
                },
            ),
    ) {
        ArtistItemsScreen(navController, scrollBehavior)
    }
    composable(
        route = "online_playlist/{playlistId}",
        arguments =
            listOf(
                navArgument("playlistId") {
                    type = NavType.StringType
                },
            ),
    ) {
        OnlinePlaylistScreen(navController, scrollBehavior)
    }
    composable(
        route = "online_podcast/{podcastId}",
        arguments =
            listOf(
                navArgument("podcastId") {
                    type = NavType.StringType
                },
            ),
    ) {
        OnlinePodcastScreen(navController, scrollBehavior)
    }
    composable(
        route = "local_playlist/{playlistId}",
        arguments =
            listOf(
                navArgument("playlistId") {
                    type = NavType.StringType
                },
            ),
    ) {
        LocalPlaylistScreen(navController, scrollBehavior)
    }
    composable(
        route = "spotify_playlist/{playlistId}",
        arguments =
            listOf(
                navArgument("playlistId") {
                    type = NavType.StringType
                },
            ),
    ) {
        SpotifyPlaylistScreen(navController, scrollBehavior)
    }
    composable(
        route = "auto_playlist/{playlist}",
        arguments =
            listOf(
                navArgument("playlist") {
                    type = NavType.StringType
                },
            ),
    ) {
        AutoPlaylistScreen(navController, scrollBehavior)
    }
    composable(
        route = "cache_playlist/{playlist}",
        arguments =
            listOf(
                navArgument("playlist") {
                    type = NavType.StringType
                },
            ),
    ) {
        CachePlaylistScreen(navController, scrollBehavior)
    }
    composable(
        route = "top_playlist/{top}",
        arguments =
            listOf(
                navArgument("top") {
                    type = NavType.StringType
                },
            ),
    ) {
        TopPlaylistScreen(navController, scrollBehavior)
    }
    composable(
        route = "youtube_browse/{browseId}?params={params}",
        arguments =
            listOf(
                navArgument("browseId") {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument("params") {
                    type = NavType.StringType
                    nullable = true
                },
            ),
    ) {
        YouTubeBrowseScreen(navController)
    }
    composable(Screens.ROUTE_SETTINGS) {
        SettingsScreen(navController, scrollBehavior, latestVersionName())
    }
    composable("settings/account") {
        AccountSettings(navController, scrollBehavior, latestVersionName())
    }
    composable("settings/hidden_playlists") {
        HiddenPlaylistsScreen(navController, scrollBehavior)
    }
    composable("settings/appearance") {
        AppearanceSettings(navController, scrollBehavior)
    }
    composable("settings/appearance/icon") {
        IconScreen(navController)
    }
    composable("settings/appearance/font_selection") {
        FontSelectionScreen(navController, scrollBehavior)
    }
    composable("settings/appearance/aod_customized") {
        AodCustomizedScreen(navController, scrollBehavior)
    }
    composable("settings/appearance/palette_picker") {
        PalettePickerScreen(navController)
    }
    composable("settings/appearance/lyrics_animations") {
        LyricsAnimationSettings(navController, scrollBehavior)
    }
    composable("settings/appearance/canvas") {
        CanvasSelection(navController, scrollBehavior)
    }
    composable("settings/appearance/theme_creator") {
        ThemeCreatorScreen(navController)
    }
    composable("settings/content") {
        ContentSettings(navController)
    }
    composable("settings/lyrics") {
        LyricsSettings(navController)
    }
    composable("settings/internet") {
        InternetSettings(navController, scrollBehavior)
    }
    composable("settings/player") {
        PlayerSettings(navController, scrollBehavior)
    }
    composable("settings/player/stream_sources") {
        StreamSourcesSettings(navController, scrollBehavior)
    }
    composable("settings/misc/jiosaavn") {
        val nestedScrollBehavior =
            TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
        JioSaavnSettings(navController, nestedScrollBehavior)
    }
    composable("settings/player/jiosaavn") {
        val nestedScrollBehavior =
            TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
        JioSaavnSettings(navController, nestedScrollBehavior)
    }
    composable("settings/android_auto") {
        AndroidAutoSettings(navController, scrollBehavior)
    }
    composable("settings/alarm") {
        AlarmSettings(navController)
    }
    composable("settings/storage") {
        StorageSettings(navController, scrollBehavior)
    }
    composable("settings/privacy") {
        PrivacySettings(navController, scrollBehavior)
    }
    composable("settings/backup_restore") {
        BackupAndRestore(navController, scrollBehavior)
    }
    composable("settings/backup_restore/autobackup") {
        AutoBackupSettings(navController, scrollBehavior)
    }
    composable("settings/integration") {
        IntegrationScreen(navController, scrollBehavior)
    }
    composable("settings/ai_integration") {
        AiIntegrationSettings(navController)
    }
    composable("settings/music_together") {
        MusicTogetherScreen(navController, scrollBehavior)
    }
    composable("settings/lastfm") {
        LastFMSettings(navController, scrollBehavior)
    }
    composable("settings/misc") {
        DebugSettings(navController)
    }
    if (BuildConfig.UPDATER_AVAILABLE) {
        composable("settings/update") {
            UpdateScreen(navController, scrollBehavior, onUpToDate = onClearUpdateBadge)
        }
    }
    composable(
        route = "settings/changelog?channel={channel}",
        arguments =
            listOf(
                navArgument("channel") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
    ) { backStackEntry ->
        val channelName = backStackEntry.arguments?.getString("channel")
        val channel =
            channelName?.let {
                runCatching { UpdateChannel.valueOf(it) }.getOrNull()
            } ?: defaultUpdateChannel
        ChangelogScreen(navController, scrollBehavior, channel = channel)
    }
    composable("settings/about") {
        AboutScreen(navController, scrollBehavior)
    }
    composable("settings/po_token") {
        PoTokenScreen(navController, scrollBehavior)
    }
    composable("customize_background") {
        CustomizeBackground(navController)
    }
    composable(
        route = "$LOGIN_ROUTE?$LOGIN_URL_ARGUMENT={$LOGIN_URL_ARGUMENT}",
        arguments =
            listOf(
                navArgument(LOGIN_URL_ARGUMENT) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
    ) { backStackEntry ->
        LoginScreen(
            navController,
            startUrl = backStackEntry.arguments?.getString(LOGIN_URL_ARGUMENT)?.let(Uri::decode),
        )
    }
}
