package app.hush.music.spotiflac

import app.hush.music.innertube.models.response.PlayerResponse
import app.hush.music.utils.YTPlayerUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

object SpotiFLACPlaybackResolver {

    private const val TAG = "SpotiFLACResolver"
    const val SPOTIFLAC_ITAG = -8001
    const val SPOTIFLAC_AUTH_FINGERPRINT = "spotiflac:relay"

    enum class Quality(val apiValue: String, val label: String) {
        FLAC("flac", "Lossless FLAC"),
        HIGH("320", "High (320 kbps)"),
        MEDIUM("160", "Medium (160 kbps)"),
        LOW("96", "Low (96 kbps)"),
        BEST("best", "Best Available"),
    }

    data class TrackIdentity(
        val title: String,
        val artists: List<String>,
        val durationSeconds: Int?,
        val spotifyTrackId: String? = null,
        val isrc: String? = null,
    )

    suspend fun resolve(
        identity: TrackIdentity,
        client: SpotiFLACClient,
        quality: Quality = Quality.BEST,
        enabledSourceIds: List<String> = emptyList(),
    ): Result<YTPlayerUtils.PlaybackData> = withContext(Dispatchers.IO) {
        val sourcesToTry = if (enabledSourceIds.isNotEmpty()) {
            enabledSourceIds
        } else {
            listOf("tidal", "deezer", "qobuz", "amazon", "soundcloud", "apple-music")
        }

        for (sourceId in sourcesToTry) {
            val result = resolveWithSource(identity, client, quality, sourceId)
            if (result != null) {
                Timber.tag(TAG).d("Resolved via source $sourceId: ${result.streamUrl?.take(60)}")
                return@withContext Result.success(result)
            }
        }

        val fallbackResult = resolveWithSource(identity, client, quality, null)
        if (fallbackResult != null) {
            return@withContext Result.success(fallbackResult)
        }

        return@withContext Result.failure(SpotiFLACException("Could not resolve track via SpotiFLAC"))
    }

    private suspend fun resolveWithSource(
        identity: TrackIdentity,
        client: SpotiFLACClient,
        quality: Quality,
        sourceId: String?,
    ): YTPlayerUtils.PlaybackData? {
        val qualitiesToTry = buildQualityCascade(quality)

        for (q in qualitiesToTry) {
            val response = trySingleQuality(identity, client, q, sourceId)
            if (response != null) {
                Timber.tag(TAG).d("Resolved with quality ${q.apiValue} source $sourceId: ${response.title}")
                val result = buildPlaybackData(response)
                if (result.isSuccess) {
                    return result.getOrNull()
                }
            }
        }

        return null
    }

    private fun buildQualityCascade(preferred: Quality): List<Quality> {
        val cascade = mutableListOf(preferred)
        for (q in Quality.entries) {
            if (q != preferred && q != Quality.BEST) {
                cascade.add(q)
            }
        }
        if (preferred != Quality.BEST) {
            cascade.add(Quality.BEST)
        }
        return cascade.distinct()
    }

    private suspend fun trySingleQuality(
        identity: TrackIdentity,
        client: SpotiFLACClient,
        quality: Quality,
        sourceId: String?,
    ): SpotiFLACTrackResponse? {
        if (!identity.spotifyTrackId.isNullOrBlank()) {
            val result = runCatching {
                client.resolveTrack(identity.spotifyTrackId, quality.apiValue, sourceId).getOrNull()
            }.getOrNull()
            if (result != null && !result.url.isNullOrBlank()) {
                return result
            }
        }

        if (!identity.isrc.isNullOrBlank()) {
            val result = runCatching {
                client.resolveByISRC(identity.isrc, quality.apiValue, sourceId).getOrNull()
            }.getOrNull()
            if (result != null && !result.url.isNullOrBlank()) {
                return result
            }
        }

        val searchQuery = buildSearchQuery(identity)
        if (searchQuery.isNotBlank()) {
            val searchResults = runCatching {
                client.search(searchQuery, sourceId).getOrNull()
            }.getOrNull()

            if (!searchResults.isNullOrEmpty()) {
                val bestMatch = findBestMatch(searchResults, identity)
                if (bestMatch != null && !bestMatch.id.isNullOrBlank()) {
                    val result = runCatching {
                        client.resolveTrack(bestMatch.id, quality.apiValue, sourceId).getOrNull()
                    }.getOrNull()
                    if (result != null && !result.url.isNullOrBlank()) {
                        return result
                    }
                }
            }
        }

        return null
    }

    private fun buildSearchQuery(identity: TrackIdentity): String {
        val title = identity.title
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\[.*?\\]"), "")
            .replace(Regex("feat\\.?.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("ft\\.?.*", RegexOption.IGNORE_CASE), "")
            .trim()

        val artist = identity.artists.firstOrNull()?.trim() ?: ""

        return if (artist.isNotBlank() && title.isNotBlank()) {
            "$artist $title"
        } else if (title.isNotBlank()) {
            title
        } else {
            ""
        }
    }

    private fun findBestMatch(
        results: List<SpotiFLACSearchResult>,
        identity: TrackIdentity,
    ): SpotiFLACSearchResult? {
        val targetTitle = identity.title.lowercase().trim()
        val targetArtist = identity.artists.firstOrNull()?.lowercase()?.trim() ?: ""

        val scored = results.map { result ->
            var score = 0

            val resultTitle = result.title?.lowercase()?.trim() ?: ""
            if (resultTitle == targetTitle) {
                score -= 100
            } else if (resultTitle.contains(targetTitle) || targetTitle.contains(resultTitle)) {
                score -= 50
            }

            val resultArtist = result.artist?.lowercase()?.trim() ?: ""
            if (targetArtist.isNotBlank() && resultArtist.isNotBlank()) {
                if (resultArtist == targetArtist) {
                    score -= 80
                } else if (resultArtist.contains(targetArtist) || targetArtist.contains(resultArtist)) {
                    score -= 40
                }
            }

            if (identity.durationSeconds != null && result.duration != null) {
                val diff = kotlin.math.abs(identity.durationSeconds - result.duration)
                if (diff <= 3) score -= 30
                else if (diff <= 10) score -= 15
            }

            result to score
        }

        val best = scored.minByOrNull { it.second } ?: return null
        if (best.second > -10) {
            Timber.tag(TAG).d("Best match score too low (${best.second}), skipping")
            return null
        }
        return best.first
    }

    private fun buildPlaybackData(response: SpotiFLACTrackResponse): Result<YTPlayerUtils.PlaybackData> {
        val streamUrl = response.url
            ?: return Result.failure(SpotiFLACException("No stream URL in response"))

        val mimeType = when (response.format?.lowercase()) {
            "flac" -> "audio/flac"
            "opus" -> "audio/opus"
            "aac" -> "audio/mp4a-latm"
            "mp3" -> "audio/mpeg"
            else -> "audio/flac"
        }

        val bitrate = response.bitrate ?: 1411

        val format = PlayerResponse.StreamingData.Format(
            itag = SPOTIFLAC_ITAG,
            url = streamUrl,
            mimeType = mimeType,
            bitrate = bitrate,
            width = null,
            height = null,
            contentLength = null,
            quality = response.quality ?: "Lossless",
            fps = null,
            qualityLabel = response.quality ?: "Lossless",
            averageBitrate = bitrate,
            audioQuality = response.quality,
            approxDurationMs = ((response.duration ?: 0) * 1000L).toString(),
            audioSampleRate = 44100,
            audioChannels = 2,
            loudnessDb = null,
            lastModified = null,
            signatureCipher = null,
            cipher = null,
        )

        val playbackData = YTPlayerUtils.PlaybackData(
            audioConfig = null,
            videoDetails = null,
            playbackTracking = null,
            format = format,
            streamUrl = streamUrl,
            streamExpiresInSeconds = 3600,
            authFingerprint = SPOTIFLAC_AUTH_FINGERPRINT,
            playbackClientLabel = "SpotiFLAC - ${response.source ?: response.quality ?: "Lossless"}",
            isYouTubeStream = false,
        )

        Timber.tag(TAG).d("Built PlaybackData: ${response.title} by ${response.artist} [${response.source}] ${response.quality}")
        return Result.success(playbackData)
    }
}
