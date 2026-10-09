/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.hush.music.LocalPlayerConnection
import app.hush.music.constants.MiniPlayerBackgroundStyle
import app.hush.music.constants.MiniPlayerBackgroundStyleKey
import app.hush.music.constants.MiniPlayerHeight
import app.hush.music.constants.PlayerDesignStyle
import app.hush.music.constants.SwipeSensitivityKey
import app.hush.music.constants.rememberPlayerDesignStyle
import app.hush.music.ui.component.rememberFramePhase
import app.hush.music.ui.theme.PlayerColorExtractor
import app.hush.music.ui.utils.resolvePlaybackArtworkUrl
import app.hush.music.utils.rememberEnumPreference
import app.hush.music.utils.rememberPreference
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MiniPlayerPaletteCacheSize = 24

/** One full swell of the pill's glow: up and back down, 5.2s a leg like the full player's. */
private const val AuraGlowBreathPeriodMillis = 5200L

@Composable
fun MiniPlayer(
    position: Long,
    duration: Long,
    modifier: Modifier = Modifier,
    pureBlack: Boolean,
) {
    NewMiniPlayer(
        position = position,
        duration = duration,
        modifier = modifier,
        pureBlack = pureBlack,
    )
}

@Composable
private fun NewMiniPlayer(
    position: Long,
    duration: Long,
    modifier: Modifier = Modifier,
    pureBlack: Boolean,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current
    val coroutineScope = rememberCoroutineScope()
    val swipeSensitivity by rememberPreference(SwipeSensitivityKey, 0.73f)
    val swipeThumbnail by rememberPreference(app.hush.music.constants.SwipeThumbnailKey, true)
    val miniPlayerBackgroundStyle by rememberEnumPreference(
        key = MiniPlayerBackgroundStyleKey,
        defaultValue = MiniPlayerBackgroundStyle.THEME,
    )
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    var gradientColors by remember {
        mutableStateOf<List<Color>>(emptyList())
    }
    val gradientColorsCache =
        remember {
            object : LinkedHashMap<String, List<Color>>(MiniPlayerPaletteCacheSize, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Color>>?): Boolean =
                    size > MiniPlayerPaletteCacheSize
            }
        }
    val fallbackColor = MaterialTheme.colorScheme.surface.toArgb()
    val auraActive = rememberPlayerDesignStyle() == PlayerDesignStyle.AURA
    val shouldUseArtworkBackground =
        miniPlayerBackgroundStyle != MiniPlayerBackgroundStyle.THEME || auraActive

    LaunchedEffect(
        mediaMetadata?.id,
        mediaMetadata?.thumbnailUrl,
        shouldUseArtworkBackground,
        auraActive,
        fallbackColor,
    ) {
        if (!shouldUseArtworkBackground) {
            gradientColors = emptyList()
            return@LaunchedEffect
        }

        val currentMetadata = mediaMetadata
        // The same URL the artwork actually displays: thumbnailUrl alone misses covers whose
        // image only exists on the media item (local/cached files), and without a fetchable URL
        // the whole palette comes back empty - no glow background, no accent hues, no ring.
        val thumbnailUrl = currentMetadata.resolvePlaybackArtworkUrl()
        if (currentMetadata == null || thumbnailUrl.isNullOrBlank()) {
            gradientColors = emptyList()
            return@LaunchedEffect
        }

        val cachedColors = gradientColorsCache[currentMetadata.id]
        if (cachedColors != null) {
            gradientColors = cachedColors
            return@LaunchedEffect
        }

        val request =
            ImageRequest
                .Builder(context)
                .data(thumbnailUrl)
                .size(PlayerColorExtractor.Config.IMAGE_SIZE, PlayerColorExtractor.Config.IMAGE_SIZE)
                .allowHardware(false)
                .build()

        val extractedColors =
            runCatching {
                val result =
                    withContext(Dispatchers.IO) {
                        context.imageLoader.execute(request)
                    }
                val bitmap = result.image?.toBitmap() ?: return@runCatching emptyList()
                val palette =
                    withContext(Dispatchers.Default) {
                        Palette
                            .from(bitmap)
                            .maximumColorCount(PlayerColorExtractor.Config.MAX_COLOR_COUNT)
                            .resizeBitmapArea(PlayerColorExtractor.Config.BITMAP_AREA)
                            .generate()
                    }
                PlayerColorExtractor.extractGradientColors(
                    palette = palette,
                    fallbackColor = fallbackColor,
                )
            }.getOrDefault(emptyList())

        if (extractedColors.isNotEmpty()) {
            gradientColorsCache[currentMetadata.id] = extractedColors
        }
        gradientColors = extractedColors
    }

    val backgroundPalette =
        remember(gradientColors) {
            MiniPlayerBackgroundPalette.from(gradientColors)
        }
    val effectiveBackgroundStyle =
        when {
            // The Aura theme carries its artwork glow all the way down to the mini pill.
            auraActive && backgroundPalette != null -> MiniPlayerBackgroundStyle.GLOW
            shouldUseArtworkBackground && backgroundPalette != null -> miniPlayerBackgroundStyle
            else -> MiniPlayerBackgroundStyle.THEME
        }

    // Aura accents: the same vivid, punched-up artwork hues the full player uses, so the mini
    // pill's play gradient and progress ring carry the cover's color instead of the theme's.
    val auraAccentColors =
        remember(gradientColors) {
            gradientColors
                .filter { maxOf(it.red, it.green, it.blue) - minOf(it.red, it.green, it.blue) >= 0.14f }
                .map { it.withAuraPunch() }
                .take(2)
        }
    val auraPrimaryGradient =
        if (auraActive && auraAccentColors.size >= 2) {
            remember(auraAccentColors) { Brush.linearGradient(auraAccentColors) }
        } else {
            null
        }

    val contentColors =
        rememberMiniPlayerContentColors(
            useArtworkBackground = effectiveBackgroundStyle != MiniPlayerBackgroundStyle.THEME,
            auraAccent = if (auraActive) auraAccentColors.firstOrNull() else null,
        )
    // The hue the pill's rim glow and border are painted in: the cover's own accent (the same
    // punch the play gradient uses), or the theme primary until artwork colors arrive.
    val auraAccent = auraAccentColors.getOrElse(0) { MaterialTheme.colorScheme.primary }

    SwipeableMiniPlayerBox(
        modifier = modifier,
        swipeSensitivity = swipeSensitivity,
        swipeThumbnail = swipeThumbnail,
        playerConnection = playerConnection,
        layoutDirection = layoutDirection,
        coroutineScope = coroutineScope,
        pureBlack = pureBlack,
        useLegacyBackground = false,
    ) { swipeOffset ->
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(MiniPlayerHeight)
                    // Drawn, not placed: a swipe moves this on every pointer sample, and the offset
                    // is read inside the layer block so that costs a redraw of the mini player
                    // rather than a recomposition of it and its content.
                    .graphicsLayer { translationX = swipeOffset() },
        ) {
            // The glow lives outside the pill's clip so its halo can spill past the rim; the
            // pill itself is drawn on top and covers the strokes' inner halves.
            if (auraActive) {
                AuraPillAccentGlow(
                    accent = auraAccent,
                    isPlaying = isPlaying,
                    modifier = Modifier.matchParentSize(),
                )
            }
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(MiniPlayerHeight / 2)),
            ) {
                MiniPlayerBackground(
                    style = effectiveBackgroundStyle,
                    palette = backgroundPalette,
                    modifier = Modifier.fillMaxSize(),
                )
                NewMiniPlayerContent(
                    position = position,
                    duration = duration,
                    playerConnection = playerConnection,
                    colors = contentColors,
                    primaryGradient = auraPrimaryGradient,
                    auraAccents =
                        if (auraActive) {
                            auraAccentColors.ifEmpty { listOf(auraAccent) }
                        } else {
                            emptyList()
                        },
                )
                if (auraActive) {
                    // The sheet clips whatever paints above the pill, so the top edge gets its
                    // accent line from the inside: a rim all the way around, with the outer halo
                    // showing wherever the sheet lets it through.
                    AuraPillInnerRim(
                        accent = auraAccent,
                        isPlaying = isPlaying,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
        }
    }
}

/**
 * The breathing pulse Aura's glow rides on: 0.74 at rest, swelling to 1.0 with the music - the
 * same curve and period as [AuraGlowBackground]'s, but advanced by frame time instead of a
 * Compose animation, so it still breathes on a device with `animator_duration_scale = 0`, where
 * `rememberInfiniteTransition` would hold its first frame forever. A remembered helper so the
 * pill's outer halo and inner rim each own a small loop instead of recomposing the whole mini
 * player per frame.
 */
@Composable
private fun rememberAuraGlowBreath(
    isPlaying: Boolean,
): Float {
    val phase = rememberFramePhase(AuraGlowBreathPeriodMillis)
    if (!isPlaying) return 0.74f
    // Ping-pong, not a sawtooth: the glow swells and recedes without snapping back at the seam.
    val swell = 1f - abs(phase * 2f - 1f)
    return 0.74f + swell * 0.26f
}

/**
 * Aura's accent rim: layered strokes on the pill's rounded rect whose alpha falls off with
 * distance, so the falloff itself reads as a glow - no blur pass, the same rule the full
 * player's backdrop follows, so it renders identically on every API level. The halo breathes
 * with the music exactly like [AuraGlowBackground], and the outermost stroke is the crisp
 * accent border the glow hangs off of. Drawn outside the pill's clip so the halo can spill past
 * the rim; the pill covers the strokes' inner halves.
 */
@Composable
private fun AuraPillAccentGlow(
    accent: Color,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val breath = rememberAuraGlowBreath(isPlaying)

    Box(
        modifier =
            modifier.drawWithCache {
                val corner = CornerRadius(size.height / 2f)
                // Widest first: every nearer layer stacks its alpha onto the one behind it, so
                // brightness builds towards the rim instead of smearing outward.
                val glowLayers =
                    listOf(
                        9.dp.toPx() to 0.07f,
                        6.dp.toPx() to 0.11f,
                        4.dp.toPx() to 0.17f,
                        2.5.dp.toPx() to 0.27f,
                    )
                // 2dp wide: the pill hides its inner half, so what shows past the edge is a 1dp
                // accent hairline - the border the halo reads as growing out of.
                val border = 2.dp.toPx()
                onDrawBehind {
                    glowLayers.forEach { (width, alpha) ->
                        drawRoundRect(
                            color = accent.copy(alpha = alpha * breath),
                            cornerRadius = corner,
                            style = Stroke(width = width),
                        )
                    }
                    drawRoundRect(
                        color = accent.copy(alpha = 0.65f * breath),
                        cornerRadius = corner,
                        style = Stroke(width = border),
                    )
                }
            },
    )
}

/**
 * The accent line drawn just inside the pill's clip: the sheet that hosts the collapsed mini
 * player cuts off whatever paints above its top edge, so the outer halo alone would leave the
 * top edge bare. This gives every edge its rim, breathing in step with the outer halo; at the
 * bottom and sides the two join into one continuous border with the halo spilling outward.
 */
@Composable
private fun AuraPillInnerRim(
    accent: Color,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val breath = rememberAuraGlowBreath(isPlaying)
    Box(
        modifier =
            modifier.border(
                // 1dp matches the outer hairline's visible half, so the bottom and sides read as
                // one 2dp rim (outer 1dp + inner 1dp) and the clipped top edge still gets 1dp.
                width = 1.dp,
                color = accent.copy(alpha = 0.65f * breath),
                shape = RoundedCornerShape(MiniPlayerHeight / 2),
            ),
    )
}

@Composable
private fun rememberMiniPlayerContentColors(
    useArtworkBackground: Boolean,
    auraAccent: Color? = null,
): MiniPlayerContentColors {
    val colorScheme = MaterialTheme.colorScheme
    return remember(
        useArtworkBackground,
        auraAccent,
        colorScheme.primary,
        colorScheme.outline,
        colorScheme.onSurface,
        colorScheme.onSurfaceVariant,
        colorScheme.surface,
        colorScheme.surfaceVariant,
        colorScheme.primaryContainer,
        colorScheme.onPrimaryContainer,
    ) {
        if (useArtworkBackground) {
            MiniPlayerContentColors(
                title = Color.White,
                secondary = Color.White.copy(alpha = 0.72f),
                // Aura's art ring and thin borders pick up the cover's accent so the pill reads
                // as part of the player theme rather than a generic glass bar.
                progress = auraAccent ?: Color.White,
                progressTrack = Color.White.copy(alpha = 0.24f),
                artworkContainer = Color.White.copy(alpha = 0.14f),
                artworkBorder = auraAccent?.copy(alpha = 0.55f) ?: Color.White.copy(alpha = 0.22f),
                secondaryButtonContainer = Color.White.copy(alpha = 0.16f),
                buttonBorder = Color.White.copy(alpha = 0.24f),
                secondaryButtonIcon = Color.White,
                disabledButtonIcon = Color.White.copy(alpha = 0.38f),
                togetherContainer = Color.White.copy(alpha = 0.16f),
                togetherContent = Color.White,
            )
        } else {
            MiniPlayerContentColors(
                title = colorScheme.onSurface,
                secondary = colorScheme.onSurfaceVariant,
                progress = colorScheme.primary,
                progressTrack = colorScheme.outline.copy(alpha = 0.18f),
                artworkContainer = colorScheme.surfaceVariant,
                artworkBorder = colorScheme.outline.copy(alpha = 0.2f),
                secondaryButtonContainer = colorScheme.primary.copy(alpha = 0.16f),
                buttonBorder = colorScheme.primary.copy(alpha = 0.35f),
                // The previous/next glyph rides on a tinted disc, not on a filled accent one, so
                // it must not be tinted with onPrimary - on a dark theme that drew a dark glyph
                // on a dark disc and made a live button read as a disabled one.
                secondaryButtonIcon = colorScheme.primary,
                disabledButtonIcon = colorScheme.onSurface.copy(alpha = 0.38f),
                togetherContainer = colorScheme.primaryContainer,
                togetherContent = colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun MiniPlayerBackground(
    style: MiniPlayerBackgroundStyle,
    palette: MiniPlayerBackgroundPalette?,
    modifier: Modifier = Modifier,
) {
    when (style) {
        MiniPlayerBackgroundStyle.THEME -> {
            val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            Box(
                modifier =
                    modifier
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f))
                        .border(width = 0.5.dp, color = borderColor, shape = RoundedCornerShape(MiniPlayerHeight / 2)),
            )
        }

        MiniPlayerBackgroundStyle.GRADIENT -> {
            val colors = requireNotNull(palette)
            Box(modifier = modifier) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colorStops =
                                        arrayOf(
                                            0f to colors.first.copy(alpha = 0.95f),
                                            0.52f to colors.second.copy(alpha = 0.82f),
                                            1f to colors.third.copy(alpha = 0.72f),
                                        ),
                                ),
                            ),
                )
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.32f)),
                )
            }
        }

        MiniPlayerBackgroundStyle.GLOW -> {
            // The cover's hues at *surface* strength: the same tone the full player's wash takes, so the
            // pill reads as a dark tinted surface on the app's dark background instead of announcing
            // the cover's brightest hue as a slab of it. The rim, the play gradient and the progress
            // ring keep the punch - those are the accents, this is a surface.
            val colors = requireNotNull(palette).tonedForSurface()
            Box(
                modifier =
                    modifier.drawWithCache {
                        val width = size.width
                        val height = size.height
                        val startGlow =
                            Brush.radialGradient(
                                colors = listOf(colors.first.copy(alpha = 0.82f), colors.first.copy(alpha = 0.38f), Color.Transparent),
                                center = Offset(width * 0.12f, height * 0.42f),
                                radius = width * 0.72f,
                            )
                        val endGlow =
                            Brush.radialGradient(
                                colors = listOf(colors.second.copy(alpha = 0.78f), colors.second.copy(alpha = 0.34f), Color.Transparent),
                                center = Offset(width * 0.88f, height * 0.58f),
                                radius = width * 0.72f,
                            )
                        val topGlow =
                            Brush.radialGradient(
                                colors = listOf(colors.third.copy(alpha = 0.58f), Color.Transparent),
                                center = Offset(width * 0.52f, height * 0.05f),
                                radius = width * 0.54f,
                            )
                        val bottomGlow =
                            Brush.radialGradient(
                                colors = listOf(colors.fourth.copy(alpha = 0.46f), Color.Transparent),
                                center = Offset(width * 0.46f, height * 1.05f),
                                radius = width * 0.54f,
                            )

                        onDrawBehind {
                            drawRect(Color.Black)
                            drawRect(startGlow)
                            drawRect(endGlow)
                            drawRect(topGlow)
                            drawRect(bottomGlow)
                            drawRect(Color.Black.copy(alpha = 0.24f))
                        }
                    },
            )
        }
    }
}

@Immutable
private data class MiniPlayerBackgroundPalette(
    val first: Color,
    val second: Color,
    val third: Color,
    val fourth: Color,
) {
    companion object {
        fun from(colors: List<Color>): MiniPlayerBackgroundPalette? {
            val first = colors.firstOrNull() ?: return null
            val second = colors.getOrElse(1) { first }
            val third = colors.getOrElse(2) { second }
            val fourth = colors.getOrElse(3) { first }
            return MiniPlayerBackgroundPalette(
                first = first,
                second = second,
                third = third,
                fourth = fourth,
            )
        }
    }

    /**
     * The palette as surface colours: every entry through [withAuraBackdropTone].
     *
     * Kept on the palette rather than at the call site so the GLOW branch cannot tone three of its
     * four glows and miss the fourth - the pills' four glows are drawn from four fields, and one
     * untoned field would be the brightest thing on the pill, which is the state this fixes.
     */
    fun tonedForSurface(): MiniPlayerBackgroundPalette =
        MiniPlayerBackgroundPalette(
            first = first.withAuraBackdropTone(),
            second = second.withAuraBackdropTone(),
            third = third.withAuraBackdropTone(),
            fourth = fourth.withAuraBackdropTone(),
        )
}
