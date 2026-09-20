/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

/**
 * The naming rules of one cached SpotiFLAC playback file.
 *
 * A track key is 24 hex characters and never contains a dot, which is what makes the first
 * dot the end of the key: `K.media`, `K.media.flac` (the container the runtime discovered),
 * `K.flac` (older builds), and the two files an interrupted transfer leaves behind -
 * `K.media.flac.partial` and `K.media.flac.partial.checkpoint.json.segments`.
 *
 * Every sweep and every in-flight guard needs the same answer to "is this file this track's",
 * and reading the *last* dot got it wrong for all but the first shape: `.substringBeforeLast('.')`
 * of `K.media.flac.partial` is `K.media.flac`, so a partial was never recognised as belonging to
 * the key being written. A sweep could therefore delete a live transfer's bytes out from under it -
 * and the deletion is silent, so the only visible symptom was a download that mysteriously had to
 * start again. One rule, stated once, is what keeps that from coming back.
 */
object SpotiFLACCacheFiles {
    /** The suffix the runtime writes a transfer under before it is complete. */
    private const val PARTIAL_SUFFIX = ".partial"

    /** The track key a cache file belongs to, taken from the first dot. */
    fun trackKeyOf(fileName: String): String = fileName.substringBefore('.')

    /** Whether [fileName] is a file of [trackKey] rather than of some other track. */
    fun belongsTo(fileName: String, trackKey: String): Boolean =
        trackKey.isNotBlank() && trackKeyOf(fileName) == trackKey

    /**
     * Whether [fileName] is an unfinished transfer, or one of its sidecars.
     *
     * A partial is never a finished file, so it can be swept without knowing anything about
     * the index - but never while its own key is being written, which is the caller's check.
     */
    fun isPartial(fileName: String): Boolean = fileName.contains(PARTIAL_SUFFIX)

    /**
     * Whether [fileName] belongs to a track that is being written right now.
     *
     * The statement a sweep has to respect: bytes that are being produced are not leftovers,
     * whatever the index happens to say about them at that moment.
     */
    fun isBeingWritten(fileName: String, writingKeys: Set<String>): Boolean =
        writingKeys.contains(trackKeyOf(fileName))
}
