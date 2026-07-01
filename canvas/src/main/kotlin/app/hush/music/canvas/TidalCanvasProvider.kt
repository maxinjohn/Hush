/*
 * Hush — ported from Vivi Music v6.0.3 (GPL-3.0)
 */

package app.hush.music.canvas

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.hush.music.canvas.models.CanvasArtwork
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object TidalCanvasProvider {
    private const val BASE_URL = "https://api.tidal.com/v1/"
    private const val TIDAL_TOKEN = "vNVdglQOjFJJGG2U"

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 30_000
                socketTimeoutMillis = 30_000
            }
            install(ContentEncoding) {
                gzip()
                deflate()
            }
            install(HttpCache)
            expectSuccess = false
        }
    }

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private data class CacheEntry(
        val value: CanvasArtwork?,
        val expiresAtMs: Long,
    )

    private const val CACHE_TTL_MS = 1000L * 60 * 60 * 24

    private val countryCode by lazy {
        val country = Locale.getDefault().country
        if (country.length == 2) country.uppercase(Locale.ROOT) else "US"
    }

    suspend fun getBySongArtist(
        song: String,
        artist: String,
        album: String? = null,
    ): CanvasArtwork? {
        val key = cacheKey("search_song", song, artist, album ?: "")
        cache[key]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.let { return it.value }

        val query = if (!album.isNullOrBlank()) "$album $artist $song" else "$artist $song"
        val result =
            searchOnTidal(
                query = query,
                types = "TRACKS",
                songValidation = song,
                artistValidation = artist,
            )
        if (result != null) {
            cache[key] = CacheEntry(result, System.currentTimeMillis() + CACHE_TTL_MS)
        }
        return result
    }

    private suspend fun searchOnTidal(
        query: String,
        types: String,
        songValidation: String? = null,
        artistValidation: String? = null,
        albumValidation: String? = null,
    ): CanvasArtwork? {
        return try {
            val response =
                client.get("${BASE_URL}search") {
                    header("X-Tidal-Token", TIDAL_TOKEN)
                    parameter("query", query)
                    parameter("limit", "10")
                    parameter("types", types)
                    parameter("countryCode", countryCode)
                }
            if (response.status != HttpStatusCode.OK) return null

            val root = response.body<JsonObject>()
            val key = types.lowercase(Locale.ROOT)
            val section = findSearchSection(root, key) ?: return null
            val items = section.jsonObject["items"]?.jsonArray ?: return null

            for (item in items) {
                val obj = item.jsonObject
                val resultTitle = obj["title"]?.jsonPrimitive?.contentOrNull
                val artistsArray = obj["artists"]?.jsonArray
                val allArtistNames =
                    artistsArray?.mapNotNull {
                        it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                    } ?: emptyList()
                val combinedArtistStr =
                    if (allArtistNames.isNotEmpty()) {
                        allArtistNames.joinToString(", ")
                    } else {
                        obj["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
                    }

                if (songValidation != null && resultTitle != null &&
                    !resultTitle.trim().equals(songValidation.trim(), ignoreCase = true)
                ) {
                    continue
                }

                if (albumValidation != null && resultTitle != null &&
                    !resultTitle.trim().equals(albumValidation.trim(), ignoreCase = true)
                ) {
                    continue
                }

                if (artistValidation != null && combinedArtistStr.isNotBlank()) {
                    val splitDelimiters =
                        Regex(
                            "(?:\\s*,\\s*|\\s*&\\s*|\\s+×\\s+|\\s+x\\s+|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b|\\bwith\\b)",
                            RegexOption.IGNORE_CASE,
                        )
                    val requestedList =
                        artistValidation
                            .split(splitDelimiters)
                            .map { it.replace(Regex("\\s+"), " ").trim().lowercase(Locale.ROOT) }
                            .filter { it.isNotBlank() }
                    val returnedList = allArtistNames.map { it.trim().lowercase(Locale.ROOT) }
                    val artistMatches =
                        requestedList.isNotEmpty() && returnedList.isNotEmpty() &&
                            requestedList.all { req -> returnedList.any { res -> res == req } }
                    if (!artistMatches) continue
                }

                val albumObj = if (types == "TRACKS") obj["album"]?.jsonObject else obj
                val videoCover = albumObj?.get("videoCover")?.jsonPrimitive?.contentOrNull
                val albumTitle =
                    if (types == "TRACKS") {
                        albumObj?.get("title")?.jsonPrimitive?.contentOrNull
                    } else {
                        resultTitle
                    }

                if (!videoCover.isNullOrBlank()) {
                    val videoUrl = formatVideoUrl(videoCover) ?: continue
                    return CanvasArtwork(
                        name = resultTitle ?: songValidation ?: albumValidation ?: "",
                        artist = combinedArtistStr.ifBlank { artistValidation ?: "" },
                        videoUrl = videoUrl,
                        animated = videoUrl,
                        albumName = albumTitle,
                    )
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun findSearchSection(
        source: JsonElement,
        key: String,
    ): JsonElement? {
        if (source is JsonObject) {
            if (source.containsKey("items") && source["items"] is JsonArray) return source
            if (source.containsKey(key)) {
                findSearchSection(source[key]!!, key)?.let { return it }
            }
            for (value in source.values) {
                findSearchSection(value, key)?.let { return it }
            }
        } else if (source is JsonArray) {
            for (element in source) {
                findSearchSection(element, key)?.let { return it }
            }
        }
        return null
    }

    internal fun formatVideoUrl(id: String): String? {
        val parts = id.split("-")
        if (parts.size != 5) return null
        return "https://resources.tidal.com/videos/${parts[0]}/${parts[1]}/${parts[2]}/${parts[3]}/${parts[4]}/1280x1280.mp4"
    }

    private fun cacheKey(
        prefix: String,
        vararg parts: String,
    ): String = "$prefix|" + parts.joinToString("|") { it.trim().lowercase(Locale.ROOT) }
}
