/*
 * Hush — GPL-3.0
 * JioSaavn playback resolver adapted from Vivi Music (GPL-3.0), v6.0.3.
 *
 * Library, likes, playlists, and sync stay on YouTube Music — only the audio stream
 * URL is resolved from JioSaavn when enabled and a confident match is found.
 */

package moe.rukamori.archivetune.playback

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import moe.rukamori.archivetune.constants.EnableSaavnStreamingKey
import moe.rukamori.archivetune.constants.SaavnAudioQuality
import moe.rukamori.archivetune.constants.SaavnAudioQualityKey
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import moe.rukamori.archivetune.jiosaavn.SaavnService
import moe.rukamori.archivetune.jiosaavn.SaavnSong
import moe.rukamori.archivetune.utils.YTPlayerUtils
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import timber.log.Timber
import java.util.Locale
import kotlin.math.abs

object SaavnPlaybackResolver {
    private const val TAG = "SaavnPlayback"
    private const val MIN_MATCH_SCORE = 6
    private const val DURATION_TOLERANCE_SEC = 4

    suspend fun tryResolve(
        context: Context,
        videoId: String,
        playlistId: String? = null,
    ): YTPlayerUtils.PlaybackData? {
        if (!context.dataStore.get(EnableSaavnStreamingKey, false)) return null

        return runCatching {
            val qualityKey = context.dataStore.get(SaavnAudioQualityKey, SaavnAudioQuality.QUALITY_320.name)
            val quality =
                runCatching { SaavnAudioQuality.valueOf(qualityKey) }
                    .getOrDefault(SaavnAudioQuality.QUALITY_320)

            val (trackContext, metadata) =
                coroutineScope {
                    val nextDeferred = async {
                        YouTube.next(WatchEndpoint(videoId = videoId)).getOrNull()
                    }
                    val metaDeferred = async {
                        YTPlayerUtils.playerResponseForMetadata(videoId, playlistId).getOrNull()
                    }
                    nextDeferred.await() to metaDeferred.await()
                }

            val currentSong =
                trackContext?.items?.getOrNull(trackContext.currentIndex ?: 0)
                    ?: trackContext?.items?.firstOrNull()

            val title =
                currentSong?.title?.takeIf { it.isNotBlank() }
                    ?: metadata?.videoDetails?.title.orEmpty()
            if (title.isBlank()) return@runCatching null

            val artistNames: List<String> =
                if (currentSong?.artists?.isNotEmpty() == true) {
                    currentSong.artists.map { it.name }
                } else {
                    listOfNotNull(
                        metadata
                            ?.videoDetails
                            ?.author
                            ?.replace(Regex("(?i)\\s*-\\s*topic\\b"), "")
                            ?.replace(Regex("(?i)\\s*vevo\\b"), "")
                            ?.trim()
                            ?.takeIf { it.isNotBlank() },
                    )
                }

            val albumName = currentSong?.album?.name.orEmpty()
            val wantedDurationSec =
                currentSong?.duration?.takeIf { it > 0 }
                    ?: metadata?.videoDetails?.lengthSeconds?.toIntOrNull()?.takeIf { it > 0 }

            Timber.tag(TAG).d("Resolving Saavn for videoId=%s title=\"%s\" artists=%s", videoId, title, artistNames)

            val queries = buildSearchQueries(title, artistNames, albumName)
            var bestSong: SaavnSong? = null
            var bestScore = 0

            for (query in queries) {
                val candidates = SaavnService.searchSongs(query).getOrNull().orEmpty()
                for (candidate in candidates) {
                    val score = scoreCandidate(candidate, title, artistNames, albumName, wantedDurationSec)
                    if (score > bestScore) {
                        bestScore = score
                        bestSong = candidate
                    }
                }
                if (bestScore >= MIN_MATCH_SCORE + 2) break
            }

            if (bestSong == null || bestScore < MIN_MATCH_SCORE) {
                Timber.tag(TAG).d("No confident Saavn match for videoId=%s (bestScore=%d)", videoId, bestScore)
                return@runCatching null
            }

            val streamUrl =
                SaavnService.resolveStreamUrl(bestSong, quality.toApiValue())
                    ?: return@runCatching null

            Timber.tag(TAG).i(
                "Streaming from JioSaavn: \"%s\" (score=%d, quality=%s) for YT videoId=%s",
                bestSong.name,
                bestScore,
                quality.toApiValue(),
                videoId,
            )

            buildPlaybackData(
                metadata = metadata,
                streamUrl = streamUrl,
                quality = quality,
            )
        }.onFailure {
            Timber.tag(TAG).w(it, "Saavn resolve failed for videoId=%s — will use YouTube", videoId)
        }.getOrNull()
    }

    private fun buildSearchQueries(
        title: String,
        artists: List<String>,
        album: String,
    ): List<String> {
        val artist = artists.joinToString(" ")
        val normalizedTitle = normalizeQuery(title)
        val normalizedArtist = normalizeQuery(artist)
        val normalizedAlbum = normalizeQuery(album)

        return buildList {
            if (normalizedAlbum.isNotBlank()) {
                add("$normalizedAlbum $normalizedTitle $normalizedArtist".trim())
            }
            add("$normalizedTitle $normalizedArtist".trim())
            add(normalizedTitle)
        }.distinct().filter { it.isNotBlank() }
    }

    private fun normalizeQuery(value: String): String =
        value
            .replace("&", " ")
            .replace(",", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun normalizeMatch(value: String): String =
        value
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun scoreCandidate(
        candidate: SaavnSong,
        wantedTitle: String,
        wantedArtists: List<String>,
        wantedAlbum: String,
        wantedDurationSec: Int?,
    ): Int {
        var score = 0
        val candidateTitle = normalizeMatch(candidate.name)
        val wantedTitleNorm = normalizeMatch(wantedTitle)
        if (candidateTitle.isBlank() || wantedTitleNorm.isBlank()) return 0

        if (candidateTitle == wantedTitleNorm) {
            score += 5
        } else if (candidateTitle.contains(wantedTitleNorm) || wantedTitleNorm.contains(candidateTitle)) {
            score += 3
        } else {
            return 0
        }

        val candidateArtists = candidate.artists.primary.map { normalizeMatch(it.name) }.filter { it.isNotBlank() }
        val wantedArtistNorms = wantedArtists.map { normalizeMatch(it) }.filter { it.isNotBlank() }
        if (wantedArtistNorms.isEmpty()) {
            score += 1
        } else {
            val artistHits =
                wantedArtistNorms.count { wanted ->
                    candidateArtists.any { candidateArtist ->
                        candidateArtist.contains(wanted) || wanted.contains(candidateArtist)
                    }
                }
            if (artistHits == 0) return 0
            score += artistHits.coerceAtMost(2) * 2
        }

        val wantedAlbumNorm = normalizeMatch(wantedAlbum)
        val candidateAlbumNorm = normalizeMatch(candidate.album?.name.orEmpty())
        if (wantedAlbumNorm.isNotBlank() && candidateAlbumNorm.isNotBlank()) {
            if (candidateAlbumNorm == wantedAlbumNorm ||
                candidateAlbumNorm.contains(wantedAlbumNorm) ||
                wantedAlbumNorm.contains(candidateAlbumNorm)
            ) {
                score += 2
            }
        }

        val candidateDuration = candidate.duration
        if (wantedDurationSec != null && candidateDuration != null && candidateDuration > 0) {
            if (abs(candidateDuration - wantedDurationSec) <= DURATION_TOLERANCE_SEC) {
                score += 2
            } else if (abs(candidateDuration - wantedDurationSec) > 12) {
                score -= 2
            }
        }

        return score
    }

    private fun buildPlaybackData(
        metadata: PlayerResponse?,
        streamUrl: String,
        quality: SaavnAudioQuality,
    ): YTPlayerUtils.PlaybackData {
        val format =
            PlayerResponse.StreamingData.Format(
                itag =
                    when (quality) {
                        SaavnAudioQuality.QUALITY_320 -> 141
                        SaavnAudioQuality.QUALITY_160 -> 140
                        SaavnAudioQuality.QUALITY_96 -> 139
                    },
                url = streamUrl,
                mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
                bitrate =
                    when (quality) {
                        SaavnAudioQuality.QUALITY_320 -> 320_000
                        SaavnAudioQuality.QUALITY_160 -> 160_000
                        SaavnAudioQuality.QUALITY_96 -> 96_000
                    },
                width = null,
                height = null,
                contentLength = null,
                quality = quality.toApiValue(),
                fps = null,
                qualityLabel = quality.toLabel(),
                averageBitrate = null,
                audioQuality = quality.toApiValue(),
                approxDurationMs = metadata?.videoDetails?.lengthSeconds?.toLongOrNull()?.times(1000L)?.toString(),
                audioSampleRate = null,
                audioChannels = null,
                loudnessDb = metadata?.playerConfig?.audioConfig?.loudnessDb,
                lastModified = null,
                signatureCipher = null,
                cipher = null,
            )

        return YTPlayerUtils.PlaybackData(
            audioConfig = metadata?.playerConfig?.audioConfig,
            videoDetails = metadata?.videoDetails,
            playbackTracking = metadata?.playbackTracking,
            format = format,
            streamUrl = streamUrl,
            streamExpiresInSeconds = 3600,
            authFingerprint = YouTube.currentPlaybackAuthState().fingerprint,
            playbackClientLabel = "JioSaavn",
            isSaavnStream = true,
        )
    }
}
