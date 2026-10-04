/**
 * QQ Music lyrics — ported from SpotiFLAC's go_backend (lyrics_qqmusic.go), GPL-3.0.
 *
 * Uses QQ Music's public web endpoints: a search to identify the track, then the lyric
 * endpoint which returns a base64-encoded LRC payload.
 */

package app.hush.music.lyrics

import android.content.Context
import android.util.Base64
import app.hush.music.constants.EnableQQLyricsKey
import app.hush.music.utils.dataStore
import app.hush.music.utils.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object QQMusicLyricsProvider : LyricsProvider {
    override val name = "QQ Music"

    private const val SEARCH_URL = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
    private const val LYRICS_URL = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Serializable
    private data class SearchResult(val mid: String = "", val id: Long = 0, val name: String = "", val interval: Int = 0, val singer: List<Singer> = emptyList())

    @Serializable
    private data class Singer(val name: String = "")

    @Serializable
    private data class SearchSongList(val list: List<SearchResult> = emptyList())

    @Serializable
    private data class SearchData(val song: SearchSongList = SearchSongList())

    @Serializable
    private data class SearchResponse(val code: Int = -1, val data: SearchData = SearchData())

    @Serializable
    private data class LyricsResponse(val retcode: Int = -1, val code: Int = -1, val lyric: String = "")

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableQQLyricsKey] ?: false

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val match = searchSong(title, artist, duration) ?: throw IOException("No matching song on QQ Music")
                val params =
                    mapOf(
                        "format" to "json",
                        "inCharset" to "utf8",
                        "outCharset" to "utf-8",
                        "notice" to "0",
                        "platform" to "yqq.json",
                        "needNewCode" to "0",
                        "songmid" to match.mid,
                        "songid" to match.id.toString(),
                    )
                val raw = fetchBody(LYRICS_URL, params)
                val response = json.decodeFromString(LyricsResponse.serializer(), raw)
                if (response.retcode != 0 || response.code != 0) {
                    throw IOException("QQ Music lyrics returned code ${response.code}")
                }
                val lrc = decodeLyric(response.lyric)
                if (lrc.isBlank()) throw IOException("QQ Music returned empty lyrics")
                lrc
            }
        }

    private fun searchSong(
        trackName: String,
        artistName: String,
        durationSec: Int,
    ): SearchResult? {
        val query = "$trackName $artistName".trim()
        if (query.isEmpty()) return null
        val params =
            mapOf(
                "format" to "json",
                "inCharset" to "utf8",
                "outCharset" to "utf8",
                "platform" to "yqq.json",
                "new_json" to "1",
                "w" to query,
                "p" to "1",
                "n" to "20",
                "t" to "0",
                "aggr" to "1",
                "cr" to "1",
                "catZhida" to "1",
                "lossless" to "1",
                "flag_qc" to "0",
                "remoteplace" to "txt.yqq.center",
                "needNewCode" to "0",
            )
        val raw = fetchBody(SEARCH_URL, params)
        val response = json.decodeFromString(SearchResponse.serializer(), raw)
        if (response.code != 0) throw IOException("QQ Music search returned code ${response.code}")

        return response.data.song.list
            .filter { it.mid.isNotBlank() }
            .filter { titleMatches(it.name, trackName) && artistMatches(it.singer.joinToString(", ") { s -> s.name }, artistName) }
            .maxByOrNull { candidate ->
                val durationDelta = abs(candidate.interval - durationSec)
                val durationScore = when {
                    durationDelta <= 3 -> 2
                    durationDelta <= 10 -> 1
                    else -> 0
                }
                durationScore + (if (normalized(candidate.name) == normalized(trackName)) 1 else 0)
            }
    }

    private fun fetchBody(
        endpoint: String,
        params: Map<String, String>,
    ): String {
        val url = endpoint.toHttpUrlOrNull()?.newBuilder() ?: throw IOException("Invalid QQ Music endpoint")
        params.forEach { (key, value) -> url.addQueryParameter(key, value) }
        val request =
            Request.Builder()
                .url(url.build())
                .header("Accept", "application/json")
                .header("Referer", "https://y.qq.com/")
                .header("User-Agent", USER_AGENT)
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("QQ Music returned HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) throw IOException("QQ Music returned an empty response")
            return body
        }
    }

    private fun decodeLyric(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("[")) return trimmed
        return try {
            String(Base64.decode(trimmed, Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (e: IllegalArgumentException) {
            ""
        }
    }

    private fun normalized(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

    private fun titleMatches(
        candidate: String,
        query: String,
    ): Boolean {
        val nc = normalized(candidate)
        val nq = normalized(query)
        if (nc.isEmpty() || nq.isEmpty()) return false
        return nc == nq || nc.contains(nq) || nq.contains(nc)
    }

    private fun artistMatches(
        candidate: String,
        query: String,
    ): Boolean {
        val nc = normalized(candidate)
        val nq = normalized(query)
        if (nq.isEmpty()) return true
        if (nc.isEmpty()) return false
        return nc.contains(nq) || nq.contains(nc)
    }
}
