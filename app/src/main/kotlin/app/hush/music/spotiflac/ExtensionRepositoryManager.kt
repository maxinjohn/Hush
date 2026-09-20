package app.hush.music.spotiflac

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.hush.music.utils.PreferenceStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
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
        private val CachedRegistryKey = stringPreferencesKey("spotiflac_cached_registry")

        private const val MAX_REGISTRY_BYTES = 512 * 1024

        /** Longest failure summary a source row keeps for its one-line test result. */
        private const val MAX_TEST_ERROR_LENGTH = 90

        private val REGISTRY_URLS = listOf(
            "https://raw.githubusercontent.com/spotiflacapp/spotiflac-extension/main/registry.json",
            "https://raw.githubusercontent.com/zarzet/spotiflac-extension/main/registry.json",
        )

        private val BUILTIN_SOURCES = listOf(
            ExtensionSource(
                id = "tidal-web",
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
                id = "qobuz-web",
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
            // Keep the last verified registry snapshot. A transient GitHub/network
            // failure must not make already-known sources disappear from Settings.
            val effectiveRemoteExtensions =
                if (remoteExtensions.isNotEmpty()) {
                    saveCachedExtensions(remoteExtensions)
                    remoteExtensions
                } else {
                    loadCachedExtensions()
                }
            val mergedExtensions = mergeWithBuiltin(effectiveRemoteExtensions)
            val enabledIds = loadEnabledIds()
            val orderedIds = loadOrderedIds()

            val sources = buildSourceList(mergedExtensions, enabledIds, orderedIds)
            _sources.value = sources

            Timber.tag(TAG).d("Synced ${sources.size} sources from ${REGISTRY_URLS.size} repos")
        } catch (e: CancellationException) {
            // A cancelled sync is not a failed one. Swallowing this would run the
            // "registry is down, publish builtins only" fallback on a coroutine that
            // has already been cancelled - overwriting the live source list with the
            // built-in subset because the caller went away.
            throw e
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
                val response = httpClient.get(url) {
                    header("Accept", "application/json")
                    header("Cache-Control", "no-cache")
                }
                if (response.status != HttpStatusCode.OK) {
                    Timber.tag(TAG).w("Registry $url returned HTTP ${response.status.value}")
                    continue
                }
                val body = response.bodyAsText()
                if (body.toByteArray(Charsets.UTF_8).size > MAX_REGISTRY_BYTES) {
                    Timber.tag(TAG).w("Ignoring oversized registry from $url")
                    continue
                }
                val registry = json.decodeFromString<ExtensionRegistry>(body)
                val safeExtensions = registry.extensions.filter { extension ->
                    if (!extension.isSafeRegistryEntry) {
                        Timber.tag(TAG).w("Ignoring unsafe registry entry ${extension.id} from $url")
                        false
                    } else {
                        true
                    }
                }
                allExtensions.addAll(safeExtensions.map { ext ->
                    ext.copy(repositoryId = url)
                })
                Timber.tag(TAG).d("Fetched ${safeExtensions.size}/${registry.extensions.size} safe extensions from $url")
            } catch (e: CancellationException) {
                // Reached through `httpClient.get`: without this the cancellation is
                // reported as "this registry URL failed" and the loop moves on to
                // issue another request on a coroutine that is already cancelled.
                throw e
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
                    icon = ext.icon ?: ext.iconUrl ?: existing.icon,
                    downloadUrl = ext.downloadUrl ?: existing.downloadUrl,
                    sha256 = ext.sha256 ?: existing.sha256,
                    minAppVersion = ext.minAppVersion ?: existing.minAppVersion,
                    category = ext.category ?: existing.category,
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
            current[index] = current[index].copy(testState = state, testError = shortTestError(error))
            _sources.value = current
        }
    }

    /**
     * A one-line summary of a failed test, for the row that shows it.
     *
     * A gateway refusal arrives as a whole response body - HTML, a JSON envelope, or an
     * exception with the request in its message - and the row has one line to say what
     * happened. Taking the first line and clipping it keeps the record about the result
     * rather than about the transport.
     */
    private fun shortTestError(error: String?): String? {
        val firstLine = error.orEmpty()
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return null
        return if (firstLine.length <= MAX_TEST_ERROR_LENGTH) {
            firstLine
        } else {
            firstLine.take(MAX_TEST_ERROR_LENGTH - 1).trimEnd() + "\u2026"
        }
    }

    /**
     * The order the user arranged in Audio Sources, readable without suspending.
     *
     * [sources] is empty until the first registry sync completes, and a resolve can be
     * asked for its candidates before that - so the persisted order has to be reachable
     * synchronously. Without it the caller falls back to enumerating the extension
     * packages on disk, which is alphabetical, and the sweep starts on whichever source
     * happens to sort first instead of the one the user put first.
     */
    fun savedSourceOrder(): List<String> {
        val live = _sources.value
        if (live.isNotEmpty()) return live.filter { it.enabled }.map { it.source.id }
        val savedOrder = PreferenceStore.get(SourceOrderKey)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            .orEmpty()
        val savedEnabled = PreferenceStore.get(EnabledSourcesKey)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            ?.toSet()
        val defaultOrder = BUILTIN_SOURCES.map { it.id }
        val order = savedOrder.ifEmpty { defaultOrder }
        val enabled = savedEnabled ?: defaultOrder.toSet()
        return order.filter { it in enabled }
    }

    fun getEnabledSourceIds(): List<String> {
        // The registry ID is the extension contract identifier (for example
        // `tidal-web` and `qobuz-web`). Do not replace it with providerKey:
        // current SpotiFLAC extensions and their signed-session/download
        // contracts use the extension ID when selecting a source. Keep the
        // existing enabled list intact while the runtime is being migrated;
        // older registries may not declare `types` at all.
        return _sources.value
            .filter { it.enabled }
            .map { it.source.id }
    }

    fun getSourceForId(sourceId: String): ExtensionSource? {
        return _sources.value.find { it.source.id == sourceId }?.source
    }

    private suspend fun loadCachedExtensions(): List<ExtensionSource> = runCatching {
        val raw = dataStore?.data?.map { prefs -> prefs[CachedRegistryKey] }?.first()
            ?: return@runCatching emptyList()
        json.decodeFromString<ExtensionRegistry>(raw).extensions
    }.getOrDefault(emptyList())

    private suspend fun saveCachedExtensions(extensions: List<ExtensionSource>) {
        val ds = dataStore ?: return
        runCatching {
            ds.edit { prefs ->
                prefs[CachedRegistryKey] = json.encodeToString(
                    ExtensionRegistry(
                        updatedAt = System.currentTimeMillis().toString(),
                        extensions = extensions,
                    ),
                )
            }
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "Failed to persist verified extension registry")
        }
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
