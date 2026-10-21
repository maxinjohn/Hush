/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 and Section 5
 */

package app.hush.music.playback

import app.hush.music.db.entities.FormatEntity
import app.hush.music.spotiflac.SpotiFLACServedRow
import java.io.File

/**
 * The format row for a SpotiFLAC playback file this device serves off disk.
 *
 * The player's codec row reads a `FormatEntity` per media id. That row used to be written from the
 * cache entry's *file extension* and a nominal PCM rate, and both were wrong in ways the screen
 * showed:
 *
 *  - The playback cache writes every file as `<key>.media` - one extension for every codec - so the
 *    extension says nothing about the container. The row was stored as `audio/media` and the player
 *    drew the container label `MEDIA`.
 *  - `sampleRate * bitDepth * 2` describes a decode format, not the bytes on disk, and it was divided
 *    by 1000 on the way into a field every reader divides by 1000. One cached AAC file was stored as
 *    `705` and drew `0 kbps`.
 *  - The cache entry's `sampleRate` is not the file's rate either. Measured on device: an entry said
 *    `22050` for an HE-AAC file that Media3 and ffprobe both decode at `44100`, and three FLAC entries
 *    claimed `29539`, which is not a sample rate at all.
 *
 * So the row states the three things the device can prove about the file - the container, the codec,
 * and the size - and leaves the rate to [DecodedAudioRow], the one place that knows what the bytes
 * actually decode to. A row with no rate is honest; a row with an invented one is not.
 *
 * A FLAC file is the exception, and it is not an exception to that rule but the same rule applied
 * more thoroughly: its `STREAMINFO` block states the stream's exact sample count, so the file's own
 * audio bytes over that duration is a measured average rate rather than a nominal decode one. That
 * figure is read by [FlacFileRate] when the caller can name the file; when it cannot, the row says
 * nothing about the rate and the decoder still gets to fill it in.
 */
object SpotiFLACFileFormat {
    /**
     * The extension SpotiFLAC's playback cache gives every file, whatever its codec.
     *
     * Named rather than passed through so it can never be read as a container: `audio/media` is a
     * file name, and the label it produced - `MEDIA` - is the one this row is no longer allowed to
     * draw.
     */
    private const val CACHE_FILE_EXTENSION = "media"

    /**
     * The container mime type for a file, from its codec and file extension.
     *
     * Used by both places that describe a SpotiFLAC file - the resolution path and the row for the
     * file already on disk - so the two cannot name the same bytes differently. The extension only
     * counts when it names a container: for the cache's own extension there is nothing to learn from
     * it, and the codec answers instead.
     */
    fun containerMime(
        codec: String?,
        extension: String?,
    ): String {
        val suffix = extension?.takeIf { !it.equals(CACHE_FILE_EXTENSION, ignoreCase = true) }
        return when {
            names(codec, suffix, "flac") -> "audio/flac"
            names(codec, suffix, "opus") -> "audio/ogg"
            names(codec, suffix, "mp3") -> "audio/mpeg"
            else -> "audio/mp4"
        }
    }

    /**
     * The row for the SpotiFLAC file that owns [mediaId].
     *
     * [codec] is what the engine reported for the file, and [sizeBytes] is the length on disk - a
     * fact read from the file itself. When [file] is given and it is a FLAC file, the rate comes
     * from the file's own header (see [FlacFileRate]): a lossless file states its sample count, so
     * its average bitrate is a measurement of these bytes rather than the nominal decode rate that
     * used to stand in and be wrong. Everything else keeps no rate at all; see this object's notes.
     */
    fun row(
        mediaId: String,
        codec: String?,
        extension: String?,
        sizeBytes: Long,
        file: File? = null,
    ): FormatEntity {
        val namedCodec = codec?.takeIf { it.isNotBlank() }
        return FormatEntity(
            id = mediaId,
            itag = SpotiFLACServedRow.ITAG,
            mimeType = containerMime(namedCodec, extension),
            codecs = namedCodec.orEmpty(),
            // Zero is "no rate", not "zero kbps": the field is in bps and every reader renders it in
            // kbps, so a figure that is a *type* - the nominal PCM rate of a decode format - cannot
            // be put here without lying about the file. A FLAC file is the one case where the bytes
            // themselves answer, because the header carries the stream's exact sample count.
            bitrate = fileBitrateBps(file = file, codec = namedCodec, extension = extension) ?: 0,
            sampleRate = null,
            contentLength = sizeBytes.takeIf { it > 0L } ?: 0L,
            loudnessDb = null,
            perceptualLoudnessDb = null,
            playbackUrl = null,
        )
    }

    /**
     * The file's own average bitrate, for the one container whose header states it.
     *
     * Gated on the container this object would name, not on the file's extension: a file the cache
     * wrote as `<key>.media` is a FLAC if the engine said so, and its header is then read. Any other
     * container answers null, so no file is opened to find out that it could not have helped.
     */
    fun fileBitrateBps(
        file: File?,
        codec: String?,
        extension: String?,
    ): Int? {
        if (file == null) return null
        if (containerMime(codec, extension) != "audio/flac") return null
        return FlacFileRate.bitrateBpsOf(file)
    }

    private fun names(
        codec: String?,
        extension: String?,
        token: String,
    ): Boolean = codec.equals(token, ignoreCase = true) || extension.equals(token, ignoreCase = true)
}
