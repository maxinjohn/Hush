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
