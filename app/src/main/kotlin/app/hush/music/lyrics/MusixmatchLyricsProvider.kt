package app.hush.music.lyrics

import android.content.Context
import app.hush.music.constants.EnableMusixmatchLyricsKey
import app.hush.music.musixmatch.Musixmatch
import app.hush.music.utils.dataStore
import app.hush.music.utils.get

object MusixmatchLyricsProvider : LyricsProvider {
    override val name = "Musixmatch"

    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnableMusixmatchLyricsKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> = Musixmatch.getLyrics(title, artist, duration)
}
