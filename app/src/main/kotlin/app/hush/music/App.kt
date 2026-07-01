/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.request.CachePolicy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowHardware
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import app.hush.music.canvas.HushCanvas
import app.hush.music.constants.*
import app.hush.music.extensions.toEnum
import app.hush.music.innertube.VersionedOkHttpClient
import app.hush.music.innertube.YouTube
import app.hush.music.innertube.models.IpVersion
import app.hush.music.innertube.models.YouTubeLocale
import app.hush.music.kugou.KuGou
import app.hush.music.lastfm.LastFM
import app.hush.music.paxsenix.PaxsenixLyrics
import app.hush.music.scrobbling.LastFmServiceConfig
import app.hush.music.di.StorageEntryPoint
import app.hush.music.storage.LegacyStorageAutoImporter
import app.hush.music.storage.StorageFolderKind
import dagger.hilt.android.EntryPointAccessors
import app.hush.music.storage.StorageLocationRepository
import app.hush.music.ui.player.CanvasArtworkPlaybackCache
import app.hush.music.ui.screens.settings.ThemePalettes
import app.hush.music.ui.theme.ThemeSeedPalette
import app.hush.music.ui.theme.ThemeSeedPaletteCodec
import app.hush.music.utils.ArtworkNetworkUtils
import app.hush.music.utils.AutoBackupHelper
import app.hush.music.utils.IconUtils
import app.hush.music.utils.PreferenceStore
import app.hush.music.utils.ProxyUtils
import app.hush.music.utils.YTPlayerUtils
import app.hush.music.utils.clearPlaybackAuthSession
import app.hush.music.utils.clearPlaybackWebAuthSession
import app.hush.music.utils.dataStore
import app.hush.music.utils.get
import app.hush.music.utils.potoken.BotGuardTokenGenerator
import app.hush.music.utils.reportException
import app.hush.music.utils.safeDataStoreEdit
import app.hush.music.utils.refreshPlaybackLoginContext
import app.hush.music.utils.toPlaybackAuthState
import okhttp3.Dns
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.PrintWriter
import java.io.StringWriter
import java.net.Proxy
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

@HiltAndroidApp
class App :
    Application(),
    SingletonImageLoader.Factory {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile private var isInitialized = false
    private val didRunImageCacheTrim = AtomicBoolean(false)
    private val imageNetworkClientHolder =
        VersionedOkHttpClient(
            versionProvider = YouTube::okHttpNetworkVersion,
            baseBuilder = YouTube::newOkHttpClientBuilder,
        )

    private fun imageNetworkClient(): OkHttpClient =
        imageNetworkClientHolder.get {
            connectTimeout(15, TimeUnit.SECONDS)
            readTimeout(15, TimeUnit.SECONDS)
            followRedirects(true)
            followSslRedirects(true)
            addInterceptor(ArtworkNetworkUtils.artworkInterceptor())
        }

    private fun currentProcessName(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            val pid = android.os.Process.myPid()
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            activityManager
                ?.runningAppProcesses
                ?.firstOrNull { it.pid == pid }
                ?.processName
        }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this
        if (currentProcessName()?.endsWith(":crash") == true) {
            Timber.plant(Timber.DebugTree())
            return
        }
        BotGuardTokenGenerator.initialize(this)
        PreferenceStore.start(this)
        Timber.plant(Timber.DebugTree())
        try {
            Timber.plant(
                app.hush.music.utils
                    .GlobalLogTree(),
            )
        } catch (_: Exception) {
        }

        initializeCriticalSync()
        initializeDeferredAsync()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // WebView cleanup happens automatically on process death
    }

    private fun initializeCriticalSync() {
        CanvasArtworkPlaybackCache.init(this)
        HushCanvas.initialize(BuildConfig.CANVAS_BEARER_TOKEN)
        PaxsenixLyrics.setUserAgent("Hush", BuildConfig.VERSION_NAME)

        val locale = Locale.getDefault()
        val languageTag = locale.toLanguageTag().replace("-Hant", "")
        YouTube.locale =
            YouTubeLocale(
                gl = locale.country.takeIf { it in CountryCodeToName } ?: "US",
                hl =
                    locale.language.takeIf { it in LanguageCodeToName }
                        ?: languageTag.takeIf { it in LanguageCodeToName }
                        ?: "en",
            )
        if (languageTag == "zh-TW") {
            KuGou.useTraditionalChinese = true
        }
        LastFM.initialize(
            apiKey = BuildConfig.LASTFM_API_KEY,
            secret = BuildConfig.LASTFM_SECRET,
        )
    }

    private fun initializeDeferredAsync() {
        applicationScope.launch(Dispatchers.IO) {
            try {
                val prefs = dataStore.data.first()
                val currentVersionCode = BuildConfig.VERSION_CODE
                val lastVersionCode = prefs[LastLaunchedVersionCodeKey] ?: 0
                val shouldForceAuthRefresh = lastVersionCode != currentVersionCode

                if (shouldForceAuthRefresh) {
                    runCatching {
                        refreshPlaybackLoginContext(forceRefresh = true)
                    }.onFailure { throwable ->
                        Timber.e(throwable, "Failed to refresh playback login context on upgrade")
                        reportException(throwable)
                    }
                } else {
                    runCatching {
                        refreshPlaybackLoginContext(forceRefresh = false)
                    }.onFailure { throwable ->
                        Timber.w(throwable, "Failed to refresh playback login context")
                    }
                }

                if (shouldForceAuthRefresh) {
                    val backupBeforeUpdate = prefs[EnableBackupBeforeUpdateKey] ?: true
                    val autoBackupEnabledOnUpgrade = prefs[AutoBackupEnabledKey] ?: true
                    if (autoBackupEnabledOnUpgrade && backupBeforeUpdate) {
                        runCatching {
                            AutoBackupHelper.performBackup(this@App, "before_update")
                        }.onFailure { throwable ->
                            Timber.e(throwable, "Failed to create backup before app update")
                            reportException(throwable)
                        }
                    }
                    safeDataStoreEdit { settings ->
                        settings[LastLaunchedVersionCodeKey] = currentVersionCode
                    }
                }

                val autoBackupEnabled = prefs[AutoBackupEnabledKey] ?: true
                val backupFrequency =
                    prefs[AutoBackupFrequencyKey]?.let { raw ->
                        runCatching { AutoBackupFrequency.valueOf(raw) }.getOrNull()
                    } ?: if (prefs[EnableWeeklyAutoBackupKey] == true) {
                        AutoBackupFrequency.WEEKLY
                    } else {
                        AutoBackupFrequency.OFF
                    }
                val backupHour = prefs[AutoBackupHourKey] ?: 2
                val backupMinute = prefs[AutoBackupMinuteKey] ?: 0
                val backupDayOfWeek = prefs[AutoBackupDayOfWeekKey] ?: 1
                AutoBackupHelper.updateScheduledBackupWork(
                    this@App,
                    enabled = autoBackupEnabled,
                    frequency = backupFrequency,
                    hour = backupHour,
                    minute = backupMinute,
                    dayOfWeek = backupDayOfWeek,
                )

                runCatching {
                    val storageEntryPoint =
                        EntryPointAccessors.fromApplication(this@App, StorageEntryPoint::class.java)
                    LegacyStorageAutoImporter.runIfEnabled(
                        context = this@App,
                        importLegacyStorage = storageEntryPoint.importLegacyStorage(),
                    )
                }.onFailure { throwable ->
                    Timber.w(throwable, "Legacy storage auto-import failed")
                }

                IconUtils.setIcon(this@App, prefs[EnableDynamicIconKey] != false)

                prefs[ContentCountryKey]?.takeIf { it != SYSTEM_DEFAULT }?.let { country ->
                    YouTube.locale = YouTube.locale.copy(gl = country)
                }
                prefs[ContentLanguageKey]?.takeIf { it != SYSTEM_DEFAULT }?.let { lang ->
                    YouTube.locale = YouTube.locale.copy(hl = lang)
                }

                LastFmServiceConfig.fromPreferences(prefs).apply(prefs[LastFMSessionKey])

                ProxyUtils.applyYouTubeProxy(
                    enabled = prefs[ProxyEnabledKey] == true,
                    type = prefs[ProxyTypeKey].toEnum(defaultValue = Proxy.Type.HTTP),
                    host = prefs[ProxyHostKey],
                    port = prefs[ProxyPortKey],
                    username = prefs[ProxyUsernameKey],
                    password = prefs[ProxyPasswordKey],
                )
                YouTube.streamBypassProxy = YouTube.proxy != null && prefs[StreamBypassProxyKey] == true

                applyDnsOverHttpsSettings(
                    enabled = prefs[EnableDnsOverHttpsKey] ?: false,
                    provider = prefs[DnsOverHttpsProviderKey] ?: "Cloudflare",
                    customUrl = prefs[stringPreferencesKey("customDnsUrl")] ?: "https://",
                )

                if (prefs[IpRotationEnabledKey] == true) {
                    try {
                        YouTube.enableIpRotation()
                    } catch (e: Exception) {
                        reportException(e)
                    }
                }

                if (prefs[UseLoginForBrowse] != false) {
                    YouTube.useLoginForBrowse = true
                }

                // Pre-warm BotGuard token generator
                val initialVisitor = prefs[VisitorDataKey] ?: YouTube.visitorData
                if (!initialVisitor.isNullOrBlank()) {
                    applicationScope.launch(Dispatchers.IO) {
                        BotGuardTokenGenerator.preWarm(initialVisitor)
                    }
                }

                // Apply random theme on startup if enabled
                if (prefs[RandomThemeOnStartupKey] == true) {
                    val randomPalette = ThemePalettes.generateRandomPalette()
                    val seedPalette =
                        ThemeSeedPalette(
                            primary = randomPalette.primary,
                            secondary = randomPalette.secondary,
                            tertiary = randomPalette.tertiary,
                            neutral = randomPalette.neutral,
                        )
                    val encodedPalette = ThemeSeedPaletteCodec.encodeForPreference(seedPalette, "Random")
                    safeDataStoreEdit { settings ->
                        settings[CustomThemeColorKey] = encodedPalette
                    }
                }

                isInitialized = true
            } catch (e: Exception) {
                Timber.e(e, "Error during deferred initialization")
                reportException(e)
            }
        }

        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map {
                    Triple(
                        it[EnableDnsOverHttpsKey] ?: false,
                        it[DnsOverHttpsProviderKey] ?: "Cloudflare",
                        it[stringPreferencesKey("customDnsUrl")] ?: "https://",
                    )
                }.distinctUntilChanged()
                .collect { (enabled, provider, customUrl) ->
                    applyDnsOverHttpsSettings(enabled, provider, customUrl)
                }
        }

        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[PlayerStreamClientKey].toEnum(PlayerStreamClient.ANDROID_VR) }
                .distinctUntilChanged()
                .collect {
                    YTPlayerUtils.clearPlaybackAuthCaches()
                }
        }

        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[EnableDynamicIconKey] != false }
                .distinctUntilChanged()
                .collect { enabled ->
                    IconUtils.setIcon(this@App, enabled)
                }
        }

        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[IpVersionKey].toEnum(IpVersion.AUTO) }
                .distinctUntilChanged()
                .collect { ipVersion ->
                    YouTube.ipVersion = ipVersion
                }
        }

        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it.toPlaybackAuthState() }
                .distinctUntilChanged()
                .collect { authState ->
                    val previousFingerprint = YouTube.currentPlaybackAuthState().fingerprint
                    YouTube.authState = authState
                    if (previousFingerprint != authState.fingerprint) {
                        YTPlayerUtils.clearPlaybackAuthCaches()
                        val newSessionId = authState.sessionId
                        if (!newSessionId.isNullOrBlank()) {
                            BotGuardTokenGenerator.preWarm(newSessionId)
                        }
                    }
                }
        }

        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it.toPlaybackAuthState().visitorData }
                .distinctUntilChanged()
                .collect { visitorData ->
                    if (!visitorData.isNullOrBlank()) return@collect
                    YouTube
                        .visitorData()
                        .onFailure {
                            reportException(it)
                        }.getOrNull()
                        ?.also { newVisitorData ->
                            safeDataStoreEdit { settings ->
                                settings[VisitorDataKey] = newVisitorData
                            }
                        }
                }
        }

        try {
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val sw = StringWriter()
                    val pw = PrintWriter(sw)
                    throwable.printStackTrace(pw)
                    val stack = sw.toString()

                    val intent =
                        Intent(this@App, DebugActivity::class.java).apply {
                            putExtra(DebugActivity.EXTRA_STACK_TRACE, stack)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                    startActivity(intent)
                    try {
                        Thread.sleep(100)
                    } catch (_: InterruptedException) {
                    }
                } catch (e: Exception) {
                    reportException(e)
                } finally {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    exitProcess(2)
                }
            }
        } catch (e: Exception) {
            reportException(e)
        }
        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { prefs ->
                    LastFmServiceConfig.fromPreferences(prefs) to prefs[LastFMSessionKey]
                }.distinctUntilChanged()
                .collect { (serviceConfig, sessionKey) ->
                    serviceConfig.apply(sessionKey)
                }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val smartTrimmer = dataStore[SmartTrimmerKey] ?: false
        val imageCacheConfig = resolveImageDiskCacheConfig(dataStore[MaxImageCacheSizeKey])

        val diskCache =
            DiskCache
                .Builder()
                .directory(StorageLocationRepository.cacheDirectory(this, StorageFolderKind.IMAGE_CACHE))
                .maxSizeBytes(imageCacheConfig.maxSizeBytes)
                .build()

        if (smartTrimmer && imageCacheConfig.policy == CachePolicy.ENABLED && didRunImageCacheTrim.compareAndSet(false, true)) {
            applicationScope.launch(Dispatchers.IO) { trimImageDiskCache(diskCache) }
        }

        return ImageLoader
            .Builder(this)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { imageNetworkClient() }))
            }
            .crossfade(true)
            .allowHardware(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            .diskCache(diskCache)
            .diskCachePolicy(imageCacheConfig.policy)
            .build()
    }

    private fun trimImageDiskCache(diskCache: DiskCache) {
        try {
            val limitBytes = diskCache.maxSize
            if (limitBytes <= 0L || limitBytes == Long.MAX_VALUE) return

            val dir = java.io.File(diskCache.directory.toString())
            if (!dir.exists()) return

            val files =
                dir
                    .walkTopDown()
                    .filter { it.isFile }
                    .sortedBy { it.lastModified() }
                    .toList()
            var currentSize = files.sumOf { it.length() }
            if (currentSize <= limitBytes) return

            for (file in files) {
                if (currentSize <= limitBytes) break
                val size = file.length()
                if (runCatching { file.delete() }.getOrDefault(false)) currentSize -= size
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        lateinit var instance: App
            private set

        fun forgetAccount(
            context: Context,
            clearWebAuthSession: Boolean = true,
        ) {
            if (clearWebAuthSession) {
                clearPlaybackWebAuthSession(context)
            }
            CoroutineScope(Dispatchers.IO).launch {
                context.safeDataStoreEdit { settings ->
                    settings.clearPlaybackAuthSession()
                }
            }
        }
    }

    private fun applyDnsOverHttpsSettings(
        enabled: Boolean,
        provider: String,
        customUrl: String,
    ) {
        if (enabled) {
            val url = YouTube.resolveDnsProviderUrl(provider, customUrl)
            if (url != null) {
                runCatching {
                    YouTube.dns = YouTube.createDnsOverHttps(url)
                    Timber.i("Configured DNS over HTTPS via $provider")
                }.onFailure { throwable ->
                    Timber.e(throwable, "Failed to configure DNS over HTTPS via $provider")
                    YouTube.dns = Dns.SYSTEM
                    reportException(throwable)
                }
            } else {
                Timber.w("Invalid DNS over HTTPS configuration for provider $provider")
                YouTube.dns = Dns.SYSTEM
            }
        } else {
            YouTube.dns = Dns.SYSTEM
        }
        YTPlayerUtils.invalidateStreamClient()
    }
}

internal data class ImageDiskCacheConfig(
    val policy: CachePolicy,
    val maxSizeBytes: Long,
)

internal fun resolveImageDiskCacheConfig(maxImageCacheSizeMb: Int?): ImageDiskCacheConfig {
    val sizeMb = maxImageCacheSizeMb ?: 512
    if (sizeMb == 0) return ImageDiskCacheConfig(policy = CachePolicy.DISABLED, maxSizeBytes = 1L)
    if (sizeMb < 0) return ImageDiskCacheConfig(policy = CachePolicy.ENABLED, maxSizeBytes = Long.MAX_VALUE)
    val bytesPerMb = 1024L * 1024L
    val safeSizeMb = sizeMb.toLong().coerceAtMost(Long.MAX_VALUE / bytesPerMb)
    return ImageDiskCacheConfig(policy = CachePolicy.ENABLED, maxSizeBytes = safeSizeMb * bytesPerMb)
}
