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
