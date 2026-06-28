/*
 * Hush — GPL-3.0
 * JioSaavn client adapted from Vivi Music (GPL-3.0), v6.0.3.
 * Uses the public Melo API wrapper (meloapi.vercel.app) around JioSaavn search/stream endpoints.
 */

package moe.rukamori.archivetune.jiosaavn

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SaavnDownloadUrl(
    @SerialName("quality") val quality: String = "",
    @SerialName("url") val url: String = "",
)

@Serializable
data class SaavnImage(
    @SerialName("quality") val quality: String = "",
    @SerialName("url") val url: String = "",
)

@Serializable
data class SaavnArtistItem(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
)

@Serializable
data class SaavnArtists(
    @SerialName("primary") val primary: List<SaavnArtistItem> = emptyList(),
    @SerialName("featured") val featured: List<SaavnArtistItem> = emptyList(),
    @SerialName("all") val all: List<SaavnArtistItem> = emptyList(),
)

@Serializable
data class SaavnAlbum(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
)

@Serializable
data class SaavnSong(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("duration") val duration: Int? = null,
    @SerialName("explicitContent") val explicitContent: Boolean = false,
    @SerialName("artists") val artists: SaavnArtists = SaavnArtists(),
    @SerialName("image") val image: List<SaavnImage> = emptyList(),
    @SerialName("downloadUrl") val downloadUrl: List<SaavnDownloadUrl> = emptyList(),
    @SerialName("album") val album: SaavnAlbum? = null,
)

@Serializable
private data class SaavnSearchSongsResult(
    @SerialName("total") val total: Int = 0,
    @SerialName("results") val results: List<SaavnSong> = emptyList(),
)

@Serializable
private data class SaavnSearchResponse(
    @SerialName("success") val success: Boolean = false,
    @SerialName("data") val data: SaavnSearchSongsResult? = null,
)

@Serializable
private data class SaavnSongResponse(
    @SerialName("success") val success: Boolean = false,
    @SerialName("data") val data: List<SaavnSong> = emptyList(),
)

object SaavnService {
    private const val TAG = "SaavnService"
    const val DEFAULT_BASE_URL = "https://meloapi.vercel.app/api/"

    private val json =
        Json {
            isLenient = true
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    private val client by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                requestTimeoutMillis = 4_000
                connectTimeoutMillis = 3_000
                socketTimeoutMillis = 4_000
            }
            defaultRequest {
                url(DEFAULT_BASE_URL)
                headers.append(HttpHeaders.Accept, "application/json")
                headers.append(HttpHeaders.UserAgent, "Hush/1.0")
            }
            expectSuccess = false
        }
    }

    suspend fun searchSongs(query: String, limit: Int = 8): Result<List<SaavnSong>> =
        runCatching {
            val response =
                client.get("search/songs") {
                    parameter("query", query)
                    parameter("limit", limit)
                }
            if (response.status != HttpStatusCode.OK) {
                error("Saavn search failed: HTTP ${response.status.value}")
            }
            val body = response.body<SaavnSearchResponse>()
            val results = body.data?.results.orEmpty()
            if (!body.success || results.isEmpty()) {
                error("No JioSaavn results for \"$query\"")
            }
            results
        }.onFailure {
            Log.w(TAG, "searchSongs failed for query=\"$query\"", it)
        }

    fun selectBestUrl(
        urls: List<SaavnDownloadUrl>,
        quality: String,
    ): String? {
        val filtered = urls.filter { it.url.isNotBlank() }
        if (filtered.isEmpty()) return null

        filtered.firstOrNull { it.quality.equals(quality, ignoreCase = true) }?.url?.let { return it }
        filtered.firstOrNull { it.quality.equals("320kbps", ignoreCase = true) }?.url?.let { return it }
        return filtered.lastOrNull()?.url
    }

    suspend fun getBestStreamUrl(
        saavnSongId: String,
        quality: String,
    ): String? =
        runCatching {
            val response = client.get("songs/$saavnSongId")
            if (response.status != HttpStatusCode.OK) return@runCatching null
            val body = response.body<SaavnSongResponse>()
            if (!body.success) return@runCatching null
            selectBestUrl(body.data.firstOrNull()?.downloadUrl.orEmpty(), quality)
        }.onFailure {
            Log.w(TAG, "getBestStreamUrl failed for id=$saavnSongId", it)
        }.getOrNull()

    suspend fun resolveStreamUrl(
        song: SaavnSong,
        quality: String,
    ): String? {
        selectBestUrl(song.downloadUrl, quality)?.let { return it }
        return getBestStreamUrl(song.id, quality)
    }
}
