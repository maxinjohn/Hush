/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.lyrics

import android.content.Context
import moe.rukamori.archivetune.betterlyrics.BetterLyrics
import app.hush.music.constants.EnableBetterLyricsPortatoKey
import app.hush.music.utils.dataStore
import app.hush.music.utils.get

object BetterLyricsPortatoProvider : LyricsProvider {
    override val name = "BetterLyrics Portato"

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableBetterLyricsPortatoKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> =
        BetterLyrics.getPortatoLyrics(
            title = title,
            artist = artist,
            album = album,
            durationSeconds = duration,
        )

    override suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
        callback: (String) -> Unit,
    ) {
        BetterLyrics.getAllPortatoLyrics(
            title = title,
            artist = artist,
            album = album,
            durationSeconds = duration,
            callback = callback,
        )
    }
}
