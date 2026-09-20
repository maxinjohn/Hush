/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

/**
 * The recommended-playlist groups a car screen can be told to show on top of the device's own
 * playlists, plus the media-id codec for the Spotify group.
 *
 * Kept out of the library callback as a pure object so two things stay checkable without a car
 * attached: which groups the switches actually produce, and the round trip from a browsed media id
 * back to the playlist it addresses.
 */
object AndroidAutoPlaylists {
    /** Media-id root for a Spotify playlist. Mirrors `MusicService.ONLINE_PLAYLIST` for YouTube. */
    const val SPOTIFY_ROOT = "spotify_playlist"

    /**
     * The library sections a car can be told to show.
     *
     * The stored order is the order the user arranged them in, and the first enabled section is the
     * one a car opens on. Disabling one removes its entry from the car's root entirely.
     */
    enum class Section(
        val id: String,
    ) {
        LIKED("liked"),
        SONGS("songs"),
        ARTISTS("artists"),
        ALBUMS("albums"),
        PLAYLISTS("playlists"),
    }

    data class SectionState(
        val section: Section,
        val enabled: Boolean,
    )

    fun serializeSections(sections: List<SectionState>): String =
        sections.joinToString(",") { (section, enabled) -> "${section.id}:$enabled" }

    /**
     * Reads the stored arrangement, treating anything unreadable as "everything, in default order".
     *
     * A car's library must never come back empty because a preference was written by an older or
     * newer build, so an unknown token is dropped rather than obeyed.
     */
    fun deserializeSections(raw: String): List<SectionState> {
        val parsed =
            raw.split(",").mapNotNull { token ->
                val parts = token.split(":")
                if (parts.size != 2) return@mapNotNull null
                val section = Section.entries.find { it.id == parts[0] } ?: return@mapNotNull null
                section to (parts[1].toBooleanStrictOrNull() ?: true)
            }
        if (parsed.isEmpty()) return Section.entries.map { SectionState(it, true) }
        val known = parsed.map { (section, _) -> section }.toSet()
        return parsed.map { (section, enabled) -> SectionState(section, enabled) } +
            Section.entries.filterNot { it in known }.map { SectionState(it, true) }
    }

    /** The sections to publish, in the order the user arranged them. */
    fun enabledSections(raw: String): List<Section> = deserializeSections(raw).filter { it.enabled }.map { it.section }

    /** Media-id segment for the shuffle entry, matching the local and online playlist groups. */
    const val SHUFFLE = "_shuffle"

    /**
     * Which recommended-playlist groups this device should offer in the car.
     *
     * YouTube Music needs nothing but its switch. Spotify additionally needs a connected account:
     * with none there is nothing to list, and a switch that only ever opens an empty folder is the
     * kind of setting that makes every other switch suspect.
     */
    data class Sources(
        val youtube: Boolean,
        val spotify: Boolean,
    ) {
        val any: Boolean get() = youtube || spotify
    }

    fun sources(
        youtubeEnabled: Boolean,
        spotifyEnabled: Boolean,
        spotifyConnected: Boolean,
    ) = Sources(
        youtube = youtubeEnabled,
        spotify = spotifyEnabled && spotifyConnected,
    )

    fun spotifyMediaId(playlistId: String): String = "$SPOTIFY_ROOT/$playlistId"


    fun spotifyShuffleMediaId(playlistId: String): String = "$SPOTIFY_ROOT/$playlistId/$SHUFFLE"

    /** The path a Spotify media id addresses, or `null` when it addresses something else. */
    data class SpotifyPath(
        val playlistId: String,
        val action: String?,
        val trackId: String?,
    ) {
        val isShuffle: Boolean get() = action == SHUFFLE

        /** The track a leaf id selects, ignoring the shuffle marker that can precede it. */
        val selectedTrackId: String? get() = if (isShuffle) trackId else action
    }

    fun parseSpotify(mediaId: String): SpotifyPath? {
        val path = mediaId.split("/").filter { it.isNotBlank() }
        if (path.firstOrNull() != SPOTIFY_ROOT) return null
        val playlistId = path.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        return SpotifyPath(
            playlistId = playlistId,
            action = path.getOrNull(2),
            trackId = path.getOrNull(3),
        )
    }

    /**
     * The Spotify playlist a quick-add destination names, or `null` when the destination is one of
     * the device's own playlists.
     */
    fun spotifyDestinationId(target: String): String? = parseSpotify(target.trim())?.playlistId
}
