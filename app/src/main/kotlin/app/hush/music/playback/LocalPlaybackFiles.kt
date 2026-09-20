/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import java.io.File

/**
 * Which local file, if any, already owns a media id.
 *
 * Playback serves a file off disk before any cache or resolver, so every path that would
 * otherwise do network work - the URL resolver, the SpotiFLAC lookahead - has to ask the same
 * question first, and has to ask it of *both* stores: the user's own download, and a SpotiFLAC
 * playback file. They are separate stores on purpose (one is the user's and never evicted, the
 * other is a cache), and the same song can be in either.
 *
 * A file that is missing or empty is not ownership. A download still being written, or one whose
 * bytes were deleted outside the app, must not stop the track from being fetched: the alternative
 * to fetching is serving a truncated file, which is worse than a slow start.
 */
object LocalPlaybackFiles {
    /** [file] when it is a real file with bytes in it, otherwise null. */
    fun usable(file: File?): File? = file?.takeIf { it.isFile && it.length() > 0L }

    /**
     * The file that already owns [mediaId], preferring the user's own download over a cached copy.
     *
     * The preference is not cosmetic: the download is what the user asked to keep, and it is the
     * copy the data source serves first, so a decision made here has to agree with it.
     */
    fun owned(
        downloaded: File?,
        cached: File?,
    ): File? = usable(downloaded) ?: usable(cached)
}
