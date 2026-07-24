/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import app.hush.music.constants.AudioQuality
import app.hush.music.constants.AudioQualityKey
import app.hush.music.db.MusicDatabase
import app.hush.music.db.entities.FormatEntity
import app.hush.music.db.entities.SongEntity
import app.hush.music.di.DownloadCache
import app.hush.music.di.PlayerCache
import app.hush.music.innertube.VersionedOkHttpClient
import app.hush.music.innertube.YouTube
import app.hush.music.utils.AuthScopedCacheValue
import app.hush.music.utils.StreamClientUtils
import app.hush.music.utils.YTPlayerUtils
import app.hush.music.utils.enumPreference
import app.hush.music.utils.get
import app.hush.music.utils.isLowDataModeActive
import app.hush.music.utils.resolveEffectiveAudioQuality
import app.hush.music.utils.retryWithoutPlaybackLoginContext
import okhttp3.ConnectionPool
import timber.log.Timber
import okhttp3.OkHttpClient
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadUtil
    @Inject
    constructor(
        @ApplicationContext context: Context,
        val database: MusicDatabase,
        val databaseProvider: DatabaseProvider,
        @DownloadCache val downloadCache: Cache,
        @PlayerCache val playerCache: Cache,
    ) {
        private val appContext = context
        private val TAG = "DownloadUtil"
        private val connectivityManager = appContext.getSystemService<ConnectivityManager>()
            ?: error("ConnectivityManager not available")
        private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)
        private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val songUrlCache =
            object : LinkedHashMap<String, AuthScopedCacheValue>(SONG_URL_CACHE_CAPACITY, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AuthScopedCacheValue>?): Boolean =
                    size > SONG_URL_CACHE_CAPACITY
            }
        private val downloadExecutor = Executors.newFixedThreadPool(DEFAULT_MAX_PARALLEL_DOWNLOADS)

        private val mediaOkHttpClientHolder =
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
                    retryOnConnectionFailure(true)
                    connectTimeout(30, TimeUnit.SECONDS)
                    readTimeout(30, TimeUnit.SECONDS)
                    dispatcher(
                        okhttp3.Dispatcher().apply {
                            maxRequests = MAX_DOWNLOAD_HTTP_REQUESTS
                            maxRequestsPerHost = DEFAULT_MAX_PARALLEL_DOWNLOADS
                        },
                    ).connectionPool(
                        ConnectionPool(
                            MAX_IDLE_DOWNLOAD_CONNECTIONS,
                            DOWNLOAD_CONNECTION_KEEP_ALIVE_MINUTES,
                            TimeUnit.MINUTES,
                        ),
                    ).addInterceptor { chain ->
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

        val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

        private val dataSourceFactory =
            ResolvingDataSource.Factory(
                CacheDataSource
                    .Factory()
                    .setCache(playerCache)
                    .setUpstreamDataSourceFactory(
                        ChunkingDataSourceFactory(
                            OkHttpDataSource.Factory(
                                mediaOkHttpClient,
                            ),
                        ),
                    ).setCacheWriteDataSinkFactory(
                        CacheDataSink.Factory().setCache(playerCache).setBufferSize(DOWNLOAD_WRITE_BUFFER_SIZE),
                    ),
            ) { dataSpec ->
                val mediaId = dataSpec.key ?: error("No media id")
                val length = if (dataSpec.length >= 0) dataSpec.length else 1
                if (playerCache.isCached(mediaId, dataSpec.position, length)) {
                    return@Factory dataSpec
                }
                val lowDataModeActive = context.isLowDataModeActive()
                val requestedAudioQuality = resolveDownloadAudioQuality(lowDataModeActive)
                val streamCacheKey = buildSongUrlCacheKey(mediaId, requestedAudioQuality)
                val authFingerprint = YouTube.currentPlaybackAuthState().fingerprint
                cachedStreamUrl(streamCacheKey, authFingerprint)?.let { url ->
                    return@Factory dataSpec.withUri(url.toUri())
                }
                val playbackData =
                    runBlocking(Dispatchers.IO) {
                        context.retryWithoutPlaybackLoginContext {
                            YTPlayerUtils.playerResponseForDownload(
                                mediaId,
                                audioQuality = requestedAudioQuality,
                                connectivityManager = connectivityManager,
                                networkMetered = lowDataModeActive,
                                context = context,
                            )
                        }
                    }.getOrThrow()
                persistPlaybackMetadata(mediaId, playbackData)

                val streamUrl = playbackData.streamUrl

                cacheStreamUrl(streamCacheKey, playbackData)
                dataSpec.withUri(streamUrl.toUri())
            }

        val downloadNotificationHelper =
            DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)

        val downloadManager: DownloadManager =
            DownloadManager(
                context,
                databaseProvider,
                downloadCache,
                dataSourceFactory,
                downloadExecutor,
            ).apply {
                maxParallelDownloads = DEFAULT_MAX_PARALLEL_DOWNLOADS
                addListener(
                    object : DownloadManager.Listener {
                        override fun onDownloadChanged(
                            downloadManager: DownloadManager,
                            download: Download,
                            finalException: Exception?,
                        ) {
                            downloads.update { map ->
                                map.toMutableMap().apply {
                                    set(download.request.id, download)
                                }
                            }
                        }

                        override fun onDownloadRemoved(
                            downloadManager: DownloadManager,
                            download: Download,
                        ) {
                            downloads.update { map -> map - download.request.id }
                        }
                    },
                )
            }

        init {
            downloadScope.launch {
                val result = mutableMapOf<String, Download>()
                val cursor = downloadManager.downloadIndex.getDownloads()
                while (cursor.moveToNext()) {
                    result[cursor.download.request.id] = cursor.download
                }
                downloads.value = result
            }
            downloadScope.launch {
                var previousFingerprint: String? = null
                YouTube.authStateFlow
                    .map { it.fingerprint }
                    .distinctUntilChanged()
                    .collect { fingerprint ->
                        if (previousFingerprint != null && previousFingerprint != fingerprint) {
                            synchronized(songUrlCache) { songUrlCache.clear() }
                        }
                        previousFingerprint = fingerprint
                    }
            }
            // Pre-resolve pending download URLs immediately so ExoPlayer doesn't block
            downloadScope.launch {
                downloads.collect { currentDownloads ->
                    for ((id, download) in currentDownloads) {
                        if (download.state == Download.STATE_QUEUED || download.state == Download.STATE_DOWNLOADING) {
                            val streamCacheKey = buildSongUrlCacheKey(id, resolveDownloadAudioQuality(false))
                            if (!hasCachedStreamUrl(streamCacheKey)) {
                                launch {
                                    preResolveDownloadUrl(id)
                                }
                            }
                        }
                    }
                }
            }
        }

        private suspend fun preResolveDownloadUrl(mediaId: String) {
            try {
                val playbackData = withContext(Dispatchers.IO) {
                    appContext.retryWithoutPlaybackLoginContext {
                        YTPlayerUtils.playerResponseForDownload(
                            mediaId,
                            audioQuality = resolveDownloadAudioQuality(false),
                            connectivityManager = connectivityManager,
                            networkMetered = false,
                            context = appContext,
                        )
                    }
                }.getOrThrow()
                persistPlaybackMetadata(mediaId, playbackData)
                val streamCacheKey = buildSongUrlCacheKey(mediaId, resolveDownloadAudioQuality(false))
                cacheStreamUrl(streamCacheKey, playbackData)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Pre-resolve failed for $mediaId")
            }
        }

        fun getDownload(songId: String): Flow<Download?> = downloads.map { it[songId] }.distinctUntilChanged()

        private fun resolveDownloadAudioQuality(lowDataModeActive: Boolean): AudioQuality =
            resolveEffectiveAudioQuality(audioQuality, lowDataModeActive)

        private fun buildSongUrlCacheKey(
            mediaId: String,
            requestedAudioQuality: AudioQuality,
        ): String = "$mediaId:${requestedAudioQuality.name}"

        private fun cachedStreamUrl(
            cacheKey: String,
            authFingerprint: String,
        ): String? =
            synchronized(songUrlCache) {
                songUrlCache[cacheKey]
                    ?.takeIf {
                        it.isValidFor(
                            authFingerprint = authFingerprint,
                            minimumRemainingMs = YTPlayerUtils.STREAM_URL_EXPIRY_SAFETY_MS,
                        )
                    }?.url
            }

        private fun hasCachedStreamUrl(cacheKey: String): Boolean =
            synchronized(songUrlCache) { songUrlCache.containsKey(cacheKey) }

        private fun cacheStreamUrl(
            cacheKey: String,
            playbackData: YTPlayerUtils.PlaybackData,
        ) {
            synchronized(songUrlCache) {
                songUrlCache[cacheKey] =
                    AuthScopedCacheValue(
                        url = playbackData.streamUrl,
                        expiresAtMs = System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L),
                        authFingerprint = playbackData.authFingerprint,
                    )
            }
        }

        private fun persistPlaybackMetadata(
            mediaId: String,
            playbackData: YTPlayerUtils.PlaybackData,
        ) {
            downloadScope.launch {
                runCatching {
                    val format = playbackData.format
                    val contentLength = format.contentLength ?: 0L
                    val resolvedCodecs =
                        format.mimeType
                            .substringAfter("codecs=", "")
                            .removeSurrounding("\"")
                            .substringBefore("\"")

                    database.query {
                        upsert(
                            FormatEntity(
                                id = mediaId,
                                itag = format.itag,
                                mimeType = format.mimeType.split(";")[0],
                                codecs = resolvedCodecs,
                                bitrate = format.bitrate,
                                sampleRate = format.audioSampleRate,
                                contentLength = contentLength,
                                loudnessDb = playbackData.audioConfig?.loudnessDb,
                                perceptualLoudnessDb = playbackData.audioConfig?.perceptualLoudnessDb,
                                playbackUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl,
                            ),
                        )

                        val now = LocalDateTime.now()
                        val existing = getSongByIdBlocking(mediaId)?.song

                        val updatedSong =
                            if (existing != null) {
                                if (existing.dateDownload == null) existing.copy(dateDownload = now) else existing
                            } else {
                                SongEntity(
                                    id = mediaId,
                                    title = playbackData.videoDetails?.title ?: "Unknown",
                                    duration = playbackData.videoDetails?.lengthSeconds?.toIntOrNull() ?: 0,
                                    thumbnailUrl =
                                        playbackData.videoDetails
                                            ?.thumbnail
                                            ?.thumbnails
                                            ?.lastOrNull()
                                            ?.url,
                                    dateDownload = now,
                                )
                            }

                        upsert(updatedSong)
                    }
                }
            }
        }

        companion object {
            private const val DEFAULT_MAX_PARALLEL_DOWNLOADS = 6
            private const val MAX_IDLE_DOWNLOAD_CONNECTIONS = 12
            private const val MAX_DOWNLOAD_HTTP_REQUESTS = 24
            private const val DOWNLOAD_CONNECTION_KEEP_ALIVE_MINUTES = 5L
            private const val DOWNLOAD_WRITE_BUFFER_SIZE = 256 * 1024
            private const val SONG_URL_CACHE_CAPACITY = 64
        }
    }
