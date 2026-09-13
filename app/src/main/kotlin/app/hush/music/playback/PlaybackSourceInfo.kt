/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

/** Which engine produced the audio currently being played. */
enum class PlaybackEngine {
    SPOTIFLAC,
    HIRES,
    YOUTUBE,
    UNKNOWN,
}

/**
 * How the audio is reaching the speaker right now.
 *
 * This is the distinction the player has to make explicit, because three very different
 * situations were all readable as "on the device":
 *
 *  - [DEVICE_CACHE] — a SpotiFLAC file that was already on disk; nothing was fetched for
 *    this play, and it would still play with the network off.
 *  - [FETCHED_FOR_PLAY] — SpotiFLAC just fetched this track's file, so it starts playing
 *    from a downloaded file, but that work happened for *this* play.
 *  - [LIVE_STREAM] — a YouTube URL being streamed over the network; nothing is on disk.
 *
 * Kept separate from the media3 "downloaded" badge on purpose: that badge describes an
 * offline copy the user explicitly asked for, which may or may not be the thing playing.
 */
enum class PlaybackDelivery {
    DEVICE_CACHE,
    FETCHED_FOR_PLAY,
    LIVE_STREAM,
}

data class PlaybackSourceInfo(
    val engine: PlaybackEngine,
    val provider: String?,
    /** Null when nothing reliable is known yet, so the UI can decline to claim anything. */
    val delivery: PlaybackDelivery?,
) {
    /** True while the audio is coming out of a file on this device. */
    val isFromDeviceFile: Boolean get() = delivery?.let { it != PlaybackDelivery.LIVE_STREAM } == true

    companion object {
        val unknown = PlaybackSourceInfo(PlaybackEngine.UNKNOWN, null, null)
    }
}

/**
 * Turns the raw playback-client label into [PlaybackSourceInfo].
 *
 * The labels are produced in several places over time — `"SpotiFLAC • deezer • cached"`,
 * `"SpotiFLAC - Deezer"`, `"YouTube • VR"`, or a bare YouTube client name such as
 * `"WEB_REMIX"` — so the parsing lives here, tested, instead of being re-derived by string
 * sniffing at each call site.
 */
object PlaybackSourceLabels {
    private val SEPARATORS = listOf("•", " - ")

    private const val CACHED = "cached"

    /** Raw YouTube client names that can reach the label without a friendly prefix. */
    private val YOUTUBE_CLIENTS =
        mapOf(
            "WEB_REMIX" to "Web",
            "ANDROID_VR" to "VR",
            "ANDROID_MUSIC" to "YT Music",
            "IOS" to "iOS",
            "TVHTML5" to "TV",
            "TVHTML5_SIMPLIFIED" to "TV",
            "VISIONOS" to "Vision",
            "ARCHIVETUNE_EXTRACTOR" to "Extractor",
        )

    fun parse(raw: String?): PlaybackSourceInfo {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return PlaybackSourceInfo.unknown

        val separator = SEPARATORS.firstOrNull { text.contains(it) }
        val head = if (separator != null) text.substringBefore(separator).trim() else text
        val tail = if (separator != null) text.substringAfter(separator).trim() else ""

        // The trailing segment can carry several facts ("deezer • cached"), so split it and
        // treat "cached" as an attribute rather than a provider name.
        val segments = tail.split('•', '-').map { it.trim() }.filter { it.isNotEmpty() }
        val fromCache = segments.any { it.equals(CACHED, ignoreCase = true) }
        val provider = segments.firstOrNull { !it.equals(CACHED, ignoreCase = true) }

        val engine =
            when {
                head.contains("spotiflac", ignoreCase = true) -> PlaybackEngine.SPOTIFLAC
                head.contains("hi-res", ignoreCase = true) ||
                    head.contains("hires", ignoreCase = true) -> PlaybackEngine.HIRES
                head.contains("youtube", ignoreCase = true) -> PlaybackEngine.YOUTUBE
                YOUTUBE_CLIENTS.containsKey(head.uppercase()) -> PlaybackEngine.YOUTUBE
                else -> PlaybackEngine.UNKNOWN
            }

        val resolvedProvider =
            when (engine) {
                PlaybackEngine.YOUTUBE -> provider ?: YOUTUBE_CLIENTS[head.uppercase()]
                PlaybackEngine.SPOTIFLAC, PlaybackEngine.HIRES -> provider
                PlaybackEngine.UNKNOWN -> provider
            }

        val delivery =
            when (engine) {
                PlaybackEngine.SPOTIFLAC, PlaybackEngine.HIRES ->
                    if (fromCache) PlaybackDelivery.DEVICE_CACHE else PlaybackDelivery.FETCHED_FOR_PLAY
                PlaybackEngine.YOUTUBE -> PlaybackDelivery.LIVE_STREAM
                PlaybackEngine.UNKNOWN -> null
            }

        return PlaybackSourceInfo(engine = engine, provider = resolvedProvider, delivery = delivery)
    }
}
