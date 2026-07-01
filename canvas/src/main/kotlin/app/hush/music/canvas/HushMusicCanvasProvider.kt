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
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import app.hush.music.canvas.models.CanvasArtwork

@Serializable
data class HushCanvasManifest(
    val items: List<HushCanvasItem> = emptyList(),
)

@Serializable
data class HushCanvasItem(
    val song: String,
    val artist: String,
    val url: String,
    val album: String = "",
)

object HushMusicCanvasProvider {
    private const val BASE_URL = "https://vivimusicanvas.mkmdevilmi.workers.dev/canvas.json"

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
                connectTimeoutMillis = 12_000
                requestTimeoutMillis = 18_000
                socketTimeoutMillis = 18_000
            }
            install(ContentEncoding) {
                gzip()
                deflate()
            }
            install(HttpCache)
            expectSuccess = false
        }
    }

    private data class CacheEntry(
        val value: HushCanvasManifest?,
        val expiresAtMs: Long,
    )

    private var manifestCache: CacheEntry? = null
    private val ttlMs = 60_000L

    private suspend fun fetchManifest(): HushCanvasManifest? {
        val currentCache = manifestCache
        if (currentCache != null && currentCache.expiresAtMs > System.currentTimeMillis()) {
            return currentCache.value
        }

        return try {
            val response = client.get(BASE_URL)
            if (response.status != HttpStatusCode.OK) return null
            val manifest: HushCanvasManifest = response.body()
            manifestCache =
                CacheEntry(
                    value = manifest,
                    expiresAtMs = System.currentTimeMillis() + ttlMs,
                )
            manifest
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getBySongArtist(
        song: String,
        artist: String,
        album: String,
    ): CanvasArtwork? {
        if (song.isBlank() || artist.isBlank()) return null

        val manifest = fetchManifest() ?: return null

        val target =
            manifest.items.firstOrNull { item ->
                val matchSong = song.contains(item.song, ignoreCase = true) || item.song.contains(song, ignoreCase = true)
                val matchArtist = artist.contains(item.artist, ignoreCase = true) || item.artist.contains(artist, ignoreCase = true)
                val matchAlbum = album.trim().equals(item.album.trim(), ignoreCase = true)
                matchSong && matchArtist && matchAlbum
            } ?: return null

        return CanvasArtwork(
            name = target.song,
            artist = target.artist,
            albumName = target.album.takeIf { it.isNotBlank() },
            videoUrl = target.url,
            animated = target.url,
        )
    }
}
