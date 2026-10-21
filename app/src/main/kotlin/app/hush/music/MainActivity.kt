/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.hush.music

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.datastore.preferences.core.edit
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.window.core.layout.WindowSizeClass
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.valentinilk.shimmer.LocalShimmerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import app.hush.music.ui.component.HushProgressSpinner
import app.hush.music.aod.ACTION_AOD_MODE
import app.hush.music.constants.AppBarHeight
import app.hush.music.constants.AppFontPreference
import app.hush.music.constants.AppSkinKey
import app.hush.music.constants.AppLanguageKey
import app.hush.music.constants.CustomFontUriKey
import app.hush.music.constants.CustomThemeColorKey
import app.hush.music.constants.DarkModeKey
import app.hush.music.constants.DefaultOpenTabKey
import app.hush.music.constants.DisableAnimationsKey
import app.hush.music.constants.DisableScreenshotKey
import app.hush.music.constants.DynamicThemeKey
import app.hush.music.constants.EnableHapticFeedbackKey
import app.hush.music.constants.CompactMiniPlayerBottomSpacing
import app.hush.music.constants.FloatingToolbarHorizontalPadding
import app.hush.music.constants.FontPreferenceKey
import app.hush.music.constants.MiniPlayerBottomSpacing
import app.hush.music.constants.MiniPlayerHeight
import app.hush.music.constants.rememberMiniPlayerHeight
import app.hush.music.constants.MiniPlayerLastAnchorKey
import app.hush.music.constants.NavigationBarAnimationSpec
import app.hush.music.constants.PauseSearchHistoryKey
import app.hush.music.constants.PlayerBackgroundStyle
import app.hush.music.constants.PlayerBackgroundStyleKey
import app.hush.music.constants.PlayerDesignStyle
import app.hush.music.constants.rememberPlayerDesignStyle
import app.hush.music.constants.PureBlackKey
import app.hush.music.constants.SYSTEM_DEFAULT
import app.hush.music.constants.SearchSource
import app.hush.music.constants.SearchSourceKey
import app.hush.music.constants.StopMusicOnTaskClearKey
import app.hush.music.constants.UpdateChannel
import app.hush.music.constants.UpdateChannelKey
import app.hush.music.constants.UseSystemFontKey
import app.hush.music.db.MusicDatabase
import app.hush.music.db.entities.Album
import app.hush.music.db.entities.Artist
import app.hush.music.db.entities.Playlist
import app.hush.music.db.entities.SearchHistory
import app.hush.music.db.entities.Song
import app.hush.music.extensions.toMediaItem
import app.hush.music.innertube.YouTube
import app.hush.music.innertube.models.AlbumItem
import app.hush.music.innertube.models.ArtistItem
import app.hush.music.innertube.models.EpisodeItem
import app.hush.music.innertube.models.PlaylistItem
import app.hush.music.innertube.models.PodcastItem
import app.hush.music.innertube.models.SongItem
import app.hush.music.models.toMediaMetadata
import app.hush.music.musicrecognition.ACTION_MUSIC_RECOGNITION
import app.hush.music.musicrecognition.MusicRecognitionRoute
import app.hush.music.musicrecognition.openMusicRecognition
import app.hush.music.playback.DownloadUtil
import app.hush.music.playback.MusicService
import app.hush.music.playback.MusicService.MusicBinder
import app.hush.music.playback.PlayerConnection
import app.hush.music.playback.queues.ListQueue
import app.hush.music.playback.queues.LocalAlbumRadio
import app.hush.music.playback.queues.Queue
import app.hush.music.playback.queues.YouTubeAlbumRadio
import app.hush.music.playback.queues.YouTubeQueue
import app.hush.music.innertube.models.WatchEndpoint
import app.hush.music.utils.YouTubeUrlParser
import app.hush.music.ui.component.BottomSheetMenu
import app.hush.music.ui.component.BottomSheetPage
import app.hush.music.ui.component.COLLAPSED_ANCHOR
import app.hush.music.ui.component.DISMISSED_ANCHOR
import app.hush.music.ui.component.EXPANDED_ANCHOR
import app.hush.music.ui.component.FloatingNavigationToolbar
import app.hush.music.ui.component.SkinActionsMenu
import app.hush.music.ui.component.IconButton
import app.hush.music.ui.component.LocalBottomSheetPageState
import app.hush.music.ui.component.LocalMenuState
import app.hush.music.ui.component.MarkdownText
import app.hush.music.ui.component.NetworkStatusBanner
import app.hush.music.ui.component.StorageHealthBanner
import app.hush.music.ui.component.SpotiFLACLosslessNudgeHost
import app.hush.music.ui.component.SpotiFLACVerifiedBannerHost
import app.hush.music.ui.component.TopSearch
import app.hush.music.ui.component.TvNavigationRail
import app.hush.music.ui.component.rememberBottomSheetState
import app.hush.music.ui.component.shimmer.ShimmerTheme
import app.hush.music.ui.menu.YouTubeSongMenu
import app.hush.music.ui.player.BottomSheetPlayer
import app.hush.music.ui.screens.LOGIN_URL_ARGUMENT
import app.hush.music.ui.screens.Screens
import app.hush.music.ui.screens.buildLoginRoute
import app.hush.music.ui.screens.navigationBuilder
import app.hush.music.ui.screens.search.DynamicSearchPlaceholder
import app.hush.music.ui.screens.search.LocalSearchScreen
import app.hush.music.ui.screens.search.OnlineSearchResultArgument
import app.hush.music.ui.screens.search.OnlineSearchResultRoutePrefix
import app.hush.music.ui.screens.search.OnlineSearchScreen
import app.hush.music.ui.screens.search.decodeOnlineSearchQuery
import app.hush.music.ui.screens.search.onlineSearchResultRoute
import app.hush.music.onboarding.OnboardingScreenState
import app.hush.music.onboarding.OnboardingViewModel
import app.hush.music.ui.screens.onboarding.OnboardingRoute
import app.hush.music.ui.screens.settings.DarkMode
import app.hush.music.ui.screens.settings.NavigationTab
import app.hush.music.ui.theme.AppSkin
import app.hush.music.ui.theme.LocalNavigationAnatomy
import app.hush.music.ui.theme.HushTheme
import app.hush.music.ui.theme.ColorSaver
import app.hush.music.ui.theme.DefaultThemeColor
import app.hush.music.ui.theme.SkinHeader
import app.hush.music.ui.theme.extractThemeColor
import app.hush.music.ui.theme.skinHeaderHostsActions
import app.hush.music.ui.utils.appBarScrollBehavior
import app.hush.music.ui.utils.backToMain
import app.hush.music.ui.utils.resetContentOffset
import app.hush.music.ui.utils.resetHeightOffset
import app.hush.music.utils.PreferenceStore
import app.hush.music.utils.SyncUtils
import app.hush.music.utils.AppUpdateInstaller
import app.hush.music.utils.Updater
import app.hush.music.utils.dataStore
import app.hush.music.utils.get
import app.hush.music.utils.getAsync
import app.hush.music.utils.isLowRamDevice
import app.hush.music.utils.rememberEnumPreference
import app.hush.music.utils.rememberPreference
import app.hush.music.utils.reportException
import app.hush.music.utils.setAppLocale
import app.hush.music.viewmodels.BackupCategory
import app.hush.music.viewmodels.BackupRestoreViewModel
import app.hush.music.viewmodels.HomeViewModel
import app.hush.music.viewmodels.NetworkBannerViewModel
import app.hush.music.viewmodels.StorageHealthViewModel
import app.hush.music.viewmodels.OnlineSearchSort
import app.hush.music.viewmodels.OnlineSearchViewModel
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import kotlin.random.Random
import kotlin.time.Duration.Companion.days

@Suppress("DEPRECATION", "ASSIGNED_BUT_NEVER_ACCESSED_VARIABLE")
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var database: MusicDatabase

    @Inject
    lateinit var downloadUtil: DownloadUtil

    @Inject
    lateinit var syncUtils: SyncUtils

    private lateinit var navController: NavHostController
    private var pendingIntent: Intent? = null
    private var pendingDeepLinkQueue: Queue? = null
    private var pendingVoiceSearchQuery: String? = null
    private var pendingAodModeRequest = false
    private var pendingAodModeJob: Job? = null
    private var aodModeLaunchRequestCount by mutableIntStateOf(0)
    private var pendingTogetherJoinLink: String? = null
    private var pendingBackupRestoreUri by mutableStateOf<Uri?>(null)
    private var latestVersionName by mutableStateOf(BuildConfig.VERSION_NAME)
    private var latestUpdateChannel by mutableStateOf(defaultUpdateChannel)

    private var playerConnection by mutableStateOf<PlayerConnection?>(null)
    private var isMusicServiceBound = false

    private val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?,
            ) {
                isMusicServiceBound = true
                if (service is MusicBinder) {
                    playerConnection =
                        PlayerConnection(this@MainActivity, service, database, lifecycleScope)
                    playPendingDeepLinkQueueIfReady()
                    playPendingVoiceSearchIfReady()
                    openPendingAodModeIfReady()
                    joinPendingTogetherIfReady()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                isMusicServiceBound = false
                pendingAodModeJob?.cancel()
                pendingAodModeJob = null
                playerConnection?.dispose()
                playerConnection = null
            }
        }

    private fun playPendingDeepLinkQueueIfReady() {
        val pending = pendingDeepLinkQueue ?: return
        val connection = playerConnection ?: return
        lifecycleScope.launch {
            connection.queueRestoreCompleted.first { it }
            if (playerConnection !== connection || pendingDeepLinkQueue !== pending) return@launch
            pendingDeepLinkQueue = null
            connection.playQueue(pending)
        }
    }

    private fun playPendingVoiceSearchIfReady() {
        val query = pendingVoiceSearchQuery ?: return
        val connection = playerConnection ?: return
        lifecycleScope.launch {
            connection.queueRestoreCompleted.first { it }
            if (playerConnection !== connection || pendingVoiceSearchQuery != query) return@launch
            pendingVoiceSearchQuery = null
            connection.playFromVoiceSearch(query)
        }
    }

    private fun requestAodMode() {
        pendingAodModeRequest = true
        startMusicServiceSafely()
        openPendingAodModeIfReady()
    }

    private fun openPendingAodModeIfReady() {
        if (!pendingAodModeRequest) return
        val connection = playerConnection ?: return
        pendingAodModeRequest = false
        pendingAodModeJob?.cancel()
        pendingAodModeJob =
            lifecycleScope.launch {
                connection.queueRestoreCompleted.first { it }
                if (awaitRestorablePlayback(connection)) {
                    aodModeLaunchRequestCount++
                }
            }
    }

    private fun joinPendingTogetherIfReady() {
        val pending = pendingTogetherJoinLink ?: return
        val connection = playerConnection ?: return
        pendingTogetherJoinLink = null
        lifecycleScope.launch(Dispatchers.IO) {
            val displayName =
                runCatching { dataStore.data.first()[app.hush.music.constants.TogetherDisplayNameKey] }
                    .getOrNull()
                    ?.trim()
                    .orEmpty()
                    .ifBlank { Build.MODEL ?: getString(R.string.app_name) }
            withContext(Dispatchers.Main) {
                connection.service.joinTogether(pending, displayName)
            }
        }
    }

    private suspend fun awaitRestorablePlayback(connection: PlayerConnection): Boolean {
        repeat(15) {
            if (
                connection.player.currentMediaItem != null ||
                connection.player.mediaItemCount > 0 ||
                connection.mediaMetadata.value != null
            ) {
                return true
            }
            delay(100)
        }

        return (
            connection.player.currentMediaItem != null ||
                connection.player.mediaItemCount > 0 ||
                connection.mediaMetadata.value != null
        )
    }

    override fun onStart() {
        super.onStart()
        isMusicServiceBound =
            bindService(
                Intent(this, MusicService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE,
            )
        playPendingDeepLinkQueueIfReady()
        openPendingAodModeIfReady()
    }

    private fun safeUnbindMusicService() {
        if (!isMusicServiceBound) return
        try {
            unbindService(serviceConnection)
        } catch (e: IllegalArgumentException) {
        } catch (e: Exception) {
            reportException(e)
        } finally {
            isMusicServiceBound = false
        }
    }

    override fun onStop() {
        // The app is leaving the foreground, which is the last signal we reliably get
        // before a swipe from recents or a system kill. Capture the queue and the
        // position now; the periodic save is up to 30 s behind otherwise.
        runCatching { playerConnection?.service?.persistQueueNow("app backgrounded") }
        safeUnbindMusicService()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        val shouldStopOnTaskClear =
            if (!isFinishing) {
                false
            } else {
                dataStore.get(StopMusicOnTaskClearKey, false)
            }

        if (shouldStopOnTaskClear) {
            playerConnection?.service?.stopAndClearPlayback(clearPersistentState = true)
            safeUnbindMusicService()
            stopService(Intent(this, MusicService::class.java))
            playerConnection = null
        } else {
            // Closing the UI is not a request to forget the queue, so keep the newest
            // one regardless of how the process ends.
            runCatching { playerConnection?.service?.persistQueueNow("activity destroyed") }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::navController.isInitialized) {
            handleIntent(intent, navController)
        } else {
            pendingIntent = intent
        }
    }

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_LTR
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val initialLocale =
                PreferenceStore
                    .get(AppLanguageKey)
                    ?.takeUnless { it == SYSTEM_DEFAULT }
                    ?.let { Locale.forLanguageTag(it) }
                    ?: Locale.getDefault()
            setAppLocale(this, initialLocale)

            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    dataStore.data.first()[AppLanguageKey]
                }.onSuccess { lang ->
                    val targetLocale =
                        lang
                            ?.takeUnless { it == SYSTEM_DEFAULT }
                            ?.let { Locale.forLanguageTag(it) }
                            ?: Locale.getDefault()
                    if (targetLocale != initialLocale) {
                        withContext(Dispatchers.Main) {
                            setAppLocale(this@MainActivity, targetLocale)
                            recreate()
                        }
                    }
                }
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[DisableScreenshotKey] ?: false }
                .distinctUntilChanged()
                .collectLatest {
                    withContext(Dispatchers.Main) {
                        if (it) {
                            window.setFlags(
                                WindowManager.LayoutParams.FLAG_SECURE,
                                WindowManager.LayoutParams.FLAG_SECURE,
                            )
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        }
                    }
                }
        }

        setContent {
            val updateChannel by rememberEnumPreference(UpdateChannelKey, defaultValue = defaultUpdateChannel)

            LaunchedEffect(Unit) {
                while (playerConnection == null) {
                    delay(100)
                }
                delay(500)

                if (
                    BuildConfig.UPDATER_AVAILABLE &&
                    System.currentTimeMillis() - Updater.lastCheckTime > 1.days.inWholeMilliseconds
                ) {
                    val channelString = withContext(Dispatchers.IO) { dataStore.data.first()[UpdateChannelKey] }
                    val actualChannel =
                        channelString?.let {
                            try {
                                UpdateChannel.valueOf(it)
                            } catch (_: IllegalArgumentException) {
                                null
                            }
                        }?.takeIf { channel ->
                            channel == UpdateChannel.STABLE || Updater.supportsExperimentalUpdateChannels()
                        } ?: UpdateChannel.STABLE

                    if (actualChannel != UpdateChannel.NIGHTLY) {
                        val versionResult =
                            when (actualChannel) {
                                UpdateChannel.DAILY_NIGHTLY -> Updater.getLatestDailyNightlyVersionName()
                                else -> Updater.getLatestVersionName(forceRefresh = true)
                            }
                        versionResult.onSuccess {
                            if (Updater.isUpdateAvailable(it, BuildConfig.VERSION_NAME)) {
                                latestUpdateChannel = actualChannel
                                latestVersionName = it
                            }
                        }
                    }
                }
                app.hush.music.utils.UpdateNotificationManager
                    .checkForUpdates(this@MainActivity)
            }

            // Use remembered instances so the same state object is used everywhere
            // (previously retrieving the composition local directly created different
            // instances in different composition scopes which caused the update
            // bottom sheet to not appear and overlay interactions to be blocked).
            val bottomSheetPageState =
                remember {
                    app.hush.music.ui.component
                        .BottomSheetPageState()
                }
            val menuState =
                remember {
                    app.hush.music.ui.component
                        .MenuState()
                }
            val releaseNotesState = remember { mutableStateOf<String?>(null) }
            var showUpdateDownloadDialog by remember { mutableStateOf(false) }
            var updateDownloadProgress by remember { mutableStateOf<Float?>(null) }
            var updateDownloadError by remember { mutableStateOf<String?>(null) }
            val updateDownloadScope = rememberCoroutineScope()
            val openUpdateUrl: (String) -> Unit = { url ->
                try {
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).also {
                        startActivity(it)
                    }
                } catch (_: Exception) {}
            }
            val updateSheetContent: @Composable ColumnScope.() -> Unit = {
                // receiver: ColumnScope
                Text(
                    text = stringResource(R.string.new_update_available),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(top = 16.dp),
                )

                Spacer(Modifier.height(8.dp))

                androidx.compose.material3.OutlinedButton(
                    onClick = {},
                    contentPadding =
                        androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 5.dp,
                            vertical = 5.dp,
                        ),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(text = latestVersionName, style = MaterialTheme.typography.labelLarge)
                }

                Spacer(Modifier.height(12.dp))

                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                ) {
                    val notes = releaseNotesState.value
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

                androidx.compose.material3.Button(
                    onClick = {
                        bottomSheetPageState.dismiss()
                        val url = Updater.getLatestDownloadUrl()
                        if (url.isBlank()) {
                            this@MainActivity.navController.navigate("settings/update") {
                                launchSingleTop = true
                            }
                            return@Button
                        }
                        updateDownloadProgress = null
                        updateDownloadError = null
                        showUpdateDownloadDialog = true
                        updateDownloadScope.launch {
                            AppUpdateInstaller
                                .downloadAndInstall(this@MainActivity, url) { progress ->
                                    updateDownloadProgress = progress.fraction
                                }.onSuccess {
                                    showUpdateDownloadDialog = false
                                }.onFailure { error ->
                                    showUpdateDownloadDialog = false
                                    updateDownloadError = error.message ?: "Download failed"
                                    if (error.message?.contains("can't be installed", ignoreCase = true) != true) {
                                        openUpdateUrl(url)
                                    }
                                }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(text = stringResource(R.string.update_text))
                }
            }

            if (showUpdateDownloadDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showUpdateDownloadDialog = false },
                    title = { Text(stringResource(R.string.downloading)) },
                    text = {
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            if (updateDownloadProgress != null) {
                                Text(
                                    text = "${(updateDownloadProgress!! * 100).toInt()}%",
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Spacer(Modifier.height(8.dp))
                                CircularProgressIndicator(
                                    progress = { updateDownloadProgress!! },
                                    modifier = Modifier.size(64.dp),
                                )
                            } else {
                                HushProgressSpinner(modifier = Modifier.size(64.dp))
                            }
                        }
                    },
                    confirmButton = {},
                )
            }

            // fetch release notes and show sheet when a new version is detected
            LaunchedEffect(latestVersionName, latestUpdateChannel, updateChannel) {
                if (
                    BuildConfig.UPDATER_AVAILABLE &&
                    latestUpdateChannel == updateChannel &&
                    Updater.isUpdateAvailable(latestVersionName, BuildConfig.VERSION_NAME)
                ) {
                    val releaseNotesResult =
                        when (latestUpdateChannel) {
                            UpdateChannel.DAILY_NIGHTLY -> Updater.getLatestDailyNightlyReleaseNotes()
                            else -> Updater.getLatestReleaseNotes()
                        }
                    releaseNotesResult
                        .onSuccess {
                            releaseNotesState.value = it
                        }.onFailure {
                            releaseNotesState.value = null
                        }

                    bottomSheetPageState.show(updateSheetContent)
                }
            }

            val enableDynamicTheme by rememberPreference(DynamicThemeKey, defaultValue = true)
            val customThemeColorValue by rememberPreference(CustomThemeColorKey, defaultValue = "default")
            val darkTheme by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
            val defaultDisableAnimations = remember(this@MainActivity) { applicationContext.isLowRamDevice() }
            val disableAnimations by rememberPreference(
                DisableAnimationsKey,
                defaultValue = defaultDisableAnimations,
            )
            val fontPreference by rememberEnumPreference(FontPreferenceKey, defaultValue = AppFontPreference.DEFAULT)
            val customFontUri by rememberPreference(CustomFontUriKey, defaultValue = "")
            val legacyUseSystemFont by rememberPreference(UseSystemFontKey, defaultValue = false)
            // Stored as the enum's name by the Appearance picker, so the id is normalized rather
            // than matched: an install that ran a build with a skin this one no longer has must
            // start on the shipped look instead of failing to find it.
            val appSkinId by rememberPreference(AppSkinKey, defaultValue = AppSkin.DEFAULT.id)
            val appSkin = AppSkin.fromId(appSkinId)
            val isSystemInDarkTheme = isSystemInDarkTheme()
            val useDarkTheme =
                remember(darkTheme, isSystemInDarkTheme) {
                    if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
                }
            val pureBlackEnabled by rememberPreference(PureBlackKey, defaultValue = false)
            val pureBlack = pureBlackEnabled && useDarkTheme

            val customThemeSeedPalette =
                remember(customThemeColorValue) {
                    if (customThemeColorValue.startsWith("#")) {
                        null
                    } else if (customThemeColorValue.startsWith("seedPalette:")) {
                        app.hush.music.ui.theme.ThemeSeedPaletteCodec
                            .decodeFromPreference(customThemeColorValue)
                    } else {
                        app.hush.music.ui.screens.settings.ThemePalettes
                            .findById(customThemeColorValue)
                            ?.let {
                                app.hush.music.ui.theme.ThemeSeedPalette(
                                    primary = it.primary,
                                    secondary = it.secondary,
                                    tertiary = it.tertiary,
                                    neutral = it.neutral,
                                )
                            }
                    }
                }

            val customThemeColor =
                remember(customThemeColorValue, customThemeSeedPalette) {
                    if (customThemeColorValue.startsWith("#")) {
                        try {
                            val colorString = customThemeColorValue.removePrefix("#")
                            Color(android.graphics.Color.parseColor("#$colorString"))
                        } catch (e: Exception) {
                            DefaultThemeColor
                        }
                    } else {
                        customThemeSeedPalette?.primary ?: DefaultThemeColor
                    }
                }

            var themeColor by rememberSaveable(stateSaver = ColorSaver) {
                mutableStateOf(DefaultThemeColor)
            }

            LaunchedEffect(legacyUseSystemFont) {
                if (!legacyUseSystemFont) return@LaunchedEffect
                val preferences = dataStore.data.first()
                if (preferences[FontPreferenceKey] == null) {
                    dataStore.edit { it[FontPreferenceKey] = AppFontPreference.SYSTEM.name }
                }
            }

            LaunchedEffect(playerConnection, enableDynamicTheme, isSystemInDarkTheme, customThemeColor) {
                val playerConnection = playerConnection
                if (!enableDynamicTheme || playerConnection == null) {
                    themeColor = if (!enableDynamicTheme) customThemeColor else DefaultThemeColor
                    return@LaunchedEffect
                }
                playerConnection.service.currentMediaMetadata.collectLatest { song ->
                    if (song != null) {
                        withContext(Dispatchers.Default) {
                            try {
                                val result =
                                    imageLoader.execute(
                                        ImageRequest
                                            .Builder(this@MainActivity)
                                            .data(song.thumbnailUrl)
                                            .allowHardware(false)
                                            .build(),
                                    )
                                val extractedColor = result.image?.toBitmap()?.extractThemeColor()
                                withContext(Dispatchers.Main) {
                                    themeColor = extractedColor ?: DefaultThemeColor
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    themeColor = DefaultThemeColor
                                }
                            }
                        }
                    } else {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            themeColor = DefaultThemeColor
                        } else {
                            themeColor = customThemeColor
                        }
                    }
                }
            }

            HushTheme(
                darkTheme = useDarkTheme,
                pureBlack = pureBlack,
                themeColor = themeColor,
                seedPalette = if (!enableDynamicTheme) customThemeSeedPalette else null,
                disableAnimations = disableAnimations,
                fontPreference = fontPreference,
                customFontUri = customFontUri,
                // Read here, at the root, because a skin is a decision about the app rather than
                // about a screen: the palette, the type scale, the radii and the surfaces all take
                // it, so a screen that opted in individually would be a screen that disagreed.
                skin = appSkin,
            ) {
                val onboardingViewModel: OnboardingViewModel = hiltViewModel()
                val onboardingState by onboardingViewModel.screenState.collectAsStateWithLifecycle()

                /**
                 * Whether this install still has onboarding to show: null while that is still being
                 * read, which is not the same answer as "yes".
                 *
                 * It used to be read as yes, so an install that had finished onboarding long ago was
                 * drawn the onboarding screen - a full-screen gradient, a pager and a page of
                 * permission rows - for as long as the read took, and only then replaced by the
                 * shell. Measured on the test device: 580 ms of the launch, spent composing a screen
                 * the user was never meant to see, in front of the feed they were waiting for.
                 */
                val onboardingDecision: Boolean? =
                    when (val state = onboardingState) {
                        OnboardingScreenState.Loading -> null
                        OnboardingScreenState.Empty -> true
                        is OnboardingScreenState.Error -> false
                        is OnboardingScreenState.Success -> state.uiState.shouldShowOnboarding
                    }

                if (onboardingDecision != null) {
                    app.hush.music.utils.StartupMark.mark("onboarding decided")
                }

                if (onboardingDecision == true) {
                    OnboardingRoute(viewModel = onboardingViewModel)
                    return@HushTheme
                }

                // The shell waits for the music service to be connected, and for nothing else. It used
                // to also wait for the player's queue to finish restoring, which is launch time spent on
                // a screen that is a spinner: the feed does not read the queue, and the one surface that
                // does - the mini player - is free to appear once its own state lands, exactly as it does
                // when the queue changes later in a session. Measured on the test device: 280-360 ms,
                // sitting in front of the feed rather than beside it.
                var mainShellReady by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    withTimeoutOrNull(5_000) {
                        while (playerConnection == null) {
                            delay(50)
                        }
                        app.hush.music.utils.StartupMark.mark("player connection ready")
                    }
                    mainShellReady = true
                    app.hush.music.utils.StartupMark.mark("main shell ready")
                }

                if (onboardingDecision == null || !mainShellReady) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(
                                    if (pureBlack) Color.Black else MaterialTheme.colorScheme.surface,
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        HushProgressSpinner()
                    }
                    return@HushTheme
                }

                BoxWithConstraints(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(
                                if (pureBlack) Color.Black else MaterialTheme.colorScheme.surface,
                            ),
                ) {
                    val focusManager = LocalFocusManager.current
                    val density = LocalDensity.current
                    val windowsInsets = WindowInsets.systemBars
                    val topInset = with(density) { windowsInsets.getTop(density).toDp() }
                    val bottomInset = with(density) { windowsInsets.getBottom(density).toDp() }
                    val bottomInsetDp = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

                    val isTvDevice = remember { applicationContext.isTvDevice() }
                    val useRail =
                        isTvDevice ||
                            currentWindowAdaptiveInfo()
                                .windowSizeClass
                                .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

                    val navController = rememberNavController()
                    DisposableEffect(navController) {
                        this@MainActivity.navController = navController
                        onDispose {}
                    }
                    val coroutineScope = rememberCoroutineScope()
                    val homeViewModel: HomeViewModel = hiltViewModel()
                    val networkBannerViewModel: NetworkBannerViewModel = hiltViewModel()
                    val storageHealthViewModel: StorageHealthViewModel = hiltViewModel()
                    val allLocalItems by homeViewModel.allLocalItems.collectAsState()
                    val allYtItems by homeViewModel.allYtItems.collectAsState()
                    val networkBannerState by networkBannerViewModel.bannerState.collectAsStateWithLifecycle()
                    val downloadsFolderHealth by storageHealthViewModel.downloadsFolderHealth.collectAsStateWithLifecycle()
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    var previousTab by rememberSaveable { mutableStateOf(Screens.ROUTE_HOME) }
                    var currentTab by rememberSaveable { mutableStateOf(Screens.ROUTE_HOME) }
                    val currentRoute = navBackStackEntry?.destination?.route
                    val onlineSearchViewModel: OnlineSearchViewModel? =
                        if (currentRoute?.startsWith(OnlineSearchResultRoutePrefix) == true && navBackStackEntry != null) {
                            hiltViewModel(navBackStackEntry!!)
                        } else {
                            null
                        }
                    val onlineSearchSort =
                        if (onlineSearchViewModel != null) {
                            onlineSearchViewModel.sort.collectAsStateWithLifecycle().value
                        } else {
                            OnlineSearchSort.DEFAULT
                        }
                    val isYearInMusicScreen = currentRoute?.startsWith("year_in_music") == true

                    val navigationItems =
                        remember(isTvDevice) {
                            if (isTvDevice) Screens.TvMainScreens else Screens.MainScreens
                        }

                    LaunchedEffect(currentRoute, navigationItems) {
                        val route = currentRoute ?: return@LaunchedEffect
                        if (navigationItems.fastAny { it.route == route } && route != currentTab) {
                            previousTab = currentTab
                            currentTab = route
                        }
                    }

                    val (savedMiniPlayerAnchor, setSavedMiniPlayerAnchor) =
                        rememberPreference(
                            MiniPlayerLastAnchorKey,
                            defaultValue = COLLAPSED_ANCHOR,
                        )
                    val defaultOpenTab by rememberEnumPreference(DefaultOpenTabKey, NavigationTab.HOME)
                    val pauseSearchHistory by rememberPreference(PauseSearchHistoryKey, defaultValue = false)
                    val tabOpenedFromShortcut =
                        remember {
                            when {
                                matchesShortcutLibrary(intent?.action) -> NavigationTab.LIBRARY
                                matchesShortcutSearch(intent?.action) -> NavigationTab.SEARCH
                                else -> null
                            }
                        }
                    val launchMusicRecognitionFromShortcut =
                        remember {
                            matchesMusicRecognition(intent?.action)
                        }

                    val topLevelScreens =
                        remember(navigationItems) {
                            buildList {
                                navigationItems.forEach { screen ->
                                    add(screen.route)
                                }
                                add(Screens.ROUTE_SETTINGS)
                            }
                        }

                    val (query, onQueryChange) =
                        rememberSaveable(stateSaver = TextFieldValue.Saver) {
                            mutableStateOf(TextFieldValue())
                        }

                    var active by rememberSaveable {
                        mutableStateOf(false)
                    }

                    val onActiveChange: (Boolean) -> Unit = { newActive ->
                        active = newActive
                        if (!newActive) {
                            focusManager.clearFocus()
                            if (navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route }) {
                                onQueryChange(TextFieldValue())
                            }
                        }
                    }

                    var searchSource by rememberEnumPreference(SearchSourceKey, SearchSource.ONLINE)

                    val searchBarFocusRequester = remember { FocusRequester() }
                    val tvRailFocusRequester = remember { FocusRequester() }
                    val contentAreaFocusRequester = remember { FocusRequester() }

                    val openSearch: () -> Unit = {
                        onActiveChange(true)
                        searchBarFocusRequester.requestFocus()
                    }

                    val onSearch: (String) -> Unit = {
                        if (it.isNotEmpty()) {
                            onActiveChange(false)
                            navController.navigate(onlineSearchResultRoute(it))
                            if (!pauseSearchHistory) {
                                database.query {
                                    insert(SearchHistory(query = it))
                                }
                            }
                        }
                    }

                    var openSearchImmediately: Boolean by remember {
                        mutableStateOf(matchesShortcutSearch(intent?.action))
                    }

                    val shouldShowSearchBar =
                        remember(active, navBackStackEntry) {
                            active ||
                                navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route } ||
                                navBackStackEntry?.destination?.route?.startsWith(OnlineSearchResultRoutePrefix) == true
                        }

                    val shouldShowNavigationBar =
                        remember(navBackStackEntry, active) {
                            navBackStackEntry?.destination?.route == null ||
                                navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route } &&
                                !active
                        }

                    val shouldShowHomeShuffleButton =
                        currentRoute == Screens.ROUTE_HOME &&
                            (allLocalItems.isNotEmpty() || allYtItems.isNotEmpty())

                    // The app's loose actions, hoisted above the Scaffold because where they are drawn
                    // is the skin's decision and both bars read the same three lambdas: Hush's pill
                    // carries them inline beside its destinations, and a skin on a parent's bar draws
                    // them in the header, because that is where every one of those parents keeps them.
                    // Declaring them at the bar they used to live in would have made the header's
                    // copy a second implementation of shuffle, and the two would drift.
                    val onShuffleAll: (() -> Unit)? =
                        if (shouldShowHomeShuffleButton) {
                            {
                                val useLocalSource =
                                    when {
                                        allLocalItems.isNotEmpty() && allYtItems.isNotEmpty() -> {
                                            Random.nextFloat() < 0.5f
                                        }

                                        allLocalItems.isNotEmpty() -> {
                                            true
                                        }

                                        else -> {
                                            false
                                        }
                                    }

                                coroutineScope.launch(Dispatchers.Main) {
                                    if (useLocalSource) {
                                        when (val luckyItem = allLocalItems.random()) {
                                            is Song -> {
                                                playerConnection?.playQueue(
                                                    if (luckyItem.song.isLocal) {
                                                        ListQueue(items = listOf(luckyItem.toMediaItem()))
                                                    } else {
                                                        YouTubeQueue.radio(luckyItem.toMediaMetadata())
                                                    },
                                                )
                                            }

                                            is Album -> {
                                                val albumWithSongs =
                                                    withContext(Dispatchers.IO) {
                                                        database.albumWithSongs(luckyItem.id).first()
                                                    }

                                                albumWithSongs?.let {
                                                    playerConnection?.playQueue(LocalAlbumRadio(it))
                                                }
                                            }

                                            is Artist -> {
                                                Unit
                                            }

                                            is Playlist -> {
                                                Unit
                                            }
                                        }
                                    } else {
                                        when (val luckyItem = allYtItems.random()) {
                                            is SongItem -> {
                                                playerConnection?.playQueue(
                                                    YouTubeQueue.radio(luckyItem.toMediaMetadata()),
                                                )
                                            }

                                            is AlbumItem -> {
                                                playerConnection?.playQueue(YouTubeAlbumRadio(luckyItem.playlistId))
                                            }

                                            is ArtistItem -> {
                                                luckyItem.radioEndpoint?.let {
                                                    playerConnection?.playQueue(YouTubeQueue(it))
                                                }
                                            }

                                            is PlaylistItem -> {
                                                luckyItem.playEndpoint?.let {
                                                    playerConnection?.playQueue(YouTubeQueue.playlist(it))
                                                }
                                            }

                                            is PodcastItem -> {
                                                luckyItem.playEndpoint?.let {
                                                    playerConnection?.playQueue(YouTubeQueue.playlist(it))
                                                }
                                            }

                                            is EpisodeItem -> {
                                                playerConnection?.playQueue(
                                                    ListQueue(
                                                        title = luckyItem.title,
                                                        items =
                                                            listOf(
                                                                luckyItem.asSongItem().toMediaMetadata().toMediaItem(),
                                                            ),
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            null
                        }

                    val onMusicRecognition: (() -> Unit)? =
                        if (shouldShowNavigationBar && !useRail) {
                            { navController.navigate(MusicRecognitionRoute) }
                        } else {
                            null
                        }

                    val onMusicTogether: (() -> Unit)? =
                        if (shouldShowNavigationBar && !useRail) {
                            { navController.navigate("settings/music_together") }
                        } else {
                            null
                        }

                    // What the header is, from the skin in force - see [SkinHeader]. Read here, above
                    // the Scaffold, so the title and the actions cannot disagree about which header
                    // they are drawing.
                    val headerHostsActions = skinHeaderHostsActions(appSkin)
                    // The screen's own name for a header that names the screen. A route that is not one
                    // of the destinations - settings, an album, the player's own screens - keeps the
                    // app's name, because "Home" over a settings screen would be a header naming a
                    // place the user is not.
                    val headerTitleRes =
                        when (currentRoute) {
                            Screens.ROUTE_HOME -> R.string.home
                            Screens.ROUTE_SEARCH -> R.string.search
                            Screens.ROUTE_LIBRARY -> R.string.filter_library
                            else -> R.string.app_name
                        }

                    // The bar's measurements are the skin's, not this file's: a Material skin draws
                    // Material's 80dp `NavigationBar` where Hush floats a 64dp pill, and every number
                    // below reserves room for whichever one is on screen. Read from the composition
                    // local so the bar, the padding behind it and the player sheet's bound cannot
                    // disagree about how tall it is - which they did, at 64dp against 80dp, and the
                    // last line of a screen's content came out cut in half by the bar (measured on
                    // the reporting device under Metrolist).
                    val navAnatomy = LocalNavigationAnatomy.current
                    val barsVisible = shouldShowNavigationBar && !useRail
                    // The window's bottom inset, from the bar's point of view. Material's bar is
                    // flush with the bottom of the window and applies that inset itself, so
                    // reserving it here as well would lift every screen's content above the bar by
                    // the height of the navigation area a second time; the floating pill is not, and
                    // the screens behind it reserve the inset themselves.
                    val barSystemInset =
                        if (barsVisible && navAnatomy.consumesSystemInset) 0.dp else bottomInset

                    fun bottomNavHeight(compact: Boolean): Dp =
                        if (barsVisible) {
                            if (compact) navAnatomy.compactHeight else navAnatomy.height
                        } else {
                            0.dp
                        }

                    // The pill's own height at this font scale, shared by everything that has to leave
                    // room for it - see [rememberMiniPlayerHeight]. One number, computed before the
                    // sheet that reserves it: a bound that disagreed with the pill by a few dp would
                    // clip it or leave a gap, and both have shown up as "the mini player is cut off".
                    val miniPlayerHeight = rememberMiniPlayerHeight()

                    val playerBottomSheetState =
                        rememberBottomSheetState(
                            dismissedBound = 0.dp,
                            collapsedBound =
                                barSystemInset +
                                    (if (barsVisible) {
                                        navAnatomy.compactBottomPadding + navAnatomy.compactHeight
                                    } else {
                                        0.dp
                                    }) +
                                    CompactMiniPlayerBottomSpacing +
                                    miniPlayerHeight,
                            expandedBound = maxHeight,
                        )

                    // The launch frame belongs to the screen, not to the chrome. The player chrome and
                    // the navigation bar were composed *ahead* of the NavHost - their composition
                    // start at ~2.05 s after process start, the nav graph's own build at ~2.4 s, the
                    // feed's composition after that - and a frame is drawn once, so the home feed's
                    // first shelf waited for the whole of the chrome's work. They join on the next
                    // frame instead (16 ms later on a release build), so the frame that carries the
                    // feed to the screen carries nothing else.
                    //
                    // Nothing is laid out differently while it waits: no screen reads the Scaffold's
                    // inner padding - they take their insets from [LocalPlayerAwareWindowInsets] - so
                    // this hides the chrome for one frame, it does not move the feed.
                    var shellChromeComposed by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        // Resumes in the next frame's callback: the pass being composed now is the one
                        // that builds the feed, and it contains none of the chrome.
                        withFrameNanos { }
                        shellChromeComposed = true
                    }

                    val isMiniPlayerVisible = !playerBottomSheetState.isDismissed
                    val navVisibleHeight = bottomNavHeight(compact = isMiniPlayerVisible)
                    val navBottomPadding =
                        if (isMiniPlayerVisible) {
                            navAnatomy.compactBottomPadding
                        } else {
                            navAnatomy.bottomPadding
                        }
                    val miniPlayerNavSpacing =
                        if (isMiniPlayerVisible) {
                            CompactMiniPlayerBottomSpacing
                        } else {
                            MiniPlayerBottomSpacing
                        }
                    val bottomNavigationBarHeight by animateDpAsState(
                        targetValue = if (barsVisible) navVisibleHeight else 0.dp,
                        animationSpec = if (disableAnimations) snap() else NavigationBarAnimationSpec,
                        label = "bottomNavigationBarHeight",
                    )
                    val miniPlayerBottomPadding =
                        if (isMiniPlayerVisible) {
                            if (barsVisible) {
                                barSystemInset +
                                    navBottomPadding +
                                    navVisibleHeight +
                                    miniPlayerNavSpacing
                            } else {
                                bottomInset + miniPlayerNavSpacing
                            }
                        } else {
                            0.dp
                        }

                    val playerBackground by rememberEnumPreference(
                        key = PlayerBackgroundStyleKey,
                        defaultValue = PlayerBackgroundStyle.DEFAULT,
                    )
                    val playerDesignStyle = rememberPlayerDesignStyle()

                    val aodModeEnabled by remember(playerConnection) {
                        playerConnection?.aodModeEnabled ?: MutableStateFlow(false)
                    }.collectAsStateWithLifecycle()

                    LaunchedEffect(aodModeLaunchRequestCount, playerConnection) {
                        val launchRequestCount = aodModeLaunchRequestCount
                        if (launchRequestCount == 0) return@LaunchedEffect
                        val connection = playerConnection ?: return@LaunchedEffect
                        if (!awaitRestorablePlayback(connection)) return@LaunchedEffect
                        if (!playerBottomSheetState.isExpandedOrExpanding) {
                            playerBottomSheetState.expandSoft()
                        }
                        connection.aodModeEnabled.value = true
                        if (aodModeLaunchRequestCount == launchRequestCount) {
                            aodModeLaunchRequestCount = 0
                        }
                    }

                    LaunchedEffect(aodModeEnabled) {
                        val controller = WindowCompat.getInsetsController(window, window.decorView)
                        if (aodModeEnabled) {
                            controller.systemBarsBehavior =
                                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            controller.hide(WindowInsetsCompat.Type.systemBars())
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            controller.show(WindowInsetsCompat.Type.systemBars())
                            controller.systemBarsBehavior =
                                WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }

                    LaunchedEffect(useDarkTheme, playerBottomSheetState.isExpanded, playerBackground, aodModeEnabled) {
                        if (aodModeEnabled) return@LaunchedEffect
                        val isDarkStatusBar =
                            if (playerBottomSheetState.isExpanded &&
                                playerBackground != PlayerBackgroundStyle.DEFAULT
                            ) {
                                true
                            } else {
                                useDarkTheme
                            }
                        setSystemBarAppearance(isDarkStatusBar)
                    }

                    val miniPlayerAnchor by remember {
                        derivedStateOf {
                            when {
                                playerBottomSheetState.isExpanded -> EXPANDED_ANCHOR
                                playerBottomSheetState.isDismissed -> DISMISSED_ANCHOR
                                else -> COLLAPSED_ANCHOR
                            }
                        }
                    }

                    var miniPlayerAnchorPersistenceEnabled by remember(playerConnection) {
                        mutableStateOf(false)
                    }

                    LaunchedEffect(miniPlayerAnchor, isYearInMusicScreen, miniPlayerAnchorPersistenceEnabled) {
                        if (!isYearInMusicScreen && miniPlayerAnchorPersistenceEnabled) {
                            setSavedMiniPlayerAnchor(miniPlayerAnchor)
                        }
                    }

                    var yearInMusicSavedPlayerAnchor by rememberSaveable { mutableStateOf(-1) }

                    val shouldHideStatusBars =
                        isYearInMusicScreen ||
                            (playerBottomSheetState.isExpanded && playerDesignStyle == PlayerDesignStyle.V7)

                    LaunchedEffect(shouldHideStatusBars, aodModeEnabled) {
                        if (aodModeEnabled) return@LaunchedEffect
                        val controller = WindowCompat.getInsetsController(window, window.decorView)
                        if (shouldHideStatusBars) {
                            controller.systemBarsBehavior =
                                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            controller.hide(WindowInsetsCompat.Type.statusBars())
                        } else {
                            controller.show(WindowInsetsCompat.Type.statusBars())
                        }
                    }

                    LaunchedEffect(isYearInMusicScreen, playerConnection) {
                        val connection = playerConnection ?: return@LaunchedEffect
                        val player = connection.player

                        if (isYearInMusicScreen) {
                            if (yearInMusicSavedPlayerAnchor == -1) {
                                yearInMusicSavedPlayerAnchor =
                                    when {
                                        playerBottomSheetState.isExpanded -> EXPANDED_ANCHOR
                                        playerBottomSheetState.isCollapsed -> COLLAPSED_ANCHOR
                                        playerBottomSheetState.isDismissed -> DISMISSED_ANCHOR
                                        else -> COLLAPSED_ANCHOR
                                    }
                            }

                            if (!playerBottomSheetState.isDismissed) {
                                playerBottomSheetState.dismiss()
                            }
                        } else if (yearInMusicSavedPlayerAnchor != -1) {
                            val anchorToRestore = yearInMusicSavedPlayerAnchor
                            yearInMusicSavedPlayerAnchor = -1

                            if (!awaitRestorablePlayback(connection)) {
                                playerBottomSheetState.dismiss()
                            } else {
                                when (anchorToRestore) {
                                    EXPANDED_ANCHOR -> playerBottomSheetState.expandSoft()
                                    COLLAPSED_ANCHOR -> playerBottomSheetState.collapseSoft()
                                    DISMISSED_ANCHOR -> playerBottomSheetState.collapseSoft()
                                    else -> playerBottomSheetState.collapseSoft()
                                }
                            }
                        }
                    }

                    val playerAwareWindowInsets =
                        remember(
                            useRail,
                            bottomInset,
                            barsVisible,
                            playerBottomSheetState.isDismissed,
                            bottomNavigationBarHeight,
                            miniPlayerNavSpacing,
                            navBottomPadding,
                        ) {
                            var bottom = barSystemInset
                            if (barsVisible) {
                                bottom += bottomNavigationBarHeight + navBottomPadding
                            }
                            if (!playerBottomSheetState.isDismissed) {
                                bottom += miniPlayerHeight + miniPlayerNavSpacing
                            }
                            windowsInsets
                                .only(
                                    (
                                        if (useRail) {
                                            WindowInsetsSides.Right
                                        } else {
                                            WindowInsetsSides.Horizontal
                                        }
                                    ) + WindowInsetsSides.Top,
                                ).add(WindowInsets(top = AppBarHeight, bottom = bottom))
                        }


                    val searchBarScrollBehavior =
                        appBarScrollBehavior(
                            canScroll = {
                                navBackStackEntry?.destination?.route?.startsWith(OnlineSearchResultRoutePrefix) == false &&
                                    navBackStackEntry?.destination?.route != Screens.ROUTE_LIBRARY &&
                                    (playerBottomSheetState.isCollapsed || playerBottomSheetState.isDismissed)
                            },
                        )
                    val topAppBarScrollBehavior =
                        appBarScrollBehavior(
                            canScroll = {
                                navBackStackEntry?.destination?.route?.startsWith(OnlineSearchResultRoutePrefix) == false &&
                                    navBackStackEntry?.destination?.route != Screens.ROUTE_LIBRARY &&
                                    (playerBottomSheetState.isCollapsed || playerBottomSheetState.isDismissed)
                            },
                        )

                    val handlePrimaryNavigationClick: (Screens, Boolean) -> Unit = { screen, isSelected ->
                        if (isSelected) {
                            if (screen == Screens.Search) {
                                openSearch()
                                coroutineScope.launch { searchBarScrollBehavior.state.resetHeightOffset() }
                            } else {
                                navController.currentBackStackEntry?.savedStateHandle?.set("scrollToTop", true)
                                coroutineScope.launch {
                                    searchBarScrollBehavior.state.resetHeightOffset()
                                }
                            }
                        } else {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }

                    var previousRoute by rememberSaveable { mutableStateOf<String?>(null) }

                    LaunchedEffect(navBackStackEntry) {
                        val currentRoute = navBackStackEntry?.destination?.route
                        val wasOnNonTopLevelScreen =
                            previousRoute != null &&
                                previousRoute !in topLevelScreens &&
                                previousRoute?.startsWith(OnlineSearchResultRoutePrefix) != true
                        val isReturningToHomeOrLibrary =
                            currentRoute == Screens.ROUTE_HOME ||
                                currentRoute == Screens.ROUTE_LIBRARY

                        if (wasOnNonTopLevelScreen && isReturningToHomeOrLibrary) {
                            searchBarScrollBehavior.state.resetHeightOffset()
                            topAppBarScrollBehavior.state.resetHeightOffset()
                        }

                        val isEnteringSubScreen =
                            currentRoute != null &&
                                currentRoute !in topLevelScreens &&
                                currentRoute.startsWith(OnlineSearchResultRoutePrefix) != true
                        if (isEnteringSubScreen) {
                            topAppBarScrollBehavior.state.resetContentOffset()
                        }

                        previousRoute = currentRoute

                        if ((
                                currentRoute?.startsWith("artist/") == true ||
                                    currentRoute?.startsWith("album/") == true
                            ) &&
                            playerBottomSheetState.isExpanded
                        ) {
                            playerBottomSheetState.collapseSoft()
                        }

                        if (navBackStackEntry?.destination?.route?.startsWith(OnlineSearchResultRoutePrefix) == true) {
                            val searchQuery =
                                decodeOnlineSearchQuery(
                                    navBackStackEntry
                                        ?.arguments
                                        ?.getString(OnlineSearchResultArgument)
                                        .orEmpty(),
                                )
                            onQueryChange(
                                TextFieldValue(
                                    searchQuery,
                                    TextRange(searchQuery.length),
                                ),
                            )
                        } else if (navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route } ||
                            navBackStackEntry?.destination?.route in topLevelScreens
                        ) {
                            onQueryChange(TextFieldValue())
                            if (navBackStackEntry?.destination?.route != Screens.ROUTE_HOME) {
                                searchBarScrollBehavior.state.resetHeightOffset()
                                topAppBarScrollBehavior.state.resetHeightOffset()
                            }
                        }
                    }
                    LaunchedEffect(active) {
                        if (active) {
                            searchBarScrollBehavior.state.resetHeightOffset()
                            topAppBarScrollBehavior.state.resetHeightOffset()
                            searchBarFocusRequester.requestFocus()
                        }
                    }

                    LaunchedEffect(isTvDevice, useRail, active, currentRoute, shouldShowNavigationBar) {
                        if (
                            isTvDevice &&
                            useRail &&
                            shouldShowNavigationBar &&
                            !active &&
                            currentRoute in topLevelScreens
                        ) {
                            delay(100)
                            tvRailFocusRequester.requestFocus()
                        }
                    }

                    var restoredMiniPlayerAnchor by remember(playerConnection) { mutableStateOf(false) }

                    LaunchedEffect(playerConnection, savedMiniPlayerAnchor, isYearInMusicScreen) {
                        if (restoredMiniPlayerAnchor) return@LaunchedEffect
                        val connection = playerConnection ?: return@LaunchedEffect
                        connection.queueRestoreCompleted.first { it }
                        if (!awaitRestorablePlayback(connection)) {
                            if (!playerBottomSheetState.isDismissed) {
                                playerBottomSheetState.dismiss()
                            }
                        } else {
                            if (!isYearInMusicScreen) {
                                when (savedMiniPlayerAnchor) {
                                    EXPANDED_ANCHOR -> playerBottomSheetState.expandSoft()
                                    COLLAPSED_ANCHOR -> playerBottomSheetState.collapseSoft()
                                    DISMISSED_ANCHOR -> playerBottomSheetState.collapseSoft()
                                    else -> playerBottomSheetState.collapseSoft()
                                }
                            }
                        }
                        restoredMiniPlayerAnchor = true
                        miniPlayerAnchorPersistenceEnabled = true
                    }

                    val currentPlayerBottomSheetState = rememberUpdatedState(playerBottomSheetState)
                    val currentIsYearInMusicScreen = rememberUpdatedState(isYearInMusicScreen)

                    DisposableEffect(playerConnection) {
                        val player =
                            playerConnection?.player ?: return@DisposableEffect onDispose { }
                        val listener =
                            object : Player.Listener {
                                private fun collapseDismissedMiniPlayerForActivePlayback() {
                                    if (
                                        player.mediaItemCount > 0 &&
                                        player.currentMediaItem != null &&
                                        player.playWhenReady &&
                                        player.playbackState != Player.STATE_IDLE &&
                                        player.playbackState != Player.STATE_ENDED &&
                                        currentPlayerBottomSheetState.value.isDismissed &&
                                        !currentIsYearInMusicScreen.value
                                    ) {
                                        currentPlayerBottomSheetState.value.collapseSoft()
                                    }
                                }

                                override fun onMediaItemTransition(
                                    mediaItem: MediaItem?,
                                    reason: Int,
                                ) {
                                    collapseDismissedMiniPlayerForActivePlayback()
                                }

                                override fun onTimelineChanged(
                                    timeline: Timeline,
                                    reason: Int,
                                ) {
                                    collapseDismissedMiniPlayerForActivePlayback()
                                }

                                override fun onPlaybackStateChanged(playbackState: Int) {
                                    collapseDismissedMiniPlayerForActivePlayback()
                                }

                                override fun onPlayWhenReadyChanged(
                                    playWhenReady: Boolean,
                                    reason: Int,
                                ) {
                                    collapseDismissedMiniPlayerForActivePlayback()
                                }
                            }
                        player.addListener(listener)
                        onDispose {
                            player.removeListener(listener)
                        }
                    }

                    var shouldShowTopBar by rememberSaveable { mutableStateOf(false) }

                    LaunchedEffect(navBackStackEntry) {
                        shouldShowTopBar =
                            !active && navBackStackEntry?.destination?.route in topLevelScreens &&
                            navBackStackEntry?.destination?.route != Screens.ROUTE_SETTINGS
                    }

                    var sharedSong: SongItem? by remember {
                        mutableStateOf(null)
                    }

                    LaunchedEffect(Unit) {
                        if (pendingIntent != null) {
                            handleIntent(pendingIntent, navController)
                            pendingIntent = null
                        } else {
                            handleIntent(intent, navController)
                        }
                    }

                    val currentTitleRes =
                        remember(navBackStackEntry) {
                            when (navBackStackEntry?.destination?.route) {
                                Screens.ROUTE_HOME -> R.string.home
                                Screens.ROUTE_SEARCH -> R.string.search
                                Screens.ROUTE_LIBRARY -> R.string.filter_library
                                else -> null
                            }
                        }
                    val haptic = LocalHapticFeedback.current
                    val (enableHapticFeedback) = rememberPreference(EnableHapticFeedbackKey, true)
                    val customHaptic =
                        remember(haptic, enableHapticFeedback) {
                            object : HapticFeedback {
                                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                                    if (enableHapticFeedback) {
                                        haptic.performHapticFeedback(hapticFeedbackType)
                                    }
                                }
                            }
                        }

                    CompositionLocalProvider(
                        LocalHapticFeedback provides customHaptic,
                        LocalAnimationsDisabled provides disableAnimations,
                        LocalDatabase provides database,
                        LocalContentColor provides if (pureBlack) Color.White else contentColorFor(MaterialTheme.colorScheme.surface),
                        LocalPlayerConnection provides playerConnection,
                        LocalPlayerAwareWindowInsets provides playerAwareWindowInsets,
                        LocalMiniPlayerBottomPadding provides miniPlayerBottomPadding,
                        LocalMiniPlayerHeight provides miniPlayerHeight,
                        LocalDownloadUtil provides downloadUtil,
                        LocalShimmerTheme provides ShimmerTheme,
                        LocalSyncUtils provides syncUtils,
                        app.hush.music.ui.component.LocalBottomSheetPageState provides bottomSheetPageState,
                        app.hush.music.ui.component.LocalMenuState provides menuState,
                    ) {
                        Row {
                            AnimatedVisibility(
                                visible = useRail && shouldShowNavigationBar,
                                enter = fadeIn(animationSpec = tween(durationMillis = if (disableAnimations) 0 else 150)),
                                exit = fadeOut(animationSpec = tween(durationMillis = if (disableAnimations) 0 else 100)),
                            ) {
                                if (isTvDevice) {
                                    TvNavigationRail(
                                        items = navigationItems,
                                        selectedItemRoute =
                                            if (active) {
                                                Screens.ROUTE_SEARCH
                                            } else {
                                                currentRoute
                                            },
                                        modifier = Modifier,
                                        firstItemFocusRequester = tvRailFocusRequester,
                                        contentFocusRequester =
                                            if (active ||
                                                navBackStackEntry?.destination?.route?.startsWith(OnlineSearchResultRoutePrefix) == true
                                            ) {
                                                searchBarFocusRequester
                                            } else {
                                                contentAreaFocusRequester
                                            },
                                        onItemClick = { screen ->
                                            val wasPlayerActive = playerBottomSheetState.isExpanded
                                            if (wasPlayerActive) {
                                                playerBottomSheetState.collapse(if (disableAnimations) snap() else spring())
                                            }
                                            val isSelected =
                                                navBackStackEntry?.destination?.hierarchy?.any { it.route == screen.route } == true
                                            if (wasPlayerActive && isSelected) {
                                                return@TvNavigationRail
                                            }
                                            handlePrimaryNavigationClick(screen, isSelected)
                                        },
                                    )
                                } else {
                                    NavigationRail(
                                        containerColor =
                                            if (pureBlack) {
                                                Color(0xFF0A0A0A)
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainerLowest
                                            },
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        header = { Spacer(Modifier.height(24.dp)) },
                                    ) {
                                        navigationItems.fastForEach { screen ->
                                            val isSelected =
                                                navBackStackEntry?.destination?.hierarchy?.any { it.route == screen.route } == true
                                            val railColors =
                                                NavigationRailItemDefaults.colors(
                                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                                )

                                            NavigationRailItem(
                                                selected = isSelected,
                                                colors = railColors,
                                                icon = {
                                                    Icon(
                                                        painter =
                                                            painterResource(
                                                                id = if (isSelected) screen.iconIdActive else screen.iconIdInactive,
                                                            ),
                                                        contentDescription = null,
                                                    )
                                                },
                                                label = {
                                                    Text(
                                                        text = stringResource(screen.titleId),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                },
                                                onClick = {
                                                    val wasPlayerActive = playerBottomSheetState.isExpanded

                                                    if (wasPlayerActive) {
                                                        playerBottomSheetState.collapse(if (disableAnimations) snap() else spring())
                                                    }

                                                    if (wasPlayerActive && isSelected) return@NavigationRailItem
                                                    handlePrimaryNavigationClick(screen, isSelected)
                                                },
                                            )
                                        }
                                    }
                                }
                            }

                            Scaffold(
                                topBar = {
                                    if (shouldShowTopBar) {
                                        val shouldUseFloatingTopBar =
                                            remember(navBackStackEntry) {
                                                navBackStackEntry?.destination?.route == Screens.ROUTE_HOME ||
                                                    navBackStackEntry?.destination?.route == Screens.ROUTE_SEARCH ||
                                                    navBackStackEntry?.destination?.route == Screens.ROUTE_LIBRARY
                                            }
                                        val shouldShowBlurBackground =
                                            remember(navBackStackEntry) {
                                                shouldUseFloatingTopBar
                                            }

                                        val surfaceColor = MaterialTheme.colorScheme.surface
                                        val currentScrollBehavior = if (shouldUseFloatingTopBar) searchBarScrollBehavior else topAppBarScrollBehavior
                                        val isLibraryRoute = navBackStackEntry?.destination?.route == Screens.ROUTE_LIBRARY

                                        Box(
                                            modifier =
                                                Modifier.offset {
                                                    IntOffset(
                                                        x = 0,
                                                        y = if (isLibraryRoute) 0 else currentScrollBehavior.state.heightOffset.toInt(),
                                                    )
                                                },
                                        ) {
                                            // Gradient shadow background
                                            if (shouldShowBlurBackground) {
                                                Box(
                                                    modifier =
                                                        Modifier
                                                            .fillMaxWidth()
                                                            .height(
                                                                AppBarHeight +
                                                                    with(LocalDensity.current) {
                                                                        WindowInsets.systemBars.getTop(LocalDensity.current).toDp()
                                                                    },
                                                            ).background(
                                                                Brush.verticalGradient(
                                                                    colors =
                                                                        listOf(
                                                                            surfaceColor.copy(alpha = 0.95f),
                                                                            surfaceColor.copy(alpha = 0.85f),
                                                                            surfaceColor.copy(alpha = 0.6f),
                                                                            Color.Transparent,
                                                                        ),
                                                                ),
                                                            ),
                                                )
                                            }

                                            TopAppBar(
                                                windowInsets =
                                                    WindowInsets.safeDrawing.only(
                                                        (
                                                            if (useRail) {
                                                                WindowInsetsSides.Right
                                                            } else {
                                                                WindowInsetsSides.Horizontal
                                                            }
                                                        ) + WindowInsetsSides.Top,
                                                    ),
                                                title = {
                                                    when (appSkin.spec.header) {
                                                        // The shipped bar: the mark and the app's
                                                        // own name, whatever screen is behind it.
                                                        SkinHeader.BRAND ->
                                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                                BrandHeaderMark(size = 36.dp)
                                                                Text(
                                                                    text = stringResource(R.string.app_name),
                                                                    style =
                                                                        MaterialTheme.typography.titleLarge.copy(
                                                                            fontWeight = FontWeight.Bold,
                                                                        ),
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                )
                                                            }

                                                        // Metrolist's and Vivi's bar: the screen's own
                                                        // name - BitChord's bar fades exactly this in
                                                        // once its feed has scrolled past the large
                                                        // heading - and Vivi's app mark ahead of it.
                                                        SkinHeader.SCREEN_TITLE ->
                                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                                if (appSkin.spec.headerMark) {
                                                                    BrandHeaderMark(size = 32.dp)
                                                                }
                                                                Text(
                                                                    text = stringResource(headerTitleRes),
                                                                    style =
                                                                        MaterialTheme.typography.titleLarge.copy(
                                                                            fontWeight = FontWeight.Bold,
                                                                        ),
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                )
                                                            }

                                                        // SimpMusic's: the app's name over a
                                                        // greeting, the same words on every screen.
                                                        SkinHeader.APP_NAME ->
                                                            Column {
                                                                Text(
                                                                    text = stringResource(R.string.app_name),
                                                                    style =
                                                                        MaterialTheme.typography.titleLarge.copy(
                                                                            fontWeight = FontWeight.Bold,
                                                                        ),
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                )
                                                                Text(
                                                                    text = stringResource(skinGreetingRes()),
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                )
                                                            }
                                                    }
                                                },
                                                actions = {
                                                    HeaderActionButton(
                                                        branded = !headerHostsActions,
                                                        onClick = { navController.navigate("history") },
                                                    ) {
                                                        Icon(
                                                            painter = painterResource(R.drawable.history),
                                                            contentDescription = stringResource(R.string.history),
                                                        )
                                                    }
                                                    // SimpMusic's own header carries three actions and
                                                    // its title takes the rest of the row, so the one
                                                    // skin that shows two lines of title shows one
                                                    // button fewer here and keeps its name whole.
                                                    val appNamedHeader =
                                                        appSkin.spec.header == SkinHeader.APP_NAME
                                                    if (!appNamedHeader) {
                                                        HeaderActionButton(
                                                            branded = !headerHostsActions,
                                                            onClick = { navController.navigate("new_release") },
                                                        ) {
                                                            Icon(
                                                                painter = painterResource(R.drawable.new_release),
                                                                contentDescription = stringResource(R.string.new_release_albums),
                                                            )
                                                        }
                                                    }
                                                    // The actions the shipped app keeps in its pill.
                                                    // A skin on a parent's bar draws them here,
                                                    // because that is where every one of those
                                                    // parents keeps its own: a bar with six items
                                                    // on it was neither app's bottom bar.
                                                    if (headerHostsActions) {
                                                        // A header that names the app carries two lines
                                                        // of title where one that names the screen
                                                        // carries one, so listening together moves
                                                        // into the menu and the name keeps its room.
                                                        // Measured on the test device: with five
                                                        // buttons the SimpMusic header ellipsized the
                                                        // name it exists to show.
                                                        val togetherInMenu = appNamedHeader
                                                        // Drawn only where the action exists: the bars are
                                                        // hidden on a rail layout, and those lambdas are
                                                        // null there, so a button drawn from a null action
                                                        // would be one that opens into nothing.
                                                        if (!togetherInMenu && onMusicTogether != null) {
                                                            HeaderActionButton(
                                                                branded = false,
                                                                onClick = onMusicTogether,
                                                            ) {
                                                                Icon(
                                                                    painter = painterResource(R.drawable.multi_user),
                                                                    contentDescription = stringResource(R.string.music_together),
                                                                )
                                                            }
                                                        }
                                                        val headerMenuItems =
                                                            onMusicRecognition != null ||
                                                                onShuffleAll != null ||
                                                                (togetherInMenu && onMusicTogether != null) ||
                                                                appNamedHeader
                                                        var headerMenuExpanded by remember { mutableStateOf(false) }
                                                        if (headerMenuItems) {
                                                            Box {
                                                                HeaderActionButton(
                                                                    branded = false,
                                                                    onClick = { headerMenuExpanded = true },
                                                                ) {
                                                                    Icon(
                                                                        painter = painterResource(R.drawable.more_vert),
                                                                        contentDescription = stringResource(R.string.more),
                                                                    )
                                                                }
                                                            SkinActionsMenu(
                                                                expanded = headerMenuExpanded,
                                                                pureBlack = pureBlack,
                                                                onDismissRequest = { headerMenuExpanded = false },
                                                                // Listening together has its own button
                                                                // in the headers that have room for it,
                                                                // so the menu carries it only where the
                                                                // row is shorter.
                                                                onMusicTogetherClick =
                                                                    if (togetherInMenu) onMusicTogether else null,
                                                                onMusicRecognitionClick = onMusicRecognition,
                                                                musicRecognitionContentDescription =
                                                                    stringResource(R.string.music_recognition),
                                                                onShuffleClick = onShuffleAll,
                                                                shuffleIconRes = if (shouldShowHomeShuffleButton) R.drawable.shuffle else null,
                                                                shuffleContentDescription = stringResource(R.string.shuffle),
                                                                onNewReleaseClick =
                                                                    if (appNamedHeader) {
                                                                        { navController.navigate("new_release") }
                                                                    } else {
                                                                        null
                                                                    },
                                                                )
                                                            }
                                                        }
                                                    }
                                                    HeaderActionButton(
                                                        branded = !headerHostsActions,
                                                        onClick = { navController.navigate(Screens.ROUTE_SETTINGS) },
                                                    ) {
                                                        BadgedBox(badge = {
                                                            if (
                                                                BuildConfig.UPDATER_AVAILABLE &&
                                                                latestUpdateChannel == updateChannel &&
                                                                Updater.isUpdateAvailable(latestVersionName, BuildConfig.VERSION_NAME)
                                                            ) {
                                                                Badge()
                                                            }
                                                        }) {
                                                            Icon(
                                                                painter = painterResource(R.drawable.settings),
                                                                contentDescription = stringResource(R.string.settings),
                                                                modifier = Modifier.size(24.dp),
                                                            )
                                                        }
                                                    }
                                                },
                                                scrollBehavior =
                                                    if (navBackStackEntry?.destination?.route ==
                                                        Screens.ROUTE_LIBRARY
                                                    ) {
                                                        null
                                                    } else if (shouldUseFloatingTopBar) {
                                                        searchBarScrollBehavior
                                                    } else {
                                                        topAppBarScrollBehavior
                                                    },
                                                colors =
                                                    TopAppBarDefaults.topAppBarColors(
                                                        containerColor =
                                                            if (shouldUseFloatingTopBar) {
                                                                Color.Transparent
                                                            } else if (pureBlack) {
                                                                Color.Black
                                                            } else {
                                                                MaterialTheme.colorScheme.surface
                                                            },
                                                        scrolledContainerColor =
                                                            if (shouldUseFloatingTopBar) {
                                                                Color.Transparent
                                                            } else if (pureBlack) {
                                                                Color.Black
                                                            } else {
                                                                MaterialTheme.colorScheme.surface
                                                            },
                                                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                                                        actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    ),
                                            )
                                        }
                                    }
                                    AnimatedVisibility(
                                        visible =
                                            active ||
                                                navBackStackEntry?.destination?.route?.startsWith(OnlineSearchResultRoutePrefix) == true,
                                        enter = fadeIn(animationSpec = tween(durationMillis = if (disableAnimations) 0 else 300)),
                                        exit = fadeOut(animationSpec = tween(durationMillis = if (disableAnimations) 0 else 200)),
                                    ) {
                                        TopSearch(
                                            query = query,
                                            onQueryChange = onQueryChange,
                                            onSearch = onSearch,
                                            active = active,
                                            onActiveChange = onActiveChange,
                                            placeholder = {
                                                DynamicSearchPlaceholder(
                                                    searchSource = searchSource,
                                                )
                                            },
                                            leadingIcon = {
                                                IconButton(
                                                    onClick = {
                                                        when {
                                                            active -> {
                                                                onActiveChange(false)
                                                            }

                                                            !navigationItems.fastAny {
                                                                it.route == navBackStackEntry?.destination?.route
                                                            } -> {
                                                                navController.navigateUp()
                                                            }

                                                            else -> {
                                                                onActiveChange(true)
                                                            }
                                                        }
                                                    },
                                                    onLongClick = {
                                                        when {
                                                            active -> {}

                                                            !navigationItems.fastAny {
                                                                it.route == navBackStackEntry?.destination?.route
                                                            } -> {
                                                                navController.backToMain()
                                                            }

                                                            else -> {}
                                                        }
                                                    },
                                                ) {
                                                    Icon(
                                                        painterResource(
                                                            if (active ||
                                                                !navigationItems.fastAny {
                                                                    it.route == navBackStackEntry?.destination?.route
                                                                }
                                                            ) {
                                                                R.drawable.arrow_back
                                                            } else {
                                                                R.drawable.search
                                                            },
                                                        ),
                                                        contentDescription = null,
                                                    )
                                                }
                                            },
                                            trailingIcon = {
                                                Row {
                                                    if (active) {
                                                        if (query.text.isNotEmpty()) {
                                                            IconButton(
                                                                onClick = {
                                                                    onQueryChange(
                                                                        TextFieldValue(
                                                                            "",
                                                                        ),
                                                                    )
                                                                },
                                                            ) {
                                                                Icon(
                                                                    painter = painterResource(R.drawable.close),
                                                                    contentDescription = null,
                                                                )
                                                            }
                                                        }
                                                        IconButton(
                                                            onClick = {
                                                                searchSource =
                                                                    if (searchSource ==
                                                                        SearchSource.ONLINE
                                                                    ) {
                                                                        SearchSource.LOCAL
                                                                    } else {
                                                                        SearchSource.ONLINE
                                                                    }
                                                            },
                                                        ) {
                                                            Icon(
                                                                painter =
                                                                    painterResource(
                                                                        when (searchSource) {
                                                                            SearchSource.LOCAL -> R.drawable.library_music
                                                                            SearchSource.ONLINE -> R.drawable.language
                                                                        },
                                                                    ),
                                                                contentDescription = null,
                                                            )
                                                        }
                                                    } else if (onlineSearchViewModel != null) {
                                                        OnlineSearchSortMenu(
                                                            selectedSort = onlineSearchSort,
                                                            onSortSelected = onlineSearchViewModel::updateSort,
                                                        )
                                                    }
                                                }
                                            },
                                            modifier =
                                                Modifier
                                                    .focusRequester(searchBarFocusRequester)
                                                    .let { with(this@BoxWithConstraints) { it.align(Alignment.TopCenter) } },
                                            focusRequester = searchBarFocusRequester,
                                            leftFocusRequester = tvRailFocusRequester,
                                            colors =
                                                if (pureBlack && active) {
                                                    SearchBarDefaults.colors(
                                                        containerColor = Color.Black,
                                                        dividerColor = Color.DarkGray,
                                                        inputFieldColors =
                                                            TextFieldDefaults.colors(
                                                                focusedTextColor = Color.White,
                                                                unfocusedTextColor = Color.Gray,
                                                                focusedContainerColor = Color.Transparent,
                                                                unfocusedContainerColor = Color.Transparent,
                                                                cursorColor = Color.White,
                                                                focusedIndicatorColor = Color.Transparent,
                                                                unfocusedIndicatorColor = Color.Transparent,
                                                            ),
                                                    )
                                                } else {
                                                    SearchBarDefaults.colors(
                                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                                    )
                                                },
                                        ) {
                                            Crossfade(
                                                targetState = searchSource,
                                                animationSpec = tween(durationMillis = if (disableAnimations) 0 else 300),
                                                label = "",
                                                modifier =
                                                    Modifier
                                                        .fillMaxSize()
                                                        .padding(
                                                            bottom =
                                                                if (!playerBottomSheetState.isDismissed) {
                                                                    miniPlayerHeight + miniPlayerNavSpacing
                                                                } else {
                                                                    0.dp
                                                                },
                                                        ).navigationBarsPadding(),
                                            ) { searchSource ->
                                                when (searchSource) {
                                                    SearchSource.LOCAL -> {
                                                        LocalSearchScreen(
                                                            query = query.text,
                                                            navController = navController,
                                                            onDismiss = { onActiveChange(false) },
                                                            pureBlack = pureBlack,
                                                        )
                                                    }

                                                    SearchSource.ONLINE -> {
                                                        OnlineSearchScreen(
                                                            query = query.text,
                                                            onQueryChange = onQueryChange,
                                                            navController = navController,
                                                            onSearch = {
                                                                val parsedLink = YouTubeUrlParser.parse(it)
                                                                if (parsedLink != null) {
                                                                    // Pasted YouTube link → open/play directly instead of searching.
                                                                    onActiveChange(false)
                                                                    when (parsedLink) {
                                                                        is YouTubeUrlParser.Parsed.Video ->
                                                                            playerConnection?.playQueue(
                                                                                YouTubeQueue(
                                                                                    endpoint = WatchEndpoint(videoId = parsedLink.videoId),
                                                                                ),
                                                                            )

                                                                        is YouTubeUrlParser.Parsed.Album ->
                                                                            navController.navigate("album/${parsedLink.playlistId}")

                                                                        is YouTubeUrlParser.Parsed.Playlist ->
                                                                            navController.navigate("online_playlist/${parsedLink.playlistId}")

                                                                        is YouTubeUrlParser.Parsed.Artist ->
                                                                            navController.navigate("artist/${parsedLink.channelId}")
                                                                    }
                                                                    return@OnlineSearchScreen
                                                                }
                                                                navController.navigate(onlineSearchResultRoute(it))
                                                                if (!pauseSearchHistory) {
                                                                    database.query {
                                                                        insert(SearchHistory(query = it))
                                                                    }
                                                                }
                                                            },
                                                            onDismiss = { onActiveChange(false) },
                                                            pureBlack = pureBlack,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                                bottomBar = {
                                    // Held back for the frame that composes the feed; see
                                    // [shellChromeComposed].
                                    if (!shellChromeComposed) return@Scaffold
                                    Box {
                                        BottomSheetPlayer(
                                            state = playerBottomSheetState,
                                            navController = navController,
                                            pureBlack = pureBlack,
                                        )

                                        if (useRail) return@Box

                                        // The bar's own space, screen-side rather than skin-side: the
                                        // floating pill floats above the window's inset with 8dp of
                                        // air, Material's bar is flush with it, and both are the
                                        // skin's answer ([barSystemInset], [navBottomPadding]).
                                        val navSlideDistance =
                                            barSystemInset + navBottomPadding + navVisibleHeight

                                        Box(
                                            modifier =
                                                Modifier
                                                    .align(Alignment.BottomCenter)
                                                    .height(navSlideDistance)
                                                    // The bar rides the sheet's progress, which changes on every
                                                    // pointer sample of a drag: read in a layer block, that costs a
                                                    // redraw of this bar, where the placement modifier it used to be
                                                    // cost a layout pass of it on the same frames.
                                                    .graphicsLayer {
                                                        translationY =
                                                            if (bottomNavigationBarHeight == 0.dp) {
                                                                navSlideDistance.toPx()
                                                            } else {
                                                                val slideOffset =
                                                                    navSlideDistance *
                                                                        playerBottomSheetState.progress.coerceIn(
                                                                            0f,
                                                                            1f,
                                                                        )
                                                                val hideOffset =
                                                                    navSlideDistance *
                                                                        (
                                                                            1 -
                                                                                bottomNavigationBarHeight.coerceAtMost(navVisibleHeight) /
                                                                                navVisibleHeight
                                                                        )
                                                                (slideOffset + hideOffset).toPx()
                                                            }
                                                    },
                                        ) {
                                            FloatingNavigationToolbar(
                                                items = navigationItems,
                                                pureBlack = pureBlack,
                                                compact = isMiniPlayerVisible,
                                                modifier =
                                                    Modifier
                                                        .align(Alignment.BottomCenter)
                                                        .padding(
                                                            start = 0.dp,
                                                            end = 0.dp,
                                                            bottom = barSystemInset + navBottomPadding,
                                                        ).height(navVisibleHeight),
                                                onShuffleClick = onShuffleAll,
                                                shuffleIconRes = if (shouldShowHomeShuffleButton) R.drawable.shuffle else null,
                                                shuffleContentDescription =
                                                    if (shouldShowHomeShuffleButton) {
                                                        stringResource(
                                                            R.string.shuffle,
                                                        )
                                                    } else {
                                                        ""
                                                    },
                                                onMusicRecognitionClick = onMusicRecognition,
                                                musicRecognitionContentDescription =
                                                    if (onMusicRecognition != null) {
                                                        stringResource(
                                                            R.string.music_recognition,
                                                        )
                                                    } else {
                                                        ""
                                                    },
                                                onMusicTogetherClick = onMusicTogether,
                                                isSelected = { screen ->
                                                    navBackStackEntry?.destination?.hierarchy?.any { it.route == screen.route } ==
                                                        true
                                                },
                                                onItemClick = { screen, isSelected ->
                                                    handlePrimaryNavigationClick(screen, isSelected)
                                                },
                                                onSearchItemDoubleClick = {
                                                    searchSource = SearchSource.ONLINE
                                                    openSearch()
                                                },
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                var transitionDirection =
                                    AnimatedContentTransitionScope.SlideDirection.Left

                                if (navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route }) {
                                    if (navigationItems.fastAny { it.route == previousTab }) {
                                        val curIndex =
                                            navigationItems.indexOf(
                                                navigationItems.fastFirstOrNull {
                                                    it.route == navBackStackEntry?.destination?.route
                                                },
                                            )

                                        val prevIndex =
                                            navigationItems.indexOf(
                                                navigationItems.fastFirstOrNull {
                                                    it.route == previousTab
                                                },
                                            )

                                        if (prevIndex > curIndex) {
                                            AnimatedContentTransitionScope.SlideDirection.Right.also {
                                                transitionDirection = it
                                            }
                                        }
                                    }
                                }

                                NavHost(
                                    navController = navController,
                                    startDestination =
                                        if (launchMusicRecognitionFromShortcut) {
                                            MusicRecognitionRoute
                                        } else {
                                            when (tabOpenedFromShortcut ?: defaultOpenTab) {
                                                NavigationTab.HOME -> Screens.ROUTE_HOME
                                                NavigationTab.LIBRARY -> Screens.ROUTE_LIBRARY
                                                else -> Screens.ROUTE_HOME
                                            }
                                        },
                                    enterTransition = {
                                        if (disableAnimations) {
                                            fadeIn(tween(0))
                                        } else if (initialState.destination.route in topLevelScreens &&
                                            targetState.destination.route in topLevelScreens
                                        ) {
                                            val enterFromRight =
                                                transitionDirection ==
                                                    AnimatedContentTransitionScope.SlideDirection.Left
                                            fadeIn(tween(280)) +
                                                slideInHorizontally(
                                                    initialOffsetX = { fullWidth ->
                                                        if (enterFromRight) fullWidth / 2 else -fullWidth / 2
                                                    },
                                                    animationSpec = tween(300),
                                                )
                                        } else {
                                            fadeIn(tween(250)) + slideInHorizontally { it / 2 }
                                        }
                                    },
                                    exitTransition = {
                                        if (disableAnimations) {
                                            fadeOut(tween(0))
                                        } else if (initialState.destination.route in topLevelScreens &&
                                            targetState.destination.route in topLevelScreens
                                        ) {
                                            val enterFromRight =
                                                transitionDirection ==
                                                    AnimatedContentTransitionScope.SlideDirection.Left
                                            fadeOut(tween(220)) +
                                                slideOutHorizontally(
                                                    targetOffsetX = { fullWidth ->
                                                        if (enterFromRight) -fullWidth / 2 else fullWidth / 2
                                                    },
                                                    animationSpec = tween(280),
                                                )
                                        } else {
                                            fadeOut(tween(200)) + slideOutHorizontally { -it / 2 }
                                        }
                                    },
                                    popEnterTransition = {
                                        if (disableAnimations) {
                                            fadeIn(tween(0))
                                        } else if ((
                                                initialState.destination.route in topLevelScreens ||
                                                    initialState.destination.route?.startsWith(OnlineSearchResultRoutePrefix) == true
                                            ) &&
                                            targetState.destination.route in topLevelScreens
                                        ) {
                                            fadeIn(tween(250))
                                        } else {
                                            fadeIn(tween(250)) + slideInHorizontally { -it / 2 }
                                        }
                                    },
                                    popExitTransition = {
                                        if (disableAnimations) {
                                            fadeOut(tween(0))
                                        } else if ((
                                                initialState.destination.route in topLevelScreens ||
                                                    initialState.destination.route?.startsWith(OnlineSearchResultRoutePrefix) == true
                                            ) &&
                                            targetState.destination.route in topLevelScreens
                                        ) {
                                            fadeOut(tween(200))
                                        } else {
                                            fadeOut(tween(200)) + slideOutHorizontally { it / 2 }
                                        }
                                    },
                                    modifier =
                                        Modifier
                                            .then(
                                                if (isTvDevice) {
                                                    Modifier
                                                        .focusRequester(contentAreaFocusRequester)
                                                        .focusGroup()
                                                } else {
                                                    Modifier
                                                },
                                            ).then(
                                                if (navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route } ||
                                                    navBackStackEntry?.destination?.route?.startsWith(OnlineSearchResultRoutePrefix) == true
                                                ) {
                                                    Modifier.nestedScroll(searchBarScrollBehavior.nestedScrollConnection)
                                                } else if (navBackStackEntry?.destination?.route == "settings" ||
                                                    navBackStackEntry?.destination?.route?.startsWith("settings/") == true
                                                ) {
                                                    Modifier
                                                } else {
                                                    Modifier.nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                                                },
                                            ),
                                ) {
                                    navigationBuilder(
                                        navController,
                                        topAppBarScrollBehavior,
                                        { latestVersionName },
                                        homeViewModel = homeViewModel,
                                        disableAnimations = disableAnimations,
                                        onClearUpdateBadge = { latestVersionName = BuildConfig.VERSION_NAME },
                                    )
                                }
                            }
                        }

                        BackHandler(enabled = playerBottomSheetState.isExpanded) {
                            playerBottomSheetState.collapseSoft()
                        }

                        BottomSheetMenu(
                            state = LocalMenuState.current,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )

                        BottomSheetPage(
                            state = LocalBottomSheetPageState.current,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )

                        // SpotiFLAC extension verification: opens the source's Cloudflare
                        // challenge over any screen the moment playback needs it.
                        app.hush.music.ui.component.SpotiFLACVerificationOverlay()

                        sharedSong?.let { song ->
                            playerConnection?.let {
                                Dialog(
                                    onDismissRequest = { sharedSong = null },
                                    properties = DialogProperties(usePlatformDefaultWidth = false),
                                ) {
                                    Surface(
                                        modifier = Modifier.padding(24.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        color = AlertDialogDefaults.containerColor,
                                        tonalElevation = AlertDialogDefaults.TonalElevation,
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                        ) {
                                            YouTubeSongMenu(
                                                song = song,
                                                navController = navController,
                                                onDismiss = { sharedSong = null },
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (!aodModeEnabled) {
                            // One slot for both banners, stacked rather than overlapped. The network
                            // state keeps the top line - being offline is the reason a source cannot
                            // be used, so it must not be replaced by news about a source - and a
                            // source landing sits under it. Each is one line of news about something
                            // that just changed, and neither is ever on screen for long.
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier =
                                    Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(
                                            top = if (shouldShowTopBar) topInset + AppBarHeight + 8.dp else topInset + 8.dp,
                                            start = 16.dp,
                                            end = 16.dp,
                                        ).zIndex(10f),
                            ) {
                                NetworkStatusBanner(state = networkBannerState)
                                // The one banner here that is a standing problem rather than news,
                                // and the only tappable one: the downloads live solely in the folder
                                // it is about, so it stays until the folder works again and leads to
                                // the screen where the folder is chosen.
                                StorageHealthBanner(
                                    health = downloadsFolderHealth,
                                    onOpenStorageSettings = { navController.navigate("settings/storage") },
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                                SpotiFLACVerifiedBannerHost(modifier = Modifier.padding(top = 8.dp))
                                // The one banner here that is *not* news: an offer to turn on
                                // lossless playback, made at most twice per install and answerable
                                // in place. It sits below the two status pills so an offline launch
                                // still leads with the reason nothing can play.
                                SpotiFLACLosslessNudgeHost(
                                    onOpenAudioSources = {
                                        navController.navigate("settings/player/audio_sources")
                                    },
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    }

                    pendingBackupRestoreUri?.let { uri ->
                        BackupRestoreFromIntentDialog(uri = uri)
                    }

                    LaunchedEffect(shouldShowSearchBar, openSearchImmediately) {
                        if (shouldShowSearchBar && openSearchImmediately) {
                            onActiveChange(true)
                            try {
                                delay(100)
                                searchBarFocusRequester.requestFocus()
                            } catch (_: Exception) {
                            }
                            openSearchImmediately = false
                        }
                    }

                    val openSearchFromRoute =
                        navBackStackEntry
                            ?.savedStateHandle
                            ?.getStateFlow("openSearch", false)
                            ?.collectAsStateWithLifecycle()

                    LaunchedEffect(openSearchFromRoute?.value) {
                        if (openSearchFromRoute?.value == true) {
                            navBackStackEntry?.savedStateHandle?.set("openSearch", false)
                            openSearch()
                        }
                    }
                }
            }
        }
    }

    private fun isBackupUri(uri: Uri?): Boolean {
        if (uri == null) return false
        val path = uri.lastPathSegment?.lowercase(Locale.US)
        return path?.endsWith(".backup") == true || uri.toString().lowercase(Locale.US).endsWith(".backup")
    }

    private fun handleIntent(
        intent: Intent?,
        navController: NavHostController,
    ) {
        if (intent == null) return
        intent.getStringExtra("navigate_to")?.takeIf { it.isNotBlank() }?.let { route ->
            // A route that is not in this build's graph is a stale link, not a reason to crash:
            // `navigate` throws IllegalArgumentException for an unknown destination, and this route
            // arrives from outside the app (an update notification written by an older version, a
            // debug probe). Falling through to the home screen is what a link to nowhere should do.
            val navigated =
                runCatching {
                    navController.navigate(route) {
                        launchSingleTop = true
                    }
                }.isSuccess
            intent.removeExtra("navigate_to")
            if (navigated) return
        }
        val dataUri = intent.data
        if (isBackupUri(dataUri)) {
            pendingBackupRestoreUri = dataUri
            return
        }
        if (matchesMusicRecognition(intent.action)) {
            navController.openMusicRecognition()
            return
        }
        if (intent.action == ACTION_AOD_MODE) {
            requestAodMode()
            return
        }
        if (intent.action == "android.media.action.MEDIA_PLAY_FROM_SEARCH") {
            val query =
                (
                    intent.getStringExtra("query")
                        ?: intent.getStringExtra(Intent.EXTRA_TEXT)
                        ?: intent.getStringExtra("android.intent.extra.TITLE")
                        ?: ""
                ).trim()
            if (query.isNotBlank()) {
                pendingVoiceSearchQuery = query
                startMusicServiceSafely()
                playPendingVoiceSearchIfReady()
            }
            return
        }
        if (handleExternalAudioIntent(intent)) {
            return
        }
        // SpotiFLAC auth-needed: raise the passive notice instead of navigating.
        // This branch used to push the Audio Sources screen, so anything that sent this
        // deep link could swap the screen out from under the user mid-track. Verification is
        // the user's call now; the Audio Sources screen still consumes the request and runs
        // the challenge when the user opens it themselves.
        val uri = intent.data
        if (uri?.scheme == "hush" && uri.host == "spotiflac-grant" && uri.getQueryParameter("auth") == "needed") {
            intent.data = null
            app.hush.music.spotiflac.SpotiFLACVerificationRequest.request(uri.getQueryParameter("ext"))
            return
        }
        // SpotiFLAC session-grant callback from inline WebView redirect
        val spotiflacGrant = extractSpotiFLACGrant(intent)
        if (spotiflacGrant != null) {
            handleSpotiFLACGrant(spotiflacGrant)
            intent.data = null
            return
        }
        handleDeepLinkIntent(intent, navController)
    }

    private fun extractSpotiFLACGrant(intent: Intent): String? {
        val uri = intent.data ?: return null
        timber.log.Timber.tag("SpotiFLACSettings").d("extractSpotiFLACGrant: scheme=${uri.scheme} host=${uri.host} uri=$uri")
        timber.log.Timber.tag("SpotiFLACSettings").d("extractSpotiFLACGrant: query=${uri.encodedQuery} fragment=${uri.fragment}")
        // Match both hush://spotiflac-grant (new) and spotiflac://session-grant (legacy)
        val isHushGrant = uri.scheme == "hush" && uri.host == "spotiflac-grant"
        val isLegacyGrant = uri.scheme == "spotiflac" && uri.host == "session-grant"
        if (!isHushGrant && !isLegacyGrant) return null
        val grant = uri.getQueryParameter("grant")
        if (!grant.isNullOrBlank()) {
            timber.log.Timber.tag("SpotiFLACSettings").d("extractSpotiFLACGrant: found grant param len=${grant.length} first50=${grant.take(50)}")
            return grant
        }
        // Fallback: check all parameters
        for (key in uri.queryParameterNames) {
            val value = uri.getQueryParameter(key)
            timber.log.Timber.tag("SpotiFLACSettings").d("extractSpotiFLACGrant: param $key=${value?.take(50)}")
            if (value != null && value.length > 30 && key != "cb_version" && key != "state") {
                timber.log.Timber.tag("SpotiFLACSettings").d("extractSpotiFLACGrant: using param $key as grant (len=${value.length})")
                return value
            }
        }
        // Last fallback
        val fullUri = uri.toString()
        val idx = fullUri.indexOf("grant=")
        if (idx > 0) {
            val afterGrant = fullUri.substring(idx + 6)
            return afterGrant.substringBefore("&").takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun handleSpotiFLACGrant(grant: String) {
        timber.log.Timber.tag("SpotiFLACSettings").w("Deep link grant received (len=${grant.length})")
        lifecycleScope.launch {
            // Two independent consumers hold gateway sessions: the extension runtime that
            // resolves SpotiFLAC playback, and the relay session this app has always used.
            // A grant is spent by the first exchange that redeems it, so the extension runtime
            // goes first - it is what a verification run exists for - and the relay exchange
            // below stays as-is for the older path.
            //
            // This is the route a grant takes when the challenge was solved in a real browser
            // (the only route on a device whose embedded WebView is too old to run Cloudflare's
            // check), so a deep link that never reached the runtime would leave the source
            // looking unverified however many times it was checked.
            try {
                val bridge = app.hush.music.spotiflac.SpotiFLACNativeRuntimeBridgeHolder.instance
                if (bridge != null) {
                    val primary =
                        app.hush.music.spotiflac.SpotiFLAutoVerifier.active.value
                            ?: app.hush.music.spotiflac.SpotiFLACVerificationRequest.pending.value
                    // A grant redeems only for the extension whose challenge raised it, and the
                    // queue's active source is not necessarily that extension (a deep link can
                    // arrive while another source's challenge is pending), so the owner is asked
                    // for explicitly before falling back to the queued source.
                    val owner = withContext(Dispatchers.IO) {
                        bridge.pendingRuntimeAuth()?.extensionId
                            ?: primary?.let { bridge.challengeOwnerFor(it) }
                    } ?: primary
                    // An empty target list is the "owner unknown" case, which is the only one where
                    // more than one extension is tried.
                    val targets = owner?.trim()?.takeIf { it.isNotEmpty() }?.let { listOf(it) }
                        ?: emptyList()
                    withContext(Dispatchers.IO) { bridge.deliverGrant(grant, targets) }
                    val verified = owner?.takeIf { bridge.isSourceVerified(it) }
                    if (verified != null) {
                        // Report it exactly as the in-app surfaces do: without this the source stays
                        // at the head of its queue waiting for a challenge that already happened, and
                        // a track parked for it is never resumed.
                        app.hush.music.spotiflac.SpotiFLAutoVerifier.notifyVerified(verified)
                    }
                }
            } catch (e: Exception) {
                timber.log.Timber.tag("SpotiFLACSettings").e(e, "Extension runtime grant delivery failed")
            }
            // There is nothing to exchange for Hush itself. A grant that arrives here belongs to
            // the extension that raised the challenge, and Hush's own gateway session is gone - it
            // signed as Hush's client version, so it could serve no extension (see
            // SpotiFLACInstallIdentity), and the gateway no longer hands one out. This block used to
            // offer the grant to the relay whenever a stale challenge URL was still in prefs, which
            // is the unnecessary request the gateway answered HTTP 403 while the verification had in
            // fact succeeded.
        }
    }

    private fun handleExternalAudioIntent(intent: Intent): Boolean {
        val incomingUris =
            buildList {
                intent.data?.let(::add)
                when (intent.action) {
                    Intent.ACTION_SEND -> {
                        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
                    }

                    Intent.ACTION_SEND_MULTIPLE -> {
                        addAll(
                            IntentCompat
                                .getParcelableArrayListExtra(
                                    intent,
                                    Intent.EXTRA_STREAM,
                                    Uri::class.java,
                                ).orEmpty(),
                        )
                    }
                }
            }.distinct()

        if (incomingUris.isEmpty()) return false

        val fallbackMimeType = intent.type
        val playableUris =
            incomingUris.filter { uri ->
                val mimeType = contentResolver.getType(uri)
                mimeType.isAudioMimeType() || fallbackMimeType.isAudioMimeType() || uri.hasAudioExtension()
            }
        if (playableUris.isEmpty()) return false

        pendingDeepLinkQueue = ListQueue(items = playableUris.map(::toExternalAudioMediaItem))
        startMusicServiceSafely()
        playPendingDeepLinkQueueIfReady()
        return true
    }

    private fun toExternalAudioMediaItem(uri: Uri): MediaItem {
        val mediaId = uri.toString()
        val title = resolveExternalAudioTitle(uri)
        val metadata =
            app.hush.music.models.MediaMetadata(
                id = mediaId,
                title = title,
                artists = emptyList(),
                duration = -1,
            )
        return MediaItem
            .Builder()
            .setMediaId(mediaId)
            .setUri(uri)
            .setTag(metadata)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata
                    .Builder()
                    .setTitle(title)
                    .setIsPlayable(true)
                    .setMediaType(MEDIA_TYPE_MUSIC)
                    .build(),
            ).build()
    }

    private fun resolveExternalAudioTitle(uri: Uri): String {
        val displayName =
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (columnIndex >= 0 && cursor.moveToFirst()) cursor.getString(columnIndex) else null
                }
            }.getOrNull()
        return displayName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringBefore('?')
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.unknown)
    }

    private fun String?.isAudioMimeType(): Boolean = this?.startsWith("audio/", ignoreCase = true) == true

    private fun Uri.hasAudioExtension(): Boolean {
        val extension = MimeTypeMap.getFileExtensionFromUrl(toString()).orEmpty()
        val normalized = extension.lowercase(Locale.US)
        return normalized in setOf("aac", "flac", "m4a", "mp3", "ogg", "opus", "wav", "webm")
    }

    private fun handleDeepLinkIntent(
        intent: Intent,
        navController: NavHostController,
    ) {
        val uri = intent.data ?: intent.extras?.getString(Intent.EXTRA_TEXT)?.toUri() ?: return
        val coroutineScope = lifecycleScope

        val authority = uri.authority?.lowercase()
        val isHushScheme =
            uri.scheme.equals("hush", ignoreCase = true) ||
                uri.scheme.equals("archivetune", ignoreCase = true)
        if (isHushScheme && authority == "together") {
            pendingTogetherJoinLink = uri.toString()
            startMusicServiceSafely()
            joinPendingTogetherIfReady()
            return
        }

        if (isHushScheme && authority == "login") {
            navController.navigate(buildLoginRoute(uri.getQueryParameter(LOGIN_URL_ARGUMENT)))
            return
        }

        // Debug-only route entry: `adb shell am start -a android.intent.action.VIEW -d
        // "hush://route?to=settings/player/audio_sources"`. Several screens (the SpotiFLAC
        // source rows, among them) are otherwise reachable only by hand, which makes an
        // automated check of them impossible on a device with no one in front of it - and a car
        // head unit is exactly that device. The `hush://route` filter lives in the debug
        // manifest, so this is unreachable in a build that ships; the guard is here as well so
        // the route can never be driven even if that filter is ever copied forward.
        if (isHushScheme && authority == "route") {
            if (BuildConfig.DEBUG) {
                uri.getQueryParameter("to")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { route ->
                        runCatching {
                            navController.navigate(route) { launchSingleTop = true }
                        }.onFailure { reportException(it) }
                    }
            }
            return
        }

        when (val path = uri.pathSegments.firstOrNull()) {
            "playlist" -> {
                uri.getQueryParameter("list")?.let { playlistId ->
                    if (playlistId.startsWith("OLAK5uy_")) {
                        coroutineScope.launch {
                            YouTube
                                .albumSongs(playlistId)
                                .onSuccess { songs ->
                                    songs.firstOrNull()?.album?.id?.let { browseId ->
                                        navController.navigate("album/$browseId")
                                    }
                                }.onFailure { reportException(it) }
                        }
                    } else {
                        navController.navigate("online_playlist/$playlistId")
                    }
                }
            }

            "browse" -> {
                uri.lastPathSegment?.let { browseId ->
                    navController.navigate("album/$browseId")
                }
            }

            "channel", "c" -> {
                uri.lastPathSegment?.let { artistId ->
                    navController.navigate("artist/$artistId")
                }
            }

            else -> {
                val videoId =
                    when {
                        path == "watch" -> uri.getQueryParameter("v")
                        uri.host == "youtu.be" -> uri.pathSegments.firstOrNull()
                        else -> null
                    }
                val playlistId = uri.getQueryParameter("list")
                val shouldShufflePlaylist = uri.requestsShuffledPlayback()

                videoId?.let { vid ->
                    coroutineScope.launch {
                        val result =
                            withContext(Dispatchers.IO) {
                                YouTube.queue(listOf(vid), playlistId)
                            }

                        result
                            .onSuccess { queued ->
                                val mediaItem =
                                    queued.firstOrNull { it.id == vid }?.toMediaItem()
                                        ?: queued.firstOrNull()?.toMediaItem()
                                        ?: MediaItem
                                            .Builder()
                                            .setMediaId(vid)
                                            .setUri(vid)
                                            .setCustomCacheKey(vid)
                                            .build()
                                pendingDeepLinkQueue = ListQueue(items = listOf(mediaItem))
                                startMusicServiceSafely()
                                playPendingDeepLinkQueueIfReady()
                            }.onFailure {
                                reportException(it)
                            }
                    }
                    return
                }

                if (path == "watch" && !playlistId.isNullOrBlank()) {
                    coroutineScope.launch {
                        val result =
                            withContext(Dispatchers.IO) {
                                YouTube.playlist(playlistId)
                            }

                        result
                            .onSuccess { playlistPage ->
                                val endpoint =
                                    when {
                                        shouldShufflePlaylist -> {
                                            playlistPage.playlist.shuffleEndpoint
                                                ?: playlistPage.playlist.playEndpoint
                                        }

                                        else -> {
                                            playlistPage.playlist.playEndpoint
                                                ?: playlistPage.playlist.shuffleEndpoint
                                        }
                                    }

                                endpoint?.let {
                                    pendingDeepLinkQueue = YouTubeQueue.playlist(it)
                                    startMusicServiceSafely()
                                    playPendingDeepLinkQueueIfReady()
                                } ?: navController.navigate("online_playlist/$playlistId")
                            }.onFailure {
                                reportException(it)
                            }
                    }
                }
            }
        }
    }

    private fun android.net.Uri.requestsShuffledPlayback(): Boolean {
        val value = getQueryParameter("shuffle")?.trim()?.lowercase(Locale.US) ?: return false
        return value == "1" || value == "true"
    }

    private fun startMusicServiceSafely() {
        runCatching { startService(Intent(this, app.hush.music.playback.MusicService::class.java)) }
            .onFailure { reportException(it) }
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun setSystemBarAppearance(isDark: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView.rootView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            window.statusBarColor =
                (if (isDark) Color.Transparent else Color.Black.copy(alpha = 0.2f)).toArgb()
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            window.navigationBarColor =
                (if (isDark) Color.Transparent else Color.Black.copy(alpha = 0.2f)).toArgb()
        }
    }

    @Composable
    private fun BackupRestoreFromIntentDialog(
        uri: Uri,
        viewModel: BackupRestoreViewModel = hiltViewModel(),
    ) {
        var selected by remember { mutableStateOf(BackupCategory.entries.toSet()) }

        AlertDialog(
            onDismissRequest = { pendingBackupRestoreUri = null },
            icon = { Icon(painterResource(R.drawable.restore), null) },
            title = { Text(stringResource(R.string.restore_options_title)) },
            text = {
                Column {
                    BackupCategory.entries.forEach { category ->
                        val isChecked = category in selected
                        val labelRes =
                            when (category) {
                                BackupCategory.LIBRARY -> R.string.backup_category_library
                                BackupCategory.ACCOUNT -> R.string.backup_category_account
                                BackupCategory.SETTINGS -> R.string.backup_category_settings
                            }
                        val descRes =
                            when (category) {
                                BackupCategory.LIBRARY -> R.string.backup_category_library_desc
                                BackupCategory.ACCOUNT -> R.string.backup_category_account_desc
                                BackupCategory.SETTINGS -> R.string.backup_category_settings_desc
                            }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = Color.Transparent,
                            onClick = {
                                selected = if (isChecked) selected - category else selected + category
                            },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 72.dp)
                                    .padding(horizontal = 4.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(labelRes),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = stringResource(descRes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                androidx.compose.material3.Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + category else selected - category
                                    },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val uri = pendingBackupRestoreUri ?: return@TextButton
                        pendingBackupRestoreUri = null
                        viewModel.restore(this@MainActivity, uri, selected)
                    },
                    enabled = selected.isNotEmpty(),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.action_restore))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingBackupRestoreUri = null },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    companion object {
        const val ACTION_SEARCH = "app.hush.music.action.SEARCH"
        const val ACTION_LIBRARY = "app.hush.music.action.LIBRARY"
        private const val LEGACY_ACTION_PREFIX = "moe.rukamori.archivetune.action."

        fun matchesShortcutSearch(action: String?): Boolean =
            action == ACTION_SEARCH || action == "${LEGACY_ACTION_PREFIX}SEARCH"

        fun matchesShortcutLibrary(action: String?): Boolean =
            action == ACTION_LIBRARY || action == "${LEGACY_ACTION_PREFIX}LIBRARY"

        fun matchesMusicRecognition(action: String?): Boolean =
            action == ACTION_MUSIC_RECOGNITION || action == "${LEGACY_ACTION_PREFIX}MUSIC_RECOGNITION"
    }
}

val LocalDatabase = staticCompositionLocalOf<MusicDatabase> { error("No database provided") }
val LocalPlayerConnection =
    staticCompositionLocalOf<PlayerConnection?> { error("No PlayerConnection provided") }
val LocalPlayerAwareWindowInsets =
    compositionLocalOf<WindowInsets> { error("No WindowInsets provided") }
val LocalMiniPlayerBottomPadding = compositionLocalOf { 0.dp }

/**
 * How tall the mini player is drawn, so a screen that leaves room for it and the thing itself can
 * never disagree. Provided by `MainActivity`; [MiniPlayerHeight] is the floor when nothing provides
 * it (a preview, or a screen composed on its own).
 */
val LocalMiniPlayerHeight = compositionLocalOf { MiniPlayerHeight }
val LocalDownloadUtil = staticCompositionLocalOf<DownloadUtil> { error("No DownloadUtil provided") }
val LocalSyncUtils = staticCompositionLocalOf<SyncUtils> { error("No SyncUtils provided") }

private const val TopAppBarIconButtonContainerAlpha = 0.48f

/**
 * One action in the top bar, in whichever of the two shapes the skin asks for.
 *
 * The difference is the whole of what a header adapts to: Hush's buttons are rounded squares of
 * translucent surface, and every parent's are plain Material `IconButton`s - a container behind an
 * icon that already has a 48dp touch target reads as a chip, which is not what any of those apps
 * draws in its app bar. One call site each, rather than a branch per button, so a header cannot end
 * up half converted.
 */
@Composable
private fun HeaderActionButton(
    branded: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (branded) {
        TranslucentTopAppBarIconButton(onClick = onClick, content = content)
    } else {
        IconButton(onClick = onClick, content = content)
    }
}

/** The app's own mark, wherever a header leads with it. Size is the caller's: 36dp in the brand bar, 32dp when it leads a titled one. */
@Composable
private fun BrandHeaderMark(size: Dp) {
    Image(
        painter = painterResource(R.drawable.hush_logo_mark),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier =
            Modifier
                .size(size)
                .padding(end = 4.dp),
    )
}

/**
 * The greeting line of the SimpMusic app theme's header, read off the clock.
 *
 * Four bands rather than one greeting, because that is what the app whose header this is does: its
 * bar says Good morning, Good afternoon, Good evening or Good night depending on the hour. The hour
 * comes from the device's own clock and not from a captured value, so the line is right when a
 * session is resumed in the morning from one started at night.
 */
@Composable
private fun skinGreetingRes(): Int {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..11 -> R.string.app_skin_greeting_morning
        in 12..16 -> R.string.app_skin_greeting_afternoon
        in 17..21 -> R.string.app_skin_greeting_evening
        else -> R.string.app_skin_greeting_night
    }
}

@Composable
private fun TranslucentTopAppBarIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        colors =
            IconButtonDefaults.iconButtonColors(
                containerColor =
                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(
                        alpha = TopAppBarIconButtonContainerAlpha,
                    ),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        content = content,
    )
}

@Composable
private fun OnlineSearchSortMenu(
    selectedSort: OnlineSearchSort,
    onSortSelected: (OnlineSearchSort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options =
        remember {
            listOf(
                OnlineSearchSort.DEFAULT,
                OnlineSearchSort.VIEWS,
            )
        }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(R.drawable.filter_alt),
                contentDescription = null,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { sort ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text =
                                stringResource(
                                    when (sort) {
                                        OnlineSearchSort.DEFAULT -> R.string.default_style
                                        OnlineSearchSort.VIEWS -> R.string.views
                                    },
                                ),
                        )
                    },
                    onClick = {
                        expanded = false
                        onSortSelected(sort)
                    },
                    leadingIcon = {
                        if (sort == selectedSort) {
                            Icon(
                                painter = painterResource(R.drawable.done),
                                contentDescription = null,
                            )
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                )
            }
        }
    }
}

private fun Context.isTvDevice(): Boolean {
    val isTelevisionUiMode =
        (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
    return isTelevisionUiMode ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
}