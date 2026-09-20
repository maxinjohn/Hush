/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 and Section 5
 */

package app.hush.music.playback

import app.hush.music.db.entities.FormatEntity

/**
 * The format row for the audio Media3 is decoding right now.
 *
 * The player's codec row reads a `FormatEntity` stored per media id, and [ServedFormatClaim] refuses
 * that row when it cannot be backed by audio this device could be serving - so a queue restored from
 * a previous session, whose rows claim lossless audio that is no longer anywhere, drew no codec line
 * at all. That left the one place the player says what is playing completely empty for the whole
 * track, which reads as a broken player rather than as a withheld fact.
 *
 * The decoded audio is the authority this row is missing. Media3 reports the format of the track it
 * selected and is decoding, and that format is true by construction: it does not depend on the stored
 * row being current, on a resolution having published anything this session, or on which engine won
 * the sweep. So when no stored row stands, the player describes *that* instead of describing nothing.
 *
 * Two things this row deliberately does not carry:
 *
 *  - **A size.** Media3's format has no content length, and inventing one is exactly the stale
 *    `20 MB` claim [ServedFormatClaim] exists to refuse.
 *  - **A lossless claim.** [DECODED_ITAG] is not SpotiFLAC's marker, so nothing downstream can read
 *    this row as lossless audio that was not served.
 */
object DecodedAudioRow {
    /**
     * Marks a row built from the decoded audio rather than from a resolution's result.
     *
     * Distinct from [SpotiFLAC's marker][app.hush.music.spotiflac.SpotiFLACServedRow.ITAG] on purpose:
     * a row that says "this is what is decoding" must never be mistaken for one that says "this was
     * served as a lossless file".
     */
    const val DECODED_ITAG = -8002

    /**
     * The mime type used when Media3 reports none.
     *
     * A selected audio track with no mime type and no codec string is not a real case, but the row is
     * only built when a track *is* selected - something is decoding, so the row must still say
     * something. "Unknown" is the vocabulary this app already uses for a value it does not have
     * (`formattedBitrate`, `autoRateDisplay`).
     */
    const val UNKNOWN_MIME = "audio/unknown"

    /**
     * Builds the row for a selected audio track.
     *
     * Always returns a row, because the caller only calls this once Media3 has selected audio to
     * decode: the promise is that a playing track never has an empty codec line. What varies is what
     * the row can say - the container from [sampleMimeType] or [containerMimeType], the codec from
     * [codecs], and the rate from [bitrate] or [averageBitrate].
     */
    fun row(
        mediaId: String,
        sampleMimeType: String?,
        containerMimeType: String?,
        codecs: String?,
        bitrate: Int,
        averageBitrate: Int,
        sampleRate: Int,
    ): FormatEntity {
        val mime =
            sampleMimeType?.takeIf { it.isNotBlank() }
                ?: containerMimeType?.takeIf { it.isNotBlank() }
        val codec = codecs?.takeIf { it.isNotBlank() }
        val resolvedBitrate = if (bitrate > 0) bitrate else averageBitrate

        return FormatEntity(
            id = mediaId,
            itag = DECODED_ITAG,
            // With a codec but no container the row is still readable: the UI labels the codec and
            // adds the container only when there is one to add. With neither, the container becomes
            // the label so the line is never blank.
            mimeType = mime ?: if (codec != null) "" else UNKNOWN_MIME,
            codecs = codec.orEmpty(),
            bitrate = resolvedBitrate.coerceAtLeast(0),
            sampleRate = sampleRate.takeIf { it > 0 },
            contentLength = 0L,
            loudnessDb = null,
            perceptualLoudnessDb = null,
            playbackUrl = null,
        )
    }

    /** Whether [row] describes the audio being decoded rather than a resolution's result. */
    fun isDecodedRow(row: FormatEntity?): Boolean = row?.itag == DECODED_ITAG
}
