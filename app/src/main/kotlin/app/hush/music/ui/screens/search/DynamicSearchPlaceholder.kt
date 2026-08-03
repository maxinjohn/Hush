/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.screens.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import app.hush.music.LocalDatabase
import app.hush.music.R
import app.hush.music.constants.SearchSource

@Composable
fun DynamicSearchPlaceholder(
    searchSource: SearchSource,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    if (searchSource == SearchSource.LOCAL) {
        Text(
            text = stringResource(R.string.search_library),
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
        return
    }

    val database = LocalDatabase.current
    val defaultPrompt = stringResource(R.string.search_yt_music)
    val promptWhatsOnMind = stringResource(R.string.search_prompt_whats_on_mind)
    val promptTodaysPick = stringResource(R.string.search_prompt_todays_pick)
    val promptTrending = stringResource(R.string.search_prompt_trending)
    val promptVibe = stringResource(R.string.search_prompt_vibe)
    val promptSongsArtists = stringResource(R.string.search_prompt_songs_artists)
    val templateSong = stringResource(R.string.search_prompt_template_song)
    val templateSongTry = stringResource(R.string.search_prompt_template_song_try)
    val templateArtist = stringResource(R.string.search_prompt_template_artist)

    val placeholders =
        remember(defaultPrompt) {
            mutableStateListOf(
                defaultPrompt,
                promptWhatsOnMind,
                promptTodaysPick,
                promptTrending,
                promptVibe,
                promptSongsArtists,
            )
        }

    LaunchedEffect(database) {
        delay(1_500L)
        val likedSongs = withContext(Dispatchers.Default) {
            runCatching {
                database.likedSongsByRowIdAsc(50).first().shuffled().take(3)
            }.getOrElse { emptyList() }
        }
        likedSongs.forEach { placeholders.add(templateSong.replace("%s", it.title)) }
        val quickPicks = withContext(Dispatchers.Default) {
            runCatching {
                database.quickPicks().first().shuffled().take(3)
            }.getOrElse { emptyList() }
        }
        quickPicks.forEach { placeholders.add(templateSongTry.replace("%s", it.title)) }
        val topArtists = withContext(Dispatchers.Default) {
            runCatching {
                database.allArtistsByPlayTime(50).first().shuffled().take(2)
            }.getOrElse { emptyList() }
        }
        topArtists.forEach { placeholders.add(templateArtist.replace("%s", it.title)) }
    }

    var currentIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(placeholders.size) {
        if (placeholders.size <= 1) return@LaunchedEffect
        while (true) {
            delay(10_000L)
            currentIndex = (currentIndex + 1) % placeholders.size
        }
    }

    val currentText = placeholders.getOrNull(currentIndex) ?: defaultPrompt

    AnimatedContent(
        targetState = currentText,
        transitionSpec = {
            fadeIn(animationSpec = tween(400)) togetherWith fadeOut(animationSpec = tween(400))
        },
        label = "SearchPlaceholderAnimation",
    ) { text ->
        Text(
            text = text,
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
    }
}
