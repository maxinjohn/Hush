/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 and Section 5
 */

package app.hush.music.playback

/**
 * Where a track's bytes actually live on this device, per store.
 *
 * The stores are named after what writes them, because that is the whole point: a media id can have
 * bytes in more than one of them, and they are not interchangeable.
 *
 * - [userDownload] - a song the user downloaded. Audio the device keeps until they remove it.
 * - [spotiflacCacheFile] - a SpotiFLAC playback file. Read straight off disk rather than copied into
 *   Media3's song cache, so this is the only place a lossless track's audio lives.
 * - [streamingCache] - Media3's song cache, which only a *stream* writes. On the SpotiFLAC path the
 *   resolved file is served directly, so these bytes are only ever a streamed YouTube track's.
 */
data class StoredBytes(
    val userDownload: Boolean = false,
    /**
     * Whether that download was produced by SpotiFLAC. Only meaningful when [userDownload] is set,
     * and read from Hush's own record rather than inferred from the file name - the extension is a
     * guess about the content, and this is a claim about where the bytes came from.
     */
    val downloadIsSpotiFLAC: Boolean = false,
    val spotiflacCacheFile: Boolean = false,
    val streamingCache: Boolean = false,
) {
    /** Whether any of the stores holds bytes for this track. */
    val any: Boolean get() = userDownload || spotiflacCacheFile || streamingCache

    /**
     * Whether these bytes can be lossless audio at all.
     *
     * A user download can hold a lossless file only when SpotiFLAC produced it, and a playback cache
     * file always is one. The streaming cache cannot: nothing lossless is ever written to it.
     */
    val canBeLossless: Boolean get() = spotiflacCacheFile || (userDownload && downloadIsSpotiFLAC)
}

/**
 * Whether the player may describe a track's audio, and on what evidence.
 *
 * The codec row reads a `FormatEntity` stored per media id - the format of whatever engine resolved
 * that track last. Queue restore keeps the ids, so on a cold start every item still carries the row
 * from the previous session, and the row was drawn before anything had been resolved at all. On
 * device that read as `FLAC • 1411 kbps • 20 MB` for a track with no cached file anywhere (the
 * SpotiFLAC cache index was empty and its directory did not exist), and the track then failed
 * outright: the player was advertising lossless audio it could not serve.
 *
 * So a stored row is only shown about audio this device can actually be serving. Anything served in
 * this session describes itself, and anything not yet resolved describes nothing - which is the
 * honest answer, and the one the fetching/source rows already follow.
 *
 * "Bytes this device has" was too narrow a test, though, and it hid truthful rows: a YouTube track
 * being streamed right now has no bytes on disk until Media3's song cache fills, and the cache is
 * written *after* the row is asked for, so the codec row - the one place the player says what is
 * playing - vanished for exactly the ordinary case of playing a track from the network. What backs a
 * row is the audio being served, and a network stream is that audio. See [FormatRowEvidence.STREAMING_NOW].
 *
 * Having *some* bytes is not the whole test, though. A row that claims lossless audio may only be
 * shown over bytes that could be lossless, or the same lie comes back through a second door: a track
 * whose last resolve was SpotiFLAC, whose file is gone, and which is now being served from the
 * streamed YouTube copy would read `FLAC` while YouTube audio decoded. The engine every store
 * belongs to is what makes that distinguishable, so the claim is checked against the store, not
 * against the mere presence of bytes.
 */
enum class FormatRowEvidence {
    /** A resolve in this session produced these bytes; the row is about them. */
    SERVED_NOW,

    /** The bytes are on this device: a scanned local file, a download, or a cached file. */
    LOCAL_BYTES,

    /**
     * The row describes the stream being served for this track right now.
     *
     * The bytes exist and are being decoded; they are simply not on disk, which is the normal state of
     * a YouTube track. Kept apart from [LOCAL_BYTES] because the two are different facts about the same
     * row, and the reason a row was drawn is worth being able to read back.
     */
    STREAMING_NOW,

    /** Nothing has been served and nothing is stored, so the stored row describes nothing here. */
    NOTHING,
}

object ServedFormatClaim {

    /**
     * The evidence behind a stored format row for one track.
     *
     * A resolve that happened in this session beats a stored row rather than being compared with it:
     * the published format *is* what is playing, and the stored row is only ever the best guess about
     * it. A scanned local song's row was written by the scanner over a file the app owns, so it needs
     * no further test either.
     *
     * @param rowClaimsSpotiFLAC whether the stored row describes SpotiFLAC lossless audio. When it
     *   does, only [StoredBytes.canBeLossless] bytes may back it - and that is checked *before*
     *   [streamingNow], because a YouTube stream can never back a lossless claim.
     * @param streamingNow whether this track is being streamed from the network at this moment. A row
     *   that claims no lossless audio describes exactly those bytes, so it stands.
     */
    fun evidence(
        servedNow: Boolean,
        trackIsLocalFile: Boolean,
        bytes: StoredBytes,
        rowClaimsSpotiFLAC: Boolean = false,
        streamingNow: Boolean = false,
    ): FormatRowEvidence = when {
        servedNow -> FormatRowEvidence.SERVED_NOW
        trackIsLocalFile -> FormatRowEvidence.LOCAL_BYTES
        rowClaimsSpotiFLAC -> if (bytes.canBeLossless) FormatRowEvidence.LOCAL_BYTES else FormatRowEvidence.NOTHING
        bytes.any -> FormatRowEvidence.LOCAL_BYTES
        streamingNow -> FormatRowEvidence.STREAMING_NOW
        else -> FormatRowEvidence.NOTHING
    }

    /** Whether a stored row may be drawn for a track, given what is known about it. */
    fun mayShowStoredRow(evidence: FormatRowEvidence): Boolean =
        evidence != FormatRowEvidence.NOTHING

    /**
     * Why a stored row was not drawn, in the terms the two cases actually differ by.
     *
     * "No bytes are here" and "the only bytes here are streamed, which cannot be lossless" are
     * different findings about the same hidden row, and reading which one happened is the difference
     * between a stale row and a mismatched row. Only called for a row that [mayShowStoredRow] refused.
     */
    fun explain(
        bytes: StoredBytes,
        rowClaimsSpotiFLAC: Boolean,
    ): String = when {
        rowClaimsSpotiFLAC && bytes.any ->
            "the row claims lossless audio but the only bytes here are not lossless " +
                "(streamed=${bytes.streamingCache} download=${bytes.userDownload})"
        rowClaimsSpotiFLAC ->
            "the row claims lossless audio and none of it is here - no playback file, " +
                "no download, no streamed copy"
        else -> "nothing has been served for this track and none of its bytes are on this device"
    }
}
