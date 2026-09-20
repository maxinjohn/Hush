/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.utils

/**
 * Live progress of a SpotiFLAC track being fetched for playback. Published by the
 * playback service so the player can show what is happening instead of a bare
 * spinner while a lossless file downloads.
 */
data class PlaybackDownloadProgress(
    val mediaId: String,
    val sourceId: String? = null,
    val percent: Int = 0,
    val bytesReceived: Long = 0L,
    val bytesTotal: Long = 0L,
    val speedMbps: Double = 0.0,
    val stage: String? = null,
    val status: String? = null,
    /** True once the track is playing from the SpotiFLAC cache instead of a download. */
    val fromCache: Boolean = false,
) {
    /** Human-friendly speed, e.g. "8.2 MB/s"; null while unknown. */
    val speedLabel: String?
        get() = speedMbps.takeIf { it > 0.01 }?.let { "%.1f MB/s".format(it) }

    /** Human-friendly size, e.g. "24.1 MB"; null while unknown. */
    val sizeLabel: String?
        get() = bytesTotal.takeIf { it > 0 }?.let { "%.1f MB".format(it / 1_048_576.0) }
}

/**
 * True while SpotiFLAC is still fetching (or decrypting) THIS track's audio.
 *
 * A SpotiFLAC track is downloaded before media3 ever sees it, so during that window the player
 * is not buffering - it has no media item to buffer - and `STATE_BUFFERING` is false for the
 * whole fetch. The transport read that as "idle": it drew a play/pause icon and answered nothing
 * when tapped, which is what a track that is audibly being fetched looked like a dead button.
 *
 * Replaying from the on-device cache is not a fetch (there is nothing to wait for), and a
 * transfer that already reports itself complete is not one either - otherwise a progress entry
 * that lingers past the last byte would pin the spinner on forever.
 *
 * @param mediaId the track being shown, so a *prefetch* of the next track never marks this one
 *   as loading. A blank media id means the runtime could not name the track, which only happens
 *   for the transfer it is running right now.
 */
fun PlaybackDownloadProgress?.isFetchingTrack(mediaId: String?): Boolean {
    val progress = this ?: return false
    if (progress.fromCache || progress.percent >= 100) return false
    val id = progress.mediaId.takeIf { it.isNotBlank() }
    return id == null || id == mediaId
}

/**
 * How full to draw the transport's "the audio is coming" ring, or null when there is no number
 * worth drawing.
 *
 * The ring the player draws while a track is being fetched is *indeterminate*, and an
 * indeterminate indicator is motion - which a device with animations turned off does not have.
 * The system's animator duration scale is a developer setting people set for speed, and when it
 * is 0 the indicator paints its first frame and stays there: a control that looks broken rather
 * than busy, on the one screen where a listener is deciding whether to keep waiting. A fraction
 * needs no animation to say something - the arc grows because the download did.
 *
 * @param mediaId the track on screen, so a prefetch of the *next* track cannot fill this one's
 *   ring.
 * @param isPlaying whether the audio is already playing. A fetch signal that outlives the audio is
 *   the other half of the same complaint: the ring stayed on a song that was audibly playing, so
 *   the play/pause control never came back.
 */
fun PlaybackDownloadProgress?.fetchFraction(mediaId: String?, isPlaying: Boolean): Float? {
    if (isPlaying) return null
    val progress = this ?: return null
    if (progress.fromCache || progress.percent <= 0 || progress.percent >= 100) return null
    val id = progress.mediaId.takeIf { it.isNotBlank() }
    if (id != null && id != mediaId) return null
    return progress.percent / 100f
}

/**
 * True for URIs that are read straight off the device filesystem.
 *
 * Deliberately string-based: this runs on the playback hot path and must not
 * depend on Android framework parsing.
 */
fun String?.isLocalPlaybackUrl(): Boolean =
    when (this?.trim()?.substringBefore(':', missingDelimiterValue = "")?.lowercase()) {
        "file", "content", "android.resource" -> true
        else -> false
    }
