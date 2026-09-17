/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import app.hush.music.db.entities.ArtistEntity
import app.hush.music.db.entities.Event
import app.hush.music.db.entities.EventWithSong
import app.hush.music.db.entities.Song
import app.hush.music.db.entities.SongEntity
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * A cold start with nothing persisted is the one case where the transport used to be a dead
 * button, so what these pin down is exactly the line between "start something" and "leave the
 * device alone".
 */
class HistoryRecoveryQueueTest {

    @Test
    fun `a transport command that asks for music may start playback`() {
        listOf("play", "next", "previous", "play_pause", "skip_to_queue_item").forEach { command ->
            assertTrue(command, TransportRecoveryPolicy.requestsPlayback(command))
        }
    }

    @Test
    fun `commands that arrive with an empty player anyway must not start music`() {
        listOf("pause", "stop", "seek", "sync", "", null, "something-new").forEach { command ->
            assertFalse(
                "command=$command must not start playback",
                TransportRecoveryPolicy.requestsPlayback(command),
            )
        }
    }

    @Test
    fun `the command name is matched regardless of case and padding`() {
        assertTrue(TransportRecoveryPolicy.requestsPlayback(" NEXT "))
        assertTrue(TransportRecoveryPolicy.requestsPlayback("Previous"))
    }

    @Test
    fun `a headset or steering-wheel key that asks for music may start playback`() {
        listOf(
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_HEADSETHOOK,
        ).forEach { keyCode ->
            assertTrue("keyCode=$keyCode", TransportRecoveryPolicy.requestsPlaybackForKeyCode(keyCode))
        }
    }

    @Test
    fun `a key that is not asking for music must not start any`() {
        listOf(
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_UNKNOWN,
        ).forEach { keyCode ->
            assertFalse(
                "keyCode=$keyCode must not start playback",
                TransportRecoveryPolicy.requestsPlaybackForKeyCode(keyCode),
            )
        }
    }

    @Test
    fun `the most recent play comes first and each song appears once`() {
        val events =
            listOf(
                event(song("c", "Third")),
                event(song("a", "First")),
                event(song("c", "Third")),
                event(song("b", "Second")),
            )

        val items = HistoryRecoveryQueue.mediaItems(events, blockedArtistIds = emptySet(), limit = 50)

        assertEquals(listOf("c", "a", "b"), items.map { it.mediaId })
    }

    @Test
    fun `blocked artists never come back into a rebuilt queue`() {
        val events =
            listOf(
                event(song("blocked-track", "Nope", artistId = "blocked-artist")),
                event(song("fine", "Yes")),
            )

        val items =
            HistoryRecoveryQueue.mediaItems(
                events = events,
                blockedArtistIds = setOf("blocked-artist"),
                limit = 50,
            )

        assertEquals(listOf("fine"), items.map { it.mediaId })
    }

    @Test
    fun `the rebuilt queue is capped`() {
        val events = (1..20).map { event(song("song-$it", "Track $it")) }

        val items = HistoryRecoveryQueue.mediaItems(events, blockedArtistIds = emptySet(), limit = 5)

        assertEquals(listOf("song-1", "song-2", "song-3", "song-4", "song-5"), items.map { it.mediaId })
    }

    @Test
    fun `no history rebuilds nothing`() {
        assertTrue(HistoryRecoveryQueue.mediaItems(emptyList(), emptySet(), 50).isEmpty())
    }

    @Test
    fun `a non-positive limit rebuilds nothing rather than throwing`() {
        val events = listOf(event(song("a", "First")))

        assertTrue(HistoryRecoveryQueue.mediaItems(events, emptySet(), 0).isEmpty())
        assertTrue(HistoryRecoveryQueue.mediaItems(events, emptySet(), -3).isEmpty())
    }

    @Test
    fun `a rebuilt item carries the metadata the player needs`() {
        val events = listOf(event(song("id-1", "A Song")))

        val item = HistoryRecoveryQueue.mediaItems(events, emptySet(), 50).single()

        // The URI is deliberately not asserted: these run on the JVM, where android.net.Uri is a
        // stub that parses nothing, so every item's local configuration reports a null uri here
        // regardless of what a device would hold. Identity and metadata are what this decides.
        assertEquals("id-1", item.mediaId)
        assertEquals("A Song", item.mediaMetadata.title?.toString())
    }

    private fun event(song: Song) =
        EventWithSong(
            event =
                Event(
                    songId = song.id,
                    timestamp = LocalDateTime.now(),
                    playTime = 1000L,
                ),
            song = song,
        )

    private fun song(
        id: String,
        title: String,
        artistId: String = "artist-$id",
    ) = Song(
        song =
            SongEntity(
                id = id,
                title = title,
                duration = 180,
                thumbnailUrl = null,
                albumId = null,
                albumName = null,
            ),
        artists = listOf(ArtistEntity(id = artistId, name = "Artist $artistId")),
        album = null,
        format = null,
    )
}
