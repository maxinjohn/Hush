/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.canvas

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import app.hush.music.canvas.models.CanvasArtwork
import app.hush.music.canvas.models.matchesSongIdentity
import app.hush.music.spotify.Spotify
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.Base64
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * Spotify canvas source, ported from ArchiveTune.
 *
 * A track is found through the pathfinder searchDesktop query (reusing [Spotify], which owns
 * token + hash handling), then canvas clips for the candidate URIs are fetched from the
 * canvaz-cache protobuf endpoint, which additionally requires a client-token handshake.
 *
 * This provider never throws for ordinary failures: the resolver runs it inside the AUTO
 * chain, where a failure must degrade to "no canvas from this source", not a crash.
 */
object SpotifyCanvasProvider {
    private const val CANVAS_URL = "https://spclient.wg.spotify.com/canvaz-cache/v0/canvases"
    private const val CLIENT_TOKEN_URL = "https://clienttoken.spotify.com/v1/clienttoken"
    private const val OPEN_SPOTIFY_URL = "https://open.spotify.com/"
    private const val WEB_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private const val APP_USER_AGENT = "Spotify/9.0.34.593 iOS/18.4 (iPhone15,3)"
    private const val HEALTH_TRACK_URI = "spotify:track:0VjIjW4GlUZAMYd2vXMi3b"
    private const val LOG_TAG = "SpotifyCanvas"

    private val trackUriPattern = Regex("spotify:track:[A-Za-z0-9]{22}")
    private val configPattern = Regex("""<script[^>]*id="appServerConfig"[^>]*>([^<]+)</script>""")
    private val json = Json { ignoreUnknownKeys = true }
    private val clientTokenMutex = Mutex()

    @Volatile
    private var cachedClientToken: ClientToken? = null

    private val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 12_000
                requestTimeoutMillis = 18_000
                socketTimeoutMillis = 18_000
            }
            expectSuccess = false
        }
    }

    suspend fun getBySongArtist(
        song: String,
        artist: String,
    ): CanvasArtwork? =
        try {
            SpotifyCanvasSession.withSession { session ->
                val tracks =
                    Spotify
                        .search(query = "$song $artist", limit = 10)
                        .getOrNull()
                        ?.tracks
                        ?.items
                        .orEmpty()
                val candidates =
                    tracks.mapNotNull { track ->
                        val uri =
                            track.uri?.takeIf(trackUriPattern::matches)
                                ?: track.id.takeIf { it.isNotBlank() }?.let { "spotify:track:$it" }
                                    ?.takeIf(trackUriPattern::matches)
                                ?: return@mapNotNull null
                        uri to
                            CanvasArtwork(
                                name = track.name,
                                artist = track.artists.joinToString(", ") { it.name },
                                albumName = track.album?.name,
                                static =
                                    track.album?.images
                                        ?.minByOrNull { abs((it.width ?: 640) - 640) }
                                        ?.url,
                            )
                    }.filter { (_, artwork) -> artwork.matchesSongIdentity(song, artist) }
                if (candidates.isEmpty()) return@withSession null

                val urls =
                    getCanvases(
                        trackUris = candidates.map { it.first }.distinct().take(10),
                        accessToken = session.accessToken,
                        clientId = session.clientId,
                    )
                candidates.firstNotNullOfOrNull { (uri, artwork) ->
                    urls[uri]?.let { artwork.copy(videoUrl = it, videoUrlVertical = it) }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.tag(LOG_TAG).d(error, "Spotify canvas lookup failed for %s", song)
            null
        }

    /** Liveness probe for the settings health panel: fetch canvases for a fixed track. */
    suspend fun isHealthy(): Boolean =
        SpotifyCanvasSession.withSession { session ->
            getCanvases(
                trackUris = listOf(HEALTH_TRACK_URI),
                accessToken = session.accessToken,
                clientId = session.clientId,
            )
            true
        }

    suspend fun getCanvases(
        trackUris: List<String>,
        accessToken: String,
        clientId: String,
    ): Map<String, String> {
        require(trackUris.size <= 10)
        require(trackUris.all(trackUriPattern::matches))
        val clientToken = clientToken(clientId)
        val response =
            client.post(CANVAS_URL) {
                header("Authorization", "Bearer $accessToken")
                header("Client-Token", clientToken)
                header("User-Agent", APP_USER_AGENT)
                header("Accept", "application/protobuf")
                header("Content-Type", "application/protobuf")
                header("Accept-Language", "en")
                setBody(encodeRequest(trackUris))
            }
        if (response.status != HttpStatusCode.OK) throw RequestException(response.status.value)
        val bytes = response.body<ByteArray>()
        if (bytes.size > 1_048_576) throw IOException("Spotify Canvas response is too large")
        return decodeResponse(bytes).filterKeys { it in trackUris }
    }

    /**
     * Mints a client token for the canvaz-cache call. The token is bound to a client id, so the
     * web player page is scraped for its config (version) and the sp_t cookie (device id) first.
     */
    private suspend fun clientToken(clientId: String): String =
        clientTokenMutex.withLock {
            require(clientId.isNotBlank())
            val now = System.nanoTime()
            cachedClientToken
                ?.takeIf { it.clientId == clientId && now < it.expiresAtNanos }
                ?.let { return@withLock it.value }

            val page = client.get(OPEN_SPOTIFY_URL) { header("User-Agent", WEB_USER_AGENT) }
            if (page.status != HttpStatusCode.OK) throw RequestException(page.status.value)
            val encoded =
                configPattern.find(page.bodyAsText())?.groupValues?.get(1)
                    ?: throw IOException("Spotify client configuration is missing")
            val config =
                json
                    .parseToJsonElement(String(Base64.getDecoder().decode(encoded), Charsets.UTF_8))
                    as? JsonObject ?: throw IOException("Spotify client configuration is invalid")
            val version =
                (config["clientVersion"] as? JsonPrimitive)?.contentOrNull
                    ?: throw IOException("Spotify client version is missing")
            val deviceId =
                page.headers
                    .getAll("Set-Cookie")
                    .orEmpty()
                    .firstNotNullOfOrNull {
                        it.substringBefore(';').takeIf { cookie -> cookie.startsWith("sp_t=") }?.substringAfter('=')
                    } ?: UUID.randomUUID().toString()
            val payload =
                buildJsonObject {
                    putJsonObject("client_data") {
                        put("client_version", version)
                        put("client_id", clientId)
                        putJsonObject("js_sdk_data") {
                            put("device_brand", "unknown")
                            put("device_model", "unknown")
                            put("os", "android")
                            put("os_version", "14")
                            put("device_id", deviceId)
                            put("device_type", "smartphone")
                        }
                    }
                }
            val response =
                client.post(CLIENT_TOKEN_URL) {
                    header("User-Agent", WEB_USER_AGENT)
                    header("Accept", "application/json")
                    header("Content-Type", "application/json")
                    setBody(payload.toString().toByteArray(Charsets.UTF_8))
                }
            if (response.status != HttpStatusCode.OK) throw RequestException(response.status.value)
            val root = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
            val granted =
                root?.get("granted_token") as? JsonObject
                    ?: throw IOException("Spotify client token was not granted")
            val value =
                (granted["token"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                    ?: throw IOException("Spotify client token was not granted")
            val ttlSeconds =
                (granted["expires_after_seconds"] as? JsonPrimitive)?.longOrNull
                    ?.coerceIn(0, 86_400) ?: 0
            cachedClientToken =
                ClientToken(
                    clientId = clientId,
                    value = value,
                    expiresAtNanos = now + (ttlSeconds - 30).coerceAtLeast(0) * 1_000_000_000,
                )
            value
        }

    // ── canvaz-cache protobuf wire ─────────────────────────────────────────────────
    //
    // Request:  repeated CanvasRequest { string uri = 1; }
    // Response: repeated Canvas { string colors = ...; string url = 2; ... string uri = 5; }
    //
    // Hand-rolled instead of pulling in a protobuf runtime for two trivial messages.

    // Internal (not private) so the wire format can be unit tested from this module's tests;
    // nothing outside this file calls them.
    internal fun encodeRequest(trackUris: List<String>): ByteArray {
        val buffer = ByteArrayOutputStream()
        for (uri in trackUris) {
            val uriBytes = uri.toByteArray(Charsets.UTF_8)
            val track = ByteArrayOutputStream()
            track.write(TAG_FIELD1_LENGTH_DELIMITED) // field 1, wire type 2
            track.writeVarint(uriBytes.size)
            track.write(uriBytes)
            val trackBytes = track.toByteArray()
            buffer.write(TAG_FIELD1_LENGTH_DELIMITED) // field 1, wire type 2
            buffer.writeVarint(trackBytes.size)
            buffer.write(trackBytes)
        }
        return buffer.toByteArray()
    }

    internal fun decodeResponse(bytes: ByteArray): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val input = ProtobufReader(bytes)
        while (!input.atEnd) {
            val tag = input.readTag()
            if (tag.fieldNumber == 1 && tag.isLengthDelimited) {
                val canvas = ProtobufReader(input.readLengthDelimited())
                var uri: String? = null
                var url: String? = null
                while (!canvas.atEnd) {
                    val field = canvas.readTag()
                    when {
                        field.fieldNumber == 2 && field.isLengthDelimited ->
                            url = canvas.readLengthDelimited().toString(Charsets.UTF_8)

                        field.fieldNumber == 5 && field.isLengthDelimited ->
                            uri = canvas.readLengthDelimited().toString(Charsets.UTF_8)

                        else ->
                            if (!canvas.skipField(field)) {
                                throw IOException("Invalid Spotify Canvas field")
                            }
                    }
                }
                if (uri != null && url != null && isCanvasUrl(url)) result[uri] = url
            } else if (!input.skipField(tag)) {
                throw IOException("Invalid Spotify Canvas response")
            }
        }
        return result
    }

    private fun isCanvasUrl(value: String): Boolean =
        try {
            val uri = URI(value)
            uri.scheme == "https" &&
                uri.host == "canvaz.scdn.co" &&
                uri.path?.lowercase(Locale.ROOT)?.endsWith(".mp4") == true
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: java.net.URISyntaxException) {
            false
        }

    private const val TAG_FIELD1_LENGTH_DELIMITED = 0x0A // (field 1 shl 3) or wire type 2

    private data class Tag(
        val fieldNumber: Int,
        val wireType: Int,
    ) {
        val isLengthDelimited: Boolean get() = wireType == 2
    }

    private class ProtobufReader(
        private val data: ByteArray,
    ) {
        private var position = 0

        val atEnd: Boolean get() = position >= data.size

        fun readTag(): Tag {
            val header = readVarint().toInt()
            return Tag(fieldNumber = header ushr 3, wireType = header and 0x7)
        }

        fun readLengthDelimited(): ByteArray {
            val length = readVarint().toInt()
            if (length < 0 || position + length > data.size) {
                throw IOException("Truncated Spotify Canvas response")
            }
            val out = data.copyOfRange(position, position + length)
            position += length
            return out
        }

        fun skipField(tag: Tag): Boolean =
            when (tag.wireType) {
                0 -> {
                    readVarint()
                    true
                }

                1 -> skip(8)

                2 -> {
                    readLengthDelimited()
                    true
                }

                5 -> skip(4)

                else -> false
            }

        private fun skip(count: Int): Boolean {
            if (position + count > data.size) return false
            position += count
            return true
        }

        private fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (position >= data.size) throw IOException("Truncated Spotify Canvas response")
                val current = data[position].toInt() and 0xFF
                position++
                result = result or ((current and 0x7F).toLong() shl shift)
                if (current and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IOException("Malformed Spotify Canvas varint")
            }
        }
    }

    private fun ByteArrayOutputStream.writeVarint(value: Int) {
        var remaining = value
        while (true) {
            val bits = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining == 0) {
                write(bits)
                return
            }
            write(bits or 0x80)
        }
    }

    private data class ClientToken(
        val clientId: String,
        val value: String,
        val expiresAtNanos: Long,
    )

    class RequestException(
        val statusCode: Int,
    ) : IOException("Spotify Canvas request failed: HTTP $statusCode")
}
