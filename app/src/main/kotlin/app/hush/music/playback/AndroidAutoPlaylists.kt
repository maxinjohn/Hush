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
     * The playlist id that addresses the account's Liked Songs.
     *
     * Liked Songs is not a real playlist, but Spotify addresses it as one everywhere else in the
     * app, and giving it a playlist id means the existing play, shuffle and child paths carry it
     * with no special case: `spotify_playlist/_liked` parses, browses and plays like any other.
     * The leading underscore is why it can never collide with a real base-62 playlist id.
     */
    const val LIKED_PLAYLIST_ID = "_liked"

    /** Whether a parsed playlist id addresses the account's Liked Songs. */
    fun isLiked(playlistId: String): Boolean = playlistId == LIKED_PLAYLIST_ID

    /**
     * The media id of the car's lossless offer.
     *
     * A car has no banner, so the offer the phone makes at launch has to be an entry in the library
     * a driver is already looking at - and an entry in a car's library is a *media item*, so tapping
     * it is a play request. The id is a word rather than an id-shaped token on purpose: it is read
     * back by the same `when` that resolves every other browsed id, and nothing that addresses the
     * user's own media may ever parse as it.
     */
    const val LOSSLESS_OFFER = "enable_lossless"

    /** Whether a car asked for the lossless offer rather than for something to play. */
    fun isLosslessOffer(mediaId: String): Boolean = mediaId == LOSSLESS_OFFER

    /**
     * Whether a car should be offered lossless playback.
     *
     * Four conditions, and each one is a way the entry would lie if it were left out:
     *
     * - **the runtime has to be in this build.** Without the gobackend module there is no lossless
     *   playback to turn on, and the entry would lead to a switch that cannot deliver anything.
     * - **SpotiFLAC has to be off.** With it already on there is nothing to offer, and the entry
     *   would sit at the top of the library asking a question that has been answered.
     * - **it must not have been dismissed on the phone.** The banner's close button is the user's
     *   "no" and it is the only word for it in the app; a car that ignored it would be asking again
     *   from a screen the user cannot dismiss.
     * - **low data mode must be off.** While it is on, the Audio Sources screen refuses to turn
     *   SpotiFLAC on, because every SpotiFLAC play is a whole lossless file over a metered link. An
     *   entry that turns on what its own settings screen refuses to turn on is worse than no entry.
     *
     * The phone's launch counter is deliberately *not* consulted. That counter rations a banner that
     * interrupts a launch the user did not ask for; this is one line in a list they opened
     * themselves. The moment they take the offer, or decline it on the phone, the line is gone.
     */
    fun offersLossless(
        runtimeAvailable: Boolean,
        spotiflacEnabled: Boolean,
        dismissed: Boolean,
        lowDataModeActive: Boolean,
    ): Boolean = runtimeAvailable && !spotiflacEnabled && !dismissed && !lowDataModeActive

    /** The media id of the Liked Songs folder in the car's Spotify group. */
    fun spotifyLikedMediaId(): String = spotifyMediaId(LIKED_PLAYLIST_ID)

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
