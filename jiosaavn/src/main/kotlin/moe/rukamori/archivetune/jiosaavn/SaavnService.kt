/*
 * Hush — GPL-3.0
 * JioSaavn client using the public jiosaavn.com API (search + encrypted stream URLs).
 */

package moe.rukamori.archivetune.jiosaavn

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
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
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

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
private data class NativeSearchResponse(
    @SerialName("total") val total: Int = 0,
    @SerialName("results") val results: List<NativeSongResult> = emptyList(),
)

@Serializable
private data class NativeSongResult(
    @SerialName("id") val id: String = "",
    @SerialName("type") val type: String = "",
    @SerialName("song") val song: String = "",
    @SerialName("album") val album: String = "",
    @SerialName("duration") val duration: String = "",
    @SerialName("primary_artists") val primaryArtists: String = "",
    @SerialName("encrypted_media_url") val encryptedMediaUrl: String = "",
    @SerialName("media_preview_url") val mediaPreviewUrl: String = "",
    @SerialName("320kbps") val supports320: String = "false",
    @SerialName("image") val image: String = "",
)

@Serializable
private data class NativeSongDetailsResponse(
    @SerialName("songs") val songs: List<NativeSongDetails> = emptyList(),
)

@Serializable
private data class NativeSongDetails(
    @SerialName("id") val id: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("duration") val duration: String = "",
    @SerialName("primary_artists") val primaryArtists: String = "",
    @SerialName("album") val album: String = "",
    @SerialName("image") val image: String = "",
    @SerialName("encrypted_media_url") val encryptedMediaUrl: String = "",
    @SerialName("media_preview_url") val mediaPreviewUrl: String = "",
    @SerialName("more_info") val moreInfo: NativeSongMoreInfo? = null,
    @SerialName("320kbps") val supports320: String = "false",
)

@Serializable
private data class NativeSongMoreInfo(
    @SerialName("encrypted_media_url") val encryptedMediaUrl: String = "",
    @SerialName("media_preview_url") val mediaPreviewUrl: String = "",
    @SerialName("duration") val duration: String = "",
)

object SaavnService {
    private const val TAG = "SaavnService"
    private const val API_BASE = "https://www.jiosaavn.com/api.php"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private val json =
        Json {
            isLenient = true
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                requestTimeoutMillis = 12_000
                connectTimeoutMillis = 8_000
                socketTimeoutMillis = 12_000
            }
            defaultRequest {
                headers.append(HttpHeaders.Accept, "application/json, text/plain, */*")
                headers.append(HttpHeaders.UserAgent, USER_AGENT)
                headers.append("Referer", "https://www.jiosaavn.com/")
                headers.append(HttpHeaders.Origin, "https://www.jiosaavn.com")
            }
            expectSuccess = false
        }
    }

    suspend fun searchSongs(
        query: String,
        limit: Int = 12,
    ): Result<List<SaavnSong>> =
        runCatching {
            val trimmedQuery = query.trim()
            if (trimmedQuery.isBlank()) error("Empty Saavn search query")

            val response =
                client.get(API_BASE) {
                    parameter("__call", "search.getResults")
                    parameter("_format", "json")
                    parameter("_marker", "0")
                    parameter("cc", "in")
                    parameter("q", trimmedQuery)
                    parameter("p", 1)
                    parameter("n", limit)
                }
            if (response.status != HttpStatusCode.OK) {
                error("Saavn search failed: HTTP ${response.status.value} for \"$trimmedQuery\"")
            }
            val rawBody = response.bodyAsText()
            val results = parseSearchResults(rawBody)
            if (results.isEmpty()) {
                error("No playable JioSaavn results for \"$trimmedQuery\" (body=${rawBody.take(120)})")
            }
            Log.i(TAG, "searchSongs query=\"$trimmedQuery\" -> ${results.size} playable results")
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
        filtered.firstOrNull { it.quality.equals("160kbps", ignoreCase = true) }?.url?.let { return it }
        filtered.firstOrNull { it.quality.equals("96kbps", ignoreCase = true) }?.url?.let { return it }
        return filtered.lastOrNull()?.url
    }

    suspend fun getBestStreamUrl(
        saavnSongId: String,
        quality: String,
    ): String? =
        runCatching {
            val response =
                client.get(API_BASE) {
                    parameter("__call", "song.getDetails")
                    parameter("pids", saavnSongId)
                    parameter("api_version", "4")
                    parameter("_format", "json")
                    parameter("ctx", "web6dot0")
                    parameter("_marker", "0")
                }
            if (response.status != HttpStatusCode.OK) return@runCatching null
            val body = response.body<NativeSongDetailsResponse>()
            val song = body.songs.firstOrNull() ?: return@runCatching null
            buildDownloadUrls(
                encryptedMediaUrl =
                    song.moreInfo?.encryptedMediaUrl?.takeIf { it.isNotBlank() }
                        ?: song.encryptedMediaUrl,
                mediaPreviewUrl =
                    song.moreInfo?.mediaPreviewUrl?.takeIf { it.isNotBlank() }
                        ?: song.mediaPreviewUrl,
                supports320 = song.supports320,
            )
        }.onFailure {
            Log.w(TAG, "getBestStreamUrl failed for id=$saavnSongId", it)
        }.getOrNull()?.let { selectBestUrl(it, quality) }

    suspend fun resolveStreamUrl(
        song: SaavnSong,
        quality: String,
    ): String? {
        selectBestUrl(song.downloadUrl, quality)?.let { return it }
        return getBestStreamUrl(song.id, quality)
    }

    private fun buildDownloadUrls(
        encryptedMediaUrl: String,
        mediaPreviewUrl: String,
        supports320: String,
    ): List<SaavnDownloadUrl> {
        SaavnUrlDecryptor.buildDownloadUrlsFromPreview(mediaPreviewUrl, supports320 == "true")
            .takeIf { it.isNotEmpty() }
            ?.let { return it }
        return SaavnUrlDecryptor.buildDownloadUrls(encryptedMediaUrl)
    }

    private fun parseSearchResults(rawBody: String): List<SaavnSong> {
        runCatching {
            json.decodeFromString<NativeSearchResponse>(rawBody).results
        }.getOrNull()?.let { nativeResults ->
            return nativeResults
                .asSequence()
                .filter { it.type.isBlank() || it.type.equals("song", ignoreCase = true) }
                .mapNotNull { it.toSaavnSong() }
                .filter { it.downloadUrl.isNotEmpty() }
                .toList()
        }

        return runCatching {
            val root = JSONObject(rawBody)
            val array = root.optJSONArray("results") ?: JSONArray()
            buildList {
                for (index in 0 until array.length()) {
                    parseJSONObjectSong(array.optJSONObject(index))?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseJSONObjectSong(item: JSONObject?): SaavnSong? {
        if (item == null) return null
        val type = item.optString("type")
        if (type.isNotBlank() && !type.equals("song", ignoreCase = true)) return null
        val id = item.optString("id")
        val name = decodeHtmlEntities(item.optString("song"))
        if (id.isBlank() || name.isBlank()) return null
        val downloadUrls =
            buildDownloadUrls(
                encryptedMediaUrl = item.optString("encrypted_media_url"),
                mediaPreviewUrl = item.optString("media_preview_url"),
                supports320 = item.optString("320kbps", "false"),
            )
        if (downloadUrls.isEmpty()) return null
        return SaavnSong(
            id = id,
            name = name,
            duration = item.optString("duration").toIntOrNull(),
            artists =
                SaavnArtists(
                    primary =
                        decodeHtmlEntities(item.optString("primary_artists"))
                            .split(',')
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .map { SaavnArtistItem(name = it) },
                ),
            image =
                item.optString("image")
                    .takeIf { it.isNotBlank() }
                    ?.let { listOf(SaavnImage(quality = "150x150", url = it.replace("150x150", "500x500"))) }
                    ?: emptyList(),
            downloadUrl = downloadUrls,
            album = decodeHtmlEntities(item.optString("album")).takeIf { it.isNotBlank() }?.let { SaavnAlbum(name = it) },
        )
    }

    private fun NativeSongResult.toSaavnSong(): SaavnSong? {
        if (id.isBlank() || song.isBlank()) return null
        val downloadUrls =
            buildDownloadUrls(
                encryptedMediaUrl = encryptedMediaUrl,
                mediaPreviewUrl = mediaPreviewUrl,
                supports320 = supports320,
            )
        if (downloadUrls.isEmpty()) return null
        return SaavnSong(
            id = id,
            name = decodeHtmlEntities(song),
            duration = duration.toIntOrNull(),
            artists =
                SaavnArtists(
                    primary =
                        decodeHtmlEntities(primaryArtists)
                            .split(',')
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .map { SaavnArtistItem(name = it) },
                ),
            image =
                if (image.isNotBlank()) {
                    listOf(SaavnImage(quality = "150x150", url = image.replace("150x150", "500x500")))
                } else {
                    emptyList()
                },
            downloadUrl = downloadUrls,
            album = decodeHtmlEntities(album).takeIf { it.isNotBlank() }?.let { SaavnAlbum(name = it) },
        )
    }

    private fun decodeHtmlEntities(value: String): String =
        value
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
}
