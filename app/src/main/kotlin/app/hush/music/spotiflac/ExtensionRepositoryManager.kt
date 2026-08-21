package app.hush.music.spotiflac

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class SourceWithState(
    val source: ExtensionSource,
    val enabled: Boolean,
    val priority: Int,
    val testState: SourceTestState = SourceTestState.IDLE,
    val testError: String? = null,
)

enum class SourceTestState {
    IDLE,
    TESTING,
    SUCCESS,
    FAILED,
}

@Singleton
class ExtensionRepositoryManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: HttpClient,
) {
    companion object {
        private const val TAG = "ExtensionRepoManager"

        @Volatile
        private var instance: ExtensionRepositoryManager? = null

        fun getInstance(): ExtensionRepositoryManager {
            return instance
                ?: throw IllegalStateException("ExtensionRepositoryManager not initialized")
        }

        val EnabledSourcesKey = stringPreferencesKey("spotiflac_enabled_sources")
        val SourceOrderKey = stringPreferencesKey("spotiflac_source_order")

        private val REGISTRY_URLS = listOf(
            "https://raw.githubusercontent.com/spotiflacapp/spotiflac-extension/main/registry.json",
            "https://raw.githubusercontent.com/zarzet/spotiflac-extension/main/registry.json",
        )

        private val BUILTIN_SOURCES = listOf(
            ExtensionSource(
                id = "tidal",
                name = "Tidal",
                description = "Lossless audio from Tidal",
                author = "SpotiFLAC",
                relayUrl = "https://api.zarz.moe/v2",
                providerKey = "tidal",
            ),
            ExtensionSource(
                id = "deezer",
                name = "Deezer",
                description = "High quality audio from Deezer",
                author = "SpotiFLAC",
                relayUrl = "https://api.zarz.moe/v2",
                providerKey = "deezer",
            ),
            ExtensionSource(
                id = "qobuz",
                name = "Qobuz",
                description = "Hi-Res audio from Qobuz",
                author = "SpotiFLAC",
                relayUrl = "https://api.zarz.moe/v2",
                providerKey = "qobuz",
            ),
            ExtensionSource(
                id = "amazon",
                name = "Amazon Music",
                description = "HD audio from Amazon Music",
                author = "SpotiFLAC",
                relayUrl = "https://api.zarz.moe/v2",
                providerKey = "amazon",
            ),
            ExtensionSource(
                id = "soundcloud",
                name = "SoundCloud",
                description = "Audio from SoundCloud",
                author = "SpotiFLAC",
                relayUrl = "https://api.zarz.moe/v2",
                providerKey = "soundcloud",
            ),
            ExtensionSource(
                id = "apple-music",
                name = "Apple Music",
                description = "Audio from Apple Music",
                author = "SpotiFLAC",
                relayUrl = "https://api.zarz.moe/v2",
                providerKey = "apple-music",
            ),
            ExtensionSource(
                id = "spotify-web",
                name = "Spotify Web",
                description = "Audio via Spotify Web API",
                author = "SpotiFLAC",
                relayUrl = "https://api.zarz.moe/v2",
                providerKey = "spotify-web",
            ),
            ExtensionSource(
                id = "pandora",
                name = "Pandora",
                description = "Audio from Pandora",
                author = "SpotiFLAC",
                relayUrl = "https://api.zarz.moe/v2",
                providerKey = "pandora",
            ),
        )
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _sources = MutableStateFlow<List<SourceWithState>>(emptyList())
    val sources: StateFlow<List<SourceWithState>> = _sources.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val mutex = Mutex()
    private var dataStore: DataStore<Preferences>? = null

    fun initialize(dataStore: DataStore<Preferences>) {
        this.dataStore = dataStore
        instance = this
    }

    suspend fun syncRegistries() {
        _isSyncing.value = true
        try {
            val remoteExtensions = fetchAllExtensions()
            val mergedExtensions = mergeWithBuiltin(remoteExtensions)
            val enabledIds = loadEnabledIds()
            val orderedIds = loadOrderedIds()

            val sources = buildSourceList(mergedExtensions, enabledIds, orderedIds)
            _sources.value = sources

            Timber.tag(TAG).d("Synced ${sources.size} sources from ${REGISTRY_URLS.size} repos")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to sync registries, using builtins")
            val enabledIds = loadEnabledIds()
            val orderedIds = loadOrderedIds()
            _sources.value = buildSourceList(BUILTIN_SOURCES, enabledIds, orderedIds)
        } finally {
            _isSyncing.value = false
        }
    }

    private suspend fun fetchAllExtensions(): List<ExtensionSource> {
        val allExtensions = mutableListOf<ExtensionSource>()

        for (url in REGISTRY_URLS) {
            try {
                val response = httpClient.get(url)
                val body = response.bodyAsText()
                val registry = json.decodeFromString<ExtensionRegistry>(body)
                allExtensions.addAll(registry.extensions.map { ext ->
                    ext.copy(repositoryId = url)
                })
                Timber.tag(TAG).d("Fetched ${registry.extensions.size} extensions from $url")
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to fetch registry from $url")
            }
        }

        return allExtensions
    }

    private fun mergeWithBuiltin(remote: List<ExtensionSource>): List<ExtensionSource> {
        val merged = mutableMapOf<String, ExtensionSource>()

        for (builtin in BUILTIN_SOURCES) {
            merged[builtin.id] = builtin
        }

        for (ext in remote) {
            val existing = merged[ext.id]
            if (existing == null) {
                merged[ext.id] = ext
            } else {
                merged[ext.id] = existing.copy(
                    version = ext.version.ifBlank { existing.version },
                    description = ext.description.ifBlank { existing.description },
                    relayUrl = ext.relayUrl ?: existing.relayUrl,
                    providerKey = ext.providerKey ?: existing.providerKey,
                    repositoryId = ext.repositoryId.ifBlank { existing.repositoryId },
                )
            }
        }

        return merged.values.toList()
    }

    private fun buildSourceList(
        extensions: List<ExtensionSource>,
        enabledIds: Set<String>,
        orderedIds: List<String>,
    ): List<SourceWithState> {
        val sourceMap = extensions.associateBy { it.id }

        val ordered = mutableListOf<SourceWithState>()
        for (id in orderedIds) {
            val source = sourceMap[id] ?: continue
            ordered.add(SourceWithState(
                source = source,
                enabled = id in enabledIds,
                priority = ordered.size + 1,
            ))
        }

        for (source in extensions) {
            if (source.id !in orderedIds) {
                ordered.add(SourceWithState(
                    source = source,
                    enabled = source.id in enabledIds,
                    priority = ordered.size + 1,
                ))
            }
        }

        return ordered
    }

    suspend fun setSourceEnabled(sourceId: String, enabled: Boolean) = mutex.withLock {
        val current = _sources.value.toMutableList()
        val index = current.indexOfFirst { it.source.id == sourceId }
        if (index >= 0) {
            current[index] = current[index].copy(enabled = enabled)
            _sources.value = current
            saveEnabledIds(current.filter { it.enabled }.map { it.source.id }.toSet())
            saveOrderedIds(current.map { it.source.id })
        }
    }

    suspend fun moveSource(fromIndex: Int, toIndex: Int) = mutex.withLock {
        val current = _sources.value.toMutableList()
        if (fromIndex < 0 || fromIndex >= current.size) return@withLock
        if (toIndex < 0 || toIndex >= current.size) return@withLock

        val item = current.removeAt(fromIndex)
        current.add(toIndex, item)

        val reordered = current.mapIndexed { idx, s -> s.copy(priority = idx + 1) }
        _sources.value = reordered
        saveOrderedIds(reordered.map { it.source.id })
    }

    suspend fun setSourceTestState(sourceId: String, state: SourceTestState, error: String? = null) = mutex.withLock {
        val current = _sources.value.toMutableList()
        val index = current.indexOfFirst { it.source.id == sourceId }
        if (index >= 0) {
            current[index] = current[index].copy(testState = state, testError = error)
            _sources.value = current
        }
    }

    fun getEnabledSourceIds(): List<String> {
        return _sources.value.filter { it.enabled }.map { it.source.providerKey ?: it.source.id }
    }

    fun getSourceForId(sourceId: String): ExtensionSource? {
        return _sources.value.find { it.source.id == sourceId }?.source
    }

    private suspend fun loadEnabledIds(): Set<String> {
        return dataStore?.data?.map { prefs ->
            prefs[EnabledSourcesKey]?.split(",")?.filter { it.isNotBlank() }?.toSet()
                ?: BUILTIN_SOURCES.map { it.id }.toSet()
        }?.first() ?: BUILTIN_SOURCES.map { it.id }.toSet()
    }

    private suspend fun loadOrderedIds(): List<String> {
        return dataStore?.data?.map { prefs ->
            prefs[SourceOrderKey]?.split(",")?.filter { it.isNotBlank() }
                ?: BUILTIN_SOURCES.map { it.id }
        }?.first() ?: BUILTIN_SOURCES.map { it.id }
    }

    private suspend fun saveEnabledIds(ids: Set<String>) {
        val ds = dataStore
        if (ds == null) {
            Timber.tag(TAG).w("dataStore not initialized, cannot save enabled IDs")
            return
        }
        ds.edit { prefs ->
            prefs[EnabledSourcesKey] = ids.joinToString(",")
        }
    }

    private suspend fun saveOrderedIds(ids: List<String>) {
        val ds = dataStore
        if (ds == null) {
            Timber.tag(TAG).w("dataStore not initialized, cannot save ordered IDs")
            return
        }
        ds.edit { prefs ->
            prefs[SourceOrderKey] = ids.joinToString(",")
        }
    }
}
