/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.utils

import androidx.media3.common.MediaItem
import moe.rukamori.archivetune.extensions.metadata
import moe.rukamori.archivetune.models.MediaMetadata

private val youtubeVideoIdRegex = Regex("^[a-zA-Z0-9_-]{11}$")

fun String?.toValidArtworkUrl(): String? = this?.trim()?.takeIf { it.isNotBlank() }

fun MediaMetadata?.resolvePlaybackArtworkUrl(): String? {
    if (this == null) return null
    return thumbnailUrl.toValidArtworkUrl()?.highRes()
        ?: id.deriveYouTubeThumbnailUrl()
}

fun MediaItem?.resolvePlaybackArtworkUrl(): String? {
    if (this == null) return null
    val tag = metadata
    return tag?.thumbnailUrl.toValidArtworkUrl()?.highRes()
        ?: mediaMetadata.artworkUri?.toString().toValidArtworkUrl()?.highRes()
        ?: tag?.id.deriveYouTubeThumbnailUrl()
}

fun String?.deriveYouTubeThumbnailUrl(): String? {
    val id = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val videoId =
        when {
            id.matches(youtubeVideoIdRegex) -> id
            id.startsWith("YT") && id.length > 2 -> id.removePrefix("YT").takeIf { it.matches(youtubeVideoIdRegex) }
            else -> null
        } ?: return null
    return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
}
