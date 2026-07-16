/*
 * Hush — GPL-3.0
 * JioSaavn client using the public jiosaavn.com API (search + encrypted stream URLs).
 * Multi-format resilient parsing with DES decryption + auth token fallback.
 */

package app.hush.music.jiosaavn

import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

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
    @SerialName("320kbps") val supports320Raw: JsonElement? = null,
    @SerialName("image") val image: String = "",
) {
    val supports320: Boolean get() = when (supports320Raw?.jsonPrimitive) {
        is JsonPrimitive -> supports320Raw.jsonPrimitive.content == "true" || supports320Raw.jsonPrimitive.content == "1"
        null -> false
    }
}

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
    @SerialName("320kbps") val supports320Raw: JsonElement? = null,
) {
    val supports320: Boolean get() = when (supports320Raw?.jsonPrimitive) {
        is JsonPrimitive -> supports320Raw.jsonPrimitive.content == "true" || supports320Raw.jsonPrimitive.content == "1"
        null -> false
    }
}

@Serializable
private data class NativeSongMoreInfo(
    @SerialName("encrypted_media_url") val encryptedMediaUrl: String = "",
    @SerialName("media_preview_url") val mediaPreviewUrl: String = "",
    @SerialName("duration") val duration: String = "",
    @SerialName("320kbps") val supports320Raw: JsonElement? = null,
) {
    val supports320: Boolean get() = when (supports320Raw?.jsonPrimitive) {
        is JsonPrimitive -> supports320Raw.jsonPrimitive.content == "true" || supports320Raw.jsonPrimitive.content == "1"
        null -> false
    }
}

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

    @Volatile
    private var customDns: okhttp3.Dns? = null
    @Volatile
    private var customProxy: java.net.Proxy? = null

    @Volatile
    private var _client: HttpClient? = null

    private fun buildClient(): HttpClient {
        return HttpClient(OkHttp) {
            engine {
                config {
                    customDns?.let { dns(it) }
                    if (customProxy != null) {
                        proxy(customProxy)
                    }
                }
            }
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
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

    private val client: HttpClient
        get() {
            val existing = _client
            if (existing != null) return existing
            return buildClient().also { _client = it }
        }

    fun reconfigure(dns: okhttp3.Dns?, proxy: java.net.Proxy?) {
        customDns = dns
        customProxy = proxy
        _client = buildClient()
    }

    private suspend fun apiCall(
        block: suspend (String) -> io.ktor.client.statement.HttpResponse,
    ): io.ktor.client.statement.HttpResponse {
        val maxAttempts = 3
        var lastError: Throwable? = null
        for (attempt in 0 until maxAttempts) {
            val server = DeviceRouter.getCurrentServer()
            try {
                val response = block(server)
                val status = response.status
                if (status.value in 500..599) {
                    Log.w(TAG, "apiCall attempt $attempt: HTTP ${status.value} on $server")
                    DeviceRouter.fallbackToNextServer()
                    lastError = Exception("Server ${status.value}")
                    continue
                }
                if (status.value != HttpStatusCode.OK.value) {
                    Log.w(TAG, "apiCall attempt $attempt: non-OK status ${status.value}")
                }
                return response
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "apiCall attempt $attempt failed on $server: ${e.message}")
                DeviceRouter.fallbackToNextServer()
            }
        }
        throw lastError ?: Exception("All API attempts failed")
    }

    // ── Search ────────────────────────────────────────────────────────────────

    suspend fun searchSongs(
        query: String,
        limit: Int = 15,
    ): Result<List<SaavnSong>> =
        runCatching {
            val q = query.trim()
            if (q.isBlank()) error("Empty query")
            Log.i(TAG, "searchSongs \"$q\"")

            // Try v4 and legacy formats in parallel, use whichever responds first with results
            val paramSets = listOf(
                mapOf(
                    "__call" to "search.getResults", "_format" to "json", "_marker" to "0",
                    "cc" to "in", "q" to q, "p" to "1", "n" to limit.toString(),
                    "api_version" to "4", "ctx" to "web6dot0",
                ),
                mapOf(
                    "__call" to "search.getResults", "_format" to "json", "_marker" to "0",
                    "cc" to "in", "q" to q, "p" to "1", "n" to limit.toString(),
                ),
            )

            val searchResult = coroutineScope {
                val deferreds = paramSets.map { params ->
                    async {
                        params to runCatching {
                            val response = apiCall { baseUrl ->
                                client.get(baseUrl) {
                                    params.forEach { (key, value) -> parameter(key, value) }
                                }
                            }
                            if (response.status != HttpStatusCode.OK) {
                                error("HTTP ${response.status.value}")
                            }
                            val rawBody = response.bodyAsText()
                            val results = parseSearchResults(rawBody)
                            if (results.isEmpty()) error("0 results")
                            results
                        }
                    }
                }

                val remaining = deferreds.toMutableList()
                var lastError: Throwable? = null
                var found: List<SaavnSong>? = null

                while (remaining.isNotEmpty() && found == null) {
                    val (params, result) = select<Pair<Map<String, String>, Result<List<SaavnSong>>>> {
                        remaining.forEach { d ->
                            d.onAwait { it }
                        }
                    }
                    remaining.removeAll { it.isCompleted }

                    result.onSuccess { results ->
                        Log.i(TAG, "searchSongs \"$q\" -> ${results.size} results (fmt=${params["api_version"] ?: "legacy"})")
                        remaining.forEach { it.cancel() }
                        found = results
                    }.onFailure { e ->
                        lastError = e
                        Log.w(TAG, "searchSongs params=$params failed: ${e.message}")
                    }
                }

                found ?: throw (lastError ?: error("No results for \"$q\""))
            }

            return@runCatching searchResult
        }.onFailure {
            Log.w(TAG, "searchSongs failed for \"$query\"", it)
        }

    fun selectBestUrl(
        urls: List<SaavnDownloadUrl>,
        quality: String,
    ): String? {
        val valid = urls.filter { it.url.isNotBlank() }
        if (valid.isEmpty()) return null
        valid.firstOrNull { it.quality.equals(quality, ignoreCase = true) }?.url?.let { return it }
        valid.firstOrNull { it.quality.equals("320kbps", ignoreCase = true) }?.url?.let { return it }
        valid.firstOrNull { it.quality.equals("160kbps", ignoreCase = true) }?.url?.let { return it }
        valid.firstOrNull { it.quality.equals("96kbps", ignoreCase = true) }?.url?.let { return it }
        return valid.lastOrNull()?.url
    }

    // ── Stream URL Resolution ─────────────────────────────────────────────────

    suspend fun resolveStreamUrl(
        song: SaavnSong,
        quality: String,
    ): String? {
        Log.i(TAG, "resolveStreamUrl id=${song.id} name=\"${song.name}\" quality=$quality")
        val qualityOrder = listOf(quality, "320kbps", "160kbps", "96kbps").distinct()
        val url = resolveStreamFresh(song.id, qualityOrder)
        if (url != null) {
            Log.i(TAG, "resolveStreamUrl SUCCESS for id=${song.id} -> ${url.take(80)}")
        } else {
            Log.w(TAG, "resolveStreamUrl FAILED for id=${song.id} quality=$quality")
        }
        return url
    }

    private suspend fun resolveStreamFresh(
        saavnSongId: String,
        qualityOrder: List<String>,
    ): String? = coroutineScope {
        Log.d(TAG, "resolveStreamFresh: racing all paths for $saavnSongId")
        val paths = listOf(
            "authToken" to async { tryGenerateAuthToken(saavnSongId, qualityOrder.first()) },
            "v4Decrypt" to async { tryDecrypt(saavnSongId, qualityOrder, apiV4 = true) },
            "flatDecrypt" to async { tryDecrypt(saavnSongId, qualityOrder, apiV4 = false) },
        )

        val remaining = paths.map { it.second }.toMutableList()

        while (remaining.isNotEmpty()) {
            val url = select<String?> {
                remaining.forEach { d ->
                    d.onAwait { it }
                }
            }
            remaining.removeAll { it.isCompleted }

            if (url != null) return@coroutineScope url
        }

        null
    }

    private suspend fun tryGenerateAuthToken(
        saavnSongId: String,
        quality: String,
    ): String? {
        return runCatching {
            val encryptedUrl = fetchAnyEncryptedUrl(saavnSongId)
                ?: return@runCatching null.also { Log.w(TAG, "tryGenerateAuthToken: no encrypted URL for $saavnSongId") }

            val bitrate = when {
                quality.contains("320") -> "320"
                quality.contains("160") -> "160"
                else -> "96"
            }

            val resp = apiCall { baseUrl ->
                client.post(baseUrl) {
                    parameter("__call", "song.generateAuthToken")
                    parameter("_format", "json")
                    parameter("_marker", "0")
                    setBody("bitrate=$bitrate&url=${URLEncoder.encode(encryptedUrl, "UTF-8")}")
                    headers.append(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                }
            }
            if (resp.status != HttpStatusCode.OK) {
                Log.w(TAG, "tryGenerateAuthToken: HTTP ${resp.status.value}")
                return@runCatching null
            }
            val raw = resp.bodyAsText()
            val root = JSONObject(raw)
            val authUrl = root.optString("auth_url", "")
            if (authUrl.startsWith("http")) {
                Log.i(TAG, "tryGenerateAuthToken SUCCESS for $saavnSongId")
                authUrl
            } else {
                Log.w(TAG, "tryGenerateAuthToken: no auth_url — ${raw.take(150)}")
                null
            }
        }.getOrNull()
    }

    private suspend fun fetchAnyEncryptedUrl(saavnSongId: String): String? {
        val v4 = runCatching {
            val resp = apiCall { baseUrl ->
                client.get(baseUrl) {
                    parameter("__call", "song.getDetails")
                    parameter("pids", saavnSongId)
                    parameter("_format", "json")
                    parameter("_marker", "0")
                    parameter("api_version", "4")
                    parameter("ctx", "web6dot0")
                }
            }
            if (resp.status != HttpStatusCode.OK) return@runCatching null
            val raw = resp.bodyAsText()
            runCatching {
                val body = json.decodeFromString<NativeSongDetailsResponse>(raw)
                val s = body.songs.firstOrNull() ?: return@runCatching null
                s.moreInfo?.encryptedMediaUrl?.takeIf { it.isNotBlank() }
                    ?: s.encryptedMediaUrl.takeIf { it.isNotBlank() }
            }.getOrNull() ?: runCatching {
                val root = JSONObject(raw)
                val arr = root.optJSONArray("songs")
                if (arr != null && arr.length() > 0) {
                    arr.getJSONObject(0).optString("encrypted_media_url", "").takeIf { it.isNotBlank() }
                } else {
                    root.optString("encrypted_media_url", "").takeIf { it.isNotBlank() }
                }
            }.getOrNull()
        }.getOrNull()
        if (v4 != null) return v4

        return runCatching {
            val resp = apiCall { baseUrl ->
                client.get(baseUrl) {
                    parameter("__call", "song.getDetails")
                    parameter("pids", saavnSongId)
                    parameter("_format", "json")
                    parameter("_marker", "0")
                }
            }
            if (resp.status != HttpStatusCode.OK) return@runCatching null
            val raw = resp.bodyAsText()
            val root = JSONObject(raw)
            // Try keyed by song ID
            root.optJSONObject(saavnSongId)?.optString("encrypted_media_url", "")?.takeIf { it.isNotBlank() }
                ?: root.optString("encrypted_media_url", "").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private suspend fun tryDecrypt(
        saavnSongId: String,
        qualityOrder: List<String>,
        apiV4: Boolean,
    ): String? {
        return runCatching {
            val resp = apiCall { baseUrl ->
                client.get(baseUrl) {
                    parameter("__call", "song.getDetails")
                    parameter("pids", saavnSongId)
                    parameter("_format", "json")
                    parameter("_marker", "0")
                    if (apiV4) {
                        parameter("api_version", "4")
                        parameter("ctx", "web6dot0")
                    }
                }
            }
            if (resp.status != HttpStatusCode.OK) {
                Log.w(TAG, "tryDecrypt(apiV4=$apiV4): HTTP ${resp.status.value}")
                return@runCatching null
            }
            val raw = resp.bodyAsText()
            Log.d(TAG, "tryDecrypt(apiV4=$apiV4) raw=${raw.take(200)}")

            var encUrl = ""
            var prevUrl = ""
            var sup320 = "false"

            if (apiV4) {
                // Try structured parse first
                val structured = runCatching {
                    val body = json.decodeFromString<NativeSongDetailsResponse>(raw)
                    val s = body.songs.firstOrNull()
                    if (s != null) {
                        encUrl = s.moreInfo?.encryptedMediaUrl?.takeIf { it.isNotBlank() } ?: s.encryptedMediaUrl
                        prevUrl = s.moreInfo?.mediaPreviewUrl?.takeIf { it.isNotBlank() } ?: s.mediaPreviewUrl
                        sup320 = if (s.moreInfo?.supports320 ?: s.supports320) "true" else "false"
                    }
                }
                if (structured.isFailure) {
                    // Fallback: manual JSONObject parse
                    runCatching {
                        val root = JSONObject(raw)
                        val arr = root.optJSONArray("songs")
                        if (arr != null && arr.length() > 0) {
                            val item = arr.getJSONObject(0)
                            val mi = item.optJSONObject("more_info")
                            encUrl = mi?.optString("encrypted_media_url", "")?.takeIf { it.isNotBlank() }
                                ?: item.optString("encrypted_media_url", "")
                            prevUrl = mi?.optString("media_preview_url", "")?.takeIf { it.isNotBlank() }
                                ?: item.optString("media_preview_url", "")
                            sup320 = if (item.optString("320kbps", "0") in listOf("1", "true")) "true" else "false"
                        }
                    }
                }
            } else {
                runCatching {
                    val root = JSONObject(raw)
                    val item = root.optJSONObject(saavnSongId)
                        ?: root.optJSONObject("result")?.optJSONObject(saavnSongId)
                        ?: root.optJSONArray("songs")?.optJSONObject(0)
                    if (item != null) {
                        encUrl = item.optString("encrypted_media_url", "")
                        prevUrl = item.optString("media_preview_url", "")
                        sup320 = if (item.optString("320kbps", "0") in listOf("1", "true")) "true" else "false"
                    }
                }
            }

            if (encUrl.isBlank() && prevUrl.isBlank()) {
                Log.w(TAG, "tryDecrypt(apiV4=$apiV4): no URLs found in response")
                return@runCatching null
            }

            val urls = buildDownloadUrls(
                encryptedMediaUrl = encUrl,
                mediaPreviewUrl = prevUrl,
                supports320 = sup320,
            )
            Log.d(TAG, "tryDecrypt(apiV4=$apiV4): got ${urls.size} URLs (enc=${encUrl.take(40)} prev=${prevUrl.take(40)})")

            for (q in qualityOrder) {
                val u = urls.firstOrNull { it.quality == q }?.url?.takeIf { it.isNotBlank() }
                if (u != null) {
                    Log.i(TAG, "tryDecrypt(apiV4=$apiV4) SUCCESS quality=$q for $saavnSongId")
                    return@runCatching u
                }
            }
            Log.w(TAG, "tryDecrypt(apiV4=$apiV4): no quality match from $qualityOrder")
            null
        }.getOrNull()
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
        // 1) NativeSearchResponse: {"total":..., "results":[...]}
        runCatching {
            json.decodeFromString<NativeSearchResponse>(rawBody).results
        }.getOrNull()?.let { nativeResults ->
            val songs = nativeResults
                .asSequence()
                .filter { it.type.isBlank() || it.type.equals("song", ignoreCase = true) }
                .mapNotNull { it.toSaavnSong() }
                .toList()
            if (songs.isNotEmpty()) return songs
        }

        // 2) JSON object with "results" array
        runCatching {
            val root = JSONObject(rawBody)
            val array = root.optJSONArray("results") ?: return@runCatching emptyList<SaavnSong>()
            (0 until array.length()).mapNotNull { parseJSONObjectSong(array.optJSONObject(it)) }
        }.getOrNull()?.let { if (it.isNotEmpty()) return it }

        // 3) Raw JSON array
        runCatching {
            val array = JSONArray(rawBody)
            (0 until array.length()).mapNotNull { parseJSONObjectSong(array.optJSONObject(it)) }
        }.getOrNull()?.let { if (it.isNotEmpty()) return it }

        // 4) JSON object with "songs" key
        runCatching {
            val root = JSONObject(rawBody)
            val array = root.optJSONArray("songs") ?: return@runCatching emptyList<SaavnSong>()
            (0 until array.length()).mapNotNull { parseJSONObjectSong(array.optJSONObject(it)) }
        }.getOrNull()?.let { if (it.isNotEmpty()) return it }

        Log.w(TAG, "parseSearchResults failed — raw=${rawBody.take(300)}")
        return emptyList()
    }

    private fun parseJSONObjectSong(item: JSONObject?): SaavnSong? {
        if (item == null) return null
        val type = item.optString("type")
        if (type.isNotBlank() && !type.equals("song", ignoreCase = true)) return null
        val id = item.optString("id")
        val name = decodeHtmlEntities(item.optString("song"))
        if (id.isBlank() || name.isBlank()) return null
        val downloadUrls = buildDownloadUrls(
            encryptedMediaUrl = item.optString("encrypted_media_url"),
            mediaPreviewUrl = item.optString("media_preview_url"),
            supports320 = item.optString("320kbps", "false"),
        )
        return SaavnSong(
            id = id,
            name = name,
            duration = item.optString("duration").toIntOrNull(),
            artists = SaavnArtists(
                primary = decodeHtmlEntities(item.optString("primary_artists"))
                    .split(',', '&')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .map { SaavnArtistItem(name = it) },
            ),
            image = item.optString("image").takeIf { it.isNotBlank() }
                ?.let { listOf(SaavnImage(quality = "150x150", url = it.replace("150x150", "500x500"))) }
                ?: emptyList(),
            downloadUrl = downloadUrls,
            album = decodeHtmlEntities(item.optString("album")).takeIf { it.isNotBlank() }
                ?.let { SaavnAlbum(name = it) },
        )
    }

    private fun NativeSongResult.toSaavnSong(): SaavnSong? {
        if (id.isBlank() || song.isBlank()) return null
        val downloadUrls = buildDownloadUrls(
            encryptedMediaUrl = encryptedMediaUrl,
            mediaPreviewUrl = mediaPreviewUrl,
            supports320 = if (supports320) "true" else "false",
        )
        return SaavnSong(
            id = id,
            name = decodeHtmlEntities(song),
            duration = duration.toIntOrNull(),
            artists = SaavnArtists(
                primary = decodeHtmlEntities(primaryArtists)
                    .split(',', '&')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .map { SaavnArtistItem(name = it) },
            ),
            image = if (image.isNotBlank()) {
                listOf(SaavnImage(quality = "150x150", url = image.replace("150x150", "500x500")))
            } else emptyList(),
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
