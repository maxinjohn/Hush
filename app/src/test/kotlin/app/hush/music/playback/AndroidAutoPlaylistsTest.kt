/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cover for the Android Auto browse decisions.
 *
 * These are the parts a car screen depends on and no test on a phone can see: which playlist groups
 * the switches produce, which library sections the arrangement publishes, and whether the media id
 * a car hands back can be turned into the playlist it names.
 */
class AndroidAutoPlaylistsTest {
    @Test
    fun `the spotify switch needs a connected account, the youtube one does not`() {
        val connected = AndroidAutoPlaylists.sources(youtubeEnabled = true, spotifyEnabled = true, spotifyConnected = true)
        assertTrue(connected.spotify)
        assertTrue(connected.youtube)

        // Switch on, no account: there is nothing to list, so the folder must not appear.
        val disconnected = AndroidAutoPlaylists.sources(youtubeEnabled = true, spotifyEnabled = true, spotifyConnected = false)
        assertFalse(disconnected.spotify)
        assertTrue(disconnected.youtube)

        // Account connected, switch off: the user asked for it not to be shown.
        val switchedOff = AndroidAutoPlaylists.sources(youtubeEnabled = false, spotifyEnabled = false, spotifyConnected = true)
        assertFalse(switchedOff.spotify)
        assertFalse(switchedOff.youtube)
        assertFalse(switchedOff.any)
    }

    @Test
    fun `a spotify playlist media id round trips back to its playlist`() {
        val id = AndroidAutoPlaylists.spotifyMediaId("37i9dQZF1DXcBWIGoYBM5M")
        assertEquals("spotify_playlist/37i9dQZF1DXcBWIGoYBM5M", id)
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", AndroidAutoPlaylists.parseSpotify(id)?.playlistId)
    }

    @Test
    fun `a shuffle entry and a track inside the folder parse into the same playlist`() {
        val shuffle = AndroidAutoPlaylists.spotifyShuffleMediaId("abc")
        val shufflePath = AndroidAutoPlaylists.parseSpotify(shuffle)!!
        assertEquals("abc", shufflePath.playlistId)
        assertTrue(shufflePath.isShuffle)
        assertNull(shufflePath.trackId)
        assertNull(shufflePath.selectedTrackId)

        val track = AndroidAutoPlaylists.parseSpotify("spotify_playlist/abc/dQw4w9WgXcQ")!!
        assertEquals("abc", track.playlistId)
        assertFalse(track.isShuffle)
        assertEquals("dQw4w9WgXcQ", track.selectedTrackId)

        val shuffledTrack = AndroidAutoPlaylists.parseSpotify("spotify_playlist/abc/_shuffle/dQw4w9WgXcQ")!!
        assertTrue(shuffledTrack.isShuffle)
        assertEquals("dQw4w9WgXcQ", shuffledTrack.selectedTrackId)
    }

    @Test
    fun `other groups are left alone`() {
        assertNull(AndroidAutoPlaylists.parseSpotify("online_playlist/abc"))
        assertNull(AndroidAutoPlaylists.parseSpotify("playlist/1"))
        assertNull(AndroidAutoPlaylists.parseSpotify("spotify_playlist"))
        assertNull(AndroidAutoPlaylists.parseSpotify("spotify_playlist/"))
        assertNull(AndroidAutoPlaylists.spotifyDestinationId("liked"))
        assertNull(AndroidAutoPlaylists.spotifyDestinationId("auto"))
    }

    @Test
    fun `a quick add destination names its spotify playlist`() {
        assertEquals("abc", AndroidAutoPlaylists.spotifyDestinationId("spotify_playlist/abc"))
        assertEquals("abc", AndroidAutoPlaylists.spotifyDestinationId(" spotify_playlist/abc "))
    }

    @Test
    fun `an unreadable arrangement shows everything in default order`() {
        assertEquals(
            AndroidAutoPlaylists.Section.entries.toList(),
            AndroidAutoPlaylists.enabledSections(""),
        )
        assertEquals(
            AndroidAutoPlaylists.Section.entries.toList(),
            AndroidAutoPlaylists.enabledSections("nonsense"),
        )
    }

    @Test
    fun `a disabled section disappears and the rest keep the arranged order`() {
        val stored = "playlists:true,liked:false,albums:true,songs:true,artists:true"
        assertEquals(
            listOf(
                AndroidAutoPlaylists.Section.PLAYLISTS,
                AndroidAutoPlaylists.Section.ALBUMS,
                AndroidAutoPlaylists.Section.SONGS,
                AndroidAutoPlaylists.Section.ARTISTS,
            ),
            AndroidAutoPlaylists.enabledSections(stored),
        )
    }

    @Test
    fun `a section an older arrangement never mentioned stays on the end and enabled`() {
        val stored = "playlists:false,liked:true"
        val states = AndroidAutoPlaylists.deserializeSections(stored)
        assertEquals(AndroidAutoPlaylists.Section.PLAYLISTS, states.first().section)
        assertEquals(
            listOf(
                AndroidAutoPlaylists.Section.LIKED,
                AndroidAutoPlaylists.Section.SONGS,
                AndroidAutoPlaylists.Section.ARTISTS,
                AndroidAutoPlaylists.Section.ALBUMS,
            ),
            AndroidAutoPlaylists.enabledSections(stored),
        )
        // Every section is accounted for exactly once, so no section can be silently lost.
        assertEquals(AndroidAutoPlaylists.Section.entries.size, states.size)
        assertEquals(states.size, states.map { it.section }.toSet().size)
    }

    @Test
    fun `Liked Songs is addressed like a playlist, so every existing path carries it`() {
        val folder = AndroidAutoPlaylists.spotifyLikedMediaId()
        val parsed = AndroidAutoPlaylists.parseSpotify(folder)
        assertEquals(AndroidAutoPlaylists.LIKED_PLAYLIST_ID, parsed?.playlistId)
        assertTrue(AndroidAutoPlaylists.isLiked(parsed!!.playlistId))
        // No action: a folder, which is what makes a car list it as browsable and expand it.
        assertNull(parsed.action)

        val shuffle = AndroidAutoPlaylists.parseSpotify(AndroidAutoPlaylists.spotifyShuffleMediaId(AndroidAutoPlaylists.LIKED_PLAYLIST_ID))
        assertTrue(shuffle!!.isShuffle)
        assertNull(shuffle.selectedTrackId)

        // A leaf a car hands back on a tap still resolves to the track, not back to the folder.
        val leaf = AndroidAutoPlaylists.parseSpotify("$folder/track123")
        assertEquals("track123", leaf!!.selectedTrackId)
        assertFalse(leaf.isShuffle)
    }

    @Test
    fun `a real playlist id can never be mistaken for Liked Songs`() {
        // The sentinel starts with an underscore; no base-62 playlist id can.
        assertFalse(AndroidAutoPlaylists.isLiked("37i9dQZF1DXcBWIGoYBM5M"))
        assertFalse(AndroidAutoPlaylists.isLiked(""))
    }

    @Test
    fun `the codec survives a round trip`() {
        val states =
            AndroidAutoPlaylists.Section.entries
                .reversed()
                .map { section ->
                    AndroidAutoPlaylists.SectionState(
                        section = section,
                        enabled = section != AndroidAutoPlaylists.Section.ALBUMS,
                    )
                }
        assertEquals(states, AndroidAutoPlaylists.deserializeSections(AndroidAutoPlaylists.serializeSections(states)))
    }
}
