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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether a source is switched on, given what the user has chosen and what this install has listed.
 *
 * Four cases, and the first two are the whole reason this is a function rather than an expression
 * inside the list builder:
 *
 *  - [storedEnabledIds] is null: nobody has ever switched anything on or off on this install, so the
 *    list starts with everything this build can play switched on. Absent and empty are deliberately
 *    different values here - an install nobody has touched must not read as an install whose owner
 *    switched every source off. This is what makes a freshly installed Hush try Deezer, Qobuz, Tidal
 *    and the rest without a visit to Audio Sources first: a default that only enabled Hush's own
 *    built-ins left every repository-published source switched off, and with them the resolve order,
 *    on the one install that had made no choice to respect.
 *  - [knownIds] is null: this install has not synced since the record existed, so nothing is assumed
 *    about any source and only [storedEnabledIds] decides. That is exactly the behaviour an upgrade
 *    had before, so no source a user had switched off comes back on.
 *  - An id that is known and not stored-on is a source the user switched off, and stays off.
 *  - An id that is *unknown* has never been shown to anyone, so it follows the repository that
 *    published it and arrives switched on. Without this an install that added a repository got a
 *    column of switches reading off and no way to tell "new" from "refused".
 *
 * A source this build cannot play is never on, whatever is stored: a switch that answers yes while
 * every resolve order ignores it is a lie. What "cannot play" means is answered by `isPlayable` - a
 * module index's JavaScript is playable here because Hush runs it in its own sandbox, and only a
 * source with nothing behind it at all stays off.
 *
 * Extracted, and internal, so the rule can be pinned without an Android `Context` - `sources` is
 * built inside a singleton that needs a `DataStore` and an HTTP client, and a default that is wrong
 * in either direction is silent.
 */
internal fun sourceEnabledByDefault(
    source: ExtensionSource,
    storedEnabledIds: Set<String>?,
    knownIds: Set<String>?,
): Boolean {
    if (!source.isPlayable) return false
    // Nothing has ever been stored: the defaults are the list, in the order the sync published it.
    if (storedEnabledIds == null) return true
    if (source.id in storedEnabledIds) return true
    return knownIds != null && source.id !in knownIds
}

/**
 * What a source that no repository declares is called on its row: Hush ships it itself.
 *
 * Pandora is the one such source to have been seen in the wild. It is in Hush's own record of its
 * sources, no registry declares it, so nothing ever fills in an origin for it - and the row read as
 * the only row in the list naming no repository at all.
 */
internal const val BUILT_IN_SOURCE_ORIGIN = "built-in"

/**
 * Which repository a source row came from, as the row prints it - or null when the row's own name
 * already says it.
 *
 * The screen's list promises this on every row ("Every row names the repository it came from"), and
 * an inline lookup cannot keep that promise: it answers only for the sources a repository row
 * happens to claim, and silently answers nothing for the rest. On the reporting device that was
 * Pandora - shipped by Hush, declared by no registry, so its `repositoryId` is empty and its row
 * printed no repository while its own check line said "· built-in", two lines of the same row
 * disagreeing about whether the origin was known. Naming the origin instead of looking one up is
 * what makes the promise keepable, and the same string the repository section above draws is what a
 * row shows, so the two cannot disagree.
 *
 * The cases, in order:
 *  - a repository row owns the id: that row's label, which is the user's own nickname when they gave
 *    one;
 *  - no id at all: [BUILT_IN_SOURCE_ORIGIN], the shipped sources Hush's own record defines;
 *  - an id no row claims: named from the id itself, which for everything a repository published is
 *    that repository's address. This is the case before the first sync has landed, and a name worth
 *    having early rather than none at all - a row with no origin reads as a source with no
 *    repository behind it, which is the one thing it never is.
 *
 * Null is only for a row that already says the repository in its *name*: a BitChord module index
 * labels its own entries `[<index>] (module)`, and the bracket under that would be the same
 * repository twice in a row - as it read under the `gglos` addon, which is named after the host its
 * repository is labelled with (`gglos` above `[gglos]`).
 */
internal fun sourceProvenanceLine(
    source: ExtensionSource,
    repositories: List<RepositoryRow>,
): String? {
    val id = source.repositoryId.trim()
    val label =
        repositories
            .firstOrNull { id.isNotEmpty() && it.url.equals(id, ignoreCase = true) }
            ?.label
            ?.takeIf { it.isNotBlank() }
            ?: if (id.isEmpty()) BUILT_IN_SOURCE_ORIGIN else ExtensionRepositories.label(id)
    // The same rule the player's label uses, so a row cannot repeat its own name: a bracket is
    // dropped when the entry already carries the index, and when the repository's name is the
    // source's name word for word (`gglos` over `gglos`). The name still says where it came from -
    // that is what the shared predicate checks - and the row is one line shorter for it.
    //
    // Read against the name the row *draws* rather than the registry's own field, because the two
    // differ for a source the app names itself: the `gglos` addon is drawn as `Tidal · gglos`, and
    // comparing its repository against the raw `gglos` would ask a question about a line nobody sees.
    val shown =
        SpotiFLACSourceName.name(
            sourceId = source.id,
            name = source.displayName,
        )
    if (SpotiFLACSourceName.namesRepository(shown, label)) return null
    return "[$label]"
}

data class SourceWithState(
    val source: ExtensionSource,
    val enabled: Boolean,
    val priority: Int,
    val testState: SourceTestState = SourceTestState.IDLE,
    val testError: String? = null,
)

/**
 * What the Audio Sources switches say, and whether this install has ever said anything.
 *
 * The difference between an empty [enabledInOrder] and [known] being false is the whole reason this
 * is a value rather than a plain list. "The user switched every source off" and "nothing has been
 * recorded yet" are the same list and two different instructions, and a caller that cannot tell them
 * apart has to guess - which is exactly the guess that put a switched-off source back into the
 * playback sweep. Measured on the reporting device: only `bugs-music`, `melon-music`,
 * `genie-music` (metadata providers, none of which can download) and the two BitChord modules were
 * switched on, and the sweep walked `amazon, jiosaavn, qobuz-web, soundcloud, tidal-web,
 * ytmusic-spotiflac` - every package on disk, including the YouTube Music source the user had just
 * switched off, which is what served the track.
 */
data class SourceSwitches(
    /** The switched-on sources, in the order the user arranged them. */
    val enabledInOrder: List<String>,
    /**
     * False when no choice is on record at all: an install that has never touched a source switch.
     *
     * A caller may fall back to what is installed on disk in that case and only that case - an
     * install with nothing to honour is not an install that said no.
     */
    val known: Boolean,
)

enum class SourceTestState {
    IDLE,
    TESTING,
    SUCCESS,
    FAILED,
}

/**
 * One repository, as the list draws it.
 *
 * A row rather than a URL plus three maps, because every field on it is read together: what a
 * repository *is* (its format), what it declares, whether Hush reads it at all, and what the user
 * called it. Split across flows, the screen would draw a name from one and a count from another and
 * show a disabled repository's stale count beside the note that says it is off.
 */
data class RepositoryRow(
    val url: String,
    /** The user's nickname when they gave one, else the repository's own name, else the host. */
    val label: String,
    val nickname: String? = null,
    /** Null when Hush has not read this repository yet, or could not. */
    val kind: RepositoryKind? = null,
    /** False means its sources are not fetched, not listed and not resolved against. */
    val enabled: Boolean = true,
    /** Hush's own registry: it can be switched off, but not deleted. */
    val isBundled: Boolean = false,
    /** Shipped or added: the user may take it out of the list. */
    val removable: Boolean = false,
    /**
     * One of the repositories Hush ships as a starting point but does not own.
     *
     * Separate from [isBundled] and from [removable], because the row used to read the two as
     * "shipped with Hush" and [removable] is true for *every* repository Hush does not own - so a
     * URL the user added themselves was labelled "Shipped with Hush", which is the one thing it
     * is not, on the row whose whole job is to say where it came from.
     */
    val isShippedDefault: Boolean = false,
    /** How many sources the repository declares, or null when it was not read. */
    val declaredSources: Int? = null,
    /** Why it could not be read, when it could not. */
    val error: String? = null,
)

/**
 * What happened when a repository URL was added.
 *
 * A rejection carries the reason rather than a boolean, because every way this fails is a
 * different thing to say: a URL that is not https, one Hush already ships, one already in the
 * list, one that did not answer, and one that answered with nothing usable. The dialog shows
 * the sentence as-is.
 */
sealed interface RepositoryAddResult {
    data class Added(
        val url: String,
        val label: String,
        val sources: Int,
        val kind: RepositoryKind,
    ) : RepositoryAddResult

    data class Rejected(
        val reason: String,
    ) : RepositoryAddResult
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

        /**
         * The switched-on extension ids in one preferences snapshot, or null when none is recorded.
         *
         * Static, because the answer is needed before [getInstance] can be called at all: the
         * app-start renewal runs in `Application.onCreate`, before the playback service has built
         * this registry. Asking the singleton there threw `ExtensionRepositoryManager not
         * initialized`, the caller caught it, and `null` - which means "nobody has chosen" - was
         * the answer, so every session on the device was renewed, including four sources the user
         * had switched off. The stored value is the one answer that exists that early.
         *
         * An empty value is a choice too (every source switched off) and answers `emptySet()`, not
         * null: the two must stay different, because null keeps a fresh install's defaults alive.
         */
        internal fun switchedOnExtensionIds(prefs: Preferences?): Set<String>? =
            prefs?.get(EnabledSourcesKey)?.let { stored ->
                stored.split(",")
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            }

        /**
         * Every source id this install has ever *listed*, one per line.
         *
         * The on set alone cannot answer the question that decides a new source's default, because
         * an absent id means two different things: a source the user switched off, and a source the
         * app has never shown them. This is what tells the two apart - an id that is not here is one
         * nobody has formed an opinion about, so it follows the repository that published it and
         * arrives switched on. An id that is here and not in the on set is a source the user chose
         * to switch off, and stays off through every later sync. Absent entirely (an install that has
         * not synced since this existed) means "assume known", which is exactly the behaviour this
         * install had before - nothing that was off comes back on by an update.
         */
        private val KnownSourcesKey = stringPreferencesKey("spotiflac_known_sources")
        private val CachedRegistryKey = stringPreferencesKey("spotiflac_cached_registry")

        /**
         * The repositories the user switched off, one URL per line.
         *
         * Off is a *fetch* switch and not a filter applied afterwards, which is the whole point: a
         * repository nobody reads cannot contribute a source, a version to update to, a package to
         * install or a check to solve. A flag applied to the merged list would leave all of those
         * running - the list would look right and the app would still be using the sources.
         */
        private val DisabledRepositoriesKey = stringPreferencesKey("spotiflac_disabled_repositories")

        /**
         * The shipped repositories the user removed.
         *
         * A default repository is shipped *and* removable, so removing one has to be remembered
         * somewhere other than "not in the custom list" - otherwise it would return on the next
         * launch, and a row that comes back by itself is worse than one that never left.
         */
        private val RemovedRepositoriesKey = stringPreferencesKey("spotiflac_removed_repositories")

        /**
         * What the user calls a repository, one `url|name` per line.
         *
         * A label rather than a replacement: the URL is still what is fetched, still what a source's
         * provenance names, and still what removing the row matches on. Only the two places that
         * show it to a person - the repository row and the line under a source - read the nickname.
         */
        private val RepositoryNicknamesKey = stringPreferencesKey("spotiflac_repository_nicknames")

        /**
         * The repositories the user added, one URL per line.
         *
         * A line rather than a comma, because a URL may legitimately contain a comma and the
         * stored list is read back by splitting it.
         */
        val CustomRepositoriesKey = stringPreferencesKey("spotiflac_custom_repositories")

        private const val MAX_REGISTRY_BYTES = 512 * 1024

        /**
         * How long adding a repository may wait for its answer.
         *
         * The add dialog stays open while the address is read, so a host that accepts the
         * connection and then says nothing would leave the user watching a spinner with no way to
         * tell it apart from a slow GitHub. A repository document is a few kilobytes; twenty
         * seconds is generous for that and short enough to report a failure while the user is
         * still looking at the dialog.
         */
        private const val REPOSITORY_TIMEOUT_MS = 20_000L

        /** Longest failure summary a source row keeps for its one-line test result. */
        private const val MAX_TEST_ERROR_LENGTH = 90

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
            // The two that cannot serve audio, described as what they are. The registry entry is
            // the authority when it arrives (`merge` keeps its category over this one), and this is
            // what is left when the registries cannot be read at all - the one path on which the
            // list would otherwise call a metadata provider an audio source, put it in the sweep and
            // count it as an engine that can fetch. Their packages declare the same thing; this only
            // says it earlier.
            ExtensionSource(
                id = "apple-music",
                name = "Apple Music",
                description = "Apple Music metadata and lyrics for SpotiFLAC",
                author = "SpotiFLAC",
                category = "integration",
                relayUrl = "https://api.zarz.moe/v2",
                providerKey = "apple-music",
            ),
            ExtensionSource(
                id = "spotify-web",
                name = "Spotify Web",
                description = "Spotify metadata for SpotiFLAC",
                author = "SpotiFLAC",
                category = "integration",
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

    private val _customRepositories = MutableStateFlow<List<String>>(emptyList())

    private val _disabledRepositories = MutableStateFlow<Set<String>>(emptySet())

    private val _removedRepositories = MutableStateFlow<Set<String>>(emptySet())

    private val _repositoryNicknames = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * What each repository calls itself, which is not persisted: it is the repository's answer and
     * it arrives with every fetch, so a stored copy could only ever be a stale claim about a name
     * the registry can change at any time.
     */
    private val _repositoryNames = MutableStateFlow<Map<String, String>>(emptyMap())

    private val _repositories = MutableStateFlow<List<RepositoryRow>>(emptyList())

    /**
     * Every repository Hush knows about, in the order the list draws them.
     *
     * Bundled first, then the shipped defaults the user kept, then their own - so the row that
     * cannot be deleted is also the one that never moves, and a user-added repository can never
     * displace the provenance of a source Hush ships.
     */
    val repositories: StateFlow<List<RepositoryRow>> = _repositories.asStateFlow()

    /**
     * The repositories the user added, in the order they added them.
     *
     * Published rather than read on demand, because the screen draws them next to the bundled
     * ones and the two must be one list: a repository that has been added but is not shown is a
     * URL the user cannot remove.
     */
    val customRepositories: StateFlow<List<String>> = _customRepositories.asStateFlow()

    /**
     * How many sources a repository declares: read at the fetch, never counted from the merged list.
     *
     * A source's `repositoryId` is last-writer-wins - two registries that both publish `amazon` end
     * with the second one's address on the merged entry - so counting the merged list said "0
     * sources" next to a registry that plainly declares twelve, which is worse than saying nothing.
     * Null is a failure and not a zero: a repository Hush could not read has no opinion about its
     * sources, and the row must not claim it has none.
     */
    private fun declaredCount(counts: Map<String, Int?>, url: String): Int? = counts[url]

    private val mutex = Mutex()
    private var dataStore: DataStore<Preferences>? = null

    fun initialize(dataStore: DataStore<Preferences>) {
        this.dataStore = dataStore
        instance = this
    }

    suspend fun syncRegistries() {
        _isSyncing.value = true
        try {
            refreshRepositoryPreferences()
            val urls = allRepositoryUrls()
            val disabled = _disabledRepositories.value
            // Published before the first request, so the list is never empty while the network is
            // still answering: a section that draws nothing for a second reads as "no repositories"
            // on an install that has four, and every row below it names a repository that is not
            // there yet. Each row is replaced with what its fetch returned as that lands.
            _repositories.value = urls.map { url -> rowFor(url, enabled = !url.isDisabledRepository(disabled)) }

            // The last answer, before anything is asked again. A cold start used to show an empty
            // source list - and an empty playback order - for as long as the slowest repository
            // took to answer, which on four repositories over mobile data is seconds of the app
            // looking like it has no sources at all. The cache is what the previous sync proved, so
            // it is shown while the next one is on the wire and replaced when it lands.
            if (_sources.value.isEmpty()) {
                val cached = loadCachedExtensions()
                if (cached.isNotEmpty()) {
                    publishSources(
                        remote = cached,
                        fetchedUrls = urls.filterNot { it.isDisabledRepository(disabled) }.toSet(),
                    )
                }
            }

            // Every repository is asked at once. They are independent hosts and were fetched one
            // after another, so the list took the *sum* of four round trips before it could be
            // shown while the screen was already open on it.
            val answers =
                coroutineScope {
                    urls.map { url ->
                        async(Dispatchers.IO) {
                            if (url.isDisabledRepository(disabled)) {
                                // Not fetched at all: a switched-off repository contributes nothing,
                                // which is what makes the switch mean something.
                                Timber.tag(TAG).d("Skipping disabled repository $url")
                                null
                            } else {
                                url to fetchRepository(url)
                            }
                        }
                    }.awaitAll()
                }
            val remoteExtensions = mutableListOf<ExtensionSource>()
            val rows = mutableListOf<RepositoryRow>()
            answers.forEachIndexed { index, answer ->
                if (answer == null) {
                    rows += rowFor(urls[index], enabled = false)
                    return@forEachIndexed
                }
                val (url, outcome) = answer
                outcome
                    .onSuccess { document ->
                        remoteExtensions.addAll(document.sources)
                        rows += rowFor(url, enabled = true, document = document)
                        Timber.tag(TAG).d(
                            "Fetched ${document.sources.size} ${document.kind.id} sources from $url",
                        )
                    }
                    // A cancelled sync is not a failed repository - `fetchRepository` rethrows it,
                    // so it never arrives here, and the next request is not issued on a dead
                    // coroutine.
                    .onFailure { error ->
                        rows += rowFor(url, enabled = true, error = shortTestError(error.message) ?: "Could not be read")
                        Timber.tag(TAG).w(error, "Failed to fetch repository from $url")
                    }
            }
            _repositories.value = rows
            // Keep the last verified registry snapshot. A transient GitHub/network
            // failure must not make already-known sources disappear from Settings.
            val effectiveRemoteExtensions =
                if (remoteExtensions.isNotEmpty()) {
                    saveCachedExtensions(remoteExtensions)
                    remoteExtensions
                } else {
                    loadCachedExtensions()
                }
            // A cached source can name a repository the user has since switched off, removed, or
            // that simply is not in the list any more - the cache is the last answer those URLs
            // gave, and this is the run where they were not all asked. Filtered against the URLs
            // actually read, because the fallback above is exactly the path that would otherwise
            // bring a switched-off repository's sources back.
            val fetchedUrls = urls.filterNot { it.isDisabledRepository(disabled) }.toSet()
            publishSources(remote = effectiveRemoteExtensions, fetchedUrls = fetchedUrls)

            Timber.tag(TAG).d("Synced ${_sources.value.size} sources from ${rows.count { it.enabled }} repos")
        } catch (e: CancellationException) {
            // A cancelled sync is not a failed one. Swallowing this would run the
            // "registry is down, publish builtins only" fallback on a coroutine that
            // has already been cancelled - overwriting the live source list with the
            // built-in subset because the caller went away.
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to sync registries, using builtins")
            val enabledIds = loadEnabledIds()
            val knownIds = loadKnownIds()
            val orderedIds = loadOrderedIds()
            _sources.value = buildSourceList(BUILTIN_SOURCES, enabledIds, knownIds, orderedIds)
        } finally {
            _isSyncing.value = false
        }
    }

    /**
     * Turns a set of remote sources into the list the screen and the resolve order read.
     *
     * One place, because the cached answer and the fetched answer have to be filtered by the same
     * rule: a source whose repository is switched off, removed or no longer listed must not appear
     * because its row was read from a cache. [fetchedUrls] is what was actually read this pass, and
     * only those repositories may contribute.
     */
    private suspend fun publishSources(
        remote: List<ExtensionSource>,
        fetchedUrls: Set<String>,
    ) {
        val activeRemote =
            remote.filter { source ->
                fetchedUrls.any { it.equals(source.repositoryId, ignoreCase = true) }
            }
        val mergedExtensions =
            ExtensionRepositories.merge(
                bundled = BUILTIN_SOURCES,
                remote = activeRemote,
                // A repository Hush ships is trusted to enrich Hush's own sources; anything else,
                // including a shipped default the user can remove, is not - it may add sources
                // of its own and must not repoint one the runtime resolves against.
                customRepositoryUrls = _customRepositories.value.toSet(),
            )
        val sources =
            buildSourceList(
                extensions = mergedExtensions,
                enabledIds = loadEnabledIds(),
                knownIds = loadKnownIds(),
                orderedIds = loadOrderedIds(),
            )
        _sources.value = sources
        rememberKnownSources(sources)
    }

    /**
     * One repository, fetched, identifed and filtered - or the reason it was not.
     *
     * Shared by the sync and by adding a repository, on purpose: an added repository has to be
     * judged by exactly the rules a bundled one is judged by, or the list would hold URLs whose
     * entries are silently dropped on every sync. The failure carries the sentence the add flow
     * shows, which is why it is a [Result] rather than a nullable document.
     *
     * The format is identified from the body rather than from the address: Hush's own registries and
     * a BitChord module index are both a JSON file on a raw host, and one reader that decides by
     * shape is what lets a user paste either kind of URL into the same field.
     */
    private suspend fun fetchRepository(url: String): Result<RepositoryDocument> {
        return try {
            // Bounded, because the add dialog waits on this answer: a host that accepts the
            // connection and then stalls would otherwise show a spinner with no verdict at all.
            val (status, body) =
                withTimeout(REPOSITORY_TIMEOUT_MS) {
                    val response = getRepositoryFile(url, AddonClientIdentity.userAgentFor(url))
                    // A 403 is two different answers from two different hosts: one refuses the
                    // client whose addon API it was written for, another refuses the *other*
                    // client's token and serves Hush happily (see [AddonClientIdentity]). Asking
                    // once more as Hush itself is what tells them apart, and it is one extra
                    // request only for the repositories that refused the first name.
                    val retried =
                        if (response.status.value == 403 || response.status.value == 401) {
                            AddonClientIdentity.noteProductTokenRefused(url)
                            getRepositoryFile(url, AddonClientIdentity.neutralUserAgent)
                        } else {
                            response
                        }
                    retried.status to retried.bodyAsText()
                }
            if (status != HttpStatusCode.OK) {
                return Result.failure(IllegalStateException(httpFailureMessage(status.value)))
            }
            if (body.toByteArray(Charsets.UTF_8).size > MAX_REGISTRY_BYTES) {
                return Result.failure(IllegalStateException("It is larger than the 512 KB Hush will read"))
            }
            // A file page fetched as a page answers with HTML, and so does a 404 from a host that
            // renders one. Saying so is what turns "not a repository" into something the user can
            // fix, because the fix is almost always "copy the Raw link instead".
            if (body.trimStart().startsWith("<")) {
                return Result.failure(
                    IllegalStateException(
                        "That address returned a web page, not a repository file. Open the file on " +
                            "GitHub (or wherever it is hosted) and copy its Raw link instead.",
                    ),
                )
            }
            val document =
                ExtensionRepositories.read(url, body)
                    ?: return Result.failure(IllegalStateException(notARepositoryMessage(body)))
            val safeExtensions =
                document.sources.filter { extension ->
                    if (!extension.isSafeRegistryEntry) {
                        Timber.tag(TAG).w("Ignoring unsafe entry ${extension.id} from $url")
                        false
                    } else {
                        true
                    }
                }
            if (safeExtensions.isEmpty() && document.sources.isNotEmpty()) {
                return Result.failure(
                    IllegalStateException("Every entry it declares was refused as unsafe"),
                )
            }
            Result.success(
                document.copy(
                    sources = safeExtensions.map { ext -> ext.copy(repositoryId = url) },
                ),
            )
        } catch (e: TimeoutCancellationException) {
            // The wait is Hush's own, not the caller's, so it is a verdict rather than a cancelled
            // job: reported as the repository not answering in time.
            Result.failure(IllegalStateException("It did not answer within 20 seconds"))
        } catch (e: CancellationException) {
            // Reached through `httpClient.get`: without this the cancellation is reported as
            // "this repository URL failed" and the caller carries on with a cancelled coroutine.
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * One GET of the repository file, as [userAgent].
     *
     * The headers are the same for both attempts on purpose: the only thing that changes between
     * them is who is asking, so an answer that differs is an answer about the name.
     */
    private suspend fun getRepositoryFile(
        url: String,
        userAgent: String,
    ) = httpClient.get(url) {
        header("Accept", "application/json")
        header("Cache-Control", "no-cache")
        header("User-Agent", userAgent)
        // A personal addon signs its manifest too: without this the add flow answers 403 before it
        // has read the document that describes the service (see [LastWaveAddon]).
        LastWaveAddon.headers(url)?.forEach { (name, value) ->
            if (!name.equals("User-Agent", ignoreCase = true)) header(name, value)
        }
    }

    /** A non-2xx answer, in words that say whether retrying or fixing the address is the fix. */
    private fun httpFailureMessage(status: Int): String =
        when (status) {
            404 -> "It answered 404 - there is no file at that address"
            401, 403 -> "It answered $status - that file is private or blocks Hush"
            429 -> "It answered 429 - the host is rate limiting requests"
            500, 502, 503, 504 -> "It answered $status - the host is having trouble right now"
            else -> "It answered HTTP $status"
        }

    /** Why a body that answered 200 was still not a repository, said by what it actually was. */
    private fun notARepositoryMessage(body: String): String {
        if (body.isBlank()) return "It answered with an empty file"
        val json = runCatching { json.parseToJsonElement(body.trim()) }.isSuccess
        return if (json) {
            "That file is JSON but not a source repository: Hush reads an `extensions` list (a " +
                "SpotiFLAC registry) or a `category:` module list (a module index)"
        } else {
            "That address did not return a JSON file - paste the link to the repository's " +
                "registry.json or manifest.json"
        }
    }

    /**
     * The reason an add was refused, whole.
     *
     * [shortTestError] clips to ninety characters because a source row has one line for its test
     * result. This dialog has a paragraph, and clipping a sentence here is how a rejection reads as
     * a different, vaguer one - the reason a pasted link did not work is the part that was cut off.
     */
    private fun repositoryErrorMessage(error: Throwable?): String =
        error?.message
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotEmpty() }
            ?: "The repository could not be read"

    /**
     * Adds a repository the user named, once it has answered with something usable.
     *
     * The order matters: the URL is validated, then *fetched*, and only then stored. Storing
     * first and discovering later is how a repository list fills with dead URLs that look like
     * working rows, and the one moment Hush can still tell the user what is wrong with an
     * address is the moment they paste it.
     *
     * Both formats are accepted, because the field asks for an address and not for a SpotiFLAC
     * registry: what the document turns out to be is reported back on the row. A personal URL with
     * a token in its path (`/u/<token>`) is added like any other - a credential *inside* the
     * authority (`https://user:pass@host/`) is what [ExtensionRepositories.canonical] refuses,
     * because that is a login Hush would make to a host it cannot name in a prompt.
     */
    suspend fun addRepository(rawUrl: String): RepositoryAddResult {
        // Resolved to the spelling Hush already knows, when it knows this document: a repository
        // page, its Raw link and its copy-link address are one file, and only one of them should
        // ever become a row.
        val candidates =
            ExtensionRepositories.addCandidates(rawUrl)
                .map { candidate -> ExtensionRepositories.knownAddress(candidate) ?: candidate }
                .distinct()
        if (candidates.isEmpty()) {
            return RepositoryAddResult.Rejected(
                "That is not an address Hush can fetch. Paste an https:// link to a repository or to " +
                    "the registry.json / manifest.json file inside one.",
            )
        }
        // An address Hush already reads is refused before a request is made: there is nothing to
        // learn by fetching a document the list already holds.
        val alreadyKnown =
            mutex.withLock {
                candidates.firstNotNullOfOrNull { url -> repositoryBlocker(url) }
            }
        if (alreadyKnown != null) return alreadyKnown

        var rejection: RepositoryAddResult.Rejected? = null
        for (candidate in candidates) {
            // The fetch is deliberately outside the lock: it is the slow part, and holding the
            // mutex every source switch also takes would freeze the list for the whole request.
            val fetched = fetchRepository(candidate)
            val failure = fetched.exceptionOrNull()
            if (failure != null) {
                rejection = RepositoryAddResult.Rejected(repositoryErrorMessage(failure))
                continue
            }
            val document = fetched.getOrNull() ?: continue
            if (document.sources.isEmpty()) {
                rejection =
                    RepositoryAddResult.Rejected(
                        "${ExtensionRepositories.label(candidate)} answered, but declares no sources Hush can use",
                    )
                continue
            }

            val validated = mutex.withLock { commitRepository(candidate, document) }
            if (validated is RepositoryAddResult.Added) {
                // Outside the lock: the sync re-reads the list and rebuilds it, and doing that while
                // holding the lock the source rows also take would stall every enable/reorder tap.
                syncRegistries()
                return validated
            }
            rejection = validated as RepositoryAddResult.Rejected
        }
        return rejection ?: RepositoryAddResult.Rejected("The repository could not be read")
    }

    /**
     * Why an address cannot be added, or null when it can.
     *
     * Checked twice - before the fetch so an address already in the list costs no request, and
     * again inside the commit lock, because two adds racing would otherwise both see a list that
     * does not contain the other and store the same URL twice.
     */
    private fun repositoryBlocker(url: String): RepositoryAddResult.Rejected? {
        // Compared as documents rather than as strings, because one file has several addresses:
        // a repository page, its Raw link and GitHub's copy-link each spell it differently, and
        // they all end up here.
        if (ExtensionRepositories.isBundled(url) || ExtensionRepositories.knownAddress(url) in ExtensionRepositories.Bundled) {
            return RepositoryAddResult.Rejected(
                "${ExtensionRepositories.label(url)} is one of Hush's own repositories",
            )
        }
        // A shipped default the user removed is added back rather than duplicated: the row is the
        // same row, and a second record of it would be a second row for one file.
        val wasRemoved = url.isRemovedRepository(_removedRepositories.value)
        if (wasRemoved || ExtensionRepositories.isDefaultRemovable(url)) return null
        if (_customRepositories.value.any { it.equals(url, ignoreCase = true) || ExtensionRepositories.sameRepositoryDocument(it, url) }) {
            return RepositoryAddResult.Rejected(
                "${ExtensionRepositories.label(url)} is already in the list",
            )
        }
        return null
    }

    /**
     * Stores an address that has answered, and returns the row it now has.
     *
     * Must run under [mutex]: it decides against the same lists it writes. A shipped default is
     * never stored as a custom repository - it is restored by dropping its "removed" record, which
     * is the only place its absence is kept. Keeping both records would make removing it again
     * contradict the custom entry that still says it was added.
     */
    private suspend fun commitRepository(
        url: String,
        document: RepositoryDocument,
    ): RepositoryAddResult {
        repositoryBlocker(url)?.let { return it }
        // The shipped spelling, not whatever page the user pasted: a default repository restored
        // through its page is still that row, and the row is addressed by the URL Hush ships.
        val shippedDefault =
            ExtensionRepositories.isDefaultRemovable(url) ||
                ExtensionRepositories.knownAddress(url) in ExtensionRepositories.DefaultRemovable
        if (shippedDefault) {
            val defaultUrl =
                ExtensionRepositories.knownAddress(url)
                    ?.takeIf { it in ExtensionRepositories.DefaultRemovable }
                    ?: url
            if (defaultUrl.isRemovedRepository(_removedRepositories.value)) {
                val restored = _removedRepositories.value.filterNot { it.equals(defaultUrl, ignoreCase = true) }.toSet()
                saveRemovedRepositories(restored)
                _removedRepositories.value = restored
            }
            // A record of a shipped default as "added" says the same thing the row already says,
            // and an install that added it through a page or a copy-link address has one under a
            // spelling Hush does not ship. Dropping every spelling keeps one owner per document.
            val custom =
                _customRepositories.value.filterNot { existing ->
                    existing.equals(defaultUrl, ignoreCase = true) ||
                        ExtensionRepositories.sameRepositoryDocument(existing, defaultUrl)
                }
            if (custom != _customRepositories.value) {
                saveCustomRepositories(custom)
                _customRepositories.value = custom
            }
        } else {
            saveCustomRepositories(_customRepositories.value + url)
            _customRepositories.value = _customRepositories.value + url
        }
        // A repository that has just been added is switched on, whatever an older record says.
        //
        // The switch is stored as a set of switched-off addresses, and removing a repository does
        // not touch it: disable a repository, remove it, paste the same address again, and the row
        // came back *off* - drawn on for the instant before the sync, then silently unfetched, and
        // reported as "switched off" on a row the user had only just added. Adding an address is
        // the user saying they want it read; the switch is how they say otherwise afterwards, and
        // it is still there for that.
        forgetDisabledRepository(url)
        // Known before the sync that follows finishes, so the new row reports what it declares
        // instead of "not read" for the moment it takes to come back.
        _repositories.value =
            _repositories.value.filterNot { it.url.equals(url, ignoreCase = true) } +
                rowFor(url, enabled = true, document = document)
        return RepositoryAddResult.Added(
            url = url,
            label = ExtensionRepositories.label(url),
            sources = document.sources.size,
            kind = document.kind,
        )
    }

    /**
     * Drops a repository. Its sources leave the list on the next sync; a package already installed
     * stays installed, because removing a registry is not uninstalling.
     *
     * A repository Hush *ships* as its own cannot be removed - the sources it addresses are the ones
     * the runtime and the signed-session contracts are written against, so a build without it would
     * list sources it cannot install. It can still be switched off, which is the reversible half.
     */
    suspend fun removeRepository(url: String) {
        val changed =
            mutex.withLock {
                val removed =
                    when {
                        ExtensionRepositories.isBundled(url) -> false
                        ExtensionRepositories.isDefaultRemovable(url) -> {
                            var changed = false
                            if (!url.isRemovedRepository(_removedRepositories.value)) {
                                saveRemovedRepositories(_removedRepositories.value + url)
                                _removedRepositories.value = _removedRepositories.value + url
                                changed = true
                            }
                            // An install that added the shipped default before it was recognised
                            // as one has both records. Leaving the custom one behind would make the
                            // row come back as "added" on the next launch after the user removed it.
                            val custom =
                                _customRepositories.value.filterNot { it.equals(url, ignoreCase = true) }
                            if (custom != _customRepositories.value) {
                                saveCustomRepositories(custom)
                                _customRepositories.value = custom
                                changed = true
                            }
                            changed
                        }
                        else -> {
                            val remaining =
                                _customRepositories.value.filterNot { it.equals(url, ignoreCase = true) }
                            if (remaining == _customRepositories.value) {
                                false
                            } else {
                                saveCustomRepositories(remaining)
                                _customRepositories.value = remaining
                                true
                            }
                        }
                    }
                // A removed address leaves no "switched off" record behind. It was never about the
                // address the user pasted; it was about the row that has now gone, and keeping it
                // made adding the same repository again restore it silently switched off.
                if (removed) forgetDisabledRepository(url)
                removed
            }
        if (changed) syncRegistries()
    }

    /**
     * Drops a switched-off record for [url], matched the way the switch matches it.
     *
     * Only ever called for an address the list no longer holds or has just begun holding again: a
     * repository the user switched off and left in place keeps its switch where they put it.
     */
    private suspend fun forgetDisabledRepository(url: String) {
        val disabled = _disabledRepositories.value
        val next = disabled.filterNot { it.equals(url, ignoreCase = true) }.toSet()
        if (next == disabled) return
        saveDisabledRepositories(next)
        _disabledRepositories.value = next
    }

    /**
     * Switches a repository on or off and re-syncs, so its sources leave or return immediately.
     *
     * The switch is stored rather than filtered, so a repository that is off is not fetched on the
     * next launch either. Switching one off does not touch the per-source choices: a source the
     * user had enabled and ordered keeps that place in the order, and switching the repository back
     * on restores exactly the list they had arranged rather than a default one.
     */
    suspend fun setRepositoryEnabled(
        url: String,
        enabled: Boolean,
    ) {
        val changed =
            mutex.withLock {
                val disabled = _disabledRepositories.value
                val next =
                    if (enabled) {
                        disabled.filterNot { it.equals(url, ignoreCase = true) }.toSet()
                    } else {
                        disabled + url
                    }
                if (next == disabled) {
                    false
                } else {
                    saveDisabledRepositories(next)
                    _disabledRepositories.value = next
                    true
                }
            }
        if (changed) syncRegistries()
    }

    /** Names a repository. An empty name restores the one it came with. */
    suspend fun setRepositoryNickname(
        url: String,
        nickname: String?,
    ) {
        val trimmed = nickname?.trim().orEmpty()
        mutex.withLock {
            val next =
                _repositoryNicknames.value.toMutableMap().apply {
                    if (trimmed.isEmpty()) remove(url) else put(url, trimmed)
                }
            saveRepositoryNicknames(next)
            _repositoryNicknames.value = next
            _repositories.value =
                _repositories.value.map { row ->
                    if (row.url.equals(url, ignoreCase = true)) {
                        row.copy(
                            nickname = trimmed.ifBlank { null },
                            label = repositoryLabel(url, trimmed.ifBlank { null }),
                        )
                    } else {
                        row
                    }
                }
        }
    }

    /**
     * Reads every persisted repository preference before a sync.
     *
     * One read rather than four, because they are consulted together and a sync that mixed a stale
     * enabled set with a fresh removed set would fetch precisely the repository it was just told to
     * drop.
     */
    suspend fun refreshRepositoryPreferences() {
        refreshCustomRepositories()
        val prefs = dataStore?.data?.first()
        val disabled =
            prefs?.get(DisabledRepositoriesKey)
                ?.split('\n')
                ?.mapNotNull { ExtensionRepositories.canonical(it) }
                ?.toSet()
                .orEmpty()
        if (disabled != _disabledRepositories.value) _disabledRepositories.value = disabled
        val removed =
            prefs?.get(RemovedRepositoriesKey)
                ?.split('\n')
                ?.mapNotNull { ExtensionRepositories.canonical(it) }
                ?.toSet()
                .orEmpty()
        if (removed != _removedRepositories.value) _removedRepositories.value = removed
        val nicknames =
            prefs?.get(RepositoryNicknamesKey)
                ?.split('\n')
                ?.mapNotNull { line ->
                    val separator = line.indexOf('|')
                    if (separator <= 0) return@mapNotNull null
                    val url = ExtensionRepositories.canonical(line.take(separator)) ?: return@mapNotNull null
                    val name = line.substring(separator + 1).trim()
                    if (name.isEmpty()) null else url to name
                }
                ?.toMap()
                .orEmpty()
        if (nicknames != _repositoryNicknames.value) _repositoryNicknames.value = nicknames
    }

    /** Reads the persisted list of repositories the user added. */
    suspend fun refreshCustomRepositories() {
        val stored =
            dataStore?.data
                ?.map { prefs -> prefs[CustomRepositoriesKey] }
                ?.first()
                .orEmpty()
        val parsed =
            stored
                .split('\n')
                .mapNotNull { ExtensionRepositories.canonical(it) }
                .distinct()
        // A retired address is dropped where the list is read *and written back*, because a stored
        // row outlives the build that shipped it: an install that added one while it worked would
        // otherwise keep drawing a repository that answers 402 or 404, switched on, forever. Only
        // the addresses Hush itself shipped once are retired; an address the user adds later is
        // theirs, including another BitChord-style addon.
        val urls = parsed.filterNot { ExtensionRepositories.isRetired(it) }
        if (urls != parsed) saveCustomRepositories(urls)
        if (urls != _customRepositories.value) _customRepositories.value = urls
    }

    /**
     * Every repository Hush reads from, in the order the list draws them.
     *
     * Bundled, then shipped defaults the user has not removed, then what they added - and the
     * dedup is inside [ExtensionRepositories.effective], so re-adding a shipped address cannot make
     * the same registry be fetched twice and count its entries twice.
     */
    private fun allRepositoryUrls(): List<String> =
        ExtensionRepositories.effective(
            bundled = ExtensionRepositories.Bundled,
            custom =
                ExtensionRepositories.DefaultRemovable
                    .filterNot { it.isRemovedRepository(_removedRepositories.value) } +
                    _customRepositories.value,
        )

    /** The row for one repository, carrying whatever is known about it. */
    private fun rowFor(
        url: String,
        enabled: Boolean,
        document: RepositoryDocument? = null,
        error: String? = null,
    ): RepositoryRow {
        // What this document calls the repository, once read - the profile's own name included, so
        // two profiles of one addon are two labels. Remembered under the URL rather than recomputed,
        // because the next pass may not read this repository at all: a switched-off row is drawn
        // from what the last read said, not relabelled as unread.
        document?.let { read ->
            _repositoryNames.value =
                _repositoryNames.value + (url to ExtensionRepositories.rowLabel(url, read))
        }
        val nickname =
            _repositoryNicknames.value.entries
                .firstOrNull { it.key.equals(url, ignoreCase = true) }
                ?.value
        return RepositoryRow(
            url = url,
            label = repositoryLabel(url, nickname),
            nickname = nickname,
            kind = document?.kind,
            enabled = enabled,
            isBundled = ExtensionRepositories.isBundled(url),
            removable = !ExtensionRepositories.isBundled(url),
            isShippedDefault = ExtensionRepositories.isDefaultRemovable(url),
            declaredSources = document?.sources?.size,
            error = error,
        )
    }

    /**
     * What a repository row is called: the user's name for it, else the repository's own name for
     * itself, else the owner/host [ExtensionRepositories.label] derives from the address.
     *
     * A repository that names itself (`BitChord Streaming Modules`) is the one label a person can
     * recognise; when it does not, the address still tells two repositories apart, which is why the
     * URL-derived label is the fallback rather than the kind or a count.
     */
    private fun repositoryLabel(
        url: String,
        nickname: String?,
    ): String {
        nickname?.takeIf { it.isNotBlank() }?.let { return it }
        val named =
            _repositoryNames.value.entries
                .firstOrNull { it.key.equals(url, ignoreCase = true) }
                ?.value
                ?.takeIf { it.isNotBlank() }
        return named ?: ExtensionRepositories.label(url)
    }

    private fun String.isDisabledRepository(disabled: Set<String>): Boolean =
        isBlank() || disabled.any { it.equals(this, ignoreCase = true) }

    private fun String.isRemovedRepository(removed: Set<String>): Boolean =
        removed.any { it.equals(this, ignoreCase = true) }

    private suspend fun saveCustomRepositories(urls: List<String>) {
        editPreferences { prefs -> prefs[CustomRepositoriesKey] = urls.joinToString("\n") }
    }

    private suspend fun saveDisabledRepositories(urls: Set<String>) {
        editPreferences { prefs -> prefs[DisabledRepositoriesKey] = urls.joinToString("\n") }
    }

    private suspend fun saveRemovedRepositories(urls: Set<String>) {
        editPreferences { prefs -> prefs[RemovedRepositoriesKey] = urls.joinToString("\n") }
    }

    private suspend fun saveRepositoryNicknames(nicknames: Map<String, String>) {
        editPreferences { prefs ->
            prefs[RepositoryNicknamesKey] =
                nicknames.entries.joinToString("\n") { (url, name) -> "$url|$name" }
        }
    }

    private suspend fun editPreferences(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        val ds = dataStore
        if (ds == null) {
            Timber.tag(TAG).w("dataStore not initialized, cannot save repository settings")
            return
        }
        ds.edit(block)
    }

    /**
     * The list, ordered and switched, which is also the resolve order.
     *
     * [knownIds] is what makes a source that has just appeared work: see [KnownSourcesKey]. A source
     * this build cannot play is never switched on, whatever the user has stored - its switch is
     * disabled on the row, and a source that answered "on" there while the resolve order ignored it
     * is the kind of lie that makes a list untrustworthy.
     */
    private fun buildSourceList(
        extensions: List<ExtensionSource>,
        enabledIds: Set<String>?,
        knownIds: Set<String>?,
        orderedIds: List<String>,
    ): List<SourceWithState> {
        val sourceMap = extensions.associateBy { it.id }

        fun stateFor(
            source: ExtensionSource,
            position: Int,
        ): SourceWithState =
            SourceWithState(
                source = source,
                enabled = sourceEnabledByDefault(source, enabledIds, knownIds),
                priority = position + 1,
            )

        // The user's order, with the sources Hush ships placed where they belong rather than at the
        // bottom: see [ExtensionRepositories.DefaultSourceOrder]. Everything the user has arranged
        // keeps its position, so this can only move a source they have never moved.
        val finalOrder =
            ExtensionRepositories.orderWithDefaults(
                storedOrder = orderedIds,
                available = extensions.map { it.id },
            )
        val ordered = mutableListOf<SourceWithState>()
        for (id in finalOrder) {
            val source = sourceMap[id] ?: continue
            ordered.add(stateFor(source, ordered.size))
        }

        return ordered
    }

    /**
     * Records the sources this sync listed, so the next one can tell a new source from a refused one.
     *
     * Written as a union rather than a replacement: a repository that is switched off is not fetched,
     * so its sources are not in the list this pass - and dropping them here would make switching that
     * repository back on turn every one of them on again, including the ones the user had switched
     * off. The set only grows, which is what "has been seen" means.
     */
    private suspend fun rememberKnownSources(sources: List<SourceWithState>) {
        val previous = loadKnownIds().orEmpty()
        val next = previous + sources.map { it.source.id }
        if (next == previous) return
        val ds = dataStore ?: return
        ds.edit { prefs -> prefs[KnownSourcesKey] = next.joinToString(",") }
        // A source this install has never listed is *on* because it is new ([sourceEnabledByDefault]),
        // and that decision has to be written down with it. Remembering the id alone made the next
        // sync read "known, and not in the switched-on set" and switch every new row off - a shipped
        // source worked on the launch it arrived and was off on the one after, which is how a new
        // repository would look broken a day later. Only an install that has a stored set is touched:
        // with nothing stored, the defaults are re-derived on every sync and must stay that way.
        val storedEnabled = loadEnabledIds() ?: return
        val bornEnabled = sources.filter { it.enabled && it.source.id !in previous }.map { it.source.id }
        if (bornEnabled.isEmpty()) return
        saveEnabledIds(storedEnabled + bornEnabled)
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
        // Enabled *and* playable: a source that needs a runtime this build does not have is on the
        // screen so it can be seen and switched off, and it must not be in the order a resolve walks
        // - a candidate that can only fail costs the user the wait before the next one is tried.
        if (live.isNotEmpty()) {
            // Native only, for the same reason as [getEnabledSourceIds]: this order is the runtime's
            // candidate walk, and a module id is not a candidate the runtime can attempt.
            return live.filter { it.enabled && it.source.isNativePlayable }.map { it.source.id }
        }
        val savedOrder = PreferenceStore.get(SourceOrderKey)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            .orEmpty()
        val savedEnabled = PreferenceStore.get(EnabledSourcesKey)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            ?.toSet()
        val defaultOrder = BUILTIN_SOURCES.map { it.id }
        val order = savedOrder.ifEmpty { defaultOrder }
        // Nothing stored is the fresh install, and there the whole order is in use - not just the
        // built-ins whose ids happen to be the fallback order. See [sourceEnabledByDefault] for the
        // same rule applied to the list the screen draws.
        val enabled = savedEnabled ?: return order
        return order.filter { it in enabled }
    }

    /**
     * The switches as the Audio Sources screen reads them, for a caller that has to obey them.
     *
     * The live list first: it is what the screen draws, and it already carries both facts - which
     * sources are switched on and the order the user put them in. When it is empty the registry sync
     * has not landed yet (or never has), and the *stored* switches are read instead, which is the
     * same answer one sync earlier. Both are needed because a resolve races the sync on every cold
     * start, and what a resolve must never do in that window is assume the switches say nothing.
     *
     * [SourceSwitches.known] is false only when the key holding them does not exist: an install that
     * has never switched a source on or off. [savedSourceOrder] cannot answer that question - it
     * substitutes the built-in defaults for a missing key - so an install that never chose and one
     * that chose "all off" read as the same list there.
     */
    suspend fun sourceSwitches(): SourceSwitches {
        val live = _sources.value
        if (live.isNotEmpty()) {
            return SourceSwitches(
                enabledInOrder = live.filter { it.enabled }.map { it.source.id },
                known = true,
            )
        }
        val prefs = dataStore?.data?.first()
        val stored = prefs?.get(EnabledSourcesKey)
            ?: return SourceSwitches(enabledInOrder = emptyList(), known = false)
        val on = stored.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val arranged =
            prefs[SourceOrderKey]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        // The user's order where it names the source, and the rest behind it: an install upgraded
        // from a build with no order stored still answers with everything it has switched on.
        return SourceSwitches(
            enabledInOrder = arranged.filter { it in on } + on.filterNot { it in arranged },
            known = true,
        )
    }

    /**
     * The switched-on sources in the live list, in the user's order, or empty before the first sync.
     *
     * Deliberately not "every source": the whole list includes the ones the user switched off, and a
     * caller that says "give me the sources" is asking what is in use. See [sourceSwitches] for the
     * same answer in the window where the live list is not there yet.
     */
    fun enabledLiveSourceIds(): List<String> =
        _sources.value.filter { it.enabled }.map { it.source.id }

    /**
     * The switched-on sources that can serve audio at all, or null before the registry is read.
     *
     * The engine's own state hangs on this question - a switch list made of metadata providers is not
     * an engine that can fetch a track - and it is deliberately answered from the live list rather
     * than from whatever ids a caller happened to pass: the resolve path asks with the runtime's ids
     * and the settings screen with the playable ones, and those two lists are not the switches. A
     * metadata provider is playable in the sense that its package can be loaded and it signs in with
     * its own service; what it cannot do is hand over a file.
     *
     * A list rather than a count because the availability rule has a second question to ask about
     * these same sources - which of them need a check at all - and answering it from the same set is
     * what keeps "nothing can play" and "nothing here needs verifying" from being decided about two
     * different lists.
     *
     * Null rather than an empty list while the live list is empty, because "no sources" and "not read
     * yet" are different answers and only one of them is a reason to stop trying: the sweep still has
     * the installed packages to fall back on in the window before the first sync.
     */
    fun enabledAudioSourceIds(): List<String>? {
        val live = _sources.value
        if (live.isEmpty()) return null
        return live
            .filter { it.enabled && it.source.isPlayable && !it.source.declaresMetadataOnly }
            .map { it.source.id }
    }

    /**
     * How many switched-on sources can serve audio at all, or null before the registry is read.
     *
     * Counted from [enabledAudioSourceIds] rather than from a second filter, so the list every
     * caller walks and the number the engine's state is decided with cannot come apart: the
     * availability rule asks *which* of these need no session, and a count that disagreed with the
     * list would answer about a different set than the one it was given.
     */
    fun enabledAudioSourceCount(): Int? = enabledAudioSourceIds()?.size

    /**
     * The extension ids the user has switched on, readable without suspending - or null when this
     * install has never recorded a choice.
     *
     * Background work that keeps a source's session alive has to be able to ask "is this source
     * switched on" *before* the registry sync has filled the live list in, because it runs at app
     * start. It used to skip the question entirely and renew every extension package on the device:
     * measured on the reporting device, a user with one source switched on and twelve packages
     * installed still had thirteen sessions checked, against a gateway that had already refused
     * some of them - network calls for sources the user had turned off, and "Checking session..."
     * on rows that were switched off.
     *
     * The live list is the authority when it exists, because it is what the screen draws. Before
     * the first sync, the stored switches are, read synchronously the same way the saved source
     * order is - a read that cannot suspend, because the caller is a worker. Null rather than an
     * empty set when nothing has ever been stored: "nothing is known" and "nothing is switched on"
     * are different answers, and treating them the same would silently stop renewals for a fresh
     * install whose defaults are all on.
     */
    fun enabledExtensionIdsSnapshot(): Set<String>? {
        enabledExtensionIdsOf(_sources.value)?.let { return it }
        val stored = PreferenceStore.get(EnabledSourcesKey) ?: return null
        return parseEnabledExtensionIds(stored)
    }

    /** The ids one stored preference value names; empty is a choice too. */
    private fun parseEnabledExtensionIds(stored: String): Set<String> =
        stored.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * The switched-on ids of a live list, or null when the list has not been read at all.
     *
     * Null rather than an empty set for the unread list, because the two mean opposite things to
     * every caller: an empty set is "everything is switched off", and an unread list must not stop
     * a fresh install's defaults from being renewed.
     */
    private fun enabledExtensionIdsOf(sources: List<SourceWithState>): Set<String>? {
        if (sources.isEmpty()) return null
        return sources
            .filter { it.enabled }
            .flatMap { source -> listOfNotNull(source.source.id, source.source.providerKey) }
            .map { it.lowercase() }
            .toSet()
    }

    fun getEnabledSourceIds(): List<String> {
        // The registry ID is the extension contract identifier (for example
        // `tidal-web` and `qobuz-web`). Do not replace it with providerKey:
        // current SpotiFLAC extensions and their signed-session/download
        // contracts use the extension ID when selecting a source. Keep the
        // existing enabled list intact while the runtime is being migrated;
        // older registries may not declare `types` at all.
        //
        // Playable as well as enabled: see [savedSourceOrder]. Native only: a JavaScript module is
        // playable here now, but it is not one of the runtime's extensions - its id would be a
        // source the runtime has never heard of, and handing it over would spend a candidate slot
        // on a source that can only fail. Modules are walked by [SpotiFLACModuleHost] and are
        // published by [getEnabledModuleSources].
        return _sources.value
            .filter { it.enabled && it.source.isNativePlayable }
            .map { it.source.id }
    }

    /**
     * The enabled JavaScript modules, in the order the user arranged, for the module host to walk.
     *
     * Its own list rather than a suffix of [getEnabledSourceIds] because the two go to different
     * engines: the runtime is handed the native ids and walks them, and the module host runs each
     * module's script and matches the module's own search results. The order is the user's, which is
     * the only ordering statement either engine is allowed to make on their behalf.
     */
    fun getEnabledModuleSources(): List<ExtensionSource> =
        _sources.value
            .filter { it.enabled && it.source.isHostedModule }
            .map { it.source }

    /**
     * The enabled sources that play without the native runtime: modules and server addons.
     *
     * One list because they answer the same two questions at the same point in the sweep - search
     * this track, then give me a URL for the row you matched - and differ only in who does the work:
     * a module is JavaScript Hush runs ([SpotiFLACModuleHost]), a server addon is an HTTP service the
     * manifest describes ([ServerAddonHost]). The bridge dispatches on [ExtensionSource.isServerAddon].
     */
    fun getEnabledAddonSources(): List<ExtensionSource> =
        _sources.value
            .filter { it.enabled && (it.source.isHostedModule || it.source.isServerAddon) }
            .map { it.source }

    fun getSourceForId(sourceId: String): ExtensionSource? {
        return _sources.value.find { it.source.id == sourceId }?.source
    }

    /**
     * What a source is called on a surface that is not the list: a label, a fetch, a row.
     *
     * Every such surface used to print the id, which is a registry identifier rather than a name -
     * so a listener read `SpotiFLAC • ytmusic-spotiflac • cached` while a fetch in flight read
     * `Fetching from bitchord-streaming-modules:bitchord-tidal-flac`. Both are answers to "where is
     * this track coming from", and neither is readable. See [SpotiFLACSourceName] for the wording,
     * which is one rule so that the player and the settings list cannot describe one source two ways.
     *
     * Readable without suspending, because it is called from the playback path, and it answers from
     * the live list when there is one and from the id alone when there is not.
     */
    fun sourceLabel(sourceId: String): String {
        val source = _sources.value.find { it.source.id.equals(sourceId, ignoreCase = true) }?.source
        return SpotiFLACSourceName.label(
            sourceId = sourceId,
            name = source?.displayName,
            repositoryId = source?.repositoryId,
            repositories = repositories.value,
        )
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

    /**
     * The switches the user has set, or null when they have never set any.
     *
     * Null is the fresh-install answer and must stay distinguishable from an empty set: empty means
     * every source was switched off on purpose, and substituting Hush's built-ins for "nothing
     * stored" is what left every repository-published source switched off on an install nobody had
     * touched - see [sourceEnabledByDefault].
     */
    private suspend fun loadEnabledIds(): Set<String>? =
        dataStore?.data?.map { prefs ->
            prefs[EnabledSourcesKey]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet()
        }?.first()

    /** The ids this install has listed, or null when it has never synced with this in place. */
    private suspend fun loadKnownIds(): Set<String>? =
        dataStore?.data?.map { prefs ->
            prefs[KnownSourcesKey]
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.toSet()
        }?.first()

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
