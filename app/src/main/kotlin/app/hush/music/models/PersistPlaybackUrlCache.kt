/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.models

import java.io.Serializable

data class PersistPlaybackUrlCache(
    val entries: List<CachedUrlEntry>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class CachedUrlEntry(
    val mediaId: String,
    val url: String,
    val expiresAtMs: Long,
    val authFingerprint: String,
    val playbackClientLabel: String?,
    val isYouTubeStream: Boolean,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
