/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadProgress
import androidx.media3.exoplayer.offline.DownloadRequest
import app.hush.music.downloads.DownloadFolderHygiene
import app.hush.music.downloads.DownloadNaming
import app.hush.music.downloads.DownloadedFile
import app.hush.music.downloads.DownloadedFileLocation
import app.hush.music.downloads.DownloadedFileStore
import app.hush.music.storage.StorageFolderKind
import app.hush.music.storage.StorageLocationRepository
import app.hush.music.spotiflac.SpotiFLACPlaybackIdentity
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import app.hush.music.constants.AudioQuality
import app.hush.music.constants.AudioQualityKey
import app.hush.music.constants.SourcePriorityKey
import app.hush.music.constants.SpotiFLACEnabledKey
import app.hush.music.constants.SpotiFLACQualityKey
import app.hush.music.constants.YoutubeStreamingEnabledKey
import app.hush.music.db.MusicDatabase
import app.hush.music.db.entities.FormatEntity
import app.hush.music.db.entities.SongEntity
import app.hush.music.di.DownloadCache
import app.hush.music.di.PlayerCache
import app.hush.music.innertube.VersionedOkHttpClient
import app.hush.music.innertube.YouTube
import app.hush.music.spotiflac.ExtensionRepositoryManager
import app.hush.music.spotiflac.SpotiFLACNativeRuntimeBridgeHolder
import app.hush.music.utils.AuthScopedCacheValue
import app.hush.music.utils.StreamClientUtils
import app.hush.music.utils.YTPlayerUtils
import app.hush.music.utils.enumPreference
import app.hush.music.utils.get
import app.hush.music.utils.dataStore
import app.hush.music.utils.isLocalMediaId
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
        private val downloadOriginStore: DownloadOriginStore,
        private val downloadedFileStore: DownloadedFileStore,
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

        /**
         * Media ids whose SpotiFLAC audio is already a complete file on disk.
         *
         * These files are read directly during playback and are intentionally not copied
         * into Media3's song cache, so this store has to report itself - otherwise tracks
         * that are perfectly playable offline would be missing from every cached-songs
         * list. Pinned entries are excluded: they are user downloads, which already appear
         * through [downloads].
         */
        val spotiflacCachedMediaIds: Set<String>
            get() =
                runCatching {
                    SpotiFLACNativeRuntimeBridgeHolder.instance
                        ?.cachedPlaybackMediaIds(includePinned = false)
                }.getOrNull().orEmpty()

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

        /**
         * Downloads Media3 still owns: entries written before downloads became real files.
         *
         * These keep working exactly as they did - they are pinned cache entries, and the
         * only way to read them is through Media3. New downloads never land here.
         */
        private val legacyDownloads = MutableStateFlow<Map<String, Download>>(emptyMap())

        /**
         * Downloads written as one ordinary file, keyed by media id.
         *
         * Published as the same [Download] type Media3 uses, because every screen already
         * reads that type: the downloaded badge, the progress bars, the download manager,
         * the header actions and the foreground notification all keep working without
         * knowing which of the two shapes a download has.
         */
        private val fileDownloads = MutableStateFlow<Map<String, Download>>(emptyMap())

        /** In-flight file downloads, so the same song cannot be fetched twice concurrently. */
        private val fileDownloadJobs = ConcurrentHashMap<String, Job>()

        /** Target paths claimed by an in-flight download, to keep same-named tracks apart. */
        private val claimedTargetPaths: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

        /** Every download, real files winning over anything Media3 still holds for the key. */
        val downloads: StateFlow<Map<String, Download>> =
            combine(legacyDownloads, fileDownloads) { legacy, files -> legacy + files }
                .stateIn(downloadScope, SharingStarted.Eagerly, emptyMap())

        // A download can resolve to a local SpotiFLAC file, and OkHttp cannot open
        // `file://` (it fails with "Malformed URL"). DefaultDataSource reads local
        // schemes itself and sends everything else to the chunked OkHttp source that
        // exists to bypass YouTube's per-request download throttling.
        private val downloadUpstreamFactory =
            DefaultDataSource.Factory(
                appContext,
                ChunkingDataSourceFactory(
                    OkHttpDataSource.Factory(
                        mediaOkHttpClient,
                    ),
                ),
            )

        private val dataSourceFactory =
            ResolvingDataSource.Factory(
                // Resolution sits above this router, and the router sits above the cache,
                // so a resolved `file://` (a SpotiFLAC file) is read directly and never
                // copied into the song cache. Without this, downloading a track that
                // SpotiFLAC had already fetched wrote the same audio into BOTH stores -
                // the download cache that DownloadManager owns and the streaming cache
                // this data source writes as it reads - so one download cost two full
                // copies and left SpotiFLAC bytes under a key the player cache would
                // otherwise hand to the YouTube engine.
                DataSource.Factory {
                    SchemeRoutingDataSource(
                        cachedFactory =
                            CacheDataSource
                                .Factory()
                                .setCache(playerCache)
                                .setUpstreamDataSourceFactory(downloadUpstreamFactory)
                                .setCacheWriteDataSinkFactory(
                                    CacheDataSink.Factory()
                                        .setCache(playerCache)
                                        .setBufferSize(DOWNLOAD_WRITE_BUFFER_SIZE),
                                ),
                        directFactory = downloadUpstreamFactory,
                    )
                },
            ) { dataSpec ->
                val mediaId = dataSpec.key ?: error("No media id")
                val length = if (dataSpec.length >= 0) dataSpec.length else 1
                if (playerCache.isCached(mediaId, dataSpec.position, length)) {
                    return@Factory dataSpec
                }
                // Download from the same engine that plays the track, instead of
                // always going to YouTube: an already-resolved SpotiFLAC file is
                // reused as-is, and otherwise SpotiFLAC resolves it here. Only when
                // it cannot (disabled, or lower priority than YouTube) does the
                // download fall through to the YouTube resolver below.
                resolveSpotiFLACDownloadUri(mediaId)?.let { spotiUri ->
                    // Record the origin at the moment the source is chosen. Media3 will
                    // not record it for us (see DownloadOriginStore), and the playback
                    // path needs it to know whether these bytes stay playable with
                    // YouTube switched off.
                    downloadOriginStore.record(mediaId, StoredBytesOrigin.SPOTIFLAC)
                    return@Factory dataSpec.withUri(spotiUri)
                }
                val lowDataModeActive = context.isLowDataModeActive()
                val requestedAudioQuality = resolveDownloadAudioQuality(lowDataModeActive)
                val streamCacheKey = buildSongUrlCacheKey(mediaId, requestedAudioQuality)
                val authFingerprint = YouTube.currentPlaybackAuthState().fingerprint
                cachedStreamUrl(streamCacheKey, authFingerprint)?.let { url ->
                    downloadOriginStore.record(mediaId, StoredBytesOrigin.YOUTUBE)
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
                            )
                        }
                    }.getOrThrow()
                persistPlaybackMetadata(mediaId, playbackData)

                val streamUrl = playbackData.streamUrl

                android.util.Log.w(
                    TAG,
                    "resolve: $mediaId resolved stream (itag=${playbackData.format.itag}, len=${playbackData.format.contentLength ?: -1}, approx=${playbackData.format.approxDurationMs}ms)",
                )

                cacheStreamUrl(streamCacheKey, playbackData)
                downloadOriginStore.record(mediaId, StoredBytesOrigin.YOUTUBE)
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
                            android.util.Log.w(
                                TAG,
                                "state: ${download.request.id} -> ${download.state} pct=${download.getPercentDownloaded()}" +
                                    (finalException?.let { " err=${it.message}" } ?: ""),
                            )
                            when (download.state) {
                                // A fresh start (manual retry or new request), a completed
                                // download, or removal resets the retry budget so the song
                                // gets a full retry window again instead of being permanently
                                // blocked after the first 3 failures.
                                Download.STATE_QUEUED, Download.STATE_COMPLETED -> {
                                    downloadFailureCounts.remove(download.request.id)
                                }
                                Download.STATE_FAILED -> {
                                    if (isRetryableDownloadFailure(finalException)) {
                                        handleRetryableDownloadFailure(download)
                                    }
                                }
                            }
                            legacyDownloads.update { map ->
                                map.toMutableMap().apply {
                                    set(download.request.id, download)
                                }
                            }
                        }

                        override fun onDownloadRemoved(
                            downloadManager: DownloadManager,
                            download: Download,
                        ) {
                            legacyDownloads.update { map -> map - download.request.id }
                            downloadFailureCounts.remove(download.request.id)
                            // The media3 download record and the SpotiFLAC playback cache are
                            // separate stores. Without this, removing a download left the
                            // lossless file on disk, so replaying the track was served from it
                            // and the configured source priority was never consulted. Purging
                            // here covers every removal entry point (song menu, playlists,
                            // download manager, "remove all") instead of each call site.
                            runCatching {
                                app.hush.music.spotiflac.SpotiFLACPlaybackCache
                                    .getInstance()
                                    ?.removeForMediaId(download.request.id)
                            }
                            // The origin record describes the bytes that were stored.
                            // Keeping it after the bytes are gone mislabels whatever the
                            // next download of this song writes, so it is dropped with
                            // them.
                            runCatching {
                                downloadOriginStore.forget(download.request.id)
                            }
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
                // A Media3 download record outlives the bytes it describes: the row says
                // "downloaded" while the cache it points at may be empty - a cache folder that
                // has since moved, or one that was cleared. Reporting those would list songs
                // that cannot play, so they are hidden. Hidden, not deleted: removing a
                // download has side effects (it purges the track's SpotiFLAC cache copy), and
                // those must not fire for a download the user never actually lost.
                val playable =
                    result.filterValues { download ->
                        download.state != Download.STATE_COMPLETED ||
                            downloadBytesStillStored(download.request.id)
                    }
                if (playable.size != result.size) {
                    Timber.tag(TAG).i(
                        "hiding %d download record(s) whose stored bytes are gone",
                        result.size - playable.size,
                    )
                }
                legacyDownloads.value = playable
            }
            // The downloads folder holds songs: one real file per download. Media3's download
            // cache used to be created there, which left `.exo` fragments and a `.uid` identity
            // file among the music - and made the downloads and cache settings look like they
            // moved together. The cache now lives under the cache folder, so sweep out what the
            // old arrangement left behind.
            downloadScope.launch {
                val removed =
                    DownloadFolderHygiene.purge(
                        StorageLocationRepository.cacheDirectory(
                            appContext,
                            StorageFolderKind.DOWNLOADS,
                        ),
                    )
                if (removed > 0) {
                    Timber.tag(TAG).i(
                        "removed %d stale cache file(s) from the downloads folder",
                        removed,
                    )
                }
            }
            // Downloads that are ordinary files survive a restart because they *are* files.
            // The store is the record, and it drops any entry whose file has since gone, so
            // a download deleted outside the app cannot come back as a ghost here.
            downloadScope.launch {
                val restored =
                    downloadedFileStore.all().associate { recorded ->
                        recorded.mediaId to
                            syntheticDownload(
                                mediaId = recorded.mediaId,
                                title = recorded.title,
                                state = Download.STATE_COMPLETED,
                                bytesDownloaded = recorded.bytes,
                                contentLength = recorded.bytes,
                                startTimeMs = recorded.createdAtMs,
                                stopReason = Download.STOP_REASON_NONE,
                                failureReason = Download.FAILURE_REASON_NONE,
                            )
                    }
                fileDownloads.value = restored
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
                legacyDownloads.collect { currentDownloads ->
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

        private val downloadFailureCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

        private fun isRetryableDownloadFailure(exception: Exception?): Boolean {
            var t: Throwable? = exception
            while (t != null) {
                val msg = t.message ?: ""
                if (msg.contains("Response code:", ignoreCase = true)) {
                    val code = msg.substringAfter("Response code:").trim().substringBefore(" ").toIntOrNull()
                    if (code != null && code in RETRYABLE_DOWNLOAD_RESPONSE_CODES) return true
                }
                if (t is java.net.SocketTimeoutException) return true
                t = t.cause
            }
            return false
        }

        /**
         * YouTube revokes stream URLs mid-transfer (HTTP 403/410). Evict the stale URL,
         * drop any partial cache spans, and resume the download so it re-resolves a fresh
         * URL. Bounded to a few retries per song to avoid hammering the CDN.
         */
        private fun handleRetryableDownloadFailure(download: Download) {
            val mediaId = download.request.id
            val failures = downloadFailureCounts.merge(mediaId, 1, Int::plus) ?: 1
            if (failures > MAX_DOWNLOAD_RETRIES) {
                android.util.Log.w(TAG, "Download $mediaId giving up after $failures retries")
                return
            }
            android.util.Log.w(TAG, "Download $mediaId failed ($failures) — evicting URL and retrying")
            synchronized(songUrlCache) {
                songUrlCache.keys
                    .filter { it.startsWith("$mediaId:") }
                    .forEach { songUrlCache.remove(it) }
            }
            // Also invalidate the global stream-URL cache in YTPlayerUtils, otherwise the
            // next resolve returns the exact same revoked URL and the retry loops forever.
            YTPlayerUtils.invalidateCachedStreamUrls(mediaId)
            // Purge partial spans from the download cache (the DownloadManager stores its
            // progress there, NOT in playerCache). Keeping them means every resume replays
            // the capped prefix and 403s at the exact same byte offset.
            runCatching { downloadCache.removeResource(mediaId) }
            runCatching { playerCache.removeResource(mediaId) }
            downloadScope.launch {
                delay(RETRY_DOWNLOAD_DELAY_MS)
                // A FAILED download is not resumed by resumeDownloads(); re-adding the
                // request restarts it from a fresh resolve against the evicted URL cache.
                runCatching { downloadManager.addDownload(download.request) }
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

        /**
         * URI to download from when SpotiFLAC should serve this track.
         *
         * Ordering mirrors playback: a file already on disk (the source that is
         * playing, or the one that resolved the track last) wins outright, then
         * SpotiFLAC resolves when it is the first-priority engine or YouTube is
         * unavailable. Returning null hands the download to the YouTube resolver.
         */
        private fun resolveSpotiFLACDownloadUri(mediaId: String): android.net.Uri? {
            if (mediaId.isBlank() || mediaId.isLocalMediaId()) return null
            if (!appContext.dataStore.get(SpotiFLACEnabledKey, false)) return null

            val bridge = SpotiFLACNativeRuntimeBridgeHolder.instance ?: return null
            if (!bridge.isRuntimeAvailable) return null

            // Already resolved for this track: keep the exact source playback used.
            runCatching { bridge.playbackFileForMediaId(mediaId) }.getOrNull()?.let { cached ->
                Timber.tag(TAG).i("download source: SpotiFLAC cache file for %s", mediaId)
                return cached.toUri()
            }

            val ytEnabled = appContext.dataStore.get(YoutubeStreamingEnabledKey, true)
            val firstEngine =
                appContext.dataStore.get(SourcePriorityKey, "SPOTIFLAC,YOUTUBE")
                    .split(",")
                    .firstOrNull { it.isNotBlank() }
                    ?.trim()
                    ?.uppercase()
            if (!(firstEngine == "SPOTIFLAC" || !ytEnabled)) {
                Timber.tag(TAG).i("download source: YouTube first for %s", mediaId)
                return null
            }

            return runBlocking(Dispatchers.IO) {
                // Prefer the identity playback resolved this track with: it carries the
                // ISRC and Spotify id from the queue item, which is what lets SpotiFLAC
                // pick the *same* track on the *same* source instead of guessing from a
                // title/artist search. The database row is the fallback, and it has
                // neither identifier, so a match found from it is a best effort.
                val identity = SpotiFLACPlaybackIdentity.get(mediaId)
                val dbSong =
                    if (identity == null) {
                        runCatching { database.song(mediaId).first() }.getOrNull()
                    } else {
                        null
                    }
                val title =
                    identity?.title?.takeIf { it.isNotBlank() }
                        ?: dbSong?.song?.title?.takeIf { it.isNotBlank() }
                        ?: run {
                            Timber.tag(TAG).i(
                                "download source: no metadata for %s yet, using YouTube",
                                mediaId,
                            )
                            return@runBlocking null
                        }
                val resolved =
                    runCatching {
                        bridge.resolve(
                            title = title,
                            artist = identity?.artist ?: dbSong?.artists?.joinToString(", ") { it.name }.orEmpty(),
                            album = identity?.album ?: dbSong?.song?.albumName,
                            durationMs =
                                identity?.durationMs
                                    ?: ((dbSong?.song?.duration?.takeIf { it > 0 } ?: 0) * 1000L),
                            isrc = identity?.isrc,
                            spotifyTrackId = identity?.spotifyTrackId,
                            quality = spotiFLACDownloadQuality(),
                            sourceIds = ExtensionRepositoryManager.getInstance().getEnabledSourceIds(),
                            coverUrl = identity?.coverUrl,
                            mediaId = mediaId,
                        )
                    }.getOrElse { error ->
                        Timber.tag(TAG).w(error, "download: SpotiFLAC resolve failed for %s", mediaId)
                        return@runBlocking null
                    }.getOrNull() ?: return@runBlocking null

                Timber.tag(TAG).i(
                    "download source: SpotiFLAC %s for %s (fromCache=%s, identity=%s)",
                    resolved.sourceId,
                    mediaId,
                    resolved.fromCache,
                    if (identity != null) "playback" else "db",
                )
                resolved.file.toUri()
            }
        }

        /** SpotiFLAC quality request derived from the user's quality setting. */
        private fun spotiFLACDownloadQuality(): String =
            when (appContext.dataStore.get(SpotiFLACQualityKey, "BEST").uppercase()) {
                "FLAC", "BEST", "HIGHEST" -> "LOSSLESS"
                "HIGH_RES", "HI_RES", "HI_RES_LOSSLESS" -> "HI_RES_LOSSLESS"
                else -> "BEST"
            }

        // ---------------------------------------------------------------------------------
        // Real-file downloads
        // ---------------------------------------------------------------------------------

        /**
         * Queues a download that is written as one real file.
         *
         * [ExoDownloadService] routes every ADD_DOWNLOAD request here instead of handing it
         * to Media3's DownloadManager, because the manager can only store bytes as cache
         * fragments named after a numeric uid. The title travels in the request's data - it
         * is what the download notification already displays - and the artist is read from
         * the database while the download runs.
         */
        fun requestFileDownload(request: DownloadRequest) {
            val mediaId = request.id.trim()
            if (mediaId.isBlank()) return
            val title = runCatching { String(request.data, Charsets.UTF_8) }.getOrNull().orEmpty()
            requestFileDownload(mediaId, title)
        }

        fun requestFileDownload(
            mediaId: String,
            title: String,
        ) {
            val id = mediaId.trim()
            if (id.isBlank() || id.isLocalMediaId()) return
            val job =
                downloadScope.launch(start = CoroutineStart.LAZY) {
                    runFileDownload(id, title)
                }
            // putIfAbsent is the guard: a second request for the same song would otherwise
            // fetch it twice and race for the same target name.
            if (fileDownloadJobs.putIfAbsent(id, job) != null) {
                job.cancel()
                return
            }
            job.invokeOnCompletion { fileDownloadJobs.remove(id, job) }
            job.start()
        }

        /** Pauses (cancels) or resumes a file download. No-op for Media3-owned downloads. */
        fun pauseDownload(mediaId: String) {
            val id = mediaId.trim()
            if (id.isBlank()) return
            if (isFileDownload(id)) fileDownloadJobs.remove(id)?.cancel()
            runCatching { downloadManager.setStopReason(id, PAUSED_STOP_REASON) }
        }

        /** Clears a stop reason, or retries a failure, for whichever shape owns [mediaId]. */
        fun resumeDownload(mediaId: String) {
            val id = mediaId.trim()
            if (id.isBlank()) return
            if (isFileDownload(id)) {
                val title =
                    fileDownloads.value[id]
                        ?.let { runCatching { String(it.request.data, Charsets.UTF_8) }.getOrNull() }
                        .orEmpty()
                requestFileDownload(id, title)
                return
            }
            val download = downloads.value[id]
            if (download?.state == Download.STATE_FAILED) {
                runCatching { downloadManager.addDownload(download.request) }
            } else {
                runCatching { downloadManager.setStopReason(id, Download.STOP_REASON_NONE) }
            }
        }

        /**
         * Removes a download of either shape: the real file and its record, plus anything
         * Media3 still holds for the same key.
         */
        fun removeDownload(mediaId: String) {
            val id = mediaId.trim()
            if (id.isBlank()) return
            fileDownloadJobs.remove(id)?.cancel()
            downloadedFileStore.forget(id)?.let { recorded ->
                runCatching { resolvedDownloadedFile(recorded)?.delete() }
            }
            // The origin record describes bytes that no longer exist; a stale SPOTIFLAC
            // record would otherwise authorise the next download of this song to play with
            // YouTube switched off.
            runCatching { downloadOriginStore.forget(id) }
            fileDownloads.update { it - id }
            runCatching { downloadManager.removeDownload(id) }
            runCatching { downloadCache.removeResource(id) }
            // The song's stamp is dropped once nothing is left on disk for it, which is
            // after the caches above have been purged - not before, or the check would
            // still see the very bytes it is about to delete.
            downloadScope.launch { dropDownloadStampIfBytesAreGone(id) }
        }

        /** Removes every real-file download. Media3-owned downloads are removed separately. */
        fun removeAllDownloads() {
            downloadedFileStore.all().forEach { recorded ->
                runCatching { resolvedDownloadedFile(recorded)?.delete() }
                downloadedFileStore.forget(recorded.mediaId)
                downloadOriginStore.forget(recorded.mediaId)
                // Same as removeDownload: this id's download-cache bytes go too, so the
                // stamp check that follows sees the truth rather than the bytes being
                // deleted underneath it.
                runCatching { downloadCache.removeResource(recorded.mediaId) }
                downloadScope.launch { dropDownloadStampIfBytesAreGone(recorded.mediaId) }
            }
            fileDownloadJobs.keys.toList().forEach { id -> fileDownloadJobs.remove(id)?.cancel() }
            fileDownloads.value = emptyMap()
        }

        /** The one ordinary file stored for [mediaId], or null when it is not a file download. */
        fun downloadedFileFor(mediaId: String): File? = downloadedFileStore.fileFor(mediaId)

        /**
         * The file a record actually points at, following the current downloads folder.
         *
         * Every caller that touches a download's bytes - serving it, replacing it, deleting
         * it - goes through here, because the recorded absolute path stops being true the
         * moment the user moves the folder.
         */
        private fun resolvedDownloadedFile(recorded: DownloadedFile): File? =
            DownloadedFileLocation.resolve(
                StorageLocationRepository.cacheDirectory(appContext, StorageFolderKind.DOWNLOADS),
                recorded,
            )

        private fun isFileDownload(mediaId: String): Boolean =
            fileDownloads.value.containsKey(mediaId) || downloadedFileStore.get(mediaId) != null

        /**
         * Whether Media3's download cache still holds bytes for [mediaId].
         *
         * "Could not tell" answers yes, so a cache that fails to open can only make Hush keep
         * a record, never hide a download the user actually has.
         */
        private fun downloadBytesStillStored(mediaId: String): Boolean =
            runCatching { downloadCache.getCachedSpans(mediaId).isNotEmpty() }.getOrDefault(true)

        private suspend fun runFileDownload(
            mediaId: String,
            title: String,
        ) {
            val startedAt = System.currentTimeMillis()
            publishFileDownload(mediaId, title, Download.STATE_QUEUED, 0L, 0L, startedAt)
            try {
                val result = downloadToFile(mediaId, title, startedAt)
                val entry =
                    DownloadedFile(
                        mediaId = mediaId,
                        path = result.file.absolutePath,
                        origin = result.origin.name,
                        title = title,
                        artist = result.artist,
                        bytes = result.file.length(),
                        createdAtMs = System.currentTimeMillis(),
                        // The name inside the downloads folder, which is the part that
                        // survives the folder being changed; `path` is kept for diagnostics.
                        fileName = result.file.name,
                    )
                // A superseded file (the name or the container changed, or the downloads
                // folder moved mid-download) is only deleted once the replacement is
                // written, so a failed re-download never costs the user the copy they
                // already had. It is *resolved* rather than read from the recorded path:
                // after a folder change that path points at where the file used to be,
                // which would leave the old copy behind forever.
                downloadedFileStore
                    .get(mediaId)
                    ?.let { previous -> resolvedDownloadedFile(previous) }
                    ?.takeIf { previous -> previous != result.file }
                    ?.let { previous -> runCatching { previous.delete() } }
                downloadedFileStore.record(entry)
                downloadOriginStore.record(mediaId, result.origin)
                markSongDownloaded(mediaId, title)
                publishFileDownload(
                    mediaId = mediaId,
                    title = title,
                    state = Download.STATE_COMPLETED,
                    bytesDownloaded = entry.bytes,
                    contentLength = entry.bytes,
                    startTimeMs = startedAt,
                )
                android.util.Log.w(
                    TAG,
                    "file download complete: $mediaId -> ${entry.path} (${entry.bytes} bytes, ${entry.origin})",
                )
            } catch (cancellation: CancellationException) {
                // A pause and a removal both land here. The record is only written on
                // success, so an interrupted download leaves nothing claiming to be a file.
                publishFileDownload(mediaId, title, Download.STATE_STOPPED, 0L, 0L, startedAt)
                throw cancellation
            } catch (error: Exception) {
                android.util.Log.w(TAG, "file download failed: $mediaId ${error.message}")
                publishFileDownload(
                    mediaId = mediaId,
                    title = title,
                    state = Download.STATE_FAILED,
                    bytesDownloaded = 0L,
                    contentLength = 0L,
                    startTimeMs = startedAt,
                    failureReason = Download.FAILURE_REASON_UNKNOWN,
                )
            }
        }

        private class ResolvedDownloadFile(
            val file: File,
            val origin: StoredBytesOrigin,
            val artist: String,
        )

        private suspend fun downloadToFile(
            mediaId: String,
            title: String,
            startedAt: Long,
        ): ResolvedDownloadFile =
            withContext(Dispatchers.IO) {
                val directory =
                    StorageLocationRepository
                        .cacheDirectory(appContext, StorageFolderKind.DOWNLOADS)
                        .apply { mkdirs() }
                val artist =
                    runCatching {
                        database.song(mediaId).first()?.artists?.joinToString(", ") { it.name }
                    }.getOrNull().orEmpty()

                val lastPublished = AtomicLong(0L)
                val onProgress: (Long, Long) -> Unit = progress@{ written, total ->
                    val now = SystemClock.elapsedRealtime()
                    if (written < total && now - lastPublished.get() < PROGRESS_PUBLISH_INTERVAL_MS) return@progress
                    lastPublished.set(now)
                    publishFileDownload(
                        mediaId = mediaId,
                        title = title,
                        state = Download.STATE_DOWNLOADING,
                        bytesDownloaded = written,
                        contentLength = total,
                        startTimeMs = startedAt,
                    )
                }
                val keepGoing = { isActive }

                // 1) SpotiFLAC. Its lossless file usually already exists on disk, so the
                // download is a copy into the user's folder rather than a fetch - and its
                // container is FLAC by definition, which is the extension that belongs on it.
                resolveSpotiFLACDownloadUri(mediaId)?.takeIf { it.scheme == "file" }?.path?.let { path ->
                    val source = File(path)
                    if (!source.isFile || source.length() <= 0L) return@let
                    val target = claimTargetFile(directory, mediaId, title, artist, FLAC_EXTENSION)
                    try {
                        copyToFile(source, target, keepGoing, onProgress)
                    } finally {
                        releaseTargetClaim(target)
                    }
                    // Playback serves this download from now on, so the SpotiFLAC cache copy
                    // is pure duplication - and removing it is the point of this change.
                    runCatching {
                        SpotiFLACNativeRuntimeBridgeHolder.instance?.removeCachedPlaybackForMediaId(mediaId)
                    }
                    Timber.tag(TAG).i("file download: SpotiFLAC file %s for %s", source.name, mediaId)
                    return@withContext ResolvedDownloadFile(target, StoredBytesOrigin.SPOTIFLAC, artist)
                }

                // 2) YouTube. The stream is saved as-is and named for the container YouTube
                // actually served (itag 140 is audio/mp4, itag 251 is audio/webm), rather
                // than being labelled with an extension it does not have.
                val lowDataModeActive = appContext.isLowDataModeActive()
                val requestedQuality = resolveDownloadAudioQuality(lowDataModeActive)
                val playbackData =
                    runBlocking(Dispatchers.IO) {
                        appContext.retryWithoutPlaybackLoginContext {
                            YTPlayerUtils.playerResponseForDownload(
                                mediaId,
                                audioQuality = requestedQuality,
                                connectivityManager = connectivityManager,
                                networkMetered = lowDataModeActive,
                            )
                        }
                    }.getOrThrow()
                persistPlaybackMetadata(mediaId, playbackData)
                cacheStreamUrl(buildSongUrlCacheKey(mediaId, requestedQuality), playbackData)
                val extension = DownloadNaming.extensionForMimeType(playbackData.format.mimeType)
                val target = claimTargetFile(directory, mediaId, title, artist, extension)
                try {
                    fetchToFile(playbackData.streamUrl, target, playbackData.format.contentLength ?: -1L, keepGoing, onProgress)
                } finally {
                    releaseTargetClaim(target)
                }
                Timber.tag(TAG).i(
                    "file download: YouTube %s for %s (itag=%d)",
                    target.name,
                    mediaId,
                    playbackData.format.itag,
                )
                ResolvedDownloadFile(target, StoredBytesOrigin.YOUTUBE, artist)
            }

        private fun claimTargetFile(
            directory: File,
            mediaId: String,
            title: String,
            artist: String,
            extension: String,
        ): File =
            synchronized(claimedTargetPaths) {
                val base = DownloadNaming.fileName(title, artist, extension)
                // Re-downloading a track replaces the file it already owns, rather than
                // piling up "(2)", "(3)" copies of the same song. The name is only reused
                // when it still describes this download: if the track now resolves to a
                // different container, or the folder has moved, a fresh name is taken and
                // the superseded file is deleted once the new one is safely written.
                val recordedFile =
                    downloadedFileStore.get(mediaId)?.let { recorded -> resolvedDownloadedFile(recorded) }
                val reusesRecordedName =
                    recordedFile != null &&
                        recordedFile.name == base &&
                        recordedFile.parentFile == directory
                val name =
                    if (reusesRecordedName) {
                        base
                    } else {
                        DownloadNaming.uniqueFileName(base) { candidate ->
                            val path = File(directory, candidate).absolutePath
                            path in claimedTargetPaths || File(directory, candidate).exists()
                        }
                    }
                File(directory, name).also { claimedTargetPaths.add(it.absolutePath) }
            }

        private fun releaseTargetClaim(target: File) {
            claimedTargetPaths.remove(target.absolutePath)
        }

        private fun partFileFor(target: File): File = File(target.parentFile, "${target.name}.part")

        /**
         * Promotes a fully written `.part` file to its real name.
         *
         * The intermediate name is what makes an interrupted download harmless: a half
         * written file never appears under the name the store records, so a crash cannot
         * leave a truncated track that looks complete.
         */
        private fun commitPartFile(
            part: File,
            target: File,
        ) {
            if (target.isFile) target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
        }

        private fun copyToFile(
            source: File,
            target: File,
            keepGoing: () -> Boolean,
            onProgress: (Long, Long) -> Unit,
        ) {
            val total = source.length()
            val part = partFileFor(target)
            try {
                source.inputStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(FILE_COPY_BUFFER_SIZE)
                        var written = 0L
                        while (true) {
                            if (!keepGoing()) throw CancellationException("file download cancelled")
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            onProgress(written, total)
                        }
                        output.flush()
                        output.fd.sync()
                    }
                }
                if (part.length() <= 0L) throw IOException("empty download for ${target.name}")
                commitPartFile(part, target)
            } catch (error: Exception) {
                runCatching { part.delete() }
                throw error
            }
        }

        private fun fetchToFile(
            url: String,
            target: File,
            declaredLength: Long,
            keepGoing: () -> Boolean,
            onProgress: (Long, Long) -> Unit,
        ) {
            val part = partFileFor(target)
            val dataSource = downloadUpstreamFactory.createDataSource()
            try {
                val opened = dataSource.open(DataSpec(url.toUri()))
                val total = if (opened > 0L) opened else declaredLength
                var written = 0L
                part.outputStream().use { output ->
                    val buffer = ByteArray(FILE_COPY_BUFFER_SIZE)
                    while (true) {
                        if (!keepGoing()) throw CancellationException("file download cancelled")
                        val read = dataSource.read(buffer, 0, buffer.size)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                    output.flush()
                    output.fd.sync()
                }
                if (written <= 0L) throw IOException("empty download for $url")
                commitPartFile(part, target)
            } catch (error: Exception) {
                runCatching { part.delete() }
                throw error
            } finally {
                runCatching { dataSource.close() }
            }
        }

        /**
         * Publishes progress as a Media3 [Download], which is the shape every screen reads.
         *
         * [DownloadProgress] is what carries the percentage, and its fields are public, so
         * a download Hush runs itself reports progress through exactly the same plumbing as
         * one Media3 runs - including the header actions that average progress across a
         * whole album.
         */
        private fun publishFileDownload(
            mediaId: String,
            title: String,
            state: Int,
            bytesDownloaded: Long,
            contentLength: Long,
            startTimeMs: Long,
            stopReason: Int = Download.STOP_REASON_NONE,
            failureReason: Int = Download.FAILURE_REASON_NONE,
        ) {
            val synthetic =
                syntheticDownload(
                    mediaId = mediaId,
                    title = title,
                    state = state,
                    bytesDownloaded = bytesDownloaded,
                    contentLength = contentLength,
                    startTimeMs = startTimeMs,
                    stopReason = stopReason,
                    failureReason = failureReason,
                )
            fileDownloads.update { current ->
                current.toMutableMap().apply { set(mediaId, synthetic) }
            }
        }

        private fun syntheticDownload(
            mediaId: String,
            title: String,
            state: Int,
            bytesDownloaded: Long,
            contentLength: Long,
            startTimeMs: Long,
            stopReason: Int,
            failureReason: Int,
        ): Download {
            val request =
                DownloadRequest
                    .Builder(mediaId, mediaId.toUri())
                    .setCustomCacheKey(mediaId)
                    .setData(title.toByteArray())
                    .build()
            val progress =
                DownloadProgress().apply {
                    this.bytesDownloaded = bytesDownloaded
                    this.percentDownloaded =
                        if (contentLength > 0L) {
                            (bytesDownloaded * 100.0 / contentLength).toFloat().coerceIn(0f, 100f)
                        } else {
                            C.PERCENTAGE_UNSET.toFloat()
                        }
                }
            return Download(
                request,
                state,
                startTimeMs,
                System.currentTimeMillis(),
                contentLength,
                stopReason,
                failureReason,
                progress,
            )
        }

        /**
         * Drops the song's on-disk stamp once it has no bytes left anywhere.
         *
         * `dateDownload` means "this song's bytes are on disk" and is read as a download
         * signal in three places: the album download counts, the downloaded-episodes list,
         * and the cache playlist's ordering. Removing a download used to leave it set, so a
         * song that was downloaded and then removed went on counting as downloaded - long
         * after its bytes were gone - everywhere that reads it.
         *
         * The stamp is kept while the song is still cached or still has a SpotiFLAC file,
         * and when the caches cannot be read: an unknown answer must not delete a record
         * that is actually true.
         */
        private suspend fun dropDownloadStampIfBytesAreGone(mediaId: String) {
            val bytesRemain =
                runCatching {
                    playerCache.getCachedSpans(mediaId).isNotEmpty() ||
                        downloadCache.getCachedSpans(mediaId).isNotEmpty() ||
                        mediaId in cachedSpotiFLACMediaIds()
                }.getOrDefault(true)
            if (bytesRemain) return
            runCatching {
                database.query {
                    val existing = getSongByIdBlocking(mediaId)?.song ?: return@query
                    if (existing.dateDownload != null) upsert(existing.copy(dateDownload = null))
                }
            }
        }

        /** Media ids with a SpotiFLAC playback file on disk, or an empty set if unavailable. */
        private fun cachedSpotiFLACMediaIds(): Set<String> =
            runCatching { SpotiFLACNativeRuntimeBridgeHolder.instance?.cachedPlaybackMediaIds().orEmpty() }
                .getOrDefault(emptySet())

        /**
         * Marks the song as downloaded so the cache playlist and the download lists agree
         * with the file that now exists - the same bookkeeping the Media3 path did.
         */
        private suspend fun markSongDownloaded(
            mediaId: String,
            title: String,
        ) {
            runCatching {
                database.query {
                    val now = LocalDateTime.now()
                    val existing = getSongByIdBlocking(mediaId)?.song
                    if (existing != null) {
                        if (existing.dateDownload == null) upsert(existing.copy(dateDownload = now))
                    } else {
                        upsert(
                            SongEntity(
                                id = mediaId,
                                title = title.ifBlank { "Unknown" },
                                duration = 0,
                                dateDownload = now,
                            ),
                        )
                    }
                }
            }
        }

        companion object {
            private const val DEFAULT_MAX_PARALLEL_DOWNLOADS = 6
            private const val FILE_COPY_BUFFER_SIZE = 128 * 1024
            private const val PROGRESS_PUBLISH_INTERVAL_MS = 250L
            private const val FLAC_EXTENSION = "flac"
            private const val PAUSED_STOP_REASON = 1
            private const val MAX_IDLE_DOWNLOAD_CONNECTIONS = 12
            private const val MAX_DOWNLOAD_HTTP_REQUESTS = 24
            private const val DOWNLOAD_CONNECTION_KEEP_ALIVE_MINUTES = 5L
            private const val DOWNLOAD_WRITE_BUFFER_SIZE = 256 * 1024
            private const val SONG_URL_CACHE_CAPACITY = 64
            private const val MAX_DOWNLOAD_RETRIES = 3
            private const val RETRY_DOWNLOAD_DELAY_MS = 2_000L
            private val RETRYABLE_DOWNLOAD_RESPONSE_CODES = setOf(403, 404, 410, 416)
        }
    }
