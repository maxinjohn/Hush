package app.hush.music.spotiflac

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class SpotiFLACTrackResponse(
    @SerialName("url") val url: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("artist") val artist: String? = null,
    @SerialName("album") val album: String? = null,
    @SerialName("duration") val duration: Int? = null,
    @SerialName("cover") val cover: String? = null,
    @SerialName("format") val format: String? = null,
    @SerialName("bitrate") val bitrate: Int? = null,
    @SerialName("quality") val quality: String? = null,
    @SerialName("source") val source: String? = null,
    @SerialName("error") val error: String? = null,
)

@Serializable
data class SpotiFLACSearchResult(
    @SerialName("title") val title: String? = null,
    @SerialName("artist") val artist: String? = null,
    @SerialName("album") val album: String? = null,
    @SerialName("duration") val duration: Int? = null,
    @SerialName("id") val id: String? = null,
    @SerialName("cover") val cover: String? = null,
)

@Serializable
private data class RelaySearchGroup(
    @SerialName("provider") val provider: String? = null,
    @SerialName("items") val items: List<SpotiFLACSearchResult> = emptyList(),
)

@Serializable
private data class RelaySearchResponse(
    @SerialName("query") val query: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("results") val results: List<RelaySearchGroup> = emptyList(),
)

@Serializable
private data class RelayErrorResponse(
    @SerialName("error") val error: String? = null,
    @SerialName("code") val code: String? = null,
)

@Serializable
private data class ResolveRequest(
    @SerialName("url") val url: String,
    @SerialName("quality") val quality: String? = null,
)

@Serializable
private data class ResolveSongUrls(
    @SerialName("Spotify") val spotify: String? = null,
    @SerialName("Tidal") val tidal: String? = null,
    @SerialName("Deezer") val deezer: String? = null,
    @SerialName("Qobuz") val qobuz: String? = null,
    @SerialName("AppleMusic") val appleMusic: String? = null,
    @SerialName("AmazonMusic") val amazonMusic: String? = null,
    @SerialName("SoundCloud") val soundCloud: String? = null,
    @SerialName("Pandora") val pandora: String? = null,
)

@Serializable
private data class ResolveResponse(
    @SerialName("success") val success: Boolean = false,
    @SerialName("isrc") val isrc: String? = null,
    @SerialName("songUrls") val songUrls: ResolveSongUrls? = null,
    @SerialName("error") val error: String? = null,
)

@Serializable
private data class DlTrackResponse(
    @SerialName("url") val url: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("artist") val artist: String? = null,
    @SerialName("album") val album: String? = null,
    @SerialName("duration") val duration: Int? = null,
    @SerialName("cover") val cover: String? = null,
    @SerialName("format") val format: String? = null,
    @SerialName("bitrate") val bitrate: Int? = null,
    @SerialName("quality") val quality: String? = null,
    @SerialName("source") val source: String? = null,
    @SerialName("error") val error: String? = null,
)

@Singleton
class SpotiFLACClient @Inject constructor(
    private val httpClient: HttpClient,
    private val sessionManager: SpotiFLACSessionManager,
) {
    companion object {
        private const val TAG = "SpotiFLACClient"

        @Volatile
        private var instance: SpotiFLACClient? = null

        fun getInstance(): SpotiFLACClient {
            return instance
                ?: throw IllegalStateException("SpotiFLACClient not initialized")
        }
    }

    init {
        instance = this
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun isHexBlob(body: String): Boolean {
        val trimmed = body.trim()
        if (trimmed.length < 100) return false
        return trimmed.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    private suspend fun handleRelayError(body: String, statusCode: Int) {
        val errorResponse = try {
            json.decodeFromString<RelayErrorResponse>(body)
        } catch (_: Exception) {
            return
        }

        when {
            errorResponse.code == "SESSION_INVALID" || errorResponse.code == "REQUEST_AUTH_INVALID" -> {
                Timber.tag(TAG).w("Session invalid (code=${errorResponse.code}), clearing")
                sessionManager.clearSession()
            }
            errorResponse.code == "VERIFY_REQUIRED" -> {
                Timber.tag(TAG).w("Verification required")
            }
            statusCode == 429 -> {
                Timber.tag(TAG).w("Rate limited (429)")
            }
            statusCode >= 500 -> {
                Timber.tag(TAG).w("Server error: $statusCode")
            }
        }
    }

    suspend fun resolveTrack(
        spotifyTrackId: String,
        quality: String = "best",
        source: String? = null,
        relayUrl: String? = null,
    ): Result<SpotiFLACTrackResponse> = runCatching {
        Timber.tag(TAG).d("Resolving Spotify track: $spotifyTrackId (quality=$quality, source=$source)")

        val sourceParam = if (!source.isNullOrBlank()) "&source=$source" else ""
        val path = "/dl/spotify?id=$spotifyTrackId&q=$quality$sourceParam"
        val response = getSignedRequest(path, relayUrl)
        val body = response.bodyAsText()

        if (response.status.value == 403 || response.status.value == 401) {
            Timber.tag(TAG).w("Auth failed for track $spotifyTrackId: status=${response.status.value}, body=$body")
            handleRelayError(body, response.status.value)
            throw SpotiFLACException("Auth failed (${response.status.value}) for Spotify track $spotifyTrackId: ${body.take(200)}")
        }
        if (response.status.value == 429) {
            throw SpotiFLACException("Rate limited (429) for Spotify track $spotifyTrackId")
        }

        if (isHexBlob(body)) {
            Timber.tag(TAG).w("Relay returned encrypted response (no valid session)")
            throw SpotiFLACException("Relay requires authentication - encrypted response")
        }

        val dlResponse = try {
            json.decodeFromString<DlTrackResponse>(body)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to parse dl response: ${body.take(200)}")
            throw SpotiFLACException("Invalid relay response: ${body.take(100)}")
        }

        if (dlResponse.error != null) {
            throw SpotiFLACException("Relay error: ${dlResponse.error}")
        }
        if (dlResponse.url.isNullOrBlank()) {
            throw SpotiFLACException("No stream URL returned for track $spotifyTrackId")
        }

        SpotiFLACTrackResponse(
            url = dlResponse.url,
            title = dlResponse.title,
            artist = dlResponse.artist,
            album = dlResponse.album,
            duration = dlResponse.duration,
            cover = dlResponse.cover,
            format = dlResponse.format,
            bitrate = dlResponse.bitrate,
            quality = dlResponse.quality,
            source = dlResponse.source ?: source,
        )
    }

    suspend fun resolveByISRC(
        isrc: String,
        quality: String = "best",
        source: String? = null,
        relayUrl: String? = null,
    ): Result<SpotiFLACTrackResponse> = runCatching {
        Timber.tag(TAG).d("Resolving by ISRC: $isrc (quality=$quality, source=$source)")

        val sourceParam = if (!source.isNullOrBlank()) "&source=$source" else ""
        val path = "/dl/isrc?code=$isrc&q=$quality$sourceParam"
        val response = getSignedRequest(path, relayUrl)
        val body = response.bodyAsText()

        if (response.status.value == 403 || response.status.value == 401) {
            Timber.tag(TAG).w("Auth failed for ISRC $isrc: status=${response.status.value}, body=$body")
            handleRelayError(body, response.status.value)
            throw SpotiFLACException("Auth failed (${response.status.value}) for ISRC $isrc: ${body.take(200)}")
        }
        if (response.status.value == 429) {
            throw SpotiFLACException("Rate limited (429) for ISRC $isrc")
        }

        if (isHexBlob(body)) {
            throw SpotiFLACException("Relay returned encrypted response")
        }

        val dlResponse = try {
            json.decodeFromString<DlTrackResponse>(body)
        } catch (e: Exception) {
            throw SpotiFLACException("Invalid relay response for ISRC")
        }

        if (dlResponse.error != null) {
            throw SpotiFLACException("Relay error: ${dlResponse.error}")
        }
        if (dlResponse.url.isNullOrBlank()) {
            throw SpotiFLACException("No stream URL returned for ISRC $isrc")
        }

        SpotiFLACTrackResponse(
            url = dlResponse.url,
            title = dlResponse.title,
            artist = dlResponse.artist,
            album = dlResponse.album,
            duration = dlResponse.duration,
            cover = dlResponse.cover,
            format = dlResponse.format,
            bitrate = dlResponse.bitrate,
            quality = dlResponse.quality,
            source = dlResponse.source ?: source,
        )
    }

    suspend fun search(
        query: String,
        source: String? = null,
        relayUrl: String? = null,
    ): Result<List<SpotiFLACSearchResult>> = runCatching {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val sourceParam = if (!source.isNullOrBlank()) "&source=$source" else ""
        val path = "/search?q=$encodedQuery$sourceParam"
        Timber.tag(TAG).d("Searching: '$query' (source=$source)")

        val response = getSignedRequest(path, relayUrl)
        val body = response.bodyAsText()

        if (response.status.value == 403 || response.status.value == 401) {
            handleRelayError(body, response.status.value)
            throw SpotiFLACException("Auth failed (${response.status.value}) for search")
        }
        if (response.status.value == 429) {
            throw SpotiFLACException("Rate limited (429) for search")
        }

        if (isHexBlob(body)) {
            throw SpotiFLACException("Relay returned encrypted response")
        }

        parseSearchResults(body)
    }

    suspend fun testSource(source: String): Result<List<SpotiFLACSearchResult>> = runCatching {
        Timber.tag(TAG).d("Testing source: $source")

        val state = sessionManager.sessionState
        if (state == SessionState.NONE || state == SessionState.EXPIRED || state == SessionState.ERROR) {
            Timber.tag(TAG).d("No active session (state=$state), bootstrapping")
            val bootstrapResult = sessionManager.bootstrap()
            val postState = bootstrapResult.getOrElse { SessionState.ERROR }
            if (postState == SessionState.CHALLENGE_PENDING) {
                throw SpotiFLACException("Cloudflare verification required — open SpotiFLAC settings to authenticate")
            }
            if (postState != SessionState.ACTIVE) {
                throw SpotiFLACException("Could not establish session (state=$postState)")
            }
        }

        val encodedQuery = java.net.URLEncoder.encode("bohemian rhapsody", "UTF-8").replace("+", "%20")
        val path = "/search?q=$encodedQuery&source=$source"

        val response = getSignedRequest(path)
        val body = response.bodyAsText()

        Timber.tag(TAG).d("Test response for $source: status=${response.status.value}, body=${body.take(200)}")

        if (response.status.value == 403 || response.status.value == 401) {
            Timber.tag(TAG).w("Auth failed for $source: status=${response.status.value}, body=$body")
            handleRelayError(body, response.status.value)
            throw SpotiFLACException("Auth failed (${response.status.value}) for source $source: ${body.take(200)}")
        }
        if (response.status.value == 429) {
            throw SpotiFLACException("Rate limited (429) for source $source")
        }

        if (isHexBlob(body)) {
            throw SpotiFLACException("Relay returned encrypted response - session may be invalid")
        }

        val results = parseSearchResults(body)
        if (results.isEmpty()) {
            throw SpotiFLACException("No results returned for source $source")
        }
        results
    }

    fun extractSpotifyTrackId(spotifyUrl: String): String? {
        val regex = Regex("""open\.spotify\.com/track/([a-zA-Z0-9]+)""")
        return regex.find(spotifyUrl)?.groupValues?.get(1)
    }

    fun extractSpotifyTrackIdFromUri(spotifyUri: String): String? {
        if (!spotifyUri.startsWith("spotify:track:")) return null
        return spotifyUri.removePrefix("spotify:track:")
    }

    private fun parseSearchResults(body: String): List<SpotiFLACSearchResult> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()

        if (trimmed.startsWith("[")) {
            return try {
                json.decodeFromString<List<SpotiFLACSearchResult>>(body)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to parse as flat list")
                emptyList()
            }
        }

        if (trimmed.startsWith("{")) {
            return try {
                val response = json.decodeFromString<RelaySearchResponse>(body)
                response.results.flatMap { it.items }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to parse as wrapped response")
                try {
                    json.decodeFromString<List<SpotiFLACSearchResult>>(body)
                } catch (e2: Exception) {
                    emptyList()
                }
            }
        }

        return emptyList()
    }

    private suspend fun getSignedRequest(
        path: String,
        relayUrl: String? = null,
    ): io.ktor.client.statement.HttpResponse {
        val base = relayUrl?.trimEnd('/') ?: SpotiFLACSessionManager.BASE_URL
        val url = "$base$path"
        val fullPath = java.net.URI(url).path ?: path
        val signedHeaders = sessionManager.getSignedHeaders("GET", fullPath)

        Timber.tag(TAG).d("GET $url (signedPath=$fullPath, headers=${signedHeaders.size})")

        return httpClient.get(url) {
            header("User-Agent", "SpotiFLAC-Mobile/${SpotiFLACSessionManager.APP_VERSION}")
            header("Accept", "application/json")
            signedHeaders.forEach { (k, v) -> header(k, v) }
        }
    }

    private suspend fun postSignedRequest(
        path: String,
        body: String = "",
        relayUrl: String? = null,
    ): io.ktor.client.statement.HttpResponse {
        val base = relayUrl?.trimEnd('/') ?: SpotiFLACSessionManager.BASE_URL
        val url = "$base$path"
        val fullPath = java.net.URI(url).path ?: path
        val signedHeaders = sessionManager.getSignedHeaders("POST", fullPath, body)

        Timber.tag(TAG).d("POST $fullPath (signed=${signedHeaders.isNotEmpty()})")

        return httpClient.post(url) {
            header("User-Agent", "SpotiFLAC-Mobile/${SpotiFLACSessionManager.APP_VERSION}")
            header("Accept", "application/json")
            contentType(ContentType.Application.Json)
            setBody(body)
            signedHeaders.forEach { (k, v) -> header(k, v) }
        }
    }
}

class SpotiFLACException(message: String) : Exception(message)
