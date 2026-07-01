/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.lyrics

import android.content.Context
import app.hush.music.constants.EnablePaxsenixNeteaseLyricsKey
import app.hush.music.paxsenix.PaxsenixLyrics
import app.hush.music.utils.dataStore
import app.hush.music.utils.get

object PaxsenixNeteaseLyricsProvider : LyricsProvider {
    override val name = "Paxsenix: NetEase"

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnablePaxsenixNeteaseLyricsKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> = PaxsenixLyrics.getNeteaseLyrics(title, artist, duration)

    override suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
        callback: (String) -> Unit,
    ) {
        getLyrics(id, title, artist, album, duration).onSuccess(callback)
    }
}
