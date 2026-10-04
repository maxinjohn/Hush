package app.hush.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistCoverInterceptorTest {
    @Test
    fun `playlist id is extracted from a refreshable ytimg cover url`() {
        assertEquals("PLabc123", refreshedPlaylistId(404, "https://i.ytimg.com/pl_c/PLabc123/hq720.jpg"))
        assertEquals("PLabc123", refreshedPlaylistId(410, "https://ytimg.com/pl_c/PLabc123/hq720.jpg"))
        assertEquals("PLabc123", refreshedPlaylistId(400, "https://i.ytimg.com/pl_c/PLabc123/default.jpg"))
        assertEquals("PLabc123", refreshedPlaylistId(401, "https://i.ytimg.com/pl_c/PLabc123/hq720.jpg"))
        assertEquals("PLabc123", refreshedPlaylistId(403, "https://i.ytimg.com/pl_c/PLabc123/hq720.jpg"))
    }

    @Test
    fun `non expiry statuses are not refreshed`() {
        assertNull(refreshedPlaylistId(200, "https://i.ytimg.com/pl_c/PLabc123/hq720.jpg"))
        assertNull(refreshedPlaylistId(429, "https://i.ytimg.com/pl_c/PLabc123/hq720.jpg"))
        assertNull(refreshedPlaylistId(500, "https://i.ytimg.com/pl_c/PLabc123/hq720.jpg"))
        assertNull(refreshedPlaylistId(503, "https://i.ytimg.com/pl_c/PLabc123/hq720.jpg"))
    }

    @Test
    fun `non ytimg hosts are ignored`() {
        assertNull(refreshedPlaylistId(404, "https://example.com/pl_c/PLabc123/hq720.jpg"))
        assertNull(refreshedPlaylistId(404, "https://evilytimg.com/pl_c/PLabc123/hq720.jpg"))
        assertNull(refreshedPlaylistId(404, "https://notytimg.com/pl_c/PLabc123/hq720.jpg"))
        assertNull(refreshedPlaylistId(404, "https://ytimg.com.evil.io/pl_c/PLabc123/hq720.jpg"))
    }

    @Test
    fun `non playlist cover paths are ignored`() {
        assertNull(refreshedPlaylistId(404, "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"))
        assertNull(refreshedPlaylistId(404, "https://i.ytimg.com/pl_c/PLabc123"))
        assertNull(refreshedPlaylistId(404, "https://i.ytimg.com/pl_c/PLabc123/hq720.jpg/extra.jpg"))
        assertNull(refreshedPlaylistId(404, "https://i.ytimg.com/vi/dQw4w9WgXcQ/pl_c/PLabc123/hq720.jpg"))
    }

    @Test
    fun `blank playlist ids are ignored`() {
        assertNull(refreshedPlaylistId(404, "https://i.ytimg.com/pl_c//hq720.jpg"))
        assertNull(refreshedPlaylistId(404, "https://i.ytimg.com/pl_c/%20/hq720.jpg"))
    }

    @Test
    fun `unparseable urls are ignored`() {
        assertNull(refreshedPlaylistId(404, "not a url"))
        assertNull(refreshedPlaylistId(404, "ftp://i.ytimg.com/pl_c/PLabc123/hq720.jpg"))
        assertNull(refreshedPlaylistId(404, "/pl_c/PLabc123/hq720.jpg"))
    }
}
