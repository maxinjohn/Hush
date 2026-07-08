/*
 * Hush — GPL-3.0
 * JioSaavn playback resolver adapted from Vivi Music (GPL-3.0), v6.0.3.
 *
 * Library, likes, playlists, and sync stay on YouTube Music — only the audio stream
 * URL is resolved from JioSaavn when enabled and a confident match is found.
 */

package app.hush.music.playback

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import app.hush.music.constants.EnableSaavnStreamingKey
import app.hush.music.constants.SaavnAudioQuality
import app.hush.music.constants.SaavnAudioQualityKey
import app.hush.music.innertube.YouTube
import app.hush.music.innertube.models.WatchEndpoint
import app.hush.music.innertube.models.response.PlayerResponse
import app.hush.music.jiosaavn.SaavnService
import app.hush.music.jiosaavn.SaavnSong
import app.hush.music.models.MediaMetadata
import app.hush.music.utils.PreferenceStore
import app.hush.music.utils.YTPlayerUtils
import app.hush.music.utils.dataStore
import app.hush.music.utils.getAsync
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import androidx.media3.common.MediaMetadata as ExoMediaMetadata

object SaavnPlaybackResolver {
    private const val TAG = "SaavnPlayback"
    private val matchCache = ConcurrentHashMap<String, SaavnSong?>()
    private const val MATCH_CACHE_MAX_SIZE = 1000
    private val streamUrlCache = ConcurrentHashMap<String, StreamUrlCacheEntry>()
    private const val STREAM_URL_TTL_MS = 6 * 60 * 60 * 1000L // 6 hours

    private data class StreamUrlCacheEntry(
        val url: String,
        val expiresAt: Long,
    )

    data class PlaybackHints(
        val title: String? = null,
        val artists: List<String> = emptyList(),
        val album: String? = null,
        val durationSec: Int? = null,
    )

    suspend fun isEnabled(context: Context): Boolean =
        readSaavnEnabled(context)

    suspend fun tryResolve(
        context: Context,
        videoId: String,
        playlistId: String? = null,
        hints: PlaybackHints? = null,
    ): YTPlayerUtils.PlaybackData? {
        if (!readSaavnEnabled(context)) {
            Timber.tag(TAG).i("JioSaavn streaming disabled — using YouTube for %s", videoId)
            return null
        }

        return runCatching {
            val qualityKey =
                context.dataStore.getAsync(SaavnAudioQualityKey)
                    ?: SaavnAudioQuality.QUALITY_320.name
            val quality =
                runCatching { SaavnAudioQuality.valueOf(qualityKey) }
                    .getOrDefault(SaavnAudioQuality.QUALITY_320)

            var metadata: PlayerResponse? = null
            var title =
                hints?.title
                    ?.let(::cleanTitleForSearch)
                    ?.takeIf { it.isNotBlank() }
            var artistNames =
                hints
                    ?.artists
                    ?.map(::cleanArtistForSearch)
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
            var albumName = hints?.album?.trim().orEmpty()

            if (title.isNullOrBlank()) {
                val (trackContext, fetchedMetadata) =
                    coroutineScope {
                        val nextDeferred = async {
                            YouTube.next(WatchEndpoint(videoId = videoId)).getOrNull()
                        }
                        val metaDeferred = async {
                            YTPlayerUtils.playerResponseForMetadata(videoId, playlistId).getOrNull()
                        }
                        nextDeferred.await() to metaDeferred.await()
                    }
                metadata = fetchedMetadata

                val currentSong =
                    trackContext?.items?.getOrNull(trackContext.currentIndex ?: 0)
                        ?: trackContext?.items?.firstOrNull()

                title =
                    cleanTitleForSearch(
                        currentSong?.title?.takeIf { it.isNotBlank() }
                            ?: metadata?.videoDetails?.title.orEmpty(),
                    ).takeIf { it.isNotBlank() }

                if (artistNames.isEmpty()) {
                    artistNames =
                        if (currentSong?.artists?.isNotEmpty() == true) {
                            currentSong.artists.map { cleanArtistForSearch(it.name) }
                        } else {
                            listOfNotNull(
                                metadata
                                    ?.videoDetails
                                    ?.author
                                    ?.let(::cleanArtistForSearch)
                                    ?.takeIf { it.isNotBlank() },
                            )
                        }
                }

                if (albumName.isBlank()) {
                    albumName = currentSong?.album?.name.orEmpty()
                }
            }

            if (title.isNullOrBlank()) {
                Timber.tag(TAG).w("No title for Saavn lookup (videoId=%s hints=%s)", videoId, hints)
                return@runCatching null
            }

            if (metadata == null) {
                metadata = YTPlayerUtils.playerResponseForMetadata(videoId, playlistId).getOrNull()
            }

            Timber.tag(TAG).i(
                "Resolving Saavn for videoId=%s title=\"%s\" artists=%s album=\"%s\"",
                videoId,
                title,
                artistNames,
                albumName,
            )

            val bestSong =
                matchCache[videoId]
                    ?: findBestMatch(
                        title = title,
                        artistNames = artistNames,
                        albumName = albumName,
                        expectedDurationSec =
                            hints?.durationSec?.takeIf { it > 0 }
                                ?: metadata?.videoDetails?.lengthSeconds?.toIntOrNull()?.takeIf { it > 0 },
                    ).also { result ->
                        if (matchCache.size >= MATCH_CACHE_MAX_SIZE) matchCache.clear()
                        matchCache[videoId] = result
                    } ?: run {
                        Timber.tag(TAG).w("No Saavn match for videoId=%s title=\"%s\"", videoId, title)
                        return@runCatching null
                    }

            val streamUrl =
                streamUrlCache[videoId]
                    ?.takeIf { it.expiresAt > System.currentTimeMillis() }
                    ?.url
                    ?: SaavnService.resolveStreamUrl(bestSong, quality.toApiValue())
                        ?.also { url ->
                            if (streamUrlCache.size >= MATCH_CACHE_MAX_SIZE) streamUrlCache.clear()
                            streamUrlCache[videoId] = StreamUrlCacheEntry(
                                url = url,
                                expiresAt = System.currentTimeMillis() + STREAM_URL_TTL_MS,
                            )
                        }
                    ?: run {
                        Timber.tag(TAG).w("Saavn match found but stream URL missing for id=%s", bestSong.id)
                        return@runCatching null
                    }

            Timber.tag(TAG).i(
                "Streaming from JioSaavn: \"%s\" (quality=%s) for YT videoId=%s",
                bestSong.name,
                quality.toApiValue(),
                videoId,
            )

            buildPlaybackData(
                metadata = metadata,
                streamUrl = streamUrl,
                quality = quality,
            )
        }.onFailure {
            Timber.tag(TAG).e(it, "Saavn resolve failed for videoId=%s — using YouTube", videoId)
        }.getOrNull()
    }

    fun hintsFrom(mediaMetadata: MediaMetadata?): PlaybackHints? {
        if (mediaMetadata == null) return null
        return PlaybackHints(
            title = mediaMetadata.title,
            artists = mediaMetadata.artists.map { it.name }.filter { it.isNotBlank() },
            album = mediaMetadata.album?.title,
            durationSec = mediaMetadata.duration.takeIf { it > 0 },
        )
    }

    fun hintsFrom(mediaMetadata: ExoMediaMetadata?): PlaybackHints? {
        if (mediaMetadata == null) return null
        val title = mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val artistField = mediaMetadata.artist?.toString().orEmpty()
        val artists =
            artistField
                .split(',', '&', '·', ';')
                .map { it.trim() }
                .filter { it.isNotBlank() }
        return PlaybackHints(
            title = title,
            artists = artists,
            album = mediaMetadata.albumTitle?.toString(),
            durationSec = null,
        )
    }

    private suspend fun readSaavnEnabled(context: Context): Boolean {
        val fromStore = context.dataStore.getAsync(EnableSaavnStreamingKey)
        if (fromStore != null) return fromStore
        return PreferenceStore.get(EnableSaavnStreamingKey) ?: false
    }

    private suspend fun findBestMatch(
        title: String,
        artistNames: List<String>,
        albumName: String,
        expectedDurationSec: Int? = null,
    ): SaavnSong? {
        val wantedTitleLower = cleanTitleForSearch(title).lowercase(Locale.US)
        if (wantedTitleLower.isBlank()) return null
        val wantedArtistsLower =
            artistNames
                .map { cleanArtistForSearch(it).lowercase(Locale.US) }
                .filter { it.isNotBlank() }
        val artistQuery = artistNames.joinToString(" ")

        val queries =
            buildList {
                if (albumName.isNotBlank()) {
                    add(normalizeQuery("$albumName $title $artistQuery"))
                }
                add(normalizeQuery("$title $artistQuery"))
                add(normalizeQuery(title))
                if (artistQuery.isNotBlank()) {
                    add(normalizeQuery(artistQuery))
                }
            }.distinct().filter { it.isNotBlank() }

        var bestLooseMatch: SaavnSong? = null

        for (query in queries) {
            val candidates = SaavnService.searchSongs(query, limit = 15).getOrNull().orEmpty()
            Timber.tag(TAG).d("Saavn search \"%s\" -> %d candidates", query, candidates.size)

            candidates.firstOrNull { candidate ->
                matchesCandidate(
                    candidate = candidate,
                    wantedTitleLower = wantedTitleLower,
                    wantedArtistsLower = wantedArtistsLower,
                    expectedDurationSec = expectedDurationSec,
                    strict = true,
                )
            }?.let { return it }

            candidates.firstOrNull { candidate ->
                matchesCandidate(
                    candidate = candidate,
                    wantedTitleLower = wantedTitleLower,
                    wantedArtistsLower = wantedArtistsLower,
                    expectedDurationSec = expectedDurationSec,
                    strict = false,
                )
            }?.let { loose ->
                if (bestLooseMatch == null) bestLooseMatch = loose
            }

            if (bestLooseMatch == null) {
                candidates.firstOrNull { candidate ->
                    val dur = candidate.duration
                    cleanTitleForSearch(candidate.name).lowercase(Locale.US) ==
                        wantedTitleLower &&
                        (expectedDurationSec == null ||
                            dur == null ||
                            kotlin.math.abs(expectedDurationSec - dur) <= 10)
                }?.let { bestLooseMatch = it }
            }
        }

        return bestLooseMatch
    }

    private fun cleanTitleForSearch(title: String): String =
        title
            .replace(Regex("(?i)\\s*\\(.*?official.*?\\)\\s*"), " ")
            .replace(Regex("(?i)\\s*\\[.*?official.*?\\]\\s*"), " ")
            .replace(Regex("(?i)\\s*\\(.*?lyrics?\\)\\s*"), " ")
            .replace(Regex("(?i)\\s*\\(.*?video\\)\\s*"), " ")
            .replace(Regex("(?i)\\s*\\(.*?audio\\)\\s*"), " ")
            .replace(Regex("(?i)\\s*\\(.*?music\\)\\s*"), " ")
            .replace(Regex("(?i)\\s*\\(.*?explicit\\)\\s*"), " ")
            .replace(Regex("(?i)\\s+\\(?ft\\.?\\s+.*$"), "")
            .replace(Regex("(?i)\\s+\\(?feat\\.?\\s+.*$"), "")
            .replace(Regex("(?i)\\s+-\\s+topic\\b"), "")
            .replace(Regex("(?i)\\s*\\|\\s*.*$"), "")
            .replace(Regex("(?i)\\s+lyrics\\s+video\\b"), " ")
            .replace(Regex("(?i)\\s+video\\s*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .removeSuffix(",")
            .trim()
            .removeSuffix("-")
            .trim()

    private fun cleanArtistForSearch(artist: String): String =
        artist
            .replace(Regex("(?i)\\s*-\\s*topic\\b"), "")
            .replace(Regex("(?i)\\s*vevo\\b"), "")
            .replace(Regex("(?i)\\s+(ft\\.?|feat\\.?).*$"), "")
            .trim()

    private fun matchesCandidate(
        candidate: SaavnSong,
        wantedTitleLower: String,
        wantedArtistsLower: List<String>,
        expectedDurationSec: Int? = null,
        strict: Boolean,
    ): Boolean {
        val candidateTitleLower = cleanTitleForSearch(candidate.name).lowercase(Locale.US)
        val normalizedWantedTitle = cleanTitleForSearch(wantedTitleLower).lowercase(Locale.US)
        val titleMatches =
            candidateTitleLower == normalizedWantedTitle ||
                candidateTitleLower.contains(normalizedWantedTitle) ||
                normalizedWantedTitle.contains(candidateTitleLower)
        if (!titleMatches) return false

        if (expectedDurationSec != null) {
            val candidateDuration = candidate.duration ?: return false
            val durationDelta = kotlin.math.abs(expectedDurationSec - candidateDuration)
            if (durationDelta > if (strict) 12 else 10) return false
        }

        if (wantedArtistsLower.isEmpty()) return true

        val candidateArtists =
            candidate.artists.primary
                .map { cleanArtistForSearch(it.name).lowercase(Locale.US) }
                .filter { it.isNotBlank() }

        if (candidateArtists.isEmpty()) return !strict

        val artistMatches =
            wantedArtistsLower.any { wanted ->
                candidateArtists.any { candidateArtist ->
                    candidateArtist.contains(wanted) || wanted.contains(candidateArtist)
                }
            }

        return artistMatches || !strict
    }

    private fun normalizeQuery(value: String): String =
        value
            .replace("&", " ")
            .replace(",", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

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
