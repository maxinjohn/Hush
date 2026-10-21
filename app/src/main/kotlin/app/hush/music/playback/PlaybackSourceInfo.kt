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
 *  - [DEVICE_CACHE] — bytes already on disk that this play fetched nothing for: a SpotiFLAC
 *    playback file. It would still play with the network off, but the app fetched it for
 *    playback rather than the user keeping it, which is what the row says: **(Fetched)**.
 *  - [DEVICE_DOWNLOAD] — the user's own download, which is a file they asked the app to keep.
 *    Kept apart from [DEVICE_CACHE] because "the app fetched this" and "you downloaded this" are
 *    different things to be told, and the second is the one a download button promises.
 *  - [FETCHED_FOR_PLAY] — SpotiFLAC just fetched this track's file, so it starts playing
 *    from a downloaded file, but that work happened for *this* play. Named **(Fetched)** too:
 *    once the file is on disk, "cached earlier" and "fetched now" are the same copy, and the
 *    distinction a listener acts on is whether they kept it.
 *  - [LIVE_STREAM] — a YouTube URL being streamed over the network; nothing is on disk.
 *
 * Kept separate from the media3 "downloaded" badge on purpose: that badge describes an
 * offline copy the user explicitly asked for, which may or may not be the thing playing.
 */
enum class PlaybackDelivery {
    DEVICE_CACHE,
    DEVICE_DOWNLOAD,
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

    /**
     * The label attribute that says these bytes are the user's own download.
     *
     * Published by the playback path when it serves a downloaded file, and read back here so a
     * `YouTube` label that is actually playing off the disk stops announcing a live stream. Public
     * because the publisher and the reader have to agree on the exact word.
     */
    const val DOWNLOADED = "downloaded"

    /**
     * The same label, saying these bytes are the user's own download.
     *
     * A track can be both things at once: fetching it for playback writes a SpotiFLAC playback
     * file, and pressing Download keeps a copy too - so the cache publishes `3rd-party • deezer •
     * cached` for the same bytes. Only one of those facts is what the download button promised,
     * and the other is the wrong one to show over a downloaded song. The cache attribute is
     * *replaced* rather than added to, so the label never carries two delivery facts that
     * disagree.
     */
    fun withDownloadAttribute(label: String): String {
        // The older hyphenated separator is normalised to the bullet every surface shows now. It
        // also has to be normalised *before* the split, or `SpotiFLAC - Deezer` would be carried as
        // one part and the parser would read the whole thing as the head, losing the provider.
        val parts =
            label
                .replace(" - ", " • ")
                .split("•")
                .map { it.trim() }
                .filter { part ->
                    part.isNotEmpty() &&
                        !part.equals(CACHED, ignoreCase = true) &&
                        !part.equals(DOWNLOADED, ignoreCase = true)
                }
        if (parts.isEmpty()) return DOWNLOADED
        return parts.joinToString(" • ") + " • " + DOWNLOADED
    }

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
        //
        // `-` is only a separator in a label that has no bullets, which is the old
        // `SpotiFLAC - Deezer` form: a provider may now be a name with a service and an addon in
        // it (`Tidal · gglos`), and splitting that on `-` would report `Tidal · gglos-vr` as
        // `Tidal · gglos` — the wrong source, silently.
        val separators = if (tail.contains('•')) charArrayOf('•') else charArrayOf('•', '-')
        val segments = tail.split(*separators).map { it.trim() }.filter { it.isNotEmpty() }
        // The attributes are looked for in the head as well as the tail: when nothing else is known the
        // whole label is the attribute ("downloaded"), and a reader that only scanned the tail would
        // see no attribute at all and fall back to claiming a live stream.
        val attributes = listOf(head) + segments
        val fromCache = attributes.any { it.equals(CACHED, ignoreCase = true) }
        // A download is a fact about delivery, not a provider name, exactly like "cached".
        val fromDownload = attributes.any { it.equals(DOWNLOADED, ignoreCase = true) }
        val provider =
            segments.firstOrNull {
                !it.equals(CACHED, ignoreCase = true) && !it.equals(DOWNLOADED, ignoreCase = true)
            }

        val engine =
            when {
                // `3rd-party` is what the app calls this engine now, so that is what it publishes and
                // what a reader sees. `spotiflac` is still recognised: a label recorded earlier (a
                // notification, a log line, a player restored across an upgrade) must keep naming the
                // same engine rather than falling through to UNKNOWN and losing its delivery state.
                head.contains("spotiflac", ignoreCase = true) ||
                    head.contains("third-party", ignoreCase = true) ||
                    head.contains("3rd-party", ignoreCase = true) ||
                    head.contains("3rdparty", ignoreCase = true) -> PlaybackEngine.SPOTIFLAC
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
            when {
                // Either attribute settles it for any engine, including the bare "downloaded" label
                // Hush publishes when nothing else is known: those bytes are on this device.
                fromDownload -> PlaybackDelivery.DEVICE_DOWNLOAD
                fromCache -> PlaybackDelivery.DEVICE_CACHE
                engine == PlaybackEngine.SPOTIFLAC || engine == PlaybackEngine.HIRES ->
                    PlaybackDelivery.FETCHED_FOR_PLAY
                engine == PlaybackEngine.YOUTUBE -> PlaybackDelivery.LIVE_STREAM
                else -> null
            }

        return PlaybackSourceInfo(engine = engine, provider = resolvedProvider, delivery = delivery)
    }
}
