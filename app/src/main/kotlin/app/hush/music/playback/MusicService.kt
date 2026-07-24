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
import android.net.Uri
import android.os.Binder
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
import app.hush.music.constants.DeviceMutePlaybackRecoveryVolumeKey
import app.hush.music.constants.EnableLastFMScrobblingKey
import app.hush.music.constants.EnableSaavnStreamingKey
import app.hush.music.constants.ParallelSourceFetchKey
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
import app.hush.music.constants.StopMusicOnTaskClearKey
import app.hush.music.constants.TogetherClientIdKey
import app.hush.music.constants.WakelockKey
import app.hush.music.constants.WazeTargetApp
import app.hush.music.constants.YtmSyncKey
import app.hush.music.db.MusicDatabase
import app.hush.music.db.entities.AlbumEntity
import app.hush.music.db.entities.ArtistEntity
import app.hush.music.db.entities.Event
import app.hush.music.db.entities.FormatEntity
import app.hush.music.db.entities.LyricsEntity
import app.hush.music.db.entities.RelatedSongMap
import app.hush.music.db.entities.Song
import app.hush.music.db.entities.SongEntity
import app.hush.music.di.DownloadCache
import app.hush.music.di.PlayerCache
import app.hush.music.extensions.SilentHandler
import app.hush.music.extensions.collect
import app.hush.music.extensions.collectLatest
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
import app.hush.music.models.MediaMetadata
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
import app.hush.music.utils.isLowDataModeActive
import app.hush.music.utils.resolveEffectiveAudioQuality
import app.hush.music.utils.reportException
import app.hush.music.utils.retryWithoutPlaybackLoginContext
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
    private var pendingWazeCommand: Intent? = null
    private var wazeColdStartRecoveryJob: Job? = null
    private var lastWazeMetadataUpdateTime = 0L
    private var wazePositionJob: Job? = null
    private val wazeSnapshotSequence = AtomicLong(0L)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakelockEnabled = false
    private var audioDeviceCallbackRegistered = false
    private var audioRouteRecoveryJob: Job? = null
    private var audiblePlaybackRecoveryJob: Job? = null
    private var lastAudioOutputDeviceSignature: String? = null
    private var lastAudioRouteRecoveryRealtimeMs = 0L

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

    private lateinit var connectivityManager: ConnectivityManager
    lateinit var connectivityObserver: NetworkConnectivityObserver
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
    private var lastLoginRecoveryPrompt: Pair<String, Long>? = null
    private val playbackStreamRecoveryTracker = PlaybackStreamRecoveryTracker()
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
    private val pendingHistoryFinalizations = mutableMapOf<String, MutableList<PendingHistoryFinalization>>()
    private val historyRecordingJobs = ConcurrentHashMap<Long, kotlinx.coroutines.Deferred<ImmediateHistoryResult>>()

    val currentMediaMetadata = MutableStateFlow<app.hush.music.models.MediaMetadata?>(null)
    val activePlaybackClientLabel = MutableStateFlow<String?>(null)
    val queueRestoreCompleted = MutableStateFlow(false)
    val infiniteQueueLoading = MutableStateFlow(false)
    private val playerInitialized = MutableStateFlow(false)
    private val currentSong =
        currentMediaMetadata
            .flatMapLatest { mediaMetadata ->
                database.song(mediaMetadata?.id)
            }.flowOn(Dispatchers.IO)
            .stateIn(scope, SharingStarted.Lazily, null)
    private val currentFormat =
        currentMediaMetadata
            .flatMapLatest { mediaMetadata ->
                database.format(mediaMetadata?.id)
            }.flowOn(Dispatchers.IO)

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
                    SleepTimer(scope, this).also { timer ->
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
            Timber.e(it, "Failed to get AudioManager")
            stopSelf()
            return
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
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, android.os.Handler(mainLooper))
        audioDeviceCallbackRegistered = true
        lastAudioOutputDeviceSignature = currentAudioOutputDeviceSignature()

        mediaLibrarySessionCallback.apply {
            toggleLike = ::toggleLike
            toggleStartRadio = ::toggleStartRadio
            toggleLibrary = ::toggleLibrary
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

        wazeCommandReceiver.attachService(this)
        val wazeFilter = IntentFilter("app.hush.music.WAZE_COMMAND")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wazeCommandReceiver, wazeFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(wazeCommandReceiver, wazeFilter)
        }
        wazeReceiverRegistered = true

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
            Timber.e(it, "Failed to get ConnectivityManager")
            stopSelf()
            return
        }
        connectivityObserver = NetworkConnectivityObserver(this)

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
            .map { it[EnableSaavnStreamingKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) { enabled ->
                val wasEnabled = saavnStreamingEnabled
                saavnStreamingEnabled = enabled
                if (enabled && !wasEnabled) {
                    clearSaavnIncompatiblePlaybackCache()
                }
            }

        dataStore.data
            .map { it[PrimaryAudioScraperKey] ?: "" }
            .distinctUntilChanged()
            .collectLatest(scope) { scraper ->
                clearSaavnIncompatiblePlaybackCache()
                playbackUrlCache.clear()
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
                desiredEqSettings.value = settings
                applyEqSettingsToEffects(settings)
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

        scope.launch(Dispatchers.IO) {
            runCatching {
                if (dataStore.get(PersistentQueueKey, true)) {
                    playerInitialized.first { it }
                    val persistedQueue = readPersistentObject<PersistQueue>(PERSISTENT_QUEUE_FILE)
                    val persistedPlayerState = readPersistentObject<PersistPlayerState>(PERSISTENT_PLAYER_STATE_FILE)

                    if (persistedQueue != null || persistedPlayerState != null) {
                        isRestoringPersistentState = true
                    }

                    var restoredQueue = false
                    try {
                        persistedQueue?.let { queue ->
                            restorePersistentQueue(queue)
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
            withContext(Dispatchers.Main) {
                queueRestoreCompleted.value = true
                pendingWazeCommand?.let {
                    pendingWazeCommand = null
                    handleWazeCommand(it)
                }
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

    private suspend fun restorePersistentQueue(persistedQueue: PersistQueue) {
        cancelRestoredQueueHydration()
        val hydrationGeneration = restoredQueueHydrationGeneration.incrementAndGet()
        isHydratingRestoredQueue = true

        val itemQueue = persistedQueue.toQueue()
        val continuationQueue = persistedQueue.toContinuationQueue()
        val hideExplicit = dataStore.get(HideExplicitKey, false)
        val hideVideo = dataStore.get(HideVideoKey, false)
        val initialStatus =
            itemQueue
                .getInitialStatus()
                .filterExplicit(hideExplicit)
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

            player.setMediaItems(
                initialChunk,
                relativeIndex,
                initialStatus.position,
            )
            player.prepare()
            player.playWhenReady = false
            currentMediaMetadata.value = player.currentMetadata
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
        player.prepare()
        player.seekTo(mediaItemIndex, playbackPosition)
        delay(AUDIO_ROUTE_RECOVERY_RESUME_DELAY_MS)

        if (
            shouldResumePlayback &&
            player.currentMediaItem != null &&
            player.playbackState != Player.STATE_ENDED &&
            requestAudioFocus()
        ) {
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

    private fun scheduleCrossfade() {
        if (!::player.isInitialized) return
        crossfadeTriggerJob?.cancel()
        crossfadeTriggerJob = null

        if (isCrossfading) return
        if (!player.playWhenReady) {
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
                        val adjustedDuration =
                            (duration - player.currentPosition - CROSSFADE_END_GUARD_MS)
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
    private var saavnStreamingEnabled = false

    private fun isSaavnStreamingEnabled(): Boolean {
        val primaryScraper = PreferenceStore.get(PrimaryAudioScraperKey)
        return when (primaryScraper) {
            "JIOSAAVN" -> true
            "YOUTUBE" -> false
            else -> PreferenceStore.get(EnableSaavnStreamingKey) ?: false
        }
    }

    private fun isParallelSourceFetchEnabled(): Boolean {
        return dataStore.get(ParallelSourceFetchKey, false)
    }

    private suspend fun saavnHintsFor(mediaId: String): SaavnPlaybackResolver.PlaybackHints? {
        currentMediaMetadata.value?.takeIf { it.id == mediaId }?.let {
            return SaavnPlaybackResolver.hintsFrom(it)
        }
        return withContext(Dispatchers.Main) {
            val mediaItem =
                player.findNextMediaItemById(mediaId)
                    ?: player.currentMediaItem?.takeIf { it.mediaId == mediaId }
            mediaItem?.metadata?.let { SaavnPlaybackResolver.hintsFrom(it) }
                ?: mediaItem?.mediaMetadata?.let { SaavnPlaybackResolver.hintsFrom(it) }
        }
    }

    private fun cachedPlaybackUrl(mediaId: String): AuthScopedCacheValue? {
        val authFingerprint = playbackAuthFingerprint()
        val saavnEnabled = isSaavnStreamingEnabled()
        return playbackUrlCache[mediaId]?.takeIf { cached ->
            cached.isValidFor(
                authFingerprint = authFingerprint,
                minimumRemainingMs = YTPlayerUtils.STREAM_URL_EXPIRY_SAFETY_MS,
            ) && (!cached.isSaavnStream || saavnEnabled)
        }?.also { cached ->
            publishPlaybackClientLabel(mediaId, cached.playbackClientLabel)
        }
    }

    private fun publishPlaybackClientLabel(
        mediaId: String,
        label: String?,
    ) {
        if (label.isNullOrBlank()) return
        val published = mediaId to label
        if (lastPublishedPlaybackClient == published) return
        scope.launch {
            val isCurrentTrack =
                withContext(Dispatchers.Main) {
                    currentMediaMetadata.value?.id == mediaId ||
                        player.currentMediaItem?.mediaId == mediaId
                }
            if (!isCurrentTrack) return@launch
            lastPublishedPlaybackClient = published
            activePlaybackClientLabel.value = label
        }
    }

    private suspend fun resolveAndCachePlaybackUrl(mediaId: String): AuthScopedCacheValue {
        cachedPlaybackUrl(mediaId)?.let { return it }

        val lowDataModeActive = isLowDataModeActive()
        val effectiveAudioQuality = resolveEffectiveAudioQuality(audioQuality, lowDataModeActive)
        val networkMeteredHint =
                if (lowDataModeActive && audioQuality != AudioQuality.HIGHEST) {
                    true
                } else {
                    null
                }
        val hiResLosslessSelected = activeStreamClient == PlayerStreamClient.HI_RES_LOSSLESS
        val parallelFetch = isParallelSourceFetchEnabled()
        val playbackData =
                if (hiResLosslessSelected) {
                    resolveHiResLosslessPlayback(mediaId).recoverCatching { youtubeFailure ->
                        if (youtubeFailure is YTPlayerUtils.BotDetectionPlaybackException) {
                            throw youtubeFailure
                        }
                        retryWithoutPlaybackLoginContext {
                            YTPlayerUtils.playerResponseForPlayback(
                                videoId = mediaId,
                                audioQuality = effectiveAudioQuality,
                                connectivityManager = connectivityManager,
                                preferredStreamClient = PlayerStreamClient.WEB_REMIX,
                                networkMetered = networkMeteredHint,
                                context = this@MusicService,
                                trySaavnFirst = false,
                            )
                        }.getOrThrow()
                    }.getOrThrow()
                } else {
                    val saavnEnabled = isSaavnStreamingEnabled()
                    val shouldTrySaavn = saavnEnabled || parallelFetch
                    retryWithoutPlaybackLoginContext {
                        YTPlayerUtils.playerResponseForPlayback(
                            videoId = mediaId,
                            audioQuality = effectiveAudioQuality,
                            connectivityManager = connectivityManager,
                            preferredStreamClient = activeStreamClient,
                            networkMetered = networkMeteredHint,
                            context = this@MusicService,
                            trySaavnFirst = saavnEnabled,
                            saavnHints = if (shouldTrySaavn) saavnHintsFor(mediaId) else null,
                            parallelFetch = parallelFetch,
                        )
                    }.getOrThrow()
                }

        applyResolvedPlaybackData(mediaId, playbackData)

        val cacheValue =
                AuthScopedCacheValue(
                    url = playbackData.streamUrl,
                    expiresAtMs = System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L),
                    authFingerprint = playbackData.authFingerprint,
                    playbackClientLabel = playbackData.playbackClientLabel,
                    isSaavnStream = playbackData.isSaavnStream,
                )
        playbackUrlCache[mediaId] = cacheValue
        return cacheValue
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

    fun clearSaavnIncompatiblePlaybackCache() {
        playbackUrlCache.clear()
        cancelPlaybackUrlPrefetches()
        SaavnPlaybackResolver.clearCaches()
        lastPublishedPlaybackClient = null
    }

    private fun prefetchNextTracks() {
        val count = dataStore.get(PrefetchCountKey, 2).coerceIn(0, 4)
        if (count <= 0) return

        val currentIndex = player.currentMediaItemIndex
        if (currentIndex == C.INDEX_UNSET) return

        val startIdx = currentIndex + 1
        val endIdx = (startIdx + count).coerceAtMost(player.mediaItemCount)

        for (idx in startIdx until endIdx) {
            val mediaId = player.getMediaItemAt(idx).mediaId.orEmpty()
            if (mediaId.isNotBlank() && !mediaId.isLocalMediaId()) {
                startPlaybackUrlPrefetch(mediaId)
            }
        }
    }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, filter)
        }
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
        consecutivePlaybackErr += 2
        val nextWindowIndex = player.nextMediaItemIndex

        if (consecutivePlaybackErr <= MAX_CONSECUTIVE_ERR && nextWindowIndex != C.INDEX_UNSET) {
            player.seekTo(nextWindowIndex, C.TIME_UNSET)
            player.prepare()
            player.play()
            return
        }

        player.pause()
        consecutivePlaybackErr = 0
    }

    private fun stopOnError() {
        player.pause()
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

        if (!playbackStreamRecoveryTracker.registerRetryAttempt(mediaId)) {
            return false
        }

        Timber.tag("MusicService").i(
            "Retrying playback for %s after stream HTTP %d from %s failed",
            mediaId,
            responseException.responseCode,
            requestProfile.variantLabel,
        )
        player.prepare()
        return true
    }

    private fun currentPlaybackSongLiked(): Boolean {
        val mediaId =
            currentMediaMetadata.value?.id?.takeIf { it.isNotBlank() }
                ?: player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
                ?: return currentSong.value?.song?.liked == true

        return database.getSongByIdBlocking(mediaId)?.song?.liked == true
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
                        ).setIconResId(if (player.shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle)
                        .setSessionCommand(CommandToggleShuffle)
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
                        queue
                            .getInitialStatus()
                            .filterExplicit(dataStore.get(HideExplicitKey, false))
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
        cancelPlaybackUrlPrefetches()

        queue.preloadItem?.id?.takeIf { it.isNotBlank() }?.let(::startPlaybackUrlPrefetch)

        val permanentShuffle = dataStore.get(PermanentShuffleKey, false)
        if (!permanentShuffle) {
            player.shuffleModeEnabled = false
        }

        clearAutomix()
        autoAddedMediaIds.clear()
        if (queue.preloadItem != null) {
            player.setMediaItem(queue.preloadItem!!.toMediaItem())
            player.prepare()
            player.playWhenReady = playWhenReady
        }
        scope.launch(SilentHandler) {
            val hideExplicit = dataStore.get(HideExplicitKey, false)
            val hideVideo = dataStore.get(HideVideoKey, false)
            val autoLoadMoreEnabled = dataStore.get(AutoLoadMoreKey, true)
            var initialStatus =
                withContext(Dispatchers.IO) {
                    queue
                        .getInitialStatus()
                        .filterExplicit(hideExplicit)
                        .filterVideo(hideVideo)
                }
            if (!autoLoadMoreEnabled && queue.shouldExpandToFullQueueWhenAutoLoadMoreDisabled() && queue.hasNextPage()) {
                val expandedItems = initialStatus.items.toMutableList()
                var pagesLoaded = 0
                while (queue.hasNextPage() && pagesLoaded < 200) {
                    pagesLoaded++
                    val nextItems =
                        withContext(Dispatchers.IO) {
                            queue
                                .nextPage()
                                .filterExplicit(hideExplicit)
                                .filterVideo(hideVideo)
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
            if (queue.preloadItem != null) {
                queue.preloadItem!!.id?.takeIf { it.isNotBlank() }?.let(::startPlaybackUrlPrefetch)
                val preloadId = queue.preloadItem!!.id.orEmpty()
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
                items.getOrNull(index)?.mediaId?.takeIf { it.isNotBlank() }?.let(::startPlaybackUrlPrefetch)
                player.setMediaItems(items, index, initialStatus.position)
                player.prepare()
                player.playWhenReady = playWhenReady
                if (player.shuffleModeEnabled) {
                    applyCurrentFirstShuffleOrder()
                }
            }
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
            val initialStatus =
                withContext(Dispatchers.IO) {
                    radioQueue
                        .getInitialStatus()
                        .filterExplicit(
                            dataStore.get(HideExplicitKey, false),
                        ).filterVideo(dataStore.get(HideVideoKey, false))
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

    fun clearAutomix() {
        autoAddedMediaIds.clear()
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
            try {
                val radioQueue = YouTubeQueue(WatchEndpoint(videoId = currentMediaId), followAutomixPreview = true)
                val status = withContext(Dispatchers.IO) { radioQueue.getInitialStatus() }

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
        database.query {
            currentSong.value?.let {
                update(it.song.toggleLibrary())
            }
        }
    }

    fun toggleLike() {
        val metadata = currentMediaMetadata.value ?: return
        val mediaId = metadata.id.trim()
        if (mediaId.isBlank()) return

        ioScope.launch {
            val updatedSong =
                database.withTransaction {
                    val existing = getSongById(mediaId)?.song
                    val baseSong =
                        existing
                            ?: run {
                                database.insert(metadata.copy(duration = metadata.duration.takeIf { it > 0 } ?: -1))
                                getSongById(mediaId)?.song
                            }
                            ?: return@withTransaction null

                    val toggled = baseSong.toggleLike()
                    update(toggled)
                    toggled
                } ?: return@launch

            withContext(Dispatchers.Main.immediate) {
                updateNotification()
            }

            syncUtils.likeSong(updatedSong)

            if (!mediaId.isLocalMediaId()) {
                ioScope.launch { recoverSong(mediaId) }
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

    private fun ensureAudioEffects(sessionId: Int) {
        if (sessionId <= 0) return
        if (audioEffectsSessionId == sessionId && equalizer != null) return

        releaseAudioEffects()
        audioEffectsSessionId = sessionId

        equalizer = runCatching { Equalizer(0, sessionId) }.getOrNull()
        bassBoost = runCatching { BassBoost(0, sessionId) }.getOrNull()
        virtualizer = runCatching { Virtualizer(0, sessionId) }.getOrNull()
        loudnessEnhancer = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()

        equalizer?.let(::updateEqCapabilitiesFromEffect)
        applyEqSettingsToEffects(desiredEqSettings.value)
    }

    private fun applyEqSettingsToEffects(settings: EqSettings) {
        val eq = equalizer ?: return
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

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        super.onMediaItemTransition(mediaItem, reason)

        activePlaybackClientLabel.value = null
        lastPublishedPlaybackClient = null

        mediaItem?.mediaId
            ?.takeIf { it.isNotBlank() && !it.isLocalMediaId() }
            ?.let(::startPlaybackUrlPrefetch)

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
        publishWazePlaybackSnapshot(force = true)

        mediaItem?.mediaId?.takeIf { it.isNotBlank() && !it.isLocalMediaId() }?.let { mediaId ->
            cachedPlaybackUrl(mediaId)?.playbackClientLabel?.let { label ->
                publishPlaybackClientLabel(mediaId, label)
            }
        }

        mediaItem?.let { item ->
            ensureNotificationArtworkUri(item)
            if (isSaavnStreamingEnabled()) {
                playbackUrlCache[item.mediaId]?.takeIf { !it.isSaavnStream }?.let {
                    playbackUrlCache.remove(item.mediaId)
                }
            }
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
                    queue
                        .nextPage()
                        .filterExplicit(
                            dataStore.get(HideExplicitKey, false),
                        ).filterVideo(dataStore.get(HideVideoKey, false))
                if (
                    queueGeneration == playQueueGeneration.get() &&
                    currentQueue === queue &&
                    player.playbackState != STATE_IDLE
                ) {
                    player.addMediaItems(mediaItems.drop(1))
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
    )

    private fun publishWazePlaybackSnapshot(
        force: Boolean = false,
    ) {
        val shimPackages = mutableListOf<String>()
        for (app in WazeTargetApp.entries) {
            try {
                val appInfo = packageManager.getApplicationInfo(
                    app.packageName,
                    PackageManager.GET_META_DATA,
                )
                val isCurrentShim = appInfo.metaData?.getBoolean("app.hush.music.waze.SHIM", false) == true
                val isLegacyShim = appInfo.loadLabel(packageManager).toString() == when (app) {
                    WazeTargetApp.SPOTIFY -> "Hush (Spotify)"
                    WazeTargetApp.YOUTUBE_MUSIC -> "Hush (YouTube Music)"
                    WazeTargetApp.DEEZER -> "Hush (Deezer)"
                }
                if (isCurrentShim || isLegacyShim) {
                    shimPackages.add(app.packageName)
                }
            } catch (_: PackageManager.NameNotFoundException) {}
        }
        if (shimPackages.isEmpty()) return

        val now = SystemClock.elapsedRealtime()
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
            timestampMs = now,
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
                    putExtra("sequence_number", snapshot.sequenceNumber)
                    putExtra("timestamp_elapsed_realtime", snapshot.timestampMs)
                    // Retain the resolved state for older installed shims.
                    putExtra("state", resolveWazePlaybackState(snapshot))
                    putExtra("artwork_url", snapshot.artworkUrl)
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
        player.prepare()
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
        if (player.mediaItemCount > 0) {
            wazeCommandReceiver.onReceive(this, intent)
        } else if (queueRestoreCompleted.value) {
            wazeColdStartRecovery(intent)
        } else {
            pendingWazeCommand = intent
            Timber.tag(TAG).d("Deferred Waze command: ${intent.getStringExtra("command")}")
        }
    }

    private fun wazeColdStartRecovery(intent: Intent) {
        pendingWazeCommand = intent
        if (wazeColdStartRecoveryJob?.isActive == true) return

        wazeColdStartRecoveryJob = scope.launch(Dispatchers.IO) {
            try {
                val persistedQueue = readPersistentObject<PersistQueue>(PERSISTENT_QUEUE_FILE)
                val persistedPlayerState = readPersistentObject<PersistPlayerState>(PERSISTENT_PLAYER_STATE_FILE)
                withContext(Dispatchers.Main) {
                    val pendingCommand = pendingWazeCommand
                    pendingWazeCommand = null
                    if (persistedQueue != null) {
                        restorePersistentQueue(persistedQueue)
                        persistedPlayerState?.let {
                            restorePersistentPlayerState(it, restoredQueue = true)
                        }
                    }
                    pendingCommand?.let { wazeCommandReceiver.onReceive(this@MusicService, it) }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to restore queue for Waze command")
            } finally {
                wazeColdStartRecoveryJob = null
            }
        }
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
        if (
            (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_READY) ||
            (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && player.isPlaying)
        ) {
            playbackStreamRecoveryTracker.onPlaybackRecovered(currentMediaId)
            ensureAudiblePlaybackVolume("player_event")
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

        if (!isLocalMedia && !isFullyCachedMedia && (!isNetworkConnected.value || isConnectionError)) {
            waitOnNetworkError()
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
            val cacheDir = StorageLocationRepository.cacheDirectory(this@MusicService, StorageFolderKind.SONG_CACHE)
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

    private fun createDataSourceFactory(): DataSource.Factory {
        val cachedFactory =
            ResolvingDataSource.Factory(createCacheDataSource()) { dataSpec ->
                resolvePlaybackDataSpec(
                    dataSpec = dataSpec,
                    allowCacheShortCircuit = true,
                )
            }
        val directFactory = createResolvedUpstreamDataSourceFactory()

        return DataSource.Factory {
            SchemeRoutingDataSource(
                cachedFactory = cachedFactory,
                directFactory = directFactory,
            )
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
            resolvePlaybackDataSpec(
                dataSpec = dataSpec,
                allowCacheShortCircuit = false,
            )
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
            resolvePlaybackDataSpec(
                dataSpec = dataSpec,
                allowCacheShortCircuit = false,
            )
        return if (resolvedDataSpec.uri == uri) {
            mediaItem
        } else {
            mediaItem
                .buildUpon()
                .setUri(resolvedDataSpec.uri)
                .build()
        }
    }

    private fun resolvePlaybackDataSpec(
        dataSpec: DataSpec,
        allowCacheShortCircuit: Boolean,
    ): DataSpec {
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

        // Always prefer locally cached/downloaded content over remote streaming.
        // Only use remote playback when no cache exists.
        val cachedDataSpec = resolveLocalPlaybackDataSpecIfAvailable(
            dataSpec = dataSpec,
            mediaId = mediaId,
            knownContentLength = knownContentLength,
        )
        if (cachedDataSpec != null) {
            scope.launch(Dispatchers.IO) { recoverSong(mediaId, isOfflinePlayback = true) }
            return cachedDataSpec
        }
        val effectiveAudioQuality = resolveEffectiveAudioQuality(audioQuality, lowDataModeActive)
        val preferExternalExtractorOnly =
            activeStreamClient == PlayerStreamClient.ARCHIVETUNE_EXTRACTOR &&
                (lowDataModeActive || effectiveAudioQuality == AudioQuality.LOW)

        if (preferExternalExtractorOnly) {
            return resolveArchiveTuneExtractorDataSpec(
                dataSpec = dataSpec,
                mediaId = mediaId,
                audioQuality = effectiveAudioQuality,
            )
        }

        cachedPlaybackUrl(mediaId)?.let { cached ->
            scope.launch(Dispatchers.IO) { recoverSong(mediaId, isOfflinePlayback = true) }
            return buildResolvedStreamDataSpec(
                dataSpec = dataSpec,
                streamUrl = cached.url,
                knownContentLength = knownContentLength,
                mimeType = storedFormat?.mimeType,
                isSaavnStream = cached.isSaavnStream,
            )
        }

        runBlocking(Dispatchers.IO) {
            awaitPlaybackUrlPrefetch(mediaId, timeoutMs = 1_500L)
        }
        cachedPlaybackUrl(mediaId)?.let { cached ->
            scope.launch(Dispatchers.IO) { recoverSong(mediaId, isOfflinePlayback = true) }
            return buildResolvedStreamDataSpec(
                dataSpec = dataSpec,
                streamUrl = cached.url,
                knownContentLength = knownContentLength,
                mimeType = storedFormat?.mimeType,
                isSaavnStream = cached.isSaavnStream,
            )
        }

        // A queued lookahead must not delay the track the user selected.
        cancelPlaybackUrlPrefetch(mediaId)

        val networkMeteredHint =
            if (lowDataModeActive && audioQuality != AudioQuality.HIGHEST) {
                true
            } else {
                null
            }

        val saavnEnabled = isSaavnStreamingEnabled()
        val parallelFetch = isParallelSourceFetchEnabled()
        val playbackData =
            try {
                runBlocking(Dispatchers.IO) {
                    withContext(NonCancellable) {
                        retryWithoutPlaybackLoginContext {
                            YTPlayerUtils.playerResponseForPlayback(
                                videoId = mediaId,
                                audioQuality = effectiveAudioQuality,
                                connectivityManager = connectivityManager,
                                preferredStreamClient = activeStreamClient,
                                networkMetered = networkMeteredHint,
                                fastResolution = true,
                                context = this@MusicService,
                                trySaavnFirst = saavnEnabled,
                                saavnHints = if (saavnEnabled) saavnHintsFor(mediaId) else null,
                                parallelFetch = parallelFetch,
                            )
                        }.getOrThrow()
                    }
                }
            } catch (throwable: Throwable) {
                if (activeStreamClient == PlayerStreamClient.ARCHIVETUNE_EXTRACTOR &&
                    throwable !is YTPlayerUtils.BotDetectionPlaybackException
                ) {
                    return resolveArchiveTuneExtractorDataSpec(
                        dataSpec = dataSpec,
                        mediaId = mediaId,
                        audioQuality = effectiveAudioQuality,
                    )
                }
                throw mapStreamResolutionFailure(throwable, mediaId)
            }

        applyResolvedPlaybackData(mediaId, playbackData)
        if (!lowDataModeActive) {
            playbackUrlCache[mediaId] =
                AuthScopedCacheValue(
                    url = playbackData.streamUrl,
                    expiresAtMs = System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L),
                    authFingerprint = playbackData.authFingerprint,
                    playbackClientLabel = playbackData.playbackClientLabel,
                    isSaavnStream = playbackData.isSaavnStream,
                )
        }
        scope.launch(Dispatchers.IO) { recoverSong(mediaId) }
        return buildResolvedStreamDataSpec(
            dataSpec = dataSpec,
            streamUrl = playbackData.streamUrl,
            knownContentLength = knownContentLength ?: playbackData.format.contentLength,
            mimeType = playbackData.format.mimeType,
            isSaavnStream = playbackData.isSaavnStream,
        )
    }

    private fun buildResolvedStreamDataSpec(
        dataSpec: DataSpec,
        streamUrl: String,
        knownContentLength: Long?,
        mimeType: String?,
        isSaavnStream: Boolean = false,
    ): DataSpec {
        val resolvedDataSpec = dataSpec.withUri(streamUrl.toUri())
        if (isSaavnStream) return resolvedDataSpec
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

    private fun resolveLocalPlaybackDataSpecIfAvailable(
        dataSpec: DataSpec,
        mediaId: String,
        knownContentLength: Long?,
    ): DataSpec? {
        val effectiveContentLength =
            knownContentLength ?: inferCachedContentLength(mediaId, dataSpec.position)

        resolveCachedDataSpec(
            dataSpec = dataSpec,
            mediaId = mediaId,
            knownContentLength = effectiveContentLength,
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
            downloadCache.isCached(mediaId, dataSpec.position, requiredCachedLength) ||
                playerCache.isCached(mediaId, dataSpec.position, requiredCachedLength)
        return if (isFullyCached) dataSpec else null
    }

    private fun hasFullyDownloadedLocalPlayback(mediaId: String): Boolean {
        val length = inferCachedContentLength(mediaId, 0L) ?: return false
        return downloadCache.isCached(mediaId, 0L, length)
    }

    private fun inferCachedContentLength(
        mediaId: String,
        position: Long,
    ): Long? {
        val spans =
            runCatching { downloadCache.getCachedSpans(mediaId).toList() }.getOrNull().orEmpty() +
                runCatching { playerCache.getCachedSpans(mediaId).toList() }.getOrNull().orEmpty()
        if (spans.isEmpty()) return null
        val continuousFromStart = getContinuousCachedLength(mediaId, 0L, Long.MAX_VALUE)
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
                    inferCachedContentLength(mediaId, dataSpec.position) ?: return null
                }
            }

        val cachedLength =
            getContinuousCachedLength(
                mediaId = mediaId,
                position = dataSpec.position,
                requestedLength = requestedLength,
            )

        if (cachedLength < requestedLength) return null

        return dataSpec.subrange(0L, requestedLength)
    }

    private fun getContinuousCachedLength(
        mediaId: String,
        position: Long,
        requestedLength: Long,
    ): Long {
        val targetEnd = position.saturatingAdd(requestedLength)
        var cursor = position
        val spans =
            (
                runCatching { downloadCache.getCachedSpans(mediaId).toList() }.getOrNull().orEmpty() +
                    runCatching { playerCache.getCachedSpans(mediaId).toList() }.getOrNull().orEmpty()
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
        return normalizedScheme == "content" ||
            normalizedScheme == "file" ||
            normalizedScheme == "android.resource" ||
            normalizedScheme == "http" ||
            normalizedScheme == "https"
    }

    private fun Uri.shouldBypassPlayerCache(): Boolean {
        val normalizedScheme = scheme?.lowercase(Locale.US)
        return normalizedScheme == "content" ||
            normalizedScheme == "file" ||
            normalizedScheme == "android.resource"
    }

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

    private class SchemeRoutingDataSource(
        private val cachedFactory: DataSource.Factory,
        private val directFactory: DataSource.Factory,
    ) : DataSource {
        private val transferListeners = mutableListOf<TransferListener>()
        private var delegate: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            transferListeners += transferListener
            delegate?.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val normalizedScheme = dataSpec.uri.scheme?.lowercase(Locale.US)
            val selectedFactory =
                if (
                    normalizedScheme == "content" ||
                    normalizedScheme == "file" ||
                    normalizedScheme == "android.resource"
                ) {
                    directFactory
                } else {
                    cachedFactory
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
        try {
            connectivityObserver.unregister()
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
                    runBlocking {
                        saveQueueToDisk()
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
        val result = super.onBind(intent) ?: binder
        if (::player.isInitialized && player.mediaItemCount > 0 && player.currentMediaItem != null) {
            currentMediaMetadata.value = player.currentMetadata
            scope.launch {
                delay(50)
                updateNotification()
            }
        }
        return result
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
                runBlocking { saveQueueToDisk() }
            }
        } catch (_: Exception) {
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) =
        if (::mediaSession.isInitialized) mediaSession else null

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
        const val PERSISTENT_QUEUE_FILE = "persistent_queue.data"
        const val PERSISTENT_AUTOMIX_FILE = "persistent_automix.data"
        const val PERSISTENT_PLAYER_STATE_FILE = "persistent_player_state.data"
        const val MAX_CONSECUTIVE_ERR = 5
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
