package app.hush.music.utils

import android.content.Context
import app.hush.music.innertube.config.RemoteCipherConfig
import app.hush.music.BuildConfig
import app.hush.music.utils.dataStore
import app.hush.music.utils.safeDataStoreEdit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

    private var fetchInProgress = false

    fun getCachedConfig(): RemoteCipherConfig? = cachedConfig

    suspend fun getConfig(): RemoteCipherConfig = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        if (cachedConfig != null && (now - lastFetchTime) < RemoteCipherConfig.CONFIG_CACHE_DURATION_MS) {
            return@withContext cachedConfig!!
        }

        if (cachedConfig == null) {
            val stored = loadFromDataStore()
            if (stored != null) {
                cachedConfig = stored
                lastFetchTime = now
                return@withContext stored
            }
        }

        if (fetchInProgress) return@withContext cachedConfig ?: RemoteCipherConfig.EMPTY

        fetchInProgress = true
        try {
            val config = fetchRemoteConfig()
            if (config != null) {
                cachedConfig = config
                lastFetchTime = now
                saveToDataStore(config)
                return@withContext config
            }
        } finally {
            fetchInProgress = false
        }

        cachedConfig ?: RemoteCipherConfig.EMPTY
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

    private suspend fun loadFromDataStore(): RemoteCipherConfig? = runCatching {
        val raw = context.dataStore.data.first()[Companion.CIPHER_CONFIG_KEY] ?: return@runCatching null
        RemoteCipherConfig.parse(raw).getOrNull()
    }.getOrNull()

    private suspend fun saveToDataStore(config: RemoteCipherConfig) {
        runCatching {
            context.safeDataStoreEdit { prefs ->
                prefs[Companion.CIPHER_CONFIG_KEY] = json.encodeToString(config)
            }
        }
    }

    fun invalidateCache() {
        cachedConfig = null
        lastFetchTime = 0L
    }

    companion object {
        val CIPHER_CONFIG_KEY = androidx.datastore.preferences.core.stringPreferencesKey("remote_cipher_config")

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
