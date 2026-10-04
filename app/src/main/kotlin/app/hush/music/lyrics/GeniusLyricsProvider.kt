/**
 * Genius lyrics — ported from SpotiFLAC's go_backend (lyrics_genius.go + the Genius search in
 * lyrics_paxsenix.go), GPL-3.0.
 *
 * Searches genius.com's public multi-search API for the song page, then extracts every
 * `data-lyrics-container` div from the page (Genius renders each verse as its own container),
 * preserving document order.
 */

package app.hush.music.lyrics

import android.content.Context
import app.hush.music.constants.EnableGeniusLyricsKey
import app.hush.music.utils.dataStore
import app.hush.music.utils.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import java.util.concurrent.TimeUnit

object GeniusLyricsProvider : LyricsProvider {
    override val name = "Genius"

    private const val SEARCH_URL = "https://genius.com/api/search/multi"
    private const val MAX_PAGE_BYTES = 8 shl 20
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
    private data class HitResult(
        val title: String = "",
        val artist_names: String = "",
        val primary_artist_names: String = "",
        val url: String = "",
    )

    @Serializable
    private data class Hit(val type: String = "", val result: HitResult = HitResult())

    @Serializable
    private data class Section(val hits: List<Hit> = emptyList())

    @Serializable
    private data class SearchResponseBody(val sections: List<Section> = emptyList())

    @Serializable
    private data class SearchResponse(val response: SearchResponseBody = SearchResponseBody())

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableGeniusLyricsKey] ?: false

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val pageUrl = searchSong(title, artist) ?: throw IOException("No songs found on Genius")
                val page = fetchPage(pageUrl)
                extractLyrics(page)
            }
        }

    private fun searchSong(
        trackName: String,
        artistName: String,
    ): String? {
        val query = "$trackName $artistName".trim()
        if (query.isEmpty()) return null
        val url =
            SEARCH_URL.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("q", query)
                ?.addQueryParameter("per_page", "5")
                ?: return null
        val request =
            Request.Builder()
                .url(url.build())
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val parsed = runCatching { json.decodeFromString(SearchResponse.serializer(), body) }.getOrNull() ?: return null

            val candidates =
                parsed.response.sections
                    .flatMap { it.hits }
                    .filter { it.type == "song" && it.result.url.isNotBlank() }
            // Prefer a hit whose title and artist both line up; fall back to a title match so a
            // featured-artist discrepancy doesn't blank the whole provider.
            val titleMatching = candidates.filter { titleMatches(it.result.title, trackName) }
            val best =
                titleMatching.firstOrNull { artistMatches(effectiveArtist(it.result), artistName) }
                    ?: titleMatching.firstOrNull()
            return best?.result?.url?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    private fun effectiveArtist(result: HitResult): String =
        result.primary_artist_names.ifBlank { result.artist_names }

    private fun fetchPage(pageUrl: String): String {
        val request =
            Request.Builder()
                .url(pageUrl)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("User-Agent", USER_AGENT)
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Genius page returned HTTP ${response.code}")
            val bytes = response.body?.bytes() ?: throw IOException("Empty Genius page")
            if (bytes.size > MAX_PAGE_BYTES) throw IOException("Genius page exceeds $MAX_PAGE_BYTES bytes")
            return String(bytes, Charsets.UTF_8)
        }
    }

    private fun extractLyrics(html: String): String {
        val document = Jsoup.parse(html)
        val containers = document.select("[data-lyrics-container=true]")
        if (containers.isEmpty()) throw IOException("Genius page has no lyrics container")

        // Genius renders each verse/chorus as a separate lyrics container. Preserve document
        // order and join all usable containers into one payload.
        val sections =
            containers.map { container ->
                val clone = container.clone()
                clone.select("[data-exclude-from-selection=true]").remove()
                // <br> is how Genius writes line breaks; promote each to a real newline text
                // node so wholeText keeps them.
                clone.select("br").forEach { br: Element -> br.appendText("\n") }
                clone.wholeText().replace('\u00a0', ' ').trim()
            }.filter { it.isNotBlank() }

        if (sections.isEmpty()) throw IOException("Genius page returned empty lyrics")
        return sections.joinToString("\n")
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
