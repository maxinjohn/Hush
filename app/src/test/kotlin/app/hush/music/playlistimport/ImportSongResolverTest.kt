package app.hush.music.playlistimport

import app.hush.music.db.entities.ArtistEntity
import app.hush.music.db.entities.Song
import app.hush.music.db.entities.SongEntity
import app.hush.music.models.ImportSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ImportSongResolverTest {
    @Test
    fun `youtube id is reused without performing a search`() = runTest {
        var searches = 0
        val resolver = ImportSongResolver(
            youtubeSearch = ImportYouTubeSongSearch {
                searches++
                emptyList()
            },
        )
        val original = song("Known title", "dQw4w9WgXcQ")

        val result = resolver.resolve(listOf(original), emptyList(), localFirst = false).single()

        assertEquals(0, searches)
        assertEquals(ImportSource.YOUTUBE, result.source)
        assertEquals("dQw4w9WgXcQ", result.resolvedId)
        assertSame(original, result.resolvedSong)
    }

    @Test
    fun `local match is preferred when local first is enabled`() = runTest {
        var searches = 0
        val resolver = ImportSongResolver(
            youtubeSearch = ImportYouTubeSongSearch {
                searches++
                emptyList()
            },
        )
        val original = song("Night Drive", "")
        val local = song("Night Drive", "local-id")

        val result = resolver.resolve(listOf(original), listOf(local), localFirst = true).single()

        assertEquals(0, searches)
        assertEquals(ImportSource.LOCAL, result.source)
        assertEquals("local-id", result.resolvedId)
    }

    @Test
    fun `unresolved item is retained for review`() = runTest {
        val resolver = ImportSongResolver(
            youtubeSearch = ImportYouTubeSongSearch { emptyList() },
        )
        val original = song("Missing song", "")

        val result = resolver.resolve(listOf(original), emptyList(), localFirst = false).single()

        assertEquals(ImportSource.UNRESOLVED, result.source)
        assertEquals(null, result.resolvedId)
        assertEquals(null, result.resolvedSong)
    }

    private fun song(title: String, id: String): Song =
        Song(
            song = SongEntity(id = id, title = title),
            artists = listOf(ArtistEntity(id = "artist", name = "Artist")),
        )
}
