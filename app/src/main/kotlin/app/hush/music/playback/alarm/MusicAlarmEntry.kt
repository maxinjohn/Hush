/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback.alarm

data class MusicAlarmEntry(
    val id: String,
    val enabled: Boolean,
    val hour: Int,
    val minute: Int,
    val playlistId: String,
    val randomSong: Boolean,
    val nextTriggerAt: Long = -1L,
)
