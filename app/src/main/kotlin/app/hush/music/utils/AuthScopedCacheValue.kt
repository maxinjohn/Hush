/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.utils

data class AuthScopedCacheValue(
    val url: String,
    val expiresAtMs: Long,
    val authFingerprint: String,
    val playbackClientLabel: String? = null,
    val isYouTubeStream: Boolean = true,
) {
    /**
     * True for playback URIs that point at a file on this device (SpotiFLAC
     * playback downloads, local files). These are not tied to any remote auth
     * state and never expire — only the file existing matters.
     */
    val isLocalFileStream: Boolean get() = url.isLocalPlaybackUrl()

    fun isValidFor(
        authFingerprint: String,
        nowMs: Long = System.currentTimeMillis(),
        minimumRemainingMs: Long = 0L,
    ): Boolean {
        // Local files must not be validated against the YouTube auth fingerprint:
        // doing that made every Media3 re-open (i.e. every seek) re-resolve and
        // re-download the track. They stay valid while the file is on disk.
        if (isLocalFileStream) return localFileExists()
        return this.authFingerprint == authFingerprint && expiresAtMs > nowMs + minimumRemainingMs
    }

    /** Cheap existence check for local playback URIs (no Android framework parsing). */
    fun localFileExists(): Boolean {
        val trimmed = url.trim()
        val scheme = trimmed.substringBefore(':', missingDelimiterValue = "").lowercase()
        return when (scheme) {
            "file" -> {
                val path = trimmed.removePrefix("file://")
                if (path.isEmpty()) {
                    false
                } else {
                    val decoded = runCatching {
                        java.net.URLDecoder.decode(path, Charsets.UTF_8.name())
                    }.getOrDefault(path)
                    java.io.File(decoded).isFile
                }
            }
            // Content / resource URIs cannot be stat-ed cheaply here; assume usable.
            "content", "android.resource" -> true
            else -> false
        }
    }
}
