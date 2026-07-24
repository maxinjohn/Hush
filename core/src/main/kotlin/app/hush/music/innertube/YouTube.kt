/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.innertube

import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import app.hush.music.innertube.models.AccountChannel
import app.hush.music.innertube.models.AccountInfo
import app.hush.music.innertube.models.AlbumItem
import app.hush.music.innertube.models.Artist
import app.hush.music.innertube.models.ArtistItem
import app.hush.music.innertube.models.BrowseEndpoint
import app.hush.music.innertube.models.GridRenderer
import app.hush.music.innertube.models.IpVersion
import app.hush.music.innertube.models.MediaInfo
import app.hush.music.innertube.models.MusicCarouselShelfRenderer
import app.hush.music.innertube.models.MusicPlaylistShelfRenderer
import app.hush.music.innertube.models.MusicResponsiveListItemRenderer
import app.hush.music.innertube.models.MusicShelfRenderer
import app.hush.music.innertube.models.MusicTwoRowItemRenderer
import app.hush.music.innertube.models.PlaylistItem
import app.hush.music.innertube.models.PodcastItem
import app.hush.music.innertube.models.EpisodeItem
import app.hush.music.innertube.models.Run
import app.hush.music.innertube.models.Runs
import app.hush.music.innertube.models.SearchSuggestions
import app.hush.music.innertube.models.SectionListRenderer
import app.hush.music.innertube.models.SongItem
import app.hush.music.innertube.models.WatchEndpoint
import app.hush.music.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import app.hush.music.innertube.models.YTItem
import app.hush.music.innertube.models.splitBySeparator
import app.hush.music.innertube.utils.parseTime
import app.hush.music.innertube.models.YouTubeClient
import app.hush.music.innertube.models.YouTubeClient.Companion.WEB
import app.hush.music.innertube.models.YouTubeClient.Companion.WEB_REMIX
import app.hush.music.innertube.models.YouTubeLocale
import app.hush.music.innertube.models.getContinuation
import app.hush.music.innertube.models.getItems
import app.hush.music.innertube.models.oddElements
import app.hush.music.innertube.models.response.AccountMenuResponse
import app.hush.music.innertube.models.response.AddItemYouTubePlaylistResponse
import app.hush.music.innertube.models.response.BrowseResponse
import app.hush.music.innertube.models.response.CreatePlaylistResponse
import app.hush.music.innertube.models.response.GetQueueResponse
import app.hush.music.innertube.models.response.GetSearchSuggestionsResponse
import app.hush.music.innertube.models.response.GetTranscriptResponse
import app.hush.music.innertube.models.response.NextResponse
import app.hush.music.innertube.models.response.PlayerResponse
import app.hush.music.innertube.models.response.SearchResponse
import app.hush.music.innertube.pages.AlbumPage
import app.hush.music.innertube.pages.ArtistItemsContinuationPage
import app.hush.music.innertube.pages.ArtistItemsPage
import app.hush.music.innertube.pages.ArtistItemsPageLayout
import app.hush.music.innertube.pages.ArtistPage
import app.hush.music.innertube.pages.BrowseResult
import app.hush.music.innertube.pages.ChartsPage
import app.hush.music.innertube.pages.ExplorePage
import app.hush.music.innertube.pages.HistoryPage
import app.hush.music.innertube.pages.HomePage
import app.hush.music.innertube.pages.LibraryContinuationPage
import app.hush.music.innertube.pages.LibraryPage
import app.hush.music.innertube.pages.MoodAndGenres
import app.hush.music.innertube.pages.NewReleaseAlbumPage
import app.hush.music.innertube.pages.NextPage
import app.hush.music.innertube.pages.NextResult
import app.hush.music.innertube.pages.PageHelper
import app.hush.music.innertube.pages.PlaylistContinuationPage
import app.hush.music.innertube.pages.PlaylistPage
import app.hush.music.innertube.pages.PodcastPage
import app.hush.music.innertube.pages.RelatedPage
import app.hush.music.innertube.pages.SearchPage
import app.hush.music.innertube.pages.SearchResult
import app.hush.music.innertube.pages.SearchSuggestionPage
import app.hush.music.innertube.pages.SearchSummary
import app.hush.music.innertube.pages.SearchSummaryPage
import app.hush.music.innertube.proxy.RotatingProxyClient
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Parse useful data with [InnerTube] sending requests.
 * Modified from [ViMusic](https://github.com/vfsfitvnm/ViMusic)
 */
object YouTube {
    private const val BROWSE_ID_EXPLORE = "FEmusic_explore"
    private const val BROWSE_ID_NEW_RELEASE_ALBUMS = "FEmusic_new_releases_albums"
    private const val BROWSE_ID_MOODS_AND_GENRES = "FEmusic_moods_and_genres"

    private val innerTube = InnerTube()
    private val accountSwitcherClient = WEB.copy(loginSupported = true)
    private val mutableAuthState = MutableStateFlow(PlaybackAuthState.EMPTY)
    private val authStateLock = Any()

    val authStateFlow: StateFlow<PlaybackAuthState> = mutableAuthState.asStateFlow()

    private val _historySyncEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val historySyncEvent: SharedFlow<Unit> = _historySyncEvent.asSharedFlow()

    fun notifyHistorySynced() {
        _historySyncEvent.tryEmit(Unit)
    }

    var authState: PlaybackAuthState
        get() = mutableAuthState.value
        set(value) {
            synchronized(authStateLock) {
                applyAuthState(value)
            }
        }

    /** Updates Web PoTokens only when the session that minted them is still active. */
    fun updateWebPoTokensIfSessionMatches(
        sessionId: String,
        sessionToken: String,
        playerToken: String,
    ): PlaybackAuthState? =
        synchronized(authStateLock) {
            val currentAuthState = mutableAuthState.value
            if (currentAuthState.sessionId != sessionId) {
                null
            } else {
                currentAuthState.copy(
                    poTokenGvs = sessionToken,
                    poTokenPlayer = playerToken,
                    webClientPoTokenEnabled = true,
                ).also(::applyAuthState)
            }
        }

    private fun applyAuthState(value: PlaybackAuthState) {
        val normalized = value.normalized()
        mutableAuthState.value = normalized
        innerTube.applyAuthState(normalized)
    }

    private inline fun updateAuthState(transform: (PlaybackAuthState) -> PlaybackAuthState) {
        synchronized(authStateLock) {
            applyAuthState(transform(mutableAuthState.value))
        }
    }

    var locale: YouTubeLocale
        get() = innerTube.locale
        set(value) {
            innerTube.locale = value
        }
    var visitorData: String?
        get() = authState.visitorData
        set(value) {
            updateAuthState { it.copy(visitorData = value) }
        }
    var dataSyncId: String?
        get() = authState.dataSyncId
        set(value) {
            updateAuthState { it.copy(dataSyncId = value) }
        }
    var cookie: String?
        get() = authState.cookie
        set(value) {
            updateAuthState { it.copy(cookie = value) }
        }
    var poToken: String?
        get() = authState.poToken
        set(value) {
            updateAuthState { it.copy(poToken = value) }
        }
    var webClientPoTokenEnabled: Boolean
        get() = authState.webClientPoTokenEnabled
        set(value) {
            updateAuthState { it.copy(webClientPoTokenEnabled = value) }
        }
    var poTokenGvs: String?
        get() = authState.poTokenGvs
        set(value) {
            updateAuthState { it.copy(poTokenGvs = value) }
        }
    var poTokenPlayer: String?
        get() = authState.poTokenPlayer
        set(value) {
            updateAuthState { it.copy(poTokenPlayer = value) }
        }
    var proxy: Proxy?
        get() = innerTube.proxy
        set(value) {
            innerTube.proxy = value
        }
    var proxyUsername: String?
        get() = innerTube.proxyUsername
        set(value) {
            innerTube.proxyUsername = value
        }
    var proxyPassword: String?
        get() = innerTube.proxyPassword
        set(value) {
            innerTube.proxyPassword = value
        }
    private val okHttpNetworkVersion = AtomicInteger(0)

    var dns: Dns
        get() = innerTube.dns
        set(value) {
            innerTube.dns = value
            bumpOkHttpNetworkVersion()
        }
    var ipVersion: IpVersion
        get() = innerTube.ipVersion
        set(value) {
            innerTube.ipVersion = value
            bumpOkHttpNetworkVersion()
        }

    fun okHttpNetworkVersion(): Int = okHttpNetworkVersion.get()

    fun playbackDns(): Dns = dns.withIpVersionPreference(ipVersion)

    fun newOkHttpClientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder().dns(playbackDns())

    private fun bumpOkHttpNetworkVersion() {
        okHttpNetworkVersion.incrementAndGet()
    }
    var streamBypassProxy: Boolean = false
    val streamProxy: Proxy?
        get() = if (streamBypassProxy) null else proxy
    val streamOkHttpProxy: Proxy
        get() = streamProxy ?: Proxy.NO_PROXY
    var useLoginForBrowse: Boolean
        get() = innerTube.useLoginForBrowse
        set(value) {
            innerTube.useLoginForBrowse = value
        }

    val rotatingProxyClient = RotatingProxyClient()
    private val _ipRotationActiveCount = MutableStateFlow(0)
    val ipRotationActiveCount: StateFlow<Int> = _ipRotationActiveCount.asStateFlow()

    suspend fun enableIpRotation() {
        withContext(Dispatchers.IO) {
            rotatingProxyClient.fetchAndLoad()
            innerTube.proxySelector = rotatingProxyClient.selector()
            _ipRotationActiveCount.value = rotatingProxyClient.activeCount()
        }
    }

    suspend fun refreshIpRotation() {
        withContext(Dispatchers.IO) {
            if (rotatingProxyClient.activeCount() <= 1) {
                rotatingProxyClient.fetchAndLoad()
            } else {
                rotatingProxyClient.rotate()
            }
            innerTube.proxySelector = rotatingProxyClient.selector()
            _ipRotationActiveCount.value = rotatingProxyClient.activeCount()
        }
    }

    fun disableIpRotation() {
        innerTube.proxySelector = null
        _ipRotationActiveCount.value = 0
    }

    fun currentPlaybackAuthState(): PlaybackAuthState = authState

    private val dnsProviderUrls =
        mapOf(
            "Google" to "https://dns.google/dns-query",
            "Cloudflare" to "https://cloudflare-dns.com/dns-query",
            "AdGuard" to "https://dns.adguard.com/dns-query",
            "Quad9" to "https://dns.quad9.net/dns-query",
        )

    fun resolveDnsProviderUrl(
        provider: String,
        customUrl: String,
    ): String? {
        val url = if (provider == "Custom") customUrl else dnsProviderUrls[provider]
        return url?.takeIf { it.isNotBlank() && it.startsWith("https://") }
    }

    fun createDnsOverHttps(url: String): Dns {
        val httpUrl = url.toHttpUrl()
        return object : Dns {
            @Volatile
            private var cached: Dns? = null
            private val cacheLock = Any()

            private fun getOrCreateDelegate(): Dns {
                return cached
                    ?: synchronized(cacheLock) {
                        cached
                            ?: buildDnsOverHttps(httpUrl).also { cached = it }
                    }
            }

            override fun lookup(hostname: String): List<InetAddress> {
                return try {
                    getOrCreateDelegate().lookup(hostname)
                } catch (e: Exception) {
                    // Clear cache on failure so next lookup tries fresh
                    synchronized(cacheLock) {
                        cached = null
                    }
                    throw e
                }
            }
        }
    }

    private fun buildDnsOverHttps(httpUrl: okhttp3.HttpUrl): Dns {
        val bootstrapClient =
            OkHttpClient
                .Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .build()
        val builder =
            DnsOverHttps
                .Builder()
                .client(bootstrapClient)
                .url(httpUrl)
                .includeIPv6(true)
                .post(true)
        bootstrapDnsHostsFor(httpUrl.host).takeIf { it.isNotEmpty() }?.let { hosts ->
            builder.bootstrapDnsHosts(*hosts)
        }
        return builder.build()
    }

    private fun bootstrapDnsHostsFor(host: String): Array<InetAddress> {
        val hostnames =
            when (host) {
                "dns.google" -> arrayOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844")
                "cloudflare-dns.com", "one.one.one.one" ->
                    arrayOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")
                "dns.adguard.com", "dns.adguard-dns.com" -> arrayOf("94.140.14.14", "94.140.15.15")
                "dns.quad9.net" -> arrayOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9")
                else -> emptyArray()
            }
        return hostnames.mapNotNull { hostname ->
            runCatching { InetAddress.getByName(hostname) }.getOrNull()
        }.toTypedArray()
    }

    private fun resolvePlayerPoToken(
        client: YouTubeClient,
        explicitPoToken: String?,
        authState: PlaybackAuthState,
    ): String? =
        authState.resolvePlayerPoToken(
            client = client,
            explicitPoToken = explicitPoToken,
        )

    fun hasLoginCookie(): Boolean = authState.hasLoginCookie

    fun hasPlaybackLoginContext(): Boolean = authState.hasPlaybackLoginContext

    internal fun resolveGvsPoToken(authState: PlaybackAuthState = currentPlaybackAuthState()): String? = authState.resolveGvsPoToken()

    internal fun appendGvsPoToken(
        url: String,
        client: YouTubeClient? = null,
        authState: PlaybackAuthState = currentPlaybackAuthState(),
    ): String {
        val token = authState.resolveGvsPoToken(client) ?: return url
        if (url.contains("pot=")) return url

        val separator = if (url.contains("?")) "&" else "?"
        return "$url${separator}pot=$token"
    }

    suspend fun searchSuggestions(query: String): Result<SearchSuggestions> =
        runCatching {
            val response = innerTube.getSearchSuggestions(WEB_REMIX, query).body<GetSearchSuggestionsResponse>()
            SearchSuggestions(
                queries =
                    response.contents
                        ?.getOrNull(0)
                        ?.searchSuggestionsSectionRenderer
                        ?.contents
                        ?.mapNotNull { content ->
                            content.searchSuggestionRenderer
                                ?.suggestion
                                ?.runs
                                ?.joinToString(separator = "") { it.text }
                        }.orEmpty(),
                recommendedItems =
                    response.contents
                        ?.getOrNull(1)
                        ?.searchSuggestionsSectionRenderer
                        ?.contents
                        ?.mapNotNull {
                            it.musicResponsiveListItemRenderer?.let { renderer ->
                                SearchSuggestionPage.fromMusicResponsiveListItemRenderer(renderer)
                            }
                        }.orEmpty(),
            )
        }

    suspend fun searchSummary(query: String): Result<SearchSummaryPage> =
        runCatching {
            val response = innerTube.search(WEB_REMIX, query).body<SearchResponse>()
            val contents =
                response.contents
                    ?.tabbedSearchResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
                    ?.contents
                    .orEmpty()
            val topItems = mutableListOf<YTItem>()
            val summaries = mutableListOf<SearchSummary>()

            contents.forEach { content ->
                content.musicCardShelfRenderer?.let { renderer ->
                    topItems +=
                        listOfNotNull(SearchSummaryPage.fromMusicCardShelfRenderer(renderer))
                            .plus(
                                renderer.contents
                                    ?.mapNotNull { it.musicResponsiveListItemRenderer }
                                    ?.mapNotNull { SearchSummaryPage.fromMusicResponsiveListItemRenderer(it) }
                                    .orEmpty(),
                            )
                    return@forEach
                }

                content.itemSectionRenderer?.contents?.let { sectionContents ->
                    topItems +=
                        sectionContents.mapNotNull {
                            it.musicResponsiveListItemRenderer?.let { renderer ->
                                SearchSummaryPage.fromMusicResponsiveListItemRenderer(renderer)
                            }
                        }
                    summaries +=
                        sectionContents.mapNotNull { it.musicShelfRenderer?.toSearchSummary() }
                    return@forEach
                }

                content.musicShelfRenderer?.toSearchSummary()?.let(summaries::add)
            }

            SearchSummaryPage(
                summaries =
                    buildList {
                        topItems
                            .distinctBy { it.id }
                            .takeIf { it.isNotEmpty() }
                            ?.let { add(SearchSummary(title = "Top results", items = it)) }
                        addAll(summaries)
                    },
            )
        }

    suspend fun search(
        query: String,
        filter: SearchFilter,
        useAccountContext: Boolean = true,
    ): Result<SearchResult> =
        runCatching {
            val response =
                innerTube
                    .search(
                        client = WEB_REMIX,
                        query = query,
                        params = filter.value,
                        useAccountContext = useAccountContext,
                    ).body<SearchResponse>()
            val contents =
                response.contents
                    ?.tabbedSearchResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
                    ?.contents
                    .orEmpty()
            val shelves =
                contents.flatMap { content ->
                    buildList {
                        content.musicShelfRenderer?.let { add(it) }
                        content.itemSectionRenderer
                            ?.contents
                            ?.mapNotNull { it.musicShelfRenderer }
                            ?.let { addAll(it) }
                    }
                }
            val inlineItems =
                contents.flatMap { content ->
                    content.itemSectionRenderer
                        ?.contents
                        ?.mapNotNull { it.musicResponsiveListItemRenderer }
                        .orEmpty()
                }
            SearchResult(
                items =
                    shelves
                        .flatMap { it.contents?.getItems().orEmpty() }
                        .plus(inlineItems)
                        .mapNotNull { SearchPage.toYTItem(it) }
                        .distinctBy { it.id },
                continuation =
                    shelves
                        .asSequence()
                        .mapNotNull { it.continuations?.getContinuation() ?: it.contents?.getContinuation() }
                        .firstOrNull(),
            )
        }

    suspend fun searchContinuation(
        continuation: String,
        useAccountContext: Boolean = true,
    ): Result<SearchResult> =
        runCatching {
            val response =
                innerTube
                    .search(
                        client = WEB_REMIX,
                        continuation = continuation,
                        useAccountContext = useAccountContext,
                    ).body<SearchResponse>()
            val continuationPage = response.continuationContents?.musicShelfContinuation
            val items =
                continuationPage
                    ?.contents
                    ?.mapNotNull {
                        it.musicResponsiveListItemRenderer?.let { renderer -> SearchPage.toYTItem(renderer) }
                    }
                    ?: emptyList()
            SearchResult(
                items = items,
                continuation =
                    if (items.isEmpty()) {
                        null
                    } else {
                        continuationPage?.continuations?.getContinuation()
                            ?: continuationPage
                                ?.contents
                                ?.firstOrNull { it.continuationItemRenderer != null }
                                ?.continuationItemRenderer
                                ?.continuationEndpoint
                                ?.continuationCommand
                                ?.token
                    },
            )
        }

    private fun MusicShelfRenderer.toSearchSummary(): SearchSummary? {
        val items =
            contents
                ?.getItems()
                ?.mapNotNull { SearchSummaryPage.fromMusicResponsiveListItemRenderer(it) }
                ?.distinctBy { it.id }
                .orEmpty()
        if (items.isEmpty()) return null

        val title =
            title
                ?.runs
                ?.joinToString(separator = "") { it.text }
                ?.takeIf { it.isNotBlank() }
                ?: "Other"

        return SearchSummary(title = title, items = items)
    }

    suspend fun album(
        browseId: String,
        withSongs: Boolean = true,
    ): Result<AlbumPage> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, browseId).body<BrowseResponse>()
            val playlistId =
                AlbumPage.getPlaylistId(response)
                    ?: throw IllegalStateException("Missing album playlist id for $browseId")
            val albumTitle =
                AlbumPage.getTitle(response)
                    ?: throw IllegalStateException("Missing album title for $browseId")
            val albumArtists = AlbumPage.getArtists(response).takeIf { it.isNotEmpty() }
            val albumYear = AlbumPage.getYear(response)
            val albumThumbnail =
                AlbumPage.getThumbnail(response)
                    ?: throw IllegalStateException("Missing album thumbnail url for $browseId")
            val albumItem =
                AlbumItem(
                    browseId = browseId,
                    playlistId = playlistId,
                    title = albumTitle,
                    artists = albumArtists,
                    year = albumYear,
                    thumbnail = albumThumbnail,
                    explicit = false, // TODO: Extract explicit badge for albums from YouTube response
                )
            val inlineSongs = if (withSongs) AlbumPage.getSongs(response, albumItem) else emptyList()
            val songs =
                if (withSongs) {
                    val fetchedSongs =
                        runCatching {
                            albumSongs(playlistId, albumItem).getOrThrow()
                        }.getOrElse { error ->
                            if (inlineSongs.isNotEmpty()) {
                                inlineSongs
                            } else {
                                throw error
                            }
                        }

                    if (fetchedSongs.isEmpty() && inlineSongs.isNotEmpty()) {
                        inlineSongs
                    } else {
                        fetchedSongs
                    }
                } else {
                    emptyList()
                }

            AlbumPage(
                album = albumItem,
                songs = songs,
                otherVersions =
                    response.contents
                        ?.twoColumnBrowseResultsRenderer
                        ?.secondaryContents
                        ?.sectionListRenderer
                        ?.contents
                        ?.mapNotNull { it.musicCarouselShelfRenderer }
                        ?.flatMap { it.contents }
                        ?.mapNotNull { it.musicTwoRowItemRenderer }
                        ?.mapNotNull(NewReleaseAlbumPage::fromMusicTwoRowItemRenderer)
                        ?.distinctBy { it.id }
                        .orEmpty(),
            )
        }

    suspend fun albumSongs(
        playlistId: String,
        album: AlbumItem? = null,
    ): Result<List<SongItem>> =
        runCatching {
            var response = innerTube.browse(WEB_REMIX, "VL$playlistId").body<BrowseResponse>()
            val songs = linkedMapOf<String, SongItem>()

            fun appendSongs(
                candidates: List<MusicResponsiveListItemRenderer>,
                parsedSongs: List<SongItem>,
                source: String,
            ): Boolean {
                if (candidates.isNotEmpty() && parsedSongs.isEmpty()) {
                    throw IllegalStateException("Unable to parse album songs from $source for playlist $playlistId")
                }

                val previousSize = songs.size
                parsedSongs.forEach { songs.putIfAbsent(it.id, it) }
                return songs.size > previousSize
            }

            appendSongs(
                candidates = AlbumPage.getSongRenderers(response),
                parsedSongs = AlbumPage.getSongs(response, album),
                source = "initial response",
            )

            var continuation = AlbumPage.getSongContinuation(response)
            val seenContinuations = mutableSetOf<String>()
            var requestCount = 0
            val maxRequests = 50 // Prevent excessive API calls

            var consecutiveEmptyResponses = 0
            while (continuation != null && requestCount < maxRequests) {
                if (continuation in seenContinuations) {
                    break
                }
                seenContinuations.add(continuation)
                requestCount++

                response =
                    innerTube
                        .browse(
                            client = WEB_REMIX,
                            continuation = continuation,
                        ).body<BrowseResponse>()

                val newSongCandidates = AlbumPage.getContinuationSongRenderers(response)
                val newSongs = AlbumPage.getContinuationSongs(response, album)
                val hasNewSongs =
                    if (newSongCandidates.isNotEmpty() || newSongs.isNotEmpty()) {
                        appendSongs(
                            candidates = newSongCandidates,
                            parsedSongs = newSongs,
                            source = "continuation response",
                        )
                    } else {
                        false
                    }

                if (!hasNewSongs) {
                    consecutiveEmptyResponses++
                    if (consecutiveEmptyResponses >= 2) break
                } else {
                    consecutiveEmptyResponses = 0
                }

                continuation = AlbumPage.getNextSongContinuation(response)
            }
            songs.values.toList()
        }

    suspend fun artist(browseId: String): Result<ArtistPage> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, browseId).body<BrowseResponse>()
            val immersiveHeader = response.header?.musicImmersiveHeaderRenderer
            val subscribeButtonRenderer = immersiveHeader?.subscriptionButton?.subscribeButtonRenderer

            ArtistPage(
                artist =
                    ArtistItem(
                        id = browseId,
                        title =
                            immersiveHeader
                                ?.title
                                ?.runs
                                ?.firstOrNull()
                                ?.text
                                ?: response.header
                                    ?.musicVisualHeaderRenderer
                                    ?.title
                                    ?.runs
                                    ?.firstOrNull()
                                    ?.text
                                ?: response.header
                                    ?.musicHeaderRenderer
                                    ?.title
                                    ?.runs
                                    ?.firstOrNull()
                                    ?.text!!,
                        thumbnail =
                            immersiveHeader?.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                                ?: response.header
                                    ?.musicVisualHeaderRenderer
                                    ?.foregroundThumbnail
                                    ?.musicThumbnailRenderer
                                    ?.getThumbnailUrl()
                                ?: response.header
                                    ?.musicDetailHeaderRenderer
                                    ?.thumbnail
                                    ?.musicThumbnailRenderer
                                    ?.getThumbnailUrl(),
                        channelId = subscribeButtonRenderer?.channelId,
                        playEndpoint =
                            response.contents
                                ?.singleColumnBrowseResultsRenderer
                                ?.tabs
                                ?.firstOrNull()
                                ?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.contents
                                ?.firstOrNull()
                                ?.musicShelfRenderer
                                ?.contents
                                ?.firstOrNull()
                                ?.musicResponsiveListItemRenderer
                                ?.overlay
                                ?.musicItemThumbnailOverlayRenderer
                                ?.content
                                ?.musicPlayButtonRenderer
                                ?.playNavigationEndpoint
                                ?.watchEndpoint,
                        shuffleEndpoint =
                            immersiveHeader
                                ?.playButton
                                ?.buttonRenderer
                                ?.navigationEndpoint
                                ?.watchEndpoint
                                ?: response.contents
                                    ?.singleColumnBrowseResultsRenderer
                                    ?.tabs
                                    ?.firstOrNull()
                                    ?.tabRenderer
                                    ?.content
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.firstOrNull()
                                    ?.musicShelfRenderer
                                    ?.contents
                                    ?.firstOrNull()
                                    ?.musicResponsiveListItemRenderer
                                    ?.navigationEndpoint
                                    ?.watchPlaylistEndpoint,
                        radioEndpoint =
                            immersiveHeader
                                ?.startRadioButton
                                ?.buttonRenderer
                                ?.navigationEndpoint
                                ?.watchEndpoint,
                        subscriberCountText =
                            subscribeButtonRenderer
                                ?.subscriberCountText
                                ?.runs
                                ?.firstOrNull()
                                ?.text
                                ?: subscribeButtonRenderer
                                    ?.subscriberCountWithSubscribeText
                                    ?.runs
                                    ?.firstOrNull()
                                    ?.text,
                        monthlyListenerCountText =
                            immersiveHeader
                                ?.monthlyListenerCount
                                ?.runs
                                ?.firstOrNull()
                                ?.text,
                    ),
                sections =
                    response.contents
                        ?.singleColumnBrowseResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.mapNotNull(ArtistPage::fromSectionListRendererContent)
                        .orEmpty(),
                description =
                    immersiveHeader
                        ?.description
                        ?.runs
                        ?.joinToString(separator = "") { run -> run.text }
                        ?.takeIf { description -> description.isNotBlank() },
            )
        }

    suspend fun artistItems(endpoint: BrowseEndpoint): Result<ArtistItemsPage> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, endpoint.browseId, endpoint.params).body<BrowseResponse>()
            val sectionContents = response.artistItemsSectionContents()
            val gridRenderer = sectionContents.firstNotNullOfOrNull { it.findGridRenderer() }
            if (gridRenderer != null) {
                ArtistItemsPage(
                    title =
                        gridRenderer.header
                            ?.gridHeaderRenderer
                            ?.title
                            ?.runs
                            ?.firstOrNull()
                            ?.text
                            .orEmpty(),
                    items =
                        gridRenderer.items.mapNotNull {
                            it.musicTwoRowItemRenderer?.let { renderer ->
                                ArtistItemsPage.fromMusicTwoRowItemRenderer(renderer)
                            }
                        },
                    continuation = gridRenderer.continuations?.getContinuation(),
                    layout = ArtistItemsPageLayout.GRID,
                )
            } else {
                val musicPlaylistShelfRenderer = sectionContents.firstNotNullOfOrNull { it.findMusicPlaylistShelfRenderer() }
                val musicShelfRenderer = sectionContents.firstNotNullOfOrNull { it.findMusicShelfRenderer() }
                val shelfContents = musicPlaylistShelfRenderer?.contents ?: musicShelfRenderer?.contents.orEmpty()
                ArtistItemsPage(
                    title =
                        response.header
                            ?.musicHeaderRenderer
                            ?.title
                            ?.runs
                            ?.firstOrNull()
                            ?.text
                            ?: musicShelfRenderer
                                ?.title
                                ?.runs
                                ?.firstOrNull()
                                ?.text
                            ?: "",
                    items =
                        shelfContents.getItems().mapNotNull {
                            ArtistItemsPage.fromMusicResponsiveListItemRenderer(it)
                        },
                    continuation =
                        shelfContents.getContinuation()
                            ?: musicPlaylistShelfRenderer?.continuations?.getContinuation()
                            ?: musicShelfRenderer?.continuations?.getContinuation(),
                    layout = ArtistItemsPageLayout.LIST,
                )
            }
        }

    private fun BrowseResponse.artistItemsSectionContents(): List<SectionListRenderer.Content> =
        contents
            ?.singleColumnBrowseResultsRenderer
            ?.tabs
            ?.firstOrNull()
            ?.tabRenderer
            ?.content
            ?.sectionListRenderer
            ?.contents
            .orEmpty()

    private fun SectionListRenderer.Content.findGridRenderer(): GridRenderer? =
        gridRenderer ?: itemSectionRenderer?.contents?.firstNotNullOfOrNull { it.gridRenderer }

    private fun SectionListRenderer.Content.findMusicPlaylistShelfRenderer(): MusicPlaylistShelfRenderer? = musicPlaylistShelfRenderer

    private fun SectionListRenderer.Content.findMusicShelfRenderer(): MusicShelfRenderer? =
        musicShelfRenderer ?: itemSectionRenderer?.contents?.firstNotNullOfOrNull { it.musicShelfRenderer }

    suspend fun artistItemsContinuation(continuation: String): Result<ArtistItemsContinuationPage> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, continuation = continuation).body<BrowseResponse>()

            when {
                response.continuationContents?.gridContinuation != null -> {
                    val gridContinuation = response.continuationContents.gridContinuation
                    val items =
                        gridContinuation.items.mapNotNull {
                            it.musicTwoRowItemRenderer?.let { renderer ->
                                ArtistItemsPage.fromMusicTwoRowItemRenderer(renderer)
                            }
                        }
                    ArtistItemsContinuationPage(
                        items = items,
                        continuation = if (items.isEmpty()) null else gridContinuation.continuations?.getContinuation(),
                    )
                }

                response.continuationContents?.musicPlaylistShelfContinuation != null -> {
                    val musicPlaylistShelfContinuation = response.continuationContents.musicPlaylistShelfContinuation
                    val items =
                        musicPlaylistShelfContinuation.contents.getItems().mapNotNull {
                            ArtistItemsPage.fromMusicResponsiveListItemRenderer(it)
                        }
                    ArtistItemsContinuationPage(
                        items = items,
                        continuation = if (items.isEmpty()) null else musicPlaylistShelfContinuation.continuations?.getContinuation(),
                    )
                }

                else -> {
                    val continuationItems =
                        response.onResponseReceivedActions
                            ?.firstOrNull()
                            ?.appendContinuationItemsAction
                            ?.continuationItems
                    val items =
                        continuationItems?.getItems()?.mapNotNull {
                            ArtistItemsPage.fromMusicResponsiveListItemRenderer(it)
                        } ?: emptyList()
                    ArtistItemsContinuationPage(
                        items = items,
                        continuation = if (items.isEmpty()) null else continuationItems?.getContinuation(),
                    )
                }
            }
        }

    suspend fun playlist(playlistId: String): Result<PlaylistPage> =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = "VL$playlistId",
                        setLogin = true,
                    ).body<BrowseResponse>()
            val primarySection =
                response.contents
                    ?.twoColumnBrowseResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
            val allFirstColumnContents = primarySection?.contents.orEmpty()
            val base =
                allFirstColumnContents.firstOrNull {
                    it.musicResponsiveHeaderRenderer != null || it.musicEditablePlaylistDetailHeaderRenderer != null
                }
            val header =
                base?.musicResponsiveHeaderRenderer
                    ?: base?.musicEditablePlaylistDetailHeaderRenderer?.header?.musicResponsiveHeaderRenderer
            if (header == null) throw IllegalStateException("PLAYLIST_PRIVATE")

            val title =
                header.title.runs
                    ?.firstOrNull()
                    ?.text ?: throw IllegalStateException("PLAYLIST_PRIVATE")
            val thumbnail =
                header.thumbnail
                    ?.musicThumbnailRenderer
                    ?.thumbnail
                    ?.thumbnails
                    ?.lastOrNull()
                    ?.normalizedUrl
                    ?: throw IllegalStateException("PLAYLIST_PRIVATE")

            val editable = base?.musicEditablePlaylistDetailHeaderRenderer != null

            val headerMenuItems =
                header.buttons
                    .firstOrNull { it.menuRenderer != null }
                    ?.menuRenderer
                    ?.items
                    .orEmpty()

            val description =
                base
                    ?.musicEditablePlaylistDetailHeaderRenderer
                    ?.header
                    ?.musicDetailHeaderRenderer
                    ?.description
                    ?.runs
                    ?.joinToString("") { it.text }
                    ?: allFirstColumnContents.firstNotNullOfOrNull {
                        it.musicDescriptionShelfRenderer
                            ?.description
                            ?.runs
                            ?.joinToString("") { run -> run.text }
                    }
            val secondarySection =
                response.contents
                    ?.twoColumnBrowseResultsRenderer
                    ?.secondaryContents
                    ?.sectionListRenderer
            val secondaryContents = secondarySection?.contents.orEmpty()
            val songContents =
                buildList {
                    secondaryContents.forEach { content ->
                        addAll(content.playlistSongContents())
                    }
                    allFirstColumnContents.forEach { content ->
                        addAll(content.playlistSongContents())
                    }
                }
            val songsContinuation =
                secondaryContents.firstNotNullOfOrNull { content ->
                    content.playlistSongContinuation()
                } ?: allFirstColumnContents.firstNotNullOfOrNull { content ->
                    content.playlistSongContinuation()
                }

            PlaylistPage(
                playlist =
                    PlaylistItem(
                        id = playlistId,
                        title = title,
                        author =
                            header.straplineTextOne?.runs?.firstOrNull()?.let {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                )
                            },
                        songCountText =
                            header.secondSubtitle
                                ?.runs
                                ?.firstOrNull()
                                ?.text,
                        thumbnail = thumbnail,
                        description = description,
                        playEndpoint =
                            header.buttons
                                .firstOrNull()
                                ?.musicPlayButtonRenderer
                                ?.playNavigationEndpoint
                                ?.anyWatchEndpoint,
                        shuffleEndpoint =
                            headerMenuItems
                                .firstOrNull()
                                ?.menuNavigationItemRenderer
                                ?.navigationEndpoint
                                ?.watchPlaylistEndpoint,
                        radioEndpoint =
                            headerMenuItems
                                .find {
                                    it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                                }?.menuNavigationItemRenderer
                                ?.navigationEndpoint
                                ?.watchPlaylistEndpoint,
                        isEditable = editable,
                    ),
                songs =
                    songContents.getItems().mapNotNull {
                        PlaylistPage.fromMusicResponsiveListItemRenderer(it, playlistId)
                    },
                songsContinuation = songsContinuation,
                continuation =
                    secondarySection?.continuations?.getContinuation()
                        ?: primarySection?.continuations?.getContinuation(),
            )
        }

    suspend fun playlistContinuation(
        continuation: String,
        playlistId: String? = null,
    ): Result<PlaylistContinuationPage> =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        continuation = continuation,
                        browseId = "",
                        setLogin = true,
                    ).body<BrowseResponse>()

            playlistContinuationPageFromResponse(response, playlistId)
        }


    suspend fun podcast(podcastId: String): Result<PodcastPage> =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = podcastId,
                        setLogin = true,
                    ).body<BrowseResponse>()

            var header =
                response.contents
                    ?.twoColumnBrowseResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
                    ?.contents
                    ?.firstOrNull()
                    ?.musicResponsiveHeaderRenderer

            if (header == null) {
                header =
                    response.contents
                        ?.singleColumnBrowseResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.firstOrNull()
                        ?.musicResponsiveHeaderRenderer
            }

            val subscribeToggle =
                header
                    ?.buttons
                    ?.flatMap { button -> button.menuRenderer?.items ?: emptyList() }
                    ?.find { it.toggleMenuServiceItemRenderer?.defaultIcon?.iconType == "SUBSCRIBE" }
                    ?.toggleMenuServiceItemRenderer
            val channelId =
                subscribeToggle
                    ?.defaultServiceEndpoint
                    ?.subscribeEndpoint
                    ?.channelIds
                    ?.firstOrNull()

            val libraryTokens =
                header
                    ?.buttons
                    ?.flatMap { button -> button.menuRenderer?.items ?: emptyList() }
                    ?.let { menuItems -> PageHelper.extractLibraryTokensFromMenuItems(menuItems) }

            val podcastItem =
                PodcastItem(
                    id = podcastId,
                    title = header?.title?.runs?.firstOrNull()?.text ?: "",
                    author =
                        header?.straplineTextOne?.runs?.firstOrNull()?.let {
                            Artist(
                                name = it.text,
                                id = it.navigationEndpoint?.browseEndpoint?.browseId,
                            )
                        },
                    episodeCountText = header?.secondSubtitle?.runs?.firstOrNull()?.text,
                    thumbnail =
                        header
                            ?.thumbnail
                            ?.musicThumbnailRenderer
                            ?.thumbnail
                            ?.thumbnails
                            ?.lastOrNull()
                            ?.url,
                    playEndpoint =
                        header
                            ?.buttons
                            ?.find {
                                it.menuRenderer
                                    ?.items
                                    ?.firstOrNull()
                                    ?.menuNavigationItemRenderer
                                    ?.icon
                                    ?.iconType == "PLAY_ARROW"
                            }?.menuRenderer
                            ?.items
                            ?.firstOrNull()
                            ?.menuNavigationItemRenderer
                            ?.navigationEndpoint
                            ?.watchPlaylistEndpoint,
                    shuffleEndpoint =
                        header
                            ?.buttons
                            ?.find {
                                it.menuRenderer?.items?.any { item ->
                                    item.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE"
                                } == true
                            }?.menuRenderer
                            ?.items
                            ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE" }
                            ?.menuNavigationItemRenderer
                            ?.navigationEndpoint
                            ?.watchPlaylistEndpoint,
                    libraryAddToken = libraryTokens?.addToken,
                    libraryRemoveToken = libraryTokens?.removeToken,
                    channelId = channelId,
                )

            val secondaryContents = response.contents?.twoColumnBrowseResultsRenderer?.secondaryContents

            var episodeContents =
                secondaryContents
                    ?.sectionListRenderer
                    ?.contents
                    ?.firstOrNull()
                    ?.musicShelfRenderer
                    ?.contents

            if (episodeContents == null) {
                episodeContents =
                    secondaryContents
                        ?.sectionListRenderer
                        ?.contents
                        ?.firstOrNull()
                        ?.musicPlaylistShelfRenderer
                        ?.contents
            }

            if (episodeContents == null) {
                episodeContents =
                    response.contents
                        ?.singleColumnBrowseResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.find { it.musicShelfRenderer != null }
                        ?.musicShelfRenderer
                        ?.contents
            }

            val multiRowItems = episodeContents?.mapNotNull { it.musicMultiRowListItemRenderer } ?: emptyList()
            val episodes =
                multiRowItems.mapNotNull { renderer ->
                    PodcastPage.fromMusicMultiRowListItemRenderer(renderer, podcastItem)
                }

            PodcastPage(
                podcast = podcastItem,
                episodes = episodes,
                continuation =
                    response.contents
                        ?.twoColumnBrowseResultsRenderer
                        ?.secondaryContents
                        ?.sectionListRenderer
                        ?.contents
                        ?.firstOrNull()
                        ?.musicShelfRenderer
                        ?.continuations
                        ?.getContinuation()
                        ?: response.contents
                            ?.singleColumnBrowseResultsRenderer
                            ?.tabs
                            ?.firstOrNull()
                            ?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.find { it.musicShelfRenderer != null }
                            ?.musicShelfRenderer
                            ?.continuations
                            ?.getContinuation(),
                isChannelSubscribed = false,
            )
        }

    suspend fun home(
        continuation: String? = null,
        browseId: String? = null,
        params: String? = null,
    ): Result<HomePage> {
        if (continuation != null) {
            return homeContinuation(continuation)
        }

        // Try logged-in first, fall back to anonymous if sections are empty or parsing fails
        val result = tryHomePage(browseId, params, setLogin = true)
        if (result.isSuccess && result.getOrNull()?.sections?.isNotEmpty() == true) {
            return result
        }
        // Retry without login
        return tryHomePage(browseId, params, setLogin = false)
    }

    private suspend fun tryHomePage(
        browseId: String?,
        params: String?,
        setLogin: Boolean,
    ): Result<HomePage> =
        runCatching {
            val resolvedBrowseId = browseId?.takeIf { it.isNotBlank() } ?: "FEmusic_home"
            val response = innerTube.browse(WEB_REMIX, browseId = resolvedBrowseId, params = params, setLogin = setLogin).body<BrowseResponse>()

            // Try multiple response structure paths
            val sectionListRender =
                response.contents?.sectionListRenderer
                    ?: response.contents?.singleColumnBrowseResultsRenderer
                        ?.tabs?.firstOrNull()
                        ?.tabRenderer?.content
                        ?.sectionListRenderer
                    ?: response.contents?.twoColumnBrowseResultsRenderer
                        ?.tabs?.firstOrNull()
                        ?.tabRenderer?.content
                        ?.sectionListRenderer
            val continuation =
                sectionListRender
                    ?.continuations
                    ?.getContinuation()
            val sections =
                sectionListRender
                    ?.contents
                    ?.flatMap { content ->
                        buildList {
                            content.musicCarouselShelfRenderer
                                ?.let { HomePage.Section.fromMusicCarouselShelfRenderer(it) }
                                ?.let(::add)
                            content.itemSectionRenderer?.contents?.forEach { nested ->
                                val items = nested.musicShelfRenderer?.contents
                                    ?.mapNotNull { it.musicResponsiveListItemRenderer }
                                    ?.mapNotNull { SearchPage.toYTItem(it) }
                                    .orEmpty()
                                if (items.isNotEmpty()) {
                                    add(HomePage.Section(title = "", label = null, thumbnail = null, endpoint = null, items = items))
                                }
                            }
                        }
                    }.orEmpty()
            val chips =
                sectionListRender?.header
                    ?.chipCloudRenderer
                    ?.chips
                    ?.mapNotNull { HomePage.Chip.fromChipCloudChipRenderer(it) }
            HomePage(chips, sections, continuation)
        }

    private suspend fun homeContinuation(continuation: String): Result<HomePage> =
        runCatching {
            val response =
                innerTube.browse(WEB_REMIX, continuation = continuation).body<BrowseResponse>()
            val sections =
                response.continuationContents
                    ?.sectionListContinuation
                    ?.contents
                    ?.mapNotNull { it.musicCarouselShelfRenderer }
                    ?.mapNotNull {
                        HomePage.Section.fromMusicCarouselShelfRenderer(it)
                    }.orEmpty()
            val nextContinuation =
                if (sections.isEmpty()) {
                    null
                } else {
                    response.continuationContents
                        ?.sectionListContinuation
                        ?.continuations
                        ?.getContinuation()
                }
            HomePage(
                chips = null,
                sections = sections,
                continuation = nextContinuation,
            )
        }

    suspend fun explore(): Result<ExplorePage> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, browseId = BROWSE_ID_EXPLORE).body<BrowseResponse>()
            ExplorePage(
                newReleaseAlbums =
                    response.contents
                        ?.singleColumnBrowseResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.find {
                            it.musicCarouselShelfRenderer
                                ?.header
                                ?.musicCarouselShelfBasicHeaderRenderer
                                ?.moreContentButton
                                ?.buttonRenderer
                                ?.navigationEndpoint
                                ?.browseEndpoint
                                ?.browseId ==
                                BROWSE_ID_NEW_RELEASE_ALBUMS
                        }?.musicCarouselShelfRenderer
                        ?.contents
                        ?.mapNotNull { it.musicTwoRowItemRenderer }
                        ?.mapNotNull(NewReleaseAlbumPage::fromMusicTwoRowItemRenderer)
                        .orEmpty(),
                moodAndGenres =
                    response.contents
                        ?.singleColumnBrowseResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.find {
                            it.musicCarouselShelfRenderer
                                ?.header
                                ?.musicCarouselShelfBasicHeaderRenderer
                                ?.moreContentButton
                                ?.buttonRenderer
                                ?.navigationEndpoint
                                ?.browseEndpoint
                                ?.browseId ==
                                BROWSE_ID_MOODS_AND_GENRES
                        }?.musicCarouselShelfRenderer
                        ?.contents
                        ?.mapNotNull { it.musicNavigationButtonRenderer }
                        ?.mapNotNull(MoodAndGenres.Companion::fromMusicNavigationButtonRenderer)
                        .orEmpty(),
            )
        }

    suspend fun newReleaseAlbums(): Result<List<AlbumItem>> =
        runCatching {
            try {
                val directAlbums = newReleaseAlbumsFromBrowsePage()
                if (directAlbums.isNotEmpty()) return@runCatching directAlbums
            } catch (throwable: Throwable) {
                if (!throwable.isBrowsePageUnavailable()) throw throwable
            }
            explore().getOrThrow().newReleaseAlbums
        }

    private suspend fun newReleaseAlbumsFromBrowsePage(): List<AlbumItem> {
        val response = innerTube.browse(WEB_REMIX, browseId = BROWSE_ID_NEW_RELEASE_ALBUMS).body<BrowseResponse>()
        return response.newReleaseAlbumItems()
    }

    private fun BrowseResponse.newReleaseAlbumItems(): List<AlbumItem> {
        val contents =
            this.contents
                ?.singleColumnBrowseResultsRenderer
                ?.tabs
                ?.firstOrNull()
                ?.tabRenderer
                ?.content
                ?.sectionListRenderer
                ?.contents
                ?: this.contents
                    ?.sectionListRenderer
                    ?.contents
                ?: continuationContents
                    ?.sectionListContinuation
                    ?.contents
                ?: emptyList()

        return contents
            .asSequence()
            .flatMap { content ->
                sequence {
                    content.gridRenderer
                        ?.items
                        ?.asSequence()
                        ?.mapNotNull { it.musicTwoRowItemRenderer }
                        ?.forEach { yield(it) }
                    content.musicCarouselShelfRenderer
                        ?.contents
                        ?.asSequence()
                        ?.mapNotNull { it.musicTwoRowItemRenderer }
                        ?.forEach { yield(it) }
                    content.itemSectionRenderer
                        ?.contents
                        ?.asSequence()
                        ?.mapNotNull { it.gridRenderer }
                        ?.flatMap { it.items.asSequence() }
                        ?.mapNotNull { it.musicTwoRowItemRenderer }
                        ?.forEach { yield(it) }
                }
            }.mapNotNull(NewReleaseAlbumPage::fromMusicTwoRowItemRenderer)
            .toList()
    }

    private fun Throwable.isBrowsePageUnavailable(): Boolean {
        val exception = this as? ClientRequestException ?: return false
        return exception.response.status == HttpStatusCode.NotFound ||
            exception.response.status == HttpStatusCode.BadRequest
    }

    private suspend fun <T> withPlaylistMutationAuthRecovery(block: suspend () -> T): T {
        val initialAuthState = authState
        return try {
            block()
        } catch (e: Throwable) {
            if (!shouldRetryPlaylistMutationWithoutDelegatedContext(initialAuthState, e)) throw e
            authState = authState.copy(dataSyncId = null)
            block()
        }
    }

    private fun shouldRetryPlaylistMutationWithoutDelegatedContext(
        initialAuthState: PlaybackAuthState,
        failure: Throwable,
    ): Boolean {
        val exception = failure as? ClientRequestException ?: return false
        if (exception.response.status != HttpStatusCode.Forbidden) return false

        val currentAuthState = authState
        return initialAuthState.hasPlaybackLoginContext &&
            currentAuthState.hasPlaybackLoginContext &&
            currentAuthState.cookie == initialAuthState.cookie &&
            currentAuthState.dataSyncId == initialAuthState.dataSyncId
    }

    suspend fun moodAndGenres(): Result<List<MoodAndGenres>> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, browseId = BROWSE_ID_MOODS_AND_GENRES).body<BrowseResponse>()
            response.contents
                ?.singleColumnBrowseResultsRenderer
                ?.tabs
                ?.firstOrNull()
                ?.tabRenderer
                ?.content
                ?.sectionListRenderer
                ?.contents
                .orEmpty()
                .mapNotNull(MoodAndGenres.Companion::fromSectionListRendererContent)
        }

    suspend fun browse(
        browseId: String,
        params: String?,
    ): Result<BrowseResult> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, browseId = browseId, params = params).body<BrowseResponse>()
            val browseItems =
                response.contents
                    ?.singleColumnBrowseResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
                    ?.contents
                    ?.mapNotNull { content ->
                        when {
                            content.gridRenderer != null -> {
                                BrowseResult.Item(
                                    title =
                                        content.gridRenderer.header
                                            ?.gridHeaderRenderer
                                            ?.title
                                            ?.runs
                                            ?.firstOrNull()
                                            ?.text,
                                    items =
                                        content.gridRenderer.items
                                            .mapNotNull(GridRenderer.Item::musicTwoRowItemRenderer)
                                            .mapNotNull(RelatedPage.Companion::fromMusicTwoRowItemRenderer),
                                )
                            }

                            content.musicCarouselShelfRenderer != null -> {
                                BrowseResult.Item(
                                    title =
                                        content.musicCarouselShelfRenderer.header
                                            ?.musicCarouselShelfBasicHeaderRenderer
                                            ?.title
                                            ?.runs
                                            ?.firstOrNull()
                                            ?.text,
                                    items =
                                        content.musicCarouselShelfRenderer.contents
                                            .mapNotNull(MusicCarouselShelfRenderer.Content::musicTwoRowItemRenderer)
                                            .mapNotNull(RelatedPage.Companion::fromMusicTwoRowItemRenderer),
                                )
                            }

                            else -> {
                                null
                            }
                        }
                    }.orEmpty()
            BrowseResult(
                title =
                    response.header
                        ?.musicHeaderRenderer
                        ?.title
                        ?.runs
                        ?.firstOrNull()
                        ?.text,
                thumbnail =
                    response.header
                        ?.musicImmersiveHeaderRenderer
                        ?.thumbnail
                        ?.musicThumbnailRenderer
                        ?.getThumbnailUrl()
                        ?: response.header
                            ?.musicVisualHeaderRenderer
                            ?.foregroundThumbnail
                            ?.musicThumbnailRenderer
                            ?.getThumbnailUrl()
                        ?: response.header
                            ?.musicDetailHeaderRenderer
                            ?.thumbnail
                            ?.musicThumbnailRenderer
                            ?.getThumbnailUrl()
                        ?: response.header
                            ?.musicEditablePlaylistDetailHeaderRenderer
                            ?.header
                            ?.musicDetailHeaderRenderer
                            ?.thumbnail
                            ?.musicThumbnailRenderer
                            ?.getThumbnailUrl()
                        ?: response.header
                            ?.musicEditablePlaylistDetailHeaderRenderer
                            ?.header
                            ?.musicResponsiveHeaderRenderer
                            ?.thumbnail
                            ?.musicThumbnailRenderer
                            ?.getThumbnailUrl()
                        ?: response.header
                            ?.musicHeaderRenderer
                            ?.thumbnail
                            ?.thumbnails
                            ?.lastOrNull()
                            ?.normalizedUrl
                        ?: response.header
                            ?.musicHeaderRenderer
                            ?.straplineThumbnail
                            ?.thumbnails
                            ?.lastOrNull()
                            ?.normalizedUrl
                        ?: browseItems
                            .asSequence()
                            .flatMap { it.items.asSequence() }
                            .mapNotNull { it.thumbnail }
                            .firstOrNull(),
                items = browseItems,
            )
        }

    suspend fun library(
        browseId: String,
        tabIndex: Int = 0,
    ) = runCatching {
        val response =
            innerTube
                .browse(
                    client = WEB_REMIX,
                    browseId = browseId,
                    setLogin = true,
                ).body<BrowseResponse>()

        val tabs = response.contents?.singleColumnBrowseResultsRenderer?.tabs

        val contents =
            if (tabs != null && tabIndex >= 0 && tabIndex < tabs.size) {
                tabs[tabIndex]
                    .tabRenderer.content
                    ?.sectionListRenderer
                    ?.contents
                    .orEmpty()
            } else {
                emptyList()
            }
        LibraryPage(
            items = contents.flatMap { it.libraryItems() },
            continuation = contents.firstNotNullOfOrNull { it.libraryContinuation() },
        )
    }

    suspend fun libraryContinuation(continuation: String) =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        continuation = continuation,
                        setLogin = true,
                    ).body<BrowseResponse>()

            val contents = response.continuationContents
            val sectionContents = contents?.sectionListContinuation?.contents.orEmpty()
            val sectionItems = sectionContents.flatMap { it.libraryItems() }
            val gridItems =
                contents
                    ?.gridContinuation
                    ?.items
                    .orEmpty()
                    .mapNotNull(GridRenderer.Item::musicTwoRowItemRenderer)
                    .mapNotNull { LibraryPage.fromMusicTwoRowItemRenderer(it) }
            val shelfItems =
                contents
                    ?.musicShelfContinuation
                    ?.contents
                    .orEmpty()
                    .mapNotNull(MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
                    .mapNotNull { LibraryPage.fromMusicResponsiveListItemRenderer(it) }
            val playlistShelfItems =
                contents
                    ?.musicPlaylistShelfContinuation
                    ?.contents
                    .orEmpty()
                    .mapNotNull(MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
                    .mapNotNull { LibraryPage.fromMusicResponsiveListItemRenderer(it) }
            val items = sectionItems + gridItems + shelfItems + playlistShelfItems
            LibraryContinuationPage(
                items = items,
                continuation =
                    if (items.isEmpty()) {
                        null
                    } else {
                        sectionContents.firstNotNullOfOrNull { it.libraryContinuation() }
                            ?: contents?.sectionListContinuation?.continuations?.getContinuation()
                            ?: contents?.gridContinuation?.continuations?.getContinuation()
                            ?: contents
                                ?.musicShelfContinuation
                                ?.contents
                                .orEmpty()
                                .getContinuation()
                            ?: contents?.musicShelfContinuation?.continuations?.getContinuation()
                            ?: contents
                                ?.musicPlaylistShelfContinuation
                                ?.contents
                                .orEmpty()
                                .getContinuation()
                            ?: contents?.musicPlaylistShelfContinuation?.continuations?.getContinuation()
                    },
            )
        }

    suspend fun libraryRecentActivity(): Result<LibraryPage> =
        runCatching {
            val continuation = LibraryFilter.FILTER_RECENT_ACTIVITY.value

            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        continuation = continuation,
                        setLogin = true,
                    ).body<BrowseResponse>()

            val gridItems =
                response.continuationContents
                    ?.sectionListContinuation
                    ?.contents
                    ?.firstOrNull()
                    ?.gridRenderer
                    ?.items

            if (gridItems == null) {
                return@runCatching LibraryPage(
                    items = emptyList(),
                    continuation = null,
                )
            }

            val items =
                gridItems
                    .mapNotNull {
                        it.musicTwoRowItemRenderer?.let { renderer ->
                            LibraryPage.fromMusicTwoRowItemRenderer(renderer)
                        }
                    }.toMutableList()

        /*
         * We need to fetch the artist page when accessing the library because it allows to have
         * a proper playEndpoint, which is needed to correctly report the playing indicator in
         * the home page.
         *
         * Despite this, we need to use the old thumbnail because it's the proper format for a
         * square picture, which is what we need.
         */
            items.forEachIndexed { index, item ->
                if (item is ArtistItem) {
                    artist(item.id).getOrNull()?.artist?.let { fetchedArtist ->
                        items[index] = fetchedArtist.copy(thumbnail = item.thumbnail)
                    }
                }
            }

            LibraryPage(
                items = items,
                continuation = null,
            )
        }

    suspend fun getChartsPage(continuation: String? = null): Result<ChartsPage> =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = "FEmusic_charts",
                        params = "ggMGCgQIgAQ%3D",
                        continuation = continuation,
                    ).body<BrowseResponse>()

            val sections = mutableListOf<ChartsPage.ChartSection>()

            response.contents
                ?.singleColumnBrowseResultsRenderer
                ?.tabs
                ?.firstOrNull()
                ?.tabRenderer
                ?.content
                ?.sectionListRenderer
                ?.contents
                ?.forEach { content ->

                    content.musicCarouselShelfRenderer?.let { renderer ->
                        val title =
                            renderer.header
                                ?.musicCarouselShelfBasicHeaderRenderer
                                ?.title
                                ?.runs
                                ?.firstOrNull()
                                ?.text
                                ?: return@forEach

                        val items =
                            renderer.contents
                                .mapNotNull { item ->
                                    when {
                                        item.musicResponsiveListItemRenderer != null -> {
                                            convertToChartItem(item.musicResponsiveListItemRenderer)
                                        }

                                        item.musicTwoRowItemRenderer != null -> {
                                            convertMusicTwoRowItem(item.musicTwoRowItemRenderer)
                                        }

                                        else -> {
                                            null
                                        }
                                    }
                                }.filterNotNull()

                        if (items.isNotEmpty()) {
                            sections.add(
                                ChartsPage.ChartSection(
                                    title = title,
                                    items = items,
                                    chartType = determineChartType(title),
                                ),
                            )
                        }
                    }

                    content.gridRenderer?.let { renderer ->
                        val title =
                            renderer.header
                                ?.gridHeaderRenderer
                                ?.title
                                ?.runs
                                ?.firstOrNull()
                                ?.text
                                ?: return@let

                        val items =
                            renderer.items
                                .mapNotNull { item ->
                                    item.musicTwoRowItemRenderer?.let { renderer ->
                                        convertMusicTwoRowItem(renderer)
                                    }
                                }.filterNotNull()

                        if (items.isNotEmpty()) {
                            sections.add(
                                ChartsPage.ChartSection(
                                    title = title,
                                    items = items,
                                    chartType = ChartsPage.ChartType.NEW_RELEASES,
                                ),
                            )
                        }
                    }
                }

            ChartsPage(
                sections = sections,
                continuation =
                    response.continuationContents
                        ?.sectionListContinuation
                        ?.continuations
                        ?.getContinuation(),
            )
        }

    private fun determineChartType(title: String): ChartsPage.ChartType =
        when {
            title.contains("Trending", ignoreCase = true) -> ChartsPage.ChartType.TRENDING
            title.contains("Top", ignoreCase = true) -> ChartsPage.ChartType.TOP
            else -> ChartsPage.ChartType.GENRE
        }

    private fun convertToChartItem(renderer: MusicResponsiveListItemRenderer): YTItem? {
        return try {
            when {
                renderer.flexColumns.size >= 3 && renderer.playlistItemData?.videoId != null -> {
                    val firstColumn =
                        renderer.flexColumns
                            .getOrNull(0)
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text ?: return null

                    val secondColumn =
                        renderer.flexColumns
                            .getOrNull(1)
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text ?: return null

                    val titleRun = firstColumn.runs?.firstOrNull() ?: return null
                    val title = titleRun.text.takeIf { it.isNotBlank() } ?: return null

                    val artists =
                        secondColumn.runs?.mapNotNull { run ->
                            run.text.takeIf { it.isNotBlank() }?.let { name ->
                                Artist(
                                    name = name,
                                    id = run.navigationEndpoint?.browseEndpoint?.browseId,
                                )
                            }
                        } ?: emptyList()

                    val thirdColumn =
                        renderer.flexColumns
                            .getOrNull(2)
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text

                    SongItem(
                        id = renderer.playlistItemData.videoId,
                        title = title,
                        artists = artists,
                        thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        explicit =
                            renderer.badges?.any {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } == true,
                        chartPosition =
                            thirdColumn
                                ?.runs
                                ?.firstOrNull()
                                ?.text
                                ?.toIntOrNull(),
                        chartChange = thirdColumn?.runs?.getOrNull(1)?.text,
                    )
                }

                else -> {
                    null
                }
            }
        } catch (e: Exception) {
            println("Error converting chart item: ${e.message}\n${Json.encodeToString(renderer)}")
            null
        }
    }

    private fun convertMusicTwoRowItem(renderer: MusicTwoRowItemRenderer): YTItem? {
        return try {
            when {
                renderer.isSong -> {
                    val subtitle = renderer.subtitle?.runs ?: return null
                    val title = renderer.title.runs?.firstOrNull()?.text ?: return null
                    val videoId = renderer.navigationEndpoint.watchEndpoint?.videoId ?: return null
                    val artists = PageHelper.extractArtists(subtitle)
                    SongItem(
                        id = videoId,
                        title = title,
                        artists = artists,
                        thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        explicit =
                            renderer.subtitleBadges?.any {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } == true,
                    )
                }

                renderer.isAlbum -> {
                    AlbumItem(
                        browseId = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null,
                        playlistId =
                            renderer.thumbnailOverlay
                                ?.musicItemThumbnailOverlayRenderer
                                ?.content
                                ?.musicPlayButtonRenderer
                                ?.playNavigationEndpoint
                                ?.watchPlaylistEndpoint
                                ?.playlistId ?: return null,
                        title =
                            renderer.title.runs
                                ?.firstOrNull()
                                ?.text ?: return null,
                        artists =
                            renderer.subtitle?.runs?.oddElements()?.drop(1)?.mapNotNull {
                                it.navigationEndpoint?.browseEndpoint?.browseId?.let { id ->
                                    Artist(name = it.text, id = id)
                                }
                            },
                        year =
                            renderer.subtitle
                                ?.runs
                                ?.lastOrNull()
                                ?.text
                                ?.toIntOrNull(),
                        thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        explicit =
                            renderer.subtitleBadges?.any {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } == true,
                    )
                }

                else -> {
                    null
                }
            }
        } catch (e: Exception) {
            println("Error converting two row item: ${e.message}\n${Json.encodeToString(renderer)}")
            null
        }
    }

    suspend fun musicHistory() =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = "FEmusic_history",
                        setLogin = true,
                    ).body<BrowseResponse>()

            HistoryPage(
                sections =
                    response.contents
                        ?.singleColumnBrowseResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.flatMap(HistoryPage::fromSectionListContent)
                        .orEmpty(),
            )
        }

    /**
     * Fetch podcast discovery/recommendations page.
     * Returns sections like "Popular shows", "Popular episodes", category sections.
     */
    suspend fun podcastDiscover(): Result<HomePage> =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = "FEmusic_non_music_audio",
                        setLogin = true,
                    ).body<BrowseResponse>()

            val sectionListRenderer =
                response.contents
                    ?.singleColumnBrowseResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
            val carousels = sectionListRenderer?.contents?.mapNotNull { it.musicCarouselShelfRenderer } ?: emptyList()
            val sections =
                carousels.mapNotNull {
                    HomePage.Section.fromMusicCarouselShelfRenderer(it)
                }
            val chips =
                sectionListRenderer?.header?.chipCloudRenderer?.chips?.mapNotNull {
                    HomePage.Chip.fromChipCloudChipRenderer(it)
                }
            val continuation = sectionListRenderer?.continuations?.getContinuation()

            HomePage(chips, sections, continuation)
        }

    suspend fun likeVideo(
        videoId: String,
        like: Boolean,
    ) = runCatching {
        if (like) {
            innerTube.likeVideo(WEB_REMIX, videoId)
        } else {
            innerTube.unlikeVideo(WEB_REMIX, videoId)
        }
    }

    suspend fun likePlaylist(
        playlistId: String,
        like: Boolean,
    ) = runCatching {
        if (like) {
            innerTube.likePlaylist(WEB_REMIX, playlistId)
        } else {
            innerTube.unlikePlaylist(WEB_REMIX, playlistId)
        }
    }

    suspend fun subscribeChannel(
        channelId: String,
        subscribe: Boolean,
    ) = runCatching {
        if (subscribe) {
            innerTube.subscribeChannel(WEB_REMIX, channelId)
        } else {
            innerTube.unsubscribeChannel(WEB_REMIX, channelId)
        }
    }

    /**
     * Save a podcast show to library.
     * Uses likePlaylist API. Podcast IDs are "MPSP<playlistId>".
     */
    suspend fun savePodcast(
        podcastId: String,
        save: Boolean,
    ) = runCatching {
        val playlistId = podcastId.removePrefix("MPSP")
        if (save) {
            innerTube.likePlaylist(WEB_REMIX, playlistId)
        } else {
            innerTube.unlikePlaylist(WEB_REMIX, playlistId)
        }
    }

    /**
     * Add episode to "Episodes for Later" playlist (SE).
     */
    suspend fun addEpisodeToSavedEpisodes(videoId: String) =
        runCatching {
            innerTube.addToPlaylist(WEB_REMIX, "SE", videoId)
        }

    /**
     * Remove episode from "Episodes for Later" playlist (SE).
     * Note: setVideoId is required for removal and must be obtained from the playlist response.
     */
    suspend fun removeEpisodeFromSavedEpisodes(
        videoId: String,
        setVideoId: String,
    ) = runCatching {
        innerTube.removeFromPlaylist(WEB_REMIX, "SE", videoId, setVideoId)
    }

    suspend fun libraryPodcastChannels(): Result<LibraryPage> {
        return runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = "FEmusic_library_non_music_audio_channels_list",
                        setLogin = true,
                    ).body<BrowseResponse>()

            val contentList =
                response.contents
                    ?.singleColumnBrowseResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
                    ?.contents ?: emptyList()

            val items =
                contentList.flatMap { content ->
                    when {
                        content.gridRenderer != null -> {
                            content.gridRenderer.items
                                .mapNotNull(GridRenderer.Item::musicTwoRowItemRenderer)
                                .mapNotNull { LibraryPage.fromMusicTwoRowItemRenderer(it) }
                        }

                        content.musicShelfRenderer != null -> {
                            content.musicShelfRenderer.contents
                                ?.mapNotNull(MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
                                ?.mapNotNull { LibraryPage.fromMusicResponsiveListItemRenderer(it) }
                                ?: emptyList()
                        }

                        content.musicCarouselShelfRenderer != null -> {
                            content.musicCarouselShelfRenderer.contents.mapNotNull { content ->
                                content.musicTwoRowItemRenderer?.let { renderer ->
                                    LibraryPage.fromMusicTwoRowItemRenderer(renderer)
                                } ?: content.musicMultiRowListItemRenderer?.let { renderer ->
                                    PodcastPage.fromMusicMultiRowListItemRenderer(renderer)
                                } ?: content.musicResponsiveListItemRenderer?.let { renderer ->
                                    LibraryPage.fromMusicResponsiveListItemRenderer(renderer)
                                }
                            }
                        }

                        else -> {
                            emptyList()
                        }
                    }
                }

            LibraryPage(
                items = items,
                continuation = null,
            )
        }.also { result ->
        }
    }

    suspend fun libraryPodcastEpisodes(): Result<LibraryPage> {
        return runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = "FEmusic_library_non_music_audio_list",
                        setLogin = true,
                    ).body<BrowseResponse>()

            val contents =
                response.contents
                    ?.singleColumnBrowseResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
                    ?.contents
                    ?.firstOrNull()

            val items =
                when {
                    contents?.gridRenderer != null -> {
                        contents.gridRenderer.items
                            .mapNotNull(GridRenderer.Item::musicTwoRowItemRenderer)
                            .mapNotNull { LibraryPage.fromMusicTwoRowItemRenderer(it) }
                    }

                    contents?.musicShelfRenderer != null -> {
                        contents.musicShelfRenderer.contents
                            ?.mapNotNull(MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
                            ?.mapNotNull { LibraryPage.fromMusicResponsiveListItemRenderer(it) }
                            ?: emptyList()
                    }

                    else -> {
                        emptyList()
                    }
                }

            LibraryPage(
                items = items,
                continuation = null,
            )
        }.also { result ->
        }
    }

    /**
     * Fetch saved podcast shows from library.
     * Uses FEmusic_library_non_music_audio_list and filters to only PodcastItem.
     */
    suspend fun savedPodcastShows(): Result<List<PodcastItem>> =
        runCatching {
            val libraryPage = libraryPodcastEpisodes().getOrThrow()
            libraryPage.items.filterIsInstance<PodcastItem>()
        }

    /**
     * Fetch "New Episodes" auto-playlist (VLRDPN).
     * Returns new episodes from saved/subscribed podcasts.
     */
    suspend fun newEpisodes(): Result<List<SongItem>> {
        return runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = "VLRDPN",
                        setLogin = true,
                    ).body<BrowseResponse>()

            val twoColumn = response.contents?.twoColumnBrowseResultsRenderer

            // RDPN may have content in either tabs (first tab) or secondaryContents
            val sections = mutableListOf<SectionListRenderer.Content>()

            // Check tabs path
            twoColumn?.tabs
                ?.firstOrNull()
                ?.tabRenderer
                ?.content
                ?.sectionListRenderer
                ?.contents
                ?.let { sections.addAll(it) }

            // Check secondaryContents path (original working path for RDPN)
            // Use TwoColumnBrowseResultsRenderer's inner SectionListRenderer types
            twoColumn?.secondaryContents?.sectionListRenderer?.contents?.let { secContents ->
                sections.addAll(secContents.map { secContent ->
                    SectionListRenderer.Content(
                        musicCarouselShelfRenderer = null,
                        musicShelfRenderer = secContent.musicShelfRenderer,
                        musicCardShelfRenderer = null,
                        musicPlaylistShelfRenderer = secContent.musicPlaylistShelfRenderer,
                        musicDescriptionShelfRenderer = null,
                        musicResponsiveHeaderRenderer = null,
                        musicEditablePlaylistDetailHeaderRenderer = null,
                        gridRenderer = null,
                        itemSectionRenderer = null,
                    )
                })
            }


            // Log all section types
            sections.forEachIndexed { idx, section ->
                section.musicCarouselShelfRenderer?.let { carousel ->
                    val carouselTitle = carousel.header?.musicCarouselShelfBasicHeaderRenderer?.title?.runs?.joinToString("") { it.text }
                }
                section.musicShelfRenderer?.let { shelf ->
                    // Log first item's subtitle fields
                    shelf.contents?.firstOrNull()?.musicMultiRowListItemRenderer?.let { r ->
                        val subtitleText = r.subtitle?.runs?.joinToString("") { it.text }
                        val secondSubtitleText = r.secondSubtitle?.runs?.joinToString("") { it.text }
                        val secondarySubtitleText = r.secondarySubtitle?.runs?.joinToString("") { it.text }
                        val subRuns = r.subtitle?.runs
                        val secRuns = r.secondSubtitle?.runs
                        val menuItems = r.menu?.menuRenderer?.items?.mapIndexed { idx, item ->
                            val navText = item.menuNavigationItemRenderer?.text?.runs?.joinToString("") { it.text }
                            val navId = item.menuNavigationItemRenderer?.navigationEndpoint?.browseEndpoint?.browseId
                            val navPageType = item.menuNavigationItemRenderer?.navigationEndpoint?.browseEndpoint
                                ?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType
                            val svcText = item.menuServiceItemRenderer?.text?.runs?.joinToString("") { it.text }
                            val toggleIcon = item.toggleMenuServiceItemRenderer?.defaultIcon?.iconType
                            "[$idx](navText='$navText' navId=$navId pageType=$navPageType svcText='$svcText' toggleIcon=$toggleIcon)"
                        }
                    }
                }
                section.musicPlaylistShelfRenderer?.let { ps ->
                }
            }

            // Check singleColumnBrowseResultsRenderer too
            val singleColumn = response.contents?.singleColumnBrowseResultsRenderer
            if (singleColumn?.tabs != null) {
                singleColumn.tabs.forEachIndexed { idx, tab ->
                    val tabTitle = tab.tabRenderer.title
                    val tabContent = tab.tabRenderer.content
                    val tabSectionList = tabContent?.sectionListRenderer
                    val tabSectionsSize = tabSectionList?.contents?.size ?: 0
                    tabSectionList?.contents?.forEachIndexed { sIdx, section ->
                        val shelf = section.musicShelfRenderer
                        val carousel = section.musicCarouselShelfRenderer
                        if (shelf != null) {
                        }
                        if (carousel != null) {
                            val carTitle = carousel.header?.musicCarouselShelfBasicHeaderRenderer?.title?.runs?.joinToString("") { it.text }
                        }
                    }
                }
            }

            // Extract episodes from all section types
            val episodesList = mutableListOf<SongItem>()

            // Helper to process musicShelf
            fun processShelf(shelf: MusicShelfRenderer, sectionPodcastName: String? = null) {
                val podcastName = sectionPodcastName ?: shelf.title?.runs?.joinToString("") { it.text }
                shelf.contents
                    ?.mapNotNull { it.musicMultiRowListItemRenderer }
                    ?.forEach { renderer ->
                        if (renderer.onTap?.watchEndpoint?.videoId == null) return@forEach
                        val title = renderer.title?.runs?.firstOrNull()?.text ?: return@forEach

                        val subtitleGroups = renderer.subtitle?.runs?.splitBySeparator()
                        val duration = subtitleGroups
                            ?.lastOrNull { group ->
                                group.firstOrNull()?.text?.parseTime() != null
                            }
                            ?.firstOrNull()
                            ?.text
                            ?.parseTime()

                        // Strategy 1: secondSubtitle / secondarySubtitle often has the podcast name
                        var artistName: String? = renderer.secondSubtitle?.runs?.joinToString("") { it.text }
                        if (artistName.isNullOrBlank()) {
                            artistName = renderer.secondarySubtitle?.runs?.joinToString("") { it.text }
                        }

                        // Strategy 2: Extract browseId from menu items with browse endpoints
                        var browseId: String? = null
                        val actionLabels = setOf("Save to playlist", "Share", "Remove from library",
                            "Add to library", "Don't recommend this episode", "Start radio",
                            "Go to podcast", "Go to artist", "Go to album")
                        renderer.menu?.menuRenderer?.items?.forEach { item ->
                            val text = item.menuNavigationItemRenderer?.text?.runs?.joinToString("") { it.text }
                            val navEp = item.menuNavigationItemRenderer?.navigationEndpoint?.browseEndpoint
                            if (navEp != null) {
                                if (browseId == null) browseId = navEp.browseId
                                if (text != null && text !in actionLabels && artistName == null) {
                                    artistName = text
                                }
                            }
                        }

                        // Strategy 3: Fallback to section header's podcast name
                        if (artistName.isNullOrBlank()) artistName = podcastName

                        val artists = if (!artistName.isNullOrBlank()) {
                            listOf(Artist(name = artistName, id = browseId))
                        } else emptyList()

                        episodesList.add(
                            SongItem(
                                id = renderer.onTap.watchEndpoint.videoId,
                                title = title,
                                artists = artists,
                                album = null,
                                duration = duration,
                                thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: "",
                                isEpisode = true,
                            )
                        )
                    }
            }

            // Process both sections and carousels
            sections.forEach { section ->
                // Process musicShelfRenderer
                section.musicShelfRenderer?.let { shelf ->
                    processShelf(shelf)
                }
                // Process musicCarouselShelfRenderer - each carousel is a podcast group
                section.musicCarouselShelfRenderer?.let { carousel ->
                    val carouselTitle = carousel.header?.musicCarouselShelfBasicHeaderRenderer?.title?.runs?.joinToString("") { it.text }
                    carousel.contents.forEach { carouselContent ->
                        carouselContent.musicMultiRowListItemRenderer?.let { renderer ->
                            if (renderer.onTap?.watchEndpoint?.videoId == null) return@let
                            val title = renderer.title?.runs?.firstOrNull()?.text ?: return@let

                            val subtitleGroups = renderer.subtitle?.runs?.splitBySeparator()
                            val duration = subtitleGroups
                                ?.lastOrNull { group ->
                                    group.firstOrNull()?.text?.parseTime() != null
                                }
                                ?.firstOrNull()
                                ?.text
                                ?.parseTime()

                            // Try secondSubtitle first, then carousel title
                            var artistName: String? = renderer.secondSubtitle?.runs?.joinToString("") { it.text }
                            if (artistName.isNullOrBlank()) {
                                artistName = renderer.secondarySubtitle?.runs?.joinToString("") { it.text }
                            }
                            if (artistName.isNullOrBlank()) artistName = carouselTitle

                            val artists = if (!artistName.isNullOrBlank()) {
                                listOf(Artist(name = artistName, id = null))
                            } else emptyList()

                            episodesList.add(
                                SongItem(
                                    id = renderer.onTap.watchEndpoint.videoId,
                                    title = title,
                                    artists = artists,
                                    album = null,
                                    duration = duration,
                                    thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: "",
                                    isEpisode = true,
                                )
                            )
                        }
                    }
                }
            }

            // Skip enrichment for now — episodes play without artist metadata

            episodesList
        }.also { result ->
        }
    }

    /**
     * Fetch the RDPN "New Episodes" playlist info (title + thumbnail).
     * Uses the same VLRDPN browse call as [newEpisodes] but parses the header instead.
     * Falls back to the first episode thumbnail if no header thumbnail is found.
     */
    suspend fun newEpisodesPlaylistInfo(): Result<PlaylistItem> =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = "VLRDPN",
                        setLogin = true,
                    ).body<BrowseResponse>()

            val thumbnail: String? =
                response.header
                    ?.musicImmersiveHeaderRenderer
                    ?.thumbnail
                    ?.musicThumbnailRenderer
                    ?.getThumbnailUrl()
                    ?: response.header
                        ?.musicVisualHeaderRenderer
                        ?.thumbnail
                        ?.musicThumbnailRenderer
                        ?.getThumbnailUrl()
                    ?: response.header
                        ?.musicDetailHeaderRenderer
                        ?.thumbnail
                        ?.croppedSquareThumbnailRenderer
                        ?.thumbnail
                        ?.thumbnails
                        ?.lastOrNull()
                        ?.url
                    ?: response.contents
                        ?.twoColumnBrowseResultsRenderer
                        ?.secondaryContents
                        ?.sectionListRenderer
                        ?.contents
                        ?.firstOrNull()
                        ?.musicShelfRenderer
                        ?.contents
                        ?.firstOrNull()
                        ?.musicMultiRowListItemRenderer
                        ?.thumbnail
                        ?.musicThumbnailRenderer
                        ?.getThumbnailUrl()

            val title =
                response.header
                    ?.musicImmersiveHeaderRenderer
                    ?.title
                    ?.runs
                    ?.joinToString("") { it.text }
                    ?: response.header
                        ?.musicVisualHeaderRenderer
                        ?.title
                        ?.runs
                        ?.joinToString("") { it.text }
                    ?: "New Episodes"

            PlaylistItem(
                id = "RDPN",
                title = title,
                author = null,
                songCountText = null,
                thumbnail = thumbnail,
                playEndpoint = null,
                shuffleEndpoint = null,
                radioEndpoint = null,
            )
        }

    suspend fun episodesForLater(): Result<List<SongItem>> =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = "VLSE",
                        setLogin = true,
                    ).body<BrowseResponse>()

            val contents =
                response.contents
                    ?.twoColumnBrowseResultsRenderer
                    ?.secondaryContents
                    ?.sectionListRenderer
                    ?.contents
                    ?.firstOrNull()

            val shelfContents =
                contents?.musicPlaylistShelfRenderer?.contents
                    ?: contents?.musicShelfRenderer?.contents

            shelfContents
                ?.mapNotNull { it.musicResponsiveListItemRenderer }
                ?.mapNotNull { renderer ->
                    val videoId = renderer.videoId ?: return@mapNotNull null
                    val setVideoId = renderer.playlistSetVideoId
                    val title =
                        renderer.flexColumns
                            .firstOrNull()
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text
                            ?.runs
                            ?.firstOrNull()
                            ?.text
                            ?: return@mapNotNull null

                    val subtitleGroups =
                        renderer.flexColumns
                            .getOrNull(1)
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text
                            ?.runs
                            ?.splitBySeparator()

                    val artistRun =
                        subtitleGroups
                            ?.firstOrNull { group ->
                                group.firstOrNull()?.navigationEndpoint?.browseEndpoint != null
                            }?.firstOrNull()
                            ?: subtitleGroups?.firstOrNull()?.firstOrNull()

                    val duration =
                        subtitleGroups
                            ?.drop(1)
                            ?.firstOrNull { group ->
                                group.firstOrNull()?.text?.parseTime() != null
                            }?.firstOrNull()
                            ?.text
                            ?.parseTime()
                            ?: renderer.fixedColumns
                                ?.firstOrNull()
                                ?.musicResponsiveListItemFlexColumnRenderer
                                ?.text
                                ?.runs
                                ?.firstOrNull()
                                ?.text
                                ?.parseTime()

                    SongItem(
                        id = videoId,
                        title = title,
                        artists =
                            artistRun?.let {
                                listOf(
                                    Artist(
                                        name = it.text,
                                        id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                    ),
                                )
                            } ?: emptyList(),
                        album = null,
                        duration = duration,
                        thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: "",
                        setVideoId = setVideoId,
                        isEpisode = true,
                    )
                } ?: emptyList()
        }

    suspend fun getChannelId(browseId: String): String {
        artist(browseId).onSuccess {
            return it.artist.channelId ?: ""
        }
        return ""
    }

    suspend fun addToPlaylist(
        playlistId: String,
        videoId: String,
    ) = runCatching {
        val result =
            withPlaylistMutationAuthRecovery {
                innerTube
                    .addToPlaylist(WEB_REMIX, playlistId, videoId)
                    .body<AddItemYouTubePlaylistResponse>()
            }.playlistEditResults
                .firstOrNull { result ->
                    result.playlistEditVideoAddedResultData.videoId == videoId
                }?.playlistEditVideoAddedResultData
        require(result?.setVideoId?.isNotBlank() == true) {
            "Playlist edit did not confirm added video $videoId"
        }
        result.setVideoId
    }

    suspend fun addSongsToPlaylist(
        playlistId: String,
        videoIds: List<String>,
        batchSize: Int = DEFAULT_PLAYLIST_EDIT_BATCH_SIZE,
        onProgress: (completedSongs: Int, totalSongs: Int) -> Unit = { _, _ -> },
    ): Result<List<String?>> =
        runCatching {
            require(batchSize > 0) { "batchSize must be positive" }
            if (videoIds.isEmpty()) return@runCatching emptyList()

            val setVideoIds = ArrayList<String?>(videoIds.size)
            val totalSongs = videoIds.size
            var completedSongs = 0
            onProgress(completedSongs, totalSongs)

            videoIds.chunked(batchSize).forEach { batch ->
                val batchResponse =
                    runCatching {
                        withPlaylistMutationAuthRecovery {
                            innerTube
                                .addSongsToPlaylist(WEB_REMIX, playlistId, batch)
                                .body<AddItemYouTubePlaylistResponse>()
                        }
                    }

                if (batchResponse.isSuccess) {
                    val resultByVideoId =
                        batchResponse
                            .getOrThrow()
                            .playlistEditResults
                            .map { it.playlistEditVideoAddedResultData }
                            .filter { result -> result.setVideoId.isNotBlank() }
                            .associateBy { it.videoId }

                    batch.forEach { videoId ->
                        val setVideoId =
                            resultByVideoId[videoId]?.setVideoId
                                ?: throw IllegalStateException("Playlist edit did not confirm added video $videoId")
                        setVideoIds += setVideoId
                        completedSongs += 1
                    }
                } else if (batch.size == 1) {
                    throw batchResponse.exceptionOrNull() ?: IllegalStateException("Playlist edit failed")
                } else {
                    batch.forEach { videoId ->
                        val setVideoId = addToPlaylist(playlistId, videoId).getOrThrow()
                        setVideoIds += setVideoId
                        completedSongs += 1
                    }
                }
                onProgress(completedSongs, totalSongs)
            }

            setVideoIds
        }

    suspend fun addPlaylistToPlaylist(
        playlistId: String,
        addPlaylistId: String,
    ) = runCatching {
        withPlaylistMutationAuthRecovery {
            innerTube.addPlaylistToPlaylist(WEB_REMIX, playlistId, addPlaylistId)
        }
    }

    suspend fun playlistEntrySetVideoIds(
        playlistId: String,
        videoId: String,
    ) = runCatching {
        val setVideoIds = mutableListOf<String>()

        fun collectSetVideoIds(songs: List<SongItem>) {
            setVideoIds +=
                songs
                    .asSequence()
                    .filter { song -> song.id == videoId }
                    .mapNotNull(SongItem::setVideoId)
                    .toList()
        }

        val playlistPage = playlist(playlistId).getOrThrow()
        collectSetVideoIds(playlistPage.songs)

        var continuation =
            playlistPage.songsContinuation?.takeUnless(String::isBlank)
                ?: playlistPage.continuation?.takeUnless(String::isBlank)
        while (continuation != null) {
            val continuationPage = playlistContinuation(continuation, playlistId).getOrThrow()
            collectSetVideoIds(continuationPage.songs)
            continuation = continuationPage.continuation?.takeUnless(String::isBlank)
        }

        setVideoIds.distinct()
    }

    suspend fun removeFromPlaylist(
        playlistId: String,
        videoId: String,
        setVideoId: String,
    ) = runCatching {
        withPlaylistMutationAuthRecovery {
            innerTube.removeFromPlaylist(WEB_REMIX, playlistId, videoId, setVideoId)
        }
    }

    suspend fun moveSongPlaylist(
        playlistId: String,
        setVideoId: String,
        successorSetVideoId: String?,
    ) = runCatching {
        withPlaylistMutationAuthRecovery {
            innerTube.moveSongPlaylist(WEB_REMIX, playlistId, setVideoId, successorSetVideoId)
        }
    }

    suspend fun createPlaylist(
        title: String,
        videoIds: List<String> = emptyList(),
    ) = runCatching {
        withPlaylistMutationAuthRecovery {
            innerTube.createPlaylist(WEB_REMIX, title, videoIds).body<CreatePlaylistResponse>()
        }.playlistId
    }

    suspend fun renamePlaylist(
        playlistId: String,
        name: String,
    ) = runCatching {
        withPlaylistMutationAuthRecovery {
            innerTube.renamePlaylist(WEB_REMIX, playlistId, name)
        }
    }

    suspend fun deletePlaylist(playlistId: String) =
        runCatching {
            withPlaylistMutationAuthRecovery {
                innerTube.deletePlaylist(WEB_REMIX, playlistId)
            }
        }

    suspend fun player(
        videoId: String,
        playlistId: String? = null,
        client: YouTubeClient,
        signatureTimestamp: Int? = null,
        poToken: String? = null,
        setLogin: Boolean = true,
        authState: PlaybackAuthState = currentPlaybackAuthState(),
    ): Result<PlayerResponse> =
        runCatching {
            val resolvedPoToken = resolvePlayerPoToken(client, poToken, authState)
            innerTube
                .player(
                    client = client,
                    videoId = videoId,
                    playlistId = playlistId,
                    signatureTimestamp = signatureTimestamp,
                    poToken = resolvedPoToken,
                    setLogin = setLogin,
                    authState = authState,
                ).body<PlayerResponse>()
        }

    suspend fun registerPlayback(
        playlistId: String? = null,
        playbackTracking: String,
        authState: PlaybackAuthState = currentPlaybackAuthState(),
    ) = runCatching {
        val cpn =
            (1..16)
                .map {
                    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"[
                        Random.Default.nextInt(
                            0,
                            64,
                        ),
                    ]
                }.joinToString("")

        innerTube.registerPlayback(
            url = playbackTracking,
            playlistId = playlistId,
            cpn = cpn,
            authState = authState,
        )
    }

    suspend fun next(
        endpoint: WatchEndpoint,
        continuation: String? = null,
        followAutomixPreview: Boolean = true,
    ): Result<NextResult> =
        runCatching {
            val response =
                innerTube
                    .next(
                        WEB_REMIX,
                        endpoint.videoId,
                        endpoint.playlistId,
                        endpoint.playlistSetVideoId,
                        endpoint.index,
                        endpoint.params,
                        continuation,
                    ).body<NextResponse>()
            val playlistPanelRenderer =
                response.continuationContents?.playlistPanelContinuation
                    ?: response.contents.singleColumnMusicWatchNextResultsRenderer
                        ?.tabbedRenderer
                        ?.watchNextTabbedResultsRenderer
                        ?.tabs
                        ?.get(0)
                        ?.tabRenderer
                        ?.content
                        ?.musicQueueRenderer
                        ?.content
                        ?.playlistPanelRenderer
                    ?: error("playlistPanelRenderer is null")
            val title =
                response.contents.singleColumnMusicWatchNextResultsRenderer
                    ?.tabbedRenderer
                    ?.watchNextTabbedResultsRenderer
                    ?.tabs
                    ?.get(0)
                    ?.tabRenderer
                    ?.content
                    ?.musicQueueRenderer
                    ?.header
                    ?.musicQueueHeaderRenderer
                    ?.subtitle
                    ?.runs
                    ?.firstOrNull()
                    ?.text
            val items =
                playlistPanelRenderer.contents.mapNotNull { content ->
                    content.playlistPanelVideoRenderer
                        ?.let(NextPage::fromPlaylistPanelVideoRenderer)
                        ?.let { it to content.playlistPanelVideoRenderer.selected }
                }
            val songs = items.map { it.first }
            val currentIndex = items.indexOfFirst { it.second }.takeIf { it != -1 }

            if (followAutomixPreview) {
                // Keep automix opt-in so ordered playlist queues can page through their own continuation first.
                playlistPanelRenderer.contents
                    .lastOrNull()
                    ?.automixPreviewVideoRenderer
                    ?.content
                    ?.automixPlaylistVideoRenderer
                    ?.navigationEndpoint
                    ?.watchPlaylistEndpoint
                    ?.let { watchPlaylistEndpoint ->
                        return@runCatching next(watchPlaylistEndpoint).getOrThrow().let { result ->
                            result.copy(
                                title = title,
                                items = songs + result.items,
                                lyricsEndpoint =
                                    response.contents.singleColumnMusicWatchNextResultsRenderer
                                        ?.tabbedRenderer
                                        ?.watchNextTabbedResultsRenderer
                                        ?.tabs
                                        ?.getOrNull(
                                            1,
                                        )?.tabRenderer
                                        ?.endpoint
                                        ?.browseEndpoint,
                                relatedEndpoint =
                                    response.contents.singleColumnMusicWatchNextResultsRenderer
                                        ?.tabbedRenderer
                                        ?.watchNextTabbedResultsRenderer
                                        ?.tabs
                                        ?.getOrNull(
                                            2,
                                        )?.tabRenderer
                                        ?.endpoint
                                        ?.browseEndpoint,
                                currentIndex = currentIndex,
                                endpoint = watchPlaylistEndpoint,
                            )
                        }
                    }
            }
            NextResult(
                title = title,
                items = songs,
                currentIndex = currentIndex,
                lyricsEndpoint =
                    response.contents.singleColumnMusicWatchNextResultsRenderer
                        ?.tabbedRenderer
                        ?.watchNextTabbedResultsRenderer
                        ?.tabs
                        ?.getOrNull(
                            1,
                        )?.tabRenderer
                        ?.endpoint
                        ?.browseEndpoint,
                relatedEndpoint =
                    response.contents.singleColumnMusicWatchNextResultsRenderer
                        ?.tabbedRenderer
                        ?.watchNextTabbedResultsRenderer
                        ?.tabs
                        ?.getOrNull(
                            2,
                        )?.tabRenderer
                        ?.endpoint
                        ?.browseEndpoint,
                continuation = playlistPanelRenderer.continuations?.getContinuation(),
                endpoint = endpoint,
            )
        }

    suspend fun lyrics(endpoint: BrowseEndpoint): Result<String?> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, endpoint.browseId, endpoint.params).body<BrowseResponse>()
            response.contents
                ?.sectionListRenderer
                ?.contents
                ?.firstOrNull()
                ?.musicDescriptionShelfRenderer
                ?.description
                ?.runs
                ?.firstOrNull()
                ?.text
        }

    suspend fun related(endpoint: BrowseEndpoint): Result<RelatedPage> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, endpoint.browseId).body<BrowseResponse>()
            val songs = mutableListOf<SongItem>()
            val albums = mutableListOf<AlbumItem>()
            val artists = mutableListOf<ArtistItem>()
            val playlists = mutableListOf<PlaylistItem>()
            response.contents?.sectionListRenderer?.contents?.forEach { sectionContent ->
                sectionContent.musicCarouselShelfRenderer?.contents?.forEach { content ->
                    when (
                        val item =
                            content.musicResponsiveListItemRenderer?.let(RelatedPage.Companion::fromMusicResponsiveListItemRenderer)
                                ?: content.musicTwoRowItemRenderer?.let(RelatedPage.Companion::fromMusicTwoRowItemRenderer)
                    ) {
                        is SongItem -> {
                            if (content.musicResponsiveListItemRenderer
                                    ?.overlay
                                    ?.musicItemThumbnailOverlayRenderer
                                    ?.content
                                    ?.musicPlayButtonRenderer
                                    ?.playNavigationEndpoint
                                    ?.watchEndpoint
                                    ?.watchEndpointMusicSupportedConfigs
                                    ?.watchEndpointMusicConfig
                                    ?.musicVideoType == MUSIC_VIDEO_TYPE_ATV
                            ) {
                                songs.add(item)
                            }
                        }

                        is AlbumItem -> {
                            albums.add(item)
                        }

                        is ArtistItem -> {
                            artists.add(item)
                        }

                        is PlaylistItem -> {
                            playlists.add(item)
                        }

                        is PodcastItem, is EpisodeItem -> {}

                        null -> {}
                    }
                }
            }
            RelatedPage(songs, albums, artists, playlists)
        }

    suspend fun queue(
        videoIds: List<String>? = null,
        playlistId: String? = null,
    ): Result<List<SongItem>> =
        runCatching {
            if (videoIds != null) {
                assert(videoIds.size <= MAX_GET_QUEUE_SIZE) // Max video limit
            }
            innerTube
                .getQueue(WEB_REMIX, videoIds, playlistId)
                .body<GetQueueResponse>()
                .queueDatas
                .mapNotNull {
                    it.content.playlistPanelVideoRenderer?.let { renderer ->
                        NextPage.fromPlaylistPanelVideoRenderer(renderer)
                    }
                }
        }

    suspend fun transcript(videoId: String): Result<String> =
        runCatching {
            val response = innerTube.getTranscript(WEB, videoId).body<GetTranscriptResponse>()
            response.actions
                ?.firstOrNull()
                ?.updateEngagementPanelAction
                ?.content
                ?.transcriptRenderer
                ?.body
                ?.transcriptBodyRenderer
                ?.cueGroups
                ?.joinToString(
                    separator = "\n",
                ) { group ->
                    val time =
                        group.transcriptCueGroupRenderer.cues[0]
                            .transcriptCueRenderer.startOffsetMs
                    val text =
                        group.transcriptCueGroupRenderer.cues[0]
                            .transcriptCueRenderer.cue.simpleText
                            .trim('♪')
                            .trim(' ')
                    "[%02d:%02d.%03d]$text".format(time / 60000, (time / 1000) % 60, time % 1000)
                }!!
        }

    suspend fun visitorData(): Result<String> =
        runCatching {
            Json
                .parseToJsonElement(innerTube.getSwJsData().bodyAsText().substring(5))
                .jsonArray[0]
                .jsonArray[2]
                .jsonArray
                .first {
                    (it as? JsonPrimitive)?.contentOrNull?.let { candidate ->
                        VISITOR_DATA_REGEX.containsMatchIn(candidate)
                    } ?: false
                }.jsonPrimitive.content
        }

    suspend fun accountInfo(): Result<AccountInfo> =
        runCatching {
            val response = innerTube.accountMenu(WEB_REMIX).body<AccountMenuResponse>()
            val accountInfo =
                response.actions
                    .firstOrNull()
                    ?.openPopupAction
                    ?.popup
                    ?.multiPageMenuRenderer
                    ?.header
                    ?.activeAccountHeaderRenderer
                    ?.toAccountInfo()
            accountInfo ?: throw IllegalStateException("Failed to get account info - user may not be logged in")
        }

    suspend fun accountChannels(): Result<List<AccountChannel>> =
        runCatching {
            val response =
                Json.parseToJsonElement(
                    innerTube.accountChannels(accountSwitcherClient).bodyAsText(),
                )

            response
                .objectsNamed("accountItemRenderer")
                .mapNotNull(::parseAccountChannel)
                .sortedByDescending(AccountChannel::isSelected)
                .distinctBy(AccountChannel::dataSyncId)
                .toList()
        }

    suspend fun accountDataSyncId(): Result<String> =
        runCatching {
            val response =
                Json.parseToJsonElement(
                    innerTube.accountChannels(accountSwitcherClient).bodyAsText(),
                )

            response.findMainAppWebDataSyncId()
                ?: response
                    .objectsNamed("accountItemRenderer")
                    .mapNotNull { renderer ->
                        val isDisabled = renderer.booleanValue("isDisabled") ?: false
                        val hasChannel = renderer.booleanValue("hasChannel") ?: true
                        if (isDisabled || !hasChannel) return@mapNotNull null

                        renderer.parseAccountChannelDataSyncId()?.let { dataSyncId ->
                            dataSyncId to (renderer.booleanValue("isSelected") ?: false)
                        }
                    }.sortedByDescending { (_, isSelected) -> isSelected }
                    .firstOrNull()
                    ?.first
                ?: throw IllegalStateException("Failed to get YouTube DataSyncId")
        }

    private fun parseAccountChannel(renderer: JsonObject): AccountChannel? {
        val isDisabled = renderer.booleanValue("isDisabled") ?: false
        val hasChannel = renderer.booleanValue("hasChannel") ?: true
        if (isDisabled || !hasChannel) return null

        val dataSyncId = renderer.parseAccountChannelDataSyncId() ?: return null

        val name = renderer["accountName"].textValue() ?: return null
        val byline = renderer["accountByline"].textValue()
        val channelHandle = renderer["channelHandle"].textValue()
        val thumbnailUrl = renderer["accountPhoto"].thumbnailUrl()

        return AccountChannel(
            name = name,
            byline = byline,
            channelHandle = channelHandle,
            thumbnailUrl = thumbnailUrl,
            dataSyncId = dataSyncId,
            isSelected = renderer.booleanValue("isSelected") ?: false,
        )
    }

    private fun JsonObject.parseAccountChannelDataSyncId(): String? =
        this["serviceEndpoint"]
            ?.findDelegationValue()
            ?.normalizeAccountChannelDataSyncId()

    private fun JsonElement.findMainAppWebDataSyncId(): String? =
        (this as? JsonObject)
            ?.get("responseContext")
            ?.jsonObjectOrNull()
            ?.get("mainAppWebResponseContext")
            ?.jsonObjectOrNull()
            ?.get("dataSyncId")
            ?.jsonPrimitiveOrNull()
            ?.contentOrNull
            ?.normalizeAccountChannelDataSyncId()

    private fun JsonElement.objectsNamed(name: String): Sequence<JsonObject> =
        sequence {
            when (val element = this@objectsNamed) {
                is JsonObject -> {
                    element[name]?.jsonObjectOrNull()?.let { yield(it) }
                    element.values.forEach { yieldAll(it.objectsNamed(name)) }
                }

                is kotlinx.serialization.json.JsonArray -> {
                    element.forEach { yieldAll(it.objectsNamed(name)) }
                }

                else -> {}
            }
        }

    private fun JsonElement?.textValue(): String? {
        val obj = this as? JsonObject ?: return null
        obj["simpleText"]?.jsonPrimitiveOrNull()?.contentOrNull?.let { return it.trim().takeIf(String::isNotBlank) }
        return obj["runs"]
            ?.jsonArrayOrNull()
            ?.joinToString(separator = "") { run ->
                run
                    .jsonObjectOrNull()
                    ?.get("text")
                    ?.jsonPrimitiveOrNull()
                    ?.contentOrNull
                    .orEmpty()
            }?.trim()
            ?.takeIf(String::isNotBlank)
    }

    private fun JsonElement?.thumbnailUrl(): String? {
        val thumbnails =
            (this as? JsonObject)
                ?.get("thumbnails")
                ?.jsonArrayOrNull()
                ?: return null
        return thumbnails
            .asSequence()
            .mapNotNull { thumbnail ->
                thumbnail
                    .jsonObjectOrNull()
                    ?.get("url")
                    ?.jsonPrimitiveOrNull()
                    ?.contentOrNull
            }.lastOrNull()
            ?.let { url -> if (url.startsWith("//")) "https:$url" else url }
    }

    private fun JsonElement.findDelegationValue(): String? {
        val directKeys =
            setOf(
                "onBehalfOfUser",
                "obfuscatedSelectedGaiaId",
                "obfuscatedGaiaId",
                "accountId",
                "delegatedSessionId",
            )
        val fallbackKeys =
            setOf(
                "selectedSerializedDelegationContext",
                "serializedDelegationContext",
            )
        return findStringValue(directKeys) ?: findStringValue(fallbackKeys)
    }

    private fun JsonElement.findStringValue(keys: Set<String>): String? =
        when (this) {
            is JsonObject -> {
                keys.firstNotNullOfOrNull { key ->
                    this[key]
                        ?.jsonPrimitiveOrNull()
                        ?.contentOrNull
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                } ?: values.firstNotNullOfOrNull { it.findStringValue(keys) }
            }

            is kotlinx.serialization.json.JsonArray -> {
                firstNotNullOfOrNull { it.findStringValue(keys) }
            }

            else -> {
                null
            }
        }

    private fun JsonObject.booleanValue(key: String): Boolean? = this[key]?.jsonPrimitiveOrNull()?.contentOrNull?.toBooleanStrictOrNull()

    private fun JsonElement?.jsonObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonElement?.jsonArrayOrNull(): kotlinx.serialization.json.JsonArray? = this as? kotlinx.serialization.json.JsonArray

    private fun JsonElement?.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

    private fun String.normalizeAccountChannelDataSyncId(): String? {
        val normalized =
            trim()
                .takeIf(String::isNotBlank)
                ?.let { value ->
                    value
                        .takeIf { !it.contains("||") }
                        ?: value.takeIf { it.endsWith("||") }?.substringBefore("||")
                        ?: value.substringAfter("||")
                }?.trim()
                ?.takeIf(String::isNotBlank)
        return normalized
    }

    suspend fun getMediaInfo(videoId: String): Result<MediaInfo> =
        runCatching {
            return innerTube.getMediaInfo(videoId)
        }

    @JvmInline
    value class SearchFilter(
        val value: String,
    ) {
        companion object {
            val FILTER_SONG = SearchFilter("EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D")
            val FILTER_VIDEO = SearchFilter("EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_ALBUM = SearchFilter("EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_ARTIST = SearchFilter("EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_FEATURED_PLAYLIST = SearchFilter("EgeKAQQoADgBagwQDhAKEAMQBRAJEAQ%3D")
            val FILTER_COMMUNITY_PLAYLIST = SearchFilter("EgeKAQQoAEABagoQAxAEEAoQCRAF")
        }
    }

    @JvmInline
    value class LibraryFilter(
        val value: String,
    ) {
        companion object {
            val FILTER_RECENT_ACTIVITY = LibraryFilter("4qmFsgIrEhdGRW11c2ljX2xpYnJhcnlfbGFuZGluZxoQZ2dNR0tnUUlCaEFCb0FZQg%3D%3D")
            val FILTER_RECENTLY_PLAYED = LibraryFilter("4qmFsgIrEhdGRW11c2ljX2xpYnJhcnlfbGFuZGluZxoQZ2dNR0tnUUlCUkFCb0FZQg%3D%3D")
            val FILTER_PLAYLISTS_ALPHABETICAL = LibraryFilter("4qmFsgIrEhdGRW11c2ljX2xpa2VkX3BsYXlsaXN0cxoQZ2dNR0tnUUlBUkFBb0FZQg%3D%3D")
            val FILTER_PLAYLISTS_RECENTLY_SAVED = LibraryFilter("4qmFsgIrEhdGRW11c2ljX2xpa2VkX3BsYXlsaXN0cxoQZ2dNR0tnUUlBQkFCb0FZQg%3D%3D")
        }
    }

    const val MAX_GET_QUEUE_SIZE = 1000
    private const val DEFAULT_PLAYLIST_EDIT_BATCH_SIZE = 50

    private val VISITOR_DATA_REGEX = Regex("^Cg[t|s]")
}

private fun SectionListRenderer.Content.libraryItems(): List<YTItem> =
    buildList {
        addAll(
            gridRenderer
                ?.items
                .orEmpty()
                .mapNotNull(GridRenderer.Item::musicTwoRowItemRenderer)
                .mapNotNull { LibraryPage.fromMusicTwoRowItemRenderer(it) },
        )
        addAll(
            musicCarouselShelfRenderer
                ?.contents
                .orEmpty()
                .mapNotNull(MusicCarouselShelfRenderer.Content::musicTwoRowItemRenderer)
                .mapNotNull { LibraryPage.fromMusicTwoRowItemRenderer(it) },
        )
        addAll(
            musicShelfRenderer
                ?.contents
                .orEmpty()
                .mapNotNull(MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
                .mapNotNull { LibraryPage.fromMusicResponsiveListItemRenderer(it) },
        )
        addAll(
            musicPlaylistShelfRenderer
                ?.contents
                .orEmpty()
                .mapNotNull(MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
                .mapNotNull { LibraryPage.fromMusicResponsiveListItemRenderer(it) },
        )
        itemSectionRenderer?.contents.orEmpty().forEach { content ->
            content.musicResponsiveListItemRenderer
                ?.let { LibraryPage.fromMusicResponsiveListItemRenderer(it) }
                ?.let(::add)
            addAll(
                content.musicShelfRenderer
                    ?.contents
                    .orEmpty()
                    .mapNotNull(MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
                    .mapNotNull { LibraryPage.fromMusicResponsiveListItemRenderer(it) },
            )
            addAll(
                content.gridRenderer
                    ?.items
                    .orEmpty()
                    .mapNotNull(GridRenderer.Item::musicTwoRowItemRenderer)
                    .mapNotNull { LibraryPage.fromMusicTwoRowItemRenderer(it) },
            )
        }
    }

private fun SectionListRenderer.Content.libraryContinuation(): String? =
    gridRenderer?.continuations?.getContinuation()
        ?: musicShelfRenderer?.contents.orEmpty().getContinuation()
        ?: musicShelfRenderer?.continuations?.getContinuation()
        ?: musicPlaylistShelfRenderer?.contents.orEmpty().getContinuation()
        ?: musicPlaylistShelfRenderer?.continuations?.getContinuation()
        ?: itemSectionRenderer?.contents.orEmpty().firstNotNullOfOrNull { content ->
            content.musicShelfRenderer
                ?.contents
                .orEmpty()
                .getContinuation()
                ?: content.musicShelfRenderer?.continuations?.getContinuation()
                ?: content.gridRenderer?.continuations?.getContinuation()
        }

private fun SectionListRenderer.Content.playlistSongContents(): List<MusicShelfRenderer.Content> =
    buildList {
        addAll(musicPlaylistShelfRenderer?.contents.orEmpty())
        addAll(musicShelfRenderer?.contents.orEmpty())
        itemSectionRenderer?.contents.orEmpty().forEach { content ->
            content.musicResponsiveListItemRenderer?.let { renderer ->
                add(
                    MusicShelfRenderer.Content(
                        musicResponsiveListItemRenderer = renderer,
                        continuationItemRenderer = null,
                    ),
                )
            }
            addAll(content.musicShelfRenderer?.contents.orEmpty())
        }
    }

private fun SectionListRenderer.Content.playlistSongContinuation(): String? =
    musicPlaylistShelfRenderer?.let { shelf ->
        shelf.contents.getContinuation() ?: shelf.continuations?.getContinuation()
    } ?: musicShelfRenderer?.let { shelf ->
        shelf.contents.orEmpty().getContinuation() ?: shelf.continuations?.getContinuation()
    } ?: itemSectionRenderer?.contents.orEmpty().firstNotNullOfOrNull { content ->
        content.musicShelfRenderer?.let { shelf ->
            shelf.contents.orEmpty().getContinuation() ?: shelf.continuations?.getContinuation()
        }
    }

internal fun playlistContinuationPageFromResponse(
    response: BrowseResponse,
    playlistId: String? = null,
): PlaylistContinuationPage {
    val appendedContents =
        response.onResponseReceivedActions
            ?.firstOrNull()
            ?.appendContinuationItemsAction
            ?.continuationItems
            .orEmpty()

    val candidates =
        listOf(
            PlaylistContinuationCandidate(
                contents =
                    buildList {
                        response.continuationContents
                            ?.sectionListContinuation
                            ?.contents
                            .orEmpty()
                            .forEach { sectionContent ->
                                addAll(sectionContent.playlistSongContents())
                            }
                        addAll(appendedContents)
                    },
                continuation =
                    response.continuationContents
                        ?.sectionListContinuation
                        ?.continuations
                        ?.getContinuation()
                        ?: appendedContents.getContinuation(),
            ),
            PlaylistContinuationCandidate(
                contents =
                    response.continuationContents
                        ?.musicPlaylistShelfContinuation
                        ?.contents
                        .orEmpty(),
                continuation =
                    response.continuationContents
                        ?.musicPlaylistShelfContinuation
                        ?.continuations
                        ?.getContinuation(),
            ),
            PlaylistContinuationCandidate(
                contents =
                    response.continuationContents
                        ?.musicShelfContinuation
                        ?.contents
                        .orEmpty(),
                continuation =
                    response.continuationContents
                        ?.musicShelfContinuation
                        ?.continuations
                        ?.getContinuation(),
            ),
            PlaylistContinuationCandidate(
                contents = appendedContents,
                continuation = appendedContents.getContinuation(),
            ),
        ).map { candidate ->
            candidate.copy(
                songs =
                    candidate.contents
                        .mapNotNull(MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
                        .mapNotNull { renderer ->
                            PlaylistPage.fromMusicResponsiveListItemRenderer(renderer, playlistId)
                        },
            )
        }

    val selectedCandidate =
        candidates.firstOrNull { it.songs.isNotEmpty() }
            ?: candidates.firstOrNull { it.contents.isNotEmpty() }

    return PlaylistContinuationPage(
        songs = selectedCandidate?.songs.orEmpty(),
        continuation = selectedCandidate?.continuation?.takeUnless(String::isBlank),
    )
}

private data class PlaylistContinuationCandidate(
    val contents: List<MusicShelfRenderer.Content>,
    val continuation: String?,
    val songs: List<SongItem> = emptyList(),
)
