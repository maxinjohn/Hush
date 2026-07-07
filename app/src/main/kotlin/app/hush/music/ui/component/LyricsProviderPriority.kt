/*
 * Hush — lyrics provider priority dialog (ported from Metrolist, GPL-3.0)
 */

package app.hush.music.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.hush.music.R
import app.hush.music.constants.DefaultLyricsProviderOrder
import app.hush.music.constants.PreferredLyricsProvider
import app.hush.music.constants.deserializeLyricsProviderOrder

data class LyricsProviderEnableState(
    val enableBetterLyrics: Boolean,
    val enableYouLyPlus: Boolean,
    val enableLrcLib: Boolean,
    val enableKugou: Boolean,
    val enableSimpMusic: Boolean,
    val enableUnison: Boolean,
    val enablePaxsenix: Boolean,
    val enablePaxsenixAppleMusic: Boolean,
    val enablePaxsenixNetease: Boolean,
    val enablePaxsenixSpotify: Boolean,
    val enablePaxsenixMusixmatch: Boolean,
    val enablePaxsenixYouTube: Boolean,
) {
    fun enabledProviders(): Set<PreferredLyricsProvider> =
        buildSet {
            if (enableBetterLyrics) add(PreferredLyricsProvider.BETTER_LYRICS)
            if (enableYouLyPlus) add(PreferredLyricsProvider.YOULY_PLUS)
            if (enableLrcLib) add(PreferredLyricsProvider.LRCLIB)
            if (enableKugou) add(PreferredLyricsProvider.KUGOU)
            if (enableSimpMusic) add(PreferredLyricsProvider.SIMPMUSIC)
            if (enableUnison) add(PreferredLyricsProvider.UNISON)
            if (enablePaxsenix) {
                if (enablePaxsenixAppleMusic) add(PreferredLyricsProvider.PAXSENIX_APPLE_MUSIC)
                if (enablePaxsenixNetease) add(PreferredLyricsProvider.PAXSENIX_NETEASE)
                if (enablePaxsenixSpotify) add(PreferredLyricsProvider.PAXSENIX_SPOTIFY)
                if (enablePaxsenixMusixmatch) add(PreferredLyricsProvider.PAXSENIX_MUSIXMATCH)
                if (enablePaxsenixYouTube) add(PreferredLyricsProvider.PAXSENIX_YOUTUBE)
            }
        }
}

fun PreferredLyricsProvider.displayName(): String =
    when (this) {
        PreferredLyricsProvider.LRCLIB -> "LrcLib"
        PreferredLyricsProvider.KUGOU -> "KuGou"
        PreferredLyricsProvider.BETTER_LYRICS -> "Better Lyrics"
        PreferredLyricsProvider.YOULY_PLUS -> "YouLyPlus"
        PreferredLyricsProvider.SIMPMUSIC -> "SimpMusic"
        PreferredLyricsProvider.PAXSENIX_APPLE_MUSIC -> "Paxsenix"
        PreferredLyricsProvider.PAXSENIX_NETEASE -> "Paxsenix: NetEase"
        PreferredLyricsProvider.PAXSENIX_SPOTIFY -> "Paxsenix: Spotify"
        PreferredLyricsProvider.PAXSENIX_MUSIXMATCH -> "Paxsenix: Musixmatch"
        PreferredLyricsProvider.PAXSENIX_YOUTUBE -> "Paxsenix: YouTube"
        PreferredLyricsProvider.UNISON -> "Unison"
    }

@Composable
fun LyricsProviderPriorityDialog(
    providerOrderStr: String,
    enableState: LyricsProviderEnableState,
    onDismiss: () -> Unit,
    onOrderChange: (String) -> Unit,
) {
    val normalizedOrder = deserializeLyricsProviderOrder(providerOrderStr)
    val enabledProviders = enableState.enabledProviders()
    val lyricsIcon = painterResource(R.drawable.lyrics)
    val draggableItems = remember { mutableStateListOf<DraggableLyricsProviderItem>() }

    LaunchedEffect(normalizedOrder, enableState) {
        val orderedEnabled =
            normalizedOrder.filter { provider ->
                provider in enabledProviders
            }
        draggableItems.clear()
        draggableItems.addAll(
            orderedEnabled.map { provider ->
                DraggableLyricsProviderItem(
                    id = provider.name,
                    name = provider.displayName(),
                    icon = lyricsIcon,
                )
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lyrics_provider_priority)) },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(300.dp),
            ) {
                Text(
                    stringResource(R.string.lyrics_provider_priority_desc),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DraggableLyricsProviderList(
                    items = draggableItems,
                    onItemsReordered = { reorderedItems ->
                        val enabledOrder =
                            reorderedItems.mapNotNull { item ->
                                PreferredLyricsProvider.entries.find { it.name == item.id }
                            }
                        val disabledOrder = normalizedOrder.filter { it !in enabledProviders }
                        val fullOrder =
                            enabledOrder +
                                disabledOrder.filter { it !in enabledOrder } +
                                DefaultLyricsProviderOrder.filter { it !in enabledOrder && it !in disabledOrder }
                        onOrderChange(fullOrder.joinToString(",") { it.name })
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
fun lyricsAnimationStyleLabel(style: app.hush.music.constants.LyricsAnimationStyle): String =
    when (style) {
        app.hush.music.constants.LyricsAnimationStyle.NONE -> stringResource(R.string.none)
        app.hush.music.constants.LyricsAnimationStyle.FADE -> stringResource(R.string.fade)
        app.hush.music.constants.LyricsAnimationStyle.GLOW -> stringResource(R.string.glow)
        app.hush.music.constants.LyricsAnimationStyle.SLIDE -> stringResource(R.string.slide)
        app.hush.music.constants.LyricsAnimationStyle.KARAOKE -> stringResource(R.string.karaoke)
        app.hush.music.constants.LyricsAnimationStyle.APPLE -> stringResource(R.string.apple_music_style)
        app.hush.music.constants.LyricsAnimationStyle.APPLE_V2 -> stringResource(R.string.apple_music_style_letter)
        app.hush.music.constants.LyricsAnimationStyle.HUSH_FLUID -> stringResource(R.string.hush_music_fluid)
        app.hush.music.constants.LyricsAnimationStyle.LYRICS_V2 -> stringResource(R.string.lyrics_v2_fluid)
        app.hush.music.constants.LyricsAnimationStyle.METRO_LYRICS -> stringResource(R.string.lyrics_animation_metro)
        app.hush.music.constants.LyricsAnimationStyle.OCEAN_WAVE -> stringResource(R.string.lyrics_animation_ocean_wave)
    }
