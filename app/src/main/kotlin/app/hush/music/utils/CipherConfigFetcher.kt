package app.hush.music.utils

import android.content.Context
import app.hush.music.innertube.config.RemoteCipherConfig
import app.hush.music.BuildConfig
import app.hush.music.utils.dataStore
import app.hush.music.utils.safeDataStoreEdit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CipherConfigFetcher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedConfig: RemoteCipherConfig? = null

    @Volatile
    private var lastFetchTime: Long = 0L

    private val fetchMutex = Mutex()

    private data class StoredConfig(
        val config: RemoteCipherConfig,
        val fetchedAt: Long,
    )

    fun getCachedConfig(): RemoteCipherConfig? = cachedConfig

    suspend fun getConfig(): RemoteCipherConfig = withContext(Dispatchers.IO) {
        fetchMutex.withLock {
            if (cachedConfig == null) {
                loadFromDataStore()?.let { stored ->
                    cachedConfig = stored.config
                    lastFetchTime = stored.fetchedAt
                }
            }

            val now = System.currentTimeMillis()
            val currentConfig = cachedConfig
            if (currentConfig != null && isFresh(lastFetchTime, now)) {
                return@withLock currentConfig
            }

            val config = fetchRemoteConfig()
            if (config != null) {
                val fetchedAt = System.currentTimeMillis()
                cachedConfig = config
                lastFetchTime = fetchedAt
                saveToDataStore(config, fetchedAt)
                return@withLock config
            }

            cachedConfig ?: RemoteCipherConfig.EMPTY
        }
    }

    fun getConfigBlocking(): RemoteCipherConfig = runBlocking { getConfig() }

    private suspend fun fetchRemoteConfig(): RemoteCipherConfig? = withContext(Dispatchers.IO) {
        runCatching {
            val urls = listOfNotNull(
                "https://raw.githubusercontent.com/anomalyco/hush-config/main/cipher.json",
                RemoteCipherConfig.defaultConfigUrl(),
            )

            for (url in urls) {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Hush/${BuildConfig.VERSION_NAME}")
                    .header("Accept", "application/json")
                    .get()
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    response.close()
                    continue
                }
                val body = response.body?.string() ?: run {
                    response.close()
                    continue
                }
                response.close()

                val config = RemoteCipherConfig.parse(body).getOrNull()
                if (config != null && config.version > 0) return@runCatching config
            }

            null
        }.getOrNull()
    }

    private fun isFresh(
        fetchedAt: Long,
        now: Long,
    ): Boolean =
        fetchedAt > 0L &&
            now >= fetchedAt &&
            now - fetchedAt < RemoteCipherConfig.CONFIG_CACHE_DURATION_MS

    private suspend fun loadFromDataStore(): StoredConfig? = runCatching {
        val preferences = context.dataStore.data.first()
        val raw = preferences[Companion.CIPHER_CONFIG_KEY] ?: return@runCatching null
        val config = RemoteCipherConfig.parse(raw).getOrNull() ?: return@runCatching null
        StoredConfig(
            config = config,
            fetchedAt = preferences[Companion.CIPHER_CONFIG_FETCHED_AT_KEY] ?: 0L,
        )
    }.getOrNull()

    private suspend fun saveToDataStore(
        config: RemoteCipherConfig,
        fetchedAt: Long,
    ) {
        runCatching {
            context.safeDataStoreEdit { prefs ->
                prefs[Companion.CIPHER_CONFIG_KEY] = json.encodeToString(config)
                prefs[Companion.CIPHER_CONFIG_FETCHED_AT_KEY] = fetchedAt
            }
        }
    }

    fun invalidateCache() {
        cachedConfig = null
        lastFetchTime = 0L
    }

    companion object {
        val CIPHER_CONFIG_KEY = androidx.datastore.preferences.core.stringPreferencesKey("remote_cipher_config")
        val CIPHER_CONFIG_FETCHED_AT_KEY = androidx.datastore.preferences.core.longPreferencesKey("remote_cipher_config_fetched_at")

        @Volatile
        private var instance: CipherConfigFetcher? = null

        @OptIn(DelicateCoroutinesApi::class)
        fun init(context: Context) {
            if (instance == null) {
                instance = CipherConfigFetcher(context.applicationContext as Context)
                instance?.let { fetcher ->
                    kotlinx.coroutines.GlobalScope.launch {
                        fetcher.getConfig()
                    }
                }
            }
        }

        fun getInstance(): CipherConfigFetcher? = instance

        fun getConfigSync(): RemoteCipherConfig = instance?.getCachedConfig() ?: RemoteCipherConfig.EMPTY
    }
}
