/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 and Section 5
 */

package app.hush.music.playback

import app.hush.music.db.entities.FormatEntity

/**
 * Where the codec row's facts came from.
 *
 * The row is drawn from four different places, and until now it did not say which: a resolve that
 * happened in this session, a row an earlier resolve saved, a file this device holds, or the audio
 * Media3 is decoding right now. That matters because the four are not equally strong evidence, and a
 * reader who sees `FLAC • 1411 kbps` has no way to tell a lossless file being served from a stale
 * database row - which is exactly the confusion this player has been fixed for twice already.
 *
 * Naming the source also makes the fixes auditable from the screen: a track whose stored row had to be
 * refused now reads [DECODED_AUDIO] rather than showing nothing.
 */
enum class FormatRowSource {
    /** A resolve in this session produced the row: it describes the bytes just served. */
    SESSION_RESOLVE,

    /** A row an earlier resolve saved, kept because the stream playing now is the one it describes. */
    SAVED_RESOLVE,

    /**
     * The row describes a file this device holds: a download, a cached playback file, a scanned song.
     *
     * Only used when such a file actually backs the row. A streamed track whose bytes Media3 has begun
     * caching is *not* this - the line would then read "from a file on this device" next to
     * "(live stream)", which is the kind of half-truth this player has been fixed for.
     */
    DEVICE_FILE,

    /** The row was built from the audio Media3 is decoding right now. See [DecodedAudioRow]. */
    DECODED_AUDIO,
}

/**
 * A format row together with the evidence behind it.
 *
 * Carried as one value rather than a row and a source that a caller assembles: the UI must never be
 * able to render a row with another row's provenance, which is precisely what two independently
 * collected flows would eventually do.
 */
data class FormatRow(
    val format: FormatEntity,
    val source: FormatRowSource,
)

/**
 * The source a row may be shown from, per the evidence that backs it - or null for
 * [FormatRowEvidence.NOTHING], which cannot back a row at all and is why the decoded audio is used
 * instead.
 *
 * [bytes] is read only where it changes the answer: [FormatRowEvidence.LOCAL_BYTES] covers both a file
 * this device holds and the copy Media3 has cached of a stream, and only the first is a device file.
 * A streamed track's row stays a saved record, so nothing claims a file that is not there.
 */
/**
 * The name for a row a resolve published in this session, given what is behind the audio.
 *
 * A resolve can run *before* the data source is chosen - the URL is resolved, then a downloaded or
 * SpotiFLAC-cached file is served straight off disk instead. So "resolved now" is not the last word
 * about the audio, and the row names the file when the bytes are one.
 *
 * Only the two stores Hush itself writes count ([StoredBytes.userDownload] and
 * [StoredBytes.spotiflacCacheFile]). Media3's song cache deliberately does not: a streamed track fills
 * it as it plays, and calling that "a file on this device" is the very claim this line exists to stop.
 */
fun servedRowSource(bytes: StoredBytes): FormatRowSource =
    if (bytes.userDownload || bytes.spotiflacCacheFile) {
        FormatRowSource.DEVICE_FILE
    } else {
        FormatRowSource.SESSION_RESOLVE
    }

fun FormatRowEvidence.toFormatRowSource(bytes: StoredBytes): FormatRowSource? =
    when (this) {
        FormatRowEvidence.SERVED_NOW -> FormatRowSource.SESSION_RESOLVE
        FormatRowEvidence.LOCAL_BYTES ->
            if (bytes.userDownload || bytes.spotiflacCacheFile) {
                FormatRowSource.DEVICE_FILE
            } else {
                FormatRowSource.SAVED_RESOLVE
            }
        FormatRowEvidence.STREAMING_NOW -> FormatRowSource.SAVED_RESOLVE
        FormatRowEvidence.NOTHING -> null
    }
