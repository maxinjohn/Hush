/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.constants

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import app.hush.music.utils.rememberEnumPreference

@Composable
fun rememberPlayerDesignStylePreference(): Pair<PlayerDesignStyle, (PlayerDesignStyle) -> Unit> {
    val (storedStyle, onStyleChange) =
        rememberEnumPreference(
            key = PlayerDesignStyleKey,
            defaultValue = PlayerDesignStyle.DEFAULT,
        )
    val playerDesignStyle = storedStyle.normalized()

    LaunchedEffect(storedStyle) {
        if (storedStyle != playerDesignStyle) {
            onStyleChange(playerDesignStyle)
        }
    }

    return playerDesignStyle to onStyleChange
}

@Composable
fun rememberPlayerDesignStyle(): PlayerDesignStyle {
    val (style, _) = rememberPlayerDesignStylePreference()
    return style
}
