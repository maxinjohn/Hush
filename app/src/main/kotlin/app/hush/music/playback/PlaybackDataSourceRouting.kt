/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import java.util.Locale

/**
 * Where the bytes for one playback open should come from.
 */
enum class PlaybackByteSource {
    /**
     * Bytes that are already a complete, immutable file on this device - a SpotiFLAC
     * playback file, a user download or a local song. Read them directly.
     *
     * Routing these through Media3's caches stores a second, full copy of the same
     * audio under the song's cache key, so a track that is by definition already
     * local ends up occupying twice the disk. It is also what let bytes of unknown
     * provenance sit in a cache under a key that a different engine would later
     * read.
     */
    LOCAL_FILE,

    /**
     * A remote stream - or a Media3 download that has to be range-read. Go through
     * the cache chain so range requests are cached, seeking stays cheap, and the
     * existing offline/download behaviour is unchanged.
     */
    CACHED,
}

/**
 * The scheme-based routing decision for the playback data-source chain.
 *
 * This is deliberately a pure function of the scheme so it can be pinned by unit
 * tests. The half that tests cannot enforce is *when* it is applied, so it is worth
 * stating plainly here:
 *
 * **[routeResolvedUri] must be given the scheme of the URI the resolver produced,
 * not the scheme of the media item's original URI.**
 *
 * A track's media item URI is a bare song id with no scheme at all, so the answer
 * for the original URI is always [PlaybackByteSource.CACHED]. Routing on it meant
 * the cache chain was chosen *before* the resolver ran, and a SpotiFLAC resolution -
 * which produces `file://.../spotiflac/playback/<key>.flac` - then had its bytes
 * written into the cache on the way through. Resolving first and routing on the
 * result is what keeps a local file out of the cache.
 */
object PlaybackDataSourceRouting {
    const val SCHEME_FILE = "file"
    const val SCHEME_CONTENT = "content"
    const val SCHEME_ANDROID_RESOURCE = "android.resource"

    /**
     * The byte source for an already-resolved playback URI.
     */
    fun routeResolvedUri(resolvedScheme: String?): PlaybackByteSource =
        if (isLocalFileScheme(resolvedScheme)) {
            PlaybackByteSource.LOCAL_FILE
        } else {
            PlaybackByteSource.CACHED
        }

    /**
     * Whether [scheme] names bytes that live on this device outside any cache.
     *
     * `content://` and `android.resource://` are local media too, so they get the same
     * direct treatment as `file://`; an unknown or absent scheme is never treated as
     * local, so a failed resolution still falls through to the cache chain and reports
     * its own error instead of being silently misread as a file.
     */
    fun isLocalFileScheme(scheme: String?): Boolean {
        val normalized = scheme?.lowercase(Locale.US)?.trim() ?: return false
        return normalized == SCHEME_FILE ||
            normalized == SCHEME_CONTENT ||
            normalized == SCHEME_ANDROID_RESOURCE
    }
}
