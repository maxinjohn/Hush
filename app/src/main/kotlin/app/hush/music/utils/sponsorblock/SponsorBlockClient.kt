package app.hush.music.utils.sponsorblock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object SponsorBlockClient {
    private const val API_BASE = "https://sponsor.ajay.app"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    private val defaultCategories = setOf(
        SegmentCategory.SPONSOR,
        SegmentCategory.INTRO,
        SegmentCategory.OUTRO,
        SegmentCategory.SELF_PROMO,
        SegmentCategory.INTERACTION,
    )

    suspend fun getSegments(
        videoId: String,
        categories: Set<SegmentCategory> = defaultCategories,
    ): Result<List<SponsorBlockSegment>> = withContext(Dispatchers.IO) {
        runCatching {
            val categoryParam = categories.joinToString(",") {
                when (it) {
                    SegmentCategory.UNKNOWN -> ""
                    else -> it.name.lowercase()
                }
            }.trim(',')

            if (categoryParam.isBlank()) return@runCatching emptyList()

            val url = "$API_BASE/api/skipSegments?videoID=$videoId&category=$categoryParam"
            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", "Hush")
                .build()

            val response = httpClient.newCall(request).execute()
            response.use { resp ->
                if (resp.code == 204) return@runCatching emptyList()
                if (!resp.isSuccessful) return@runCatching emptyList()

                val body = resp.body?.string() ?: return@runCatching emptyList()
                val segments: List<SponsorBlockSegment> = json.decodeFromString(body)
                segments.filter { it.segment.size >= 2 }
            }
        }
    }

    suspend fun getSegmentsForVideoIds(
        videoIds: List<String>,
        categories: Set<SegmentCategory> = defaultCategories,
    ): Result<Map<String, List<SponsorBlockSegment>>> = withContext(Dispatchers.IO) {
        runCatching {
            val categoryParam = categories.joinToString(",") {
                when (it) {
                    SegmentCategory.UNKNOWN -> ""
                    else -> it.name.lowercase()
                }
            }.trim(',')

            if (categoryParam.isBlank() || videoIds.isEmpty()) return@runCatching emptyMap()

            val bodyJson = buildString {
                append("{\"videoID\":[")
                append(videoIds.joinToString(",") { "\"$it\"" })
                append("],\"category\":\"$categoryParam\"}")
            }

            val request = Request.Builder()
                .url("$API_BASE/api/skipSegments")
                .post(bodyJson.toRequestBody(mediaType))
                .header("User-Agent", "Hush")
                .build()

            val response = httpClient.newCall(request).execute()
            response.use { resp ->
                if (resp.code == 204) return@runCatching emptyMap()
                if (!resp.isSuccessful) return@runCatching emptyMap()

                val body = resp.body?.string() ?: return@runCatching emptyMap()
                val segments: List<SponsorBlockSegment> = json.decodeFromString(body)
                segments.groupBy { it.UUID.take(11) }
            }
        }
    }
}
