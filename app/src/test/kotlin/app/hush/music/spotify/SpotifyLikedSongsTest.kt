/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotify

import app.hush.music.playback.AndroidAutoPlaylists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cover for the phone's Liked Songs entry.
 *
 * The car got its Liked Songs folder first, and the two had better be the same collection: a phone
 * that lists one thing and a head unit that browses another is the same bug twice. These are the
 * parts no screen can show a test - which id the entry carries, that no real playlist can be
 * mistaken for it, and that the header built for it cannot claim a count the rows disagree with.
 */
class SpotifyLikedSongsTest {
    @Test
    fun `the entry is addressed by the id the car's media ids already parse`() {
        assertEquals(AndroidAutoPlaylists.LIKED_PLAYLIST_ID, SpotifyLikedSongs.ID)
        assertTrue(SpotifyLikedSongs.isLiked(SpotifyLikedSongs.ID))

        // One id, two surfaces: the media id a head unit browses and the route a phone opens both
        // resolve to this same collection.
        val mediaId = AndroidAutoPlaylists.spotifyLikedMediaId()
        assertEquals(SpotifyLikedSongs.ID, AndroidAutoPlaylists.parseSpotify(mediaId)?.playlistId)
        assertTrue(SpotifyLikedSongs.isLiked(AndroidAutoPlaylists.parseSpotify(mediaId)!!.playlistId))
    }

    @Test
    fun `no real playlist id can be mistaken for liked songs`() {
        assertFalse(SpotifyLikedSongs.isLiked("37i9dQZF1DXcBWIGoYBM5M"))
        assertFalse(SpotifyLikedSongs.isLiked(""))
        assertFalse(SpotifyLikedSongs.isLiked("liked"))
        assertFalse(SpotifyLikedSongs.isLiked("collection"))

        val real = AndroidAutoPlaylists.parseSpotify("spotify_playlist/37i9dQZF1DXcBWIGoYBM5M")!!
        assertFalse(SpotifyLikedSongs.isLiked(real.playlistId))
    }

    @Test
    fun `the built header carries the name and the count of what it opens onto`() {
        val playlist = SpotifyLikedSongs.playlist(displayName = "Liked songs", total = 42)
        assertEquals(SpotifyLikedSongs.ID, playlist.id)
        assertEquals("Liked songs", playlist.name)
        assertEquals(42, playlist.tracks?.total)

        // Nothing real exists for the collection, so every screen has to fall back on its own
        // placeholder rather than render an absent owner, artwork or description as data.
        assertNull(playlist.owner)
        assertNull(playlist.description)
        assertTrue(playlist.images.isEmpty())

        // An unreadable count must not become a negative claim in the header chip.
        assertEquals(0, SpotifyLikedSongs.playlist(displayName = "Liked songs", total = -1).tracks?.total)
    }

    @Test
    fun `sharing links to the collection, a playlist links to itself`() {
        assertEquals(
            "https://open.spotify.com/collection/tracks",
            SpotifyLikedSongs.shareUrl(SpotifyLikedSongs.ID),
        )
        assertEquals(
            "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M",
            SpotifyLikedSongs.shareUrl("37i9dQZF1DXcBWIGoYBM5M"),
        )
    }
}
