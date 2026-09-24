/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:Suppress("DEPRECATION")

package app.hush.music.playback

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.content.ContextCompat
import android.app.PendingIntent
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.database.SQLException
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaCodecList
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import app.hush.music.eq.HushEqualizerService
import app.hush.music.eq.audio.CustomEqualizerAudioProcessor
import app.hush.music.playback.AudioOutputResolver
import android.net.Uri
import android.os.Binder
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_POSITION_DISCONTINUITY
import androidx.media3.common.Player.EVENT_TIMELINE_CHANGED
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Player.STATE_IDLE
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import app.hush.music.MainActivity
import app.hush.music.R
import app.hush.music.cast.CastMediaItemResolver
import app.hush.music.cast.CastPlaybackRepository
import app.hush.music.cast.CastPlaybackRepositoryLocator
import app.hush.music.constants.AudioNormalizationKey
import app.hush.music.constants.LoudnessLevel
import app.hush.music.constants.LoudnessLevelKey
import app.hush.music.constants.AudioOffload
import app.hush.music.constants.AudioQuality
import app.hush.music.constants.AudioQualityKey
import app.hush.music.constants.AutoDownloadOnLikeKey
import app.hush.music.constants.AutoLoadMoreKey
import app.hush.music.constants.AutoSkipNextOnErrorKey
import app.hush.music.constants.AutoStartOnBluetoothKey
import app.hush.music.constants.CrossfadeDurationKey
import app.hush.music.constants.CrossfadeEnabledKey
import app.hush.music.constants.CrossfadeGaplessKey
import app.hush.music.constants.PrefetchCountKey
import app.hush.music.constants.UrlCacheRefreshIntervalKey
import app.hush.music.constants.DeviceMutePlaybackRecoveryVolumeKey
import app.hush.music.constants.EnableLastFMScrobblingKey
import app.hush.music.constants.PrimaryAudioScraper
import app.hush.music.constants.PrimaryAudioScraperKey
import app.hush.music.constants.EqualizerBandLevelsMbKey
import app.hush.music.constants.EqualizerBassBoostEnabledKey
import app.hush.music.constants.EqualizerBassBoostStrengthKey
import app.hush.music.constants.EqualizerEnabledKey
import app.hush.music.constants.EqualizerOutputGainEnabledKey
import app.hush.music.constants.EqualizerOutputGainMbKey
import app.hush.music.constants.EqualizerSelectedProfileIdKey
import app.hush.music.constants.EqualizerVirtualizerEnabledKey
import app.hush.music.constants.EqualizerVirtualizerStrengthKey
import app.hush.music.constants.HISTORY_DURATION_DEFAULT
import app.hush.music.constants.HISTORY_DURATION_MAX
import app.hush.music.constants.HISTORY_DURATION_MIN
import app.hush.music.constants.HideExplicitKey
import app.hush.music.constants.HideVideoKey
import app.hush.music.constants.HistoryDuration
import app.hush.music.constants.InnerTubeCookieKey
import app.hush.music.constants.LastFMSessionKey
import app.hush.music.constants.LastFMUseNowPlaying
import app.hush.music.constants.ListenBrainzEnabledKey
import app.hush.music.constants.ListenBrainzTokenKey
import app.hush.music.constants.MaxSongCacheSizeKey
import app.hush.music.constants.MediaSessionConstants.CommandAddToTargetPlaylist
import app.hush.music.constants.MediaSessionConstants.CommandToggleLike
import app.hush.music.constants.MediaSessionConstants.CommandToggleRepeatMode
import app.hush.music.constants.MediaSessionConstants.CommandToggleShuffle
import app.hush.music.constants.MediaSessionConstants.CommandToggleStartRadio
import app.hush.music.constants.PauseListenHistoryKey
import app.hush.music.constants.PauseOnDeviceMuteKey
import app.hush.music.constants.PermanentShuffleKey
import app.hush.music.constants.PersistentQueueKey
import app.hush.music.constants.PlayerStreamClient
import app.hush.music.constants.PlayerStreamClientKey
import app.hush.music.constants.PlayerVolumeKey
import app.hush.music.constants.RepeatModeKey
import app.hush.music.constants.ScrobbleDelayPercentKey
import app.hush.music.constants.ScrobbleDelaySecondsKey
import app.hush.music.constants.ScrobbleMinSongDurationKey
import app.hush.music.constants.ContentCountryKey
import app.hush.music.constants.ContentLanguageKey
import app.hush.music.constants.SkipSilenceKey
import app.hush.music.constants.SmartTrimmerKey
import app.hush.music.constants.StreamSourcePreferences
import app.hush.music.constants.SourcePriorityKey
import app.hush.music.constants.SpotiFLACEnabledKey
import app.hush.music.constants.SpotiFLACFallbackToYouTubeKey
import app.hush.music.constants.SpotiFLACQualityKey
import app.hush.music.constants.StopMusicOnTaskClearKey
import app.hush.music.constants.TogetherClientIdKey
import app.hush.music.constants.WakelockKey
import app.hush.music.constants.YoutubeStreamingEnabledKey
import app.hush.music.constants.WazeTargetApp
import app.hush.music.waze.WazeBridgeAutoReconnect
import app.hush.music.constants.YtmSyncKey
import app.hush.music.db.MusicDatabase
import app.hush.music.db.entities.AlbumEntity
import app.hush.music.db.entities.ArtistEntity
import app.hush.music.db.entities.Event
import app.hush.music.db.entities.FormatEntity
import app.hush.music.db.entities.containerLabel
import app.hush.music.db.entities.LyricsEntity
import app.hush.music.db.entities.RelatedSongMap
import app.hush.music.db.entities.Song
import app.hush.music.db.entities.SongEntity
import app.hush.music.di.DownloadCache
import app.hush.music.di.PlayerCache
import app.hush.music.extensions.SilentHandler
import app.hush.music.extensions.collect
import app.hush.music.extensions.collectLatest
import app.hush.music.extensions.ExtraIsMusicVideo
import app.hush.music.extensions.ExtraSpotifyTrackId
import app.hush.music.extensions.currentMetadata
import app.hush.music.models.artistsDisplayText
import app.hush.music.extensions.directorySizeBytes
import app.hush.music.extensions.findNextMediaItemById
import app.hush.music.extensions.mediaItems
import app.hush.music.extensions.metadata
import app.hush.music.extensions.setOffloadEnabled
import app.hush.music.extensions.toContinuationQueue
import app.hush.music.extensions.toEnum
import app.hush.music.extensions.resolveNotificationArtworkUrl
import app.hush.music.extensions.toMediaItem
import app.hush.music.extensions.toPersistQueue
import app.hush.music.extensions.toQueue
import app.hush.music.innertube.PlaybackAuthState
import app.hush.music.innertube.VersionedOkHttpClient
import app.hush.music.innertube.YouTube
import app.hush.music.innertube.models.SongItem
import app.hush.music.innertube.models.WatchEndpoint
import app.hush.music.innertube.models.YouTubeClient
import app.hush.music.innertube.models.response.PlayerResponse
import app.hush.music.lastfm.LastFM
import app.hush.music.lyrics.LyricsHelper
import app.hush.music.lyrics.LyricsLanguageFilter
import app.hush.music.lyrics.LyricsPreloadManager
import app.hush.music.lyrics.LyricsUtils.displayLyricsText
import app.hush.music.moriextractor.HushExtractorException
import app.hush.music.moriextractor.ExtractorAudioQuality
import app.hush.music.moriextractor.StreamingExtractionManager
import app.hush.music.models.CachedUrlEntry
import app.hush.music.models.MediaMetadata
import app.hush.music.models.PersistPlaybackUrlCache
import app.hush.music.models.PersistPlayerState
import app.hush.music.models.PersistQueue
import app.hush.music.models.QueueData
import app.hush.music.models.QueueType
import app.hush.music.models.toMediaMetadata
import app.hush.music.playback.queues.EmptyQueue
import app.hush.music.playback.alarm.MusicAlarmScheduler
import app.hush.music.playback.alarm.MusicAlarmStore
import app.hush.music.playback.queues.ListQueue
import kotlin.random.Random
import app.hush.music.playback.queues.Queue
import app.hush.music.playback.queues.YouTubeQueue
import app.hush.music.playback.queues.filterBlockedArtists
import app.hush.music.playback.queues.filterExplicit
import app.hush.music.playback.queues.filterVideo
import app.hush.music.scrobbling.LastFmServiceConfig
import app.hush.music.storage.StorageFolderKind
import app.hush.music.storage.StorageLocationRepository
import app.hush.music.together.TogetherPlaybackSync
import app.hush.music.ui.screens.settings.ListenBrainzManager
import app.hush.music.utils.PreferenceStore
import app.hush.music.utils.AuthScopedCacheValue
import app.hush.music.utils.CoilBitmapLoader
import app.hush.music.utils.DeviceProfile
import app.hush.music.utils.DeviceTier
import app.hush.music.utils.NotificationArtworkLoader
import app.hush.music.utils.NetworkConnectivityObserver
import app.hush.music.utils.StreamClientUtils
import app.hush.music.utils.SyncUtils
import app.hush.music.utils.YTPlayerUtils
import app.hush.music.utils.dataStore
import app.hush.music.utils.enumPreference
import app.hush.music.utils.get
import app.hush.music.utils.getAsync
import app.hush.music.utils.isInternetAvailable
import app.hush.music.utils.isLocalMediaId
import app.hush.music.utils.isLocalPlaybackUrl
import app.hush.music.utils.isLowDataModeActive
import app.hush.music.utils.resolveEffectiveAudioQuality
import app.hush.music.utils.reportException
import app.hush.music.utils.toPlaybackAuthState
import app.hush.music.utils.retryWithoutPlaybackLoginContext
import app.hush.music.utils.potoken.BotGuardTokenGenerator
import app.hush.music.widget.LoadWidgetInsightsUseCase
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.EOFException
import java.io.FileOutputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.net.ConnectException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDateTime
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.time.Duration.Companion.seconds
import android.media.AudioAttributes as LegacyAudioAttributes

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class, UnstableApi::class)
@AndroidEntryPoint
class MusicService :
    MediaLibraryService(),
    Player.Listener,
    PlaybackStatsListener.Callback {
    @Inject
    lateinit var database: MusicDatabase

    @Inject
    lateinit var lyricsHelper: LyricsHelper

    @Inject
    lateinit var syncUtils: SyncUtils

    @Inject
    lateinit var mediaLibrarySessionCallback: MediaLibrarySessionCallback

    @Inject
    internal lateinit var loadWidgetInsightsUseCase: LoadWidgetInsightsUseCase

    @Inject
    lateinit var extensionRepoManager: app.hush.music.spotiflac.ExtensionRepositoryManager

    @Inject
    lateinit var spotiFLACMissMemo: app.hush.music.spotiflac.SpotiFLACMissMemo

    @Inject
    lateinit var spotiflacNativeRuntime: app.hush.music.spotiflac.SpotiFLACNativeRuntimeBridge

    @Inject
    lateinit var connectivityObserver: NetworkConnectivityObserver

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var lastAudioFocusState = AudioManager.AUDIOFOCUS_NONE
    private var wasPlayingBeforeAudioFocusLoss = false
    private var pauseOnDeviceMuteEnabled = false
    private var deviceMutePlaybackRecoveryVolumePercent = 0
    private var wasAutoPausedByDeviceMute = false
    private var muteRecoveryObserver: ContentObserver? = null
    private var lastDeviceMutePlaybackNoticeAtElapsedMs = 0L
    private var hasAudioFocus = false
    private var autoStartOnBluetoothEnabled = false
    private var bluetoothReceiverRegistered = false
    private val wazeCommandReceiver = WazeCommandReceiver()
    private var wazeReceiverRegistered = false
    private var pendingWazeCommands: MutableList<Intent> = mutableListOf()
    private var wazeColdStartRecoveryJob: Job? = null
    private var lastWazeMetadataUpdateTime = 0L
    private var cachedShimPackages: List<String>? = null
    private var cachedShimPackagesCheckedAt = 0L
    private var wazePositionJob: Job? = null
    private val wazeSnapshotSequence = AtomicLong(0L)
    private val wazeQueueRevision = AtomicLong(0L)
    private var wazeLikedState = false
    private var wazeLikedMediaId: String? = null
    private var wazePauseDebounceJob: Job? = null
    private val wazePauseDebounceMs = 300L
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakelockEnabled = false
    private var audioDeviceCallbackRegistered = false
    private var audioRouteRecoveryJob: Job? = null
    private var audiblePlaybackRecoveryJob: Job? = null
    private var lastAudioOutputDeviceSignature: String? = null
    private var lastAudioRouteRecoveryRealtimeMs = 0L

    private lateinit var audioOutputResolver: AudioOutputResolver

    private val audioDeviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                if (addedDevices.any { it.isSink }) onAudioOutputDeviceChanged()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
                if (removedDevices.any { it.isSink }) onAudioOutputDeviceChanged()
            }
        }

    private var scopeJob = Job()
    private var scope = CoroutineScope(Dispatchers.Main + scopeJob)
    private var ioScope = CoroutineScope(Dispatchers.IO + scopeJob)
    private val binder = MusicBinder()
    private var hasBoundClients = false
    private var idleStopJob: Job? = null
    private var urlRefreshJob: Job? = null

    /**
     * The in-flight SpotiFLAC lookahead.
     *
     * Kept so a skip can replace it: the lookahead now covers as many tracks as the user
     * asked to prefetch, and three quick skips must not stack three chains of downloads on
     * top of the track that is playing.
     */
    private var spotiflacPrefetchJob: Job? = null
    /** How often (ms) to check whether the current stream URL needs proactive refresh. */
    private val URL_REFRESH_POLL_INTERVAL_MS = 30_000L

    private lateinit var connectivityManager: ConnectivityManager
    val waitingForNetworkConnection = MutableStateFlow(false)
    private val isNetworkConnected = MutableStateFlow(false)

    private val audioQuality by enumPreference(
        this,
        AudioQualityKey,
        app.hush.music.constants.AudioQuality.AUTO,
    )
    private val preferredStreamClient by enumPreference(
        this,
        PlayerStreamClientKey,
        PlayerStreamClient.ANDROID_VR,
    )
    private val activeStreamClient: PlayerStreamClient
        get() =
            if (preferredStreamClient == PlayerStreamClient.ARCHIVETUNE_EXTRACTOR &&
                app.hush.music.BuildConfig.EXTRACTOR_BEARER.isBlank()
            ) {
                PlayerStreamClient.ANDROID_VR
            } else {
                preferredStreamClient
            }
    private val playbackUrlCache = ConcurrentHashMap<String, AuthScopedCacheValue>()
    private val playbackUrlPrefetchInFlight = ConcurrentHashMap<String, Deferred<AuthScopedCacheValue?>>()
    private val playbackUrlResolutionInFlight = ConcurrentHashMap<String, Deferred<AuthScopedCacheValue>>()
    private val playbackUrlPrefetchSemaphore = kotlinx.coroutines.sync.Semaphore(4)
    private val extractorPlaybackUrlCache = ConcurrentHashMap<String, AuthScopedCacheValue>()
    private val remotePlaybackTrackingUrlCache = ConcurrentHashMap<String, String>()
    private val contentLengthCache = ConcurrentHashMap<String, Long>()
    private val streamingExtractionManager by lazy {
        StreamingExtractionManager(
            bearerToken = app.hush.music.BuildConfig.EXTRACTOR_BEARER,
        )
    }
    private val mediaOkHttpClientHolder =
        VersionedOkHttpClient(
            versionProvider = YouTube::okHttpNetworkVersion,
            baseBuilder = YouTube::newOkHttpClientBuilder,
        )
    private val extractorMediaOkHttpClientHolder =
        VersionedOkHttpClient(
            versionProvider = YouTube::okHttpNetworkVersion,
            baseBuilder = YouTube::newOkHttpClientBuilder,
        )
    private val mediaOkHttpClient: OkHttpClient
        get() =
            mediaOkHttpClientHolder.get {
                proxy(YouTube.streamOkHttpProxy)
                followRedirects(true)
                followSslRedirects(true)
                connectTimeout(30, TimeUnit.SECONDS)
                readTimeout(30, TimeUnit.SECONDS)
                addInterceptor { chain ->
                    val request = chain.request()
                    val host = request.url.host
                    val isYouTubeMediaHost =
                        host.endsWith("googlevideo.com") ||
                            host.endsWith("googleusercontent.com") ||
                            host.endsWith("youtube.com") ||
                            host.endsWith("youtube-nocookie.com") ||
                            host.endsWith("ytimg.com")

                    if (!isYouTubeMediaHost) return@addInterceptor chain.proceed(request)

                    val requestProfile = StreamClientUtils.resolveRequestProfile(request.url)
                    chain.proceed(
                        StreamClientUtils
                            .applyRequestProfile(
                                request.newBuilder(),
                                requestProfile,
                            ).build(),
                    )
                }
            }
    private val extractorMediaOkHttpClient: OkHttpClient
        get() =
            extractorMediaOkHttpClientHolder.get {
                proxy(Proxy.NO_PROXY)
                followRedirects(true)
                followSslRedirects(true)
                connectTimeout(30, TimeUnit.SECONDS)
                readTimeout(30, TimeUnit.SECONDS)
                addInterceptor { chain ->
                    val request =
                        chain
                            .request()
                            .newBuilder()
                            .header(
                                "User-Agent",
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
                            ).header("Accept", "*/*")
                            .build()
                    chain.proceed(request)
                }
            }

private var currentQueue: Queue = EmptyQueue
var queueTitle: String? = null
var originalQueueSize: Int = 0
    private val persistentStateLock = Any()
    private val persistentSaveGeneration = AtomicLong(0L)
    private val playQueueGeneration = AtomicLong(0L)
    private val infiniteQueueGeneration = AtomicLong(0L)

    @Volatile
    private var isRestoringPersistentState = false

    @Volatile
    private var isHydratingRestoredQueue = false
    private val restoredQueueHydrationGeneration = AtomicLong(0L)
    private var restoredQueueBackfillJob: Job? = null

    @Volatile
    private var suppressAutoPlayback = false
    @Volatile
    private var hideMusicVideos = false
    @Volatile
    private var lastLoginRecoveryPrompt: Pair<String, Long>? = null
    private val playbackStreamRecoveryTracker = PlaybackStreamRecoveryTracker()

    /**
     * Media ids already checked against the downloads folder for an unrecorded file.
     *
     * The check reads a directory and a database row, so it happens once per media id per
     * process rather than on every open. A miss is safe to remember: a file only ever appears
     * there through a download, which records itself as it finishes.
     */
    private val downloadAdoptionChecked =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val queuePageLoadMutex = Mutex()
    private val likeToggleMutex = Mutex()
    private var nextHistorySessionToken = 0L
    private var currentHistorySessionToken = 0L
    private var currentHistoryMediaId: String? = null
    private var currentHistoryAccumulatedPlayMs = 0L
    private var currentHistoryStartedAtElapsedMs: Long? = null
    private var currentHistoryEventId: Long? = null
    private var currentHistoryRemoteRegistered = false
    private var currentHistoryImmediateAttempted = false
    private var currentHistorySessionQueued = false
    private var historyThresholdJob: Job? = null
    private val pendingHistoryFinalizations = ConcurrentHashMap<String, MutableList<PendingHistoryFinalization>>()
    private val historyRecordingJobs = ConcurrentHashMap<Long, kotlinx.coroutines.Deferred<ImmediateHistoryResult>>()

    val currentMediaMetadata = MutableStateFlow<app.hush.music.models.MediaMetadata?>(null)
    val activePlaybackClientLabel = MutableStateFlow<String?>(null)

    /**
     * Live progress of a SpotiFLAC track being fetched for playback, or null when
     * the current track is streaming or already cached. Published here (like
     * [activePlaybackClientLabel]) so the player can show real download progress
     * instead of an unexplained spinner.
     */
    val activeDownloadProgress = MutableStateFlow<app.hush.music.utils.PlaybackDownloadProgress?>(null)
    val activeAudioDevice get() = audioOutputResolver.activeAudioDevice

    fun refreshActiveDevice() = audioOutputResolver.refresh()
    val queueRestoreCompleted = MutableStateFlow(false)
    val infiniteQueueLoading = MutableStateFlow(false)
    private val playerInitialized = MutableStateFlow(false)
    private val currentSong =
        currentMediaMetadata
            .flatMapLatest { mediaMetadata ->
                database.song(mediaMetadata?.id)
            }.flowOn(Dispatchers.IO)
            .stateIn(scope, SharingStarted.Lazily, null)
    /**
     * The format most recently resolved or served for a track, keyed by mediaId.
     *
     * The player's codec row is this value when it exists, and the database row otherwise.
     * Without the override the row described the format of whatever engine last resolved the
     * track, so a track whose stored row came from a YouTube play kept reading as a ~5 MB
     * WebM while a 30 MB SpotiFLAC FLAC was decoding - which looks exactly like the app
     * still playing from YouTube.
     */
    private val publishedFormatEntity = MutableStateFlow<Pair<String, FormatEntity>?>(null)

    /**
     * The format row the player draws, and the one source of it for every surface.
     *
     * The stored row is only consulted when it could describe what this device is serving. A queue
     * restored from a previous session keeps its media ids, so each item still carried the row from
     * whatever engine resolved it *then* - and the row was drawn before this session had resolved
     * anything, which on device read as `FLAC • 1411 kbps • 20 MB` for a track with no cached file at
     * all. See [ServedFormatClaim].
     *
     * The source label is part of the inputs, not just the media id: for a streamed YouTube track the
     * label is what says a stream is what is playing, and it is published a moment *after* the item
     * changes. Without it the row was decided while nothing was known and then never revisited, which
     * is how the codec row disappeared for the duration of an ordinary YouTube track.
     *
     * The last input is the audio Media3 is decoding. It is the fallback of last resort: when the
     * stored row cannot be backed and nothing was published this session, the player describes what is
     * actually being decoded rather than leaving the codec line empty. It is keyed by media id, so a
     * row belonging to the track just left cannot describe the one now playing.
     */
    private val decodedAudioFormat = MutableStateFlow<Pair<String, FormatEntity>?>(null)

    val currentFormatRow: Flow<FormatRow?> =
        combine(
            currentMediaMetadata,
            publishedFormatEntity,
            activePlaybackClientLabel,
            decodedAudioFormat,
        ) { mediaMetadata, published, _, decoded ->
            FormatRowInputs(
                mediaId = mediaMetadata?.id,
                published = published,
                decoded = decoded,
            )
        }
            .flatMapLatest { inputs ->
                val mediaId = inputs.mediaId
                val fresh = inputs.published?.takeIf { it.first == mediaId }?.second
                val decoded = inputs.decoded?.takeIf { it.first == mediaId }?.second
                if (fresh != null) {
                    // A resolve running first does not make the audio a stream: a downloaded or
                    // SpotiFLAC-cached track is served straight off disk *after* that resolve, so the
                    // row's evidence is the file. Measured on device: a track Hush logged as
                    // `serving downloaded file for mediaId=W-KuIsdWexk` read `codec from this
                    // session's resolve` over `(live stream)`, which is the one thing this line is
                    // supposed to make impossible to say.
                    flowOf(FormatRow(fresh, servedRowSource(storedBytes(mediaId.orEmpty()))))
                } else {
                    storedFormatForUi(mediaId, decoded)
                }
            }.flowOn(Dispatchers.IO)

    /**
     * The codec row's format alone, for callers that do not draw the provenance.
     *
     * Derived from [currentFormatRow] rather than computed beside it: two flows deciding the same row
     * would eventually disagree, and audio normalisation reads this one.
     */
    val currentFormat: Flow<FormatEntity?> = currentFormatRow.map { it?.format }

    /** The three rows that can describe the current track, kept named rather than destructured. */
    private data class FormatRowInputs(
        val mediaId: String?,
        val published: Pair<String, FormatEntity>?,
        val decoded: Pair<String, FormatEntity>?,
    )

    /**
     * The stored row for [mediaId], or no row at all when it would describe bytes that are not here.
     *
     * Every store is checked because the row is per media id and the stores are not interchangeable -
     * and a false positive here is the failure this exists to stop. The row's own claim takes part in
     * the decision too: a row that says lossless may only be backed by bytes that could be lossless,
     * so a stale `FLAC` row cannot ride on the streamed YouTube copy of the same track.
     */
    private fun storedFormatForUi(
        mediaId: String?,
        decoded: FormatEntity?,
    ): Flow<FormatRow?> {
        val id = mediaId?.trim().orEmpty()
        if (id.isEmpty()) return flowOf(null)
        // A scanned song's bytes are the file the scanner wrote the row from, so it always stands.
        if (id.isLocalMediaId()) {
            return database
                .format(id)
                .map { row ->
                    row?.let { FormatRow(it, FormatRowSource.DEVICE_FILE) }
                        ?: decodedRowFor(id, null, decoded)?.let { FormatRow(it, FormatRowSource.DECODED_AUDIO) }
                }
        }
        return database
            .format(id)
            .map { row ->
                val source = row?.let { storedRowSource(id, it) }
                if (row != null && source != null) {
                    FormatRow(row, source)
                } else {
                    decodedRowFor(id, row, decoded)?.let { FormatRow(it, FormatRowSource.DECODED_AUDIO) }
                }
            }
            .flowOn(Dispatchers.IO)
    }

    /**
     * The decoded audio's row for [mediaId], or null when Media3 has not described it yet.
     *
     * The refused row's loudness is carried over rather than dropped: loudness is a property of the
     * *track*, and it is what the audio-normalisation pass reads. Without this the fallback would
     * silently turn normalisation off for the very tracks whose rows were refused.
     */
    private fun decodedRowFor(
        mediaId: String,
        refusedRow: FormatEntity?,
        decoded: FormatEntity?,
    ): FormatEntity? {
        val row = decoded?.takeIf { it.id == mediaId } ?: return null
        val merged =
            row.copy(
                loudnessDb = row.loudnessDb ?: refusedRow?.loudnessDb,
                perceptualLoudnessDb = row.perceptualLoudnessDb ?: refusedRow?.perceptualLoudnessDb,
            )
        // Logged once per distinct row: this flow recomposes with every source-label change, and a
        // reader chasing a missing codec line needs to know it came from the decoder instead.
        val description =
            "${merged.containerLabel()} ${merged.codecs} ${merged.bitrate}bps sr=${merged.sampleRate}"
        if (decodedRowLogKey != "$mediaId|$description") {
            decodedRowLogKey = "$mediaId|$description"
            app.hush.music.spotiflac.SpotiFLACDiag.log(
                "codec row for mediaId=$mediaId comes from the decoded audio: $description",
            )
        }
        return merged
    }

    @Volatile private var decodedRowLogKey: String = ""

    /**
     * The source the stored row for [mediaId] may be shown from, or null when it cannot be backed.
     *
     * Null is the interesting answer: the row is then replaced by the decoded audio rather than being
     * dropped, so the codec line still says what is playing.
     */
    private fun storedRowSource(
        mediaId: String,
        row: FormatEntity,
    ): FormatRowSource? {
        val bytes = storedBytes(mediaId)
        val claimsSpotiFLAC =
            row.itag == app.hush.music.spotiflac.SpotiFLACServedRow.ITAG
        val streamingNow = isStreamingNow(mediaId)
        val evidence =
            ServedFormatClaim.evidence(
                // Reaching here means nothing was published for this id.
                servedNow = false,
                trackIsLocalFile = false,
                bytes = bytes,
                rowClaimsSpotiFLAC = claimsSpotiFLAC,
                streamingNow = streamingNow,
            )
        if (ServedFormatClaim.mayShowStoredRow(evidence)) return evidence.toFormatRowSource(bytes)
        app.hush.music.spotiflac.SpotiFLACDiag.log(
            "stored codec row refused for mediaId=$mediaId (row claimed itag=${row.itag} " +
                "mime=${row.mimeType} bitrate=${row.bitrate}): " +
                ServedFormatClaim.explain(bytes, claimsSpotiFLAC),
        )
        return null
    }

    /**
     * Whether [mediaId] is being served as a network stream right now.
     *
     * Read from the published source label rather than from the player, because this runs inside a
     * database flow on a background thread and Media3's player state belongs to the application
     * thread. The label already answers the question exactly: a YouTube playback is a live stream, and
     * a SpotiFLAC one is served from the file it fetched, so a stale YouTube row cannot ride on it.
     */
    private fun isStreamingNow(mediaId: String): Boolean {
        if (currentMediaMetadata.value?.id != mediaId) return false
        val source = PlaybackSourceLabels.parse(activePlaybackClientLabel.value)
        return source.engine == PlaybackEngine.YOUTUBE &&
            source.delivery == PlaybackDelivery.LIVE_STREAM
    }

    /**
     * Which of this device's stores hold bytes for [mediaId].
     *
     * The streaming cache is asked as well as the two download stores: it is where a *streamed*
     * YouTube track's bytes live, and leaving it out would hide a row that describes real audio.
     */
    private fun storedBytes(mediaId: String): StoredBytes {
        val downloadedSpans =
            runCatching {
                if (::downloadCache.isInitialized) {
                    downloadCache.getCachedSpans(mediaId).isNotEmpty()
                } else {
                    false
                }
            }.getOrDefault(false)
        val downloadedFile = runCatching { downloadUtil.downloadedFileFor(mediaId) }.getOrNull()
        val userDownload = downloadedSpans || downloadedFile != null
        val streamingCache =
            runCatching {
                if (::playerCache.isInitialized) {
                    playerCache.getCachedSpans(mediaId).isNotEmpty()
                } else {
                    false
                }
            }.getOrDefault(false)
        return StoredBytes(
            userDownload = userDownload,
            downloadIsSpotiFLAC =
                userDownload && runCatching { downloadOriginStore.isSpotiFLACSourced(mediaId) }.getOrDefault(false),
            spotiflacCacheFile = spotiflacPlaybackFile(mediaId) != null,
            streamingCache = streamingCache,
        )
    }

    private val normalizeFactor = MutableStateFlow(1f)
    private val audioNormalizationFactorCache = ConcurrentHashMap<String, Float>()
    private val formatEntityCache = ConcurrentHashMap<String, FormatEntity>()
    private var audioNormalizationEnabled = true
    private var loudnessLevelCached = LoudnessLevel.BALANCED
    var playerVolume = MutableStateFlow(1f)
    private val audioFocusVolumeFactor = MutableStateFlow(1f)
    private var effectiveVolumeRampJob: Job? = null
    private var crossfadeEnabled = false
    private var crossfadeDurationMs = 0L
    private var crossfadeGapless = false
    private var crossfadeTriggerJob: Job? = null
    private var crossfadeJob: Job? = null
    private var secondaryCrossfadePlayer: ExoPlayer? = null
    private var secondaryEqProcessor: CustomEqualizerAudioProcessor? = null
    private var secondaryCrossfadeTarget: CrossfadeTarget? = null
    private var isCrossfading = false
    private var crossfadeHandoffInProgress = false
    private var crossfadeBaseVolume = 1f
    private var crossfadeIncomingBaseVolume = 1f
    private var crossfadeProgress = 0f
    private var crossfadePlaybackRequested = false

    data class CrossfadeLyricsState(
        val isActive: Boolean = false,
        val incomingMediaId: String? = null,
        val incomingPositionMs: Long = 0L,
    )

    val crossfadeLyricsState = MutableStateFlow(CrossfadeLyricsState())
    private var lyricsPreloadManager: LyricsPreloadManager? = null

    private val secondaryCrossfadeListener =
        object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause
                val isMediaCodecError = cause is IllegalArgumentException && cause.message?.contains("newPosition > limit") == true
                Timber.tag(TAG).w(error, "Secondary crossfade player failed")

                if (isMediaCodecError) {
                    Timber.tag(TAG).w("MediaCodec buffer conflict — falling back to gapless transition")
                    secondaryCrossfadeRetryCount.set(MAX_SECONDARY_PLAYER_RETRIES)
                }
                scope.launch {
                    cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
                }
            }
        }

    private val secondaryCrossfadeRetryCount = java.util.concurrent.atomic.AtomicInteger(0)

    private data class CrossfadeConfig(
        val enabled: Boolean,
        val durationSeconds: Float,
        val gapless: Boolean,
    )

    private data class CrossfadeTarget(
        val index: Int,
        val mediaId: String,
    )

    enum class CrossfadeTransitionState {
        IDLE,
        PREPARING_INCOMING,
        FADING,
        HANDOFF_COMPLETE,
        CLEANING_OUTGOING
    }

    private data class CrossfadeTransition(
        val transitionId: Long,
        val outgoingTrackId: String,
        val incomingTrackId: String,
        val outgoingQueueIndex: Int,
        val incomingQueueIndex: Int,
        var state: CrossfadeTransitionState = CrossfadeTransitionState.IDLE,
        val incomingStartedAtPositionMs: Long = 0L,
        var logicalHandoffCompleted: Boolean = false,
        var metadataPublished: Boolean = false,
    )

    private var activeCrossfadeTransition: CrossfadeTransition? = null
    private var crossfadeTransitionSeq = 0L

    private data class PendingHistoryFinalization(
        val sessionToken: Long,
        val eventId: Long?,
        val remoteRegistered: Boolean,
    )

    private data class ImmediateHistoryResult(
        val eventId: Long?,
        val remoteRegistered: Boolean,
    )

    private fun PlayerResponse.PlaybackTracking.remotePlaybackTrackingUrl(): String? =
        videostatsPlaybackUrl
            ?.baseUrl
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun isAppInForeground(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val appProcesses = activityManager.runningAppProcesses ?: return false
        return appProcesses.any { processInfo ->
            processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                processInfo.processName == packageName
        }
    }

    private fun promptLoginRecovery(
        mediaId: String,
        targetUrl: String,
    ) {
        if (!isAppInForeground()) return

        val now = System.currentTimeMillis()
        val lastPrompt = lastLoginRecoveryPrompt
        if (lastPrompt?.first == mediaId && now - lastPrompt.second < 10000L) return
        lastLoginRecoveryPrompt = mediaId to now

        val deepLink = Uri.parse("hush://login?url=${Uri.encode(targetUrl)}")
        val intent =
            Intent(Intent.ACTION_VIEW, deepLink, this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

        runCatching {
            startActivity(intent)
        }.onFailure {
            Timber.e(it, "Failed to open login recovery for %s", mediaId)
        }
    }

    private fun Throwable.isRequestTimeout(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is SocketTimeoutException) return true
            if (current.message?.contains("Request timeout has expired", ignoreCase = true) == true) return true
            current = current.cause
        }
        return false
    }

    private fun Throwable.isNetworkConnectionFailure(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is ConnectException || current is UnknownHostException) return true
            current = current.cause
        }
        return false
    }

    var sleepTimer: SleepTimer? = null

    @Inject
    @PlayerCache
    lateinit var playerCache: Cache

    @Inject
    @DownloadCache
    lateinit var downloadCache: Cache

    @Inject
    lateinit var downloadOriginStore: DownloadOriginStore

    @Inject
    lateinit var downloadedFileStore: app.hush.music.downloads.DownloadedFileStore

    @Inject
    lateinit var downloadUtil: DownloadUtil

    lateinit var localPlayer: ExoPlayer
        private set
    lateinit var player: Player
        private set
    private lateinit var castPlaybackRepository: CastPlaybackRepository
    private lateinit var mediaSession: MediaLibrarySession

    private var isAudioEffectSessionOpened = false
    private var openedAudioSessionId: Int? = null
    val eqCapabilities = MutableStateFlow<EqCapabilities?>(null)
    private val desiredEqSettings =
        MutableStateFlow(
            EqSettings(
                enabled = false,
                bandLevelsMb = emptyList(),
                outputGainEnabled = false,
                outputGainMb = 0,
                bassBoostEnabled = false,
                bassBoostStrength = 0,
                virtualizerEnabled = false,
                virtualizerStrength = 0,
            ),
        )

    private var audioEffectsSessionId: Int? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    val hushEqualizerService = HushEqualizerService()
    private lateinit var primaryEqProcessor: CustomEqualizerAudioProcessor

    private var scrobbleManager: app.hush.music.utils.ScrobbleManager? = null

    private lateinit var widgetUpdater: MusicServiceWidgetUpdater

    val autoAddedMediaIds: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    private var consecutivePlaybackErr = 0

    val maxSafeGainFactor = MAX_AUDIO_NORMALIZATION_FACTOR

    @Volatile
    private var hasCalledStartForeground = false

    val togetherSessionState =
        MutableStateFlow<app.hush.music.together.TogetherSessionState>(
            app.hush.music.together.TogetherSessionState.Idle,
        )
    private var togetherServer: app.hush.music.together.TogetherServer? = null
    private var togetherOnlineHost: app.hush.music.together.TogetherOnlineHost? = null
    private var togetherClient: app.hush.music.together.TogetherClient? = null
    private var togetherBroadcastJob: Job? = null
    private var togetherOnlineConnectJob: Job? = null
    private var togetherClientEventsJob: Job? = null
    private var togetherHeartbeatJob: Job? = null
    private var togetherClock: app.hush.music.together.TogetherClock? = null
    private var togetherSelfParticipantId: String? = null
    private var togetherAuthorityParticipantId: String? = null
    private var togetherLastAppliedQueueHash: String? = null
    private var togetherIsOnlineSession: Boolean = false

    @Volatile
    private var togetherApplyingRemote: Boolean = false

    @Volatile
    private var togetherSuppressEchoUntilElapsedMs: Long = 0L

    @Volatile
    private var togetherLastAppliedRoomStateSentAtElapsedMs: Long = 0L

    @Volatile
    private var togetherLastRemoteAppliedPlayWhenReady: Boolean? = null

    @Volatile
    private var togetherLastRemoteAppliedIndex: Int = -1

    @Volatile
    private var togetherLastSentControlAtElapsedMs: Long = 0L

    @Volatile
    private var togetherLastSentControlAction: app.hush.music.together.ControlAction? = null

    @Volatile
    private var togetherPendingGuestControl: TogetherPendingGuestControl? = null

    private fun isTogetherApplyingRemote(): Boolean = togetherApplyingRemote

    private val togetherHostId: String = "host"
    private val togetherParticipantNames = ConcurrentHashMap<String, String>()
    private var lastTogetherNoticeAtElapsedMs: Long = 0L
    private var lastTogetherNoticeKey: String? = null

    private data class TogetherPendingGuestControl(
        val desiredIsPlaying: Boolean? = null,
        val desiredIndex: Int? = null,
        val desiredTrackId: String? = null,
        val requestedAtElapsedMs: Long,
        val expiresAtElapsedMs: Long,
    )

    private fun showTogetherNotice(
        message: String,
        key: String? = null,
    ) {
        val now = android.os.SystemClock.elapsedRealtime()
        val normalizedKey = key ?: message
        if (normalizedKey == lastTogetherNoticeKey && now - lastTogetherNoticeAtElapsedMs < 1200L) return
        lastTogetherNoticeKey = normalizedKey
        lastTogetherNoticeAtElapsedMs = now
        scope.launch(SilentHandler) {
            Toast.makeText(this@MusicService, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTogetherParticipantNotification(
        participantName: String,
        joined: Boolean,
    ) {
        val normalizedName = participantName.trim().ifBlank { getString(R.string.together_unknown_participant) }
        val contentText =
            getString(
                if (joined) {
                    R.string.together_participant_joined_notification
                } else {
                    R.string.together_participant_left_notification
                },
                normalizedName,
            )
        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat
                .Builder(this, TOGETHER_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.small_icon)
                .setContentTitle(getString(R.string.music_together))
                .setContentText(contentText)
                .setContentIntent(contentIntent)
                .setCategory(Notification.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()

        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(TOGETHER_PARTICIPANT_NOTIFICATION_ID, notification)
        }.onFailure { error ->
            Timber.tag("Together").v(error, "Unable to show participant notification")
        }
    }

    private suspend fun getOrCreateTogetherClientId(): String {
        val existing = dataStore.getAsync(TogetherClientIdKey)?.trim().orEmpty()
        if (existing.isNotBlank()) return existing
        val generated =
            java.util.UUID
                .randomUUID()
                .toString()
        dataStore.edit { prefs -> prefs[TogetherClientIdKey] = generated }
        return generated
    }

    private fun ensureStartedAsForeground() {
        if (hasCalledStartForeground) return

        val notification =
            try {
                val contentIntent =
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )

                NotificationCompat
                    .Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.small_icon)
                    .setContentTitle(getString(R.string.music_player))
                    .setContentText(getString(R.string.app_name))
                    .setContentIntent(contentIntent)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build()
            } catch (e: Exception) {
                reportException(e)
                return
            }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            hasCalledStartForeground = true
        } catch (e: Exception) {
            reportException(e)
        }
    }

    private fun promoteToStartedService() {
        runCatching { startService(Intent(this, MusicService::class.java)) }
            .onFailure { reportException(it) }
    }

    private fun cancelIdleStop() {
        idleStopJob?.cancel()
        idleStopJob = null
    }

    private fun hasResumablePlaybackNotification(): Boolean {
        val state = player.playbackState
        return player.mediaItemCount > 0 &&
            player.currentMediaItem != null &&
            state != Player.STATE_IDLE &&
            state != Player.STATE_ENDED
    }

    private fun stopForegroundAndSelf() {
        cancelIdleStop()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                stopForeground(true)
            }
        }
        hasCalledStartForeground = false
        stopSelf()
    }

    private fun scheduleStopIfIdle() {
        if (hasBoundClients) return
        if (hasResumablePlaybackNotification()) {
            cancelIdleStop()
            promoteToStartedService()
            ensureStartedAsForeground()
            return
        }
        val togetherIdle = togetherSessionState.value is app.hush.music.together.TogetherSessionState.Idle
        if (!togetherIdle) {
            cancelIdleStop()
            return
        }

        val state = player.playbackState
        val delayMs =
            when (state) {
                Player.STATE_ENDED, Player.STATE_IDLE -> 30_000L
                else -> 60_000L
            }

        cancelIdleStop()
        idleStopJob =
            scope.launch {
                delay(delayMs)
                if (hasBoundClients) return@launch
                if (hasResumablePlaybackNotification()) return@launch
                if (togetherSessionState.value !is app.hush.music.together.TogetherSessionState.Idle) return@launch
                stopForegroundAndSelf()
            }
    }

    override fun onCreate() {
        super.onCreate()
        ensureScopesActive()

        // Restore the monotonic Waze queue revision so the shim's revision gate
        // stays valid across MusicService restarts (shim keeps its own last value).
        runCatching {
            val restored = getSharedPreferences(WAZE_PREFS, MODE_PRIVATE)
                .getLong(WAZE_PREFS_QUEUE_REVISION, 0L)
            if (restored > 0L) {
                wazeQueueRevision.set(restored + WAZE_REVISION_RESTART_STEP)
            }
        }

        // Announce this player to the Bridges installed on the device.
        //
        // A Bridge that started *before* Hush - Waze opened first, so the Bridge came up on its own
        // - has nothing to mirror and shows a dead panel. Opening Hush afterwards used to change
        // nothing, because the only things that ever told a Bridge to re-attach were Waze's own bind
        // and the Reconnect button buried in Hush's settings; the Bridge had already given up
        // waiting by then. This service coming up is exactly the event it was waiting for, and it
        // is also how the Bridges themselves start Hush, so announcing here closes the loop in both
        // directions. Asked once per service creation rather than per track, and idempotent on the
        // Bridge side (it re-binds to Waze, re-starts this service and asks for a snapshot).
        if (app.hush.music.BuildConfig.WAZE_SUPPORTED) {
            ioScope.launch {
                runCatching { WazeBridgeAutoReconnect.reconnect(this@MusicService, "music-service") }
                    .onFailure { Timber.tag(TAG).w(it, "Unable to announce Hush to the Bridges") }
            }
        }

        primaryEqProcessor = CustomEqualizerAudioProcessor().also(hushEqualizerService::addAudioProcessor)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(NotificationManager::class.java)
                nm?.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.music_player),
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
                nm?.createNotificationChannel(
                    NotificationChannel(
                        TOGETHER_NOTIFICATION_CHANNEL_ID,
                        getString(R.string.music_together),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                )
            }
        } catch (e: Exception) {
            reportException(e)
        }

        localPlayer =
            ExoPlayer
                .Builder(this)
                .setMediaSourceFactory(createMediaSourceFactory())
                .setRenderersFactory(createRenderersFactory(primaryEqProcessor))
                .setLoadControl(createPrimaryLoadControl())
                .setTrackSelector(DefaultTrackSelector(this, SafeTrackSelectionFactory()))
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setAudioAttributes(
                    playbackAudioAttributes(),
                    false,
                ).setSeekBackIncrementMs(5000)
                .setSeekForwardIncrementMs(5000)
                .setDeviceVolumeControlEnabled(true)
                .build()
                .apply {
                    addAnalyticsListener(PlaybackStatsListener(false, this@MusicService))
                    setOffloadEnabled(false)
                }
        castPlaybackRepository = CastPlaybackRepositoryLocator.get(this)
        player =
            castPlaybackRepository
                .createPlayer(
                    context = this,
                    localPlayer = localPlayer,
                    mediaItemResolver = CastMediaItemResolver(::resolveMediaItemForCast),
                ).apply {
                    addListener(this@MusicService)
                    SleepTimer(scope, this, this@MusicService).also { timer ->
                        sleepTimer = timer
                        addListener(timer)
                    }
                }
        playerInitialized.value = true
        widgetUpdater =
            MusicServiceWidgetUpdater(
                service = this,
                player = player,
                scope = scope,
                loadWidgetInsights = loadWidgetInsightsUseCase,
            )

        audioManager = runCatching {
            getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: error("AudioManager not available")
        }.getOrElse {
            // Do not stopSelf() here — Android Auto may already be binding.
            // Log and continue; audio output features will be degraded but the
            // service stays alive so MediaBrowser connections don't crash.
            Timber.e(it, "Failed to get AudioManager — audio output features disabled")
            getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            audioManager.setAllowedCapturePolicy(android.media.AudioAttributes.ALLOW_CAPTURE_BY_ALL)
        }
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock =
            powerManager
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Hush:Playback")
                ?.also { it.setReferenceCounted(false) }
        setupAudioFocusRequest()
        audioOutputResolver = AudioOutputResolver(audioManager)
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, android.os.Handler(mainLooper))
        audioDeviceCallbackRegistered = true
        lastAudioOutputDeviceSignature = currentAudioOutputDeviceSignature()
        audioOutputResolver.refresh()

        mediaLibrarySessionCallback.apply {
            toggleLike = ::toggleLike
            toggleStartRadio = ::toggleStartRadio
            toggleLibrary = ::toggleLibrary
            // Hardware and headset keys reach the session, not the app's transport buttons, so a
            // skip or a play on a fresh install has to be answered from here too.
            onEmptyPlayerTransportRequest = ::recoverQueueIfEmpty
        }
        mediaSession =
            MediaLibrarySession
                .Builder(this, player, mediaLibrarySessionCallback)
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).setBitmapLoader(CoilBitmapLoader(this, scope))
                .build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this,
                { NOTIFICATION_ID },
                CHANNEL_ID,
                R.string.music_player,
            ).apply {
                setSmallIcon(R.drawable.small_icon)
            },
        )

        updateNotification()
        player.repeatMode = REPEAT_MODE_OFF

        loadPersistentUrlCache()
        // The persistent cache may predate a source toggle. Enforce the CURRENT
        // toggles here too, so a YouTube URL restored from disk can never play
        // while YouTube is disabled — this was the "toggle ignored" bug.
        purgeCacheEntriesViolatingSourceToggles()
        startUrlCacheRefreshJob()

        if (app.hush.music.BuildConfig.WAZE_SUPPORTED) {
            wazeCommandReceiver.attachService(this)
            val wazeFilter = IntentFilter("app.hush.music.WAZE_COMMAND")
            ContextCompat.registerReceiver(
                this,
                wazeCommandReceiver,
                wazeFilter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            wazeReceiverRegistered = true
        }

        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({ controllerFuture.get() }, MoreExecutors.directExecutor())
        scope.launch(Dispatchers.IO) {
            val prefs = dataStore.data.first()
            val repeatMode = prefs[RepeatModeKey] ?: REPEAT_MODE_OFF
            val volume = (prefs[PlayerVolumeKey] ?: 1f).coerceIn(0f, 1f)
            val offload = prefs[AudioOffload] ?: false
            val crossfadePrefEnabled = prefs[CrossfadeEnabledKey] ?: false
            withContext(Dispatchers.Main) {
                player.repeatMode = repeatMode
                playerVolume.value = volume
                updateAudioOffload(offload && !crossfadePrefEnabled)
            }
        }

        connectivityManager = runCatching {
            getSystemService<ConnectivityManager>() ?: error("ConnectivityManager not available")
        }.getOrElse {
            // Do not stopSelf() here — Android Auto may already be binding.
            Timber.e(it, "Failed to get ConnectivityManager — network detection disabled")
            getSystemService<ConnectivityManager>()!!
        }

        scope.launch {
            connectivityObserver.networkStatus.collect { isConnected ->
                isNetworkConnected.value = isConnected
                if (isConnected && waitingForNetworkConnection.value) {
                    waitingForNetworkConnection.value = false
                    if (player.currentMediaItem != null && player.playWhenReady &&
                        player.playbackState == Player.STATE_IDLE
                    ) {
                        player.prepare()
                        player.play()
                    }
                }
            }
        }

        // A gateway block is the gateway refusing an *address*, and the address changes far more often
        // than the app does: a VPN switched on, Wi-Fi handing over to mobile data, a proxy enabled in
        // Internet settings. The watch (process-wide, so it also runs when no playback exists) asks the
        // gateway once per change; this is the half that can do the one thing an app with no playback
        // cannot - replay the track the block was holding.
        app.hush.music.spotiflac.SpotiFLACRouteWatch.onGatewayReachableAgain = {
            scope.launch(Dispatchers.Main) { replayHeldTrackAfterGatewayReturns() }
        }

        // The other direction: the device has moved *onto* a network the gateway refuses. The watch has
        // already recorded the block, so the next resolve skips SpotiFLAC on its own; this is for the
        // track that is already parked, which was parked because nothing could answer and now can go to
        // the other engine instead of waiting out a countdown that means nothing on this route.
        app.hush.music.spotiflac.SpotiFLACRouteWatch.onGatewayBlocked = { block ->
            scope.launch(Dispatchers.Main) { onRouteBlockedAtGateway(block.remainingMs) }
        }

        // Eagerly pre-warm playback resolution components at service startup
        // so the first playback URL resolution is fast regardless of queue state.
        // Seed from stored prefs so pre-warm isn't blocked on a network refresh.
        scope.launch(Dispatchers.IO) {
            try {
                val storedSessionId = dataStore.data.first().toPlaybackAuthState().sessionId
                val sessionId = storedSessionId ?: YouTube.currentPlaybackAuthState().sessionId
                if (!sessionId.isNullOrBlank()) {
                    BotGuardTokenGenerator.preWarm(sessionId)
                }
            } catch (e: Exception) {
                Timber.w(e, "Service-level BotGuard pre-warm failed (non-fatal)")
            }
        }

        // Pre-warm caches on background thread so first playback doesn't block on
        // SimpleCache SQLite init + directory scan.
        scope.launch(Dispatchers.IO) {
            try {
                playerCache.cacheSpace
                downloadCache.cacheSpace
            } catch (_: Exception) { }
        }

        combine(playerVolume, normalizeFactor, audioFocusVolumeFactor) { playerVolume, normalizeFactor, audioFocusVolumeFactor ->
            calculateEffectivePlayerVolume(playerVolume, normalizeFactor, audioFocusVolumeFactor)
        }.collectLatest(scope) { finalVolume ->
            updateEffectiveVolume(finalVolume)
        }

        playerVolume.debounce(1000).collect(ioScope) { volume ->
            dataStore.edit { settings ->
                settings[PlayerVolumeKey] = volume
            }
        }

        currentSong.debounce(300).collect(scope) {
            updateNotification()
        }

        currentMediaMetadata.distinctUntilChangedBy { it?.id }.collectLatest(ioScope) { mediaMetadata ->
            if (mediaMetadata == null) return@collectLatest

            val prefs = dataStore.data.first()
            val contentLanguage = prefs[ContentLanguageKey]
            val contentCountry = prefs[ContentCountryKey]
            val artist = mediaMetadata.artists.joinToString { it.name }
            val existing = database.lyrics(mediaMetadata.id).first()
            val hasInvalidStoredLyrics =
                existing?.lyrics?.let { storedLyrics ->
                    storedLyrics != LyricsEntity.LYRICS_NOT_FOUND &&
                        !LyricsLanguageFilter.isAcceptableLyrics(
                            lyrics = storedLyrics,
                            title = mediaMetadata.title,
                            artist = artist,
                            contentLanguage = contentLanguage,
                            contentCountry = contentCountry,
                        )
                } == true

            if (existing != null && existing.lyrics != LyricsEntity.LYRICS_NOT_FOUND && !hasInvalidStoredLyrics) {
                return@collectLatest
            }

            val lyrics = lyricsHelper.getLyrics(mediaMetadata)
            database.query {
                if (existing == null) {
                    insertLyricsIfAbsent(
                        id = mediaMetadata.id,
                        lyrics = lyrics,
                    )
                } else {
                    replaceLyrics(
                        id = mediaMetadata.id,
                        lyrics = lyrics,
                        source = LyricsEntity.Source.REMOTE.value,
                    )
                }
            }
        }

        dataStore.data
            .map { it[SkipSilenceKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) {
                localPlayer.skipSilenceEnabled = it
                secondaryCrossfadePlayer?.skipSilenceEnabled = it
            }

        dataStore.data
            .map { it[PauseOnDeviceMuteKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) { enabled ->
                pauseOnDeviceMuteEnabled = enabled
                if (!enabled) {
                    wasAutoPausedByDeviceMute = false
                    unregisterMuteRecoveryObserver()
                } else {
                    handleDeviceMuteStateChanged()
                }
            }

        dataStore.data
            .map { (it[DeviceMutePlaybackRecoveryVolumeKey] ?: 0).coerceIn(0, 100) }
            .distinctUntilChanged()
            .collectLatest(scope) { percent ->
                deviceMutePlaybackRecoveryVolumePercent = percent
            }

        dataStore.data
            .map { it[AutoStartOnBluetoothKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) { enabled ->
                autoStartOnBluetoothEnabled = enabled
                if (enabled) {
                    registerBluetoothReceiver()
                } else {
                    unregisterBluetoothReceiver()
                }
            }

        dataStore.data
            .map { it[YoutubeStreamingEnabledKey] ?: true }
            .distinctUntilChanged()
            .collectLatest(scope) { enabled ->
                val changed = youtubeStreamingEnabled != enabled
                youtubeStreamingEnabled = enabled
                if (changed) {
                    clearIncompatiblePlaybackCache()
                    scope.launch(Dispatchers.IO) { savePersistentUrlCache() }
                    reResolveCurrentTrackForSourceToggle()
                }
            }

        dataStore.data
            .map { it[app.hush.music.constants.SpotiFLACEnabledKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) { enabled ->
                val changed = spotiflacEnabled != enabled
                spotiflacEnabled = enabled
                if (changed) {
                    // Toggling SpotiFLAC changes routing for every cached stream.
                    // Clear the in-memory AND persistent caches so a stale YouTube
                    // URL can never leak through after the toggle (and vice versa).
                    clearIncompatiblePlaybackCache()
                    scope.launch(Dispatchers.IO) { savePersistentUrlCache() }
                }
                if (changed && !enabled) {
                    clearIncompatiblePlaybackCache()
                    // Stop asking for verifications the moment SpotiFLAC is switched off: a
                    // queued source, the passive notice and the manual notification were all
                    // raised for playback that can no longer route through SpotiFLAC, and the
                    // notification in particular asks a car user to leave the app for nothing.
                    app.hush.music.spotiflac.SpotiFLAutoVerifier.disableForPreferenceChange()
                } else if (enabled) {
                    // Switching SpotiFLAC on is the moment to find out whether the gateway is
                    // refusing this connection at all, so the answer is in hand before the first
                    // track asks six sources. It is the same unauthenticated health endpoint the
                    // route watch and the empty sweep use, and it is the only part of the old
                    // session bootstrap that still had a job: the session itself could never serve
                    // an extension (see SpotiFLACInstallIdentity) and nothing could complete its
                    // challenge any more.
                    scope.launch(Dispatchers.IO) {
                        app.hush.music.spotiflac.SpotiFLACSessionRenewer
                            .probeRelayBlock(this@MusicService, reason = "spotiflac-enabled")
                    }
                }
            }

        dataStore.data
            .map { it[app.hush.music.constants.DevModeKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) { dev ->
                devMode = dev
            }

        dataStore.data
            .map { prefs ->
                // A YouTube fallback only exists while YouTube is an enabled source;
                // disabling YouTube must make this effectively off, matching the
                // settings screen where the switch is greyed out.
                val requested = prefs[app.hush.music.constants.SpotiFLACFallbackToYouTubeKey] ?: true
                val ytEnabled = prefs[YoutubeStreamingEnabledKey] ?: true
                requested && ytEnabled
            }
            .distinctUntilChanged()
            .collectLatest(scope) { allowFallback ->
                spotiflacAllowYouTubeFallback = allowFallback
            }

        // Mirror the extension runtime's per-item transfer meter so the player can
        // show download progress for the track that is actually playing.
        scope.launch {
            spotiflacNativeRuntime.downloadProgress.collect { progress ->
                if (progress == null) {
                    activeDownloadProgress.value = null
                    return@collect
                }
                val currentId = withContext(Dispatchers.Main) { player.currentMediaItem?.mediaId }
                if (progress.mediaId.isBlank() || progress.mediaId == currentId) {
                    activeDownloadProgress.value = progress
                }
            }
        }

        // A source that just finished verifying can serve the track that stopped
        // waiting for it, so playback resumes instead of the queue skipping on. The
        // callback is registered here rather than collected from a flow, because a
        // missed emission would leave the parked track with nothing to wake it.
        app.hush.music.spotiflac.SpotiFLAutoVerifier.onSourceVerified = {
            scheduleHeldTrackRecovery()
        }

        dataStore.data
            .map { it[app.hush.music.constants.SourcePriorityKey] ?: "SPOTIFLAC,YOUTUBE" }
            .distinctUntilChanged()
            .collectLatest(scope) { priority ->
                sourcePriorityList = priority.split(",").filter { it.isNotBlank() }
            }

        extensionRepoManager.initialize(dataStore)
        scope.launch(Dispatchers.IO) {
            extensionRepoManager.syncRegistries()
            // Warm the SpotiFLAC runtime and extension packages so the first track
            // does not pay the download/load cost inside playback resolution.
            if (dataStore.get(app.hush.music.constants.SpotiFLACEnabledKey, false)) {
                // A registry that published a newer build should not have to wait for the first
                // track, and an update that only reached the diagnostic log is one no user sees.
                // Every installed package is checked here, and anything that moved is reported in
                // Audio Sources; the enabled sources are still prepared (and loaded) below.
                runCatching { spotiflacNativeRuntime.reconcileExtensionPackages() }
                    .onFailure { error ->
                        Timber.tag(TAG).w(error, "SpotiFLAC package reconcile failed")
                    }
                runCatching {
                    spotiflacNativeRuntime.prepareForPlayback(
                        extensionRepoManager.getEnabledSourceIds(),
                    )
                }
            }
        }

        combine(
            dataStore.data.map { it[AudioOffload] ?: false },
            dataStore.data.map { it[CrossfadeEnabledKey] ?: false },
        ) { offloadEnabled, crossfadeEnabled ->
            offloadEnabled to crossfadeEnabled
        }.distinctUntilChanged()
            .collectLatest(scope) { (offloadEnabled, crossfadeEnabled) ->
                val effectiveOffload = offloadEnabled && !crossfadeEnabled
                updateAudioOffload(effectiveOffload)
                if (effectiveOffload) {
                    val skipSilenceEnabled = dataStore.get(SkipSilenceKey, false)
                    if (skipSilenceEnabled) {
                        dataStore.edit { it[SkipSilenceKey] = false }
                        localPlayer.skipSilenceEnabled = false
                    }
                }
            }

        combine(dataStore.data, togetherSessionState) { prefs, togetherState ->
            val enabled = prefs[CrossfadeEnabledKey] ?: false
            val durationSeconds = prefs[CrossfadeDurationKey] ?: 5f
            val gapless = prefs[CrossfadeGaplessKey] ?: true
            CrossfadeConfig(
                enabled = enabled && togetherState is app.hush.music.together.TogetherSessionState.Idle,
                durationSeconds = durationSeconds,
                gapless = gapless,
            )
        }.distinctUntilChanged()
            .collectLatest(scope) { config ->
                crossfadeEnabled = config.enabled
                crossfadeDurationMs =
                    (config.durationSeconds.coerceIn(0f, 10f) * 1000f)
                        .roundToLong()
                        .coerceAtLeast(0L)
                crossfadeGapless = config.gapless
                if (crossfadeEnabled && crossfadeDurationMs > 0L) {
                    scheduleCrossfade()
                } else {
                    cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
                }
            }

        dataStore.data
            .map { it[WakelockKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) { enabled ->
                wakelockEnabled = enabled
                updateWakeLock()
            }

        // Initialize lyrics pre-load manager
        lyricsPreloadManager =
            LyricsPreloadManager(
                context = this,
                database = database,
                networkConnectivity = connectivityObserver,
                lyricsHelper = lyricsHelper,
            )

        dataStore.data
            .map(::readEqSettingsFromPrefs)
            .distinctUntilChanged()
            .collectLatest(scope) { settings ->
                val previous = desiredEqSettings.value
                desiredEqSettings.value = settings
                // Turning one of the optional colouring effects on has to create it, and
                // turning it off has to release it so its slot in the platform's effect
                // budget goes back to the pool. Without the rebuild those effects would
                // only ever appear on the next session open, and would never be freed.
                val effectsNeedRebuild =
                    previous.enabled != settings.enabled ||
                        previous.bassBoostEnabled != settings.bassBoostEnabled ||
                        previous.virtualizerEnabled != settings.virtualizerEnabled ||
                        previous.outputGainEnabled != settings.outputGainEnabled
                val sessionId = openedAudioSessionId ?: localPlayer.audioSessionId
                if (effectsNeedRebuild && isAudioEffectSessionOpened && sessionId > 0) {
                    ensureAudioEffects(sessionId, force = true)
                } else {
                    applyEqSettingsToEffects(settings)
                }
            }

        combine(
            currentMediaMetadata
                .map { it?.id }
                .distinctUntilChanged(),
            currentFormat,
            dataStore.data
                .map { prefs ->
                    (prefs[AudioNormalizationKey] ?: true) to
                        prefs[LoudnessLevelKey].toEnum(LoudnessLevel.BALANCED)
                }
                .distinctUntilChanged(),
        ) { mediaId, format, (normalizeAudio, loudnessLevel) ->
            loudnessLevelCached = loudnessLevel
            normalizeAudio to resolveAudioNormalizationFactor(mediaId, format, normalizeAudio)
        }.distinctUntilChanged()
            .collectLatest(scope) { (normalizeAudio, factor) ->
                audioNormalizationEnabled = normalizeAudio
                normalizeFactor.value = factor
            }

        dataStore.data
            .map { prefs ->
                (prefs[SmartTrimmerKey] ?: false) to (prefs[MaxSongCacheSizeKey] ?: 1024)
            }.debounce(300)
            .distinctUntilChanged()
            .collectLatest(ioScope) { (enabled, maxSongCacheSizeMb) ->
                if (!enabled) return@collectLatest
                if (maxSongCacheSizeMb <= 0 || maxSongCacheSizeMb == -1) return@collectLatest
                val bytesPerMb = 1024L * 1024L
                val safeSizeMb = maxSongCacheSizeMb.toLong().coerceAtMost(Long.MAX_VALUE / bytesPerMb)
                val limitBytes = safeSizeMb * bytesPerMb
                trimPlayerCacheToBytes(limitBytes)
            }

        dataStore.data
            .map { preferences ->
                val serviceConfig = LastFmServiceConfig.fromPreferences(preferences)
                Triple(
                    preferences[EnableLastFMScrobblingKey] ?: false,
                    !preferences[LastFMSessionKey].isNullOrBlank(),
                    serviceConfig.initialized,
                )
            }.debounce(300)
            .distinctUntilChanged()
            .collect(scope) { (enabled, hasSession, serviceConfigured) ->
                val shouldEnable = enabled && hasSession && serviceConfigured
                if (shouldEnable && scrobbleManager == null) {
                    val delayPercent = dataStore.get(ScrobbleDelayPercentKey, LastFM.DEFAULT_SCROBBLE_DELAY_PERCENT)
                    val minSongDuration = dataStore.get(ScrobbleMinSongDurationKey, LastFM.DEFAULT_SCROBBLE_MIN_SONG_DURATION)
                    val delaySeconds = dataStore.get(ScrobbleDelaySecondsKey, LastFM.DEFAULT_SCROBBLE_DELAY_SECONDS)

                    scrobbleManager =
                        app.hush.music.utils.ScrobbleManager(
                            ioScope,
                            minSongDuration = minSongDuration,
                            scrobbleDelayPercent = delayPercent,
                            scrobbleDelaySeconds = delaySeconds,
                        )
                    scrobbleManager?.useNowPlaying = dataStore.get(LastFMUseNowPlaying, false)
                } else if (!shouldEnable && scrobbleManager != null) {
                    scrobbleManager?.destroy()
                    scrobbleManager = null
                }
            }

        dataStore.data
            .map { it[LastFMUseNowPlaying] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) {
                scrobbleManager?.useNowPlaying = it
            }

        dataStore.data
            .map { prefs ->
                Triple(
                    prefs[ScrobbleDelayPercentKey] ?: LastFM.DEFAULT_SCROBBLE_DELAY_PERCENT,
                    prefs[ScrobbleMinSongDurationKey] ?: LastFM.DEFAULT_SCROBBLE_MIN_SONG_DURATION,
                    prefs[ScrobbleDelaySecondsKey] ?: LastFM.DEFAULT_SCROBBLE_DELAY_SECONDS,
                )
            }.distinctUntilChanged()
            .collect(scope) { (delayPercent, minSongDuration, delaySeconds) ->
                scrobbleManager?.let {
                    it.scrobbleDelayPercent = delayPercent
                    it.minSongDuration = minSongDuration
                    it.scrobbleDelaySeconds = delaySeconds
                }
            }

        dataStore.data
            .map(StreamSourcePreferences::disabledClientNames)
            .distinctUntilChanged()
            .collectLatest(scope) { disabledClients ->
                YTPlayerUtils.disabledStreamClients = disabledClients
            }

        dataStore.data
            .map { prefs -> prefs[HideVideoKey] ?: false }
            .distinctUntilChanged()
            .collect(scope) { shouldHideMusicVideos ->
                hideMusicVideos = shouldHideMusicVideos
                if (shouldHideMusicVideos) {
                    removeMusicVideoItems()
                }
            }

        scope.launch(Dispatchers.IO) {
            runCatching {
                // Fetch all restore-related prefs in a single blocking call to avoid
                // multiple 1.5 s timeouts if PreferenceStore hasn't emitted yet.
                val prefs = dataStore.data.first()
                val persistentQueueEnabled = prefs[PersistentQueueKey] ?: true
                if (persistentQueueEnabled) {
                    // Bounded: waiting forever here used to leave the queue empty and
                    // every deferred transport command stranded, because the flag that
                    // releases them is only set after this block returns.
                    val playerReady =
                        withTimeoutOrNull(RESTORE_PLAYER_READY_TIMEOUT_MS) {
                            playerInitialized.first { it }
                        }
                    if (playerReady == null) {
                        Timber.tag(TAG).w("Player not initialized within %d ms; restoring anyway", RESTORE_PLAYER_READY_TIMEOUT_MS)
                    }
                    val persistedQueue = readPersistentObject<PersistQueue>(PERSISTENT_QUEUE_FILE)
                    val persistedPlayerState = readPersistentObject<PersistPlayerState>(PERSISTENT_PLAYER_STATE_FILE)

                    if (persistedQueue != null || persistedPlayerState != null) {
                        isRestoringPersistentState = true
                    }

                    var restoredQueue = false
                    try {
                        persistedQueue?.let { queue ->
                            restorePersistentQueue(queue, prefs)
                            restoredQueue = true
                        }
                        persistedPlayerState?.let { playerState ->
                            restorePersistentPlayerState(playerState, restoredQueue)
                        }
                    } finally {
                        isRestoringPersistentState = false
                    }
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Timber.tag(TAG).w(error, "Failed to restore persisted queue, clearing data")
                isRestoringPersistentState = false
                cancelRestoredQueueHydration()
                clearPersistedQueueFiles()
            }
            markQueueRestoreCompleted()
        }

        // Safety net: nothing may leave the transport controls dead. If the restore
        // coroutine is slow, cancelled, or dies, release the commands it was holding
        // and let them run against whatever the player has.
        scope.launch {
            delay(RESTORE_COMPLETION_DEADLINE_MS)
            if (!queueRestoreCompleted.value) {
                Timber.tag(TAG).w(
                    "Queue restore did not finish within %d ms; releasing transport commands",
                    RESTORE_COMPLETION_DEADLINE_MS,
                )
                app.hush.music.spotiflac.SpotiFLACDiag.log(
                    "queue restore watchdog fired after ${RESTORE_COMPLETION_DEADLINE_MS}ms; playerItems=${player.mediaItemCount}",
                )
                markQueueRestoreCompleted()
            }
        }

        scope.launch {
            while (isActive) {
                delay(if (player.isPlaying) 10.seconds else 30.seconds)
                val shouldSave = withContext(Dispatchers.IO) { dataStore.get(PersistentQueueKey, true) }
                if (shouldSave && player.mediaItemCount > 0) {
                    saveQueueToDisk()
                }
            }
        }
    }

    /**
     * Releases the transport commands held while the persisted queue was being
     * restored. Idempotent, and safe to call from a watchdog: the flag must reach
     * `true` exactly once, otherwise commands queued behind it are never delivered.
     */
    private fun markQueueRestoreCompleted() {
        if (queueRestoreCompleted.value) return
        queueRestoreCompleted.value = true
        val commands = pendingWazeCommands.toList()
        pendingWazeCommands.clear()
        if (commands.isEmpty()) return
        scope.launch(Dispatchers.Main) {
            for (command in commands) {
                handleWazeCommand(command)
            }
        }
    }

    private fun ensureScopesActive() {
        if (!scopeJob.isActive) {
            scopeJob = Job()
        }
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.Main + scopeJob)
        }
        if (!ioScope.isActive) {
            ioScope = CoroutineScope(Dispatchers.IO + scopeJob)
        }
    }

    private fun cancelRestoredQueueHydration() {
        restoredQueueHydrationGeneration.incrementAndGet()
        restoredQueueBackfillJob?.cancel()
        restoredQueueBackfillJob = null
        isHydratingRestoredQueue = false
    }

    private suspend fun restorePersistentQueue(persistedQueue: PersistQueue, prefs: Preferences) {
        cancelRestoredQueueHydration()
        val hydrationGeneration = restoredQueueHydrationGeneration.incrementAndGet()
        isHydratingRestoredQueue = true

        val itemQueue = persistedQueue.toQueue()
        val continuationQueue = persistedQueue.toContinuationQueue()
        val hideExplicit = prefs[HideExplicitKey] ?: false
        val hideVideo = prefs[HideVideoKey] ?: false
        // A restored queue is materialised here rather than through startQueue, so it
        // needs the same filters: without them, restarting the app put blocked artists'
        // tracks back into the timeline that the fresh-load path had removed.
        val blockedArtistIds =
            try {
                withContext(Dispatchers.IO) { database.getBlockedArtistIds().toSet() }
            } catch (_: Exception) {
                emptySet()
            }
        val initialStatus =
            itemQueue
                .getInitialStatus()
                .filterExplicit(hideExplicit)
                .filterBlockedArtists(blockedArtistIds)
                .filterVideo(hideVideo)

        withContext(Dispatchers.Main) {
            currentQueue = continuationQueue
            queueTitle = initialStatus.title

            val items = initialStatus.items
            if (items.isEmpty()) {
                if (hydrationGeneration == restoredQueueHydrationGeneration.get()) {
                    isHydratingRestoredQueue = false
                }
                return@withContext
            }

            val fullIndex = initialStatus.mediaItemIndex.coerceIn(0, items.lastIndex)
            val windowStart = (fullIndex - 20).coerceAtLeast(0)
            val windowEnd = (fullIndex + 50).coerceAtMost(items.size)

            val initialChunk = items.subList(windowStart, windowEnd)
            val relativeIndex = (fullIndex - windowStart).coerceIn(0, initialChunk.lastIndex)

            // Kick off URL resolution before prepare() so the ExoPlayer loader awaits
            // the in-flight prefetch instead of resolving the stream cold and synchronously.
            items.getOrNull(fullIndex)
                ?.mediaId
                ?.takeIf { it.isNotBlank() && !it.isLocalMediaId() }
                ?.let { startPlaybackUrlPrefetch(it) }

            player.setMediaItems(
                initialChunk,
                relativeIndex,
                initialStatus.position,
            )
            player.playWhenReady = false
            currentMediaMetadata.value = player.currentMetadata

            val restoredMediaId = player.currentMediaItem?.mediaId?.takeIf {
                it.isNotBlank() && !it.isLocalMediaId()
            }
            if (restoredMediaId == null) {
                player.prepare()
            } else {
                // The current item was prefetched before setMediaItems(). Prepare now
                // instead of waiting for the prefetch coroutine to be scheduled. The
                // data source reuses the in-flight request, so this remains single-flight.
                player.prepare()
                scope.launch(SilentHandler) {
                    warmPlaybackUrl(restoredMediaId, maxWaitMs = 0L)
                    withContext(Dispatchers.Main.immediate) {
                        if (
                            player.currentMediaItem?.mediaId == restoredMediaId &&
                            player.playbackState == Player.STATE_IDLE
                        ) {
                            player.prepare()
                        }
                    }
                }
            }
            updateNotification()

            if (items.size > initialChunk.size) {
                restoredQueueBackfillJob =
                    scope.launch(SilentHandler) {
                        try {
                            delay(2000)
                            if (!isActive || player.mediaItemCount == 0) return@launch
                            if (windowStart > 0) {
                                player.addMediaItems(0, items.subList(0, windowStart))
                            }
                            if (windowEnd < items.size) {
                                player.addMediaItems(items.subList(windowEnd, items.size))
                            }
                        } finally {
                            if (hydrationGeneration == restoredQueueHydrationGeneration.get()) {
                                isHydratingRestoredQueue = false
                                restoredQueueBackfillJob = null
                                if (isActive && dataStore.get(PersistentQueueKey, true) && player.mediaItemCount > 0) {
                                    saveQueueToDisk()
                                }
                            }
                        }
                    }
            } else {
                if (hydrationGeneration == restoredQueueHydrationGeneration.get()) {
                    isHydratingRestoredQueue = false
                }
            }
        }
    }

    private suspend fun restorePersistentPlayerState(
        playerState: PersistPlayerState,
        restoredQueue: Boolean,
    ) {
        withContext(Dispatchers.Main) {
            player.repeatMode = playerState.repeatMode
            player.shuffleModeEnabled = playerState.shuffleModeEnabled
            playerVolume.value = playerState.volume.coerceIn(0f, 1f)

            if (player.mediaItemCount > 0) {
                val index =
                    when {
                        restoredQueue -> {
                            player.currentMediaItemIndex.coerceIn(0, player.mediaItemCount - 1)
                        }

                        playerState.currentMediaItemIndex in 0 until player.mediaItemCount -> {
                            playerState.currentMediaItemIndex
                        }

                        else -> {
                            player.currentMediaItemIndex.coerceIn(0, player.mediaItemCount - 1)
                        }
                    }
                player.seekTo(index, playerState.currentPosition.coerceAtLeast(0L))
            }

            player.playWhenReady = false
            abandonAudioFocus()

            currentMediaMetadata.value = player.currentMetadata.takeIf { player.mediaItemCount > 0 }
            updateNotification()
        }
    }

    private fun setupAudioFocusRequest() {
        audioFocusRequest =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes
                        .Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                ).setOnAudioFocusChangeListener { focusChange ->
                    handleAudioFocusChange(focusChange)
                }.setAcceptsDelayedFocusGain(true)
                .build()
    }

    private fun onAudioOutputDeviceChanged() {
        if (!::player.isInitialized) return
        val outputSignature = currentAudioOutputDeviceSignature()
        if (outputSignature == lastAudioOutputDeviceSignature) return
        lastAudioOutputDeviceSignature = outputSignature
        audioOutputResolver.refresh()
        cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
        player.setAudioAttributes(playbackAudioAttributes(), false)
        audioRouteRecoveryJob?.cancel()
        audioRouteRecoveryJob =
            scope.launch {
                delay(AUDIO_ROUTE_CHANGE_DEBOUNCE_MS)
                recoverAudioRouteAfterDeviceChange()
            }
    }

    private suspend fun recoverAudioRouteAfterDeviceChange() {
        if (!::player.isInitialized) return

        rebindAudioEffectsAfterRouteChange()

        if (!shouldRebuildPlaybackForAudioRouteChange()) return

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAudioRouteRecoveryRealtimeMs < AUDIO_ROUTE_RECOVERY_MIN_INTERVAL_MS) return
        lastAudioRouteRecoveryRealtimeMs = now

        val mediaItemIndex = player.currentMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: return
        val playbackPosition = player.currentPosition.coerceAtLeast(0L)
        val shouldResumePlayback = player.playWhenReady

        Timber.tag("MusicService").i(
            "Recovering audio route after output change at index=$mediaItemIndex position=$playbackPosition resume=$shouldResumePlayback",
        )

        if (shouldResumePlayback && !requestAudioFocus()) {
            wasPlayingBeforeAudioFocusLoss = true
            player.playWhenReady = false
            return
        }

        player.playWhenReady = false
        player.seekTo(mediaItemIndex, playbackPosition)
        delay(AUDIO_ROUTE_RECOVERY_RESUME_DELAY_MS)

        if (shouldResumePlayback && requestAudioFocus()) {
            player.playWhenReady = true
        }
    }

    private suspend fun rebindAudioEffectsAfterRouteChange() {
        if (!isAudioEffectSessionOpened) return
        closeAudioEffectSession()
        if (!player.playWhenReady) return
        delay(AUDIO_EFFECT_ROUTE_REBIND_DELAY_MS)
        openAudioEffectSession()
    }

    private fun shouldRebuildPlaybackForAudioRouteChange(): Boolean {
        if (player.currentMediaItem == null) return false
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) return false
        return player.playWhenReady || player.playbackState == Player.STATE_BUFFERING
    }

    private fun currentAudioOutputDeviceSignature(): String =
        runCatching {
            audioManager
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .asSequence()
                .filter { it.isSink }
                .sortedWith(
                    compareBy<AudioDeviceInfo>(
                        { it.type },
                        { it.id },
                        { it.productName?.toString().orEmpty() },
                    ),
                ).joinToString(separator = "|") { device ->
                    "${device.type}:${device.id}:${device.productName?.toString().orEmpty()}"
                }
        }.getOrDefault("")

    private fun playbackAudioAttributes(): AudioAttributes =
        AudioAttributes
            .Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_ALL)
            .build()

    private fun calculateEffectivePlayerVolume(
        playerVolume: Float,
        normalizeFactor: Float,
        audioFocusVolumeFactor: Float,
    ): Float {
        val safePlayerVolume = playerVolume.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        val safeNormalizeFactor =
            normalizeFactor.takeIf { it.isFinite() }?.coerceIn(MIN_AUDIO_NORMALIZATION_FACTOR, MAX_AUDIO_NORMALIZATION_FACTOR) ?: 1f
        val safeAudioFocusVolumeFactor =
            audioFocusVolumeFactor.takeIf { it.isFinite() }?.coerceIn(MIN_AUDIO_FOCUS_VOLUME_FACTOR, 1f) ?: 1f
        return (safePlayerVolume * safeNormalizeFactor * safeAudioFocusVolumeFactor).coerceIn(0f, maxSafeGainFactor)
    }

    private fun currentEffectivePlayerVolume(): Float =
        calculateEffectivePlayerVolume(playerVolume.value, normalizeFactor.value, audioFocusVolumeFactor.value)

    private fun currentEffectivePlayerVolumeForMediaId(mediaId: String): Float {
        val targetNormalizeFactor =
            if (audioNormalizationEnabled) {
                audioNormalizationFactorCache[mediaId] ?: 1f
            } else {
                1f
            }
        return calculateEffectivePlayerVolume(playerVolume.value, targetNormalizeFactor, audioFocusVolumeFactor.value)
    }

    private fun updateEffectiveVolume(finalVolume: Float) {
        if (!::player.isInitialized || !shouldRampEffectiveVolume(finalVolume)) {
            applyEffectiveVolumeImmediately(finalVolume)
            return
        }

        val startVolume = player.volume.takeIf { it.isFinite() }?.coerceIn(0f, maxSafeGainFactor) ?: finalVolume
        val targetVolume = finalVolume.coerceIn(0f, maxSafeGainFactor)
        if (abs(targetVolume - startVolume) <= EFFECTIVE_VOLUME_RAMP_MIN_DELTA) {
            applyEffectiveVolumeImmediately(targetVolume)
            return
        }

        effectiveVolumeRampJob?.cancel()
        effectiveVolumeRampJob =
            scope.launch {
                val durationMs =
                    if (targetVolume > startVolume) {
                        EFFECTIVE_VOLUME_RAMP_UP_MS
                    } else {
                        EFFECTIVE_VOLUME_RAMP_DOWN_MS
                    }
                val startedAtMs = android.os.SystemClock.elapsedRealtime()
                while (isActive) {
                    val elapsedMs = android.os.SystemClock.elapsedRealtime() - startedAtMs
                    val progress = (elapsedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    val easedProgress = progress * progress * (3f - (2f * progress))
                    val interpolatedVolume = startVolume + ((targetVolume - startVolume) * easedProgress)
                    applyEffectiveVolume(interpolatedVolume)
                    if (progress >= 1f) break
                    delay(EFFECTIVE_VOLUME_RAMP_FRAME_MS)
                }
                applyEffectiveVolume(targetVolume)
                effectiveVolumeRampJob = null
            }
    }

    private fun shouldRampEffectiveVolume(finalVolume: Float): Boolean {
        if (isCrossfading || crossfadeHandoffInProgress) return false
        if (!shouldKeepPlaybackAudible()) return false
        if (!finalVolume.isFinite()) return false
        if (player.volume <= STUCK_MUTED_VOLUME_EPSILON) return false
        return true
    }

    private fun applyEffectiveVolumeImmediately(finalVolume: Float = currentEffectivePlayerVolume()) {
        effectiveVolumeRampJob?.cancel()
        effectiveVolumeRampJob = null
        applyEffectiveVolume(finalVolume)
    }

    private fun applyEffectiveVolume(finalVolume: Float = currentEffectivePlayerVolume()) {
        crossfadeBaseVolume = finalVolume
        val incomingPlayer = secondaryCrossfadePlayer
        if (isCrossfading && incomingPlayer != null) {
            val incomingBaseVolume =
                secondaryCrossfadeTarget?.let { currentEffectivePlayerVolumeForMediaId(it.mediaId) }
                    ?: finalVolume
            crossfadeIncomingBaseVolume = incomingBaseVolume
            applyCrossfadeVolumes(crossfadeProgress, finalVolume, incomingBaseVolume, localPlayer, incomingPlayer)
            return
        }
        if (::player.isInitialized) {
            player.volume = finalVolume
        }
        incomingPlayer?.volume = 0f
    }

    private fun ensureAudiblePlaybackVolume(reason: String) {
        if (!::player.isInitialized) return
        if (isCrossfading || crossfadeHandoffInProgress) return
        if (!shouldKeepPlaybackAudible()) return
        if (playerVolume.value <= 0f) return

        val expectedVolume = currentEffectivePlayerVolume()
        if (expectedVolume <= MIN_AUDIBLE_EFFECTIVE_VOLUME) return
        if (player.volume > STUCK_MUTED_VOLUME_EPSILON) return

        Timber.tag(TAG).w(
            "Restoring muted primary player volume during active playback: reason=%s expected=%s actual=%s",
            reason,
            expectedVolume,
            player.volume,
        )
        applyEffectiveVolumeImmediately(expectedVolume)
    }

    private fun updateAudiblePlaybackRecovery() {
        if (!::player.isInitialized || !shouldKeepPlaybackAudible()) {
            audiblePlaybackRecoveryJob?.cancel()
            audiblePlaybackRecoveryJob = null
            return
        }

        if (audiblePlaybackRecoveryJob?.isActive == true) return
        audiblePlaybackRecoveryJob =
            scope.launch {
                while (isActive && shouldKeepPlaybackAudible()) {
                    ensureAudiblePlaybackVolume("watchdog")
                    delay(AUDIBLE_PLAYBACK_VOLUME_CHECK_MS)
                }
                audiblePlaybackRecoveryJob = null
            }
    }

    private fun applyCrossfadeVolumes(
        progress: Float,
        outgoingBaseVolume: Float,
        incomingBaseVolume: Float,
        outgoingPlayer: ExoPlayer,
        incomingPlayer: ExoPlayer,
    ) {
        val clampedProgress = progress.coerceIn(0f, 1f)
        val radians = clampedProgress.toDouble() * (PI / 2.0)
        outgoingPlayer.volume = (outgoingBaseVolume * cos(radians).toFloat()).coerceIn(0f, maxSafeGainFactor)
        incomingPlayer.volume = (incomingBaseVolume * sin(radians).toFloat()).coerceIn(0f, maxSafeGainFactor)
    }

    fun pauseFromSleepTimer() {
        sleepTimer?.clear()
        crossfadeTriggerJob?.cancel()
        crossfadeTriggerJob = null
        cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
        releaseSecondaryCrossfadePlayer()
        player.pause()
        player.playWhenReady = false
        localPlayer.pause()
        localPlayer.playWhenReady = false
    }

    private fun scheduleCrossfade() {
        if (!::player.isInitialized) return
        crossfadeTriggerJob?.cancel()
        crossfadeTriggerJob = null

        if (isCrossfading) return
        if (!player.playWhenReady || sleepTimer?.pauseWhenSongEnd == true) {
            localPlayer.pauseAtEndOfMediaItems = false
            releaseSecondaryCrossfadePlayer()
            return
        }

        val target = resolveCrossfadeTarget()
        val duration = player.duration
        val effectiveDuration = effectiveCrossfadeDuration(duration)
        if (target == null || effectiveDuration == null) {
            localPlayer.pauseAtEndOfMediaItems = false
            releaseSecondaryCrossfadePlayer()
            return
        }

        val currentMediaId = player.currentMediaItem?.mediaId ?: return
        val currentIndex = player.currentMediaItemIndex
        val triggerAt = duration - effectiveDuration - CROSSFADE_END_GUARD_MS

        crossfadeTriggerJob =
            scope.launch {
                var hasPreparedSecondaryPlayer = false
                while (isActive) {
                    if (!crossfadeEnabled || isCrossfading) return@launch
                    if (player.currentMediaItem?.mediaId != currentMediaId || player.currentMediaItemIndex != currentIndex) {
                        return@launch
                    }
                    if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                        return@launch
                    }

                    val remainingToTrigger = triggerAt - player.currentPosition
                    if (!hasPreparedSecondaryPlayer && remainingToTrigger <= CROSSFADE_PREPARE_AHEAD_MS) {
                        prepareSecondaryCrossfadePlayer(target)
                        hasPreparedSecondaryPlayer = true
                    }
                    if (remainingToTrigger <= 0L) {
                        val speed = player.playbackParameters.speed.coerceAtLeast(0.5f)
                        val adjustedDuration =
                            ((duration - player.currentPosition - CROSSFADE_END_GUARD_MS) * speed)
                                .toLong()
                                .coerceAtMost(effectiveDuration)
                        if (adjustedDuration >= MIN_CROSSFADE_DURATION_MS) {
                            startCrossfade(target, adjustedDuration)
                        }
                        return@launch
                    }

                    val sleepMs =
                        when {
                            remainingToTrigger > 5_000L -> 1_000L
                            remainingToTrigger > 1_000L -> 250L
                            else -> 50L
                        }.coerceAtMost(remainingToTrigger).coerceAtLeast(1L)
                    delay(sleepMs)
                }
            }
    }

    private fun resolveCrossfadeTarget(): CrossfadeTarget? {
        if (!crossfadeEnabled || crossfadeDurationMs <= 0L) return null
        if (player.mediaItemCount == 0 || player.currentTimeline.isEmpty) return null
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) return null

        val currentIndex = player.currentMediaItemIndex
        if (currentIndex !in 0 until player.mediaItemCount) return null

        val repeatCurrent = player.repeatMode == REPEAT_MODE_ONE
        val targetIndex = if (repeatCurrent) currentIndex else player.nextMediaItemIndex
        if (targetIndex == C.INDEX_UNSET || targetIndex !in 0 until player.mediaItemCount) return null
        if (!repeatCurrent && targetIndex == currentIndex) return null

        val currentItem = player.getMediaItemAt(currentIndex)
        val targetItem = player.getMediaItemAt(targetIndex)
        if (!repeatCurrent && crossfadeGapless && isGaplessAlbumTransition(currentItem, targetItem)) return null

        return CrossfadeTarget(
            index = targetIndex,
            mediaId = targetItem.mediaId,
        )
    }

    private fun effectiveCrossfadeDuration(duration: Long): Long? {
        if (duration == C.TIME_UNSET || duration <= 0L) return null
        val maxDuration = duration - CROSSFADE_END_GUARD_MS
        if (maxDuration < MIN_CROSSFADE_DURATION_MS) return null
        return crossfadeDurationMs
            .coerceAtLeast(MIN_CROSSFADE_DURATION_MS)
            .coerceAtMost(maxDuration)
    }

    private fun isGaplessAlbumTransition(
        currentItem: MediaItem,
        targetItem: MediaItem,
    ): Boolean {
        val currentAlbum =
            currentItem.metadata
                ?.album
                ?.id
                ?.takeIf { it.isNotBlank() }
                ?: currentItem.metadata
                    ?.album
                    ?.title
                    ?.takeIf { it.isNotBlank() }
                ?: currentItem.mediaMetadata.albumTitle
                    ?.toString()
                    ?.takeIf { it.isNotBlank() }
        val targetAlbum =
            targetItem.metadata
                ?.album
                ?.id
                ?.takeIf { it.isNotBlank() }
                ?: targetItem.metadata
                    ?.album
                    ?.title
                    ?.takeIf { it.isNotBlank() }
                ?: targetItem.mediaMetadata.albumTitle
                    ?.toString()
                    ?.takeIf { it.isNotBlank() }
        return currentAlbum != null && currentAlbum == targetAlbum
    }

    private fun prepareSecondaryCrossfadePlayer(target: CrossfadeTarget): ExoPlayer? {
        val existingPlayer = secondaryCrossfadePlayer
        if (existingPlayer != null && secondaryCrossfadeTarget == target) {
            return existingPlayer
        }

        releaseSecondaryCrossfadePlayer()

        // A second ExoPlayer is the largest single allocation the app can make: its
        // renderers and buffers are tens of megabytes. "Low RAM" as a platform flag is
        // the wrong test for that - a head unit capped at 128-192 MB never sets it, and
        // would build an entire second player just to crossfade two tracks.
        if (DeviceProfile.tier(this) != DeviceTier.STANDARD) {
            return null
        }

        val retries = secondaryCrossfadeRetryCount.get()
        if (retries >= MAX_SECONDARY_PLAYER_RETRIES) {
            Timber.tag(TAG).w("Max secondary player retries ($MAX_SECONDARY_PLAYER_RETRIES) reached — falling back to normal transition")
            secondaryCrossfadeRetryCount.set(0)
            return null
        }

        val targetItem =
            runCatching { player.getMediaItemAt(target.index) }
                .getOrNull()
                ?.takeIf { it.mediaId == target.mediaId }
                ?: return null

        return runCatching {
            createSecondaryCrossfadePlayer().also { secondaryPlayer ->
                secondaryCrossfadePlayer = secondaryPlayer
                secondaryCrossfadeTarget = target
                secondaryPlayer.setMediaItem(targetItem)
                secondaryPlayer.playbackParameters = player.playbackParameters
                secondaryPlayer.volume = 0f
                secondaryPlayer.prepare()
            }
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "Failed to prepare crossfade player")
            releaseSecondaryCrossfadePlayer()
        }.getOrNull()
    }

    private fun createSecondaryCrossfadePlayer(): ExoPlayer =
        ExoPlayer
            .Builder(this)
            .setMediaSourceFactory(createMediaSourceFactory())
            .setRenderersFactory(
                createRenderersFactory(
                    CustomEqualizerAudioProcessor().also {
                        secondaryEqProcessor = it
                        hushEqualizerService.addAudioProcessor(it)
                    },
                ),
            )
            .setLoadControl(createCrossfadeLoadControl())
            .setTrackSelector(DefaultTrackSelector(this, SafeTrackSelectionFactory()))
            .setHandleAudioBecomingNoisy(false)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false,
            )
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .build()
            .apply {
                addListener(secondaryCrossfadeListener)
                setOffloadEnabled(false)
                skipSilenceEnabled = localPlayer.skipSilenceEnabled
            }

    private fun startCrossfade(
        target: CrossfadeTarget,
        durationMs: Long,
    ) {
        if (isCrossfading || !crossfadeEnabled) return

        val incomingPlayer = prepareSecondaryCrossfadePlayer(target) ?: return
        val outgoingMediaId = player.currentMediaItem?.mediaId ?: return
        val outgoingQueueIndex = player.currentMediaItemIndex

        crossfadeTransitionSeq++
        val transition = CrossfadeTransition(
            transitionId = crossfadeTransitionSeq,
            outgoingTrackId = outgoingMediaId,
            incomingTrackId = target.mediaId,
            outgoingQueueIndex = outgoingQueueIndex,
            incomingQueueIndex = target.index,
            state = CrossfadeTransitionState.FADING,
        )
        activeCrossfadeTransition = transition

        Timber.tag(TAG).d("[CROSSFADE #${transition.transitionId}] START outgoing=$outgoingMediaId incoming=${target.mediaId} duration=${durationMs}ms")
        crossfadeTriggerJob?.cancel()
        crossfadeTriggerJob = null
        crossfadeJob?.cancel()
        crossfadeJob =
            scope.launch {
                isCrossfading = true
                crossfadeProgress = 0f
                crossfadeBaseVolume = currentEffectivePlayerVolume()
                crossfadeIncomingBaseVolume = currentEffectivePlayerVolumeForMediaId(target.mediaId)
                crossfadePlaybackRequested = player.playWhenReady

                try {
                    val requiredBufferedMs = requiredCrossfadeStartBufferMs(durationMs)
                    if (!awaitCrossfadePlayerReady(incomingPlayer, CROSSFADE_READY_TIMEOUT_MS, requiredBufferedMs)) {
                        Timber.tag(TAG).w("[CROSSFADE #${transition.transitionId}] incoming player not ready")
                        cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
                        scheduleCrossfade()
                        return@launch
                    }

                    localPlayer.pauseAtEndOfMediaItems = true
                    incomingPlayer.playbackParameters = player.playbackParameters
                    incomingPlayer.playWhenReady = crossfadePlaybackRequested
                    if (crossfadePlaybackRequested) {
                        incomingPlayer.play()
                    }
                    secondaryCrossfadeRetryCount.set(0)
                    Timber.tag(TAG).d("[CROSSFADE #${transition.transitionId}] PLAY incoming position=0")

                    var elapsedMs = 0L
                    var handoffMetadataPromoted = false
                    var lastTickMs = android.os.SystemClock.elapsedRealtime()
                    while (isActive && elapsedMs < durationMs) {
                        if (player.currentMediaItem?.mediaId != outgoingMediaId) {
                            Timber.tag(TAG).d("[CROSSFADE #${transition.transitionId}] outgoing track changed, cancelling")
                            cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
                            return@launch
                        }

                        val nowMs = android.os.SystemClock.elapsedRealtime()
                        if (crossfadePlaybackRequested) {
                            incomingPlayer.playWhenReady = true
                            elapsedMs = (elapsedMs + (nowMs - lastTickMs)).coerceAtMost(durationMs)
                            crossfadeProgress = (elapsedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                            applyCrossfadeVolumes(
                                crossfadeProgress,
                                crossfadeBaseVolume,
                                crossfadeIncomingBaseVolume,
                                localPlayer,
                                incomingPlayer,
                            )
                            crossfadeLyricsState.value =
                                CrossfadeLyricsState(
                                    isActive = true,
                                    incomingMediaId = target.mediaId,
                                    incomingPositionMs = incomingPlayer.currentPosition.coerceAtLeast(0L),
                                )

                            // Promote metadata when crossfade reaches 50%
                            if (!handoffMetadataPromoted && crossfadeProgress >= 0.50f) {
                                handoffMetadataPromoted = true
                                transition.state = CrossfadeTransitionState.HANDOFF_COMPLETE
                                transition.logicalHandoffCompleted = true
                                promoteCrossfadeMetadata(transition, target, incomingPlayer)
                            }
                        } else {
                            incomingPlayer.pause()
                        }
                        lastTickMs = nowMs
                        delay(CROSSFADE_FRAME_MS)
                    }

                    finishCrossfade(target, incomingPlayer, transition)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Timber.tag(TAG).w(error, "Crossfade failed")
                    cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
                }
            }
    }

    private suspend fun awaitCrossfadePlayerReady(
        crossfadePlayer: ExoPlayer,
        timeoutMs: Long,
        minimumBufferedMs: Long,
    ): Boolean {
        val deadlineMs = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (kotlinx.coroutines.currentCoroutineContext().isActive && android.os.SystemClock.elapsedRealtime() < deadlineMs) {
            when (crossfadePlayer.playbackState) {
                Player.STATE_READY -> {
                    if (hasBufferedForSmoothStart(crossfadePlayer, minimumBufferedMs)) {
                        return true
                    }
                }

                Player.STATE_IDLE -> {
                    crossfadePlayer.prepare()
                }

                Player.STATE_ENDED -> {
                    return false
                }
            }
            delay(50L)
        }
        return crossfadePlayer.playbackState == Player.STATE_READY &&
            hasBufferedForSmoothStart(crossfadePlayer, minimumBufferedMs)
    }

    private fun promoteCrossfadeMetadata(
        transition: CrossfadeTransition,
        target: CrossfadeTarget,
        incomingPlayer: ExoPlayer,
    ) {
        if (transition.metadataPublished) return
        transition.metadataPublished = true

        val incomingPosition = incomingPlayer.currentPosition.coerceAtLeast(0L)
        Timber.tag(TAG).d("[CROSSFADE #${transition.transitionId}] METADATA_PROMOTED active=${target.mediaId} position=${incomingPosition}ms")

        val targetItem = runCatching { player.getMediaItemAt(target.index) }.getOrNull()
        if (targetItem != null && targetItem.mediaId == target.mediaId) {
            currentMediaMetadata.value = targetItem.metadata
            Timber.tag(TAG).d("[CROSSFADE #${transition.transitionId}] MEDIASESSION metadata updated to incoming")

            publishWazePlaybackSnapshot(force = true)
            Timber.tag(TAG).d("[CROSSFADE #${transition.transitionId}] WAZE_BRIDGE snapshot published")
        }
    }

    private suspend fun finishCrossfade(
        target: CrossfadeTarget,
        incomingPlayer: ExoPlayer,
        transition: CrossfadeTransition,
    ) {
        val targetIndex = resolveCrossfadeTargetIndex(target)
        if (targetIndex == C.INDEX_UNSET) {
            Timber.tag(TAG).w("[CROSSFADE #${transition.transitionId}] target index not found")
            cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
            return
        }

        val incomingPosition = incomingPlayer.currentPosition.coerceAtLeast(0L)
        val shouldContinuePlayback = crossfadePlaybackRequested

        Timber.tag(TAG).d("[CROSSFADE #${transition.transitionId}] HANDOFF active=${target.mediaId} position=${incomingPosition}ms")

        var handoffCompleted = false
        try {
            localPlayer.pauseAtEndOfMediaItems = false
            player.volume = 0f
            crossfadeHandoffInProgress = true
            player.seekTo(targetIndex, incomingPosition)
            player.playWhenReady = shouldContinuePlayback
            Timber.tag(TAG).d("[CROSSFADE #${transition.transitionId}] HANDOFF seekTo(target=$targetIndex, pos=$incomingPosition)")

            if (shouldContinuePlayback) {
                awaitPrimaryCrossfadeHandoffReady(incomingPlayer)
            }

            // Ensure metadata is promoted even if the 50% threshold wasn't reached
            if (!transition.metadataPublished) {
                promoteCrossfadeMetadata(transition, target, incomingPlayer)
            }

            handoffCompleted = true
        } finally {
            if (!handoffCompleted) {
                Timber.tag(TAG).w("[CROSSFADE #${transition.transitionId}] handoff failed, cleaning up")
                crossfadeHandoffInProgress = false
                isCrossfading = false
                crossfadeProgress = 0f
                crossfadePlaybackRequested = false
                releaseSecondaryCrossfadePlayer()
                applyEffectiveVolumeImmediately()
            }
        }

        isCrossfading = false
        crossfadeHandoffInProgress = false
        crossfadeProgress = 0f
        crossfadeIncomingBaseVolume = 1f
        crossfadePlaybackRequested = false
        crossfadeLyricsState.value = CrossfadeLyricsState()
        releaseSecondaryCrossfadePlayer()
        applyEffectiveVolumeImmediately()
        updateAudiblePlaybackRecovery()

        transition.state = CrossfadeTransitionState.CLEANING_OUTGOING
        Timber.tag(TAG).d("[CROSSFADE #${transition.transitionId}] HANDOFF complete — incoming continues at ${incomingPosition}ms")
        activeCrossfadeTransition = null

        scheduleCrossfade()
    }

    private suspend fun awaitPrimaryCrossfadeHandoffReady(incomingPlayer: ExoPlayer): Boolean {
        val deadlineMs = android.os.SystemClock.elapsedRealtime() + CROSSFADE_HANDOFF_READY_TIMEOUT_MS
        while (kotlinx.coroutines.currentCoroutineContext().isActive && android.os.SystemClock.elapsedRealtime() < deadlineMs) {
            if (player.playbackState == Player.STATE_READY && canHandoffWithoutRebuffer(incomingPlayer)) {
                return true
            }
            if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                return false
            }
            delay(25L)
        }
        return player.playbackState == Player.STATE_READY && canHandoffWithoutRebuffer(incomingPlayer)
    }

    private fun canHandoffWithoutRebuffer(incomingPlayer: ExoPlayer): Boolean {
        if (player.currentMediaItem
                ?.localConfiguration
                ?.uri
                ?.shouldBypassPlayerCache() == true
        ) {
            return true
        }
        if (hasBufferedForSmoothStart(localPlayer, CROSSFADE_HANDOFF_BUFFER_MS)) {
            val bufferedPosition = localPlayer.bufferedPosition
            val incomingPosition = incomingPlayer.currentPosition.coerceAtLeast(0L)
            return bufferedPosition == C.TIME_UNSET ||
                incomingPosition + CROSSFADE_HANDOFF_SEEK_GUARD_MS <= bufferedPosition
        }
        return false
    }

    private fun requiredCrossfadeStartBufferMs(durationMs: Long): Long =
        (durationMs + CROSSFADE_HANDOFF_BUFFER_MS)
            .coerceAtLeast(CROSSFADE_MIN_BUFFER_BEFORE_START_MS)
            .coerceAtMost(CROSSFADE_MAX_BUFFER_BEFORE_START_MS)

    private fun hasBufferedForSmoothStart(
        targetPlayer: ExoPlayer,
        minimumBufferedMs: Long,
    ): Boolean {
        if (minimumBufferedMs <= 0L) return true
        if (targetPlayer.currentMediaItem
                ?.localConfiguration
                ?.uri
                ?.shouldBypassPlayerCache() == true
        ) {
            return true
        }

        val duration = targetPlayer.duration
        val currentPosition = targetPlayer.currentPosition.coerceAtLeast(0L)
        val remainingDuration =
            if (duration != C.TIME_UNSET && duration > currentPosition) {
                duration - currentPosition
            } else {
                Long.MAX_VALUE
            }
        val requiredBufferedMs = minimumBufferedMs.coerceAtMost(remainingDuration)
        if (requiredBufferedMs <= 0L) return true

        val bufferedDuration = targetPlayer.totalBufferedDuration.coerceAtLeast(0L)
        if (bufferedDuration >= requiredBufferedMs) return true

        return duration != C.TIME_UNSET &&
            targetPlayer.bufferedPosition >= duration - CROSSFADE_END_GUARD_MS
    }

    private fun resolveCrossfadeTargetIndex(target: CrossfadeTarget): Int {
        if (target.index in 0 until player.mediaItemCount &&
            player.getMediaItemAt(target.index).mediaId == target.mediaId
        ) {
            return target.index
        }

        for (index in 0 until player.mediaItemCount) {
            if (player.getMediaItemAt(index).mediaId == target.mediaId) {
                return index
            }
        }
        return C.INDEX_UNSET
    }

    private fun cancelCrossfade(
        resetVolume: Boolean,
        resetPauseAtEnd: Boolean,
    ) {
        val activeTx = activeCrossfadeTransition
        if (activeTx != null) {
            Timber.tag(TAG).d("[CROSSFADE #${activeTx.transitionId}] CANCELLED")
            activeCrossfadeTransition = null
        }
        crossfadeTriggerJob?.cancel()
        crossfadeTriggerJob = null
        crossfadeJob?.cancel()
        crossfadeJob = null
        isCrossfading = false
        crossfadeHandoffInProgress = false
        crossfadeProgress = 0f
        crossfadeIncomingBaseVolume = 1f
        crossfadePlaybackRequested = false
        crossfadeLyricsState.value = CrossfadeLyricsState()
        if (::player.isInitialized && resetPauseAtEnd) {
            localPlayer.pauseAtEndOfMediaItems = false
        }
        releaseSecondaryCrossfadePlayer()
        if (resetVolume && ::player.isInitialized) {
            applyEffectiveVolumeImmediately()
        }
    }

    private fun releaseSecondaryCrossfadePlayer() {
        val playerToRelease = secondaryCrossfadePlayer
        secondaryCrossfadePlayer = null
        secondaryCrossfadeTarget = null
        secondaryEqProcessor?.let(hushEqualizerService::removeAudioProcessor)
        secondaryEqProcessor = null
        if (playerToRelease == null) return
        runCatching { playerToRelease.removeListener(secondaryCrossfadeListener) }
        runCatching { playerToRelease.stop() }
        runCatching { playerToRelease.clearMediaItems() }
        runCatching { playerToRelease.release() }
    }

    private fun calculateAudioNormalizationFactor(
        format: FormatEntity?,
        normalizeAudio: Boolean,
    ): Float {
        Timber.tag("AudioNormalization").d("Audio normalization enabled: $normalizeAudio")
        Timber
            .tag(
                "AudioNormalization",
            ).d("Format loudnessDb: ${format?.loudnessDb}, perceptualLoudnessDb: ${format?.perceptualLoudnessDb}")

        if (!normalizeAudio) {
            Timber.tag("AudioNormalization").d("Normalization disabled - using factor 1.0")
            return 1f
        }

        val loudnessDb = format?.normalizationLoudnessDb()
        if (loudnessDb == null || !loudnessDb.isFinite()) {
            Timber.tag("AudioNormalization").w("Normalization enabled but no valid loudness data available - no normalization applied")
            return 1f
        }

        val rawFactor = 10f.pow(-loudnessDb / 20)
        val factor =
            if (rawFactor.isFinite()) {
                rawFactor.coerceIn(MIN_AUDIO_NORMALIZATION_FACTOR, MAX_AUDIO_NORMALIZATION_FACTOR)
            } else {
                1f
            }

        if (factor != rawFactor) {
            Timber.tag("AudioNormalization").d("Normalization factor clamped from $rawFactor to $factor")
        }
        Timber.tag("AudioNormalization").i("Applying normalization factor: $factor")
        return factor
    }

    private fun resolveAudioNormalizationFactor(
        mediaId: String?,
        format: FormatEntity?,
        normalizeAudio: Boolean,
    ): Float {
        val currentMediaId = mediaId?.takeIf { it.isNotBlank() } ?: return 1f
        if (!normalizeAudio) {
            return 1f
        }

        if (format?.id == currentMediaId) {
            val factor = calculateAudioNormalizationFactor(format, normalizeAudio = true)
            audioNormalizationFactorCache[currentMediaId] = factor
            return factor
        }

        return audioNormalizationFactorCache[currentMediaId] ?: 1f
    }

    private fun FormatEntity.normalizationLoudnessDb(): Float? {
        val measuredLufs =
            perceptualLoudnessDb?.toFloat()?.takeIf { it.isFinite() }
                ?: loudnessDb?.let { it + LoudnessLevel.AGGRESSIVE.targetLufs }
                    ?.toFloat()
                    ?.takeIf { it.isFinite() }
        return measuredLufs?.let { it - loudnessLevelCached.targetLufs }
    }

    private fun playbackAuthFingerprint(): String =
        when (activeStreamClient) {
            PlayerStreamClient.HI_RES_LOSSLESS -> HiResLosslessPlaybackResolver.EXTERNAL_AUTH_FINGERPRINT
            else -> YouTube.currentPlaybackAuthState().fingerprint
        }

    @Volatile
    private var lastPublishedPlaybackClient: Pair<String, String>? = null

    @Volatile
    private var youtubeStreamingEnabled = true
    @Volatile
    private var spotiflacEnabled = false
    @Volatile
    private var devMode = false
    @Volatile
    private var sourcePriorityList: List<String> = listOf("SPOTIFLAC", "YOUTUBE")

    @Volatile
    private var spotiflacAllowYouTubeFallback = true

    private var queuePersistJob: Job? = null

    private fun isYouTubeStreamingEnabled(): Boolean = youtubeStreamingEnabled

    /**
     * The audio-source toggles as they are *right now*.
     *
     * They are mirrored into fields by long-lived collectors, and a mirror can lag
     * a settings change — or miss it for good if its collector coroutine died. The
     * symptom is severe rather than cosmetic: routing keeps treating YouTube as
     * disabled, so when every SpotiFLAC source fails there is nothing to fall back
     * to, the user's "fall back to YouTube" switch is force-disabled, and the player
     * spins forever without an error. Re-reading the preferences at resolve time
     * removes that dependency; after the first read DataStore serves this from
     * memory, so it costs nothing on the playback path.
     */
    private suspend fun refreshSourceToggleMirrors() {
        val prefs = runCatching { dataStore.data.first() }.getOrNull() ?: return
        val yt = prefs[YoutubeStreamingEnabledKey] ?: true
        val spoti = prefs[SpotiFLACEnabledKey] ?: false
        // A YouTube fallback only exists while YouTube is an enabled source.
        val fallback = (prefs[SpotiFLACFallbackToYouTubeKey] ?: true) && yt
        val priority =
            prefs[SourcePriorityKey]
                ?.split(",")
                ?.map { it.trim().uppercase() }
                ?.filter { it.isNotBlank() }
                ?.takeIf { it.isNotEmpty() }
        val changed =
            yt != youtubeStreamingEnabled ||
                spoti != spotiflacEnabled ||
                fallback != spotiflacAllowYouTubeFallback ||
                (priority != null && priority != sourcePriorityList)
        youtubeStreamingEnabled = yt
        spotiflacEnabled = spoti
        spotiflacAllowYouTubeFallback = fallback
        if (priority != null) sourcePriorityList = priority
        if (changed) {
            Timber.tag(TAG).i(
                "source toggles refreshed from prefs: yt=%s spotiflac=%s fallback=%s priority=%s",
                yt,
                spoti,
                fallback,
                sourcePriorityList.joinToString(","),
            )
            app.hush.music.spotiflac.SpotiFLACDiag.log(
                "source toggles: yt=$yt spotiflac=$spoti fallbackToYt=$fallback priority=${sourcePriorityList.joinToString(",")}",
            )
        }
    }

    private fun effectiveEngineOrder(
        spotiflacAvailable: Boolean,
        ytAvailable: Boolean,
    ): List<String> = PlaybackEngineOrder.effective(
        savedOrder = sourcePriorityList,
        spotiflacAvailable = spotiflacAvailable,
        youtubeAvailable = ytAvailable,
    )

    /**
     * Moves the track that is playing *right now* onto the routing the toggles just chose,
     * and leaves the rest of the queue exactly where it was.
     *
     * This used to `clearMediaItems()` and then `setMediaItem()` the current track back, which
     * threw away every item after it. The periodic queue save then wrote that one-song queue
     * over the persisted copy, so turning a source on or off also cost the user everything
     * queued behind the current track - immediately in the queue screen, and permanently after
     * the next restart. Replacing the item in place keeps the timeline intact.
     *
     * The replacement carries the media id as its URI again - the same placeholder every queue
     * item is built with - so Media3's own resolving data source re-runs
     * [resolvePlaybackDataSpec] for this item and follows the new engine order. Pinning the URL
     * resolved here instead would freeze the item on the engine that was just switched off (or
     * hide the one just switched on) for every later seek and re-open.
     */
    private fun reResolveCurrentTrackForSourceToggle() {
        val index = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return
        val mediaId = item.mediaId
        if (mediaId.isBlank() || mediaId.isLocalMediaId() || index < 0) return
        val wasPlaying = player.isPlaying
        val position = player.currentPosition
        scope.launch(SilentHandler) {
            runCatching {
                player.pause()
                player.replaceMediaItem(index, item.buildUpon().setUri(mediaId).build())
                player.seekTo(index, position)
                player.prepare()
                player.playWhenReady = wasPlaying
                // The queue size is part of the contract, not decoration: a source toggle must
                // cost the user nothing but the stream they were listening to. A regression
                // here is invisible on screen until the next restart, so it is recorded where
                // a shell can read it.
                app.hush.music.spotiflac.SpotiFLACDiag.log(
                    "source toggle: re-opened current item index=$index queueItems=${player.mediaItemCount}",
                )
            }.onFailure { error ->
                Timber.tag(TAG).w(error, "Failed to re-open current track after source toggle")
            }
        }
    }

    /**
     * Media ids the user explicitly asked to hear from YouTube, once.
     *
     * The player's error screen offers this when nothing enabled can play a track. It
     * exists because both alternatives are worse: silently rewriting the user's source
     * settings, or leaving them with a track that cannot be played at all. It is one-shot
     * and in memory only - a decision about one song never edits the saved configuration.
     */
    private val forceYouTubeOnceMediaIds =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /**
     * Plays the current track from YouTube even though YouTube, or the YouTube fallback, is
     * switched off. Called by the player's error action; the settings are left alone.
     *
     * The permission is for one resolve, but the URL that resolve produces is cached like
     * any other, so the track keeps playing from YouTube until that URL expires - the user
     * asked for this song, not for one playthrough of it.
     */
    fun playCurrentTrackFromYouTube(): Boolean {
        val mediaId = player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return false
        if (forceYouTubeOnceMediaIds.size >= MAX_FORCE_YOUTUBE_ONCE) forceYouTubeOnceMediaIds.clear()
        forceYouTubeOnceMediaIds.add(mediaId)
        // Drop whatever was resolved for this track before, so the next resolve consults the
        // override instead of handing back a remembered URL for another engine.
        playbackUrlCache.remove(mediaId)
        extractorPlaybackUrlCache.remove(mediaId)
        app.hush.music.spotiflac.SpotiFLACDiag.log("one-shot YouTube override mediaId=$mediaId")
        player.prepare()
        player.play()
        return true
    }

    /**
     * What a provider sweep would be attempted with right now.
     *
     * A remembered miss is only an answer to *this* question: which sources are enabled,
     * at which quality, with a session and a runtime available. Change any of them and the
     * old answer is discarded, so enabling a provider or re-authenticating brings the
     * track straight back to SpotiFLAC instead of waiting out a retention window.
     */
    private fun spotiflacMissContext(): String {
        val enabled =
            runCatching { extensionRepoManager.getEnabledSourceIds() }.getOrDefault(emptyList())
        return app.hush.music.spotiflac.SpotiFLACMissPolicy.contextFingerprint(
            enabledSourceIds = enabled,
            qualityBucket =
                app.hush.music.spotiflac.SpotiFLACPlaybackCache.qualityBucket(spotiFLACNativeQuality()),
            runtimeAvailable =
                runCatching { spotiflacNativeRuntime.isRuntimeAvailable }.getOrDefault(false),
            // A source that completed verification can download now, so every miss
            // recorded before that is stale by definition.
            usableSourceIds = enabled.filter {
                runCatching {
                    spotiflacNativeRuntime.sourceAuthState(it) !=
                        app.hush.music.spotiflac.SpotiFLACSourceAuthState.NEEDS_VERIFICATION
                }.getOrDefault(true)
            },
        )
    }

    /**
     * [reason] is not decoration: a sweep that timed out proves nothing, so it is trusted
     * for minutes, while a sweep that finished and matched nothing is trusted for hours.
     */
    private fun recordSpotiFLACMiss(
        mediaId: String,
        reason: String,
    ) {
        spotiFLACMissMemo.record(mediaId, spotiflacMissContext(), reason)
    }

    private fun clearSpotiFLACMiss(mediaId: String) {
        spotiFLACMissMemo.remove(mediaId)
    }

    private fun spotiflacRecentlyMissed(mediaId: String): Boolean =
        spotiFLACMissMemo.isMissed(mediaId, spotiflacMissContext())

    /**
     * Runs one SpotiFLAC provider sweep, unless the miss memo already knows the answer.
     *
     * Without the memo this is the expensive part of playing an unmatched track: the sweep
     * runs to the fallback timeout on every play, including the first play after every app
     * start. With it, the second attempt costs nothing and still ends in the same place -
     * which engine the failure falls through to is decided by the caller, not here.
     */
    /**
     * One SpotiFLAC sweep, and what it actually established.
     *
     * The sweep used to answer with `PlaybackData?`, which put "no provider has this
     * track" and "the sweep could not ask" in the same value. Both then took the long
     * miss retention, so one moment of bad connectivity could take a track away from
     * SpotiFLAC for hours. Carrying the outcome is what makes the difference visible.
     */
    private data class SpotiFLACSweep(
        val data: YTPlayerUtils.PlaybackData?,
        val outcome: app.hush.music.spotiflac.SpotiFLACSweepOutcome,
        val detail: String? = null,
    ) {
        companion object {
            fun resolved(data: YTPlayerUtils.PlaybackData) =
                SpotiFLACSweep(data, app.hush.music.spotiflac.SpotiFLACSweepOutcome.RESOLVED)

            fun noMatch(detail: String?) =
                SpotiFLACSweep(null, app.hush.music.spotiflac.SpotiFLACSweepOutcome.NO_MATCH, detail)

            fun unavailable(detail: String?) =
                SpotiFLACSweep(null, app.hush.music.spotiflac.SpotiFLACSweepOutcome.UNAVAILABLE, detail)
        }
    }

    private suspend fun resolveSpotiFLACTrackWithMemo(mediaId: String): YTPlayerUtils.PlaybackData? {
        val context = spotiflacMissContext()
        spotiFLACMissMemo.entry(mediaId, context)?.let { remembered ->
            app.hush.music.spotiflac.SpotiFLACDiag.log(
                "provider sweep skipped mediaId=$mediaId: remembered miss (${remembered.reason})",
            )
            return null
        }

        // A timeout is folded in as `unavailable` rather than tracked with a separate
        // "did it finish" flag: "the sweep never returned" and "the sweep could not ask"
        // are the same statement about the providers, and neither is a verdict. The
        // resolvers re-throw cancellation, which is what keeps a superseded sweep from
        // arriving here as a finished one.
        // The budget has to cover the work the sweep now contains. With the lossy retry a
        // source that cannot serve the selected quality is asked twice, so a fixed window
        // sized for one attempt per source cuts the chain off part-way - and the sources
        // behind the cut were never asked, which is indistinguishable from a catalogue miss.
        val enabledSourceIds =
            runCatching { extensionRepoManager.getEnabledSourceIds() }.getOrDefault(emptyList())
        // Sized from what the sweep will actually walk. The enabled list is empty during a cold start
        // - the registry sync has not landed yet - while the sweep still walks the installed
        // packages, so a budget sized from the empty list allowed one provider's worth of work for a
        // six-provider chain and the providers behind the cut were never asked. Measured on device:
        // `sweep budget ... sources=0 perAttemptMs=25000 budgetMs=50000` for a six-provider sweep.
        val sweepSourceCount =
            maxOf(enabledSourceIds.size, spotiflacNativeRuntime.installedSweepSourceCount())
        val perAttemptMs = spotiflacNativeRuntime.sweepAttemptWindowMs(enabledSourceIds)
        val sweepBudgetMs =
            app.hush.music.spotiflac.SpotiFLACQualityCascade.sweepBudgetMs(
                sourceCount = sweepSourceCount,
                quality = spotiFLACNativeQuality(),
                // The window each attempt will actually be given, measured on this device. A budget
                // sized from a constant shorter than the windows cuts the chain off part-way, and
                // the sources behind the cut look exactly like sources that had nothing.
                perAttemptMs = perAttemptMs,
            )
        // Said out loud because the budget decides whether the last providers are ever asked: a
        // sweep that ends on the budget looks exactly like a catalogue miss, and the difference is
        // only visible in these numbers.
        app.hush.music.spotiflac.SpotiFLACDiag.log(
            "sweep budget mediaId=$mediaId sources=$sweepSourceCount " +
                "perAttemptMs=$perAttemptMs budgetMs=$sweepBudgetMs",
        )
        var sweep =
            withTimeoutOrNull(sweepBudgetMs) {
                resolveSpotiFLACTrack(mediaId)
            } ?: SpotiFLACSweep.unavailable("sweep did not finish within ${sweepBudgetMs}ms")

        if (sweep.data == null) {
            // A sweep that was cut off at the budget or ended empty is the moment to ask the relay
            // whether it is refusing this client. Measured on the blocked device, the entire 96s
            // budget went on six providers whose failures were each their own - a 404, an invalid
            // ASIN, a metadata stall - and the client-wide block was invisible in every one of them,
            // so the user was left holding a source error with no fix in it. One probe names the
            // block, and its answer replaces a detail that only describes the symptom.
            app.hush.music.spotiflac.SpotiFLACSessionRenewer
                .probeRelayBlock(this, reason = "sweep-empty")
                ?.let { block ->
                    val left =
                        app.hush.music.spotiflac.SpotiFLACSessionRenewer
                            .formatBlockRemaining(block.remainingMs)
                    app.hush.music.spotiflac.SpotiFLACDiag.log(
                        "sweep blocked at the gateway: ${block.reason} ($left left)",
                    )
                    sweep =
                        SpotiFLACSweep.unavailable(
                            "SpotiFLAC is temporarily blocked (HTTP 429) for this connection - " +
                                "$left left",
                        )
                }
        }

        sweep.data?.let { resolved ->
            clearSpotiFLACMiss(mediaId)
            return resolved
        }

        val reason = app.hush.music.spotiflac.SpotiFLACMissPolicy.reasonFor(sweep.outcome)
        app.hush.music.spotiflac.SpotiFLACDiag.log(
            "sweep outcome mediaId=$mediaId outcome=${sweep.outcome} reason=$reason detail=${sweep.detail}",
        )
        recordSpotiFLACMiss(mediaId = mediaId, reason = reason)
        return null
    }

    private fun cachedPlaybackUrl(
        mediaId: String,
        publishLabel: Boolean = true,
    ): AuthScopedCacheValue? {
        val authFingerprint = playbackAuthFingerprint()
        return playbackUrlCache[mediaId]?.takeIf { cached ->
            cached.isValidFor(
                authFingerprint = authFingerprint,
                minimumRemainingMs = YTPlayerUtils.STREAM_URL_EXPIRY_SAFETY_MS,
            )
        }?.also { cached ->
            if (publishLabel) publishPlaybackClientLabel(mediaId, cached.playbackClientLabel)
        }
    }

    private fun publishPlaybackClientLabel(
        mediaId: String,
        label: String?,
    ) {
        if (label.isNullOrBlank()) return
        val published = mediaId to labelWithDeviceFileAttribute(mediaId, label)
        // Logged for every publish, not just the download branch's: the label is what the player reads
        // to decide whether the audio is a stream or a file, and "which label won" is the first
        // question whenever that line looks wrong.
        app.hush.music.spotiflac.SpotiFLACDiag.log(
            "playback label mediaId=$mediaId -> ${published.second}",
        )
        if (lastPublishedPlaybackClient == published) return
        scope.launch {
            val isCurrentTrack =
                withContext(Dispatchers.Main) {
                    currentMediaMetadata.value?.id == mediaId ||
                        player.currentMediaItem?.mediaId == mediaId
                }
            if (!isCurrentTrack) {
                // Said out loud because it is invisible in the UI: a label for a track that is no longer
                // current is discarded, and the player then keeps describing the previous one.
                app.hush.music.spotiflac.SpotiFLACDiag.log(
                    "playback label mediaId=$mediaId dropped: it is not the current track",
                )
                return@launch
            }
            lastPublishedPlaybackClient = published
            activePlaybackClientLabel.value = published.second
        }
    }

    private suspend fun resolveAndCachePlaybackUrl(
        mediaId: String,
        preferredClientOverride: PlayerStreamClient? = null,
    ): AuthScopedCacheValue {
        val resolutionKey =
            PlaybackResolutionKeys.of(
                mediaId = mediaId,
                preferredClientOverride = preferredClientOverride?.name,
            )
        playbackUrlResolutionInFlight[resolutionKey]?.let { existing ->
            return existing.await()
        }

        // The shared request belongs to the service IO scope, not to the first caller's
        // coroutine. Media3 may cancel one resolver callback while another callback for
        // the same item is still active; tying the deferred to that callback would cancel
        // the request and make the next callback start a duplicate player request.
        lateinit var candidate: Deferred<AuthScopedCacheValue>
        candidate =
            ioScope.async(Dispatchers.IO + SilentHandler, start = CoroutineStart.LAZY) {
                try {
                    resolveAndCachePlaybackUrlInternal(mediaId, preferredClientOverride)
                } finally {
                    playbackUrlResolutionInFlight.remove(resolutionKey, candidate)
                }
            }
        val existing = playbackUrlResolutionInFlight.putIfAbsent(resolutionKey, candidate)
        if (existing != null) {
            candidate.cancel()
            return existing.await()
        }

        candidate.start()
        return candidate.await()
    }

    private suspend fun resolveAndCachePlaybackUrlInternal(
        mediaId: String,
        preferredClientOverride: PlayerStreamClient? = null,
    ): AuthScopedCacheValue {
        // A track this device already owns as a file needs no URL and no sweep: the data source
        // serves the file itself, so resolving here spends a provider sweep (or a YouTube
        // lookup) on bytes that are already on disk. Measured on the reporting device: a song
        // playing from its own download still ran a full eight-source sweep a second later, and
        // the lookahead had already fetched a second copy of it into the cache.
        //
        // Put here rather than in each caller because the callers differ - lookahead, proactive
        // refresh, 403 recovery, route recovery - and the question is the same one every time. A
        // file is source-independent, never expires, and cannot be rate-limited, so it is always
        // the right answer.
        ownedLocalPlaybackFile(mediaId)?.let { local ->
            app.hush.music.spotiflac.SpotiFLACDiag.log(
                "url resolve skipped mediaId=$mediaId: already on disk (${local.name})",
            )
            return AuthScopedCacheValue(
                url = local.toUri().toString(),
                expiresAtMs = Long.MAX_VALUE,
                authFingerprint = playbackAuthFingerprint(),
                playbackClientLabel = null,
                isYouTubeStream = false,
            )
        }

        // Routing must never run on a stale toggle mirror: it decides both which
        // engine is tried first and whether a failed SpotiFLAC resolve may fall back.
        refreshSourceToggleMirrors()

        // Resolve the active engines and their order BEFORE consulting the cache, because
        // the order is what decides whether a cached value is allowed to short-circuit.
        //
        // The SpotiFLAC switch is the user's *intent*; this is the engine's *state*. An engine with
        // no usable source is paused rather than consulted: it would spend the sweep budget on
        // sources the runtime refuses to download with and hand back a failure for a track YouTube
        // plays at once. See SpotiFLACAvailability for the rule and its two failure directions.
        val spotiflacStatus = spotiflacEngineStatus()
        val spotiflacAvailable = spotiflacStatus.usable
        if (spotiflacEnabled && !spotiflacAvailable) {
            app.hush.music.spotiflac.SpotiFLACDiag.log(
                "spotiflac engine ${spotiflacStatus.state}: ${spotiflacStatus.detail}",
            )
        }
        val ytAvailable = isYouTubeStreamingEnabled()
        // A one-shot request from the player's error screen makes YouTube available for this
        // track alone, so it is read before the "nothing is enabled" verdict and consumed
        // here: the very next resolve of this media id is the one that was asked for.
        // Read without consuming: a resolve that is superseded by a skip or that fails
        // outright must not spend the permission the user granted. Consuming it up front
        // meant tapping "play this one from YouTube" could silently do nothing forever
        // after, with no sign that the tap had been used by an attempt that never played.
        val forceYouTubeOnce = mediaId in forceYouTubeOnceMediaIds
        if (!spotiflacAvailable && !ytAvailable && !forceYouTubeOnce) {
            // Naming which engine is off, and why: with SpotiFLAC paused for want of a session,
            // "both sources are disabled" is a lie the user cannot act on - the switch beside it
            // says SpotiFLAC is on.
            throw IOException(
                if (spotiflacEnabled) {
                    "SpotiFLAC is ${spotiflacStatus.headline.lowercase()}. " + spotiflacStatus.detail +
                        " YouTube is switched off, so nothing can play this track."
                } else {
                    "Both YouTube and SpotiFLAC sources are disabled"
                },
            )
        }
        // The saved order is only consulted for engines that are actually enabled: a
        // switched-off engine can never be first, so with a single active source there
        // is nothing to choose and it wins regardless of what the order still says.
        val engineOrder = effectiveEngineOrder(spotiflacAvailable, ytAvailable)
        val engineOrderLabel = engineOrder.joinToString(",")
        val spotiflacFirst = engineOrder.firstOrNull() == PlaybackEngineOrder.SPOTIFLAC
        val youtubeFirst = !spotiflacFirst && ytAvailable

        // A cached value may only short-circuit when it came from the engine that would
        // be chosen first anyway. Letting any valid cached URL win meant a YouTube URL
        // cached by an earlier play (or by a SpotiFLAC fallback, or persisted from a
        // session where YouTube was first) kept winning for the URL's whole lifetime —
        // up to ~6 hours — even after SpotiFLAC was moved to the top of the priority
        // list. A cached local SpotiFLAC file is always allowed: it is source-
        // independent, never expires, and re-resolving it on every seek caused stalls.
        val cachedEntry = cachedPlaybackUrl(mediaId, publishLabel = false)
        if (cachedEntry != null) {
            val cachedFromFirstEngine =
                PlaybackEngineOrder.cachedValueMayShortCircuit(
                    spotiflacFirst = spotiflacFirst,
                    cachedIsYouTubeStream = cachedEntry.isYouTubeStream,
                    spotiflacRecentlyMissed = spotiflacRecentlyMissed(mediaId),
                )
            if (cachedFromFirstEngine) {
                publishPlaybackClientLabel(mediaId, cachedEntry.playbackClientLabel)
                return cachedEntry
            }
            // Drop the YouTube entry so this play actually honours the priority order.
            playbackUrlCache.remove(mediaId)
            extractorPlaybackUrlCache.remove(mediaId)
            app.hush.music.spotiflac.SpotiFLACDiag.log(
                "cache short-circuit skipped mediaId=$mediaId: cached YouTube URL but SpotiFLAC is first",
            )
        }

        // Skip cached YouTube URLs when YouTube is disabled entirely.
        if (!ytAvailable) {
            playbackUrlCache.entries.removeIf { (key, value) ->
                key == mediaId && value.isYouTubeStream
            }
            extractorPlaybackUrlCache.entries.removeIf { (key, value) ->
                key == mediaId && value.isYouTubeStream
            }
        }

        val lowDataModeActive = isLowDataModeActive()
        val effectiveAudioQuality = resolveEffectiveAudioQuality(audioQuality, lowDataModeActive)
        val networkMeteredHint =
                if (lowDataModeActive && audioQuality != AudioQuality.HIGHEST) {
                    true
                } else {
                    null
                }
        val effectiveStreamClient = preferredClientOverride ?: activeStreamClient
        val hiResLosslessSelected = effectiveStreamClient == PlayerStreamClient.HI_RES_LOSSLESS

        Timber.tag("MusicService").i(
            "Source routing: spotiflacEnabled=$spotiflacEnabled ytEnabled=$ytAvailable engines=$engineOrderLabel mediaId=$mediaId",
        )
        app.hush.music.spotiflac.SpotiFLACDiag.log(
            "routing spotiflac=$spotiflacAvailable yt=$ytAvailable engines=$engineOrderLabel mediaId=$mediaId",
        )

        val playbackData: YTPlayerUtils.PlaybackData

        if (forceYouTubeOnce) {
            app.hush.music.spotiflac.SpotiFLACDiag.log("one-shot YouTube override honoured mediaId=$mediaId")
            playbackData =
                resolveYouTubePlayback(
                    mediaId,
                    effectiveAudioQuality,
                    networkMeteredHint,
                    hiResLosslessSelected,
                    effectiveStreamClient,
                )
            // Spent now, and only now: the permission is for one play, and a play that
            // never happened is not one.
            forceYouTubeOnceMediaIds.remove(mediaId)
        } else if (!ytAvailable && spotiflacAvailable) {
            // The user's most common configuration for this failure: YouTube switched off,
            // so a track SpotiFLAC cannot match has no other engine to fall through to. The
            // memo is what keeps every replay from re-running the sweep to reach the same
            // "no" before showing the error and the one-tap YouTube action.
            Timber.tag(TAG).i("YouTube disabled; forcing SpotiFLAC-only resolution")
            playbackData =
                resolveSpotiFLACTrackWithMemo(mediaId)
                    ?: throw spotiflacNoMatchWhileYouTubeOff(mediaId)
        } else if (spotiflacFirst) {
            playbackData = resolveSpotiFLACWithFallback(
                mediaId, effectiveAudioQuality, networkMeteredHint,
                hiResLosslessSelected, effectiveStreamClient,
            )
        } else if (youtubeFirst) {
            playbackData = resolveYouTubeWithSpotiFLACFallback(
                mediaId, effectiveAudioQuality, networkMeteredHint,
                hiResLosslessSelected, spotiflacAvailable, effectiveStreamClient,
            )
        } else {
            playbackData = resolveSpotiFLACWithFallback(
                mediaId, effectiveAudioQuality, networkMeteredHint,
                hiResLosslessSelected, effectiveStreamClient,
            )
        }

        applyResolvedPlaybackData(mediaId, playbackData)

        // For YouTube streams, derive a source label from the ACTUAL stream client
        // that produced the URL (not the preferred one, which may have fallen back).
        val resolvedLabel =
            if (playbackData.isYouTubeStream) {
                val clientName = playbackData.playbackClientLabel
                val friendly =
                    when (clientName?.uppercase()) {
                        "WEB_REMIX" -> "YouTube • Web"
                        "ANDROID_VR" -> "YouTube • VR"
                        "ANDROID_MUSIC" -> "YouTube • YTMusic"
                        "IOS" -> "YouTube • iOS"
                        "TVHTML5", "TVHTML5_SIMPLIFIED" -> "YouTube • TV"
                        "ARCHIVETUNE_EXTRACTOR" -> "YouTube • Extractor"
                        "HI_RES_LOSSLESS" -> "YouTube • Hi-Res"
                        "VISIONOS" -> "YouTube • Vision"
                        else -> null
                    }
                friendly ?: clientName?.takeIf { it.isNotBlank() }
            } else null

        val cacheValue =
                AuthScopedCacheValue(
                    url = playbackData.streamUrl,
                    expiresAtMs = System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L),
                    authFingerprint = playbackData.authFingerprint,
                    playbackClientLabel = resolvedLabel,
                    isYouTubeStream = playbackData.isYouTubeStream,
                )
        playbackUrlCache[mediaId] = cacheValue
        publishPlaybackClientLabel(mediaId, resolvedLabel)
        return cacheValue
    }

    private suspend fun resolveSpotiFLACWithFallback(
        mediaId: String,
        effectiveAudioQuality: AudioQuality,
        networkMeteredHint: Boolean?,
        hiResLosslessSelected: Boolean,
        preferredStreamClient: PlayerStreamClient = activeStreamClient,
    ): YTPlayerUtils.PlaybackData {
        // The sweep records its own verdict in the miss memo, and is skipped outright when
        // that memo already holds one; see [resolveSpotiFLACTrackWithMemo].
        val spotiflacResult = resolveSpotiFLACTrackWithMemo(mediaId)
        if (spotiflacResult != null) return spotiflacResult

        if (!isYouTubeStreamingEnabled()) {
            Timber.tag(TAG).w("SpotiFLAC failed for %s and YouTube is disabled; no fallback", mediaId)
            app.hush.music.spotiflac.SpotiFLACDiag.log(
                "no fallback mediaId=$mediaId: youtube disabled in audio sources",
            )
            throw spotiflacNoMatchWhileYouTubeOff(mediaId)
        }
        // Explicit user control: when the fallback is off, a failed SpotiFLAC
        // resolve must surface an error instead of silently switching engines.
        if (!spotiflacAllowYouTubeFallback) {
            Timber.tag(TAG).w("SpotiFLAC failed for %s and the YouTube fallback is off", mediaId)
            app.hush.music.spotiflac.SpotiFLACDiag.log(
                "no fallback mediaId=$mediaId: 'fall back to YouTube' is disabled",
            )
            throw spotiflacNoMatchAndFallbackOff(mediaId)
        }
        // Falling back is the whole point of the second source: say so, because a
        // silent engine switch is indistinguishable from a stutter in the UI.
        Timber.tag(TAG).i("SpotiFLAC could not resolve %s; falling back to YouTube", mediaId)
        app.hush.music.spotiflac.SpotiFLACDiag.log(
            "falling back to YouTube for mediaId=$mediaId",
        )

        return resolveYouTubePlayback(
            mediaId, effectiveAudioQuality, networkMeteredHint, hiResLosslessSelected,
            preferredStreamClient,
        )
    }

    private suspend fun resolveYouTubeWithSpotiFLACFallback(
        mediaId: String,
        effectiveAudioQuality: AudioQuality,
        networkMeteredHint: Boolean?,
        hiResLosslessSelected: Boolean,
        spotiflacAvailable: Boolean,
        preferredStreamClient: PlayerStreamClient = activeStreamClient,
    ): YTPlayerUtils.PlaybackData {
        // Not runCatching: it swallows CancellationException, and this function's whole
        // job on failure is to run a *provider sweep*. Swallowing cancellation here meant
        // a skipped track kept a full sweep running in a cancelled coroutine - and, if
        // that sweep found the track, this returned it as though the YouTube attempt had
        // succeeded, from a coroutine nobody was waiting for.
        val ytError: Throwable? =
            try {
                return resolveYouTubePlayback(
                    mediaId, effectiveAudioQuality, networkMeteredHint, hiResLosslessSelected,
                    preferredStreamClient,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                throwable
            }

        if (!spotiflacAvailable) {
            throw ytError ?: java.io.IOException("YouTube playback failed and SpotiFLAC is not available")
        }

        val spotiflacResult = resolveSpotiFLACTrackWithMemo(mediaId)
        if (spotiflacResult != null) return spotiflacResult

        throw ytError ?: java.io.IOException("Both YouTube and SpotiFLAC playback failed")
    }

    private suspend fun resolveSpotiFLACTrack(mediaId: String): SpotiFLACSweep {
        Timber.tag("MusicService").i("SpotiFLAC playback requested for mediaId=$mediaId")

        // Prefer the current upstream SpotiFLAC runtime. It executes the signed
        // extension package, downloads/validates the audio to an app-owned file,
        // and hands Media3 a local file URI. The old /dl/* URL resolver remains
        // below only for older relays and is never a YouTube fallback.
        val nativeSweep =
            try {
                resolveSpotiFLACNativeTrack(mediaId)
            } catch (cancellation: CancellationException) {
                // A cancelled resolve says nothing about this track. Swallowing it turned
                // "the request was superseded" (a skip, a seek, a re-open, or a duplicate
                // in-flight resolve) into "SpotiFLAC has no match", which sent the caller
                // down the YouTube fallback - and with YouTube switched off that surfaced as
                // a bare "No stream available" error for tracks SpotiFLAC serves perfectly
                // well on the next attempt. Re-throwing is also what keeps this coroutine
                // from continuing to work after it has been cancelled.
                app.hush.music.spotiflac.SpotiFLACDiag.log(
                    "native path cancelled mediaId=$mediaId (not treated as a miss)",
                )
                throw cancellation
            } catch (throwable: Throwable) {
                app.hush.music.spotiflac.SpotiFLACDiag.log(
                    "native path failed mediaId=$mediaId type=${throwable::class.simpleName} msg=${throwable.message}",
                )
                Timber.tag("MusicService").w(throwable, "Native SpotiFLAC runtime unavailable for $mediaId")
                // A thrown failure is the runtime reporting *why* it could not deliver,
                // which is exactly the evidence that decides verdict vs. blocked.
                SpotiFLACSweep.noMatch(throwable.message)
                    .takeIf {
                        app.hush.music.spotiflac.SpotiFLACSweepVerdict.forFailure(throwable) ==
                            app.hush.music.spotiflac.SpotiFLACSweepOutcome.NO_MATCH
                    } ?: SpotiFLACSweep.unavailable(
                    "native resolve failed: ${throwable::class.simpleName}: ${throwable.message}",
                )
            }
        nativeSweep.data?.let { resolved ->
            app.hush.music.spotiflac.SpotiFLACDiag.log("native path SUCCEEDED mediaId=$mediaId")
            return SpotiFLACSweep.resolved(resolved)
        }
        app.hush.music.spotiflac.SpotiFLACDiag.log(
            "native path produced nothing mediaId=$mediaId outcome=${nativeSweep.outcome} " +
                "detail=${nativeSweep.detail ?: "-"}",
        )

        // The native sweep is the only resolver there is, so its verdict is the answer the caller
        // gets: "every source answered no" is a real catalogue verdict - recorded as one, and asked
        // again far less often - while anything else means the question was never put to a provider.
        //
        // A second, legacy resolver used to decide this, and it required Hush's own gateway session
        // before it would even run. That session is minted for Hush's client version while every
        // extension signs as `<id>@<version>`, so it can serve no source; and the gateway now answers
        // its bootstrap with a challenge this app has no way to complete (see SpotiFLACInstallIdentity).
        // So every native miss came back as "no active SpotiFLAC session": no catalogue verdict was
        // ever recorded, a track no provider has was re-swept on every replay, and the reason shown
        // blamed a session that plays no part in playback.
        return when (nativeSweep.outcome) {
            app.hush.music.spotiflac.SpotiFLACSweepOutcome.NO_MATCH ->
                SpotiFLACSweep.noMatch(nativeSweep.detail)
            else ->
                SpotiFLACSweep.unavailable(
                    "native=${nativeSweep.outcome}/${nativeSweep.detail ?: "-"}",
                )
        }
    }

    /**
     * Reports that [extensionId] blocked a download with a verification requirement.
     *
     * This is deliberately passive. It raises a notice the UI can show and stops there; it
     * does not open the Audio Sources screen and does not start an activity. Playback used
     * to do both, which is why the app appeared to jump to the audio-source settings by
     * itself partway through a track - once per unresolvable track, for as long as the
     * session stayed unverified. Background renewal is attempted before the resolver ever
     * gets here, so the user decides whether the challenge is worth doing now.
     */
    private fun reportSpotiFLACVerificationNeeded(extensionId: String? = null) {
        app.hush.music.spotiflac.SpotiFLACDiag.log("SpotiFLAC verification required ext=$extensionId")
        // With the automatic run in flight the notice would only be noise: it is a
        // prompt for the user to do by hand what is already being done for them.
        if (app.hush.music.spotiflac.SpotiFLAutoVerifier.isRunning) return
        app.hush.music.spotiflac.SpotiFLACVerificationRequest.request(extensionId)
    }

    private suspend fun resolveSpotiFLACNativeTrack(mediaId: String): SpotiFLACSweep {
        // The queue can be momentarily empty while a source toggle rebuilds the
        // timeline, so metadata falls back to the database instead of bailing:
        // SpotiFLAC only needs title/artist/duration to resolve a track.
        val item = withContext(Dispatchers.Main) {
            player.currentMediaItem?.takeIf { it.mediaId == mediaId }
                ?: player.mediaItems.firstOrNull { it.mediaId == mediaId }
        }
        val dbSong = withContext(Dispatchers.IO) { database.song(mediaId).first() }
        if (item == null && dbSong == null) {
            app.hush.music.spotiflac.SpotiFLACDiag.log("native skip: no queue item or db song for mediaId=$mediaId")
            return SpotiFLACSweep.unavailable("no queue item or db song")
        }
        val metadata = item?.metadata
        val title = item?.mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() }
            ?: dbSong?.song?.title ?: run {
            app.hush.music.spotiflac.SpotiFLACDiag.log("native skip: no title for mediaId=$mediaId")
            return SpotiFLACSweep.unavailable("no title to match on")
        }
        val artist = item?.mediaMetadata?.artist?.toString()?.takeIf { it.isNotBlank() }
            ?: dbSong?.artists?.joinToString(", ") { it.name }.orEmpty()
        val album = item?.mediaMetadata?.albumTitle?.toString()
            ?.takeIf { it.isNotBlank() } ?: dbSong?.song?.albumName
        // Never let an unknown duration (0) win over a known one: the item, what playback
        // recorded for this mediaId, and the database are all consulted in turn. A 0 here
        // used to become a distinct cache identity for a song that already had a file.
        val durationMs =
            app.hush.music.spotiflac.SpotiFLACPlaybackIdentity.bestDurationMs(
                item?.mediaMetadata?.durationMs,
                app.hush.music.spotiflac.SpotiFLACPlaybackIdentity.get(mediaId)?.durationMs,
                dbSong?.song?.duration?.takeIf { it > 0 }?.times(1000L),
            )
        val extras = item?.mediaMetadata?.extras
        val isrc = extras?.getString("isrc")?.takeIf { it.isNotBlank() }
        val spotifyId = metadata?.spotifyTrackId
            ?: extras?.getString(ExtraSpotifyTrackId)?.takeIf { it.isNotBlank() }
        val quality = spotiFLACNativeQuality()
        // A download of this mediaId must resolve the exact track playback chose,
        // so remember the identity (including ISRC / Spotify id) it resolved with.
        app.hush.music.spotiflac.SpotiFLACPlaybackIdentity.record(
            mediaId = mediaId,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            isrc = isrc,
            spotifyTrackId = spotifyId,
            quality = quality,
            coverUrl = metadata?.thumbnailUrl,
        )
        val native = spotiflacNativeRuntime.resolve(
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            isrc = isrc,
            spotifyTrackId = spotifyId,
            quality = quality,
            sourceIds = extensionRepoManager.getEnabledSourceIds(),
            coverUrl = metadata?.thumbnailUrl,
            mediaId = mediaId,
        ).getOrElse { error ->
            // The runtime wraps its entire body in runCatching, so a superseded or
            // timed-out resolve arrives here as an ordinary failure result. Converting it
            // to "nothing matched" is what made a skip look like a catalogue verdict, and
            // it also defeated the caller's own cancellation handling one level up.
            if (error is CancellationException) {
                app.hush.music.spotiflac.SpotiFLACDiag.log(
                    "native resolve cancelled mediaId=$mediaId (not treated as a failure)",
                )
                throw error
            }
            app.hush.music.spotiflac.SpotiFLACDiag.log(
                "native resolve error type=${error::class.simpleName} msg=${error.message}",
            )
            val outcome = app.hush.music.spotiflac.SpotiFLACSweepVerdict.forFailure(error)
            when (error) {
                is app.hush.music.spotiflac.SpotiFLACVerificationRequiredException -> {
                    Timber.tag("MusicService").w(
                        "SpotiFLAC verification required (source=%s) — user must re-authenticate",
                        error.sourceId,
                    )
                    // The extension runtime needs its own Turnstile grant for this
                    // source. Hush's gateway session is untouched: it is a separate
                    // credential, and clearing it broke working sessions. The
                    // challenge is solved in the background so playback recovers on
                    // its own instead of waiting for the user to go and find it.
                    scope.launch(Dispatchers.IO) {
                        val pending = spotiflacNativeRuntime.pendingAuthFor(error.sourceId)
                        app.hush.music.spotiflac.SpotiFLACDiag.log(
                            if (pending != null) {
                                "pending auth for ${pending.extensionId}: ${pending.authUrl.take(160)}"
                            } else {
                                "no pending auth challenge for ${error.sourceId}"
                            },
                        )
                    }
                    scope.launch(Dispatchers.IO) {
                        // A record that merely lapsed is refreshable against the gateway without
                        // any Cloudflare check, and the runtime refuses it either way - so the
                        // refusal alone is not proof the user has anything to do. Restore what
                        // the app already holds and renew what the gateway will renew, and only
                        // raise the notice for what is genuinely left. Without this, one expired
                        // record produced a verification prompt for a source the app could have
                        // fixed by itself.
                        val blocking = runCatching {
                            spotiflacNativeRuntime.sourcesNeedingUserVerification()
                        }.getOrDefault(emptyList())
                        val sourceId = error.sourceId
                        val recovered = sourceId != null && runCatching {
                            spotiflacNativeRuntime.isSourceVerified(sourceId)
                        }.getOrDefault(false)
                        if (recovered) {
                            app.hush.music.spotiflac.SpotiFLACDiag.log(
                                "playback: $sourceId became usable by renewal - no challenge needed",
                            )
                            // Same entry point every other surface reports through, so a track
                            // parked on this source resumes and the notice clears.
                            app.hush.music.spotiflac.SpotiFLAutoVerifier.notifyVerified(sourceId)
                            return@launch
                        }
                        val needsUser = blocking.ifEmpty { listOfNotNull(sourceId) }
                        if (needsUser.isEmpty()) return@launch
                        app.hush.music.spotiflac.SpotiFLAutoVerifier.enqueue(needsUser, "playback")
                        withContext(Dispatchers.Main) {
                            reportSpotiFLACVerificationNeeded(sourceId)
                        }
                    }
                }
                is app.hush.music.spotiflac.SpotiFLACRelayBlockedException -> {
                    // Nothing about this track or this source is wrong: the gateway is refusing this
                    // connection's address for a while, and every provider would answer identically.
                    // No verification is raised - the challenge endpoint sits inside the same block,
                    // so the check could not be completed anyway - and the message the user sees is
                    // the wait, which is the one thing they can act on.
                    Timber.tag("MusicService").w(
                        "SpotiFLAC blocked by the gateway: %s",
                        error.message,
                    )
                }
                else -> Timber.tag("MusicService").w(error, "Native SpotiFLAC resolution failed for $title")
            }
            return when (outcome) {
                app.hush.music.spotiflac.SpotiFLACSweepOutcome.NO_MATCH ->
                    SpotiFLACSweep.noMatch(error.message)
                else ->
                    SpotiFLACSweep.unavailable(
                        "${error::class.simpleName}: ${error.message}",
                    )
            }
        }
        val file = native.file
        val mime = when {
            native.codec.equals("flac", true) || file.extension.equals("flac", true) -> "audio/flac"
            native.codec.equals("opus", true) || file.extension.equals("opus", true) -> "audio/ogg"
            native.codec.equals("mp3", true) || file.extension.equals("mp3", true) -> "audio/mpeg"
            else -> "audio/mp4"
        }
        val bitrate = if (native.bitDepth != null && native.sampleRate != null) {
            native.sampleRate * native.bitDepth * 2
        } else 1411
        val format = PlayerResponse.StreamingData.Format(
            itag = app.hush.music.spotiflac.SpotiFLACServedRow.ITAG,
            url = Uri.fromFile(file).toString(),
            mimeType = mime,
            bitrate = bitrate,
            width = null,
            height = null,
            contentLength = file.length(),
            quality = native.quality ?: "Lossless",
            fps = null,
            qualityLabel = native.quality ?: "Lossless",
            averageBitrate = bitrate,
            audioQuality = native.quality,
            approxDurationMs = durationMs.toString(),
            audioSampleRate = native.sampleRate ?: 44100,
            audioChannels = 2,
            loudnessDb = null,
            lastModified = null,
            signatureCipher = null,
            cipher = null,
        )
        Timber.tag("MusicService").i("SpotiFLAC native file ready: ${file.absolutePath} source=${native.sourceId}")
        return SpotiFLACSweep.resolved(
            YTPlayerUtils.PlaybackData(
                audioConfig = null,
                videoDetails = null,
                playbackTracking = null,
                format = format,
                streamUrl = Uri.fromFile(file).toString(),
                streamExpiresInSeconds = Int.MAX_VALUE,
                authFingerprint = "spotiflac-native:${native.sourceId}",
                playbackClientLabel =
                    if (native.fromCache) {
                        "SpotiFLAC • ${native.sourceId} • cached"
                    } else {
                        "SpotiFLAC • ${native.sourceId}"
                    },
                isYouTubeStream = false,
            ),
        )
    }

    /** SpotiFLAC quality request derived from the user's quality setting. */
    private fun spotiFLACNativeQuality(): String {
        val configured = dataStore.get(SpotiFLACQualityKey, "BEST")
        return when (configured.uppercase()) {
            "FLAC", "BEST", "HIGHEST" -> "LOSSLESS"
            "HIGH_RES", "HI_RES", "HI_RES_LOSSLESS" -> "HI_RES_LOSSLESS"
            else -> configured.uppercase()
        }
    }

    /**
     * Adds the "downloaded" attribute to a label for a track this device is serving from a file.
     *
     * The player reads a `YouTube` label as a live stream, so a downloaded track - whose bytes are
     * entirely on the device - announced itself as "live stream" in the very row that says where the
     * audio is coming from. Doing this at the one place labels are published, rather than at the point
     * the download was chosen, is what makes it stick: the resolve that runs for the same track
     * publishes its own label afterwards and used to overwrite the correction. Measured on device: the
     * log showed `playback label mediaId=W-KuIsdWexk -> downloaded` and the player then read
     * `YouTube · Vision (live stream)` for the file it was serving.
     *
     * A label that already names a device file (`… • cached`, or an earlier `downloaded`) is left
     * alone, so a SpotiFLAC cache hit keeps its own wording.
     */
    private fun labelWithDeviceFileAttribute(
        mediaId: String,
        label: String,
    ): String {
        if (label.contains(PlaybackSourceLabels.DOWNLOADED, ignoreCase = true)) return label
        if (PlaybackSourceLabels.parse(label).isFromDeviceFile) return label
        val isDownload =
            runCatching { downloadedFileStore.fileFor(mediaId) != null }.getOrDefault(false)
        return if (isDownload) "$label • ${PlaybackSourceLabels.DOWNLOADED}" else label
    }

    /**
     * Names a track that is playing from the user's own download when nothing else has named it.
     *
     * Offline, the resolve that would have published `YouTube • Vision` fails, so this is the only
     * label the track ever gets - and the attribute alone is enough for the player to say the audio is
     * on the device.
     */
    private fun publishDownloadedFileLabel(mediaId: String) {
        // Only this track's own label is extended: `activePlaybackClientLabel` is whichever track
        // published last, and appending to that would dress another track's name in this one's fact.
        val existing =
            lastPublishedPlaybackClient
                ?.takeIf { it.first == mediaId }
                ?.second
                ?.trim()
                .orEmpty()
        if (existing.contains(PlaybackSourceLabels.DOWNLOADED, ignoreCase = true)) return
        val label =
            if (existing.isEmpty()) {
                PlaybackSourceLabels.DOWNLOADED
            } else {
                "$existing • ${PlaybackSourceLabels.DOWNLOADED}"
            }
        publishPlaybackClientLabel(mediaId, label)
    }

    /**
     * Publishes the real SpotiFLAC source for a track served from the on-disk
     * playback cache, so the player names the source instead of defaulting to
     * YouTube whenever no resolver ran.
     */
    private fun publishSpotiFLACCachedLabel(mediaId: String) {
        if (!::spotiflacNativeRuntime.isInitialized) return
        val source = runCatching {
            spotiflacNativeRuntime.cachedPlaybackSourceForMediaId(mediaId)
        }.getOrNull()
        val label = if (source != null) "SpotiFLAC • $source • cached" else "SpotiFLAC • cached"
        app.hush.music.spotiflac.SpotiFLACDiag.log("playback label mediaId=$mediaId -> $label")
        publishPlaybackClientLabel(mediaId, label)
    }

    /**
     * Records the real format of a SpotiFLAC playback file.
     *
     * The player's codec row reads `FormatEntity` out of the database, and that row is
     * only written by the resolution path. A file served straight off disk skips
     * resolution entirely, so the row kept describing whatever engine last resolved the
     * track - typically a ~5 MB YouTube WebM - while a 30 MB FLAC was decoding. That
     * mismatch is what made a correctly-served SpotiFLAC track look like it was still
     * playing from YouTube. Publishing the file's own codec, sample rate and size keeps
     * the row and the source label describing the same audio.
     */
    private fun publishSpotiFLACFileFormat(mediaId: String) {
        if (!::spotiflacNativeRuntime.isInitialized) return
        val entry =
            runCatching { spotiflacNativeRuntime.cachedPlaybackEntryForMediaId(mediaId) }
                .getOrNull() ?: return
        val sampleRate = entry.sampleRate?.takeIf { it > 0 } ?: 44100
        val bitDepth = entry.bitDepth?.takeIf { it > 0 } ?: 16
        val extension = entry.extension?.lowercase()?.takeIf { it.isNotBlank() } ?: "flac"
        val formatEntity =
            FormatEntity(
                id = mediaId,
                itag = app.hush.music.spotiflac.SpotiFLACServedRow.ITAG,
                mimeType = "audio/$extension",
                codecs = entry.codec?.takeIf { it.isNotBlank() } ?: extension,
                // Lossless is variable-rate; this is the nominal rate implied by the
                // stream's own sample format (stereo), the same figure the SpotiFLAC
                // resolution path reports.
                bitrate = sampleRate * bitDepth * 2 / 1000,
                sampleRate = sampleRate,
                contentLength = entry.sizeBytes.takeIf { it > 0L } ?: 0L,
                loudnessDb = null,
                perceptualLoudnessDb = null,
                playbackUrl = null,
            )
        formatEntityCache[mediaId] = formatEntity
        publishedFormatEntity.value = mediaId to formatEntity
        scope.launch(Dispatchers.IO) {
            runCatching { database.query { upsert(formatEntity) } }
        }
    }

    /**
     * Cached SpotiFLAC playback file for a queue item. A user-downloaded copy always
     * wins - that check happens earlier in [resolveLocalPlaybackDataSpecIfAvailable].
     */
    private fun spotiflacPlaybackFile(mediaId: String): java.io.File? =
        if (mediaId.isBlank() || mediaId.isLocalMediaId() || !::spotiflacNativeRuntime.isInitialized) {
            null
        } else {
            runCatching { spotiflacNativeRuntime.playbackFileForMediaId(mediaId) }.getOrNull()
        }

    /**
     * The local file that already owns [mediaId], if any: the user's download, or a SpotiFLAC
     * playback file.
     *
     * The one question every network path has to ask before doing work. Both lookups are an index
     * read plus one stat, so it is cheap enough for a resolve and for a lookahead.
     */
    private fun ownedLocalPlaybackFile(mediaId: String): java.io.File? {
        if (mediaId.isBlank() || mediaId.isLocalMediaId()) return null
        val downloaded =
            if (::downloadedFileStore.isInitialized) {
                runCatching { downloadedFileStore.fileFor(mediaId) }.getOrNull()
            } else {
                null
            }
        return LocalPlaybackFiles.owned(downloaded = downloaded, cached = spotiflacPlaybackFile(mediaId))
    }

    /**
     * The SpotiFLAC engine's live state: its switch, its sources' sessions, and the gateway's block.
     *
     * Read in one place so the resolve order, the prefetch and the settings screen cannot disagree
     * about whether the engine is in play. See SpotiFLACAvailability.
     */
    private fun spotiflacEngineStatus(): app.hush.music.spotiflac.SpotiFLACEngineStatus =
        app.hush.music.spotiflac.SpotiFLACAvailability.current(
            context = this,
            enabled = spotiflacEnabled,
            sources = runCatching {
                app.hush.music.spotiflac.ExtensionRepositoryManager.getInstance()
                    .getEnabledSourceIds()
            }.getOrDefault(emptyList()),
        )

    /**
     * Prefetch the next queue item through SpotiFLAC when the user allows it *and* the engine can
     * serve.
     *
     * The availability clause is not a nicety: a paused engine's lookahead still cost a full
     * per-track provider sweep - against a gateway that was rate-limiting the device, which is what
     * put it in the paused state to begin with. Warming tracks nothing can play is how a block gets
     * longer rather than shorter.
     */
    private fun isSpotiFLACPrefetchEnabled(): Boolean =
        spotiflacEnabled &&
            spotiflacEngineStatus().usable &&
            (dataStore[app.hush.music.constants.SpotiFLACPrefetchNextKey] ?: true) &&
            !isLowDataModeActive()

    /**
     * Warms the upcoming tracks into the SpotiFLAC cache so they start without paying
     * the download cost mid-transition. Best-effort: failures are ignored on purpose.
     *
     * How many are warmed is the user's own answer rather than a second opinion: the same
     * count that decides how many stream URLs are resolved ahead (Settings -> Player ->
     * "Prefetch upcoming songs") decides this, with the toggle here as the on/off. Two
     * prefetch numbers that disagree are worse than one, because the slider then means
     * "some of what Hush does ahead" rather than "how far ahead Hush works".
     *
     * Sequential rather than parallel: these are whole lossless downloads (tens of MB
     * each), and several at once would compete for the same socket as the track that is
     * playing. Wrapping at the end keeps a short looped queue warm too.
     */
    private suspend fun prefetchNextSpotiFLACTracks() =
        withContext(Dispatchers.IO) {
            val count = dataStore.get(PrefetchCountKey, 2).coerceIn(0, 4)
            if (count <= 0) return@withContext
            val upcoming =
                withContext(Dispatchers.Main) {
                    // Which positions this count covers is pinned by SpotiFLACPrefetchPlan:
                    // a lookahead never contains the track it is warming for, and never
                    // covers the same position twice.
                    app.hush.music.spotiflac.SpotiFLACPrefetchPlan
                        .upcomingIndices(
                            total = player.mediaItemCount,
                            currentIndex = player.currentMediaItemIndex,
                            count = count,
                            wrap = queueLoops(),
                        )
                        .map { index -> player.getMediaItemAt(index) }
                }
            app.hush.music.spotiflac.SpotiFLACDiag.log(
                "prefetch plan spotiflac count=$count slots=${upcoming.size} " +
                    "ids=${upcoming.joinToString(",") { it.mediaId }}",
            )
            for (item in upcoming) {
                prefetchSpotiFLACTrack(item)
            }
        }

    /**
     * One lookahead item. A failure here is logged and the next item still gets its
     * chance: these are best-effort, and one source having nothing for one track says
     * nothing about the track after it.
     */
    private suspend fun prefetchSpotiFLACTrack(next: MediaItem) {
        runCatching {
            val mediaId = next.mediaId
            if (mediaId.isBlank() || mediaId.isLocalMediaId()) return@runCatching
            // Nothing to warm for a track that is already a file on this device. It used to be
            // skipped only when a SpotiFLAC cache file existed, so a track the user had
            // *downloaded* - whose cached copy is deliberately dropped when the download is made -
            // was fetched all over again, tens of megabytes for a song sitting in the downloads
            // folder, and left a second copy of it in the cache.
            ownedLocalPlaybackFile(mediaId)?.let { local ->
                app.hush.music.spotiflac.SpotiFLACDiag.log(
                    "prefetch spotiflac skipped mediaId=$mediaId: already on disk (${local.name})",
                )
                return@runCatching
            }
            val dbSong = withContext(Dispatchers.IO) { database.song(mediaId).first() }
            val title =
                next.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
                    ?: dbSong?.song?.title
            if (title.isNullOrBlank()) return@runCatching
            val artist =
                next.mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() }
                    ?: dbSong?.artists?.joinToString(", ") { it.name }.orEmpty()
            val durationMs =
                app.hush.music.spotiflac.SpotiFLACPlaybackIdentity.bestDurationMs(
                    next.mediaMetadata.durationMs,
                    app.hush.music.spotiflac.SpotiFLACPlaybackIdentity.get(mediaId)?.durationMs,
                    dbSong?.song?.duration?.takeIf { it > 0 }?.times(1000L),
                )
            val extras = next.mediaMetadata.extras
            val prefetchAlbum =
                next.mediaMetadata.albumTitle?.toString()?.takeIf { it.isNotBlank() }
                    ?: dbSong?.song?.albumName
            val prefetchIsrc = extras?.getString("isrc")?.takeIf { it.isNotBlank() }
            val prefetchSpotifyId =
                next.metadata?.spotifyTrackId
                    ?: extras?.getString(ExtraSpotifyTrackId)?.takeIf { it.isNotBlank() }
            // The prefetch decides which source this track plays from next, so the
            // download path must be able to reuse that same identity.
            app.hush.music.spotiflac.SpotiFLACPlaybackIdentity.record(
                mediaId = mediaId,
                title = title,
                artist = artist,
                album = prefetchAlbum,
                durationMs = durationMs,
                isrc = prefetchIsrc,
                spotifyTrackId = prefetchSpotifyId,
                quality = spotiFLACNativeQuality(),
                coverUrl = next.metadata?.thumbnailUrl,
            )
            val result =
                spotiflacNativeRuntime.resolve(
                    title = title,
                    artist = artist,
                    album = prefetchAlbum,
                    durationMs = durationMs,
                    isrc = prefetchIsrc,
                    spotifyTrackId = prefetchSpotifyId,
                    quality = spotiFLACNativeQuality(),
                    sourceIds = extensionRepoManager.getEnabledSourceIds(),
                    coverUrl = next.metadata?.thumbnailUrl,
                    mediaId = mediaId,
                )
            app.hush.music.spotiflac.SpotiFLACDiag.log(
                "prefetch next mediaId=$mediaId success=${result.isSuccess}",
            )
        }.onFailure { error ->
            // A superseded sweep arrives here as cancellation; swallowing it would keep
            // downloading for a queue nobody is looking at any more.
            if (error is CancellationException) throw error
            Timber.tag(TAG).d(error, "SpotiFLAC prefetch failed")
        }
    }

    private fun extractIsrcFromMediaItem(mediaId: String): String? {
        val item = player.currentMediaItem ?: return null
        val isrc = item.mediaMetadata.extras?.getString("isrc")
        if (!isrc.isNullOrBlank()) return isrc
        return null
    }

    private suspend fun resolveYouTubePlayback(
        mediaId: String,
        effectiveAudioQuality: AudioQuality,
        networkMeteredHint: Boolean?,
        hiResLosslessSelected: Boolean,
        preferredStreamClient: PlayerStreamClient = activeStreamClient,
    ): YTPlayerUtils.PlaybackData {
        return if (hiResLosslessSelected) {
            resolveHiResLosslessPlayback(mediaId).recoverCatching { youtubeFailure ->
                if (youtubeFailure is YTPlayerUtils.BotDetectionPlaybackException) {
                    throw youtubeFailure
                }
                // The Hi-Res attempt's own Result captures cancellation, so recovering
                // from it would start a second full resolve for a request that was
                // already superseded.
                if (youtubeFailure is CancellationException) {
                    throw youtubeFailure
                }
                retryWithoutPlaybackLoginContext {
                    YTPlayerUtils.playerResponseForPlayback(
                        videoId = mediaId,
                        audioQuality = effectiveAudioQuality,
                        connectivityManager = connectivityManager,
                        preferredStreamClient = preferredStreamClient,
                        networkMetered = networkMeteredHint,
                        context = this@MusicService,
                    )
                }.getOrThrow()
            }.getOrThrow()
        } else {
            retryWithoutPlaybackLoginContext {
                YTPlayerUtils.playerResponseForPlayback(
                    videoId = mediaId,
                    audioQuality = effectiveAudioQuality,
                    connectivityManager = connectivityManager,
                    preferredStreamClient = preferredStreamClient,
                    networkMetered = networkMeteredHint,
                    context = this@MusicService,
                )
            }.getOrThrow()
        }
    }

    private fun applyResolvedPlaybackData(
        mediaId: String,
        playbackData: YTPlayerUtils.PlaybackData,
    ) {
        playbackData.playbackTracking
            ?.remotePlaybackTrackingUrl()
            ?.let { remotePlaybackTrackingUrlCache[mediaId] = it }
        val format = playbackData.format
        val loudnessDb = playbackData.audioConfig?.loudnessDb
        val perceptualLoudnessDb = playbackData.audioConfig?.perceptualLoudnessDb
        val resolvedContentLength = format.contentLength?.takeIf { it > 0L } ?: 0L
        val resolvedCodecs =
            format.mimeType
                .substringAfter("codecs=", "")
                .removeSurrounding("\"")
                .substringBefore("\"")
        resolvedContentLength.takeIf { it > 0L }?.let { contentLengthCache[mediaId] = it }

        val formatEntity =
            FormatEntity(
                id = mediaId,
                itag = format.itag,
                mimeType = format.mimeType.split(";")[0],
                codecs = resolvedCodecs,
                bitrate = format.bitrate,
                sampleRate = format.audioSampleRate,
                contentLength = resolvedContentLength,
                loudnessDb = loudnessDb,
                perceptualLoudnessDb = perceptualLoudnessDb,
                playbackUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl,
            )
        formatEntityCache[mediaId] = formatEntity
        publishedFormatEntity.value = mediaId to formatEntity
        val resolvedNormalizationFactor = calculateAudioNormalizationFactor(formatEntity, normalizeAudio = true)
        audioNormalizationFactorCache[mediaId] = resolvedNormalizationFactor
        scope.launch {
            if (currentMediaMetadata.value?.id == mediaId &&
                dataStore.get(AudioNormalizationKey, true)
            ) {
                normalizeFactor.value = resolvedNormalizationFactor
            }
        }
        ioScope.launch(Dispatchers.IO) {
            database.query {
                upsert(formatEntity)
            }
            recoverSong(mediaId, playbackData)
        }
        playbackData.playbackClientLabel?.let { clientLabel ->
            publishPlaybackClientLabel(mediaId, clientLabel)
        }
    }

    private fun startPlaybackUrlPrefetch(mediaId: String) {
        if (mediaId.isBlank() || mediaId.isLocalMediaId()) return
        if (cachedPlaybackUrl(mediaId) != null) return
        if (hasFullyDownloadedLocalPlayback(mediaId)) return

        lateinit var deferred: Deferred<AuthScopedCacheValue?>
        deferred =
            ioScope.async(Dispatchers.IO + SilentHandler, start = CoroutineStart.LAZY) {
                try {
                    playbackUrlPrefetchSemaphore.withPermit {
                        resolveAndCachePlaybackUrl(mediaId)
                    }
                } catch (_: CancellationException) {
                    null
                } finally {
                    playbackUrlPrefetchInFlight.remove(mediaId, deferred)
                }
            }
        val existing = playbackUrlPrefetchInFlight.putIfAbsent(mediaId, deferred)
        if (existing == null) {
            deferred.start()
        } else {
            deferred.cancel()
        }
    }

    private fun cancelPlaybackUrlPrefetches() {
        playbackUrlPrefetchInFlight.forEach { (mediaId, deferred) ->
            if (playbackUrlPrefetchInFlight.remove(mediaId, deferred)) {
                deferred.cancel()
            }
        }
    }

    private fun cancelPlaybackUrlPrefetch(mediaId: String) {
        playbackUrlPrefetchInFlight.remove(mediaId)?.cancel()
    }

    private suspend fun awaitPlaybackUrlPrefetch(
        mediaId: String,
        timeoutMs: Long,
    ) {
        val deferred = playbackUrlPrefetchInFlight[mediaId] ?: return
        withContext(NonCancellable) {
            withTimeoutOrNull(timeoutMs) {
                runCatching { deferred.await() }.getOrNull()
            }
        }
    }

    private suspend fun warmPlaybackUrl(
        mediaId: String,
        maxWaitMs: Long,
    ) {
        if (mediaId.isBlank() || mediaId.isLocalMediaId()) return
        if (cachedPlaybackUrl(mediaId) != null) return
        startPlaybackUrlPrefetch(mediaId)
        if (maxWaitMs > 0L) {
            awaitPlaybackUrlPrefetch(mediaId, maxWaitMs)
        }
    }

    /**
     * Stops resolving the tracks a skip has left behind.
     *
     * The resolve for the item being skipped away from is owned by the service IO scope, so
     * Media3 cancelling that load does *not* stop it: the sweep keeps holding the extension
     * runtime until its own budget expires. The track the user skipped *to* then waits behind
     * it for that same runtime, which is why skipping during a SpotiFLAC download looked like
     * a wedged player - the transport stayed on "fetching" for as long as the abandoned sweep
     * had left to run. Cancelling the deferred unwinds the native attempt through the provider
     * watchdog's "superseded" path, which already knows how to abort it.
     *
     * Only ids nobody is waiting for are dropped, so the item now playing and anything keyed
     * to it survive.
     */
    private fun cancelAbandonedPlaybackResolutions(keepMediaIds: Set<String>) {
        val inFlight = playbackUrlResolutionInFlight
        if (inFlight.isEmpty()) return
        val abandoned = PlaybackResolutionKeys.abandoned(inFlight.keys, keepMediaIds)
        var cancelled = 0
        for (key in abandoned) {
            val deferred = inFlight.remove(key) ?: continue
            deferred.cancel()
            cancelled++
        }
        if (cancelled > 0) {
            Timber.tag(TAG).i("Cancelled %d abandoned playback resolve(s)", cancelled)
            app.hush.music.spotiflac.SpotiFLACDiag.log(
                "abandoned resolves cancelled=$cancelled keep=${keepMediaIds.joinToString(",")}",
            )
        }
    }

    fun clearIncompatiblePlaybackCache() {
        playbackUrlCache.clear()
        extractorPlaybackUrlCache.clear()
        playbackUrlResolutionInFlight.values.forEach { it.cancel() }
        playbackUrlResolutionInFlight.clear()
        cancelPlaybackUrlPrefetches()
        cancelUrlRefresh()
        lastPublishedPlaybackClient = null
    }

    /**
     * Startup/purge hook: entries restored from the persistent cache are checked
     * against the CURRENT source toggles so a YouTube URL persisted while YT was
     * enabled cannot be replayed after YT was turned off (and vice versa).
     */
    private fun purgeCacheEntriesViolatingSourceToggles() {
        val allowYt = isYouTubeStreamingEnabled()
        var purged = 0
        playbackUrlCache.entries.removeIf { (_, value) ->
            val violates = value.isYouTubeStream && !allowYt
            if (violates) purged++
            violates
        }
        extractorPlaybackUrlCache.entries.removeIf { (_, value) ->
            val violates = value.isYouTubeStream && !allowYt
            if (violates) purged++
            violates
        }
        if (purged > 0) {
            Timber.tag(TAG).i("Purged %d cached URLs violating source toggles (ytEnabled=%s)", purged, allowYt)
        }
    }

    /**
     * Proactively refresh the currently-playing stream URL before it expires.
     * Runs a periodic check every [URL_REFRESH_POLL_INTERVAL_MS] and, when the
     * cached URL is within [YTPlayerUtils.STREAM_URL_EXPIRY_SAFETY_MS] of expiry,
     * fetches a fresh URL in the background so the player never hits an HTTP 403/410.
     */
    private fun scheduleUrlRefresh() {
        cancelUrlRefresh()
        urlRefreshJob = scope.launch {
            while (isActive) {
                delay(URL_REFRESH_POLL_INTERVAL_MS)
                try {
                    val mediaId = withContext(Dispatchers.Main) {
                        player.currentMediaItem?.mediaId
                    } ?: continue
                    if (mediaId.isBlank() || mediaId.isLocalMediaId()) continue

                    val cached = playbackUrlCache[mediaId] ?: continue
                    val authFingerprint = playbackAuthFingerprint()
                    val remainingMs = cached.expiresAtMs - System.currentTimeMillis()

                    if (cached.authFingerprint != authFingerprint) {
                        // Auth changed — invalidate so next resolve picks up the new context.
                        playbackUrlCache.remove(mediaId)
                        continue
                    }

                    if (remainingMs > YTPlayerUtils.STREAM_URL_EXPIRY_SAFETY_MS) {
                        continue // URL still valid
                    }

                    Timber.tag(TAG).i(
                        "Proactive URL refresh for %s — %.0fs remaining",
                        mediaId,
                        remainingMs / 1000.0,
                    )

                    // Invalidate so resolveAndCachePlaybackUrl fetches a fresh URL.
                    playbackUrlCache.remove(mediaId)
                    extractorPlaybackUrlCache.remove(mediaId)
                    YTPlayerUtils.invalidateCachedStreamUrls(mediaId)

                    withContext(Dispatchers.IO) {
                        runCatching {
                            playbackUrlPrefetchSemaphore.withPermit {
                                resolveAndCachePlaybackUrl(mediaId)
                            }
                        }.onFailure { e ->
                            Timber.tag(TAG).w(e, "Proactive URL refresh failed for %s", mediaId)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "URL refresh poll error")
                }
            }
        }
    }

    private fun cancelUrlRefresh() {
        urlRefreshJob?.cancel()
        urlRefreshJob = null
    }

    private fun prefetchNextTracks() {
        if (!::player.isInitialized || player.playbackState != Player.STATE_READY || !player.isPlaying) return
        val count = dataStore.get(PrefetchCountKey, 2).coerceIn(0, 4)
        if (count <= 0) return

        val currentIndex = player.currentMediaItemIndex
        if (currentIndex == C.INDEX_UNSET) return

        // The same plan the SpotiFLAC lookahead uses, with the same wrap rule, so both
        // engines warm the same tracks in the same order - on a looping queue that means
        // both wrap to the front rather than one of them going cold on the last track.
        val indices =
            app.hush.music.spotiflac.SpotiFLACPrefetchPlan.upcomingIndices(
                total = player.mediaItemCount,
                currentIndex = currentIndex,
                count = count,
                wrap = queueLoops(),
            )

        // Reported because the count is a user setting shared by two engines, and "how far
        // ahead did it actually work" is otherwise invisible - the per-track lines below only
        // appear for tracks that had to be fetched at all.
        app.hush.music.spotiflac.SpotiFLACDiag.log(
            "prefetch plan stream-urls count=$count slots=${indices.size} " +
                "ids=${indices.joinToString(",") { player.getMediaItemAt(it).mediaId.orEmpty() }}",
        )

        // Warming a *stream URL* is only cheap work when YouTube is the engine being warmed.
        // With SpotiFLAC first, resolving a stream URL **is** a provider sweep - so doing this
        // for the same lookahead the SpotiFLAC prefetch is already downloading ran the
        // identical sweep twice for every track ahead of the queue: four concurrent sweeps
        // here against one sequential download there, up to twenty-four provider requests in
        // flight at once. Measured on the reporting device, that is what made the providers
        // answer `HTTP 429 for /tickets` to every later attempt, and what turned "SpotiFLAC
        // plays my music" into "everything falls through to YouTube".
        //
        // A track the SpotiFLAC lookahead is already warming needs no second opinion. One it
        // has already refused still gets one: the refusal is remembered in the miss memo, so
        // its YouTube URL is resolved at the moment the player reaches it - and only then.
        if (spotiflacLookaheadCoversTheSameTracks()) {
            app.hush.music.spotiflac.SpotiFLACDiag.log(
                "prefetch stream-urls skipped: the SpotiFLAC lookahead is already warming " +
                    "these tracks (a second sweep per track is what rate-limits the providers)",
            )
            return
        }

        for (idx in indices) {
            val mediaId = player.getMediaItemAt(idx).mediaId.orEmpty()
            if (mediaId.isNotBlank() && !mediaId.isLocalMediaId()) {
                startPlaybackUrlPrefetch(mediaId)
            }
        }
    }

    /**
     * Whether the SpotiFLAC lookahead is already warming the tracks this one would warm.
     *
     * Both lookaheads walk the same plan, so "the same tracks" is the whole lookahead, not an
     * intersection. It is true exactly when SpotiFLAC is switched on, its prefetch is on, and
     * it is the first engine - because that is when a "stream URL" resolve would run a
     * provider sweep instead of a YouTube lookup.
     */
    private fun spotiflacLookaheadCoversTheSameTracks(): Boolean =
        isSpotiFLACPrefetchEnabled() &&
            effectiveEngineOrder(
                // The engine's state, not the switch: a paused SpotiFLAC is not warming anything, so
                // the stream-URL lookahead must run - otherwise neither engine warms ahead while the
                // engine that is actually playing is the one with nothing prepared.
                spotiflacAvailable = spotiflacEngineStatus().usable,
                ytAvailable = isYouTubeStreamingEnabled(),
            ).firstOrNull() == PlaybackEngineOrder.SPOTIFLAC

    /**
     * True when the queue comes back round after its last track.
     *
     * Repeat-all is the signal both lookaheads use to decide whether to wrap. Repeat-one is
     * not: with one track repeating, nothing after it is ever played at all.
     */
    private fun queueLoops(): Boolean =
        ::player.isInitialized && player.repeatMode == Player.REPEAT_MODE_ALL

    private fun prefetchNextTrack() {
        prefetchNextTracks()
    }

    private fun shouldKeepPlaybackAudible(): Boolean {
        if (!::player.isInitialized) return false
        if (player.currentMediaItem == null || !player.playWhenReady) return false
        return player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED
    }

    private fun restoreAudioFocusVolume() {
        audioFocusVolumeFactor.value = 1f
        hasAudioFocus = true
        lastAudioFocusState = AudioManager.AUDIOFOCUS_GAIN
    }

    private fun pauseForAudioFocusLoss(resumeWhenFocusReturns: Boolean) {
        audioFocusVolumeFactor.value = 1f
        wasPlayingBeforeAudioFocusLoss = resumeWhenFocusReturns && player.playWhenReady
        if (player.playWhenReady) {
            player.pause()
        }
    }

    private fun ensureAudioFocusForActivePlayback(): Boolean {
        if (!player.playWhenReady) return true
        if (requestAudioFocus()) return true
        pauseForAudioFocusLoss(resumeWhenFocusReturns = true)
        return false
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                audioFocusVolumeFactor.value = 1f

                if (wasPlayingBeforeAudioFocusLoss) {
                    player.play()
                    wasPlayingBeforeAudioFocusLoss = false
                }

                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                pauseForAudioFocusLoss(resumeWhenFocusReturns = false)

                abandonAudioFocus()

                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                pauseForAudioFocusLoss(resumeWhenFocusReturns = true)

                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                hasAudioFocus = false
                pauseForAudioFocusLoss(resumeWhenFocusReturns = true)

                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> {
                hasAudioFocus = true
                audioFocusVolumeFactor.value = 1f

                if (wasPlayingBeforeAudioFocusLoss) {
                    player.play()
                    wasPlayingBeforeAudioFocusLoss = false
                }

                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> {
                hasAudioFocus = true
                audioFocusVolumeFactor.value = 1f

                lastAudioFocusState = focusChange
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) {
            if (audioFocusVolumeFactor.value != 1f || lastAudioFocusState == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                restoreAudioFocusVolume()
            }
            return true
        }

        audioFocusRequest?.let { request ->
            val result = audioManager.requestAudioFocus(request)
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (hasAudioFocus) {
                restoreAudioFocusVolume()
            }
            return hasAudioFocus
        }
        return false
    }

    private fun abandonAudioFocus() {
        if (hasAudioFocus) {
            audioFocusRequest?.let { request ->
                audioManager.abandonAudioFocusRequest(request)
                hasAudioFocus = false
            }
        }
    }

    fun hasAudioFocusForPlayback(): Boolean = hasAudioFocus

    private fun isDeviceMutedNow(): Boolean {
        val streamVolume =
            runCatching {
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            }.getOrElse { error ->
                reportException(error)
                return player.isDeviceMuted || player.deviceVolume <= 0
            }
        val isStreamMuted =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                runCatching {
                    audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
                }.getOrElse { error ->
                    reportException(error)
                    false
                }

        return isStreamMuted || streamVolume <= 0
    }

    private fun isTogetherGuestSession(): Boolean {
        val joined = togetherSessionState.value as? app.hush.music.together.TogetherSessionState.Joined
        return joined?.role is app.hush.music.together.TogetherRole.Guest
    }

    private fun registerMuteRecoveryObserver() {
        if (muteRecoveryObserver != null) return
        val observer =
            object : ContentObserver(Handler(mainLooper)) {
                override fun onChange(selfChange: Boolean) {
                    if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) {
                        handleDeviceMuteStateChanged()
                    }
                }
            }
        contentResolver.registerContentObserver(
            android.provider.Settings.System.CONTENT_URI,
            true,
            observer,
        )
        muteRecoveryObserver = observer
    }

    private fun unregisterMuteRecoveryObserver() {
        muteRecoveryObserver?.let { contentResolver.unregisterContentObserver(it) }
        muteRecoveryObserver = null
    }

    private fun handleDeviceMuteStateChanged(playbackRequestedWhileMuted: Boolean = false) {
        if (!pauseOnDeviceMuteEnabled || isTogetherGuestSession()) {
            wasAutoPausedByDeviceMute = false
            unregisterMuteRecoveryObserver()
            return
        }

        if (isDeviceMutedNow()) {
            if (playbackRequestedWhileMuted && restoreDeviceMusicVolumeForPlayback()) {
                wasAutoPausedByDeviceMute = false
                unregisterMuteRecoveryObserver()
                return
            }

            val canPauseNow =
                player.currentMediaItem != null &&
                    player.playWhenReady &&
                    player.playbackState != Player.STATE_IDLE &&
                    player.playbackState != Player.STATE_ENDED

            if (canPauseNow) {
                player.pause()
                wasAutoPausedByDeviceMute = true
                registerMuteRecoveryObserver()
                if (playbackRequestedWhileMuted) {
                    showDeviceMutePlaybackNotice()
                }
            }
            return
        }

        unregisterMuteRecoveryObserver()

        if (!wasAutoPausedByDeviceMute) return

        wasAutoPausedByDeviceMute = false
        val canResumeNow =
            player.currentMediaItem != null &&
                player.playbackState != Player.STATE_IDLE &&
                player.playbackState != Player.STATE_ENDED
        if (canResumeNow) {
            player.play()
        }
    }

    private fun restoreDeviceMusicVolumeForPlayback(): Boolean {
        val recoveryPercent = deviceMutePlaybackRecoveryVolumePercent.coerceIn(0, 100)
        if (recoveryPercent <= 0) return false

        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume <= 0) return false

        val targetVolume =
            ceil(maxVolume * (recoveryPercent / 100.0))
                .toInt()
                .coerceIn(1, maxVolume)

        return runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) > 0
        }.getOrElse {
            reportException(it)
            false
        }
    }

    private fun showDeviceMutePlaybackNotice() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastDeviceMutePlaybackNoticeAtElapsedMs < DEVICE_MUTE_PLAYBACK_NOTICE_INTERVAL_MS) return
        lastDeviceMutePlaybackNoticeAtElapsedMs = now
        scope.launch(SilentHandler) {
            Toast
                .makeText(
                    this@MusicService,
                    R.string.device_volume_zero_playback_paused,
                    Toast.LENGTH_SHORT,
                ).show()
        }
    }

    private val bluetoothReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
                if (!autoStartOnBluetoothEnabled) return

                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return

                val isAudioDevice =
                    try {
                        val majorClass = device.bluetoothClass?.majorDeviceClass
                        majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                            majorClass == BluetoothClass.Device.Major.WEARABLE
                    } catch (_: SecurityException) {
                        true
                    }

                if (!isAudioDevice) return

                scope.launch {
                    delay(1500)
                    handleBluetoothAutoStart()
                }
            }
        }

    private fun handleBluetoothAutoStart() {
        if (isTogetherGuestSession()) return

        if (player.currentMediaItem != null &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED
        ) {
            if (!player.playWhenReady) {
                player.play()
            }
            return
        }

        if (player.mediaItemCount > 0) {
            player.prepare()
            player.play()
        }
    }

    @Suppress("DEPRECATION")
    private fun registerBluetoothReceiver() {
        if (bluetoothReceiverRegistered) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val filter = IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED)
        ContextCompat.registerReceiver(
            this,
            bluetoothReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        bluetoothReceiverRegistered = true
    }

    private fun unregisterBluetoothReceiver() {
        if (!bluetoothReceiverRegistered) return
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (_: Exception) {
        }
        bluetoothReceiverRegistered = false
    }

    private fun unregisterWazeCommandReceiver() {
        if (!wazeReceiverRegistered) return
        try {
            wazeCommandReceiver.detachService()
            unregisterReceiver(wazeCommandReceiver)
        } catch (_: Exception) {
        }
        wazeReceiverRegistered = false
    }

    private fun waitOnNetworkError() {
        waitingForNetworkConnection.value = true
    }

    private fun skipOnError() {
        /**
         * Auto skip to the next media item on error.
         *
         * To prevent a "runaway diesel engine" scenario, force the user to take action after
         * too many errors come up too quickly. Pause to show player "stopped" state
         */
        consecutivePlaybackErr += 1
        val nextWindowIndex = player.nextMediaItemIndex

        Timber.tag(TAG).w(
            "skipOnError: consecutivePlaybackErr=%d (max=%d), nextIndex=%d",
            consecutivePlaybackErr,
            MAX_CONSECUTIVE_ERR,
            nextWindowIndex,
        )

        if (consecutivePlaybackErr <= MAX_CONSECUTIVE_ERR && nextWindowIndex != C.INDEX_UNSET) {
            player.seekTo(nextWindowIndex, C.TIME_UNSET)
            player.prepare()
            player.play()
            return
        }

        Timber.tag(TAG).w("skipOnError: pausing after %d consecutive errors", consecutivePlaybackErr)
        player.pause()
        consecutivePlaybackErr = 0
    }

    private fun stopOnError() {
        player.pause()
    }

    /**
     * The queue position parked because its sources were awaiting a grant.
     *
     * Set when the player gives up on a track that only failed for lack of a
     * verification, and cleared as soon as one lands - which is what lets the same
     * position be re-resolved instead of the queue moving past it.
     */
    @Volatile private var awaitingSourceVerification: String? = null
    @Volatile private var awaitingSourceVerificationIndex: Int = C.INDEX_UNSET

    /** Pending deferred re-resolve of a parked track (see [scheduleHeldTrackRecovery]). */
    private var heldTrackRecoveryJob: Job? = null

    /**
     * Re-resolves a parked track once the verification burst has settled.
     *
     * Verifying four gateway sources takes several challenge rounds, and each one
     * reports success - so recovering on every single success re-ran the whole
     * provider sweep three or four times while the Cloudflare challenges were still
     * running. Those attempts competed with the invisible challenge window for the
     * gateway and the network, and the sweep - which is bounded at 45s - timed out
     * even though a usable source had just come up, so the parked track failed after
     * all that waiting. Waiting for the queue to drain means one clean resolve runs
     * with every newly usable source in the candidate list and nothing else using the
     * runtime.
     */
    private fun scheduleHeldTrackRecovery() {
        val verifier = app.hush.music.spotiflac.SpotiFLAutoVerifier
        heldTrackRecoveryJob?.cancel()
        heldTrackRecoveryJob =
            scope.launch(Dispatchers.Main) {
                // Bounded so a stalled challenge can never leave the parked track
                // waiting on a callback that never comes.
                val settled = withTimeoutOrNull(HELD_RECOVERY_SETTLE_TIMEOUT_MS) {
                    while (verifier.isRunning || verifier.queued().isNotEmpty()) {
                        delay(HELD_RECOVERY_POLL_MS)
                    }
                    true
                }
                if (settled == null) {
                    app.hush.music.spotiflac.SpotiFLACDiag.log(
                        "held recovery settle timed out; resolving with whatever is usable",
                    )
                }
                retryTrackAfterSourceVerification()
            }
    }

    /**
     * Replays what the gateway block was holding, now that it is serving this client again.
     *
     * A position is parked, not skipped, when the only thing that failed was the connection - so
     * something has to bring it back, and until now the only candidates were a verification landing or
     * the gateway's own countdown running out. Neither notices the route changing, which is exactly
     * when the block stops applying: a user who switches a VPN on while looking at a song that will not
     * play expects it to play, not to wait out twenty hours that no longer mean anything.
     *
     * Verification is asked for first when any source still needs it, because a route that has just
     * been unblocked is also a route whose sessions may never have been minted - and
     * [scheduleHeldTrackRecovery] waits for exactly that burst to settle before resolving, so the
     * replay runs once with every newly usable source in its candidate list.
     */
    private suspend fun replayHeldTrackAfterGatewayReturns() {
        val parked = awaitingSourceVerification
        if (parked == null) {
            app.hush.music.spotiflac.SpotiFLACDiag.log(
                "gateway reachable again: nothing was parked for it",
            )
            return
        }
        val needing = runCatching {
            withContext(Dispatchers.IO) { spotiflacNativeRuntime.sourcesNeedingUserVerification() }
        }.getOrDefault(emptyList())
        if (needing.isNotEmpty()) {
            app.hush.music.spotiflac.SpotiFLAutoVerifier.enqueue(
                needing,
                "gateway-reachable",
                force = true,
            )
        }
        app.hush.music.spotiflac.SpotiFLACDiag.log(
            "gateway reachable again: replaying parked mediaId=$parked " +
                "(sources needing verification: ${needing.joinToString(", ").ifEmpty { "none" }})",
        )
        scheduleHeldTrackRecovery()
    }

    /**
     * The route this session is playing on is now known to be one the gateway refuses.
     *
     * Nothing is interrupted: a track that is playing from a file goes on playing, and a sweep in
     * flight is left to finish (its refusals are what the block is made of). The one thing worth doing
     * at once is the track that is *parked*, because the reason it is parked has just been named: no
     * source can answer from this route, so retrying it can only land on the other engine. Without this
     * the parked position waited for a verification that cannot succeed here, or for the gateway's own
     * countdown, while the user watched a song that would play fine over YouTube sit at 0:00.
     */
    private suspend fun onRouteBlockedAtGateway(remainingMs: Long) {
        val parked = awaitingSourceVerification
        app.hush.music.spotiflac.SpotiFLACDiag.log(
            "route is refused at the gateway " +
                "(${app.hush.music.spotiflac.SpotiFLACSessionRenewer.formatBlockRemaining(remainingMs)} left) - " +
                "SpotiFLAC is passed over until the route moves" +
                (parked?.let { "; retrying parked mediaId=$it" } ?: "; nothing parked"),
        )
        if (parked == null) return
        scheduleHeldTrackRecovery()
    }

    /** Forgets a parked position once the track is no longer waiting on a grant. */
    private fun clearSourceVerificationHold() {
        awaitingSourceVerification = null
        awaitingSourceVerificationIndex = C.INDEX_UNSET
    }

    /** Parks the failing position instead of skipping it. */
    private fun holdForSourceVerification(mediaId: String?) {
        val index = player.currentMediaItemIndex
        awaitingSourceVerification = mediaId?.takeIf { it.isNotBlank() }
        awaitingSourceVerificationIndex = index
        app.hush.music.spotiflac.SpotiFLACDiag.log(
            "held mediaId=${mediaId ?: "?"} index=$index awaiting source verification",
        )
    }

    /**
     * Re-resolves the parked position the moment a source becomes usable.
     *
     * The miss memo is dropped first: the sweep that failed was remembered as
     * "unavailable", and replaying that verdict would stop the retry before it ever
     * reached the runtime that can now serve the track. The held index is used when
     * the player is no longer on it, so a position the queue moved past still comes
     * back - but only while playback is stopped, never over something that is
     * actually playing.
     */
    private fun retryTrackAfterSourceVerification() {
        val mediaId = awaitingSourceVerification
        val heldIndex = awaitingSourceVerificationIndex
        awaitingSourceVerification = null
        awaitingSourceVerificationIndex = C.INDEX_UNSET
        if (mediaId == null) return

        spotiFLACMissMemo.remove(mediaId)
        val currentId = player.currentMediaItem?.mediaId
        val onHeldTrack = currentId == mediaId
        if (!onHeldTrack) {
            val movable =
                !player.isPlaying &&
                    heldIndex != C.INDEX_UNSET &&
                    heldIndex in 0 until player.mediaItemCount
            if (!movable) {
                app.hush.music.spotiflac.SpotiFLACDiag.log(
                    "recover skipped mediaId=$mediaId (moved on, current=$currentId)",
                )
                return
            }
        }
        val index = if (onHeldTrack) player.currentMediaItemIndex else heldIndex
        if (index == C.INDEX_UNSET) return
        Timber.tag(TAG).i("Source verified — recovering %s at index %d", mediaId, index)
        app.hush.music.spotiflac.SpotiFLACDiag.log(
            "recovering held mediaId=$mediaId index=$index onHeldTrack=$onHeldTrack",
        )
        consecutivePlaybackErr = 0
        player.seekTo(index, C.TIME_UNSET)
        player.prepare()
        player.play()
    }

    private fun findRetryableStreamFailure(
        error: PlaybackException,
    ): androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException? {
        var throwable: Throwable? = error.cause
        while (throwable != null) {
            if (throwable is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException &&
                throwable.responseCode in RETRYABLE_STREAM_RESPONSE_CODES
            ) {
                return throwable
            }
            throwable = throwable.cause
        }
        return null
    }

    /** Check if the error is a retryable HTTP error by inspecting cause messages. */
    private fun isRetryableHttpError(error: PlaybackException): Boolean {
        var throwable: Throwable? = error.cause
        while (throwable != null) {
            val msg = throwable.message ?: ""
            if (msg.startsWith("Response code:", ignoreCase = true)) {
                val code = msg.substringAfter("Response code:").trim().substringBefore(" ").toIntOrNull()
                if (code != null && code in RETRYABLE_STREAM_RESPONSE_CODES) return true
            }
            throwable = throwable.cause
        }
        return false
    }

    private fun isRetryableRemoteParserFailure(error: PlaybackException): Boolean {
        if (
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
        ) {
            return true
        }

        var throwable: Throwable? = error.cause
        while (throwable != null) {
            if (throwable.message?.contains("Skipping atom with length", ignoreCase = true) == true) {
                return true
            }
            throwable = throwable.cause
        }
        return false
    }

    private fun isCacheCorruptionError(
        error: PlaybackException,
        isContentCached: Boolean,
    ): Boolean {
        val isIoError =
            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
        val isContainerParseError =
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED

        if (!isIoError && !isContainerParseError) {
            return false
        }

        var throwable: Throwable? = error.cause
        while (throwable != null) {
            when {
                throwable is EOFException -> {
                    return true
                }

                throwable is IOException &&
                    throwable.message?.contains("unexpected end of stream", ignoreCase = true) == true -> {
                    return true
                }

                throwable is IllegalStateException || throwable is IllegalArgumentException -> {
                    if (throwable.stackTrace.any { it.className.startsWith("androidx.media3.extractor") }) {
                        return true
                    }
                }

                isContainerParseError && isContentCached && throwable is ParserException -> {
                    return true
                }

                isContainerParseError && isContentCached &&
                    throwable.message?.let {
                        it.contains("Invalid integer size", ignoreCase = true) ||
                            it.contains("Skipping atom with length", ignoreCase = true) ||
                            it.contains("contentIsMalformed=true", ignoreCase = true)
                    } == true -> {
                    return true
                }
            }
            throwable = throwable.cause
        }
        return false
    }

    private fun retryPlaybackAfterStreamFailure(
        mediaId: String,
        isFullyCachedMedia: Boolean,
        responseException: androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException,
    ): Boolean {
        if (isFullyCachedMedia) return false

        val failedUrl = responseException.dataSpec.uri.toString()
        val requestProfile = StreamClientUtils.resolveRequestProfile(failedUrl)
        val authFingerprint = YouTube.currentPlaybackAuthState().fingerprint
        val extractorAuthFingerprint = ArchiveTuneExtractorCacheFingerprintPrefix + authFingerprint
        val cachedFailedUrl = playbackUrlCache[mediaId]?.takeIf { it.url == failedUrl }
        val cachedExtractorFailedUrl = extractorPlaybackUrlCache[mediaId]?.takeIf { it.url == failedUrl }
        val failedExpiredUrl =
            YTPlayerUtils.isExpiredOrNearExpiredStreamUrl(failedUrl) ||
                (
                    cachedFailedUrl?.let {
                        !it.isValidFor(
                            authFingerprint = authFingerprint,
                            minimumRemainingMs = YTPlayerUtils.STREAM_URL_EXPIRY_SAFETY_MS,
                        )
                    } == true
                ) ||
                (
                    cachedExtractorFailedUrl?.let {
                        !it.isValidFor(
                            authFingerprint = extractorAuthFingerprint,
                            minimumRemainingMs = 0L,
                        )
                    } == true
                )

        // Try alternate CDN host before marking the client as failed
        val retryableCdn = responseException.responseCode in 403..530
        val cdnAlternate = if (retryableCdn && app.hush.music.utils.CdnUrlRotator.isCdnUrl(failedUrl)) {
            app.hush.music.utils.CdnUrlRotator.alternateCdnUrl(failedUrl)
        } else null

        if (cdnAlternate != null) {
            Timber.tag("MusicService").w(
                "CDN edge failed for %s (HTTP %d), trying alternate CDN host",
                mediaId,
                responseException.responseCode,
            )
            playbackUrlCache[mediaId]?.let { entry ->
                playbackUrlCache[mediaId] = entry.copy(url = cdnAlternate)
            }
            extractorPlaybackUrlCache.remove(mediaId)
            YTPlayerUtils.invalidateCachedStreamUrls(mediaId)
            if (!playbackStreamRecoveryTracker.registerRetryAttempt(mediaId)) return false
            player.prepare()
            return true
        }

        playbackUrlCache.remove(mediaId)
        extractorPlaybackUrlCache.remove(mediaId)
        YTPlayerUtils.invalidateCachedStreamUrls(mediaId)
        if (!failedExpiredUrl && cachedExtractorFailedUrl == null && requestProfile.clientKey.isNotEmpty()) {
            YTPlayerUtils.markStreamClientFailed(mediaId, requestProfile.clientKey, responseException.responseCode)
        }

        // Always allow retry for expired URLs — the fix is just fetching a fresh URL.
        // Only block retries for non-expired failures (bot detection, rate-limit, etc.).
        if (!failedExpiredUrl && !playbackStreamRecoveryTracker.registerRetryAttempt(mediaId)) {
            Timber.tag(TAG).w(
                "Retry budget exhausted for %s (HTTP %d, expired=%b)",
                mediaId,
                responseException.responseCode,
                failedExpiredUrl,
            )
            return false
        }

        Timber.tag(TAG).i(
            "Retrying playback for %s after stream HTTP %d from %s (expired=%b)",
            mediaId,
            responseException.responseCode,
            requestProfile.variantLabel,
            failedExpiredUrl,
        )
        player.prepare()
        return true
    }

    private fun currentPlaybackSongLiked(): Boolean {
        // Player callbacks and notification updates run on the main thread. Never
        // use the blocking Room accessor here; it crashes the app as soon as a
        // queue item transitions on recent Android versions.
        return currentSong.value?.song?.liked == true
    }

    private fun updateNotification() {
        try {
            val liked = currentPlaybackSongLiked()
            val customLayout =
                listOf(
                    CommandButton
                        .Builder()
                        .setDisplayName(
                            getString(
                                if (liked) {
                                    R.string.action_remove_like
                                } else {
                                    R.string.action_like
                                },
                            ),
                        ).setIconResId(if (liked) R.drawable.favorite else R.drawable.favorite_border)
                        .setSessionCommand(CommandToggleLike)
                        .setEnabled(currentMediaMetadata.value != null)
                        .build(),
                    CommandButton
                        .Builder()
                        .setDisplayName(
                            getString(
                                when (player.repeatMode) {
                                    REPEAT_MODE_OFF -> R.string.repeat_mode_off
                                    REPEAT_MODE_ONE -> R.string.repeat_mode_one
                                    REPEAT_MODE_ALL -> R.string.repeat_mode_all
                                    else -> R.string.repeat_mode_off
                                },
                            ),
                        ).setIconResId(
                            when (player.repeatMode) {
                                REPEAT_MODE_OFF -> R.drawable.repeat
                                REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                                REPEAT_MODE_ALL -> R.drawable.repeat_on
                                else -> R.drawable.repeat
                            },
                        ).setSessionCommand(CommandToggleRepeatMode)
                        .build(),
                    CommandButton
                        .Builder()
                        .setDisplayName(
                            getString(if (player.shuffleModeEnabled) R.string.action_shuffle_off else R.string.action_shuffle_on),
                        ).setIconResId(if (player.shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle                        ).setSessionCommand(CommandToggleShuffle)
                        .build(),
                    // The save button the "Quick-add destination" setting describes. It is offered
                    // to the car the same way the other three are, and it is the only way that
                    // setting can ever be reached.
                    CommandButton
                        .Builder()
                        .setDisplayName(getString(R.string.add_to_playlist))
                        .setIconResId(R.drawable.playlist_add)
                        .setSessionCommand(CommandAddToTargetPlaylist)
                        .setEnabled(currentMediaMetadata.value != null)
                        .build(),
                )
            mediaSession.setCustomLayout(customLayout)
        } catch (e: Exception) {
            reportException(e)
        }
    }

    fun refreshPlaybackNotification() {
        updateNotification()
        onUpdateNotification(mediaSession, hasResumablePlaybackNotification())
    }

    private suspend fun recoverSong(
        mediaId: String,
        playbackData: YTPlayerUtils.PlaybackData? = null,
        isOfflinePlayback: Boolean = false,
    ) {
        val song = database.song(mediaId).first()
        val mediaMetadata =
            withContext(Dispatchers.Main) {
                player.findNextMediaItemById(mediaId)?.metadata
                    ?: player.currentMediaItem?.takeIf { it.mediaId == mediaId }?.metadata
                    ?: currentMediaMetadata.value?.takeIf { it.id == mediaId }
            } ?: return
        val duration =
            song?.song?.duration?.takeIf { it != -1 }
                ?: mediaMetadata.duration.takeIf { it != -1 }
                ?: if (isOfflinePlayback) -1 else (
                    playbackData?.videoDetails ?: YTPlayerUtils
                        .playerResponseForMetadata(mediaId)
                        .getOrNull()
                        ?.videoDetails
                )?.lengthSeconds?.toInt()
                ?: -1
        database.query {
            if (song == null) {
                insert(mediaMetadata.copy(duration = duration))
            } else if (song.song.duration == -1) {
                update(song.song.copy(duration = duration))
            }
        }
        if (!isOfflinePlayback && !database.hasRelatedSongs(mediaId)) {
            val relatedEndpoint =
                YouTube.next(WatchEndpoint(videoId = mediaId)).getOrNull()?.relatedEndpoint
                    ?: return
            val relatedPage = YouTube.related(relatedEndpoint).getOrNull() ?: return
            database.query {
                relatedPage.songs
                    .map(SongItem::toMediaMetadata)
                    .onEach(::insert)
                    .map {
                        RelatedSongMap(
                            songId = mediaId,
                            relatedSongId = it.id,
                        )
                    }.forEach(::insert)
            }
        }
    }

    fun playQueue(
        queue: Queue,
        playWhenReady: Boolean = true,
    ) {
        Timber.tag(TAG).d(
            "playQueue: queue=${queue.javaClass.simpleName} preloadId=${queue.preloadItem?.id?.take(32)} playWhenReady=$playWhenReady",
        )
        val joined = togetherSessionState.value as? app.hush.music.together.TogetherSessionState.Joined
        if (!isTogetherApplyingRemote() && joined?.role is app.hush.music.together.TogetherRole.Guest) {
            if (!joined.roomState.settings.allowGuestsToControlPlayback) {
                showTogetherNotice(getString(R.string.not_allowed), key = "GUEST_PLAYQUEUE_DISABLED")
                return
            }
            ensureScopesActive()
            scope.launch(SilentHandler) {
                val initialStatus =
                    withContext(Dispatchers.IO) {
                        val blockedArtistIds =
                            try {
                                database.getBlockedArtistIds().toSet()
                            } catch (_: Exception) {
                                emptySet()
                            }
                        queue
                            .getInitialStatus()
                            .filterExplicit(dataStore.get(HideExplicitKey, false))
                            .filterBlockedArtists(blockedArtistIds)
                            .filterVideo(dataStore.get(HideVideoKey, false))
                    }

                val targetItem =
                    initialStatus.items.getOrNull(initialStatus.mediaItemIndex)
                        ?: queue.preloadItem?.toMediaItem()

                val meta = targetItem?.metadata
                val trackId =
                    meta?.id?.trim().orEmpty().ifBlank {
                        targetItem?.mediaId?.trim().orEmpty()
                    }
                if (trackId.isBlank()) {
                    showTogetherNotice(getString(R.string.not_allowed), key = "GUEST_PLAYQUEUE_NO_TRACK")
                    return@launch
                }

                val track =
                    app.hush.music.together.TogetherTrack(
                        id = trackId,
                        title = meta?.title ?: trackId,
                        artists = meta?.artists?.map { it.name }.orEmpty(),
                        durationSec = meta?.duration ?: -1,
                        thumbnailUrl = meta?.thumbnailUrl,
                    )

                val ops =
                    app.hush.music.together.TogetherGuestPlaybackPlanner.planPlayTrackNow(
                        roomState = joined.roomState,
                        track = track,
                        positionMs = initialStatus.position,
                        playWhenReady = playWhenReady,
                    )

                if (ops.isEmpty()) {
                    showTogetherNotice(getString(R.string.not_allowed), key = "GUEST_PLAYQUEUE_BLOCKED")
                    return@launch
                }

                showTogetherNotice(getString(R.string.together_requesting_song_change), key = "GUEST_PLAYQUEUE_REQUEST")
                ops.forEach { op ->
                    when (op) {
                        is app.hush.music.together.TogetherGuestOp.Control -> requestTogetherControl(op.action)
                        is app.hush.music.together.TogetherGuestOp.AddTrack -> requestTogetherAddTrack(op.track, op.mode)
                    }
                }
            }
            return
        }
        if (playWhenReady) {
            cancelIdleStop()
            promoteToStartedService()
            ensureStartedAsForeground()
        }
        cancelRestoredQueueHydration()
        ensureScopesActive()
        cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
        suppressAutoPlayback = false
        currentQueue = queue
        queueTitle = null
        val queueLoadGeneration = playQueueGeneration.incrementAndGet()

        queue.preloadItem?.id?.takeIf { it.isNotBlank() }?.let(::startPlaybackUrlPrefetch)

        val permanentShuffle = dataStore.get(PermanentShuffleKey, false)
        if (!permanentShuffle) {
            player.shuffleModeEnabled = false
        }

        clearAutomix()
        autoAddedMediaIds.clear()
        val mediaItemForUi = queue.preloadItem
        if (mediaItemForUi != null) {
            player.setMediaItem(mediaItemForUi.toMediaItem())
        }
        val preloadMediaId = mediaItemForUi?.id?.takeIf { it.isNotBlank() && !it.isLocalMediaId() }
        player.playWhenReady = false
        if (preloadMediaId == null) {
            // Local media and already-resolved items should start without waiting on IO.
            player.prepare()
            player.playWhenReady = playWhenReady
        } else {
            // The prefetch is already in flight. Start ExoPlayer immediately; its
            // resolver coalesces with that same request if the URL is not ready yet.
            // Waiting for the prefetch coroutine here made the first track depend on
            // queue construction and made cold-start playback feel unnecessarily slow.
            player.prepare()
            player.playWhenReady = playWhenReady
            scope.launch(SilentHandler) {
                if (spotiflacEnabled && !isYouTubeStreamingEnabled()) {
                    runCatching { warmPlaybackUrl(preloadMediaId, maxWaitMs = 0L) }
                        .onFailure { Timber.tag(TAG).w(it, "SpotiFLAC-only preload failed") }
                } else {
                    warmPlaybackUrl(preloadMediaId, maxWaitMs = 0L)
                }
                withContext(Dispatchers.Main.immediate) {
                    if (
                        queueLoadGeneration == playQueueGeneration.get() &&
                        player.currentMediaItem?.mediaId == preloadMediaId &&
                        player.playbackState == Player.STATE_IDLE
                    ) {
                        // A failed/cancelled prefetch can leave the player idle;
                        // retry prepare without disturbing a track already playing.
                        player.prepare()
                        player.playWhenReady = playWhenReady
                    }
                }
            }
        }
        scope.launch(SilentHandler) {
            val hideExplicit = dataStore.get(HideExplicitKey, false)
            val hideVideo = dataStore.get(HideVideoKey, false)
            val blockedArtistIds = try {
                withContext(Dispatchers.IO) { database.getBlockedArtistIds().toSet() }
            } catch (_: Exception) {
                emptySet()
            }
            val autoLoadMoreEnabled = dataStore.get(AutoLoadMoreKey, true)
            var initialStatus =
                withContext(Dispatchers.IO) {
                    queue
                        .getInitialStatus()
                        .filterExplicit(hideExplicit)
                        .filterBlockedArtists(blockedArtistIds)
                        .filterVideo(hideVideo)
                }
            if (!autoLoadMoreEnabled && queue.shouldExpandToFullQueueWhenAutoLoadMoreDisabled() && queue.hasNextPage()) {
                val expandedItems = initialStatus.items.toMutableList()
                var pagesLoaded = 0
                while (queue.hasNextPage() && pagesLoaded < 200) {
                    pagesLoaded++
                    val nextItems =
                        withContext(Dispatchers.IO) {
                            loadNextQueuePageWithRetry(queue)
                                .filterExplicit(hideExplicit)
                                .filterVideo(hideVideo)
                                .filterBlockedArtists(blockedArtistIds)
                        }
                    if (nextItems.isNotEmpty()) {
                        expandedItems += nextItems
                    }
                }
                initialStatus = initialStatus.copy(items = expandedItems)
            }
            if (queueLoadGeneration != playQueueGeneration.get() || currentQueue !== queue) {
                return@launch
            }
            if (initialStatus.title != null) {
                queueTitle = initialStatus.title
            }
            if (initialStatus.items.isEmpty()) return@launch
            val preloadId = queue.preloadItem?.id
            preloadId?.takeIf { it.isNotBlank() }?.let(::startPlaybackUrlPrefetch)
            if (preloadId != null) {
                val selectedIndex = initialStatus.mediaItemIndex.coerceIn(0, initialStatus.items.lastIndex)
                val duplicateIndex =
                    initialStatus.items.indices
                        .filter { index ->
                            val itemId =
                                initialStatus.items[index].metadata?.id?.takeIf { it.isNotBlank() }
                                    ?: initialStatus.items[index].mediaId
                            itemId == preloadId
                        }.minByOrNull { index -> kotlin.math.abs(index - selectedIndex) }
                if (duplicateIndex != null) {
                    val remainingItems = initialStatus.items.toMutableList().apply { removeAt(duplicateIndex) }
                    val preloadPosition =
                        (selectedIndex - if (duplicateIndex < selectedIndex) 1 else 0)
                            .coerceIn(0, remainingItems.size)
                    player.addMediaItems(0, remainingItems.subList(0, preloadPosition))
                    player.addMediaItems(remainingItems.subList(preloadPosition, remainingItems.size))
                } else {
                    player.addMediaItems(0, initialStatus.items.subList(0, selectedIndex))
                    player.addMediaItems(initialStatus.items.subList(selectedIndex + 1, initialStatus.items.size))
                }
                if (player.shuffleModeEnabled) {
                    applyCurrentFirstShuffleOrder()
                }
            } else {
                val items = initialStatus.items
                val index = initialStatus.mediaItemIndex
                val fallbackMediaId = items.getOrNull(index)?.mediaId?.takeIf { it.isNotBlank() && !it.isLocalMediaId() }
                if (fallbackMediaId != null) {
                    startPlaybackUrlPrefetch(fallbackMediaId)
                }
                player.setMediaItems(items, index, initialStatus.position)
                player.prepare()
                player.playWhenReady = playWhenReady
                if (fallbackMediaId != null) {
                    scope.launch(SilentHandler) {
                        warmPlaybackUrl(fallbackMediaId, maxWaitMs = 0L)
                    }
                }
                if (player.shuffleModeEnabled) {
                    applyCurrentFirstShuffleOrder()
                }
            }
            // The queue is now on the player, so persist it without waiting for the
            // periodic save (10-30 s) or a track transition: choosing a fresh queue
            // and restarting the app used to bring back the previous one.
            scheduleQueuePersist(delayMs = 0L)
        }
    }

    /**
     * Debounced queue persist for queue *changes*.
     *
     * The periodic save runs every 10-30 s and the transition handlers persist on
     * track changes, so a queue chosen moments before the app is killed could be
     * lost entirely. Cancelling and rescheduling keeps this to one write per burst.
     */
    private fun scheduleQueuePersist(delayMs: Long = QUEUE_PERSIST_DEBOUNCE_MS) {
        queuePersistJob?.cancel()
        queuePersistJob =
            scope.launch(SilentHandler) {
                if (delayMs > 0) delay(delayMs)
                if (!withContext(Dispatchers.IO) { dataStore.get(PersistentQueueKey, true) }) return@launch
                if (player.mediaItemCount == 0) return@launch
                saveQueueToDisk()
            }
    }

    private fun playerQueueMetadataWindow(lookahead: Int = 4): List<MediaMetadata> {
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex == C.INDEX_UNSET || player.mediaItemCount == 0) {
            return emptyList()
        }
        val endIndex = minOf(player.mediaItemCount, currentIndex + lookahead + 1)
        return buildList(endIndex - currentIndex) {
            for (i in currentIndex until endIndex) {
                player.getMediaItemAt(i).metadata?.let(::add)
            }
        }
    }

    private fun applyCurrentFirstShuffleOrder() {
        val count = player.mediaItemCount
        if (count <= 1) return
        val currentIndex = player.currentMediaItemIndex.coerceIn(0, count - 1)
        val shuffledIndices = IntArray(count) { it }
        shuffledIndices.shuffle()
        val currentPos = shuffledIndices.indexOf(currentIndex)
        if (currentPos >= 0) {
            shuffledIndices[currentPos] = shuffledIndices[0]
        }
        shuffledIndices[0] = currentIndex
        localPlayer.setShuffleOrder(DefaultShuffleOrder(shuffledIndices, System.currentTimeMillis()))
    }

    private fun buildPlayNextShuffleOrder(
        currentIndex: Int,
        insertionIndex: Int,
        insertionCount: Int,
    ): DefaultShuffleOrder? {
        if (insertionCount <= 0 || player.currentTimeline.isEmpty) return null

        fun adjustedIndex(index: Int): Int =
            if (index >= insertionIndex) {
                index + insertionCount
            } else {
                index
            }

        val timeline = player.currentTimeline
        val previousIndices = ArrayDeque<Int>()
        var traversalIndex = currentIndex
        while (true) {
            traversalIndex = timeline.getPreviousWindowIndex(traversalIndex, REPEAT_MODE_OFF, true)
            if (traversalIndex == C.INDEX_UNSET) {
                break
            }
            previousIndices.addFirst(adjustedIndex(traversalIndex))
        }

        val nextIndices = mutableListOf<Int>()
        traversalIndex = currentIndex
        while (true) {
            traversalIndex = timeline.getNextWindowIndex(traversalIndex, REPEAT_MODE_OFF, true)
            if (traversalIndex == C.INDEX_UNSET) {
                break
            }
            nextIndices += adjustedIndex(traversalIndex)
        }

        val shuffledIndices =
            buildList(player.mediaItemCount + insertionCount) {
                addAll(previousIndices)
                add(currentIndex)
                repeat(insertionCount) { offset ->
                    add(insertionIndex + offset)
                }
                addAll(nextIndices)
            }.toIntArray()

        return DefaultShuffleOrder(shuffledIndices, System.currentTimeMillis())
    }

    fun startRadioSeamlessly() {
        val joined = togetherSessionState.value as? app.hush.music.together.TogetherSessionState.Joined
        if (!isTogetherApplyingRemote() && joined?.role is app.hush.music.together.TogetherRole.Guest) {
            if (!joined.roomState.settings.allowGuestsToControlPlayback) {
                showTogetherNotice(getString(R.string.not_allowed), key = "GUEST_RADIO_DISABLED")
                return
            }
            showTogetherNotice(getString(R.string.not_allowed), key = "GUEST_RADIO_UNSUPPORTED")
            return
        }
        suppressAutoPlayback = false
        val currentMediaMetadata = player.currentMetadata ?: return

        val currentIndex = player.currentMediaItemIndex
        val currentMediaId = currentMediaMetadata.id
        if (currentSong.value?.song?.isLocal == true || currentMediaId.isLocalMediaId()) {
            return
        }
        val queue = currentQueue
        val queueGeneration = playQueueGeneration.get()

        scope.launch(SilentHandler) {
            val radioQueue =
                YouTubeQueue(
                    endpoint = WatchEndpoint(videoId = currentMediaId),
                    followAutomixPreview = true,
                )
            val blockedArtistIds =
                try {
                    withContext(Dispatchers.IO) { database.getBlockedArtistIds().toSet() }
                } catch (_: Exception) {
                    emptySet()
                }
            val initialStatus =
                withContext(Dispatchers.IO) {
                    radioQueue
                        .getInitialStatus()
                        .filterExplicit(
                            dataStore.get(HideExplicitKey, false),
                        ).filterVideo(dataStore.get(HideVideoKey, false))
                        .filterBlockedArtists(blockedArtistIds)
                }

            if (
                queueGeneration != playQueueGeneration.get() ||
                currentQueue !== queue ||
                player.currentMediaItemIndex != currentIndex ||
                player.currentMetadata?.id != currentMediaId
            ) {
                return@launch
            }

            if (initialStatus.title != null) {
                queueTitle = initialStatus.title
            }

            val radioItems =
                initialStatus.items.filter { item ->
                    item.mediaId != currentMediaId
                }

            if (radioItems.isNotEmpty()) {
                val itemCount = player.mediaItemCount

                if (itemCount > currentIndex + 1) {
                    player.removeMediaItems(currentIndex + 1, itemCount)
                }

                player.addMediaItems(currentIndex + 1, radioItems)
            }

            currentQueue = radioQueue
        }
    }

    fun adoptQueue(queue: Queue, title: String? = null, initialQueueSize: Int = 0) {
        currentQueue = queue
        queueTitle = title
        originalQueueSize = initialQueueSize
    }

    fun clearAutomix() {
        autoAddedMediaIds.clear()
    }

    private fun removeMusicVideoItems() {
        if (player.mediaItemCount == 0) return

        var blockedRangeEnd = C.INDEX_UNSET
        for (index in player.mediaItemCount - 1 downTo 0) {
            val item = player.getMediaItemAt(index)
            val isMusicVideo = item.mediaMetadata.extras?.getBoolean(ExtraIsMusicVideo, false) == true
            if (isMusicVideo) {
                autoAddedMediaIds.remove(item.mediaId)
                if (blockedRangeEnd == C.INDEX_UNSET) {
                    blockedRangeEnd = index + 1
                }
            } else if (blockedRangeEnd != C.INDEX_UNSET) {
                player.removeMediaItems(index + 1, blockedRangeEnd)
                blockedRangeEnd = C.INDEX_UNSET
            }
        }
        if (blockedRangeEnd != C.INDEX_UNSET) {
            player.removeMediaItems(0, blockedRangeEnd)
        }
        if (player.mediaItemCount == 0) {
            infiniteQueueLoading.value = false
            infiniteQueueGeneration.incrementAndGet()
            currentQueue = EmptyQueue
        }
    }

    fun onInfiniteQueueDisabled() {
        infiniteQueueLoading.value = false
        infiniteQueueGeneration.incrementAndGet()
        val currentIndex = player.currentMediaItemIndex
        val idsToRemove = synchronized(autoAddedMediaIds) { autoAddedMediaIds.toSet() }
        if (idsToRemove.isEmpty()) {
            return
        }
        for (i in player.mediaItemCount - 1 downTo 0) {
            if (i == currentIndex) continue
            val item = player.getMediaItemAt(i)
            if (item.mediaId in idsToRemove) {
                player.removeMediaItem(i)
            }
        }
        autoAddedMediaIds.clear()
        currentQueue = EmptyQueue
    }

    fun onInfiniteQueueEnabled() {
        val currentMeta = player.currentMetadata ?: return
        if (isCurrentPlaybackItemLocal(currentMeta)) return
        if (infiniteQueueLoading.value) return
        if (isCrossfading || crossfadeHandoffInProgress) return
        infiniteQueueLoading.value = true
        val queue = currentQueue
        val queueGeneration = playQueueGeneration.get()
        val infiniteQueueLoadGeneration = infiniteQueueGeneration.incrementAndGet()
        val currentIndex = player.currentMediaItemIndex
        val currentMediaId = currentMeta.id

        scope.launch(SilentHandler) {
            val blockedArtistIds =
                try {
                    withContext(Dispatchers.IO) { database.getBlockedArtistIds().toSet() }
                } catch (_: Exception) {
                    emptySet()
                }
            try {
                val radioQueue = YouTubeQueue(WatchEndpoint(videoId = currentMediaId), followAutomixPreview = true)
                val status = withContext(Dispatchers.IO) {
                    radioQueue.getInitialStatus().filterBlockedArtists(blockedArtistIds)
                }

                if (
                    infiniteQueueLoadGeneration != infiniteQueueGeneration.get() ||
                    !infiniteQueueLoading.value ||
                    queueGeneration != playQueueGeneration.get() ||
                    currentQueue !== queue ||
                    player.currentMediaItemIndex != currentIndex ||
                    player.currentMetadata?.id != currentMediaId
                ) {
                    return@launch
                }

                val existingIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
                val newItems = status.items.filter { it.mediaId !in existingIds }

                if (newItems.isNotEmpty()) {
                    player.addMediaItems(newItems)
                    newItems.forEach { autoAddedMediaIds.add(it.mediaId) }

                    currentQueue = radioQueue

                    if (player.playbackState == Player.STATE_ENDED || player.mediaItemCount == player.currentMediaItemIndex + 1) {
                        player.seekToNext()
                        player.play()
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to bootstrap auto-queue")
            } finally {
                if (infiniteQueueLoadGeneration == infiniteQueueGeneration.get()) {
                    infiniteQueueLoading.value = false
                }
            }
        }
    }

    fun stopAndClearPlayback(clearPersistentState: Boolean = false) {
        cancelRestoredQueueHydration()
        suppressAutoPlayback = true
        cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
        clearAutomix()
        currentQueue = EmptyQueue
        queueTitle = null
        waitingForNetworkConnection.value = false
        currentMediaMetadata.value = null
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
        abandonAudioFocus()
        closeAudioEffectSession()
        cancelUrlRefresh()
        consecutivePlaybackErr = 0
        if (clearPersistentState) {
            clearPersistedQueueFiles()
        }
    }

    fun playNext(items: List<MediaItem>) {
        val joined = togetherSessionState.value as? app.hush.music.together.TogetherSessionState.Joined
        if (joined?.role is app.hush.music.together.TogetherRole.Guest) {
            if (!joined.roomState.settings.allowGuestsToAddTracks) {
                return
            }
            val tracks =
                items.mapNotNull { it.metadata }.map { meta ->
                    app.hush.music.together.TogetherTrack(
                        id = meta.id,
                        title = meta.title,
                        artists = meta.artists.map { it.name },
                        durationSec = meta.duration,
                        thumbnailUrl = meta.thumbnailUrl,
                    )
                }
            tracks.asReversed().forEach { track ->
                requestTogetherAddTrack(track, app.hush.music.together.AddTrackMode.PLAY_NEXT)
            }
            return
        }
        suppressAutoPlayback = false
        val insertionIndex = if (player.mediaItemCount == 0) 0 else player.currentMediaItemIndex + 1
        val playNextShuffleOrder =
            if (player.shuffleModeEnabled && player.mediaItemCount > 0) {
                buildPlayNextShuffleOrder(
                    currentIndex = player.currentMediaItemIndex,
                    insertionIndex = insertionIndex,
                    insertionCount = items.size,
                )
            } else {
                null
            }

        player.addMediaItems(insertionIndex, items)
        playNextShuffleOrder?.let(localPlayer::setShuffleOrder)
        player.prepare()
    }

    fun addToQueue(items: List<MediaItem>) {
        val joined = togetherSessionState.value as? app.hush.music.together.TogetherSessionState.Joined
        if (joined?.role is app.hush.music.together.TogetherRole.Guest) {
            if (!joined.roomState.settings.allowGuestsToAddTracks) {
                return
            }
            val tracks =
                items.mapNotNull { it.metadata }.map { meta ->
                    app.hush.music.together.TogetherTrack(
                        id = meta.id,
                        title = meta.title,
                        artists = meta.artists.map { it.name },
                        durationSec = meta.duration,
                        thumbnailUrl = meta.thumbnailUrl,
                    )
                }
            tracks.forEach { track ->
                requestTogetherAddTrack(track, app.hush.music.together.AddTrackMode.ADD_TO_QUEUE)
            }
            return
        }
        suppressAutoPlayback = false
        player.addMediaItems(items)
        player.prepare()
    }

    fun playFromVoiceSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        ensureScopesActive()
        scope.launch(SilentHandler) {
            val mediaItems =
                withContext(Dispatchers.IO) {
                    mediaLibrarySessionCallback.resolveVoiceMediaItems(trimmed)
                }
            if (mediaItems.isEmpty()) return@launch
            playQueue(ListQueue(items = mediaItems))
        }
    }

    fun startTogetherHost(
        port: Int,
        displayName: String,
        settings: app.hush.music.together.TogetherRoomSettings,
    ) {
        ensureScopesActive()
        scope.launch(SilentHandler) {
            togetherSessionState.value = app.hush.music.together.TogetherSessionState.Idle
        }

        ioScope.launch(SilentHandler) {
            stopTogetherInternal()
            togetherIsOnlineSession = false

            val localIp = getLocalIpv4Address()
            val sessionId =
                java.util.UUID
                    .randomUUID()
                    .toString()
            val sessionKey =
                java.util.UUID
                    .randomUUID()
                    .toString()
            val joinInfo =
                app.hush.music.together.TogetherJoinInfo(
                    host = localIp ?: "127.0.0.1",
                    port = port,
                    sessionId = sessionId,
                    sessionKey = sessionKey,
                )
            val joinLink =
                app.hush.music.together.TogetherLink
                    .encode(joinInfo)

            val server =
                app.hush.music.together.TogetherServer(
                    scope = ioScope,
                    sessionId = sessionId,
                    sessionKey = sessionKey,
                    hostDisplayName = displayName.trim().ifBlank { getString(R.string.app_name) },
                    initialSettings = settings,
                    hostParticipantId = togetherHostId,
                )

            server.onEvent = { event ->
                ioScope.launch(SilentHandler) {
                    handleTogetherHostEvent(event) { server.currentSettings() }
                }
            }

            server.start(port)
            togetherServer = server

            scope.launch(SilentHandler) {
                togetherSessionState.value =
                    app.hush.music.together.TogetherSessionState.Hosting(
                        sessionId = sessionId,
                        joinLink = joinLink,
                        localAddressHint = localIp,
                        port = port,
                        settings = settings,
                        roomState = null,
                    )
            }

            togetherBroadcastJob =
                ioScope.launch(SilentHandler) {
                    while (togetherServer === server) {
                        if (togetherAuthorityParticipantId == null || togetherAuthorityParticipantId == togetherHostId) {
                            val state = buildTogetherRoomState(sessionId = sessionId, hostId = togetherHostId)
                            server.broadcastRoomState(state)
                            scope.launch(SilentHandler) {
                                val hosting = togetherSessionState.value as? app.hush.music.together.TogetherSessionState.Hosting
                                if (hosting?.sessionId == sessionId) {
                                    togetherSessionState.value =
                                        hosting.copy(
                                            settings = server.currentSettings(),
                                            roomState =
                                                state.copy(
                                                    participants = server.currentParticipants(),
                                                    settings = server.currentSettings(),
                                                ),
                                        )
                                }
                            }
                        }
                        kotlinx.coroutines.delay(TogetherPlaybackSync.BroadcastIntervalMs)
                    }
                }
        }
    }

    private fun togetherOnlineErrorMessage(t: Throwable): String {
        if (t is app.hush.music.together.TogetherOnlineApiException) {
            val code = t.statusCode
            return when {
                code == 404 -> getString(R.string.together_session_not_found)
                code != null && code in 500..599 -> getString(R.string.together_server_error)
                else -> t.message ?: getString(R.string.network_unavailable)
            }
        }
        val root = generateSequence(t) { it.cause }.lastOrNull() ?: t
        return when (root) {
            is UnknownHostException -> getString(R.string.together_server_unreachable)
            is ConnectException -> getString(R.string.together_server_unreachable)
            is SocketTimeoutException -> getString(R.string.together_connection_timed_out)
            is javax.net.ssl.SSLHandshakeException -> getString(R.string.together_server_unreachable)
            else -> getString(R.string.network_unavailable)
        }
    }

    fun startTogetherOnlineHost(
        displayName: String,
        settings: app.hush.music.together.TogetherRoomSettings,
    ) {
        ensureScopesActive()
        scope.launch(SilentHandler) {
            togetherSessionState.value = app.hush.music.together.TogetherSessionState.Idle
        }

        ioScope.launch(SilentHandler) {
            stopTogetherInternal()
            togetherIsOnlineSession = true

            val baseUrl =
                app.hush.music.together.TogetherOnlineEndpoint
                    .baseUrlOrNull(dataStore)
            if (baseUrl == null) {
                scope.launch(SilentHandler) {
                    togetherSessionState.value =
                        app.hush.music.together.TogetherSessionState.Error(
                            message = getString(R.string.together_online_not_configured),
                            recoverable = true,
                        )
                }
                return@launch
            }

            val togetherToken =
                app.hush.music.BuildConfig.TOGETHER_BEARER_TOKEN
                    .trim()
                    .takeIf { it.isNotBlank() }
            if (togetherToken == null) {
                scope.launch(SilentHandler) {
                    togetherSessionState.value =
                        app.hush.music.together.TogetherSessionState.Error(
                            message = getString(R.string.together_token_missing),
                            recoverable = true,
                        )
                }
                return@launch
            }

            val api =
                app.hush.music.together
                    .TogetherOnlineApi(baseUrl = baseUrl, bearerToken = togetherToken)
            val hostName = displayName.trim().ifBlank { getString(R.string.app_name) }

            val created =
                runCatching {
                    api.createSession(
                        hostDisplayName = hostName,
                        settings = settings,
                    )
                }.getOrElse { t ->
                    scope.launch(SilentHandler) {
                        togetherSessionState.value =
                            app.hush.music.together.TogetherSessionState.Error(
                                message = togetherOnlineErrorMessage(t),
                                recoverable = true,
                            )
                    }
                    reportException(t)
                    return@launch
                }

            val onlineHost =
                app.hush.music.together.TogetherOnlineHost(
                    externalScope = ioScope,
                    sessionId = created.sessionId,
                    sessionKey = created.hostKey,
                    hostId = togetherHostId,
                    hostDisplayName = hostName,
                    initialSettings = created.settings,
                    clientId = getOrCreateTogetherClientId(),
                    bearerToken = togetherToken,
                )

            onlineHost.onEvent = { event ->
                ioScope.launch(SilentHandler) {
                    handleTogetherHostEvent(event) { onlineHost.currentSettings() }
                }
            }

            togetherOnlineHost = onlineHost

            scope.launch(SilentHandler) {
                togetherSessionState.value =
                    app.hush.music.together.TogetherSessionState.HostingOnline(
                        sessionId = created.sessionId,
                        code = created.code,
                        settings = created.settings,
                        roomState = null,
                    )
            }

            val wsUrl =
                app.hush.music.together.TogetherOnlineEndpoint.onlineWebSocketUrlOrNull(
                    rawWsUrl = created.wsUrl,
                    baseUrl = baseUrl,
                )
            if (wsUrl == null) {
                scope.launch(SilentHandler) {
                    togetherSessionState.value =
                        app.hush.music.together.TogetherSessionState.Error(
                            message = "Connection failed: Invalid server websocket URL",
                            recoverable = true,
                        )
                }
                ioScope.launch(SilentHandler) { stopTogetherInternal() }
                return@launch
            }

            togetherOnlineConnectJob?.cancel()
            togetherOnlineConnectJob =
                ioScope.launch(SilentHandler) {
                    onlineHost.connect(wsUrl)
                }

            togetherBroadcastJob =
                ioScope.launch(SilentHandler) {
                    while (togetherOnlineHost === onlineHost) {
                        val state =
                            if (togetherAuthorityParticipantId == null || togetherAuthorityParticipantId == togetherHostId) {
                                buildTogetherRoomState(
                                    sessionId = created.sessionId,
                                    hostId = togetherHostId,
                                )
                            } else {
                                null
                            }
                        if (state != null) {
                            onlineHost.broadcastRoomState(state)
                            scope.launch(SilentHandler) {
                                val hosting =
                                    togetherSessionState.value as? app.hush.music.together.TogetherSessionState.HostingOnline
                                if (hosting?.sessionId == created.sessionId) {
                                    val currentSettings = onlineHost.currentSettings()
                                    togetherSessionState.value =
                                        hosting.copy(
                                            settings = currentSettings,
                                            roomState =
                                                state.copy(
                                                    participants = onlineHost.currentParticipants(),
                                                    settings = currentSettings,
                                                ),
                                        )
                                }
                            }
                        }
                        kotlinx.coroutines.delay(TogetherPlaybackSync.BroadcastIntervalMs)
                    }
                }
        }
    }

    fun joinTogether(
        rawLink: String,
        displayName: String,
    ) {
        ensureScopesActive()
        val joinInfo =
            app.hush.music.together.TogetherLink
                .decode(rawLink)
        if (joinInfo == null) {
            scope.launch(SilentHandler) {
                togetherSessionState.value =
                    app.hush.music.together.TogetherSessionState.Error(
                        message = getString(R.string.invalid_link),
                        recoverable = true,
                    )
            }
            return
        }

        scope.launch(SilentHandler) {
            togetherSessionState.value =
                app.hush.music.together.TogetherSessionState
                    .Joining(joinInfo.toDeepLink())
        }

        ioScope.launch(SilentHandler) {
            stopTogetherInternal()
            togetherIsOnlineSession = false
            val client =
                app.hush.music.together.TogetherClient(
                    ioScope,
                    clientId = getOrCreateTogetherClientId(),
                )
            togetherClient = client
            togetherClock =
                app.hush.music.together
                    .TogetherClock()
            togetherSelfParticipantId = null
            togetherLastAppliedQueueHash = null

            togetherClientEventsJob?.cancel()
            togetherClientEventsJob =
                ioScope.launch(SilentHandler) {
                    client.events.collect { event ->
                        when (event) {
                            is app.hush.music.together.TogetherClientEvent.Welcome -> {
                                togetherSelfParticipantId = event.welcome.participantId
                                scope.launch(SilentHandler) {
                                    val state = togetherSessionState.value
                                    if (state is app.hush.music.together.TogetherSessionState.Joining) {
                                        val selfName = displayName.trim().ifBlank { getString(R.string.together_role_guest) }
                                        val initial =
                                            app.hush.music.together.TogetherRoomState(
                                                sessionId = joinInfo.sessionId,
                                                hostId = togetherHostId,
                                                participants =
                                                    listOf(
                                                        app.hush.music.together.TogetherParticipant(
                                                            id = event.welcome.participantId,
                                                            name = selfName,
                                                            isHost = false,
                                                            isPending = event.welcome.isPending,
                                                            isConnected = true,
                                                        ),
                                                    ),
                                                settings = event.welcome.settings,
                                                queue = emptyList(),
                                                queueHash = "",
                                                currentIndex = 0,
                                                isPlaying = false,
                                                positionMs = 0L,
                                                repeatMode = 0,
                                                shuffleEnabled = false,
                                                sentAtElapsedRealtimeMs = android.os.SystemClock.elapsedRealtime(),
                                            )
                                        togetherSessionState.value =
                                            app.hush.music.together.TogetherSessionState.Joined(
                                                role = app.hush.music.together.TogetherRole.Guest,
                                                sessionId = joinInfo.sessionId,
                                                selfParticipantId = event.welcome.participantId,
                                                roomState = initial,
                                            )
                                    }
                                }
                                startTogetherHeartbeat(joinInfo.sessionId, client)
                            }

                            is app.hush.music.together.TogetherClientEvent.RoomState -> {
                                applyRemoteRoomState(event.state)
                            }

                            is app.hush.music.together.TogetherClientEvent.HostTransferred -> {
                                handleTogetherClientHostTransferred(event.transfer)
                            }

                            is app.hush.music.together.TogetherClientEvent.ControlRequested -> {
                                val joined =
                                    togetherSessionState.value as? app.hush.music.together.TogetherSessionState.Joined
                                if (togetherAuthorityParticipantId == togetherSelfParticipantId &&
                                    joined?.roomState?.settings?.allowGuestsToControlPlayback == true
                                ) {
                                    applyHostControl(event.request.action)
                                }
                            }

                            is app.hush.music.together.TogetherClientEvent.AddTrackRequested -> {
                                val joined =
                                    togetherSessionState.value as? app.hush.music.together.TogetherSessionState.Joined
                                if (togetherAuthorityParticipantId == togetherSelfParticipantId &&
                                    joined?.roomState?.settings?.allowGuestsToAddTracks == true
                                ) {
                                    applyHostAddTrack(event.request.track, event.request.mode)
                                }
                            }

                            is app.hush.music.together.TogetherClientEvent.JoinDecision -> {
                                if (!event.decision.approved) {
                                    scope.launch(SilentHandler) {
                                        togetherSessionState.value =
                                            app.hush.music.together.TogetherSessionState.Error(
                                                message = getString(R.string.not_allowed),
                                                recoverable = true,
                                            )
                                    }
                                    ioScope.launch(SilentHandler) { stopTogetherInternal() }
                                }
                            }

                            is app.hush.music.together.TogetherClientEvent.ServerIssue -> {
                                Timber.tag("Together").w("server issue (lan) code=${event.code.orEmpty()} message=${event.message}")
                                when (event.code) {
                                    "GUEST_CONTROL_DISABLED" -> {
                                        showTogetherNotice(event.message, key = "GUEST_CONTROL_DISABLED")
                                        val joined =
                                            togetherSessionState.value as? app.hush.music.together.TogetherSessionState.Joined
                                        if (joined?.role is app.hush.music.together.TogetherRole.Guest) {
                                            togetherPendingGuestControl = null
                                            togetherLastSentControlAction = null
                                            scope.launch(SilentHandler) { applyRemoteRoomState(joined.roomState, force = true) }
                                        }
                                    }

                                    "GUEST_ADD_DISABLED" -> {
                                        showTogetherNotice(event.message, key = "GUEST_ADD_DISABLED")
                                    }

                                    "HOST_OFFLINE" -> {
                                        showTogetherNotice(event.message, key = "HOST_OFFLINE")
                                    }

                                    else -> {
                                        scope.launch(SilentHandler) {
                                            togetherSessionState.value =
                                                app.hush.music.together.TogetherSessionState.Error(
                                                    message = event.message,
                                                    recoverable = true,
                                                )
                                        }
                                        ioScope.launch(SilentHandler) { stopTogetherInternal() }
                                    }
                                }
                            }

                            is app.hush.music.together.TogetherClientEvent.HeartbeatPong -> {
                                val clock = togetherClock ?: return@collect
                                clock.onPong(
                                    sentAtElapsedMs = event.pong.clientElapsedRealtimeMs,
                                    receivedAtElapsedMs = event.receivedAtElapsedRealtimeMs,
                                    serverElapsedMs = event.pong.serverElapsedRealtimeMs,
                                )
                            }

                            is app.hush.music.together.TogetherClientEvent.Error -> {
                                scope.launch(SilentHandler) {
                                    togetherSessionState.value =
                                        app.hush.music.together.TogetherSessionState.Error(
                                            message = event.message,
                                            recoverable = true,
                                        )
                                }
                                ioScope.launch(SilentHandler) { stopTogetherInternal() }
                            }

                            app.hush.music.together.TogetherClientEvent.Disconnected -> {
                                val current = togetherSessionState.value
                                if (current is app.hush.music.together.TogetherSessionState.Idle) return@collect
                                scope.launch(SilentHandler) {
                                    val currentState = togetherSessionState.value
                                    togetherSessionState.value =
                                        app.hush.music.together.TogetherSessionState.Error(
                                            message =
                                                if (currentState is app.hush.music.together.TogetherSessionState.Joined &&
                                                    currentState.role is app.hush.music.together.TogetherRole.Guest
                                                ) {
                                                    getString(R.string.together_host_left_session)
                                                } else {
                                                    getString(R.string.network_unavailable)
                                                },
                                            recoverable = true,
                                        )
                                }
                                ioScope.launch(SilentHandler) { stopTogetherInternal() }
                            }
                        }
                    }
                }

            client.connect(joinInfo, displayName.trim().ifBlank { getString(R.string.together_role_guest) })
        }
    }

    fun joinTogetherOnline(
        code: String,
        displayName: String,
    ) {
        ensureScopesActive()
        val trimmedCode = code.trim()
        if (trimmedCode.isBlank()) {
            scope.launch(SilentHandler) {
                togetherSessionState.value =
                    app.hush.music.together.TogetherSessionState.Error(
                        message = getString(R.string.invalid_code),
                        recoverable = true,
                    )
            }
            return
        }

        scope.launch(SilentHandler) {
            togetherSessionState.value =
                app.hush.music.together.TogetherSessionState
                    .JoiningOnline(trimmedCode)
        }

        ioScope.launch(SilentHandler) {
            stopTogetherInternal()
            togetherIsOnlineSession = true

            val baseUrl =
                app.hush.music.together.TogetherOnlineEndpoint
                    .baseUrlOrNull(dataStore)
            if (baseUrl == null) {
                scope.launch(SilentHandler) {
                    togetherSessionState.value =
                        app.hush.music.together.TogetherSessionState.Error(
                            message = getString(R.string.together_online_not_configured),
                            recoverable = true,
                        )
                }
                return@launch
            }

            val togetherToken =
                app.hush.music.BuildConfig.TOGETHER_BEARER_TOKEN
                    .trim()
                    .takeIf { it.isNotBlank() }
            if (togetherToken == null) {
                scope.launch(SilentHandler) {
                    togetherSessionState.value =
                        app.hush.music.together.TogetherSessionState.Error(
                            message = getString(R.string.together_token_missing),
                            recoverable = true,
                        )
                }
                return@launch
            }

            val api =
                app.hush.music.together
                    .TogetherOnlineApi(baseUrl = baseUrl, bearerToken = togetherToken)
            val resolved =
                runCatching { api.resolveCode(trimmedCode) }
                    .getOrElse { t ->
                        scope.launch(SilentHandler) {
                            togetherSessionState.value =
                                app.hush.music.together.TogetherSessionState.Error(
                                    message = togetherOnlineErrorMessage(t),
                                    recoverable = true,
                                )
                        }
                        reportException(t)
                        return@launch
                    }

            val client =
                app.hush.music.together.TogetherClient(
                    ioScope,
                    clientId = getOrCreateTogetherClientId(),
                    bearerToken = togetherToken,
                )
            togetherClient = client
            togetherClock =
                app.hush.music.together
                    .TogetherClock()
            togetherSelfParticipantId = null
            togetherLastAppliedQueueHash = null

            togetherClientEventsJob?.cancel()
            togetherClientEventsJob =
                ioScope.launch(SilentHandler) {
                    client.events.collect { event ->
                        when (event) {
                            is app.hush.music.together.TogetherClientEvent.Welcome -> {
                                togetherSelfParticipantId = event.welcome.participantId
                                scope.launch(SilentHandler) {
                                    val state = togetherSessionState.value
                                    if (state is app.hush.music.together.TogetherSessionState.JoiningOnline) {
                                        val selfName = displayName.trim().ifBlank { getString(R.string.together_role_guest) }
                                        val initial =
                                            app.hush.music.together.TogetherRoomState(
                                                sessionId = resolved.sessionId,
                                                hostId = togetherHostId,
                                                participants =
                                                    listOf(
                                                        app.hush.music.together.TogetherParticipant(
                                                            id = event.welcome.participantId,
                                                            name = selfName,
                                                            isHost = false,
                                                            isPending = event.welcome.isPending,
                                                            isConnected = true,
                                                        ),
                                                    ),
                                                settings = event.welcome.settings,
                                                queue = emptyList(),
                                                queueHash = "",
                                                currentIndex = 0,
                                                isPlaying = false,
                                                positionMs = 0L,
                                                repeatMode = 0,
                                                shuffleEnabled = false,
                                                sentAtElapsedRealtimeMs = android.os.SystemClock.elapsedRealtime(),
                                            )
                                        togetherSessionState.value =
                                            app.hush.music.together.TogetherSessionState.Joined(
                                                role = app.hush.music.together.TogetherRole.Guest,
                                                sessionId = resolved.sessionId,
                                                selfParticipantId = event.welcome.participantId,
                                                roomState = initial,
                                            )
                                    }
                                }
                                startTogetherHeartbeat(resolved.sessionId, client)
                            }

                            is app.hush.music.together.TogetherClientEvent.RoomState -> {
                                applyRemoteRoomState(event.state)
                            }

                            is app.hush.music.together.TogetherClientEvent.HostTransferred -> {
                                handleTogetherClientHostTransferred(event.transfer)
                            }

                            is app.hush.music.together.TogetherClientEvent.ControlRequested -> {
                                val joined =
                                    togetherSessionState.value as? app.hush.music.together.TogetherSessionState.Joined
                                if (togetherAuthorityParticipantId == togetherSelfParticipantId &&
                                    joined?.roomState?.settings?.allowGuestsToControlPlayback == true
                                ) {
                                    applyHostControl(event.request.action)
                                }
                            }

                            is app.hush.music.together.TogetherClientEvent.AddTrackRequested -> {
                                val joined =
                                    togetherSessionState.value as? app.hush.music.together.TogetherSessionState.Joined
                                if (togetherAuthorityParticipantId == togetherSelfParticipantId &&
                                    joined?.roomState?.settings?.allowGuestsToAddTracks == true
                                ) {
                                    applyHostAddTrack(event.request.track, event.request.mode)
                                }
                            }

                            is app.hush.music.together.TogetherClientEvent.JoinDecision -> {
                                if (!event.decision.approved) {
                                    scope.launch(SilentHandler) {
                                        togetherSessionState.value =
                                            app.hush.music.together.TogetherSessionState.Error(
                                                message = getString(R.string.not_allowed),
                                                recoverable = true,
                                            )
                                    }
                                    ioScope.launch(SilentHandler) { stopTogetherInternal() }
                                }
                            }

                            is app.hush.music.together.TogetherClientEvent.ServerIssue -> {
                                Timber.tag("Together").w("server issue (online) code=${event.code.orEmpty()} message=${event.message}")
                                when (event.code) {
                                    "GUEST_CONTROL_DISABLED" -> {
                                        showTogetherNotice(event.message, key = "GUEST_CONTROL_DISABLED")
                                        val joined =
                                            togetherSessionState.value as? app.hush.music.together.TogetherSessionState.Joined
                                        if (joined?.role is app.hush.music.together.TogetherRole.Guest) {
                                            togetherPendingGuestControl = null
                                            togetherLastSentControlAction = null
                                            scope.launch(SilentHandler) { applyRemoteRoomState(joined.roomState, force = true) }
                                        }
                                    }

                                    "GUEST_ADD_DISABLED" -> {
                                        showTogetherNotice(event.message, key = "GUEST_ADD_DISABLED")
                                    }

                                    "HOST_OFFLINE" -> {
                                        showTogetherNotice(event.message, key = "HOST_OFFLINE")
                                    }

                                    else -> {
                                        scope.launch(SilentHandler) {
                                            togetherSessionState.value =
                                                app.hush.music.together.TogetherSessionState.Error(
                                                    message = event.message,
                                                    recoverable = true,
                                                )
                                        }
                                        ioScope.launch(SilentHandler) { stopTogetherInternal() }
                                    }
                                }
                            }

                            is app.hush.music.together.TogetherClientEvent.HeartbeatPong -> {
                                val clock = togetherClock ?: return@collect
                                clock.onPong(
                                    sentAtElapsedMs = event.pong.clientElapsedRealtimeMs,
                                    receivedAtElapsedMs = event.receivedAtElapsedRealtimeMs,
                                    serverElapsedMs = event.pong.serverElapsedRealtimeMs,
                                )
                            }

                            is app.hush.music.together.TogetherClientEvent.Error -> {
                                scope.launch(SilentHandler) {
                                    togetherSessionState.value =
                                        app.hush.music.together.TogetherSessionState.Error(
                                            message = event.message,
                                            recoverable = true,
                                        )
                                }
                                ioScope.launch(SilentHandler) { stopTogetherInternal() }
                            }

                            app.hush.music.together.TogetherClientEvent.Disconnected -> {
                                val current = togetherSessionState.value
                                if (current is app.hush.music.together.TogetherSessionState.Idle) return@collect
                                scope.launch(SilentHandler) {
                                    val currentState = togetherSessionState.value
                                    togetherSessionState.value =
                                        app.hush.music.together.TogetherSessionState.Error(
                                            message =
                                                if (currentState is app.hush.music.together.TogetherSessionState.Joined &&
                                                    currentState.role is app.hush.music.together.TogetherRole.Guest
                                                ) {
                                                    getString(R.string.together_host_left_session)
                                                } else {
                                                    getString(R.string.network_unavailable)
                                                },
                                            recoverable = true,
                                        )
                                }
                                ioScope.launch(SilentHandler) { stopTogetherInternal() }
                            }
                        }
                    }
                }

            val wsUrl =
                app.hush.music.together.TogetherOnlineEndpoint.onlineWebSocketUrlOrNull(
                    rawWsUrl = resolved.wsUrl,
                    baseUrl = baseUrl,
                )
            if (wsUrl == null) {
                scope.launch(SilentHandler) {
                    togetherSessionState.value =
                        app.hush.music.together.TogetherSessionState.Error(
                            message = "Connection failed: Invalid server websocket URL",
                            recoverable = true,
                        )
                }
                ioScope.launch(SilentHandler) { stopTogetherInternal() }
                return@launch
            }

            client.connect(
                wsUrl = wsUrl,
                sessionId = resolved.sessionId,
                sessionKey = resolved.guestKey,
                displayName = displayName.trim().ifBlank { getString(R.string.together_role_guest) },
            )
        }
    }

    fun leaveTogether() {
        ensureScopesActive()
        scope.launch(SilentHandler) {
            togetherSessionState.value = app.hush.music.together.TogetherSessionState.Idle
        }
        ioScope.launch(SilentHandler) { stopTogetherInternal() }
    }

    fun updateTogetherSettings(settings: app.hush.music.together.TogetherRoomSettings) {
        val server = togetherServer
        val onlineHost = togetherOnlineHost
        if (server == null && onlineHost == null) return
        ioScope.launch(SilentHandler) {
            server?.updateSettings(settings)
            onlineHost?.updateSettings(settings)
        }
    }

    fun approveTogetherParticipant(
        participantId: String,
        approved: Boolean,
    ) {
        val server = togetherServer
        val onlineHost = togetherOnlineHost
        if (server == null && onlineHost == null) return
        ioScope.launch(SilentHandler) {
            server?.approveParticipant(participantId, approved)
            onlineHost?.approveParticipant(participantId, approved)
        }
    }

    fun kickTogetherParticipant(
        participantId: String,
        reason: String? = null,
    ) {
        val onlineHost = togetherOnlineHost ?: return
        ioScope.launch(SilentHandler) {
            onlineHost.kickParticipant(participantId, reason)
        }
    }

    fun banTogetherParticipant(
        participantId: String,
        reason: String? = null,
    ) {
        val onlineHost = togetherOnlineHost ?: return
        ioScope.launch(SilentHandler) {
            onlineHost.banParticipant(participantId, reason)
        }
    }

    fun transferTogetherHostOwnership(participantId: String) {
        val targetId = participantId.trim()
        if (targetId.isBlank() || targetId == togetherHostId || targetId == togetherSelfParticipantId) return
        val server = togetherServer
        val onlineHost = togetherOnlineHost
        val client = togetherClient
        val joined = togetherSessionState.value as? app.hush.music.together.TogetherSessionState.Joined
        ioScope.launch(SilentHandler) {
            when {
                server != null -> server.transferHostOwnership(targetId)
                onlineHost != null -> onlineHost.transferHostOwnership(targetId)
                joined?.role is app.hush.music.together.TogetherRole.Host && client != null -> {
                    client.transferHostOwnership(joined.sessionId, targetId)
                }
            }
        }
    }

    fun requestTogetherControl(action: app.hush.music.together.ControlAction) {
        val client =
            togetherClient ?: run {
                showTogetherNotice(getString(R.string.network_unavailable), key = "TOGETHER_CLIENT_MISSING")
                return
            }
        val state = togetherSessionState.value as? app.hush.music.together.TogetherSessionState.Joined ?: return
        if (state.role !is app.hush.music.together.TogetherRole.Guest) return
        if (!state.roomState.settings.allowGuestsToControlPlayback) {
            Timber.tag("Together").i("control blocked locally (disabled) action=${action::class.java.simpleName}")
            showTogetherNotice(getString(R.string.not_allowed), key = "GUEST_CONTROL_DISABLED_LOCAL")
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val lastAction = togetherLastSentControlAction
        val lastAt = togetherLastSentControlAtElapsedMs
        if (lastAction == action && now - lastAt < 350L) return
        togetherLastSentControlAction = action
        togetherLastSentControlAtElapsedMs = now

        val timeout = if (togetherIsOnlineSession) 5000L else 2000L
        togetherPendingGuestControl =
            when (action) {
                app.hush.music.together.ControlAction.Play -> {
                    TogetherPendingGuestControl(desiredIsPlaying = true, requestedAtElapsedMs = now, expiresAtElapsedMs = now + timeout)
                }

                app.hush.music.together.ControlAction.Pause -> {
                    TogetherPendingGuestControl(desiredIsPlaying = false, requestedAtElapsedMs = now, expiresAtElapsedMs = now + timeout)
                }

                is app.hush.music.together.ControlAction.SeekToIndex -> {
                    TogetherPendingGuestControl(
                        desiredIndex = action.index.coerceAtLeast(0),
                        requestedAtElapsedMs = now,
                        expiresAtElapsedMs =
                            now + timeout,
                    )
                }

                is app.hush.music.together.ControlAction.SeekToTrack -> {
                    TogetherPendingGuestControl(
                        desiredTrackId = action.trackId.trim().ifBlank { null },
                        requestedAtElapsedMs = now,
                        expiresAtElapsedMs = now + timeout,
                    )
                }

                else -> {
                    togetherPendingGuestControl
                }
            }
        client.requestControl(state.sessionId, action)
    }

    fun requestTogetherAddTrack(
        track: app.hush.music.together.TogetherTrack,
        mode: app.hush.music.together.AddTrackMode,
    ) {
        val client = togetherClient ?: return
        val state = togetherSessionState.value as? app.hush.music.together.TogetherSessionState.Joined ?: return
        if (state.role !is app.hush.music.together.TogetherRole.Guest) return
        if (!state.roomState.settings.allowGuestsToAddTracks) {
            Timber.tag("Together").i("add blocked locally (disabled) mode=$mode trackId=${track.id}")
            showTogetherNotice(getString(R.string.not_allowed), key = "GUEST_ADD_DISABLED_LOCAL")
            return
        }
        client.requestAddTrack(state.sessionId, track, mode)
    }

    private suspend fun handleTogetherHostEvent(
        event: app.hush.music.together.TogetherServerEvent,
        currentSettings: suspend () -> app.hush.music.together.TogetherRoomSettings,
    ) {
        when (event) {
            is app.hush.music.together.TogetherServerEvent.ControlRequested -> {
                val settings = currentSettings()
                if (!settings.allowGuestsToControlPlayback) return
                applyHostControl(event.request.action)
            }

            is app.hush.music.together.TogetherServerEvent.AddTrackRequested -> {
                val settings = currentSettings()
                if (!settings.allowGuestsToAddTracks) return
                applyHostAddTrack(event.request.track, event.request.mode)
            }

            is app.hush.music.together.TogetherServerEvent.ParticipantJoined -> {
                val participant = event.participant
                if (!participant.isHost && !participant.isPending) {
                    togetherParticipantNames[participant.id] = participant.name
                    showTogetherParticipantNotification(participant.name, joined = true)
                }
            }

            is app.hush.music.together.TogetherServerEvent.ParticipantLeft -> {
                val participantName =
                    togetherParticipantNames.remove(event.participantId)
                        ?: return
                showTogetherParticipantNotification(participantName, joined = false)
            }

            is app.hush.music.together.TogetherServerEvent.HostTransferred -> {
                handleTogetherHostTransferred(event.participantId)
            }

            is app.hush.music.together.TogetherServerEvent.RoomStateReceived -> {
                if (event.state.hostId != togetherHostId) {
                    togetherSelfParticipantId = togetherHostId
                    applyRemoteRoomState(event.state, force = true)
                }
            }

            is app.hush.music.together.TogetherServerEvent.Error -> {
                val current = togetherSessionState.value
                if (current is app.hush.music.together.TogetherSessionState.Idle) return
                togetherSessionState.value =
                    app.hush.music.together.TogetherSessionState.Error(
                        message = event.message,
                        recoverable = true,
                    )
                ioScope.launch(SilentHandler) { stopTogetherInternal() }
            }

            else -> {
                Unit
            }
        }
    }

    private suspend fun applyHostControl(action: app.hush.music.together.ControlAction) {
        withContext(Dispatchers.Main) {
            when (action) {
                app.hush.music.together.ControlAction.Play -> {
                    if (!player.playWhenReady) {
                        player.prepare()
                        player.playWhenReady = true
                    }
                }

                app.hush.music.together.ControlAction.Pause -> {
                    if (player.playWhenReady) {
                        player.playWhenReady = false
                    }
                }

                is app.hush.music.together.ControlAction.SeekTo -> {
                    player.seekTo(action.positionMs.coerceAtLeast(0L))
                    player.prepare()
                }

                app.hush.music.together.ControlAction.SkipNext -> {
                    if (player.hasNextMediaItem()) {
                        player.seekToNext()
                        player.prepare()
                        player.playWhenReady = true
                    }
                }

                app.hush.music.together.ControlAction.SkipPrevious -> {
                    if (player.hasPreviousMediaItem()) {
                        player.seekToPrevious()
                        player.prepare()
                        player.playWhenReady = true
                    }
                }

                is app.hush.music.together.ControlAction.SeekToTrack -> {
                    val trackId = action.trackId.trim()
                    if (trackId.isNotBlank()) {
                        val idx =
                            player.mediaItems.indexOfFirst {
                                val metaId = it.metadata?.id
                                it.mediaId == trackId || metaId == trackId
                            }
                        if (idx >= 0 && idx < player.mediaItemCount) {
                            player.seekTo(idx, action.positionMs.coerceAtLeast(0L))
                            player.prepare()
                        }
                    }
                }

                is app.hush.music.together.ControlAction.SeekToIndex -> {
                    val idx = action.index.coerceAtLeast(0)
                    if (idx < player.mediaItemCount) {
                        player.seekTo(idx, action.positionMs.coerceAtLeast(0L))
                        player.prepare()
                    }
                }

                is app.hush.music.together.ControlAction.SetRepeatMode -> {
                    if (player.repeatMode != action.repeatMode) {
                        player.repeatMode = action.repeatMode
                    }
                }

                is app.hush.music.together.ControlAction.SetShuffleEnabled -> {
                    if (player.shuffleModeEnabled != action.shuffleEnabled) {
                        player.shuffleModeEnabled = action.shuffleEnabled
                    }
                }
            }
        }
    }

    private suspend fun applyHostAddTrack(
        track: app.hush.music.together.TogetherTrack,
        mode: app.hush.music.together.AddTrackMode,
    ) {
        val mediaItem = track.toMediaMetadata().toMediaItem()
        withContext(Dispatchers.Main) {
            when (mode) {
                app.hush.music.together.AddTrackMode.PLAY_NEXT -> playNext(listOf(mediaItem))
                app.hush.music.together.AddTrackMode.ADD_TO_QUEUE -> addToQueue(listOf(mediaItem))
            }
        }
    }

    private suspend fun buildTogetherRoomState(
        sessionId: String,
        hostId: String,
    ): app.hush.music.together.TogetherRoomState =
        withContext(Dispatchers.Main) {
            val tracks =
                player.mediaItems.mapNotNull { it.metadata }.map { meta ->
                    app.hush.music.together.TogetherTrack(
                        id = meta.id,
                        title = meta.title,
                        artists = meta.artists.map { it.name },
                        durationSec = meta.duration,
                        thumbnailUrl = meta.thumbnailUrl,
                    )
                }

            val queueHash =
                app.hush.music.utils
                    .md5(tracks.joinToString(separator = "|") { it.id })

            app.hush.music.together.TogetherRoomState(
                sessionId = sessionId,
                hostId = hostId,
                settings =
                    app.hush.music.together
                        .TogetherRoomSettings(),
                participants = emptyList(),
                queue = tracks,
                queueHash = queueHash,
                currentIndex = player.currentMediaItemIndex.coerceAtLeast(0),
                isPlaying = player.playWhenReady && player.playbackState != Player.STATE_ENDED,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                repeatMode = player.repeatMode,
                shuffleEnabled = player.shuffleModeEnabled,
                sentAtElapsedRealtimeMs = android.os.SystemClock.elapsedRealtime(),
            )
        }

    private fun markTogetherHostParticipant(
        state: app.hush.music.together.TogetherRoomState,
        hostId: String,
    ): app.hush.music.together.TogetherRoomState =
        state.copy(
            hostId = hostId,
            participants =
                state.participants.map { participant ->
                    participant.copy(isHost = participant.id == hostId)
                },
        )

    private fun handleTogetherHostTransferred(participantId: String) {
        togetherAuthorityParticipantId = participantId
        if (participantId != togetherHostId) {
            togetherSelfParticipantId = togetherHostId
        }
        scope.launch(SilentHandler) {
            when (val current = togetherSessionState.value) {
                is app.hush.music.together.TogetherSessionState.Hosting -> {
                    val roomState = current.roomState?.let { markTogetherHostParticipant(it, participantId) }
                    togetherSessionState.value =
                        app.hush.music.together.TogetherSessionState.Joined(
                            role =
                                if (participantId == togetherHostId) {
                                    app.hush.music.together.TogetherRole.Host
                                } else {
                                    app.hush.music.together.TogetherRole.Guest
                                },
                            sessionId = current.sessionId,
                            selfParticipantId = togetherHostId,
                            roomState =
                                roomState
                                    ?: app.hush.music.together.TogetherRoomState(
                                        sessionId = current.sessionId,
                                        hostId = participantId,
                                    ),
                        )
                }

                is app.hush.music.together.TogetherSessionState.HostingOnline -> {
                    val roomState = current.roomState?.let { markTogetherHostParticipant(it, participantId) }
                    togetherSessionState.value =
                        app.hush.music.together.TogetherSessionState.Joined(
                            role =
                                if (participantId == togetherHostId) {
                                    app.hush.music.together.TogetherRole.Host
                                } else {
                                    app.hush.music.together.TogetherRole.Guest
                                },
                            sessionId = current.sessionId,
                            selfParticipantId = togetherHostId,
                            roomState =
                                roomState
                                    ?: app.hush.music.together.TogetherRoomState(
                                        sessionId = current.sessionId,
                                        hostId = participantId,
                                    ),
                        )
                }

                is app.hush.music.together.TogetherSessionState.Joined -> {
                    togetherSessionState.value =
                        current.copy(
                            role =
                                if (current.selfParticipantId == participantId) {
                                    app.hush.music.together.TogetherRole.Host
                                } else {
                                    app.hush.music.together.TogetherRole.Guest
                                },
                            roomState = markTogetherHostParticipant(current.roomState, participantId),
                        )
                }

                else -> Unit
            }
        }
    }

    private fun handleTogetherClientHostTransferred(transfer: app.hush.music.together.HostTransferred) {
        val participantId = transfer.participantId
        handleTogetherHostTransferred(participantId)
        val client = togetherClient ?: return
        if (participantId != togetherSelfParticipantId) return
        startTogetherAuthorityBroadcast(transfer.sessionId, participantId, client)
    }

    private fun startTogetherAuthorityBroadcast(
        sessionId: String,
        participantId: String,
        client: app.hush.music.together.TogetherClient,
    ) {
        togetherBroadcastJob?.cancel()
        togetherBroadcastJob =
            ioScope.launch(SilentHandler) {
                while (togetherClient === client && togetherAuthorityParticipantId == participantId) {
                    val state = buildTogetherRoomState(sessionId = sessionId, hostId = participantId)
                    client.sendRoomState(state)
                    kotlinx.coroutines.delay(TogetherPlaybackSync.BroadcastIntervalMs)
                }
            }
    }

    private suspend fun applyRemoteRoomState(
        state: app.hush.music.together.TogetherRoomState,
        force: Boolean = false,
    ) {
        val pid = togetherSelfParticipantId ?: return
        val now = android.os.SystemClock.elapsedRealtime()

        val pending = togetherPendingGuestControl
        if (force) {
            togetherPendingGuestControl = null
        } else if (pending != null) {
            val currentTrackId = state.queue.getOrNull(state.currentIndex.coerceAtLeast(0))?.id
            val mismatch =
                (pending.desiredIsPlaying != null && state.isPlaying != pending.desiredIsPlaying) ||
                    (pending.desiredIndex != null && state.currentIndex != pending.desiredIndex) ||
                    (pending.desiredTrackId != null && currentTrackId != pending.desiredTrackId)
            if (now >= pending.expiresAtElapsedMs) {
                if ((pending.desiredIndex != null || pending.desiredTrackId != null) &&
                    now - pending.requestedAtElapsedMs >= 1200L &&
                    mismatch
                ) {
                    showTogetherNotice(getString(R.string.together_song_change_failed), key = "GUEST_SEEK_TIMEOUT")
                }
                togetherPendingGuestControl = null
            } else {
                if (mismatch) return
                togetherPendingGuestControl = null
            }
        }

        val sentAt = state.sentAtElapsedRealtimeMs
        if (TogetherPlaybackSync.isStaleRoomState(
                sentAtElapsedRealtimeMs = sentAt,
                lastAppliedSentAtElapsedRealtimeMs = togetherLastAppliedRoomStateSentAtElapsedMs,
                force = force,
            )
        ) {
            return
        }

        val targetPos =
            TogetherPlaybackSync.targetPositionMs(
                state = state,
                isOnlineSession = togetherIsOnlineSession,
                clockSnapshot = if (togetherIsOnlineSession) null else togetherClock?.snapshot(),
                nowElapsedRealtimeMs = now,
            )

        withContext(Dispatchers.Main) {
            togetherApplyingRemote = true
            togetherSuppressEchoUntilElapsedMs =
                TogetherPlaybackSync.echoSuppressionUntil(
                    android.os.SystemClock.elapsedRealtime(),
                )
            try {
                val desiredItems = state.queue.map { it.toMediaMetadata().toMediaItem() }
                val desiredIds = state.queue.map { it.id }
                val desiredHash = state.queueHash
                val localIds = player.mediaItems.mapNotNull { it.metadata?.id ?: it.mediaId }.filter { it.isNotBlank() }
                val localHash =
                    if (localIds.isEmpty()) {
                        ""
                    } else {
                        app.hush.music.utils
                            .md5(localIds.joinToString(separator = "|"))
                    }
                val needsRebuild =
                    TogetherPlaybackSync.needsQueueRebuild(
                        desiredHash = desiredHash,
                        desiredIds = desiredIds,
                        localHash = localHash,
                        localIds = localIds,
                    )

                if (desiredItems.isNotEmpty() && needsRebuild) {
                    togetherLastAppliedQueueHash = desiredHash.ifBlank { localHash }
                    val startIndex = state.currentIndex.coerceIn(0, desiredItems.lastIndex)
                    suppressAutoPlayback = false
                    currentQueue =
                        app.hush.music.playback.queues.ListQueue(
                            title = getString(R.string.music_player),
                            items = desiredItems,
                            startIndex = startIndex,
                            position = targetPos,
                        )
                    queueTitle = null
                    player.setMediaItems(desiredItems, startIndex, targetPos)
                    player.prepare()
                    player.repeatMode = state.repeatMode
                    player.shuffleModeEnabled = state.shuffleEnabled
                    player.playWhenReady = state.isPlaying
                    togetherLastRemoteAppliedIndex = startIndex
                } else {
                    val index =
                        if (player.mediaItemCount > 0) {
                            state.currentIndex.coerceIn(0, player.mediaItemCount - 1)
                        } else {
                            0
                        }
                    val indexChanged = player.mediaItemCount > 0 && index != player.currentMediaItemIndex

                    if (indexChanged) {
                        if (player.repeatMode != state.repeatMode) player.repeatMode = state.repeatMode
                        if (player.shuffleModeEnabled != state.shuffleEnabled) player.shuffleModeEnabled = state.shuffleEnabled
                        player.seekTo(index, targetPos)
                        player.prepare()
                        player.playWhenReady = state.isPlaying
                    } else {
                        val playbackStateChanged = player.playWhenReady != state.isPlaying
                        if (player.repeatMode != state.repeatMode) player.repeatMode = state.repeatMode
                        if (player.shuffleModeEnabled != state.shuffleEnabled) player.shuffleModeEnabled = state.shuffleEnabled
                        if (playbackStateChanged) player.playWhenReady = state.isPlaying
                        val shouldSeekForDrift =
                            TogetherPlaybackSync.shouldSeekForDrift(
                                currentPositionMs = player.currentPosition,
                                targetPositionMs = targetPos,
                                isPlaying = state.isPlaying,
                                isOnlineSession = togetherIsOnlineSession,
                            )
                        if (shouldSeekForDrift || (playbackStateChanged && !state.isPlaying)) {
                            player.seekTo(targetPos)
                            player.prepare()
                        }
                    }
                    togetherLastRemoteAppliedIndex = index
                }
                togetherLastRemoteAppliedPlayWhenReady = state.isPlaying
                togetherLastAppliedRoomStateSentAtElapsedMs = sentAt

                togetherSessionState.value =
                    app.hush.music.together.TogetherSessionState.Joined(
                        role = app.hush.music.together.TogetherRole.Guest,
                        sessionId = state.sessionId,
                        selfParticipantId = pid,
                        roomState = state,
                    )
            } finally {
                togetherApplyingRemote = false
            }
        }
    }

    private fun startTogetherHeartbeat(
        sessionId: String,
        client: app.hush.music.together.TogetherClient,
    ) {
        togetherHeartbeatJob?.cancel()
        togetherHeartbeatJob =
            ioScope.launch(SilentHandler) {
                var pingId = 0L
                while (togetherClient === client) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    client.sendHeartbeat(sessionId = sessionId, pingId = pingId++, clientElapsedRealtimeMs = now)
                    kotlinx.coroutines.delay(2000)
                }
            }
    }

    private suspend fun stopTogetherInternal() {
        togetherBroadcastJob?.cancel()
        togetherBroadcastJob = null

        togetherOnlineConnectJob?.cancel()
        togetherOnlineConnectJob = null

        togetherClientEventsJob?.cancel()
        togetherClientEventsJob = null

        togetherHeartbeatJob?.cancel()
        togetherHeartbeatJob = null

        togetherClock = null
        togetherSelfParticipantId = null
        togetherAuthorityParticipantId = null
        togetherParticipantNames.clear()
        togetherLastAppliedQueueHash = null
        togetherIsOnlineSession = false
        togetherApplyingRemote = false
        togetherSuppressEchoUntilElapsedMs = 0L
        togetherLastAppliedRoomStateSentAtElapsedMs = 0L
        togetherLastRemoteAppliedPlayWhenReady = null
        togetherLastRemoteAppliedIndex = -1
        togetherLastSentControlAtElapsedMs = 0L
        togetherLastSentControlAction = null
        togetherPendingGuestControl = null

        try {
            togetherClient?.disconnect()
        } catch (_: Exception) {
        }
        togetherClient = null

        try {
            togetherOnlineHost?.disconnect()
        } catch (_: Exception) {
        }
        togetherOnlineHost = null

        try {
            togetherServer?.stop()
        } catch (_: Exception) {
        }
        togetherServer = null
    }

    private fun app.hush.music.together.TogetherTrack.toMediaMetadata(): app.hush.music.models.MediaMetadata =
        app.hush.music.models.MediaMetadata(
            id = id,
            title = title,
            artists =
                artists.map { name ->
                    app.hush.music.models.MediaMetadata
                        .Artist(id = null, name = name)
                },
            duration = durationSec,
            thumbnailUrl = thumbnailUrl,
            album = null,
            setVideoId = null,
            explicit = false,
            liked = false,
            likedDate = null,
            inLibrary = null,
        )

    private fun getLocalIpv4Address(): String? =
        runCatching {
            java.net.NetworkInterface
                .getNetworkInterfaces()
                .toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<java.net.Inet4Address>()
                .map { it.hostAddress }
                .firstOrNull { it.isNotBlank() && it != "127.0.0.1" }
        }.getOrNull()

    private fun toggleLibrary() {
        ensureScopesActive()
        database.query {
            currentSong.value?.let {
                update(it.song.toggleLibrary())
            }
        }
    }

    fun toggleLike() {
        ensureScopesActive()
        val metadata = currentMediaMetadata.value ?: player.currentMediaItem?.metadata ?: return
        val mediaId =
            metadata.id.trim().ifBlank {
                player.currentMediaItem?.mediaId?.trim().orEmpty()
            }
        if (mediaId.isBlank()) {
            Timber.tag(TAG).w("toggleLike ignored: current item has no media id")
            return
        }

        Timber.tag(TAG).d("toggleLike: mediaId=$mediaId")
        ioScope.launch {
            val updatedSong =
                likeToggleMutex.withLock {
                    database.withTransaction {
                        val existing = getSongById(mediaId)?.song
                        val baseSong =
                            existing
                                ?: run {
                                    database.insert(
                                        metadata.copy(
                                            duration = metadata.duration.takeIf { it > 0 } ?: -1,
                                        ),
                                    )
                                    getSongById(mediaId)?.song
                                }
                                ?: return@withTransaction null

                        val toggled = baseSong.toggleLike()
                        android.util.Log.w(
                            TAG,
                            "toggleLike: mediaId=$mediaId liked=${baseSong.liked} -> toggled=${toggled.liked}",
                        )
                        update(toggled)
                        toggled
                    }
                } ?: run {
                    android.util.Log.w(TAG, "toggleLike failed: no database song for mediaId=$mediaId")
                    return@launch
                }

            withContext(Dispatchers.Main.immediate) {
                // Keep notification/Waze state correct immediately; Room will update the
                // Compose Flow used by the player controls as the transaction is observed.
                currentMediaMetadata.value = currentMediaMetadata.value?.copy(liked = updatedSong.liked)
                wazeLikedState = updatedSong.liked
                wazeLikedMediaId = updatedSong.id
                updateNotification()
                publishWazePlaybackSnapshot(force = true)
            }

            Timber.tag(TAG).d(
                "toggleLike: calling syncUtils.likeSong for ${updatedSong.id} liked=${updatedSong.liked}",
            )
            syncUtils.likeSong(updatedSong)

            if (!mediaId.isLocalMediaId()) {
                runCatching { recoverSong(mediaId) }
                    .onFailure { error -> Timber.tag(TAG).w(error, "toggleLike metadata recovery failed for $mediaId") }
            }

            val autoDownloadOnLike = dataStore.get(AutoDownloadOnLikeKey, false)
            if (!updatedSong.isLocal && autoDownloadOnLike && updatedSong.liked) {
                val downloadRequest =
                    androidx.media3.exoplayer.offline.DownloadRequest
                        .Builder(updatedSong.id, updatedSong.id.toUri())
                        .setCustomCacheKey(updatedSong.id)
                        .setData(updatedSong.title.toByteArray())
                        .build()
                androidx.media3.exoplayer.offline.DownloadService.sendAddDownload(
                    this@MusicService,
                    ExoDownloadService::class.java,
                    downloadRequest,
                    false,
                )
            }
        }
    }

    fun toggleDownload() {
        val metadata = currentMediaMetadata.value ?: return
        val mediaId = metadata.id.trim()
        if (mediaId.isBlank()) return
        ensureScopesActive()
        ioScope.launch {
            val song = database.getSongById(mediaId)?.song ?: run {
                database.insert(metadata.copy(duration = metadata.duration.takeIf { it > 0 } ?: -1))
                database.getSongById(mediaId)?.song
            } ?: return@launch
            if (!song.isLocal) {
                val downloadRequest = androidx.media3.exoplayer.offline.DownloadRequest
                    .Builder(song.id, song.id.toUri())
                    .setCustomCacheKey(song.id)
                    .setData(song.title.toByteArray())
                    .build()
                androidx.media3.exoplayer.offline.DownloadService.sendAddDownload(
                    this@MusicService,
                    ExoDownloadService::class.java,
                    downloadRequest,
                    false,
                )
            }
        }
    }

    fun toggleShuffleMode() {
        player.shuffleModeEnabled = !player.shuffleModeEnabled
    }

    fun toggleRepeatMode() {
        player.repeatMode = when (player.repeatMode) {
            androidx.media3.common.Player.REPEAT_MODE_OFF -> androidx.media3.common.Player.REPEAT_MODE_ONE
            androidx.media3.common.Player.REPEAT_MODE_ONE -> androidx.media3.common.Player.REPEAT_MODE_ALL
            else -> androidx.media3.common.Player.REPEAT_MODE_OFF
        }
    }

    fun toggleStartRadio() {
        startRadioSeamlessly()
    }

    private fun decodeBandLevelsMb(raw: String?): List<Int> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { EqualizerJson.json.decodeFromString<List<Int>>(raw) }.getOrNull() ?: emptyList()
    }

    private fun encodeBandLevelsMb(levelsMb: List<Int>): String =
        runCatching {
            EqualizerJson.json.encodeToString(levelsMb)
        }.getOrNull().orEmpty()

    private fun readEqSettingsFromPrefs(prefs: Preferences): EqSettings {
        val levels = decodeBandLevelsMb(prefs[EqualizerBandLevelsMbKey])
        return EqSettings(
            enabled = prefs[EqualizerEnabledKey] ?: false,
            bandLevelsMb = levels,
            outputGainEnabled = prefs[EqualizerOutputGainEnabledKey] ?: false,
            outputGainMb = prefs[EqualizerOutputGainMbKey] ?: 0,
            bassBoostEnabled = prefs[EqualizerBassBoostEnabledKey] ?: false,
            bassBoostStrength = (prefs[EqualizerBassBoostStrengthKey] ?: 0).coerceIn(0, 1000),
            virtualizerEnabled = prefs[EqualizerVirtualizerEnabledKey] ?: false,
            virtualizerStrength = (prefs[EqualizerVirtualizerStrengthKey] ?: 0).coerceIn(0, 1000),
        )
    }

    fun applyEqFlatPreset() {
        ioScope.launch {
            val caps = eqCapabilities.value
            val bandCount =
                caps?.bandCount ?: equalizer?.let { readAudioEffectValue("equalizer band count") { it.numberOfBands.toInt() } } ?: 0
            val encoded = encodeBandLevelsMb(List(bandCount.coerceAtLeast(0)) { 0 })
            dataStore.edit { prefs ->
                prefs[EqualizerEnabledKey] = true
                prefs[EqualizerBandLevelsMbKey] = encoded
                prefs[EqualizerSelectedProfileIdKey] = "flat"
            }
        }
    }

    fun applySystemEqPreset(presetIndex: Int) {
        scope.launch {
            ensureAudioEffects(localPlayer.audioSessionId)
            val eq = equalizer ?: return@launch
            val maxPreset = readAudioEffectValue("equalizer preset count") { eq.numberOfPresets.toInt() } ?: 0
            if (presetIndex !in 0 until maxPreset) return@launch

            runCatching { eq.usePreset(presetIndex.toShort()) }.getOrNull() ?: return@launch

            val bandCount = readAudioEffectValue("equalizer band count") { eq.numberOfBands.toInt() } ?: 0
            val levels =
                (0 until bandCount).map { band ->
                    readAudioEffectValue("equalizer band level for band $band") {
                        eq.getBandLevel(band.toShort()).toInt()
                    } ?: 0
                }

            val encoded = encodeBandLevelsMb(levels)
            if (encoded.isBlank()) return@launch

            ioScope.launch {
                dataStore.edit { prefs ->
                    prefs[EqualizerEnabledKey] = true
                    prefs[EqualizerBandLevelsMbKey] = encoded
                    prefs[EqualizerSelectedProfileIdKey] = "system:$presetIndex"
                }
            }
        }
    }

    private fun resampleLevelsByIndex(
        levelsMb: List<Int>,
        targetCount: Int,
    ): List<Int> {
        if (targetCount <= 0) return emptyList()
        if (levelsMb.isEmpty()) return List(targetCount) { 0 }
        if (levelsMb.size == targetCount) return levelsMb
        if (targetCount == 1) return listOf(levelsMb.sum() / levelsMb.size)

        val lastIndex = levelsMb.lastIndex.toFloat().coerceAtLeast(1f)
        return List(targetCount) { i ->
            val pos = i.toFloat() * lastIndex / (targetCount - 1).toFloat()
            val lo =
                kotlin.math
                    .floor(pos)
                    .toInt()
                    .coerceIn(0, levelsMb.lastIndex)
            val hi =
                kotlin.math
                    .ceil(pos)
                    .toInt()
                    .coerceIn(0, levelsMb.lastIndex)
            val t = (pos - lo.toFloat()).coerceIn(0f, 1f)
            val a = levelsMb[lo]
            val b = levelsMb[hi]
            (a + ((b - a) * t)).toInt()
        }
    }

    private inline fun <T> readAudioEffectValue(
        operation: String,
        block: () -> T,
    ): T? =
        runCatching(block)
            .onFailure { error ->
                Timber.tag("MusicService").w(error, "Audio effect query failed: %s", operation)
            }.getOrNull()

    private fun updateEqCapabilitiesFromEffect(eq: Equalizer) {
        val bandCount = readAudioEffectValue("equalizer band count") { eq.numberOfBands.toInt().coerceAtLeast(0) } ?: 0
        val range = readAudioEffectValue("equalizer band range") { eq.bandLevelRange }
        val minMb = range?.getOrNull(0)?.toInt() ?: -1500
        val maxMb = range?.getOrNull(1)?.toInt() ?: 1500
        val center =
            (0 until bandCount).map { band ->
                (
                    readAudioEffectValue("equalizer center frequency for band $band") {
                        eq.getCenterFreq(band.toShort())
                    } ?: 0
                ) / 1000
            }
        val presetCount = readAudioEffectValue("equalizer preset count") { eq.numberOfPresets.toInt().coerceAtLeast(0) } ?: 0
        val presets =
            (0 until presetCount).map { idx ->
                readAudioEffectValue("equalizer preset name for preset $idx") {
                    eq.getPresetName(idx.toShort()).toString()
                } ?: "Preset ${idx + 1}"
            }
        eqCapabilities.value =
            EqCapabilities(
                bandCount = bandCount,
                minBandLevelMb = minMb,
                maxBandLevelMb = maxMb,
                centerFreqHz = center,
                systemPresets = presets,
            )
    }

    private fun releaseAudioEffects() {
        audioEffectsSessionId = null
        try {
            equalizer?.release()
        } catch (_: Exception) {
        }
        try {
            bassBoost?.release()
        } catch (_: Exception) {
        }
        try {
            virtualizer?.release()
        } catch (_: Exception) {
        }
        try {
            loudnessEnhancer?.release()
        } catch (_: Exception) {
        }
        equalizer = null
        bassBoost = null
        virtualizer = null
        loudnessEnhancer = null
        eqCapabilities.value = null
    }

    private fun ensureAudioEffects(sessionId: Int, force: Boolean = false) {
        if (sessionId <= 0) return
        if (!force && audioEffectsSessionId == sessionId && equalizer != null) return

        releaseAudioEffects()
        audioEffectsSessionId = sessionId

        val settings = desiredEqSettings.value

        // Each AudioEffect occupies space in the platform's audio-effect budget: a small
        // pool shared device-wide with OEM effects (this device already carries "Music
        // Listener" on the playback session and Dolby's "DAP" on the output mix).
        // Attaching all four unconditionally meant a user with every EQ feature switched
        // off still consumed four slots, and when the pool runs out the platform refuses
        // to register anything — which silently takes the equaliser *and* the visualiser
        // down with it. Create the optional colouring effects only when they are in use.
        // Nothing is created for a feature the user has not switched on. That matters more
        // than it looks: the platform pool is only 512 KB and is shared device-wide, and each
        // playback session used to add a fresh four-effect set that was never reclaimed if
        // the process died without closing the session cleanly. Those stale sets accumulate
        // until the pool is full, after which NOTHING can register — not the equaliser, not
        // the visualiser, not even the system's own effects.
        equalizer =
            if (settings.enabled) {
                createAudioEffect("Equalizer", sessionId) { Equalizer(0, sessionId) }
            } else {
                null
            }
        bassBoost =
            if (settings.bassBoostEnabled) {
                createAudioEffect("BassBoost", sessionId) { BassBoost(0, sessionId) }
            } else {
                null
            }
        virtualizer =
            if (settings.virtualizerEnabled) {
                createAudioEffect("Virtualizer", sessionId) { Virtualizer(0, sessionId) }
            } else {
                null
            }
        loudnessEnhancer =
            if (settings.outputGainEnabled) {
                createAudioEffect("LoudnessEnhancer", sessionId) { LoudnessEnhancer(sessionId) }
            } else {
                null
            }

        equalizer?.let(::updateEqCapabilitiesFromEffect)
        applyEqSettingsToEffects(settings)
    }

    /**
     * Creates one audio effect while reporting the outcome.
     *
     * These used to fail completely silently (`runCatching { .. }.getOrNull()`), so a
     * device whose effect budget was exhausted looked identical to one where the EQ was
     * simply off, and the equaliser appeared to do nothing with no way to tell why. The
     * platform's refusal is also the same failure that stops the visualiser.
     */
    private fun <T> createAudioEffect(
        name: String,
        sessionId: Int,
        factory: () -> T,
    ): T? = try {
        factory()
    } catch (error: Throwable) {
        Timber.tag(TAG).w(
            error,
            "Audio effect %s unavailable on session %d: %s",
            name,
            sessionId,
            error.message,
        )
        app.hush.music.spotiflac.SpotiFLACDiag.log(
            "audio effect $name unavailable session=$sessionId: ${error.message}",
        )
        null
    }

    private fun applyEqSettingsToEffects(settings: EqSettings) {
        // Each effect is handled independently. This used to bail out entirely when the
        // equaliser was absent, which would have silently skipped the bass/virtualiser/gain
        // settings once the equaliser stopped being created unconditionally.
        equalizer?.let { eq ->
            val caps = eqCapabilities.value
            val bandCount = caps?.bandCount ?: readAudioEffectValue("equalizer band count") { eq.numberOfBands.toInt() } ?: 0
            val minMb =
                caps?.minBandLevelMb ?: readAudioEffectValue("equalizer minimum band level") { eq.bandLevelRange.getOrNull(0)?.toInt() }
                    ?: -1500
            val maxMb =
                caps?.maxBandLevelMb ?: readAudioEffectValue("equalizer maximum band level") { eq.bandLevelRange.getOrNull(1)?.toInt() } ?: 1500

            val levels = resampleLevelsByIndex(settings.bandLevelsMb, bandCount)
            runCatching { eq.enabled = settings.enabled }

            for (band in 0 until bandCount) {
                val levelMb = levels.getOrNull(band)?.coerceIn(minMb, maxMb) ?: 0
                runCatching { eq.setBandLevel(band.toShort(), levelMb.toShort()) }
            }
        }

        bassBoost?.let { bb ->
            runCatching { bb.enabled = settings.bassBoostEnabled }
            runCatching { bb.setStrength(settings.bassBoostStrength.toShort()) }
        }

        virtualizer?.let { v ->
            runCatching { v.enabled = settings.virtualizerEnabled }
            runCatching { v.setStrength(settings.virtualizerStrength.toShort()) }
        }

        loudnessEnhancer?.let { le ->
            val gainMb = if (settings.outputGainEnabled) settings.outputGainMb.coerceIn(-1500, 1500) else 0
            runCatching { le.setTargetGain(gainMb) }
            runCatching { le.enabled = settings.outputGainEnabled }
        }
    }

    private fun shouldKeepAudioEffectSessionOpen(): Boolean {
        val playbackState = player.playbackState
        return playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY
    }

    private fun openAudioEffectSession() {
        if (isAudioEffectSessionOpened) return
        val sessionId = localPlayer.audioSessionId
        if (sessionId <= 0) return
        isAudioEffectSessionOpened = true
        openedAudioSessionId = sessionId
        ensureAudioEffects(sessionId)
        sendOpenAudioEffectSessionBroadcast(sessionId)
    }

    private fun closeAudioEffectSession() {
        if (!isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = false
        val sessionId = openedAudioSessionId ?: localPlayer.audioSessionId
        openedAudioSessionId = null
        releaseAudioEffects()
        if (sessionId <= 0) return
        sendCloseAudioEffectSessionBroadcast(sessionId)
    }

    private fun rebindAudioEffectSession(newSessionId: Int) {
        if (newSessionId <= 0 || !shouldKeepAudioEffectSessionOpen()) return
        val oldSessionId = openedAudioSessionId
        if (!isAudioEffectSessionOpened) {
            openAudioEffectSession()
            return
        }
        if (oldSessionId == newSessionId) {
            ensureAudioEffects(newSessionId)
            return
        }

        if (oldSessionId != null && oldSessionId > 0) {
            sendCloseAudioEffectSessionBroadcast(oldSessionId)
        }
        openedAudioSessionId = newSessionId
        ensureAudioEffects(newSessionId)
        sendOpenAudioEffectSessionBroadcast(newSessionId)
    }

    private fun sendOpenAudioEffectSessionBroadcast(sessionId: Int) {
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            },
        )
    }

    private fun sendCloseAudioEffectSessionBroadcast(sessionId: Int) {
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            },
        )
    }

    private fun historyThresholdMs(): Long =
        (runCatching { dataStore[HistoryDuration] }.getOrNull() ?: HISTORY_DURATION_DEFAULT)
            .coerceIn(HISTORY_DURATION_MIN, HISTORY_DURATION_MAX)
            .toLong() * 1000L

    private fun currentHistoryPlayedMs(nowElapsedMs: Long = android.os.SystemClock.elapsedRealtime()): Long {
        val runningPlayMs =
            currentHistoryStartedAtElapsedMs
                ?.let { (nowElapsedMs - it).coerceAtLeast(0L) }
                ?: 0L
        return currentHistoryAccumulatedPlayMs + runningPlayMs
    }

    private fun flushCurrentHistoryPlayedTime(nowElapsedMs: Long = android.os.SystemClock.elapsedRealtime()) {
        currentHistoryAccumulatedPlayMs = currentHistoryPlayedMs(nowElapsedMs)
        currentHistoryStartedAtElapsedMs = null
    }

    private fun updatePendingHistoryFinalization(
        mediaId: String,
        sessionToken: Long,
        result: ImmediateHistoryResult,
    ) {
        val pendingSessions = pendingHistoryFinalizations[mediaId] ?: return
        val index = pendingSessions.indexOfFirst { it.sessionToken == sessionToken }
        if (index == -1) return

        val existing = pendingSessions[index]
        pendingSessions[index] =
            existing.copy(
                eventId = result.eventId ?: existing.eventId,
                remoteRegistered = existing.remoteRegistered || result.remoteRegistered,
            )
    }

    private fun enqueueCurrentHistorySessionForFinalization() {
        val mediaId = currentHistoryMediaId ?: return
        if (currentHistorySessionQueued) return

        pendingHistoryFinalizations
            .getOrPut(mediaId) { mutableListOf() }
            .add(
                PendingHistoryFinalization(
                    sessionToken = currentHistorySessionToken,
                    eventId = currentHistoryEventId,
                    remoteRegistered = currentHistoryRemoteRegistered,
                ),
            )
        currentHistorySessionQueued = true
    }

    private fun popPendingHistoryFinalization(mediaId: String): PendingHistoryFinalization? {
        val pendingSessions = pendingHistoryFinalizations[mediaId] ?: return null
        val pending = pendingSessions.firstOrNull() ?: return null
        pendingSessions.removeAt(0)
        if (pendingSessions.isEmpty()) {
            pendingHistoryFinalizations.remove(mediaId)
        }
        return pending
    }

    private fun beginHistorySession(
        mediaId: String?,
        forceNew: Boolean = false,
    ) {
        val normalizedMediaId = mediaId?.trim()?.takeIf { it.isNotEmpty() }
        if (!forceNew && currentHistoryMediaId == normalizedMediaId && currentHistorySessionToken != 0L) {
            updateHistoryTrackingPlaybackState()
            return
        }

        historyThresholdJob?.cancel()
        historyThresholdJob = null
        flushCurrentHistoryPlayedTime()
        enqueueCurrentHistorySessionForFinalization()

        currentHistorySessionToken = ++nextHistorySessionToken
        currentHistoryMediaId = normalizedMediaId
        currentHistoryAccumulatedPlayMs = 0L
        currentHistoryStartedAtElapsedMs = null
        currentHistoryEventId = null
        currentHistoryRemoteRegistered = false
        currentHistoryImmediateAttempted = false
        currentHistorySessionQueued = false

        updateHistoryTrackingPlaybackState()
    }

    private fun updateHistoryTrackingPlaybackState() {
        val mediaId = currentHistoryMediaId
        if (mediaId == null || currentHistorySessionQueued) {
            historyThresholdJob?.cancel()
            historyThresholdJob = null
            currentHistoryStartedAtElapsedMs = null
            return
        }

        if (player.isPlaying) {
            if (currentHistoryStartedAtElapsedMs == null) {
                currentHistoryStartedAtElapsedMs = android.os.SystemClock.elapsedRealtime()
            }
        } else {
            flushCurrentHistoryPlayedTime()
        }

        syncHistoryThresholdJob()
    }

    private fun syncHistoryThresholdJob() {
        historyThresholdJob?.cancel()
        historyThresholdJob = null

        val mediaId = currentHistoryMediaId ?: return
        if (currentHistorySessionQueued) return
        if (dataStore.get(PauseListenHistoryKey, false)) return
        if (currentHistoryEventId != null && currentHistoryRemoteRegistered) return

        val thresholdMs = historyThresholdMs()
        val playedMs = currentHistoryPlayedMs()
        if (playedMs >= thresholdMs) {
            if (!currentHistoryImmediateAttempted) {
                maybeRecordCurrentPlaybackHistory()
            }
            return
        }
        if (!player.isPlaying) return

        historyThresholdJob =
            scope.launch {
                delay((thresholdMs - playedMs).coerceAtLeast(0L))
                maybeRecordCurrentPlaybackHistory()
            }
    }

    private fun maybeRecordCurrentPlaybackHistory() {
        val mediaId = currentHistoryMediaId ?: return
        if (currentHistorySessionQueued) return
        if (dataStore.get(PauseListenHistoryKey, false)) return

        val thresholdMs = historyThresholdMs()
        val playedMs = currentHistoryPlayedMs()
        if (playedMs < thresholdMs) {
            syncHistoryThresholdJob()
            return
        }

        val sessionToken = currentHistorySessionToken
        if (historyRecordingJobs.containsKey(sessionToken)) return
        currentHistoryImmediateAttempted = true

        val eventIdSnapshot = currentHistoryEventId
        val remoteRegisteredSnapshot = currentHistoryRemoteRegistered
        val mediaMetadataSnapshot = player.currentMetadata?.takeIf { it.id == mediaId }

        val deferred =
            scope.async {
                withContext(Dispatchers.IO) {
                    val resolvedEventId =
                        eventIdSnapshot
                            ?: insertPlaybackHistoryEvent(
                                mediaId = mediaId,
                                playTimeMs = playedMs,
                                mediaMetadata = mediaMetadataSnapshot,
                            )
                    val remoteRegistered = remoteRegisteredSnapshot || registerRemotePlaybackHistory(mediaId)
                    ImmediateHistoryResult(
                        eventId = resolvedEventId,
                        remoteRegistered = remoteRegistered,
                    )
                }
            }

        historyRecordingJobs[sessionToken] = deferred
        scope.launch {
            val result =
                runCatching { deferred.await() }
                    .onFailure(::reportException)
                    .getOrNull()

            historyRecordingJobs.remove(sessionToken)

            if (result != null) {
                if (currentHistorySessionToken == sessionToken &&
                    !currentHistorySessionQueued &&
                    currentHistoryMediaId == mediaId
                ) {
                    currentHistoryEventId = result.eventId ?: currentHistoryEventId
                    currentHistoryRemoteRegistered = currentHistoryRemoteRegistered || result.remoteRegistered
                } else {
                    updatePendingHistoryFinalization(mediaId, sessionToken, result)
                }
            }

            syncHistoryThresholdJob()
        }
    }

    private suspend fun insertPlaybackHistoryEvent(
        mediaId: String,
        playTimeMs: Long,
        mediaMetadata: app.hush.music.models.MediaMetadata?,
    ): Long? =
        try {
            database.withTransaction {
                if (song(mediaId).first() == null && mediaMetadata != null) {
                    insert(mediaMetadata)
                }

                insert(
                    Event(
                        songId = mediaId,
                        timestamp = LocalDateTime.now(),
                        playTime = playTimeMs,
                    ),
                ).takeIf { it > 0L }
            }
        } catch (_: SQLException) {
            null
        } catch (throwable: Throwable) {
            reportException(throwable)
            null
        }

    private suspend fun registerRemotePlaybackHistory(mediaId: String): Boolean {
        if (database
                .song(mediaId)
                .first()
                ?.song
                ?.isLocal == true
        ) {
            return false
        }

        suspend fun registerTracking(playbackTrackingUrl: String): Boolean =
            YouTube
                .registerPlayback(
                    playlistId = null,
                    playbackTracking = playbackTrackingUrl,
                ).onFailure { throwable ->
                    if (throwable is CancellationException) {
                        throw throwable
                    }
                    Timber.tag("MusicService").w(
                        throwable,
                        "Failed to register remote playback history for %s",
                        mediaId,
                    )
                }.onSuccess {
                    YouTube.notifyHistorySynced()
                }.isSuccess

        remotePlaybackTrackingUrlCache[mediaId]?.let { cachedPlaybackTrackingUrl ->
            if (registerTracking(cachedPlaybackTrackingUrl)) {
                return true
            }
            remotePlaybackTrackingUrlCache.remove(mediaId, cachedPlaybackTrackingUrl)
        }

        val remotePlaybackTracking =
            retryWithoutPlaybackLoginContext {
                YTPlayerUtils.playerResponseForMetadata(mediaId)
            }.onFailure { throwable ->
                if (throwable is CancellationException) {
                    throw throwable
                }
                when (throwable) {
                    is YTPlayerUtils.InvalidPlaybackLoginContextException -> {
                        promptLoginRecovery(mediaId, throwable.targetUrl)
                    }

                    is YTPlayerUtils.LoginRequiredForPlaybackException -> {
                        Timber.tag("MusicService").w(
                            throwable,
                            "Playback confirmation is required before refreshing remote playback tracking for %s",
                            mediaId,
                        )
                    }

                    else -> {
                        Timber.tag("MusicService").w(
                            throwable,
                            "Failed to refresh remote playback tracking for %s",
                            mediaId,
                        )
                    }
                }
            }.getOrNull()
                ?.playbackTracking

        val refreshedPlaybackTrackingUrl = remotePlaybackTracking?.remotePlaybackTrackingUrl()
        if (refreshedPlaybackTrackingUrl != null) {
            remotePlaybackTrackingUrlCache[mediaId] = refreshedPlaybackTrackingUrl
            return registerTracking(refreshedPlaybackTrackingUrl)
        }

        return false
    }

    /**
     * Publishes the format of the audio Media3 has selected and is decoding.
     *
     * This is the player's last-resort description of a track: when [ServedFormatClaim] refuses the
     * stored row because nothing on this device could be serving what it claims, this row still says
     * what is playing. It is read from the player's own tracks rather than from any resolver, so it
     * needs no resolution to have happened and cannot describe an engine that did not run.
     */
    override fun onTracksChanged(tracks: Tracks) {
        super.onTracksChanged(tracks)
        val mediaId = player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
        val format = selectedAudioFormat(tracks)
        app.hush.music.spotiflac.SpotiFLACDiag.log(
            "tracks changed: id=$mediaId groups=${tracks.groups.size} " +
                "audio=${format != null} mime=${format?.sampleMimeType} codecs=${format?.codecs} " +
                "bitrate=${format?.bitrate}",
        )
        if (mediaId == null || format == null) {
            decodedAudioFormat.value = null
            return
        }
        decodedAudioFormat.value =
            mediaId to
                DecodedAudioRow.row(
                    mediaId = mediaId,
                    sampleMimeType = format.sampleMimeType,
                    containerMimeType = format.containerMimeType,
                    codecs = format.codecs,
                    bitrate = format.bitrate,
                    averageBitrate = format.averageBitrate,
                    sampleRate = format.sampleRate,
                )
    }

    /** The format of the selected audio track in [tracks], if any is selected. */
    private fun selectedAudioFormat(tracks: Tracks): Format? {
        tracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEach
            for (index in 0 until group.length) {
                if (group.isTrackSelected(index)) {
                    return group.getTrackFormat(index)
                }
            }
        }
        return null
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        super.onMediaItemTransition(mediaItem, reason)
        val reasonLabel = when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
            else -> "UNKNOWN($reason)"
        }
        android.util.Log.w(TAG, "onMediaItemTransition: id=${mediaItem?.mediaId?.take(30)}, reason=$reasonLabel, pos=${player.currentPosition}ms, dur=${mediaItem?.mediaMetadata?.durationMs}ms")

        if (sleepTimer?.pauseWhenSongEnd == true) {
            pauseFromSleepTimer()
            return
        }

        // A download still running for the track we just left is wasted work: it
        // would also keep the progress chip on screen for the wrong song.
        activeDownloadProgress.value?.mediaId
            ?.takeIf { it.isNotBlank() && it != mediaItem?.mediaId && ::spotiflacNativeRuntime.isInitialized }
            ?.let { staleId ->
                spotiflacNativeRuntime.cancelDownload(staleId)
                spotiflacNativeRuntime.clearDownloadProgress()
            }
        activePlaybackClientLabel.value = null
        activeDownloadProgress.value = null
        lastPublishedPlaybackClient = null
        // The decoded row belongs to the track being left; Media3 reports the new one as soon as it has
        // selected audio for it. Only dropped for a *different* track: the two callbacks can arrive in
        // either order for one item change, and clearing an already-correct row would leave the codec
        // line empty until Media3 reported tracks again.
        if (decodedAudioFormat.value?.first != mediaItem?.mediaId) {
            decodedAudioFormat.value = null
        }

        // A skip or an auto-advance abandons the resolution for the track we just left. Drop
        // it here rather than letting it finish: it would otherwise keep occupying the
        // SpotiFLAC runtime while the track now playing tries to resolve its own stream.
        // Queue replacement (PLAYLIST_CHANGED) is exempt - the items it brings are the ones
        // that are about to be wanted, and they are resolving as this runs.
        if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
            cancelAbandonedPlaybackResolutions(
                keepMediaIds = setOfNotNull(mediaItem?.mediaId?.takeIf { it.isNotBlank() }),
            )
        }

        mediaItem?.mediaId
            ?.takeIf { it.isNotBlank() && !it.isLocalMediaId() }
            ?.let(::startPlaybackUrlPrefetch)

        // Warm the next track through SpotiFLAC so it starts without a download
        // stall (skipped in low-data mode / when the user turned prefetching off).
        if (mediaItem?.mediaId != null && isSpotiFLACPrefetchEnabled()) {
            spotiflacPrefetchJob?.cancel()
            spotiflacPrefetchJob = scope.launch(Dispatchers.IO) { prefetchNextSpotiFLACTracks() }
        }

        beginHistorySession(mediaItem?.mediaId, forceNew = true)

        // Pre-load lyrics for upcoming songs — only scan a small window, not the full queue.
        val lyricsQueueWindow = playerQueueMetadataWindow(lookahead = 4)
        if (lyricsQueueWindow.isNotEmpty()) {
            lyricsPreloadManager?.onSongChanged(0, lyricsQueueWindow)
        }

        prefetchNextTrack()

        val joined = togetherSessionState.value as? app.hush.music.together.TogetherSessionState.Joined
        if (joined?.role is app.hush.music.together.TogetherRole.Guest &&
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
        ) {
            if (!joined.roomState.settings.allowGuestsToControlPlayback) {
                scope.launch(SilentHandler) { applyRemoteRoomState(joined.roomState, force = true) }
                return
            }
            val now = android.os.SystemClock.elapsedRealtime()
            val index = player.currentMediaItemIndex.coerceAtLeast(0)
            val isEcho =
                isTogetherApplyingRemote() ||
                    (now < togetherSuppressEchoUntilElapsedMs && togetherLastRemoteAppliedIndex == index)
            if (!isEcho) {
                val trackId = (mediaItem?.metadata ?: player.currentMetadata)?.id?.trim().orEmpty()
                requestTogetherControl(
                    if (trackId.isBlank()) {
                        app.hush.music.together.ControlAction.SeekToIndex(
                            index = index,
                            positionMs = player.currentPosition.coerceAtLeast(0L),
                        )
                    } else {
                        app.hush.music.together.ControlAction.SeekToTrack(
                            trackId = trackId,
                            positionMs = player.currentPosition.coerceAtLeast(0L),
                        )
                    },
                )
            }
        }

        val timelineEmpty = player.currentTimeline.isEmpty || player.mediaItemCount == 0 || player.currentMediaItem == null
        currentMediaMetadata.value = if (timelineEmpty) null else (mediaItem?.metadata ?: player.currentMetadata)
        wazeLikedMediaId = currentMediaMetadata.value?.id
        wazeLikedState = if (timelineEmpty) false else currentPlaybackSongLiked()
        publishWazePlaybackSnapshot(force = true)

        // currentSong is a database-backed StateFlow and may lag the Media3
        // transition by one emission. Refresh Waze's like state off the main
        // thread, then publish only if this track is still current.
        val transitionedMediaId = currentMediaMetadata.value?.id?.takeIf { it.isNotBlank() }
        if (transitionedMediaId != null) {
            scope.launch(Dispatchers.IO) {
                val liked = database.song(transitionedMediaId).first()?.song?.liked == true
                withContext(Dispatchers.Main.immediate) {
                    if (wazeLikedMediaId == transitionedMediaId) {
                        wazeLikedState = liked
                        publishWazePlaybackSnapshot(force = true)
                    }
                }
            }
        }

        mediaItem?.mediaId?.takeIf { it.isNotBlank() && !it.isLocalMediaId() }?.let { mediaId ->
            cachedPlaybackUrl(mediaId)?.playbackClientLabel?.let { label ->
                publishPlaybackClientLabel(mediaId, label)
            }
        }

        mediaItem?.let { item ->
            ensureNotificationArtworkUri(item)
            scope.launch(SilentHandler) {
                hydrateNotificationArtwork(item)
            }
        }

        widgetUpdater.update()

        scrobbleManager?.onSongStop()

        if (!timelineEmpty &&
            dataStore.get(AutoLoadMoreKey, true) &&
            reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
            player.repeatMode == REPEAT_MODE_OFF
        ) {
            // No redundant seeding update check.
        }

        // Auto-load more from queue if available
        if (!suppressAutoPlayback &&
            !timelineEmpty &&
            dataStore.get(AutoLoadMoreKey, true) &&
            reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
            player.mediaItemCount - player.currentMediaItemIndex <= 5 &&
            currentQueue.hasNextPage() &&
            player.repeatMode == REPEAT_MODE_OFF
        ) {
            val queue = currentQueue
            val queueGeneration = playQueueGeneration.get()
            scope.launch(SilentHandler) {
                val mediaItems =
                    loadNextQueuePageWithRetry(queue)
                        .filterExplicit(
                            dataStore.get(HideExplicitKey, false),
                        ).filterVideo(dataStore.get(HideVideoKey, false))
                if (
                    queueGeneration == playQueueGeneration.get() &&
                    currentQueue === queue &&
                    player.playbackState != STATE_IDLE
                ) {
                    // Some queues (e.g. YouTube continuation pages) repeat the current item
                    // as the first entry of the next page; drop it only when it actually
                    // duplicates the last item already queued, so growing local lists keep
                    // every real song.
                    val lastQueuedMediaId =
                        if (player.mediaItemCount > 0) {
                            player.getMediaItemAt(player.mediaItemCount - 1).mediaId
                        } else {
                            null
                        }
                    val itemsToAdd =
                        if (mediaItems.firstOrNull()?.mediaId == lastQueuedMediaId) {
                            mediaItems.drop(1)
                        } else {
                            mediaItems
                        }
                    player.addMediaItems(itemsToAdd)
                }
            }
        }

        if (!suppressAutoPlayback &&
            !timelineEmpty &&
            dataStore.get(AutoLoadMoreKey, true) &&
            reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
            player.repeatMode == REPEAT_MODE_OFF &&
            player.mediaItemCount - player.currentMediaItemIndex <= 3 &&
            !currentQueue.hasNextPage()
        ) {
            val queue = currentQueue
            val queueGeneration = playQueueGeneration.get()
            scope.launch(SilentHandler) {
                if (suppressAutoPlayback || player.mediaItemCount == 0) return@launch

                val currentMediaMetadata = player.currentMetadata ?: return@launch
                val currentMediaId = currentMediaMetadata.id.trim().ifBlank { return@launch }
                if (isCurrentPlaybackItemLocal(currentMediaMetadata)) return@launch
                val currentIndex = player.currentMediaItemIndex

                try {
                    val radioQueue = YouTubeQueue(WatchEndpoint(videoId = currentMediaId), followAutomixPreview = true)
                    val status = withContext(Dispatchers.IO) { radioQueue.getInitialStatus() }

                    if (
                        queueGeneration != playQueueGeneration.get() ||
                        currentQueue !== queue ||
                        player.currentMediaItemIndex != currentIndex ||
                        player.currentMetadata?.id != currentMediaId
                    ) {
                        return@launch
                    }

                    val queueIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
                    val newItems = status.items.filter { it.mediaId !in queueIds }

                    if (newItems.isNotEmpty()) {
                        player.addMediaItems(newItems)
                        newItems.forEach { autoAddedMediaIds.add(it.mediaId) }
                    }
                    currentQueue = radioQueue
                } catch (e: Exception) {
                    Timber.e(e, "Failed to inject YouTube replacement queue")
                }
            }
        }

        if (player.playWhenReady && player.playbackState == Player.STATE_READY) {
            scrobbleManager?.onSongStart(player.currentMetadata, duration = player.duration)
        }

        scope.launch {
            val shouldSave = withContext(Dispatchers.IO) { dataStore.get(PersistentQueueKey, true) }
            if (shouldSave) {
                saveQueueToDisk()
            }
        }
        if (!isCrossfading && activeCrossfadeTransition == null) {
            scheduleCrossfade()
        }
    }

    private fun ensureNotificationArtworkUri(mediaItem: MediaItem) {
        val index = player.currentMediaItemIndex
        if (index == C.INDEX_UNSET || index >= player.mediaItemCount) return
        val currentItem = player.getMediaItemAt(index)
        if (currentItem.mediaId != mediaItem.mediaId) return
        if (currentItem.mediaMetadata.artworkData != null) return

        val artworkUrl =
            currentItem.resolveNotificationArtworkUrl()
                ?: NotificationArtworkLoader.resolveArtworkUrl(currentItem)
                ?: return

        val existingUri = currentItem.mediaMetadata.artworkUri?.toString()?.trim().orEmpty()
        if (existingUri == artworkUrl) {
            refreshPlaybackNotification()
            return
        }

        val updatedItem =
            currentItem.buildUpon()
                .setMediaMetadata(
                    currentItem.mediaMetadata.buildUpon()
                        .setArtworkUri(artworkUrl.toUri())
                        .build(),
                ).build()
        player.replaceMediaItem(index, updatedItem)
        refreshPlaybackNotification()
    }

    private suspend fun hydrateNotificationArtwork(mediaItem: MediaItem) {
        val targetMediaId = mediaItem.mediaId
        val currentItem =
            withContext(Dispatchers.Main.immediate) {
                val index = player.currentMediaItemIndex
                if (index == C.INDEX_UNSET || index >= player.mediaItemCount) return@withContext null
                val item = player.getMediaItemAt(index)
                if (item.mediaId != targetMediaId) return@withContext null
                item
            } ?: return

        if (currentItem.mediaMetadata.artworkData != null) return

        val artworkUrl =
            currentItem.resolveNotificationArtworkUrl()
                ?: NotificationArtworkLoader.resolveArtworkUrl(currentItem)
                ?: return

        val bitmap =
            withContext(Dispatchers.IO) {
                NotificationArtworkLoader.loadBitmap(
                    url = artworkUrl,
                    maxSizePx = 1080,
                )
            } ?: return

        withContext(Dispatchers.Main.immediate) {
            val index = player.currentMediaItemIndex
            if (index == C.INDEX_UNSET || index >= player.mediaItemCount) return@withContext
            val latestItem = player.getMediaItemAt(index)
            if (latestItem.mediaId != targetMediaId) return@withContext
            if (latestItem.mediaMetadata.artworkData != null) return@withContext

            val updatedItem =
                NotificationArtworkLoader.mediaItemWithEmbeddedArtwork(
                    mediaItem = latestItem,
                    bitmap = bitmap,
                    artworkUrl = artworkUrl,
                )
            player.replaceMediaItem(index, updatedItem)
            refreshPlaybackNotification()
        }
    }

    private fun isCurrentPlaybackItemLocal(currentMediaMetadata: MediaMetadata): Boolean =
        currentSong.value?.song?.isLocal == true ||
            currentMediaMetadata.id.trim().isLocalMediaId() ||
            player.currentMediaItem
                ?.localConfiguration
                ?.uri
                ?.shouldBypassPlayerCache() == true

    override fun onPlaybackStateChanged(
        @Player.State playbackState: Int,
    ) {
        super.onPlaybackStateChanged(playbackState)

        updateHistoryTrackingPlaybackState()
        if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
            enqueueCurrentHistorySessionForFinalization()
            if (!isCrossfading || playbackState == Player.STATE_IDLE) {
                cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
            }
        } else if (playbackState == Player.STATE_READY) {
            scheduleCrossfade()
            // Do not let look-ahead URL resolution compete with the first track
            // during a cold launch. Once the current player is ready, prefetching
            // the next items is safe and improves subsequent transitions.
            if (player.isPlaying) {
                prefetchNextTrack()
            }
            player.currentMediaItem?.let { item ->
                if (item.mediaMetadata.artworkData == null) {
                    ensureNotificationArtworkUri(item)
                    scope.launch(SilentHandler) {
                        hydrateNotificationArtwork(item)
                    }
                }
            }
        }

        widgetUpdater.update()
        widgetUpdater.updateProgressTracking()
        publishWazePlaybackSnapshot(force = true)

        scope.launch {
            val shouldSave = withContext(Dispatchers.IO) { dataStore.get(PersistentQueueKey, true) }
            if (shouldSave) {
                saveQueueToDisk()
            }
        }
    }

    override fun onPlayWhenReadyChanged(
        playWhenReady: Boolean,
        reason: Int,
    ) {
        super.onPlayWhenReadyChanged(playWhenReady, reason)
        secondaryCrossfadePlayer?.let { secondaryPlayer ->
            if (isCrossfading && !crossfadeHandoffInProgress) {
                val isEndOfOutgoingItemPause =
                    !playWhenReady &&
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM &&
                        localPlayer.pauseAtEndOfMediaItems
                if (!isEndOfOutgoingItemPause) {
                    crossfadePlaybackRequested = playWhenReady
                }
                secondaryPlayer.playWhenReady = crossfadePlaybackRequested
                if (crossfadePlaybackRequested) {
                    secondaryPlayer.play()
                } else if (!isEndOfOutgoingItemPause) {
                    secondaryPlayer.pause()
                }
            }
        }
        if (playWhenReady && !isCrossfading) {
            scheduleCrossfade()
        } else if (!playWhenReady && !isCrossfading) {
            crossfadeTriggerJob?.cancel()
            crossfadeTriggerJob = null
            localPlayer.pauseAtEndOfMediaItems = false
            releaseSecondaryCrossfadePlayer()
        }
        publishWazePlaybackSnapshot(force = true)
    }

    override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
        super.onPlaybackParametersChanged(playbackParameters)
        secondaryCrossfadePlayer?.playbackParameters = playbackParameters
        publishWazePlaybackSnapshot(force = true)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        super.onIsPlayingChanged(isPlaying)
        secondaryCrossfadePlayer?.let { secondaryPlayer ->
            if (isCrossfading && !crossfadeHandoffInProgress) {
                if (isPlaying) {
                    secondaryPlayer.play()
                } else {
                    secondaryPlayer.pause()
                }
            }
        }
        if (isPlaying && !isCrossfading) {
            scheduleCrossfade()
            // Start look-ahead only after audio has actually become audible;
            // resolving several future tracks during cold startup competes with
            // the current track for network/BotGuard resources.
            prefetchNextTrack()
        }
        updateAudiblePlaybackRecovery()

        widgetUpdater.update()
        widgetUpdater.updateProgressTracking()
        publishWazePlaybackSnapshot(force = true)
        manageWazePositionUpdates(isPlaying)
    }

    private data class WazePlaybackSnapshot(
        val trackId: String,
        val title: String,
        val artist: String,
        val album: String,
        val artworkUrl: String?,
        val duration: Long,
        val position: Long,
        val bufferedPosition: Long,
        val isPlaying: Boolean,
        val playWhenReady: Boolean,
        val playerState: Int,
        val playbackSpeed: Float,
        val activeQueueItemId: Long,
        val sequenceNumber: Long,
        val timestampMs: Long,
        val queueBundles: List<Bundle> = emptyList(),
        val queueRevision: Long = -1L,
        val queueTitle: String = "",
        val liked: Boolean = false,
    )

    internal fun publishWazePlaybackSnapshot(
        force: Boolean = false,
    ) {
        if (!app.hush.music.BuildConfig.WAZE_SUPPORTED) return
        val now = SystemClock.elapsedRealtime()
        val shimPackages =
            cachedShimPackages
                ?.takeIf { now - cachedShimPackagesCheckedAt < SHIM_PACKAGE_CACHE_TTL_MS }
                ?: run {
                    // One definition of "what is a Bridge", shared with the auto-reconnect that
                    // announces Hush to them: two copies of this test would eventually disagree,
                    // and the failure mode of that is a Bridge being told about snapshots while
                    // never being told Hush had started.
                    val detected = WazeBridgeAutoReconnect.installedBridgePackages(this)
                    cachedShimPackages = detected
                    cachedShimPackagesCheckedAt = now
                    detected
                }
        if (shimPackages.isEmpty()) return
        if (!force && now - lastWazeMetadataUpdateTime < 200) return
        lastWazeMetadataUpdateTime = now

        val metadata = player.currentMetadata
        if (metadata == null) {
            scheduleDelayedWazeUpdate()
            return
        }
        val title = metadata.title?.toString()
        if (title.isNullOrBlank()) {
            scheduleDelayedWazeUpdate()
            return
        }

        // Build queue bundles for Waze.
        //
        // This runs on every metadata tick - up to five times a second while playing - so
        // rebuilding it each time meant allocating one Bundle plus two or three Strings
        // per queue item per tick (a 50-song queue: ~250 objects a second, a 500-song one:
        // ~2,500). That is pure GC pressure on the devices least able to absorb it, and it
        // bought nothing: the payload is identical until the queue itself changes. The
        // built list is cached and reused until a fingerprint of the queue moves.
        val queueTitle = "Hush Queue"
        val queueRevision = wazeQueueRevision.get()
        val itemCount = player.mediaItemCount
        val queueFingerprint = wazeQueueFingerprint(itemCount)
        val cachedQueue = cachedWazeQueueBundles
        val queueBundles =
            if (cachedQueue != null && cachedQueue.fingerprint == queueFingerprint) {
                cachedQueue.bundles
            } else {
                buildWazeQueueBundles(itemCount).also { built ->
                    // Retained only for queues a replay is likely to reuse. A very long
                    // queue is the case where holding a second copy of every item is
                    // itself the memory problem, and rebuilding it is no worse than the
                    // Binder marshalling that follows anyway.
                    cachedWazeQueueBundles =
                        WazeQueueBundles(queueFingerprint, built)
                            .takeIf { itemCount <= MAX_CACHED_WAZE_QUEUE_ITEMS }
                }
            }

        val snapshot = WazePlaybackSnapshot(
            trackId = player.currentMediaItem?.mediaId.orEmpty(),
            title = title,
            artist = metadata.artistsDisplayText,
            album = metadata.album?.title.orEmpty(),
            artworkUrl = player.currentMediaItem?.mediaMetadata?.artworkUri?.toString(),
            duration = player.duration.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L,
            position = player.currentPosition.coerceAtLeast(0L),
            bufferedPosition = player.bufferedPosition.coerceAtLeast(0L),
            isPlaying = player.isPlaying,
            playWhenReady = player.playWhenReady,
            playerState = player.playbackState,
            playbackSpeed = player.playbackParameters.speed,
            activeQueueItemId = player.currentMediaItemIndex.toLong(),
            sequenceNumber = wazeSnapshotSequence.incrementAndGet(),
            liked = if (wazeLikedMediaId == player.currentMediaItem?.mediaId) {
                wazeLikedState
            } else {
                currentSong.value?.song?.liked ?: false
            },
            timestampMs = now,
            queueBundles = queueBundles,
            queueRevision = queueRevision,
            queueTitle = queueTitle,
        )

        for (shimPackage in shimPackages) {
            try {
                val intent = Intent("app.hush.music.WAZE_METADATA_UPDATE").apply {
                    putExtra("title", snapshot.title)
                    putExtra("artist", snapshot.artist)
                    putExtra("album", snapshot.album)
                    putExtra("duration", snapshot.duration)
                    putExtra("position", snapshot.position)
                    putExtra("buffered_position", snapshot.bufferedPosition)
                    putExtra("is_playing", snapshot.isPlaying)
                    putExtra("play_when_ready", snapshot.playWhenReady)
                    putExtra("player_state", snapshot.playerState)
                    putExtra("playback_speed", snapshot.playbackSpeed)
                    putExtra("track_id", snapshot.trackId)
                    putExtra("queue_item_id", snapshot.activeQueueItemId)
                    putExtra("liked", snapshot.liked)
                    putExtra("sequence_number", snapshot.sequenceNumber)
                    putExtra("timestamp_elapsed_realtime", snapshot.timestampMs)
                    putExtra("state", resolveWazePlaybackState(snapshot))
                    putExtra("artwork_url", snapshot.artworkUrl)
                    putExtra("queue_revision", snapshot.queueRevision)
                    putExtra("queue_title", snapshot.queueTitle)
                    putParcelableArrayListExtra("queue_items", ArrayList(snapshot.queueBundles))
                    setPackage(shimPackage)
                }
                sendBroadcast(intent)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to send metadata to $shimPackage")
            }
        }
    }

    internal fun publishWazePausedTrackChange() {
        publishWazePlaybackSnapshot(force = true)
    }

    /** One built Waze queue, kept until a fingerprint says the queue moved. */
    private class WazeQueueBundles(
        val fingerprint: Long,
        val bundles: List<Bundle>,
    )

    @Volatile private var cachedWazeQueueBundles: WazeQueueBundles? = null

    /**
     * A cheap value that changes whenever the queue's contents do.
     *
     * Deliberately allocation-free: it is computed on every metadata tick, so building a
     * string of ids here would just move the cost being removed. `String.hashCode` is
     * cached inside the id itself, and the media metadata is only read when the
     * fingerprint actually moved.
     */
    private fun wazeQueueFingerprint(itemCount: Int): Long {
        var hash = itemCount.toLong() * 31 + wazeQueueRevision.get()
        for (i in 0 until itemCount) {
            hash = hash * 31 + player.getMediaItemAt(i).mediaId.hashCode()
        }
        return hash
    }

    private fun buildWazeQueueBundles(itemCount: Int): List<Bundle> {
        if (itemCount <= 0) return emptyList()
        val bundles = ArrayList<Bundle>(itemCount)
        for (i in 0 until itemCount) {
            val item = player.getMediaItemAt(i)
            val mediaMeta = item.mediaMetadata
            val bundle = Bundle()
            bundle.putString("track_id", item.mediaId.orEmpty())
            bundle.putString("title", mediaMeta.title?.toString() ?: "")
            bundle.putString("artist", mediaMeta.artist?.toString() ?: "")
            bundle.putString("album", mediaMeta.albumTitle?.toString() ?: "")
            mediaMeta.artworkUri?.let { bundle.putString("artwork_url", it.toString()) }
            bundle.putLong("queue_item_id", i.toLong())
            bundles.add(bundle)
        }
        return bundles
    }

    private fun resolveWazePlaybackState(snapshot: WazePlaybackSnapshot): Int = when {
        snapshot.playerState == Player.STATE_BUFFERING && snapshot.playWhenReady -> PlaybackState.STATE_BUFFERING
        snapshot.isPlaying -> PlaybackState.STATE_PLAYING
        snapshot.playerState == Player.STATE_READY && !snapshot.playWhenReady -> PlaybackState.STATE_PAUSED
        snapshot.playerState == Player.STATE_ENDED -> PlaybackState.STATE_STOPPED
        else -> PlaybackState.STATE_PAUSED
    }

    fun playWazeQueueItem(queueItemId: Long) {
        val index = queueItemId.toInt()
        if (index < 0 || index >= player.mediaItemCount) return
        suppressAutoPlayback = false
        player.seekTo(index, C.TIME_UNSET)
    }

    private suspend fun loadNextQueuePageWithRetry(
        queue: Queue,
        maxAttempts: Int = 3,
    ): List<MediaItem> = queuePageLoadMutex.withLock {
        var lastFailure: Throwable? = null
        repeat(maxAttempts.coerceAtLeast(1)) { attemptIndex ->
            try {
                val page = queue.nextPage()
                if (page.isNotEmpty() || !queue.hasNextPage()) return@withLock page
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                lastFailure = error
                Timber.tag(TAG).w(
                    error,
                    "Queue page load failed (attempt %d/%d)",
                    attemptIndex + 1,
                    maxAttempts,
                )
            }
            if (attemptIndex + 1 < maxAttempts) {
                delay(250L * (attemptIndex + 1))
            }
        }
        lastFailure?.let { throw it }
        emptyList()
    }

    private fun scheduleDelayedWazeUpdate() {
        scope.launch {
            delay(500)
            publishWazePlaybackSnapshot()
        }
    }

    private fun manageWazePositionUpdates(isPlaying: Boolean) {
        wazePositionJob?.cancel()
        wazePositionJob = null
        if (!isPlaying) return
        wazePositionJob = scope.launch {
            while (isActive) {
                publishWazePlaybackSnapshot()
                delay(1000)
            }
        }
    }

    private fun handleWazeCommand(intent: Intent) {
        if (!app.hush.music.BuildConfig.WAZE_SUPPORTED) return
        val command = intent.getStringExtra("command")
        android.util.Log.w(TAG, "handleWazeCommand: command=$command mediaItemCount=${player.mediaItemCount} isPlaying=${player.isPlaying}")
        if (command == "sync") {
            publishWazePlaybackSnapshot(force = true)
            return
        }

        if (player.mediaItemCount > 0) {
            // Execute immediately — the shim already debounces per-command,
            // and this server-side debounce caused pauses to be silently
            // cancelled when a subsequent play command arrived within the
            // debounce window (Waze state sync race).
            wazePauseDebounceJob?.cancel()
            wazePauseDebounceJob = null
            executeWazeCommand(intent)
        } else if (queueRestoreCompleted.value) {
            wazeColdStartRecovery(intent)
        } else {
            if (pendingWazeCommands.size < MAX_PENDING_WAZE_COMMANDS) {
                pendingWazeCommands.add(intent)
                Timber.tag(TAG).d("Deferred Waze command: ${intent.getStringExtra("command")} (queue size: ${pendingWazeCommands.size})")
            } else {
                Timber.tag(TAG).w("Dropping Waze command: ${intent.getStringExtra("command")} (queue full)")
            }
        }
    }

    fun routeWazeCommand(intent: Intent) {
        if (!app.hush.music.BuildConfig.WAZE_SUPPORTED) return
        handleWazeCommand(intent)
    }

    private fun executeWazeCommand(intent: Intent) {
        val command = intent.getStringExtra("command") ?: return
        when (command) {
            "play" -> {
                player.play()
                publishWazePlaybackSnapshot(force = true)
            }
            "pause" -> {
                android.util.Log.w(TAG, "executeWazeCommand: pausing player (wasPlaying=${player.isPlaying})")
                player.pause()
                android.util.Log.w(TAG, "executeWazeCommand: player.pause() called, isPlaying=${player.isPlaying}")
                publishWazePlaybackSnapshot(force = true)
            }
            "stop" -> {
                player.pause()
                publishWazePlaybackSnapshot(force = true)
            }
            "play_pause" -> {
                if (player.isPlaying) player.pause() else player.play()
                publishWazePlaybackSnapshot(force = true)
            }
            "next" -> {
                when (
                    TransportSkipPolicy.nextAction(
                        mediaItemCount = player.mediaItemCount,
                        hasNext = player.hasNextMediaItem(),
                        repeatEnabled = player.repeatMode != REPEAT_MODE_OFF,
                    )
                ) {
                    TransportSkipPolicy.Action.RECOVER_QUEUE -> recoverQueueIfEmpty()
                    TransportSkipPolicy.Action.EXTEND_QUEUE -> extendQueueForSkip()
                    TransportSkipPolicy.Action.SKIP -> {
                        player.seekToNext()
                        if (!player.playWhenReady) publishWazePausedTrackChange()
                    }
                }
            }
            "previous" -> {
                if (TransportSkipPolicy.previousAction(player.mediaItemCount) == TransportSkipPolicy.Action.SKIP) {
                    player.seekToPrevious()
                    if (!player.playWhenReady) publishWazePausedTrackChange()
                } else {
                    recoverQueueIfEmpty()
                }
            }
            "seek" -> {
                val pos = intent.getLongExtra("position", 0L)
                player.seekTo(pos)
                publishWazePlaybackSnapshot(force = true)
            }
            "skip_to_queue_item" -> {
                val queueItemId = intent.getLongExtra("queue_item_id", -1L)
                if (queueItemId >= 0L) {
                    playWazeQueueItem(queueItemId)
                    publishWazePlaybackSnapshot(force = true)
                }
            }
            "search" -> {
                val query = intent.getStringExtra("query") ?: return
                Timber.tag(TAG).d("Search query: $query")
            }
            "like" -> toggleLike()
            "download" -> toggleDownload()
            "shuffle" -> toggleShuffleMode()
            "repeat" -> toggleRepeatMode()
            else -> Timber.tag(TAG).w("Unknown Waze command: $command")
        }
    }

    /**
     * Brings the queue back when the player has no timeline at all.
     *
     * After a restart the timeline can legitimately be empty for a moment — and if
     * the restore failed it stays empty — while the UI still shows the last song. A
     * bare `seekToNext()` on an empty player is a silent no-op, which is exactly why
     * next/previous looked dead until a song was picked from a list. Recovery loads
     * the persisted queue, restores the position and starts playback, so the
     * transport buttons work from then on.
     */
    /**
     * Best-effort queue + position persist for lifecycle moments.
     *
     * The periodic save runs every 10-30 s and track transitions persist too, so a
     * close in between can lose the newest queue or the position reached since the
     * last write. Called when the app leaves the foreground and when the task is
     * removed, fire-and-forget behind a short ceiling so it can never hold up the
     * main thread. Honours the persistent-queue setting and never writes while a
     * restore is still hydrating (which would persist a partial window).
     */
    fun persistQueueNow(reason: String) {
        // ExoPlayer may only be touched on the thread that owns it, so read the
        // diagnostics here — every caller is a lifecycle callback on the main
        // thread — and pass the values to the background save. Reading them inside
        // the IO coroutine throws "Player is accessed on the wrong thread" and the
        // log line (and any code after it) never runs.
        val itemCount = player.mediaItemCount
        val position = player.currentPosition
        if (itemCount == 0) return
        if (!dataStore.get(PersistentQueueKey, true)) return
        ensureScopesActive()
        ioScope.launch(SilentHandler) {
            val saved =
                withTimeoutOrNull(PERSIST_NOW_TIMEOUT_MS) { saveQueueToDisk() } != null
            app.hush.music.spotiflac.SpotiFLACDiag.log(
                "queue persist ($reason): items=$itemCount position=$position saved=$saved",
            )
        }
    }

    /**
     * Grows the queue when a skip has nothing ahead of it.
     *
     * Honours the auto-load-more setting, so a queue the user deliberately wants to
     * end still ends — the explicit press only decides *when* to try, not whether it
     * is allowed.
     */
    fun extendQueueForSkip() {
        if (infiniteQueueLoading.value) return
        ensureScopesActive()
        scope.launch(SilentHandler) {
            if (!dataStore.get(AutoLoadMoreKey, true)) {
                app.hush.music.spotiflac.SpotiFLACDiag.log("skip at end of queue: auto-load-more is off")
                return@launch
            }
            withContext(Dispatchers.Main) { onInfiniteQueueEnabled() }
        }
    }

    fun recoverQueueIfEmpty() {
        if (player.mediaItemCount > 0) return
        ensureScopesActive()
        app.hush.music.spotiflac.SpotiFLACDiag.log(
            "queue recovery requested: player has no items (restoreCompleted=${queueRestoreCompleted.value})",
        )
        val intent =
            Intent(this, MusicService::class.java).apply {
                action = "app.hush.music.WAZE_COMMAND"
                putExtra("command", "play")
            }
        wazeColdStartRecovery(intent)
    }

    private fun wazeColdStartRecovery(intent: Intent) {
        pendingWazeCommands.add(intent)
        if (wazeColdStartRecoveryJob?.isActive == true) return

        wazeColdStartRecoveryJob = scope.launch(Dispatchers.IO) {
            try {
                val prefs = dataStore.data.first()
                val persistedQueue = readPersistentObject<PersistQueue>(PERSISTENT_QUEUE_FILE)
                val persistedPlayerState = readPersistentObject<PersistPlayerState>(PERSISTENT_PLAYER_STATE_FILE)
                var recovered = false
                var wantsPlayback = false
                withContext(Dispatchers.Main) {
                    val commandsToExecute = pendingWazeCommands.toList()
                    pendingWazeCommands.clear()
                    wantsPlayback =
                        commandsToExecute.any { command ->
                            TransportRecoveryPolicy.requestsPlayback(command.getStringExtra("command"))
                        }
                    if (persistedQueue != null) {
                        restorePersistentQueue(persistedQueue, prefs)
                        persistedPlayerState?.let {
                            restorePersistentPlayerState(it, restoredQueue = true)
                        }
                    }
                    // A file that decoded but materialised nothing (truncated, or written by a
                    // build whose queue model has since changed) leaves the same dead timeline
                    // as no file at all, so the player decides - not the read.
                    if (player.mediaItemCount > 0) {
                        for (command in commandsToExecute) {
                            executeWazeCommand(command)
                        }
                        recovered = true
                    }
                }
                // Nothing to drive the transport with. Only a command that was asking for music
                // may rebuild one: an empty-player "pause" or "stop" must stay a no-op rather
                // than starting music the user did not ask for.
                if (!recovered && wantsPlayback) recoverQueueFromHistory()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to restore queue for Waze command")
            } finally {
                wazeColdStartRecoveryJob = null
            }
        }
    }

    /**
     * Rebuilds a play queue from what the device still knows when nothing was persisted.
     *
     * A fresh install, a cleared data directory, or a car head unit that was reinstalled all
     * leave no persisted queue behind. Every entry point that reaches here exists so that a
     * transport press is never a dead button - but the only recovery was "read the persisted
     * queue and replay it", so in exactly those cases next/previous/play did nothing at all and
     * said nothing about it.
     *
     * Recently played is the only context still on the device, so that is what is rebuilt - and
     * as a real queue, not a single track, so next/previous and the queue screen work afterwards
     * instead of only the first song starting. The DAO hands history back newest-first, which is
     * also the order the user left off in: the track they stopped on becomes item 0 and next
     * walks back through the session.
     *
     * @return true when playback was started.
     */
    private suspend fun recoverQueueFromHistory(): Boolean {
        // Blocked artists are filtered inside, for the same reason a restored queue filters them:
        // this is a timeline the user did not build by hand.
        val items =
            runCatching { withContext(Dispatchers.IO) { database.historyRecoveryItems() } }
                .onFailure { Timber.tag(TAG).w(it, "cold-start recovery: history unavailable") }
                .getOrDefault(emptyList())
        if (items.isEmpty()) {
            app.hush.music.spotiflac.SpotiFLACDiag.log(
                "cold-start recovery: no persisted queue and nothing in history to play",
            )
            return false
        }
        app.hush.music.spotiflac.SpotiFLACDiag.log(
            "cold-start recovery: no persisted queue; playing ${items.size} recently played item(s) from ${items.first().mediaId}",
        )
        withContext(Dispatchers.Main) {
            playQueue(
                ListQueue(
                    title = null,
                    items = items,
                    startIndex = 0,
                    position = 0L,
                ),
                playWhenReady = true,
            )
        }
        return true
    }

    private fun onMediaItemTransitionInternal() {
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
            scrobbleManager?.onSongStop()
        }

        // Auto-start recommendations when playback ends (handoff finite queues into infinite)
        if (!suppressAutoPlayback &&
            player.playbackState == Player.STATE_ENDED &&
            dataStore.get(AutoLoadMoreKey, true) &&
            player.repeatMode == REPEAT_MODE_OFF &&
            player.currentMediaItem != null
        ) {
            onInfiniteQueueEnabled()
        }

        scope.launch {
            try {
                submitListenBrainzPlayingNow()
            } catch (e: Exception) {
                Timber.tag("MusicService").v(e, "ListenBrainz playing_now submit failed")
            }
        }
        publishWazePlaybackSnapshot(force = true)
        manageWazePositionUpdates(player.isPlaying)
    }

    override fun onEvents(
        player: Player,
        events: Player.Events,
    ) {
        val currentMediaId = player.currentMediaItem?.mediaId
        if (currentMediaId == null && currentHistoryMediaId != null) {
            beginHistorySession(null, forceNew = true)
        } else if (currentHistoryMediaId == null && currentMediaId != null) {
            beginHistorySession(currentMediaId)
        }
        if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
            playbackStreamRecoveryTracker.onMediaItemChanged(currentMediaId)
        }
        if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
            val newRevision = wazeQueueRevision.incrementAndGet()
            getSharedPreferences(WAZE_PREFS, MODE_PRIVATE)
                .edit()
                .putLong(WAZE_PREFS_QUEUE_REVISION, newRevision)
                .apply()
        }
        if (
            (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_READY) ||
            (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && player.isPlaying)
        ) {
            playbackStreamRecoveryTracker.onPlaybackRecovered(currentMediaId)
            if (consecutivePlaybackErr > 0) {
                Timber.tag(TAG).d("Playback recovered — resetting consecutivePlaybackErr (was %d)", consecutivePlaybackErr)
                consecutivePlaybackErr = 0
            }
            if (player.isPlaying && urlRefreshJob?.isActive != true) {
                scheduleUrlRefresh()
            }
            ensureAudiblePlaybackVolume("player_event")
        }
        // Stop proactive URL refresh when playback pauses or stops.
        if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && !player.isPlaying) {
            cancelUrlRefresh()
        }
        if (events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED,
            )
        ) {
            updateAudiblePlaybackRecovery()
        }
        if (events.contains(Player.EVENT_MEDIA_METADATA_CHANGED)) {
            currentMediaMetadata.value = player.currentMetadata
            publishWazePlaybackSnapshot(force = true)
        }
        if (events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED,
            )
        ) {
            updateHistoryTrackingPlaybackState()
        }
        val joined = togetherSessionState.value as? app.hush.music.together.TogetherSessionState.Joined
        if (joined?.role is app.hush.music.together.TogetherRole.Guest &&
            events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)
        ) {
            if (!joined.roomState.settings.allowGuestsToControlPlayback) {
                scope.launch(SilentHandler) { applyRemoteRoomState(joined.roomState, force = true) }
            } else {
                val now = android.os.SystemClock.elapsedRealtime()
                val playWhenReady = this.player.playWhenReady
                val isEcho =
                    isTogetherApplyingRemote() ||
                        (
                            now < togetherSuppressEchoUntilElapsedMs &&
                                togetherLastRemoteAppliedPlayWhenReady != null &&
                                togetherLastRemoteAppliedPlayWhenReady == playWhenReady
                        )
                if (!isEcho) {
                    val action =
                        if (playWhenReady) {
                            app.hush.music.together.ControlAction.Play
                        } else {
                            app.hush.music.together.ControlAction.Pause
                        }
                    requestTogetherControl(action)
                }
            }
        }
        if (events.contains(Player.EVENT_DEVICE_VOLUME_CHANGED)) {
            handleDeviceMuteStateChanged()
        }
        if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) && isDeviceMutedNow() && this.player.playWhenReady) {
            handleDeviceMuteStateChanged(playbackRequestedWhileMuted = true)
        }
        if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) &&
            (this.player.playbackState == Player.STATE_IDLE || this.player.playbackState == Player.STATE_ENDED)
        ) {
            wasAutoPausedByDeviceMute = false
            unregisterMuteRecoveryObserver()
            updateAudiblePlaybackRecovery()
        }
        if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) &&
            isDeviceMutedNow() &&
            this.player.playWhenReady
        ) {
            handleDeviceMuteStateChanged(playbackRequestedWhileMuted = true)
        }
        if (events.contains(Player.EVENT_AUDIO_SESSION_ID)) {
            rebindAudioEffectSession(this.localPlayer.audioSessionId)
        }
        if (events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
            )
        ) {
            val keepAudioEffectSessionOpen = shouldKeepAudioEffectSessionOpen()
            if (player.playWhenReady && keepAudioEffectSessionOpen) {
                ensureAudioFocusForActivePlayback()
            }
            if (keepAudioEffectSessionOpen) {
                openAudioEffectSession()
            } else {
                closeAudioEffectSession()
            }
            updateWakeLock()
            if (hasResumablePlaybackNotification()) {
                cancelIdleStop()
                promoteToStartedService()
                ensureStartedAsForeground()
            } else {
                scheduleStopIfIdle()
            }
        }

        if (events.containsAny(EVENT_TIMELINE_CHANGED, EVENT_POSITION_DISCONTINUITY)) {
            currentMediaMetadata.value = player.currentMetadata
            scope.launch {
                try {
                    submitListenBrainzPlayingNow()
                } catch (e: Exception) {
                    Timber.tag("MusicService").v(e, "ListenBrainz playing_now submit failed on transition")
                }
            }
            if (events.contains(EVENT_TIMELINE_CHANGED)) {
                publishWazePlaybackSnapshot(force = true)
            }
        }
        if (events.contains(EVENT_TIMELINE_CHANGED) && !isCrossfading) {
            scheduleCrossfade()
        }

        if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) {
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                currentMediaMetadata.value = player.currentMetadata
            }
            val currentPosition = player.currentPosition
            scope.launch {
                try {
                    submitListenBrainzPlayingNow(positionMs = currentPosition)
                } catch (e: Exception) {
                    Timber.tag("MusicService").v(e, "ListenBrainz playing_now submit failed for isPlaying/mediaTransition")
                }
            }
        }

        if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED)) {
            // Scrobble: Track play/pause state
            scrobbleManager?.onPlayerStateChanged(player.isPlaying, player.currentMetadata, duration = player.duration)
        }

        // Persist queue on play/pause so a force-stop right after pausing still restores the correct position
        if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) && player.mediaItemCount > 0) {
            scope.launch(SilentHandler) {
                if (withContext(Dispatchers.IO) { dataStore.get(PersistentQueueKey, true) }) {
                    saveQueueToDisk()
                }
            }
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        super.onPositionDiscontinuity(oldPosition, newPosition, reason)
        val isSeekDiscontinuity =
            reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
        if (isSeekDiscontinuity) {
            if (!crossfadeHandoffInProgress) {
                cancelCrossfade(resetVolume = true, resetPauseAtEnd = true)
            }
        }
        if (!isCrossfading && !crossfadeHandoffInProgress) {
            scheduleCrossfade()
        }
        publishWazePlaybackSnapshot(force = true)
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        updateNotification()
        val joined = togetherSessionState.value as? app.hush.music.together.TogetherSessionState.Joined
        if (joined?.role is app.hush.music.together.TogetherRole.Guest) {
            if (!isTogetherApplyingRemote()) {
                if (!joined.roomState.settings.allowGuestsToControlPlayback) {
                    scope.launch(SilentHandler) { applyRemoteRoomState(joined.roomState, force = true) }
                    return
                }
                requestTogetherControl(
                    app.hush.music.together.ControlAction.SetShuffleEnabled(
                        shuffleEnabled = shuffleModeEnabled,
                    ),
                )
            }
            return
        }
        if (shuffleModeEnabled) {
            applyCurrentFirstShuffleOrder()
        }

        // Save state when shuffle mode changes - must be on Main thread to access player
        scope.launch {
            if (dataStore.get(PersistentQueueKey, true)) {
                saveQueueToDisk()
            }
        }
        if (!isCrossfading) {
            scheduleCrossfade()
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        updateNotification()
        val joined = togetherSessionState.value as? app.hush.music.together.TogetherSessionState.Joined
        if (joined?.role is app.hush.music.together.TogetherRole.Guest) {
            if (!isTogetherApplyingRemote()) {
                if (!joined.roomState.settings.allowGuestsToControlPlayback) {
                    scope.launch(SilentHandler) { applyRemoteRoomState(joined.roomState, force = true) }
                    return
                }
                requestTogetherControl(
                    app.hush.music.together.ControlAction.SetRepeatMode(
                        repeatMode = repeatMode,
                    ),
                )
            }
            return
        }
        scope.launch {
            dataStore.edit { settings ->
                settings[RepeatModeKey] = repeatMode
            }
        }

        // Save state when repeat mode changes - must be on Main thread to access player
        scope.launch {
            if (dataStore.get(PersistentQueueKey, true)) {
                saveQueueToDisk()
            }
        }
        if (!isCrossfading) {
            scheduleCrossfade()
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        // The OPPO/ColorOS battery killer cancels the service coroutine scope while the
        // service stays alive. Every recovery path below launches on `scope`/`ioScope`;
        // if the scope is dead those launches silently no-op and the player sits in
        // ERROR forever (the mid-song skip). Resurrect the scope first.
        ensureScopesActive()
        android.util.Log.w(TAG, "onPlayerError: code=${error.errorCode}, msg=${error.message}, cause=${error.cause?.message}")
        super.onPlayerError(error)
        publishWazePlaybackSnapshot(force = true)

        val currentMediaId = player.currentMediaItem?.mediaId ?: return
        val isLocalMedia = currentMediaId.isLocalMediaId()

        val isFullyCachedMedia =
            runCatching {
                val cachedInDownload =
                    downloadCache.getContentMetadata(currentMediaId).get(ContentMetadata.KEY_CONTENT_LENGTH, -1L) > 0L ||
                        downloadCache.getCachedSpans(currentMediaId).isNotEmpty()
                val cachedInPlayer = playerCache.getContentMetadata(currentMediaId).get(ContentMetadata.KEY_CONTENT_LENGTH, -1L) > 0L
                cachedInDownload || cachedInPlayer
            }.getOrDefault(false)

        val hasAnyCachedData =
            isFullyCachedMedia ||
                runCatching {
                    downloadCache.getCachedSpans(currentMediaId).isNotEmpty() ||
                        playerCache.getCachedSpans(currentMediaId).isNotEmpty()
                }.getOrDefault(false)

        val isConnectionError =
            (error.cause?.cause is PlaybackException) &&
                (error.cause?.cause as PlaybackException).errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED

        // Detect any IO-level error (connection reset, socket timeout, etc.)
        // which commonly occurs after OPPO/OnePlus battery optimizer freezes/unfreezes the process.
        val isTransientIoError =
            !isLocalMedia && (
                error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
            )

        if (!isLocalMedia && !isFullyCachedMedia && (!isNetworkConnected.value || isConnectionError)) {
            waitOnNetworkError()
            return
        }

        // A "no enabled source can serve this track" failure arrives here as a generic source
        // error, and media3 reports it with an IO error code, so without this check it looked
        // transient: the player re-ran the whole provider sweep (up to the fallback timeout)
        // a few times per track and surfaced the same message each round. Nothing about a
        // setting changes by retrying, so the retry budget is left for real IO failures.
        val noPlayableSource = error.findNoPlayableSourceException()
        if (noPlayableSource != null) {
            android.util.Log.w(
                TAG,
                "onPlayerError: no enabled source for $currentMediaId - not retrying: ${noPlayableSource.message}",
            )
            Timber.tag(TAG).w(
                "No enabled source for %s; skipping the transient-IO retry path",
                currentMediaId,
            )
            // A track that failed only because a SpotiFLAC source is waiting for a
            // Cloudflare grant is not a broken track. Skipping it is what made one
            // unverified source look like "songs randomly skipping" (and like
            // "previous jumps forward": the previous track failed, so the queue
            // advanced again). Pause first - pausing is reversible, skipping is not -
            // then decide. If sources are genuinely waiting, park the position and ask
            // for the verification again (ignoring the failure cooldown, because
            // something is visibly waiting for it). Otherwise the user's own
            // auto-skip preference applies as before.
            stopOnError()
            scope.launch(Dispatchers.IO) {
                // Restore-and-renew first: a lapsed record is renewable without a challenge,
                // and only what survives that is worth parking the track for.
                val blocked = runCatching {
                    spotiflacNativeRuntime.sourcesNeedingUserVerification()
                }.getOrDefault(emptyList())
                // A gateway block looked exactly like "this track cannot be played": no source could
                // answer, the sweep came up empty, and the queue moved on - so a queue full of refused
                // songs read as a queue full of dead ones. Parked instead, and with no verification
                // asked for: while the gateway refuses this connection the challenge endpoint answers
                // the same way, so there is nothing worth asking until the route moves - which the route
                // watch notices and replays this for.
                val gatewayBlock =
                    app.hush.music.spotiflac.SpotiFLACSessionRenewer.relayBlock(this@MusicService)
                withContext(Dispatchers.Main) {
                    if (player.currentMediaItem?.mediaId != currentMediaId) return@withContext
                    if (gatewayBlock != null) {
                        holdForSourceVerification(currentMediaId)
                        app.hush.music.spotiflac.SpotiFLACDiag.log(
                            "held mediaId=$currentMediaId - the gateway is refusing this connection " +
                                "(" +
                                app.hush.music.spotiflac.SpotiFLACSessionRenewer
                                    .formatBlockRemaining(gatewayBlock.remainingMs) +
                                " left)",
                        )
                    } else if (blocked.isNotEmpty()) {
                        android.util.Log.w(
                            TAG,
                            "onPlayerError: $currentMediaId held — sources awaiting verification: " +
                                blocked.joinToString(","),
                        )
                        holdForSourceVerification(currentMediaId)
                        app.hush.music.spotiflac.SpotiFLAutoVerifier.enqueue(
                            blocked,
                            "held",
                            force = true,
                        )
                    } else {
                        clearSourceVerificationHold()
                        if (dataStore.get(AutoSkipNextOnErrorKey, false)) skipOnError()
                    }
                }
            }
            return
        }

        // YouTube asking this listener to confirm playback is the other failure no retry can
        // answer, and the only one the listener has to act on themselves. Media3 wraps it with
        // ERROR_CODE_IO_UNSPECIFIED, so it entered the transient-IO recovery below: three rounds
        // of clearing caches and re-resolving with a rotated stream client, each round re-running
        // the whole client sweep and logging the same demand again, and finally the configured
        // auto-skip - which replaced the panel that names the one action that works with a silent
        // skip to the next track. Leave it settled so the player shows it.
        val confirmationRequired = error.playbackConfirmationRequiredCause()
        if (confirmationRequired != null) {
            android.util.Log.w(
                TAG,
                "onPlayerError: playback confirmation required for $currentMediaId - not retrying: " +
                    confirmationRequired.message,
            )
            stopOnError()
            return
        }

        // Failing on bytes this device already holds: a malformed or unsupported container, a
        // decode failure, or an IO error inside the file. Corruption does not have to shorten a
        // file - bytes damaged in place leave the size and the container signature intact, so an
        // integrity check passes and the failure only appears once a decoder reaches the damaged
        // frame.
        val unreadableStoredBytes =
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND

        // A download is served straight off disk and outranks every cache, so a damaged one fails
        // identically on every attempt: the retry re-prepares the very same bytes. Drop it - the
        // record, the file, and any cached copy of the same song - and re-resolve, which is what
        // removing the download by hand would have done. Asking the user to find a broken download
        // in a list in order to play a track the app cannot play is the wrong way round.
        val downloadedEntry =
            runCatching { downloadedFileStore.get(currentMediaId) }.getOrNull()
        if (downloadedEntry != null && !isLocalMedia && unreadableStoredBytes &&
            playbackStreamRecoveryTracker.registerRetryAttempt(currentMediaId)
        ) {
            val resumeIndex = player.currentMediaItemIndex
            val resumePosition = player.currentPosition.coerceAtLeast(0L)
            android.util.Log.w(
                TAG,
                "onPlayerError: downloaded file unreadable for $currentMediaId " +
                    "(code=${error.errorCode}) - discarding the download and re-resolving",
            )
            app.hush.music.spotiflac.SpotiFLACDiag.log(
                "discarding unreadable download mediaId=$currentMediaId " +
                    "file=${downloadedEntry.fileName} bytes=${downloadedEntry.bytes} " +
                    "code=${error.errorCode} (retrying every source)",
            )
            scope.launch(Dispatchers.IO) {
                // Removes the file, its record and the cached copy of the same song, so the next
                // attempt fetches it again from a source that works.
                runCatching { downloadUtil.removeDownload(currentMediaId) }
                withContext(Dispatchers.Main) {
                    if (player.currentMediaItem?.mediaId != currentMediaId) return@withContext
                    player.seekTo(resumeIndex, resumePosition)
                    player.prepare()
                }
            }
            return
        }

        // A track whose bytes came from our own SpotiFLAC playback file needs its own
        // recovery, because every path below is written for bytes that live in a Media3
        // cache. A playback file is served straight off disk, so purging those caches
        // changes nothing and the retry re-prepares the very same file - which is why a
        // truncated or non-audio copy failed on every attempt and surfaced as a bare
        // source error, with no attempt to fetch it again. Discard the copy and
        // re-prepare: resolution then walks every enabled source, and only if all of
        // them fail does the track end in an error.
        val spotiFileEntry =
            runCatching { spotiflacNativeRuntime.cachedPlaybackEntryForMediaId(currentMediaId) }
                .getOrNull()
        val servedFromSpotiFile =
            spotiFileEntry != null &&
                !isLocalMedia &&
                // A user download outranks the playback file, so when one exists the
                // failure belongs to that file and is not ours to discard.
                runCatching { downloadedFileStore.fileFor(currentMediaId) }.getOrNull() == null
        if (servedFromSpotiFile) {
            // DECODING_FAILED is included because corruption does not have to shorten a
            // file: bytes damaged in place leave the size and the container signature
            // intact, so the integrity check passes and the failure only appears once a
            // decoder reaches the damaged frame. Unlike an unsupported-format code, this
            // is worth another fetch. The retry budget still bounds it, so a codec
            // limitation cannot turn into an endless re-download.
            val unreadableBytes =
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
            if (unreadableBytes && playbackStreamRecoveryTracker.registerRetryAttempt(currentMediaId)) {
                val resumeIndex = player.currentMediaItemIndex
                val resumePosition = player.currentPosition.coerceAtLeast(0L)
                android.util.Log.w(
                    TAG,
                    "onPlayerError: SpotiFLAC playback file unreadable for $currentMediaId " +
                        "(code=${error.errorCode}) - discarding and re-resolving through all sources",
                )
                scope.launch(Dispatchers.IO) {
                    val freed =
                        runCatching { spotiflacNativeRuntime.discardPlaybackFile(currentMediaId) }
                            .getOrDefault(0L)
                    // The miss memo would otherwise answer "remembered miss" and skip the
                    // sweep the user is waiting for.
                    clearSpotiFLACMiss(currentMediaId)
                    app.hush.music.spotiflac.SpotiFLACDiag.log(
                        "playback file discarded mediaId=$currentMediaId bytes=$freed " +
                            "code=${error.errorCode} (retrying every source)",
                    )
                    withContext(Dispatchers.Main) {
                        if (player.currentMediaItem?.mediaId != currentMediaId) return@withContext
                        player.seekTo(resumeIndex, resumePosition)
                        player.prepare()
                    }
                }
                return
            }
        }

        // For transient IO errors on streaming media, always retry with a fresh URL.
        // This is the most common cause of mid-playback skips: YouTube stream URLs
        // expire after ~2 minutes and ExoPlayer gets HTTP 403/410.
        android.util.Log.w(TAG, "onPlayerError: isTransientIoError=$isTransientIoError, local=$isLocalMedia, fullyCached=$isFullyCachedMedia, code=${error.errorCode}")
        if (isTransientIoError && playbackStreamRecoveryTracker.registerRetryAttempt(currentMediaId)) {
            val retryNumber = playbackStreamRecoveryTracker.retryCountFor(currentMediaId)
            android.util.Log.w(TAG, "Transient IO error (code=${error.errorCode}) for $currentMediaId — retrying with fresh URL (attempt $retryNumber)")
            Timber.tag(TAG).w(
                "Transient IO error (code=%d) for %s — retrying with fresh URL (attempt %d)",
                error.errorCode,
                currentMediaId,
                retryNumber,
            )
            val shouldResetAuth = retryNumber >= 2
            // Rotate the stream client on retry: YouTube's CDN escalates bot detection
            // against the client that served the revoked URL, so re-resolving with the
            // same client returns another URL that dies in seconds. Try a different
            // client on each retry so the request genuinely differs.
            val rotatedClient =
                when (retryNumber) {
                    2 -> PlayerStreamClient.ANDROID_VR
                    3 -> PlayerStreamClient.TVHTML5
                    4 -> PlayerStreamClient.IOS
                    else -> activeStreamClient
                }
            android.util.Log.w(TAG, "Transient IO retry $retryNumber for $currentMediaId using client $rotatedClient")
            scope.launch {
                // Clear all cached URLs so the next resolve gets a fresh one
                playbackUrlCache.remove(currentMediaId)
                extractorPlaybackUrlCache.remove(currentMediaId)
                YTPlayerUtils.invalidateCachedStreamUrls(currentMediaId)
                // Stale partial spans from the expired URL poison the re-prepare because
                // the player cache is keyed by mediaId. Purge them so the fresh URL is
                // used from position 0 of the re-resolved stream.
                runCatching { playerCache.removeResource(currentMediaId) }
                // If the same client context keeps returning 403, drop the stale logged-in
                // playback context (keep the account cookie) so the next resolve changes
                // request state instead of replaying the exact same rejected session.
                if (shouldResetAuth) {
                    withContext(Dispatchers.IO) {
                        runCatching { YTPlayerUtils.recoverFromBadStreamPlayerResponse(currentMediaId) }
                            .onFailure {
                                Timber.tag(TAG).w(
                                    it,
                                    "Failed to reset playback auth context for %s",
                                    currentMediaId,
                                )
                            }
                    }
                }
                // Wait briefly for network to stabilize, then re-resolve on IO thread
                delay(500L)
                withContext(Dispatchers.IO) {
                    runCatching {
                        resolveAndCachePlaybackUrl(currentMediaId, preferredClientOverride = rotatedClient)
                    }
                }
                withContext(Dispatchers.Main) {
                    if (player.currentMediaItem?.mediaId == currentMediaId) {
                        player.prepare()
                    }
                }
            }
            return
        }

        if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) {
            scope.launch(Dispatchers.IO) {
                runCatching { downloadCache.removeResource(currentMediaId) }
                runCatching { playerCache.removeResource(currentMediaId) }
            }
        }

        val retryableStreamFailure = findRetryableStreamFailure(error)
        if (retryableStreamFailure != null) {
            if (retryPlaybackAfterStreamFailure(currentMediaId, isFullyCachedMedia, retryableStreamFailure)) {
                return
            }
        }

        if (!isLocalMedia && isCacheCorruptionError(error, hasAnyCachedData)) {
            // Snapshot on the Main thread before dispatching; these can change.
            val mediaItemIndex = player.currentMediaItemIndex
            val resumePosition = player.currentPosition.coerceAtLeast(0L)

            Timber.tag("MusicService").w(
                "Cache corruption / truncated stream for %s (fullyCached=%b); purging caches then retrying",
                currentMediaId,
                isFullyCachedMedia,
            )

            playbackUrlCache.remove(currentMediaId)
            extractorPlaybackUrlCache.remove(currentMediaId)
            YTPlayerUtils.invalidateCachedStreamUrls(currentMediaId)

            scope.launch(Dispatchers.IO) {
                // Always purge the streaming/player cache.
                runCatching { playerCache.removeResource(currentMediaId) }
                // Keep a complete offline download in place; deleting a user's saved download
                // to recover from a read error is surprising. Only purge partial entries.
                if (!isFullyCachedMedia) {
                    runCatching { downloadCache.removeResource(currentMediaId) }
                } else {
                    Timber.tag("MusicService").w(
                        "Keeping offline download for %s; corruption may require manual re-download",
                        currentMediaId,
                    )
                }

                // Re-prepare ONLY after the purge completes, back on the Main thread, so the
                // fresh prepare cannot re-read the spans we just deleted.
                withContext(Dispatchers.Main) {
                    if (playbackStreamRecoveryTracker.registerRetryAttempt(currentMediaId)) {
                        player.seekTo(mediaItemIndex, resumePosition)
                        player.prepare()
                    } else {
                        // Retry budget for this item is spent; fall back to configured behavior.
                        if (dataStore.get(AutoSkipNextOnErrorKey, false)) skipOnError() else stopOnError()
                    }
                }
            }
            return
        }

        if (!isLocalMedia && !isFullyCachedMedia && YTPlayerUtils.isBotDetectionException(error)) {
            playbackUrlCache.remove(currentMediaId)
            extractorPlaybackUrlCache.remove(currentMediaId)
            YTPlayerUtils.invalidateCachedStreamUrls(currentMediaId)
            YTPlayerUtils.clearPlaybackAuthCaches()
            if (playbackStreamRecoveryTracker.registerRetryAttempt(currentMediaId)) {
                Timber.tag("MusicService").i("Retrying playback for %s after bot-detection source error", currentMediaId)
                player.prepare()
                return
            }
        }

        if (!isLocalMedia && !isFullyCachedMedia && YTPlayerUtils.isBadStreamPlayerResponseException(error)) {
            playbackUrlCache.remove(currentMediaId)
            extractorPlaybackUrlCache.remove(currentMediaId)
            YTPlayerUtils.invalidateCachedStreamUrls(currentMediaId)
            if (playbackStreamRecoveryTracker.registerRetryAttempt(currentMediaId)) {
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        YTPlayerUtils.recoverFromBadStreamPlayerResponse(currentMediaId)
                    }.onFailure {
                        Timber.tag("MusicService").w(
                            it,
                            "Failed to refresh stream session for %s after all stream clients failed",
                            currentMediaId,
                        )
                        reportException(it)
                    }
                    withContext(Dispatchers.Main) {
                        if (player.currentMediaItem?.mediaId == currentMediaId) {
                            Timber.tag("MusicService").i(
                                "Retrying playback for %s after refreshing stream session",
                                currentMediaId,
                            )
                            player.prepare()
                        }
                    }
                }
                return
            }
        }

        if (!isLocalMedia && !isFullyCachedMedia && isRetryableRemoteParserFailure(error)) {
            playbackUrlCache.remove(currentMediaId)
            extractorPlaybackUrlCache.remove(currentMediaId)
            YTPlayerUtils.invalidateCachedStreamUrls(currentMediaId)
            if (playbackStreamRecoveryTracker.registerRetryAttempt(currentMediaId)) {
                Timber.tag("MusicService").i(
                    "Retrying playback for %s after parser source error %d",
                    currentMediaId,
                    error.errorCode,
                )
                player.prepare()
                return
            }
        }

        // Catch HTTP 403/404/410/416 errors that Media3 wraps in generic IOExceptions.
        // This is the most common mid-playback skip cause.
        if (!isLocalMedia && isRetryableHttpError(error)) {
            android.util.Log.w(TAG, "Retryable HTTP error for $currentMediaId — refreshing URL and retrying")
            playbackUrlCache.remove(currentMediaId)
            extractorPlaybackUrlCache.remove(currentMediaId)
            YTPlayerUtils.invalidateCachedStreamUrls(currentMediaId)
            if (playbackStreamRecoveryTracker.registerRetryAttempt(currentMediaId)) {
                scope.launch {
                    delay(500L)
                    withContext(Dispatchers.IO) {
                        runCatching { resolveAndCachePlaybackUrl(currentMediaId) }
                    }
                    withContext(Dispatchers.Main) {
                        if (player.currentMediaItem?.mediaId == currentMediaId) {
                            player.prepare()
                        }
                    }
                }
                return
            }
        }

        if (dataStore.get(AutoSkipNextOnErrorKey, false)) {
            skipOnError()
        } else {
            stopOnError()
        }
    }

    /** Removes only transient streamed audio; completed offline downloads remain intact. */
    fun evictCachedAudio(mediaId: String) {
        if (mediaId.isBlank()) return
        scope.launch(Dispatchers.IO) {
            runCatching { playerCache.removeResource(mediaId) }
                .onFailure { Timber.tag("MusicService").w(it, "Unable to evict player cache for %s", mediaId) }
        }
    }

    private suspend fun trimPlayerCacheToBytes(limitBytes: Long) {
        if (limitBytes <= 0L) return

        withContext(Dispatchers.IO) {
            // The player cache's own folder, not the song-cache root it sits in: the root also
            // holds the SpotiFLAC playback files and the download cache, and counting those as
            // streamed bytes would evict this cache to pay for storage it does not own.
            val cacheDir = StorageLocationRepository.playerCacheDirectory(this@MusicService)
            val currentSpace = runCatching { playerCache.cacheSpace }.getOrNull() ?: 0L
            var totalBytes = if (currentSpace > 0L) currentSpace else cacheDir.directorySizeBytes()
            if (totalBytes <= limitBytes) return@withContext

            data class Candidate(
                val key: String,
                val lastTouchTimestamp: Long,
                val sizeBytes: Long,
            )

            val candidates =
                runCatching {
                    playerCache.keys
                        .mapNotNull { key ->
                            runCatching {
                                val spans = playerCache.getCachedSpans(key)
                                if (spans.isEmpty()) return@runCatching null
                                val oldestTouch = spans.minOf { it.lastTouchTimestamp }
                                val sizeBytes = spans.sumOf { it.length }
                                Candidate(key = key, lastTouchTimestamp = oldestTouch, sizeBytes = sizeBytes)
                            }.getOrNull()
                        }.sortedBy { it.lastTouchTimestamp }
                }.getOrNull().orEmpty()

            for (candidate in candidates) {
                if (totalBytes <= limitBytes) break
                val removedSize = candidate.sizeBytes.coerceAtLeast(0L)
                runCatching { playerCache.removeResource(candidate.key) }
                totalBytes -= removedSize
            }
        }
    }

    private fun createPlayerCacheDataSourceFactory(cacheWriteEnabled: Boolean): CacheDataSource.Factory =
        CacheDataSource
            .Factory()
            .setCache(playerCache)
            .setUpstreamDataSourceFactory(createResolvedUpstreamDataSourceFactory())
            .apply {
                if (!cacheWriteEnabled) {
                    setCacheWriteDataSinkFactory(null)
                }
            }.setFlags(FLAG_IGNORE_CACHE_ON_ERROR)

    private fun createCacheDataSource(): CacheDataSource.Factory =
        CacheDataSource
            .Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(
                DataSource.Factory {
                    createPlayerCacheDataSourceFactory(
                        cacheWriteEnabled = !isLowDataModeActive(),
                    ).createDataSource()
                },
            ).setCacheWriteDataSinkFactory(null)
            .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)

    /**
     * The playback byte chain.
     *
     * The order is the point, and it is: **resolve first, then route**.
     *
     * Resolution runs in the outermost layer, so [SchemeRoutingDataSource] sees the URI
     * the resolver actually produced rather than the URI the media item started with.
     * That distinction is the whole fix. A track's media item URI is a bare song id with
     * no scheme, and the router used to sit above the resolver - so every track took the
     * cache path, and when the resolver returned a local SpotiFLAC file
     * (`file://.../spotiflac/playback/<key>.flac`) CacheDataSource wrote the bytes of that
     * complete local file into the song cache under the song's cache key. The same audio
     * was then on disk twice: once as the SpotiFLAC file, and again as a Media3 entry that
     * outlived it and had to be reasoned about as "cached bytes of unknown provenance".
     *
     * Resolving first means a resolved local file is read straight off disk with no cache
     * layer anywhere in between ([PlaybackByteSource.LOCAL_FILE]), while a resolved remote
     * stream still goes through both caches ([PlaybackByteSource.CACHED]) so range
     * requests, seeking and offline playback behave exactly as before.
     *
     * One more thing is decided here, for the same reason: a song whose downloaded file is
     * already in the downloads folder but whose index entry was lost (a reinstall, a data
     * clear, a restore onto another device) is matched back to this track by name and served
     * from disk. A download exists to be played locally, so finding the file is worth a
     * lookup; see [adoptDownloadedFile].
     */
    private fun createDataSourceFactory(): DataSource.Factory {
        // Built once and shared across opens, exactly as before: both are factories, so
        // every open still gets its own DataSource while the OkHttp clients, cache and
        // listener wiring are not rebuilt per request.
        val cachedFactory = createCacheDataSource()
        val directFactory = createResolvedUpstreamDataSourceFactory()

        val routingFactory =
            DataSource.Factory {
                SchemeRoutingDataSource(
                    cachedFactory = cachedFactory,
                    directFactory = directFactory,
                )
            }

        return ResolvingDataSource.Factory(routingFactory) { dataSpec ->
            resolvePlaybackDataSpec(dataSpec = dataSpec)
        }
    }

    private fun createResolvedUpstreamDataSourceFactory(): DataSource.Factory {
        val youtubeMediaFactory =
            DefaultDataSource.Factory(
                this,
                OkHttpDataSource.Factory(mediaOkHttpClient),
            )
        val extractorMediaFactory =
            DefaultDataSource.Factory(
                this,
                OkHttpDataSource.Factory(extractorMediaOkHttpClient),
            )
        val routingFactory =
            DataSource.Factory {
                ResolvedUrlRoutingDataSource(
                    defaultFactory = youtubeMediaFactory,
                    extractorFactory = extractorMediaFactory,
                    shouldUseExtractorFactory = ::isExtractorPlaybackUri,
                )
            }

        return ResolvingDataSource.Factory(routingFactory) { dataSpec ->
            resolvePlaybackDataSpec(dataSpec = dataSpec)
        }
    }

    private fun resolveMediaItemForCast(mediaItem: MediaItem): MediaItem {
        val uri = mediaItem.localConfiguration?.uri ?: return mediaItem
        if (uri.shouldBypassYouTubeResolver()) return mediaItem
        val dataSpec =
            DataSpec
                .Builder()
                .setUri(uri)
                .setKey(mediaItem.localConfiguration?.customCacheKey ?: mediaItem.mediaId)
                .build()
        val resolvedDataSpec =
            resolvePlaybackDataSpec(dataSpec = dataSpec)
        return if (resolvedDataSpec.uri == uri) {
            mediaItem
        } else {
            mediaItem
                .buildUpon()
                .setUri(resolvedDataSpec.uri)
                .build()
        }
    }

    /**
     * Re-establishes a download's record from the file the user still has.
     *
     * A downloads index lives in the app's private data, so a reinstall, a data clear, or a
     * restore onto another device loses it while the files - ordinary files in a folder the user
     * chose - stay behind. Without this, a song whose file is already sitting there was streamed
     * from the network again, which is the opposite of what downloading it was for.
     *
     * The track's name is what the file is matched on, and this is the point where a media id
     * still has a database row to ask, so the lookup belongs here and happens once per media id.
     */
    private fun adoptDownloadedFile(mediaId: String): java.io.File? {
        if (mediaId.isBlank()) return null
        if (!downloadAdoptionChecked.add(mediaId)) return null
        return runCatching {
            val song =
                runBlocking(Dispatchers.IO) { database.song(mediaId).first() }
                    ?: return@runCatching null
            val adopted =
                downloadedFileStore.adoptExistingFile(
                    mediaId = mediaId,
                    title = song.song.title,
                    artist = song.artists.joinToString(", ") { it.name },
                ) ?: return@runCatching null
            java.io.File(adopted.path).takeIf { it.isFile }
        }.getOrNull()
    }

    /**
     * The single resolution path for a playback open.
     *
     * It reads as a sequence of guards, and each one is the reason a step below it is
     * skipped: a resolved local file is preferred over any cache, then cached bytes (a
     * download, or the streaming cache while YouTube is on), then a non-YouTube first
     * engine's local file, then the one single-flight resolver shared with prefetch,
     * refresh and recovery.
     *
     * There used to be a second, unreachable copy of this below the real one - behind
     * `@Suppress("UNREACHABLE_CODE") if (false)` - which still threw
     * "No stream available - both sources failed" for paths that had already been
     * replaced. It never executed, so it could not break anything, but it is what made
     * this function read as two competing resolvers and sent two separate debugging
     * sessions down the wrong branch.
     */
    private fun resolvePlaybackDataSpec(dataSpec: DataSpec): DataSpec {
        if (dataSpec.uri.shouldBypassYouTubeResolver()) {
            return dataSpec
        }
        val mediaId = dataSpec.key ?: return dataSpec
        val storedFormat =
            formatEntityCache[mediaId] ?: runBlocking(Dispatchers.IO) {
                database.format(mediaId).first()?.also { formatEntityCache[mediaId] = it }
            }
        storedFormat?.let { format ->
            audioNormalizationFactorCache[mediaId] = calculateAudioNormalizationFactor(format, normalizeAudio = true)
        }
        val knownContentLength =
            contentLengthCache[mediaId] ?: storedFormat?.contentLength?.takeIf { it > 0L } ?: runCatching {
                downloadCache
                    .getContentMetadata(mediaId)
                    .get(ContentMetadata.KEY_CONTENT_LENGTH, -1L)
            }.getOrNull()?.takeIf { it > 0L } ?: runCatching {
                playerCache
                    .getContentMetadata(mediaId)
                    .get(ContentMetadata.KEY_CONTENT_LENGTH, -1L)
            }.getOrNull()?.takeIf { it > 0L } ?: runCatching {
                // Fallback: derive content length from cached download spans so that
                // fully-downloaded songs can short-circuit even when cache metadata
                // did not record KEY_CONTENT_LENGTH (e.g. chunked YouTube responses).
                downloadCache.getCachedSpans(mediaId).takeIf { it.isNotEmpty() }?.sumOf { it.length }
            }.getOrNull()?.takeIf { it > 0L }

        knownContentLength?.takeIf { it > 0L }?.let { contentLengthCache[mediaId] = it }

        val lowDataModeActive = isLowDataModeActive()

        // A download is an ordinary file this device owns, so it is served straight off
        // disk: before the caches, and independent of which engines are switched on. That
        // is what downloading it was for. Serving the file also skips the resolver, so the
        // source label is published here for the same reason the SpotiFLAC branch below
        // publishes it - otherwise the player falls back to guessing.
        (downloadedFileStore.fileFor(mediaId) ?: adoptDownloadedFile(mediaId))?.let { downloaded ->
            if (downloadedFileStore.get(mediaId)?.origin == StoredBytesOrigin.SPOTIFLAC.name) {
                publishSpotiFLACCachedLabel(mediaId)
                publishSpotiFLACFileFormat(mediaId)
            } else {
                publishDownloadedFileLabel(mediaId)
            }
            Timber.tag(TAG).i("serving downloaded file for mediaId=$mediaId: ${downloaded.name}")
            // Also in the diagnostic log, because "these bytes came from the user's download"
            // is the answer to the question every playback complaint starts with, and Timber
            // lines do not survive a logcat buffer on a head unit.
            app.hush.music.spotiflac.SpotiFLACDiag.log(
                "serving downloaded file for mediaId=$mediaId: ${downloaded.name}" +
                    (if (downloadedFileStore.get(mediaId)?.origin == StoredBytesOrigin.SPOTIFLAC.name) {
                        " (SpotiFLAC)"
                    } else {
                        ""
                    }),
            )
            scope.launch(Dispatchers.IO) { recoverSong(mediaId, isOfflinePlayback = true) }
            return dataSpec.withUri(downloaded.toUri())
        }

        // A SpotiFLAC playback file has to be consulted *before* Media3's caches.
        //
        // Both caches are keyed by mediaId, and the bytes stored under that key came
        // from whichever engine happened to be selected when they were written. So a
        // track whose bytes were cached from YouTube earlier would keep being served
        // out of that cache - instantly, with no resolver call - even after SpotiFLAC
        // became the first engine, while every visible label (source chip, codec row)
        // reported SpotiFLAC. That is how a track could announce "SpotiFLAC - deezer"
        // and still decode as a YouTube stream, and why switching YouTube off did not
        // stop it playing.
        //
        // The file is local, complete and immutable, so preferring it costs nothing:
        // it is not the "remote streaming" that the cache exists to avoid.
        val spotiflacFirst =
            spotiflacEnabled &&
                effectiveEngineOrder(spotiflacEnabled, isYouTubeStreamingEnabled())
                    .firstOrNull() == PlaybackEngineOrder.SPOTIFLAC
        if (spotiflacFirst) {
            spotiflacPlaybackFile(mediaId)?.let { cachedFile ->
                app.hush.music.spotiflac.SpotiFLACDiag.log(
                    "serving SpotiFLAC file for mediaId=$mediaId ahead of the Media3 cache",
                )
                publishSpotiFLACCachedLabel(mediaId)
                publishSpotiFLACFileFormat(mediaId)
                scope.launch(Dispatchers.IO) { recoverSong(mediaId) }
                return dataSpec.withUri(cachedFile.toUri())
            }
        }

        // Locally downloaded content (and, while YouTube is an active engine, the
        // transient streaming cache). Only use remote playback when neither exists.
        val cachedDataSpec = resolveLocalPlaybackDataSpecIfAvailable(
            dataSpec = dataSpec,
            mediaId = mediaId,
            knownContentLength = knownContentLength,
        )
        if (cachedDataSpec != null) {
            scope.launch(Dispatchers.IO) { recoverSong(mediaId, isOfflinePlayback = true) }
            return cachedDataSpec
        }

        // SpotiFLAC playback files live outside Media3's cache, so they are served as
        // a plain local file. Checking here - not only when a track first resolves -
        // means a seek re-open reads the same file instead of downloading it again.
        // When SpotiFLAC is the first engine the check above already covered this, and
        // the resolver below would pick SpotiFLAC anyway; this branch is what keeps a
        // SpotiFLAC copy usable when YouTube is the first engine.
        if (!spotiflacFirst) spotiflacPlaybackFile(mediaId)?.let { cachedFile ->
            // Serving the file off disk skips the resolver, so the source label has
            // to be published here. Without this the player fell back to "YouTube"
            // for every SpotiFLAC track that was already cached (and after every
            // seek, since a re-open takes this same path).
            publishSpotiFLACCachedLabel(mediaId)
            publishSpotiFLACFileFormat(mediaId)
            scope.launch(Dispatchers.IO) { recoverSong(mediaId) }
            return dataSpec.withUri(cachedFile.toUri())
        }
        val effectiveAudioQuality = resolveEffectiveAudioQuality(audioQuality, lowDataModeActive)
        // The ArchiveTune extractor resolves audio out of a YouTube watch URL, so it is a
        // YouTube engine: it must not run while YouTube is switched off, exactly like the
        // InnerTube path below. Without this gate, choosing the extractor client (or
        // dropping to Low quality) kept pulling YouTube audio - a WebM stream - with
        // YouTube disabled, which is a stream the user had explicitly turned off.
        val preferExternalExtractorOnly =
            isYouTubeStreamingEnabled() &&
                activeStreamClient == PlayerStreamClient.ARCHIVETUNE_EXTRACTOR &&
                (lowDataModeActive || effectiveAudioQuality == AudioQuality.LOW)

        if (preferExternalExtractorOnly) {
            return resolveArchiveTuneExtractorDataSpec(
                dataSpec = dataSpec,
                mediaId = mediaId,
                audioQuality = effectiveAudioQuality,
            )
        }

        // Source-toggle safety net: a cached YouTube URL must never be replayed
        // while YouTube is disabled. Purge and re-resolve through SpotiFLAC.
        cachedPlaybackUrl(mediaId)?.let { cached ->
            if (cached.isYouTubeStream && !isYouTubeStreamingEnabled()) {
                playbackUrlCache.remove(mediaId)
                extractorPlaybackUrlCache.remove(mediaId)
                Timber.tag(TAG).w("Dropped cached YouTube URL for %s while YouTube is disabled", mediaId)
            }
        }

        // All non-local, non-extractor playback uses the same single-flight resolver
        // as prefetch, refresh, and recovery. This prevents multiple Media3 opens for
        // one cold track from starting duplicate YouTube/SpotiFLAC requests.
        val resolvedPlayback =
            try {
                runBlocking(Dispatchers.IO) {
                    withContext(NonCancellable) {
                        resolveAndCachePlaybackUrl(mediaId)
                    }
                }
            } catch (cancellation: CancellationException) {
                // Cancellation is not a stream failure. It means the player moved on (a skip
                // or seek, or a re-open of the same item) or a duplicate in-flight resolve
                // superseded this one - a request nobody is waiting for any more. Mapping it
                // to "No stream available" reported a failure that never happened.
                throw cancellation
            } catch (throwable: Throwable) {
                throw mapStreamResolutionFailure(throwable, mediaId)
            }
        return buildResolvedStreamDataSpec(
            dataSpec = dataSpec,
            streamUrl = resolvedPlayback.url,
            knownContentLength = knownContentLength,
            mimeType = storedFormat?.mimeType,
            isYouTubeStream = resolvedPlayback.isYouTubeStream,
        )
    }

    private fun buildResolvedStreamDataSpec(
        dataSpec: DataSpec,
        streamUrl: String,
        knownContentLength: Long?,
        mimeType: String?,
        isYouTubeStream: Boolean = false,
    ): DataSpec {
        val resolvedDataSpec = dataSpec.withUri(streamUrl.toUri())
        if (isYouTubeStream) return resolvedDataSpec
        // Chunked range reads exist to defeat YouTube's per-request throttling and
        // must never bound a local file: clamping a file read to 8 MB is what broke
        // seeking (and playback past the first chunk).
        if (streamUrl.isLocalPlaybackUrl()) return resolvedDataSpec
        val length =
            resolveStreamChunkLength(
                requestedLength = dataSpec.length,
                position = dataSpec.position,
                knownContentLength = knownContentLength,
                chunkLength = CHUNK_LENGTH,
                mimeType = mimeType?.substringBefore(';'),
            )
        return length?.let { nonNullLength ->
            resolvedDataSpec.subrange(0L, nonNullLength)
        } ?: resolvedDataSpec
    }

    private suspend fun resolveHiResLosslessPlayback(mediaId: String): Result<YTPlayerUtils.PlaybackData> =
        runCatching {
            val song = database.song(mediaId).first()
            val mediaItem =
                withContext(Dispatchers.Main) {
                    player.findNextMediaItemById(mediaId)
                        ?: player.currentMediaItem?.takeIf { it.mediaId == mediaId }
                }
            val mediaMetadata = mediaItem?.metadata
            val mediaItemMetadata = mediaItem?.mediaMetadata
            val title =
                song?.song?.title?.takeIf { it.isNotBlank() }
                    ?: mediaMetadata?.title?.takeIf { it.isNotBlank() }
                    ?: mediaItemMetadata?.title?.toString()?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Missing track title for external stream lookup")
            val artists =
                song
                    ?.artists
                    ?.map { it.name }
                    ?.filter { it.isNotBlank() }
                    ?.takeIf { it.isNotEmpty() }
                    ?: mediaMetadata
                        ?.artists
                        ?.map { it.name }
                        ?.filter { it.isNotBlank() }
                        ?.takeIf { it.isNotEmpty() }
                    ?: mediaItemMetadata
                        ?.artist
                        ?.toString()
                        ?.split(',', '&')
                        ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                        .orEmpty()
            val durationSeconds =
                song?.song?.duration?.takeIf { it > 0 }
                    ?: mediaMetadata?.duration?.takeIf { it > 0 }

            HiResLosslessPlaybackResolver
                .resolve(
                    HiResLosslessPlaybackResolver.TrackIdentity(
                        title = title,
                        artists = artists,
                        durationSeconds = durationSeconds,
                    ),
                ).getOrThrow()
        }

    private fun resolveArchiveTuneExtractorDataSpec(
        dataSpec: DataSpec,
        mediaId: String,
        audioQuality: AudioQuality,
    ): DataSpec {
        if (app.hush.music.BuildConfig.EXTRACTOR_BEARER.isBlank()) {
            throw PlaybackException(
                getString(R.string.error_extractor_token_missing),
                HushExtractorException("Hush Extractor token is missing"),
                PlaybackException.ERROR_CODE_REMOTE_ERROR,
            )
        }

        val authState = YouTube.currentPlaybackAuthState()
        val authFingerprint = ArchiveTuneExtractorCacheFingerprintPrefix + authState.fingerprint
        val userPoToken = authState.resolveExtractorPoToken()
        val userCookies = authState.resolveExtractorCookies()

        extractorPlaybackUrlCache[mediaId]
            ?.takeIf {
                it.isValidFor(
                    authFingerprint = authFingerprint,
                    minimumRemainingMs = 0L,
                )
            }?.let { cached ->
                scope.launch(Dispatchers.IO) { recoverSong(mediaId, isOfflinePlayback = true) }
                return dataSpec.withUri(cached.url.toUri())
            }

        val streamUrl =
            runCatching {
                runBlocking(Dispatchers.IO) {
                    streamingExtractionManager.extractAudioUrl(
                        videoUrl = mediaId.toYouTubeWatchUrl(),
                        audioQuality = audioQuality.toExtractorAudioQuality(),
                        userPoToken = userPoToken,
                        cookies = userCookies,
                    )
                }
            }.getOrElse { throwable ->
                when {
                    throwable.isNetworkConnectionFailure() -> {
                        throw PlaybackException(
                            getString(R.string.error_no_internet),
                            throwable,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        )
                    }

                    throwable.isRequestTimeout() -> {
                        throw PlaybackException(
                            getString(R.string.error_timeout),
                            throwable,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                        )
                    }

                    throwable is HushExtractorException -> {
                        val message =
                            if (throwable.message?.contains("token is missing", ignoreCase = true) == true) {
                                getString(R.string.error_extractor_token_missing)
                            } else {
                                getString(R.string.error_no_stream)
                            }
                        throw PlaybackException(
                            message,
                            throwable,
                            PlaybackException.ERROR_CODE_REMOTE_ERROR,
                        )
                    }

                    throwable is PlaybackException -> {
                        throw throwable
                    }

                    else -> {
                        throw PlaybackException(
                            getString(R.string.error_unknown),
                            throwable,
                            PlaybackException.ERROR_CODE_REMOTE_ERROR,
                        )
                    }
                }
            }

        extractorPlaybackUrlCache[mediaId] =
            AuthScopedCacheValue(
                url = streamUrl,
                expiresAtMs = System.currentTimeMillis() + ArchiveTuneExtractorCacheTtlMs,
                authFingerprint = authFingerprint,
            )
        scope.launch(Dispatchers.IO) { recoverSong(mediaId) }
        return dataSpec.withUri(streamUrl.toUri())
    }

    private fun PlaybackAuthState.resolveExtractorPoToken(): String? =
        resolveGvsPoToken().normalizeExtractorRequestValue()
            ?: poTokenGvs.normalizeExtractorRequestValue()
            ?: poToken.normalizeExtractorRequestValue()
            ?: poTokenPlayer.normalizeExtractorRequestValue()

    private fun PlaybackAuthState.resolveExtractorCookies(): String? =
        cookie.normalizeExtractorRequestValue()

    private fun String?.normalizeExtractorRequestValue(): String? {
        val trimmed = this?.trim()
        return trimmed?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    }

    private fun String.toYouTubeWatchUrl(): String = "https://music.youtube.com/watch?v=$this"

    private fun isExtractorPlaybackUri(uri: Uri): Boolean {
        val url = uri.toString()
        return extractorPlaybackUrlCache.values.any { it.url == url } ||
            uri.path?.startsWith("/api/play/") == true
    }

    private fun AudioQuality.toExtractorAudioQuality(): ExtractorAudioQuality =
        when (this) {
            AudioQuality.HIGHEST -> ExtractorAudioQuality.HIGHEST
            AudioQuality.HIGH -> ExtractorAudioQuality.HIGH
            AudioQuality.AUTO -> ExtractorAudioQuality.AUTO
            AudioQuality.LOW -> ExtractorAudioQuality.LOW
        }

    /**
     * Cached bytes that can be played without asking a resolver for a URL.
     *
     * The two stores mean different things. `downloadCache` only ever receives writes
     * from the download service, so its entries are content the user explicitly
     * downloaded. `playerCache` is the *streaming* cache: it holds whatever bytes were
     * fetched for a mediaId.
     *
     * Both are keyed by mediaId alone, so the key cannot say which engine produced the
     * bytes stored under it. Hush has created downloads whose content is a YouTube
     * stream (`itag=251` WebM) and downloads whose content is a SpotiFLAC FLAC file,
     * for the same song, at different times. So serving either store has to be gated on
     * the origin actually matching an enabled engine - otherwise a switched-off YouTube
     * keeps playing through whichever cache still holds its bytes.
     *
     * SpotiFLAC's own file is served from earlier on this path, so it never depends on
     * this decision.
     */
    private fun resolveLocalPlaybackDataSpecIfAvailable(
        dataSpec: DataSpec,
        mediaId: String,
        knownContentLength: Long?,
    ): DataSpec? {
        // The streaming cache is only meaningful while YouTube is an active engine.
        val youtubeEnabled = isYouTubeStreamingEnabled()
        val includePlayerCache = youtubeEnabled
        // User downloads stay playable offline, but only when their stored bytes did
        // not come from YouTube. Hush records that itself, because Media3 cannot: see
        // [DownloadOriginStore].
        val downloadOrigin = downloadedBytesOrigin(mediaId)
        val downloadCacheDecision =
            PlaybackEngineOrder.storedBytesDecision(
                youtubeEnabled = youtubeEnabled,
                origin = downloadOrigin,
            )
        val includeDownloadCache = downloadCacheDecision == StoredBytesDecision.SERVE
        val downloadBytesPresent =
            runCatching { downloadCache.getCachedSpans(mediaId).isNotEmpty() }.getOrDefault(false)
        if (!includePlayerCache && !includeDownloadCache && downloadBytesPresent) {
            app.hush.music.spotiflac.SpotiFLACDiag.log(
                when (downloadCacheDecision) {
                    StoredBytesDecision.REFUSE_ENGINE_DISABLED ->
                        "ignoring downloaded bytes for mediaId=$mediaId: recorded origin is " +
                            "${downloadOrigin.name} and YouTube is disabled"
                    else ->
                        "ignoring downloaded bytes for mediaId=$mediaId: recorded origin is unknown " +
                            "while YouTube is disabled"
                },
            )
        }

        val effectiveContentLength =
            knownContentLength ?: inferCachedContentLength(
                mediaId = mediaId,
                position = dataSpec.position,
                includePlayerCache = includePlayerCache,
                includeDownloadCache = includeDownloadCache,
            )

        resolveCachedDataSpec(
            dataSpec = dataSpec,
            mediaId = mediaId,
            knownContentLength = effectiveContentLength,
            includePlayerCache = includePlayerCache,
            includeDownloadCache = includeDownloadCache,
        )?.let { return it }

        val requiredCachedLength =
            when {
                dataSpec.length >= 0L -> dataSpec.length
                effectiveContentLength != null && effectiveContentLength > dataSpec.position -> {
                    effectiveContentLength - dataSpec.position
                }
                else -> null
            } ?: return null

        val isFullyCached =
            (includeDownloadCache && downloadCache.isCached(mediaId, dataSpec.position, requiredCachedLength)) ||
                (
                    includePlayerCache &&
                        playerCache.isCached(mediaId, dataSpec.position, requiredCachedLength)
                )
        return if (isFullyCached) dataSpec else null
    }

    /**
     * Which engine produced the bytes stored for [mediaId], for the purpose of deciding
     * whether they may be served.
     *
     * Hush's own record comes first because it is authoritative - it is written at the
     * moment Hush chooses the download's URI - and because Media3's redirect metadata is
     * never populated on this path at all: `CacheDataSource` records a redirect only when
     * the URI it opened differs from the URI it was asked for, and Hush hands it the
     * already-resolved URI. The Media3 lookup is kept as a fallback for entries written
     * by other paths, and an entry that recorded nothing is [StoredBytesOrigin.UNKNOWN],
     * so the answer fails closed rather than playing bytes of unknown origin.
     */
    private fun downloadedBytesOrigin(mediaId: String): StoredBytesOrigin {
        when (downloadOriginStore.originOf(mediaId)) {
            StoredBytesOrigin.SPOTIFLAC -> return StoredBytesOrigin.SPOTIFLAC
            StoredBytesOrigin.YOUTUBE -> return StoredBytesOrigin.YOUTUBE
            StoredBytesOrigin.UNKNOWN -> Unit
        }
        return if (cachedBytesCameFromSpotiFLAC(mediaId, downloadCache)) {
            StoredBytesOrigin.SPOTIFLAC
        } else {
            StoredBytesOrigin.UNKNOWN
        }
    }

    /**
     * The failure a user actually hits when SpotiFLAC cannot match a track and YouTube has
     * been switched off.
     *
     * A plain `IOException` here surfaced as the generic "No stream available" source error,
     * which says nothing about the cause and reads like a bug rather than a setting. The
     * message names both halves of the situation so it is actionable, and the type tells the
     * player not to burn its retry budget re-running a provider sweep that cannot succeed.
     */
    private fun spotiflacNoMatchWhileYouTubeOff(mediaId: String? = null): NoPlayableSourceException =
        NoPlayableSourceException(
            getString(R.string.spotiflac_no_match_youtube_off),
            rememberedMissAtMs = rememberedMissAt(mediaId),
        )

    /** As above, for the case where the user turned the YouTube fallback itself off. */
    private fun spotiflacNoMatchAndFallbackOff(mediaId: String? = null): NoPlayableSourceException =
        NoPlayableSourceException(
            getString(R.string.spotiflac_no_match_fallback_off),
            rememberedMissAtMs = rememberedMissAt(mediaId),
        )

    /**
     * When this track's miss was recorded, if a remembered answer is what produced the
     * failure. Null means the sweep ran just now and this is a fresh verdict.
     */
    private fun rememberedMissAt(mediaId: String?): Long? =
        mediaId
            ?.takeIf { it.isNotBlank() }
            ?.let { spotiFLACMissMemo.entry(it, spotiflacMissContext())?.recordedAtMs }

    /**
     * Whether the bytes cached under [mediaId] were fetched from a SpotiFLAC playback
     * file, according to the redirect Media3 recorded for the cached response.
     *
     * This is the fallback signal; [downloadedBytesOrigin] consults Hush's own
     * [DownloadOriginStore] first. It is kept because a `playerCache` entry written while
     * a *different* URI was requested can still carry a redirect, and because it is the
     * only signal available for entries written before the origin store existed.
     */
    private fun cachedBytesCameFromSpotiFLAC(
        mediaId: String,
        cache: Cache,
    ): Boolean =
        runCatching {
            val redirected = ContentMetadata.getRedirectedUri(cache.getContentMetadata(mediaId))
            val path = redirected?.path ?: return@runCatching false
            SPOTIFLAC_PLAYBACK_PATH_SEGMENTS.any { segment -> path.contains(segment) }
        }.getOrDefault(false)

    private fun hasFullyDownloadedLocalPlayback(mediaId: String): Boolean {
        val length =
            inferCachedContentLength(
                mediaId = mediaId,
                position = 0L,
                includePlayerCache = false,
                includeDownloadCache = true,
            ) ?: return false
        return downloadCache.isCached(mediaId, 0L, length)
    }

    private fun inferCachedContentLength(
        mediaId: String,
        position: Long,
        includePlayerCache: Boolean = true,
        includeDownloadCache: Boolean = true,
    ): Long? {
        val spans =
            if (!includeDownloadCache) {
                emptyList()
            } else {
                runCatching { downloadCache.getCachedSpans(mediaId).toList() }.getOrNull().orEmpty()
            } + if (!includePlayerCache) {
                emptyList()
            } else {
                runCatching { playerCache.getCachedSpans(mediaId).toList() }.getOrNull().orEmpty()
            }
        if (spans.isEmpty()) return null
        val continuousFromStart =
            getContinuousCachedLength(
                mediaId = mediaId,
                position = 0L,
                requestedLength = Long.MAX_VALUE,
                includePlayerCache = includePlayerCache,
                includeDownloadCache = includeDownloadCache,
            )
        if (continuousFromStart <= 0L) return null
        return (continuousFromStart - position).takeIf { it > 0L }
    }

    private fun mapStreamResolutionFailure(
        throwable: Throwable,
        mediaId: String,
    ): PlaybackException =
        when {
            throwable is CancellationException -> {
                PlaybackException(
                    getString(R.string.error_no_stream),
                    throwable,
                    PlaybackException.ERROR_CODE_REMOTE_ERROR,
                )
            }

            throwable is YTPlayerUtils.InvalidPlaybackLoginContextException -> {
                promptLoginRecovery(mediaId, throwable.targetUrl)
                PlaybackException(
                    getString(R.string.playback_requires_youtube_music_login_refresh),
                    throwable,
                    PlaybackException.ERROR_CODE_REMOTE_ERROR,
                )
            }

            throwable is YTPlayerUtils.LoginRequiredForPlaybackException -> {
                PlaybackException(
                    getString(R.string.playback_requires_youtube_music_confirmation),
                    throwable,
                    PlaybackException.ERROR_CODE_REMOTE_ERROR,
                )
            }

            throwable is YTPlayerUtils.BotDetectionPlaybackException -> {
                PlaybackException(
                    getString(R.string.error_no_stream),
                    throwable,
                    PlaybackException.ERROR_CODE_REMOTE_ERROR,
                )
            }

            throwable is YTPlayerUtils.BadStreamPlayerResponseException -> {
                PlaybackException(
                    getString(R.string.error_no_stream),
                    throwable,
                    PlaybackException.ERROR_CODE_REMOTE_ERROR,
                )
            }

            throwable is PlaybackException -> throwable

            throwable.isNetworkConnectionFailure() -> {
                PlaybackException(
                    getString(R.string.error_no_internet),
                    throwable,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                )
            }

            throwable.isRequestTimeout() -> {
                PlaybackException(
                    getString(R.string.error_timeout),
                    throwable,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                )
            }

            else -> {
                PlaybackException(
                    getString(R.string.error_unknown),
                    throwable,
                    PlaybackException.ERROR_CODE_REMOTE_ERROR,
                )
            }
        }

    private fun resolveCachedDataSpec(
        dataSpec: DataSpec,
        mediaId: String,
        knownContentLength: Long?,
        includePlayerCache: Boolean = true,
        includeDownloadCache: Boolean = true,
    ): DataSpec? {
        val requestedLength =
            when {
                dataSpec.length > 0L -> {
                    dataSpec.length
                }

                knownContentLength != null && knownContentLength > dataSpec.position -> {
                    knownContentLength - dataSpec.position
                }

                else -> {
                    inferCachedContentLength(
                        mediaId = mediaId,
                        position = dataSpec.position,
                        includePlayerCache = includePlayerCache,
                        includeDownloadCache = includeDownloadCache,
                    ) ?: return null
                }
            }

        val cachedLength =
            getContinuousCachedLength(
                mediaId = mediaId,
                position = dataSpec.position,
                requestedLength = requestedLength,
                includePlayerCache = includePlayerCache,
                includeDownloadCache = includeDownloadCache,
            )

        if (cachedLength < requestedLength) return null

        return dataSpec.subrange(0L, requestedLength)
    }

    private fun getContinuousCachedLength(
        mediaId: String,
        position: Long,
        requestedLength: Long,
        includePlayerCache: Boolean = true,
        includeDownloadCache: Boolean = true,
    ): Long {
        val targetEnd = position.saturatingAdd(requestedLength)
        var cursor = position
        val spans =
            (
                if (!includeDownloadCache) {
                    emptyList()
                } else {
                    runCatching { downloadCache.getCachedSpans(mediaId).toList() }.getOrNull().orEmpty()
                } + if (!includePlayerCache) {
                    emptyList()
                } else {
                    runCatching { playerCache.getCachedSpans(mediaId).toList() }.getOrNull().orEmpty()
                }
            ).asSequence()
                .filter { span -> span.position.saturatingAdd(span.length) > position }
                .sortedBy { span -> span.position }
                .toList()

        for (span in spans) {
            if (span.position > cursor) break
            val spanEnd = span.position.saturatingAdd(span.length)
            if (spanEnd > cursor) {
                cursor = minOf(spanEnd, targetEnd)
                if (cursor >= targetEnd) break
            }
        }

        return (cursor - position).coerceAtLeast(0L)
    }

    private fun Long.saturatingAdd(value: Long): Long {
        if (value <= 0L) return this
        val result = this + value
        return if (result < this) Long.MAX_VALUE else result
    }

    private fun Uri.shouldBypassYouTubeResolver(): Boolean {
        val normalizedScheme = scheme?.lowercase(Locale.US)
        return PlaybackDataSourceRouting.isLocalFileScheme(normalizedScheme) ||
            normalizedScheme == "http" ||
            normalizedScheme == "https"
    }

    /**
     * Whether these bytes never pass through a Media3 cache - the same rule the routing in
     * [SchemeRoutingDataSource] applies, kept in one place so crossfade and local-item
     * heuristics cannot disagree with the byte chain about what "local" means.
     */
    private fun Uri.shouldBypassPlayerCache(): Boolean =
        PlaybackDataSourceRouting.isLocalFileScheme(scheme)

    private fun deviceSupportsMimeType(mimeType: String): Boolean =
        runCatching {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            codecList.codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            }
        }.getOrDefault(false)

    private fun createMediaSourceFactory() =
        DefaultMediaSourceFactory(
            createDataSourceFactory(),
            DefaultExtractorsFactory(),
        )

    private class ResolvedUrlRoutingDataSource(
        private val defaultFactory: DataSource.Factory,
        private val extractorFactory: DataSource.Factory,
        private val shouldUseExtractorFactory: (Uri) -> Boolean,
    ) : DataSource {
        private val transferListeners = mutableListOf<TransferListener>()
        private var delegate: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            transferListeners += transferListener
            delegate?.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val selectedFactory =
                if (shouldUseExtractorFactory(dataSpec.uri)) {
                    extractorFactory
                } else {
                    defaultFactory
                }
            val selectedDataSource = selectedFactory.createDataSource()
            transferListeners.forEach(selectedDataSource::addTransferListener)
            delegate = selectedDataSource
            return selectedDataSource.open(dataSpec)
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = checkNotNull(delegate).read(buffer, offset, length)

        override fun getUri(): Uri? = delegate?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()

        override fun close() {
            delegate?.close()
            delegate = null
        }
    }

    /**
     * Sends an already-resolved [DataSpec] either straight to the local file or through
     * Media3's caches. See [createDataSourceFactory] for why it must be the *resolved*
     * URI that is inspected here.
     */
    // SchemeRoutingDataSource moved to its own file: the download path needs the same
    // "a resolved local file never enters a cache" routing, and two copies of that rule
    // is how the two paths drifted apart in the first place.

    private fun updateAudioOffload(enabled: Boolean) {
        val effectiveEnabled = enabled && !crossfadeEnabled
        runCatching {
            val builder = localPlayer.trackSelectionParameters.buildUpon()
            val audioOffloadPrefsClass = Class.forName("androidx.media3.common.AudioOffloadPreferences")
            val audioOffloadPrefsBuilderClass = Class.forName("androidx.media3.common.AudioOffloadPreferences\$Builder")

            val modeFieldName = if (effectiveEnabled) "AUDIO_OFFLOAD_MODE_ENABLED" else "AUDIO_OFFLOAD_MODE_DISABLED"
            val mode = audioOffloadPrefsClass.getField(modeFieldName).getInt(null)

            val prefsBuilder = audioOffloadPrefsBuilderClass.getDeclaredConstructor().newInstance()
            audioOffloadPrefsBuilderClass.getMethod("setAudioOffloadMode", Int::class.javaPrimitiveType).invoke(prefsBuilder, mode)
            val prefs = audioOffloadPrefsBuilderClass.getMethod("build").invoke(prefsBuilder)

            val setMethod =
                builder.javaClass.methods.firstOrNull { method ->
                    method.name == "setAudioOffloadPreferences" && method.parameterTypes.size == 1
                }
            if (setMethod != null) {
                setMethod.invoke(builder, prefs)
                localPlayer.trackSelectionParameters = builder.build()
            }
        }
        localPlayer.setOffloadEnabled(effectiveEnabled)
    }

    private fun updateWakeLock() {
        val wl = wakeLock ?: return
        val shouldHold = wakelockEnabled && player.isPlaying
        if (shouldHold && !wl.isHeld) {
            wl.acquire()
        } else if (!shouldHold && wl.isHeld) {
            wl.release()
        }
    }

    private fun createPrimaryLoadControl(): DefaultLoadControl =
        DefaultLoadControl
            .Builder()
            .setBufferDurationsMs(
                PRIMARY_MIN_BUFFER_MS,
                PRIMARY_MAX_BUFFER_MS,
                PRIMARY_BUFFER_FOR_PLAYBACK_MS,
                PRIMARY_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            ).setPrioritizeTimeOverSizeThresholds(true)
            .build()

    private fun createCrossfadeLoadControl(): DefaultLoadControl =
        DefaultLoadControl
            .Builder()
            .setBufferDurationsMs(
                CROSSFADE_MIN_BUFFER_MS,
                CROSSFADE_MAX_BUFFER_MS,
                CROSSFADE_MIN_BUFFER_BEFORE_START_MS.toInt(),
                CROSSFADE_MIN_BUFFER_BEFORE_START_MS.toInt(),
            ).setPrioritizeTimeOverSizeThresholds(true)
            .build()

    private fun createRenderersFactory(eqProcessor: CustomEqualizerAudioProcessor) =
        object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ) = DefaultAudioSink
                .Builder(context)
                .setEnableFloatOutput(false)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessorChain(
                    DefaultAudioSink.DefaultAudioProcessorChain(
                        eqProcessor,
                        SilenceSkippingAudioProcessor(
                            1_500_000L,
                            0.35f,
                            500_000L,
                            10,
                            150.toShort(),
                        ),
                        SonicAudioProcessor(),
                    ),
                ).build()
        }

    override fun onPlaybackStatsReady(
        eventTime: AnalyticsListener.EventTime,
        playbackStats: PlaybackStats,
    ) {
        val mediaItem = eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem
        val mediaId = mediaItem.mediaId
        val thresholdMs = historyThresholdMs()
        val pendingSession = popPendingHistoryFinalization(mediaId)
        val alreadyPersistedForSession = pendingSession?.eventId != null || pendingSession?.remoteRegistered == true
        val reachedHistoryThreshold =
            playbackStats.totalPlayTimeMs >= thresholdMs &&
                !dataStore.get(PauseListenHistoryKey, false)
        val shouldPersistHistory = alreadyPersistedForSession || reachedHistoryThreshold

        if (shouldPersistHistory) {
            ioScope.launch {
                val pendingResult =
                    pendingSession?.let { session ->
                        historyRecordingJobs[session.sessionToken]
                            ?.let { deferred ->
                                runCatching { deferred.await() }
                                    .onFailure(::reportException)
                                    .getOrNull()
                            }?.let { result ->
                                session.copy(
                                    eventId = result.eventId ?: session.eventId,
                                    remoteRegistered = session.remoteRegistered || result.remoteRegistered,
                                )
                            }
                            ?: session
                    }

                val fallbackMetadata = mediaItem.metadata
                val eventId =
                    pendingResult?.eventId ?: insertPlaybackHistoryEvent(
                        mediaId = mediaId,
                        playTimeMs = playbackStats.totalPlayTimeMs,
                        mediaMetadata = fallbackMetadata,
                    )

                if (eventId != null) {
                    runCatching {
                        database.updateEventPlayTime(eventId, playbackStats.totalPlayTimeMs)
                    }.onFailure(::reportException)
                }

                try {
                    database.withTransaction {
                        incrementTotalPlayTime(mediaId, playbackStats.totalPlayTimeMs)
                    }
                } catch (_: SQLException) {
                } catch (throwable: Throwable) {
                    reportException(throwable)
                }

                if (pendingResult?.remoteRegistered != true) {
                    registerRemotePlaybackHistory(mediaId)
                }
            }

            ioScope.launch {
                try {
                    val song =
                        database.song(mediaId).first()
                            ?: return@launch

                    val lbEnabled = dataStore.get(ListenBrainzEnabledKey, false)
                    val lbToken = dataStore.get(ListenBrainzTokenKey, "")
                    if (lbEnabled && !lbToken.isNullOrBlank()) {
                        val endMs = System.currentTimeMillis()
                        val startMs = endMs - playbackStats.totalPlayTimeMs
                        try {
                            ListenBrainzManager.submitFinished(this@MusicService, lbToken, song, startMs, endMs)
                        } catch (ie: Exception) {
                            Timber.tag("MusicService").v(ie, "ListenBrainz finished submit failed")
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private suspend fun submitListenBrainzPlayingNow(positionMs: Long = player.currentPosition) {
        val lbEnabled = withContext(Dispatchers.IO) { dataStore.get(ListenBrainzEnabledKey, false) }
        val lbToken = withContext(Dispatchers.IO) { dataStore.get(ListenBrainzTokenKey, "") }
        if (!lbEnabled || lbToken.isNullOrBlank()) return

        val mediaId = player.currentMediaItem?.mediaId
        val dbSong = if (mediaId != null) withContext(Dispatchers.IO) { database.song(mediaId).first() } else null
        val song = dbSong ?: player.currentMetadata?.let { createTransientSongFromMedia(it) } ?: return

        withContext(Dispatchers.IO) {
            ListenBrainzManager.submitPlayingNow(this@MusicService, lbToken, song, positionMs)
        }
    }

    // Create a transient Song object from current Player MediaMetadata when the DB doesn't have it.
    private fun createTransientSongFromMedia(media: MediaMetadata): Song {
        val songEntity =
            SongEntity(
                id = media.id,
                title = media.title,
                duration = media.duration,
                thumbnailUrl = media.thumbnailUrl,
                albumId = media.album?.id,
                albumName = media.album?.title,
                explicit = media.explicit,
                isLocal = media.id.isLocalMediaId(),
            )

        val artists =
            media.artists.map { artist ->
                ArtistEntity(
                    id = artist.id ?: "LA_unknown_${artist.name}",
                    name = artist.name,
                    thumbnailUrl = if (!artist.thumbnailUrl.isNullOrBlank()) artist.thumbnailUrl else media.thumbnailUrl,
                    isLocal = artist.id == null || artist.id.isLocalMediaId(),
                )
            }

        val album =
            media.album?.let { alb ->
                AlbumEntity(
                    id = alb.id,
                    playlistId = null,
                    title = alb.title,
                    year = null,
                    thumbnailUrl = media.thumbnailUrl,
                    themeColor = null,
                    songCount = 1,
                    duration = media.duration,
                    isLocal = media.id.isLocalMediaId(),
                )
            }

        return Song(
            song = songEntity,
            artists = artists,
            album = album,
            format = null,
        )
    }

    private inline fun <reified T> readPersistentObject(fileName: String): T? {
        val persistentFile = filesDir.resolve(fileName)
        if (!persistentFile.exists() || !persistentFile.isFile) return null

        return synchronized(persistentStateLock) {
            runCatching {
                persistentFile.inputStream().use { fis ->
                    ObjectInputStream(fis).use { input ->
                        val payload = input.readObject()
                        check(payload is T) { "Unexpected persistent payload type for $fileName" }
                        payload
                    }
                }
            }.onFailure {
                Timber.tag(TAG).w(it, "Failed to read persistent file: $fileName")
            }.getOrNull()
        }
    }

    private fun clearPersistedQueueFiles() {
        persistentSaveGeneration.incrementAndGet()
        synchronized(persistentStateLock) {
            listOf(
                PERSISTENT_QUEUE_FILE,
                PERSISTENT_PLAYER_STATE_FILE,
                PERSISTENT_AUTOMIX_FILE,
            ).forEach { fileName ->
                val persistentFile = filesDir.resolve(fileName)
                val tempFile = filesDir.resolve("$fileName.tmp")
                runCatching {
                    if (persistentFile.exists() && !persistentFile.delete()) {
                        Timber.tag(TAG).w("Failed to delete persistent file: $fileName")
                    }
                    if (tempFile.exists() && !tempFile.delete()) {
                        Timber.tag(TAG).w("Failed to delete temporary persistent file: $fileName")
                    }
                }.onFailure {
                    Timber.tag(TAG).w(it, "Failed to clear persistent file: $fileName")
                }
            }
        }
    }

    private fun writePersistentObject(
        fileName: String,
        payload: Serializable,
    ) {
        val persistentFile = filesDir.resolve(fileName)
        val tempFile = filesDir.resolve("$fileName.tmp")

        synchronized(persistentStateLock) {
            runCatching {
                FileOutputStream(tempFile).use { fos ->
                    ObjectOutputStream(fos).use { output ->
                        output.writeObject(payload)
                        output.flush()
                    }
                }

                if (!tempFile.renameTo(persistentFile)) {
                    if (persistentFile.exists() && !persistentFile.delete()) {
                        error("Could not replace $fileName")
                    }
                    if (!tempFile.renameTo(persistentFile)) {
                        error("Could not atomically move $fileName")
                    }
                }
            }.onFailure {
                runCatching { tempFile.delete() }
                reportException(it)
            }
        }
    }

    private fun loadPersistentUrlCache() {
        val cached = readPersistentObject<PersistPlaybackUrlCache>(PERSISTENT_URL_CACHE_FILE)
            ?: loadPersistentUrlCacheFromMediaStore()
            ?: return
        val now = System.currentTimeMillis()
        var loaded = 0
        cached.entries.forEach { entry ->
            if (entry.expiresAtMs > now) {
                playbackUrlCache[entry.mediaId] = AuthScopedCacheValue(
                    url = entry.url,
                    expiresAtMs = entry.expiresAtMs,
                    authFingerprint = entry.authFingerprint,
                    playbackClientLabel = entry.playbackClientLabel,
                    isYouTubeStream = entry.isYouTubeStream,
                )
                loaded++
            }
        }
        Timber.tag(TAG).d("loadPersistentUrlCache: loaded $loaded entries from disk")
    }

    private fun loadPersistentUrlCacheFromMediaStore(): PersistPlaybackUrlCache? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val resolver = contentResolver
            val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?"
            val selectionArgs = arrayOf(PERSISTENT_URL_CACHE_FILE, "Download/Hush/")
            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                resolver.openInputStream(uri)?.use { inputStream ->
                    java.io.ObjectInputStream(inputStream).use { ois ->
                        val payload = ois.readObject()
                        if (payload is PersistPlaybackUrlCache) {
                            Timber.tag(TAG).d("loadPersistentUrlCache: restored from MediaStore")
                            payload
                        } else null
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "loadPersistentUrlCache: MediaStore read failed")
            null
        }
    }

    private fun savePersistentUrlCache() {
        if (playbackUrlCache.isEmpty()) return
        val now = System.currentTimeMillis()
        val entries = playbackUrlCache.entries.mapNotNull { (mediaId, value) ->
            if (value.expiresAtMs > now) {
                CachedUrlEntry(
                    mediaId = mediaId,
                    url = value.url,
                    expiresAtMs = value.expiresAtMs,
                    authFingerprint = value.authFingerprint,
                    playbackClientLabel = value.playbackClientLabel,
                    isYouTubeStream = value.isYouTubeStream,
                )
            } else {
                null
            }
        }
        if (entries.isEmpty()) return
        val payload = PersistPlaybackUrlCache(entries)
        writePersistentObject(PERSISTENT_URL_CACHE_FILE, payload)
        savePersistentUrlCacheToMediaStore(payload)
        Timber.tag(TAG).d("savePersistentUrlCache: persisted ${entries.size} entries")
    }

    private fun savePersistentUrlCacheToMediaStore(payload: PersistPlaybackUrlCache) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val resolver = contentResolver
            val projection = arrayOf(MediaStore.Downloads._ID)
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?"
            val selectionArgs = arrayOf(PERSISTENT_URL_CACHE_FILE, "Download/Hush/")
            val existingUri = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                    android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                } else null
            }
            val uri = existingUri ?: run {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, PERSISTENT_URL_CACHE_FILE)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/Hush")
                }
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            } ?: return
            java.io.ByteArrayOutputStream().use { baos ->
                java.io.ObjectOutputStream(baos).use { oos ->
                    oos.writeObject(payload)
                    oos.flush()
                }
                resolver.openOutputStream(uri, "wt")?.use { outputStream ->
                    baos.writeTo(outputStream)
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "savePersistentUrlCacheToMediaStore: failed")
        }
    }

    private var urlCacheRefreshJob: kotlinx.coroutines.Job? = null
    private var urlCachePersistJob: kotlinx.coroutines.Job? = null

    private fun startUrlCacheRefreshJob() {
        urlCacheRefreshJob?.cancel()
        urlCacheRefreshJob = ioScope.launch {
            dataStore.data
                .map { prefs -> prefs[UrlCacheRefreshIntervalKey] ?: 0 }
                .distinctUntilChanged()
                .collectLatest(ioScope) { intervalIndex ->
                    val intervalHours = when (intervalIndex) {
                        1 -> 6
                        2 -> 12
                        3 -> 24
                        4 -> 168
                        else -> 0
                    }
                    if (intervalHours <= 0) {
                        Timber.tag(TAG).d("URL cache refresh: disabled")
                        return@collectLatest
                    }
                    val intervalMs = intervalHours.toLong() * 60 * 60 * 1000L
                    Timber.tag(TAG).d("URL cache refresh: enabled, interval=${intervalHours}h")
                    while (true) {
                        val jitter = (0..URL_CACHE_REFRESH_JITTER_MS).random()
                        delay(intervalMs + jitter)
                        runCatching { backgroundRefreshPlaybackUrls() }
                            .onFailure { Timber.tag(TAG).w(it, "URL cache refresh failed") }
                    }
                }
        }
        urlCachePersistJob?.cancel()
        urlCachePersistJob = ioScope.launch {
            while (true) {
                delay(30 * 60 * 1000L)
                savePersistentUrlCache()
            }
        }
    }

    private fun stopUrlCacheRefreshJob() {
        urlCacheRefreshJob?.cancel()
        urlCacheRefreshJob = null
        urlCachePersistJob?.cancel()
        urlCachePersistJob = null
    }

    private suspend fun backgroundRefreshPlaybackUrls() {
        val now = System.currentTimeMillis()
        val authFingerprint = playbackAuthFingerprint()
        val entriesToRefresh = playbackUrlCache.entries.filter { (_, value) ->
            value.authFingerprint == authFingerprint &&
                value.expiresAtMs > now &&
                value.expiresAtMs - now < 60 * 60 * 1000L
        }
        if (entriesToRefresh.isEmpty()) return
        Timber.tag(TAG).d("backgroundRefresh: ${entriesToRefresh.size} entries approaching expiry")
        for ((mediaId, oldEntry) in entriesToRefresh) {
            val still = playbackUrlCache[mediaId] ?: continue
            if (still.expiresAtMs > now + 60 * 60 * 1000L) continue
            try {
                val newData = resolveAndCachePlaybackUrl(mediaId)
                if (newData.isValidFor(authFingerprint)) {
                    playbackUrlCache[mediaId] = newData
                }
            } catch (cancellation: CancellationException) {
                // The job was cancelled (the interval changed, the cache was cleared, or
                // the service is going away). Swallowing this kept walking the remaining
                // entries, resolving URLs for a refresh that had already been abandoned.
                throw cancellation
            } catch (e: Exception) {
                // Silent before, which made a systematic failure here (every URL stale at
                // once, a broken auth fingerprint) invisible in the log.
                Timber.tag(TAG).w(e, "backgroundRefresh failed for %s", mediaId)
            }
            delay(200L)
        }
        savePersistentUrlCache()
    }

    private fun MediaItem.toPersistableMetadata(): app.hush.music.models.MediaMetadata? {
        val tagged = metadata
        if (tagged != null) return tagged

        val id =
            mediaId
                .trim()
                .ifBlank {
                    localConfiguration
                        ?.uri
                        ?.toString()
                        ?.trim()
                        .orEmpty()
                }.takeIf { it.isNotBlank() } ?: return null

        val title =
            mediaMetadata.title
                ?.toString()
                ?.trim()
                .takeIf { !it.isNullOrBlank() }
                ?: id

        val artistText =
            mediaMetadata.artist
                ?.toString()
                ?.trim()
                .takeIf { !it.isNullOrBlank() }
                ?: mediaMetadata.subtitle
                    ?.toString()
                    ?.trim()
                    .takeIf { !it.isNullOrBlank() }

        val artists =
            artistText
                ?.split(",")
                ?.mapNotNull { it.trim().takeIf(String::isNotBlank) }
                ?.map { name ->
                    app.hush.music.models.MediaMetadata
                        .Artist(id = null, name = name)
                }.orEmpty()

        val thumbnailUrl = mediaMetadata.artworkUri?.toString()
        val albumTitle =
            mediaMetadata.albumTitle
                ?.toString()
                ?.trim()
                .takeIf { !it.isNullOrBlank() }
        val album =
            albumTitle?.let { titleValue ->
                app.hush.music.models.MediaMetadata
                    .Album(id = titleValue, title = titleValue)
            }

        return app.hush.music.models.MediaMetadata(
            id = id,
            title = title,
            artists = artists,
            duration = -1,
            thumbnailUrl = thumbnailUrl,
            album = album,
            explicit = false,
            liked = false,
            likedDate = null,
            inLibrary = null,
        )
    }

    private suspend fun saveQueueToDisk() {
        val saveGeneration = persistentSaveGeneration.get()
        val snapshot =
            withContext(Dispatchers.Main.immediate) {
                if (
                    saveGeneration != persistentSaveGeneration.get() ||
                    isRestoringPersistentState ||
                    isHydratingRestoredQueue
                ) {
                    return@withContext null
                }

                val mediaItemsSnapshot = player.mediaItems.mapNotNull { it.toPersistableMetadata() }
                if (mediaItemsSnapshot.isEmpty()) return@withContext null

                val currentMediaItemIndex = player.currentMediaItemIndex
                val currentPosition = player.currentPosition
                val persistQueue =
                    currentQueue.toPersistQueue(
                        title = queueTitle,
                        items = mediaItemsSnapshot,
                        mediaItemIndex = currentMediaItemIndex,
                        position = currentPosition,
                    )
                val persistPlayerState =
                    PersistPlayerState(
                        playWhenReady = player.playWhenReady,
                        repeatMode = player.repeatMode,
                        shuffleModeEnabled = player.shuffleModeEnabled,
                        volume = playerVolume.value,
                        currentPosition = currentPosition,
                        currentMediaItemIndex = currentMediaItemIndex,
                        playbackState = player.playbackState,
                    )

                persistQueue to persistPlayerState
            } ?: return

        withContext(Dispatchers.IO) {
            if (saveGeneration != persistentSaveGeneration.get()) return@withContext
            writePersistentObject(PERSISTENT_QUEUE_FILE, snapshot.first)
            if (saveGeneration != persistentSaveGeneration.get()) return@withContext
            writePersistentObject(PERSISTENT_PLAYER_STATE_FILE, snapshot.second)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Drop the resume hook so a later verification cannot reach a dead service.
        app.hush.music.spotiflac.SpotiFLAutoVerifier.onSourceVerified = null
        // Same for the route watch: a gateway that starts answering again must not reach a player that
        // no longer exists.
        app.hush.music.spotiflac.SpotiFLACRouteWatch.onGatewayReachableAgain = null
        app.hush.music.spotiflac.SpotiFLACRouteWatch.onGatewayBlocked = null
        heldTrackRecoveryJob?.cancel()
        heldTrackRecoveryJob = null
        cancelUrlRefresh()
        cachedShimPackages = null
        cachedShimPackagesCheckedAt = 0L
        stopUrlCacheRefreshJob()
        // Use runCatching + runBlocking with a short timeout.
        // On Automotive OS the main thread may have a tighter ANR budget,
        // so we keep this under 1 s and never let it propagate.
        runCatching {
            runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                withTimeout(500L) { savePersistentUrlCache() }
            }
        }
        effectiveVolumeRampJob?.cancel()
        effectiveVolumeRampJob = null
        cancelCrossfade(resetVolume = false, resetPauseAtEnd = true)
        audioRouteRecoveryJob?.cancel()
        if (audioDeviceCallbackRegistered) {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            audioDeviceCallbackRegistered = false
        }
        unregisterBluetoothReceiver()
        unregisterWazeCommandReceiver()
        unregisterMuteRecoveryObserver()
        try {
            scope.launch { stopTogetherInternal() }
        } catch (_: Exception) {
        }
        abandonAudioFocus()
        try {
            releaseAudioEffects()
        } catch (_: Exception) {
        }
        if (::player.isInitialized) {
            try {
                if (dataStore.get(PersistentQueueKey, true) && player.mediaItemCount > 0) {
                    runCatching {
                        runBlocking {
                            withTimeout(500L) {
                                saveQueueToDisk()
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
            try {
                player.removeListener(this)
                sleepTimer?.let { player.removeListener(it) }
                player.release()
            } catch (_: Exception) {
            }
        }
        if (::mediaSession.isInitialized) {
            try {
                mediaSession.release()
            } catch (_: Exception) {
            }
        }
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        scopeJob.cancel()
    }

    override fun onBind(intent: Intent?): android.os.IBinder? {
        hasBoundClients = true
        cancelIdleStop()
        // MediaBrowserServiceCompat.onBind() only returns a non-null binder for
        // the standard "android.media.browse.MediaBrowserService" action.
        // Our own bindService() call uses a plain Intent(context, MusicService::class.java)
        // with no action, so super returns null. We must return the MusicBinder in
        // that case, or the app's own ServiceConnection.onServiceConnected gets null
        // and playerConnection is never set (blank home screen).
        //
        // For the standard MediaBrowser action (Android Auto, Waze shim, etc.),
        // super.onBind() already returns the correct IMediaBrowserService binder.
        val result = super.onBind(intent)
        if (result == null) {
            Timber.w("onBind: super returned null for action=%s, returning MusicBinder fallback", intent?.action)
        }
        if (::player.isInitialized && player.mediaItemCount > 0 && player.currentMediaItem != null) {
            currentMediaMetadata.value = player.currentMetadata
            scope.launch {
                delay(50)
                updateNotification()
            }
        }
        return result ?: binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hasBoundClients = false
        scheduleStopIfIdle()
        return super.onUnbind(intent)
    }

    override fun onRebind(intent: Intent?) {
        hasBoundClients = true
        cancelIdleStop()
        super.onRebind(intent)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        val stopMusicOnTaskClearEnabled = dataStore.get(StopMusicOnTaskClearKey, false)

        try {
            val state = togetherSessionState.value
            val isHostSessionActive =
                state is app.hush.music.together.TogetherSessionState.Hosting ||
                    state is app.hush.music.together.TogetherSessionState.HostingOnline ||
                    (
                        state is app.hush.music.together.TogetherSessionState.Joined &&
                            state.role is app.hush.music.together.TogetherRole.Host
                    )

            val isPlaybackInactive = player.playbackState == Player.STATE_IDLE || player.mediaItemCount == 0

            if (shouldStopServiceOnTaskRemoved(stopMusicOnTaskClearEnabled, isHostSessionActive, isPlaybackInactive)) {
                if (stopMusicOnTaskClearEnabled) {
                    runCatching { stopAndClearPlayback(clearPersistentState = true) }
                    stopForegroundAndSelf()
                    return
                }

                if (isHostSessionActive && isPlaybackInactive) {
                    runCatching { scope.launch { stopTogetherInternal() } }
                    runCatching { togetherSessionState.value = app.hush.music.together.TogetherSessionState.Idle }
                    stopSelf()
                    return
                }
            }

            if (dataStore.get(PersistentQueueKey, true) && player.mediaItemCount > 0) {
                runCatching {
                    // Same bounded path as the background save, so every close route
                    // persists the queue the same way.
                    persistQueueNow("task removed")
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return if (::mediaSession.isInitialized) {
            mediaSession
        } else {
            // Android Auto / Automotive binds before onCreate finishes creating
            // the session. Returning null causes the car display to crash.
            Timber.w(
                "onGetSession: mediaSession not yet initialized (controller=%s) — " +
                    "returning null; Android Auto may fail to connect",
                controllerInfo.packageName,
            )
            null
        }
    }

    private fun handleAlarmTrigger(intent: Intent) {
        scope.launch(Dispatchers.IO) {
            try {
                MusicAlarmScheduler.scheduleFromPreferences(this@MusicService)
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Failed to reschedule alarms after trigger")
            }
        }
        val playlistId = intent.getStringExtra(EXTRA_ALARM_PLAYLIST_ID).orEmpty()
        val alarmId = intent.getStringExtra(EXTRA_ALARM_ID).orEmpty()
        if (playlistId.isBlank()) {
            if (alarmId.isNotBlank()) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val alarms = MusicAlarmStore.load(this@MusicService)
                        val updated =
                            alarms.map { alarm ->
                                if (alarm.id == alarmId) {
                                    alarm.copy(enabled = false, nextTriggerAt = -1L)
                                } else {
                                    alarm
                                }
                            }
                        MusicAlarmScheduler.scheduleAll(this@MusicService, updated)
                    } catch (t: Throwable) {
                        Timber.tag(TAG).e(t, "Failed to disable alarm with invalid playlist")
                    }
                }
            }
            return
        }
        val randomSong = intent.getBooleanExtra(EXTRA_ALARM_RANDOM_SONG, false)
        scope.launch {
            try {
                val playlistSongs =
                    withContext(Dispatchers.IO) {
                        database.playlistSongs(playlistId).first()
                    }
                if (playlistSongs.isEmpty()) {
                    if (alarmId.isNotBlank()) {
                        withContext(Dispatchers.IO) {
                            val alarms = MusicAlarmStore.load(this@MusicService)
                            val updated =
                                alarms.map { alarm ->
                                    if (alarm.id == alarmId) {
                                        alarm.copy(enabled = false, nextTriggerAt = -1L)
                                    } else {
                                        alarm
                                    }
                                }
                            MusicAlarmScheduler.scheduleAll(this@MusicService, updated)
                        }
                    }
                    return@launch
                }
                val items = playlistSongs.map { it.song.toMediaItem() }
                val playlistName =
                    withContext(Dispatchers.IO) {
                        database.playlist(playlistId).first()?.title
                    }
                withContext(Dispatchers.IO) {
                    MusicAlarmScheduler.scheduleFromPreferences(this@MusicService)
                }

                val alarmItems =
                    if (randomSong) {
                        val firstIndex = Random.nextInt(items.size)
                        buildList(items.size) {
                            add(items[firstIndex])
                            items.forEachIndexed { index, item ->
                                if (index != firstIndex) add(item)
                            }
                        }
                    } else {
                        items
                    }

                player.stop()
                player.clearMediaItems()
                playQueue(
                    ListQueue(
                        title = playlistName,
                        items = alarmItems,
                        startIndex = 0,
                        position = 0L,
                    ),
                    playWhenReady = true,
                )
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Failed to start alarm playback")
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        ensureStartedAsForeground()
        when (intent?.action) {
            ACTION_ALARM_TRIGGER -> {
                handleAlarmTrigger(intent)
            }

            "app.hush.music.WIDGET_PLAY_PAUSE" -> {
                if (player.isPlaying) player.pause() else player.play()
            }

            "app.hush.music.WIDGET_SKIP_NEXT" -> {
                if (player.hasNextMediaItem()) {
                    player.seekToNext()
                    player.prepare()
                    player.play()
                }
            }

            "app.hush.music.WIDGET_SKIP_PREV" -> {
                if (player.hasPreviousMediaItem()) {
                    player.seekToPrevious()
                    player.prepare()
                    player.play()
                }
            }

            "app.hush.music.WAZE_COMMAND" -> {
                handleWazeCommand(intent)
            }
        }
        try {
            super.onStartCommand(intent, flags, startId)
        } catch (e: RuntimeException) {
            if (Build.VERSION.SDK_INT >= 31 && e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException") {
                Timber.w(e, "Foreground service start not allowed (user switch?)")
            } else {
                throw e
            }
        }
        return START_NOT_STICKY
    }

    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ) {
        val keepInForeground = startInForegroundRequired || hasResumablePlaybackNotification()
        if (keepInForeground) ensureStartedAsForeground()
        runCatching { super.onUpdateNotification(session, keepInForeground) }
            .onFailure { reportException(it) }
    }

    // ── Widget Support ────────────────────────────────────────────────────────────

    fun updateWidget() {
        widgetUpdater.update()
    }

    inner class MusicBinder : Binder() {
        val service: MusicService
            get() = this@MusicService
    }

    companion object {
        private const val WAZE_QUEUE_ITEM_ID = "app.hush.music.waze.QUEUE_ITEM_ID"
        private const val WAZE_PREFS = "waze_bridge"
        private const val WAZE_PREFS_QUEUE_REVISION = "queue_revision"

        /**
         * Headroom added to the persisted queue revision on service restart so a
         * shim that kept the old value never sees the counter go backwards.
         */
        private const val WAZE_REVISION_RESTART_STEP = 10_000L
        const val ACTION_ALARM_TRIGGER = "app.hush.music.action.ALARM_TRIGGER"
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_ALARM_PLAYLIST_ID = "extra_alarm_playlist_id"
        const val EXTRA_ALARM_RANDOM_SONG = "extra_alarm_random_song"

        internal fun shouldStopServiceOnTaskRemoved(
            stopMusicOnTaskClearEnabled: Boolean,
            isHostSessionActive: Boolean,
            isPlaybackInactive: Boolean,
        ): Boolean = (isHostSessionActive && isPlaybackInactive) || stopMusicOnTaskClearEnabled

        const val ROOT = "root"
        const val HOME = "home"
        const val HOME_QUICK_PICKS = "home_quick_picks"
        const val HOME_FORGOTTEN_FAVORITES = "home_forgotten_favorites"
        const val HOME_KEEP_LISTENING = "home_keep_listening"
        const val HOME_SUGGESTED_SONGS = "home_suggested_songs"
        const val HOME_MIXES_AND_RADIOS = "home_mixes_and_radios"
        const val QUICK_PICKS = "quick_picks"
        const val RECENT = "recent"
        const val LIKED = "liked"
        const val DOWNLOADED = "downloaded"
        const val SONG = "song"
        const val ARTIST = "artist"
        const val ALBUM = "album"
        const val PLAYLIST = "playlist"
        const val ONLINE_PLAYLIST = "online_playlist"

        private const val TAG = "MusicService"
        const val CHANNEL_ID = "music_channel_01"
        const val NOTIFICATION_ID = 888
        private const val TOGETHER_NOTIFICATION_CHANNEL_ID = "together_room_events"
        private const val TOGETHER_PARTICIPANT_NOTIFICATION_ID = 891
        const val ERROR_CODE_NO_STREAM = 1000001
        const val CHUNK_LENGTH = 8 * 1024 * 1024L
        val RETRYABLE_STREAM_RESPONSE_CODES = setOf(403, 404, 410, 416)
        /** How long to wait for the player before restoring without it. */
        private const val RESTORE_PLAYER_READY_TIMEOUT_MS = 10_000L

        /** Deadline after which transport commands stop waiting for the restore. */
        private const val RESTORE_COMPLETION_DEADLINE_MS = 8_000L

        /** Debounce for persisting a freshly built or mutated queue. */
        private const val QUEUE_PERSIST_DEBOUNCE_MS = 1_500L

        /** Ceiling for a lifecycle-driven persist; it must never block a teardown. */
        private const val PERSIST_NOW_TIMEOUT_MS = 2_000L

        const val PERSISTENT_QUEUE_FILE = "persistent_queue.data"
        const val PERSISTENT_AUTOMIX_FILE = "persistent_automix.data"
        const val PERSISTENT_PLAYER_STATE_FILE = "persistent_player_state.data"
        const val PERSISTENT_URL_CACHE_FILE = "persistent_url_cache.data"
        const val URL_CACHE_REFRESH_JITTER_MS = 30 * 60 * 1000L
        private const val MAX_PENDING_WAZE_COMMANDS = 5

        /** Above this the built Waze queue is not retained between metadata ticks. */
        private const val MAX_CACHED_WAZE_QUEUE_ITEMS = 2_000
        private const val SHIM_PACKAGE_CACHE_TTL_MS = 30_000L
        const val MAX_CONSECUTIVE_ERR = 5
        // Start ExoPlayer immediately and let URL prefetch run concurrently. Waiting here
        // serialized stream resolution before prepare(), which made the first track feel
        // noticeably slower after a cold service start. The data-source resolver still
        // awaits an in-flight prefetch when necessary, so this does not bypass resolution.
        private const val STARTUP_PREFETCH_WAIT_MS = 0L
        // SpotiFLAC resolution needs a realistic window rather than the 2.5s cap that
        // used to cancel every real attempt and silently push playback to YouTube. The
        // window is sized from the sweep's actual work by
        // SpotiFLACQualityCascade.sweepBudgetMs (which also owns the 45s floor).
        // A parked track is re-resolved once the verification burst drains, so the
        // resolve does not race the challenge window for the gateway. Bounded so a
        // stalled challenge cannot park the track forever.
        private const val HELD_RECOVERY_SETTLE_TIMEOUT_MS = 180_000L
        private const val HELD_RECOVERY_POLL_MS = 400L

        /**
         * Path segments of the directories SpotiFLAC stores fetched playback files in.
         *
         * Media3 records the URI a cached response came from, so this is how cached bytes are
         * attributed to SpotiFLAC rather than to YouTube. Two entries because the folder moved:
         * these files now live in a subfolder of the song-cache location the user picks in
         * Storage, and entries written before that move still name the old private path.
         */
        private val SPOTIFLAC_PLAYBACK_PATH_SEGMENTS =
            listOf(
                "/${app.hush.music.spotiflac.SpotiFLACPlaybackCache.CACHE_SUBDIRECTORY_NAME}/",
                "/spotiflac/playback/",
            )

        /** Upper bound on outstanding one-shot YouTube overrides - one per tap, no more. */
        private const val MAX_FORCE_YOUTUBE_ONCE = 32
        const val AUDIO_ROUTE_CHANGE_DEBOUNCE_MS = 350L
        const val AUDIO_EFFECT_ROUTE_REBIND_DELAY_MS = 200L
        const val AUDIO_ROUTE_RECOVERY_MIN_INTERVAL_MS = 1_500L
        const val AUDIO_ROUTE_RECOVERY_RESUME_DELAY_MS = 150L
        const val DEVICE_MUTE_PLAYBACK_NOTICE_INTERVAL_MS = 1_200L
        const val MIN_AUDIO_FOCUS_VOLUME_FACTOR = 0.2f
        const val MIN_AUDIO_NORMALIZATION_FACTOR = 0.25f
        const val MAX_AUDIO_NORMALIZATION_FACTOR = 1.414f
        const val EFFECTIVE_VOLUME_RAMP_FRAME_MS = 16L
        const val EFFECTIVE_VOLUME_RAMP_UP_MS = 350L
        const val EFFECTIVE_VOLUME_RAMP_DOWN_MS = 180L
        const val EFFECTIVE_VOLUME_RAMP_MIN_DELTA = 0.015f
        const val MIN_CROSSFADE_DURATION_MS = 500L
        const val CROSSFADE_END_GUARD_MS = 150L
        const val CROSSFADE_PREPARE_AHEAD_MS = 30_000L
        const val CROSSFADE_READY_TIMEOUT_MS = 5_000L
        const val CROSSFADE_HANDOFF_READY_TIMEOUT_MS = 5_000L
        const val CROSSFADE_HANDOFF_BUFFER_MS = 5_000L
        const val CROSSFADE_HANDOFF_SEEK_GUARD_MS = 750L
        const val CROSSFADE_MIN_BUFFER_BEFORE_START_MS = 5_000L
        const val CROSSFADE_MAX_BUFFER_BEFORE_START_MS = 12_500L
        const val PRIMARY_MIN_BUFFER_MS = 20_000
        const val PRIMARY_MAX_BUFFER_MS = 60_000
        const val PRIMARY_BUFFER_FOR_PLAYBACK_MS = 750
        const val PRIMARY_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2_500
        const val CROSSFADE_MIN_BUFFER_MS = 15_000
        const val CROSSFADE_MAX_BUFFER_MS = 45_000
        const val CROSSFADE_FRAME_MS = 32L
        const val MAX_SECONDARY_PLAYER_RETRIES = 3
        const val MIN_AUDIBLE_EFFECTIVE_VOLUME = 0.01f
        const val STUCK_MUTED_VOLUME_EPSILON = 0.001f
        const val AUDIBLE_PLAYBACK_VOLUME_CHECK_MS = 2_000L
        private const val ArchiveTuneExtractorCacheFingerprintPrefix = "archivetune_extractor:"
        private const val ArchiveTuneExtractorCacheTtlMs = 5 * 60 * 1000L
    }
}
