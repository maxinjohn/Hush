/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

/**
 * Which playback engine is tried first.
 *
 * The saved order only has meaning when both engines are enabled. A disabled engine
 * can never be first, so with a single active source the order collapses to that one
 * source and the priority setting stops mattering — which is what keeps a disabled
 * engine from ever being consulted, regardless of what the stored order still says.
 */
/**
 * What to do with bytes already stored for a track, and - when the answer is "refuse" -
 * which of the two quite different problems is the reason. They need separate messages:
 * one is a setting the user can change, the other is a record Hush does not have.
 */
enum class StoredBytesDecision {
    SERVE,

    /** The bytes came from an engine the user has switched off. */
    REFUSE_ENGINE_DISABLED,

    /** Nothing recorded which engine produced them, and YouTube is off. */
    REFUSE_UNKNOWN_ORIGIN,
}

object PlaybackEngineOrder {
    const val SPOTIFLAC = "SPOTIFLAC"
    const val YOUTUBE = "YOUTUBE"

    /**
     * Enabled engines, highest priority first.
     *
     * Entries for a disabled engine are dropped and any enabled engine missing from
     * the saved order is appended, so the result always describes what can actually
     * play.
     */
    fun effective(
        savedOrder: List<String>,
        spotiflacAvailable: Boolean,
        youtubeAvailable: Boolean,
    ): List<String> {
        val enabled = buildList {
            if (spotiflacAvailable) add(SPOTIFLAC)
            if (youtubeAvailable) add(YOUTUBE)
        }
        val preferred = savedOrder
            .map { it.trim().uppercase() }
            .filter { it in enabled }
            .distinct()
        return preferred + enabled.filterNot { it in preferred }
    }

    /**
     * Whether an already-cached playback value may be used instead of re-resolving.
     *
     * A cache entry is only allowed to short-circuit when it came from the engine that
     * would be chosen first anyway. A cached YouTube URL is deliberately refused while
     * SpotiFLAC is first: otherwise a URL cached by an earlier play (or by a SpotiFLAC
     * fallback, or persisted from a session where YouTube was first) keeps winning for
     * that URL's whole lifetime — up to ~6 hours — and the priority list silently stops
     * meaning anything. This is the rule that makes "remove the download and play it
     * again" actually run the regular priority logic.
     *
     * Cached local SpotiFLAC files are exempt because they are not YouTube streams: they
     * never expire, and re-resolving them on every seek caused playback stalls.
     *
     * [spotiflacRecentlyMissed] is the escape hatch that keeps this rule usable: when
     * SpotiFLAC has just been shown to have no match for this track, re-running the
     * provider sweep would only spend the fallback timeout again to reach the same
     * answer, so the cached YouTube URL is reused. A track SpotiFLAC *can* serve has no
     * memo, so it always re-resolves through the priority order.
     */
    fun cachedValueMayShortCircuit(
        spotiflacFirst: Boolean,
        cachedIsYouTubeStream: Boolean,
        spotiflacRecentlyMissed: Boolean = false,
    ): Boolean = !spotiflacFirst || !cachedIsYouTubeStream || spotiflacRecentlyMissed

    /**
     * Whether bytes already stored on disk for a track may be played.
     *
     * Media3's caches are keyed by mediaId alone, so the key cannot say which engine
     * produced the bytes stored under it. Hush has held both a YouTube stream
     * (`itag=251` WebM, a few megabytes) and a SpotiFLAC FLAC file (tens of megabytes)
     * under one key for the same song, depending on which engine fetched it first.
     * Serving either without checking origin is how a track could be labelled
     * `SpotiFLAC - deezer` and still decode as YouTube - and why switching YouTube off
     * did not stop it playing.
     *
     * Bytes of unknown or YouTube origin therefore only play while YouTube is enabled.
     * SpotiFLAC-sourced bytes play regardless, so genuine downloads remain usable
     * offline with YouTube switched off.
     */
    fun cachedBytesMayBeServed(
        youtubeEnabled: Boolean,
        bytesCameFromSpotiFLAC: Boolean,
    ): Boolean = storedBytesMayBeServed(youtubeEnabled, bytesCameFromSpotiFLAC)

    /**
     * The same rule, with the reason kept.
     *
     * A SpotiFLAC-sourced download is a complete file this device already has, so it
     * plays with YouTube off - that is the point of downloading it. Anything else needs
     * YouTube, because a YouTube stream is what it is. The third case is honest about
     * not knowing rather than calling an unrecorded download "YouTube-sourced".
     */
    fun storedBytesDecision(
        youtubeEnabled: Boolean,
        origin: StoredBytesOrigin,
    ): StoredBytesDecision = when {
        origin == StoredBytesOrigin.SPOTIFLAC -> StoredBytesDecision.SERVE
        youtubeEnabled -> StoredBytesDecision.SERVE
        origin == StoredBytesOrigin.YOUTUBE -> StoredBytesDecision.REFUSE_ENGINE_DISABLED
        else -> StoredBytesDecision.REFUSE_UNKNOWN_ORIGIN
    }

    /** Boolean form of [storedBytesDecision], for callers that only branch. */
    fun storedBytesMayBeServed(
        youtubeEnabled: Boolean,
        origin: StoredBytesOrigin,
    ): Boolean = storedBytesDecision(youtubeEnabled, origin) == StoredBytesDecision.SERVE

    private fun storedBytesMayBeServed(
        youtubeEnabled: Boolean,
        bytesCameFromSpotiFLAC: Boolean,
    ): Boolean =
        storedBytesMayBeServed(
            youtubeEnabled = youtubeEnabled,
            origin = if (bytesCameFromSpotiFLAC) StoredBytesOrigin.SPOTIFLAC else StoredBytesOrigin.UNKNOWN,
        )
}
