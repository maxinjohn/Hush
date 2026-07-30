/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.lyrics

import android.content.Context
import android.net.Uri
import app.hush.music.constants.EnableMegalobizLyricsKey
import app.hush.music.utils.dataStore
import app.hush.music.utils.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object MegalobizLyricsProvider : LyricsProvider {
    override val name = "Megalobiz"

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

    private const val SEARCH_URL = "https://www.megalobiz.com/search/all"
    private const val BASE_URL = "https://www.megalobiz.com"

    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnableMegalobizLyricsKey] ?: false

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val searchQuery = "$artist $title"
                val encodedQuery = Uri.encode(searchQuery)
                val searchRequest =
                    Request
                        .Builder()
                        .url("$SEARCH_URL?qry=$encodedQuery&searchButton.x=0&searchButton.y=0")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                        .build()

                val searchHtml =
                    client.newCall(searchRequest).execute().use { response ->
                        response.body?.string()
                    } ?: return@withContext Result.failure(Exception("Empty response"))

                val linkPattern = Regex("""href="(/lrc/maker/[^"]+)"""")
                val titlePattern = Regex("""<a[^>]*href="/lrc/maker/[^"]*"[^>]*>([^<]+)</a>""")

                val links = linkPattern.findAll(searchHtml).map { it.groupValues[1] }.toList()
                val titles = titlePattern.findAll(searchHtml).map { it.groupValues[1].replace('_', ' ').trim() }.toList()

                val matchIndex =
                    links.indices.firstOrNull { idx ->
                        val linkTitle = titles.getOrElse(idx) { "" }
                        artist.lowercase() in linkTitle.lowercase() &&
                            title.lowercase() in linkTitle.lowercase()
                    }

                if (matchIndex == null) {
                    return@withContext Result.failure(Exception("No matching lyrics found"))
                }

                val lyricPath = links[matchIndex]
                val lyricRequest =
                    Request
                        .Builder()
                        .url("$BASE_URL$lyricPath")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                        .build()

                val lyricHtml =
                    client.newCall(lyricRequest).execute().use { response ->
                        response.body?.string()
                    } ?: return@withContext Result.failure(Exception("Empty lyrics response"))

                val lyricsMatch = Regex("""<span id="lrc_\d+_lyrics">(.*?)</span>""", RegexOption.DOT_MATCHES_ALL).find(lyricHtml)
                if (lyricsMatch != null) {
                    val rawLyrics = lyricsMatch.groupValues[1]
                    val cleanLyrics = rawLyrics.replace(Regex("<[^<]+?>"), "").trim()
                    if (cleanLyrics.isNotBlank()) {
                        return@withContext Result.success(cleanLyrics)
                    }
                }

                Result.failure(Exception("Could not extract lyrics"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
