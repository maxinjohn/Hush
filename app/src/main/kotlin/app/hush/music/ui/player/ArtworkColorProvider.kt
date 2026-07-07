/*
 * Hush (2026)
 * Artwork color provider — loads the current album artwork via Coil, runs
 * Palette extraction, and returns a small list of vibrant colors that the
 * audio visualizer can use as a dynamic multi-color gradient.
 *
 * This is a new feature not present in the parent ArchiveTune project.
 */

package app.hush.music.ui.player

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.hush.music.ui.theme.PlayerColorExtractor

/**
 * Composable that observes [artworkUrl] and, when it changes, loads the image
 * and extracts a small vibrant palette. Returns null while loading or if the
 * url is blank.
 */
@Composable
fun rememberArtworkPaletteColors(
    artworkUrl: String?,
    fallbackColor: Color = Color.DarkGray,
): List<Color>? {
    val context = LocalContext.current
    var paletteColors by remember(artworkUrl) { mutableStateOf<List<Color>?>(null) }
    val imageLoader = context.imageLoader

    LaunchedEffect(artworkUrl, imageLoader) {
        val url = artworkUrl
        if (url.isNullOrBlank()) {
            paletteColors = null
            return@LaunchedEffect
        }

        paletteColors = null // reset while loading

        val colors =
            withContext(Dispatchers.IO) {
                runCatching {
                    val request =
                        ImageRequest
                            .Builder(context)
                            .data(url)
                            .size(PlayerColorExtractor.Config.IMAGE_SIZE)
                            .memoryCacheKey(url)
                            .diskCacheKey(url)
                            .allowHardware(false)
                            .build()
                    val result = imageLoader.execute(request)
                    val bitmap = result.image?.toBitmap() ?: return@runCatching null

                    val palette =
                        Palette
                            .from(bitmap)
                            .maximumColorCount(PlayerColorExtractor.Config.MAX_COLOR_COUNT)
                            .resizeBitmapArea(PlayerColorExtractor.Config.BITMAP_AREA)
                            .generate()

                    PlayerColorExtractor.extractGradientColors(
                        palette = palette,
                        fallbackColor = fallbackColor.toArgb(),
                    )
                }.getOrNull()
            }

        if (colors != null && colors.size >= 2) {
            // Take the first 3 most distinct colors for the visualizer gradient
            paletteColors = colors.take(3)
        } else {
            paletteColors = null
        }
    }

    return paletteColors
}
