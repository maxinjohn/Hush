/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotify

import app.hush.music.playback.AndroidAutoPlaylists
import app.hush.music.spotify.models.SpotifyPlaylist
import app.hush.music.spotify.models.SpotifyPlaylistTracksRef

/**
 * The account's Liked Songs, dressed as the playlist every screen already knows how to render.
 *
 * Liked Songs is not a playlist: Spotify answers neither `/playlists/{id}` nor
 * `/playlists/{id}/tracks` for it, so the entry the phone lists and the header it opens onto have
 * to be built here instead of fetched. The id is the same
 * [LIKED_PLAYLIST_ID][AndroidAutoPlaylists.LIKED_PLAYLIST_ID] the car's media ids already parse,
 * so one entry addresses the same collection on both screens, and a real base-62 playlist id can
 * never be mistaken for it.
 */
object SpotifyLikedSongs {
    /** The playlist id that addresses the account's Liked Songs on every surface. */
    const val ID: String = AndroidAutoPlaylists.LIKED_PLAYLIST_ID

    /** Whether [playlistId] addresses the account's Liked Songs rather than a real playlist. */
    fun isLiked(playlistId: String): Boolean = AndroidAutoPlaylists.isLiked(playlistId)

    /**
     * Header metadata for the collection.
     *
     * No owner, no artwork and no description, because none of them exist for it - the playlist
     * header treats all three as optional and falls back to its own placeholder, so the empty
     * values are what makes this render like any other playlist rather than as a special case.
     */
    fun playlist(
        displayName: String,
        total: Int,
    ): SpotifyPlaylist = SpotifyPlaylist(
        id = ID,
        name = displayName,
        tracks = SpotifyPlaylistTracksRef(total = total.coerceAtLeast(0)),
    )

    /** The link worth sharing: `/playlist/{id}` answers 404 for `_liked`. */
    fun shareUrl(playlistId: String): String =
        if (isLiked(playlistId)) {
            "https://open.spotify.com/collection/tracks"
        } else {
            "https://open.spotify.com/playlist/$playlistId"
        }
}
