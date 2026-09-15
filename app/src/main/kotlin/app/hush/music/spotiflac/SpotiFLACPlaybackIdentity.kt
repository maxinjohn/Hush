/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

/**
 * The identity a track was resolved with for playback, keyed by `mediaId`.
 *
 * A download has to resolve the *same* track on the *same* source as the audio
 * that is playing. Playback derives its identity from the queue item, which
 * carries the ISRC and Spotify id in its `MediaMetadata` extras; the download
 * path only has a media id and used to fall back to whatever the database row
 * held. That lost the strong identifiers, so SpotiFLAC had to guess from a
 * title/artist search and often failed ("Invalid Deezer track ID"), after which
 * the download silently went to YouTube — a different engine than the one
 * playing.
 *
 * Recording what playback used closes that gap. Entries are bounded and LRU so a
 * long listening session cannot grow this without limit.
 */
object SpotiFLACPlaybackIdentity {
    /** Everything the runtime needs to resolve one track, as playback saw it. */
    data class Identity(
        val title: String,
        val artist: String,
        val album: String?,
        val durationMs: Long,
        val isrc: String?,
        val spotifyTrackId: String?,
        val quality: String,
        val coverUrl: String?,
    )

    private const val MAX_ENTRIES = 256

    /**
     * The best duration available for a track, in milliseconds, or 0 when none is.
     *
     * `MediaMetadata.durationMs` is a non-null `Long` that reads 0 when the item carries
     * no duration - the norm for a queue restored after a restart - so a plain `?:`
     * chain never reaches the database fallback behind it. Feeding that 0 into a resolve
     * made it a *different* track identity from the same song resolved with its real
     * duration. Measured on device: `Africa|Toto||0` downloaded the file, and the next
     * lookup of the same song computed `Africa|Toto||272000`, missed the cache and
     * re-downloaded a track already on disk. So an unknown duration must never win over
     * a known one, whichever source knows it.
     */
    fun bestDurationMs(vararg candidates: Long?): Long =
        candidates.firstOrNull { it != null && it > 0L } ?: 0L

    private val entries =
        object : LinkedHashMap<String, Identity>(64, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Identity>?,
            ): Boolean = size > MAX_ENTRIES
        }

    /** Remembers the identity playback used for [mediaId], if it can identify the track. */
    fun record(
        mediaId: String?,
        title: String?,
        artist: String?,
        album: String?,
        durationMs: Long,
        isrc: String?,
        spotifyTrackId: String?,
        quality: String,
        coverUrl: String? = null,
    ) {
        val id = mediaId?.takeIf { it.isNotBlank() } ?: return
        val resolvedTitle = title?.takeIf { it.isNotBlank() } ?: return
        val identity =
            Identity(
                title = resolvedTitle,
                artist = artist.orEmpty(),
                album = album?.takeIf { it.isNotBlank() },
                durationMs = durationMs,
                isrc = isrc?.takeIf { it.isNotBlank() },
                spotifyTrackId = spotifyTrackId?.takeIf { it.isNotBlank() },
                quality = quality,
                coverUrl = coverUrl,
            )
        synchronized(entries) { entries[id] = identity }
    }

    fun get(mediaId: String): Identity? =
        mediaId.takeIf { it.isNotBlank() }?.let { synchronized(entries) { entries[it] } }

    /** Test/format helper: number of remembered identities. */
    fun size(): Int = synchronized(entries) { entries.size }
}
