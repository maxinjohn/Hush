/*
 * Hush (2026) — GPL-3.0
 */
package app.hush.music.musixmatch

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

object Musixmatch {
    private const val BASE_URL = "https://apic-desktop.musixmatch.com/ws/1.1/"
    private const val APP_ID = "web-desktop-app-v1.0"
    private const val TOKEN_TTL_MS = 30 * 60 * 1000L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val tokenMutex = Mutex()
    private var token: String? = null
    private var tokenAt = 0L

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 15_000
                socketTimeoutMillis = 15_000
            }
            expectSuccess = false
        }
    }

    suspend fun getLyrics(title: String, artist: String, durationSeconds: Int): Result<String> = runCatching {
        val userToken = getToken()
        val trackId = findTrack(userToken, title, artist, durationSeconds)
            ?: error("Musixmatch track not found")
        getSubtitle(userToken, trackId) ?: getPlainLyrics(userToken, trackId)
            ?: error("Musixmatch lyrics unavailable")
    }

    private suspend fun getToken(): String = tokenMutex.withLock {
        if (token != null && System.currentTimeMillis() - tokenAt < TOKEN_TTL_MS) return token!!
        val response = client.get(BASE_URL + "token.get") {
            parameter("app_id", APP_ID)
            parameter("guid", UUID.randomUUID().toString())
            parameter("format", "json")
        }
        val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val status = root["message"]?.jsonObject?.get("header")?.jsonObject?.get("status_code")?.jsonPrimitive?.content?.toIntOrNull()
        if (status != 200) error("Musixmatch token request failed: HTTP $status")
        val value = root["message"]!!.jsonObject["body"]!!.jsonObject["user_token"]!!.jsonPrimitive.content
        token = value
        tokenAt = System.currentTimeMillis()
        value
    }

    private suspend fun findTrack(token: String, title: String, artist: String, duration: Int): Long? {
        val response = client.get(BASE_URL + "track.search") {
            parameter("app_id", APP_ID)
            parameter("usertoken", token)
            parameter("q_track", title)
            parameter("q_artist", artist)
            parameter("page_size", 10)
            parameter("format", "json")
        }
        val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val list = root["message"]?.jsonObject?.get("body")?.jsonObject?.get("track_list")
            ?: return null
        val candidates = list as? kotlinx.serialization.json.JsonArray ?: return null
        return candidates.mapNotNull { item ->
            val track = item.jsonObject["track"]?.jsonObject ?: return@mapNotNull null
            val id = track["track_id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
            val candidateDuration = track["track_length"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt() ?: 0
            val candidateTitle = track["track_name"]?.jsonPrimitive?.content.orEmpty()
            val candidateArtist = track["artist_name"]?.jsonPrimitive?.content.orEmpty()
            val titleScore = similarity(title, candidateTitle)
            val artistScore = similarity(artist, candidateArtist)
            val durationScore = if (duration <= 0 || candidateDuration <= 0) 0.0 else 1.0 - (kotlin.math.abs(duration - candidateDuration) / 30.0).coerceIn(0.0, 1.0)
            Triple(id, titleScore + artistScore + durationScore, kotlin.math.abs(duration - candidateDuration))
        }.sortedWith(compareByDescending<Triple<Long, Double, Int>> { it.second }.thenBy { it.third }).firstOrNull()?.first
    }

    private suspend fun getSubtitle(token: String, trackId: Long): String? =
        getTrackEndpoint("track.subtitle.get", token, trackId, "subtitle_body")

    private suspend fun getPlainLyrics(token: String, trackId: Long): String? =
        getTrackEndpoint("track.lyrics.get", token, trackId, "lyrics_body")

    private suspend fun getTrackEndpoint(endpoint: String, token: String, trackId: Long, field: String): String? {
        val response = client.get(BASE_URL + endpoint) {
            parameter("app_id", APP_ID)
            parameter("usertoken", token)
            parameter("track_id", trackId)
            parameter("format", "json")
        }
        if (!response.status.isSuccess()) return null
        val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
        return root["message"]?.jsonObject?.get("body")?.jsonObject
            ?.values?.firstOrNull()?.jsonObject?.get(field)?.jsonPrimitive?.content
            ?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun similarity(a: String, b: String): Double {
        val left = a.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
        val right = b.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
        return when {
            left == right -> 1.0
            left.contains(right) || right.contains(left) -> 0.8
            else -> 0.0
        }
    }
}
