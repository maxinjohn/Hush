/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.utils

/**
 * Parses YouTube / YouTube Music links pasted into search so they can be opened
 * directly (play a video, open album/playlist/artist) instead of running a text search.
 */
object YouTubeUrlParser {
    sealed interface Parsed {
        /** A single video, optionally with playlist context (`watch?v=..&list=..`). */
        data class Video(
            val videoId: String,
            val playlistId: String? = null,
        ) : Parsed

        data class Album(val playlistId: String) : Parsed

        data class Playlist(val playlistId: String) : Parsed

        data class Artist(val channelId: String) : Parsed
    }

    private const val VIDEO_ID_LENGTH = 11

    private val watchUrlRegex =
        Regex("""(?:youtube(?:-nocookie)?\.com|music\.youtube\.com)/(?:watch\?(?:.*&)?v=|(?:v|shorts|embed)/)([A-Za-z0-9_-]{11})""", RegexOption.IGNORE_CASE)
    private val shortUrlRegex = Regex("""youtu\.be/([A-Za-z0-9_-]{11})""", RegexOption.IGNORE_CASE)
    private val playlistParamRegex = Regex("""[?&]list=([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
    private val playlistUrlRegex =
        Regex("""(?:youtube\.com|music\.youtube\.com)/playlist\?[^\s]*[?&]?list=([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
    private val channelRegex = Regex("""(?:youtube\.com|music\.youtube\.com)/channel/(UC[A-Za-z0-9_-]{10,})""", RegexOption.IGNORE_CASE)

    /**
     * Returns the parsed target for [text], or null when [text] does not look like
     * a YouTube link (in which case normal text search should proceed).
     */
    fun parse(text: String): Parsed? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.contains('\n')) return null
        // Fast reject: anything without a youtube host/scheme is a normal query.
        val looksLikeUrl = trimmed.startsWith("http") || trimmed.contains("youtu.be") || trimmed.contains("youtube.com")
        if (!looksLikeUrl) return null

        val listId = playlistParamRegex.find(trimmed)?.groupValues?.get(1)

        // watch?v / shorts / embed / youtu.be forms → play the video.
        val videoId = watchUrlRegex.find(trimmed)?.groupValues?.get(1)
            ?: shortUrlRegex.find(trimmed)?.groupValues?.get(1)
        if (videoId != null && isValidVideoId(videoId)) {
            return Parsed.Video(
                videoId = videoId,
                playlistId = listId?.takeUnless { it.isEmpty() },
            )
        }

        // Playlist-only link.
        playlistUrlRegex.find(trimmed)?.groupValues?.get(1)?.let { id ->
            return classifyPlaylist(id)
        }

        // Channel link.
        channelRegex.find(trimmed)?.groupValues?.get(1)?.let { id ->
            return Parsed.Artist(id)
        }

        return null
    }

    private fun classifyPlaylist(playlistId: String): Parsed? {
        if (playlistId.length < 12) return null
        return when {
            // Radio built from a video (RDAMVM<videoId>) → play that video.
            playlistId.startsWith("RDAMVM") &&
                playlistId.length == "RDAMVM".length + VIDEO_ID_LENGTH -> {
                Parsed.Video(videoId = playlistId.removePrefix("RDAMVM"))
            }
            // My Mix / radio mixes behave like videos with an endless radio queue.
            playlistId.startsWith("RD") || playlistId.startsWith("UL") -> {
                Parsed.Playlist(playlistId)
            }
            // Album playlist ids always start with OLAK5uy_.
            playlistId.startsWith("OLAK5uy_") -> Parsed.Album(playlistId)
            else -> Parsed.Playlist(playlistId)
        }
    }

    private fun isValidVideoId(id: String): Boolean = id.length == VIDEO_ID_LENGTH
}
