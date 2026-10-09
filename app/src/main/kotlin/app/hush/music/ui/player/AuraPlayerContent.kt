/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.player

import android.content.Context
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin
import androidx.media3.common.Player.STATE_ENDED
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.hush.music.LocalAnimationsDisabled
import app.hush.music.R
import app.hush.music.constants.AuraLandscapeArtBottomReserve
import app.hush.music.constants.AuraLandscapeSeekSharedStroke
import app.hush.music.constants.AuraLandscapeSeekStripHeight
import app.hush.music.constants.EnableHapticFeedbackKey
import app.hush.music.constants.SliderStyle
import app.hush.music.db.entities.containerLabel
import app.hush.music.db.entities.formattedBitrate
import app.hush.music.db.entities.formattedFileSize
import app.hush.music.playback.FormatRow
import app.hush.music.extensions.togglePlayPause
import app.hush.music.models.MediaMetadata
import app.hush.music.playback.PlayerConnection
import app.hush.music.ui.component.BottomSheetPageState
import app.hush.music.ui.component.BottomSheetState
import app.hush.music.ui.component.MenuState
import app.hush.music.ui.component.hushBouncyClickable
import app.hush.music.ui.component.hushMarquee
import app.hush.music.ui.component.rememberFramePhase
import app.hush.music.ui.theme.HushDesign
import app.hush.music.ui.theme.PlayerColorExtractor
import app.hush.music.ui.theme.hushPressable
import app.hush.music.utils.makeTimeString
import app.hush.music.utils.rememberPreference
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.palette.graphics.Palette
import kotlin.math.abs
import kotlin.math.exp

/**
 * Aura - the artwork-driven player style.
 *
 * The whole surface breathes with colors extracted from the current cover: a radial glow top and
 * bottom, an oversized marquee title, a gradient seek track with a glowing thumb, a gradient play
 * button and glass chips. Portrait and landscape share every control the Expressive style offers;
 * only the arrangement changes. The queue sheet (bottom pills) and the mini player stay in charge
 * of queue/lyrics/sleep entry points, exactly like V6.
 */

/** Chroma of a color (0 = gray, 1 = fully saturated). */
private fun Color.chroma(): Float = maxOf(red, green, blue) - minOf(red, green, blue)

/** Lifts and saturates extracted colors into a vivid band so accents never go pastel or grey. */
internal fun Color.withAuraPunch(): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    // Muted artwork hues become real hues again; near-greys are filtered before this runs.
    if (hsv[1] > 0.05f) {
        hsv[1] = (hsv[1] * 1.7f).coerceAtLeast(0.62f)
    }
    // Land brightness in the punchy band - dark enough to feel rich, bright enough to glow.
    hsv[2] = hsv[2].coerceIn(0.58f, 0.86f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * The same artwork hue as [withAuraPunch], landed in a *dark* band so a large surface can carry it.
 *
 * A punch is an accent: it forces saturation up and brightness into 0.58..0.86, which is right for a
 * play button and wrong for a full-bleed wash. Painted across the whole player, the punched colour
 * becomes the colour of the screen - a cover with a blue accent (this one is a deep ultramarine)
 * turned the player into a solid blue slab that read as a different app from the neutral-dark
 * screens around it, and it forced the seek bar and the slider to frost-white so their positions
 * stayed visible against it.
 *
 * The tone keeps the hue (the cover still tints the player, which is the point of Aura) and drops
 * saturation and brightness into a band that reads as a *dark tinted surface* rather than a colour:
 * at the wash's 0.55 alpha the result lands around the app's own dark background while the hue stays
 * legible. Accents keep using [withAuraPunch]; only the surfaces take this tone.
 */
internal fun Color.withAuraBackdropTone(): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    return Color(android.graphics.Color.HSVToColor(auraBackdropTone(hsv)))
}

/**
 * The band rule behind [withAuraBackdropTone], on raw HSV so a JVM test can pin it: the colour
 * conversion around it is `android.graphics`, which a unit test cannot call.
 *
 * Pulls chroma down with the brightness. A dark *vivid* colour still reads as a bright colour on a
 * dark screen, so the backdrop has to surrender saturation to stay a surface - and the hue is left
 * untouched, because the hue is the whole reason the tint exists.
 */
internal fun auraBackdropTone(hsv: FloatArray): FloatArray {
    hsv[1] = (hsv[1] * AuraBackdropSaturationScale).coerceIn(0f, AuraBackdropSaturationMax)
    hsv[2] = hsv[2].coerceIn(AuraBackdropValueMin, AuraBackdropValueMax)
    return hsv
}

/** How much of an accent's chroma a surface keeps; see [auraBackdropTone]. */
private const val AuraBackdropSaturationScale = 0.7f

/** Above this the tint stops reading as a surface and starts reading as a colour. */
internal const val AuraBackdropSaturationMax = 0.5f

/** Brightness band for a full-bleed tint. */
internal const val AuraBackdropValueMin = 0.08f
internal const val AuraBackdropValueMax = 0.30f

/** Remembers the artwork gradient for [thumbnailUrl], with a small in-composition LRU cache. */
@Composable
fun rememberAuraColors(thumbnailUrl: String?): List<Color> {
    val context = LocalContext.current
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val fallback = remember(primary, secondary) { listOf(primary, secondary) }
    var extracted by remember(thumbnailUrl) { mutableStateOf(emptyList<Color>()) }
    val cache =
        remember {
            object : LinkedHashMap<String, List<Color>>(8, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Color>>?): Boolean = size > 8
            }
        }

    LaunchedEffect(thumbnailUrl) {
        if (thumbnailUrl.isNullOrBlank()) {
            extracted = emptyList()
            return@LaunchedEffect
        }
        cache[thumbnailUrl]?.let {
            extracted = it
            return@LaunchedEffect
        }
        val colors =
            runCatching {
                val request =
                    ImageRequest
                        .Builder(context)
                        .data(thumbnailUrl)
                        .size(PlayerColorExtractor.Config.IMAGE_SIZE, PlayerColorExtractor.Config.IMAGE_SIZE)
                        .allowHardware(false)
                        .build()
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
                    fallbackColor = fallback.first().toArgb(),
                )
            }.getOrDefault(emptyList())
        // The extractor can hand back dark grey stops for muted artwork; grey accents would
        // drain the glow, the seek and the transport, so keep only vivid colors (punched up)
        // and let the theme colors carry the rest.
        val vivid = colors.filter { it.chroma() >= 0.14f }.map { it.withAuraPunch() }
        val usable = if (vivid.size >= 2) vivid.take(2) else emptyList()
        if (usable.isNotEmpty()) {
            cache[thumbnailUrl] = usable
        }
        extracted = usable
    }

    return if (extracted.isNotEmpty()) extracted else fallback
}

/**
 * Full-bleed aura backdrop: a tinted vertical wash plus two radial glows that breathe while the
 * music plays. Pure gradients - no blur - so it renders identically on every API level.
 *
 * The wash takes the artwork hues through [withAuraBackdropTone], never raw: an accent is sized for a
 * button, and at full-bleed the same colour becomes the colour of the screen. See the tone's docs.
 */
@Composable
fun AuraGlowBackground(
    colors: List<Color>,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val wash = remember(colors) { colors.map { it.withAuraBackdropTone() } }
    val c1 by animateColorAsState(wash.getOrElse(0) { MaterialTheme.colorScheme.primary }, tween(700), label = "auraGlow1")
    val c2 by animateColorAsState(wash.getOrElse(1) { c1 }, tween(700), label = "auraGlow2")
    val transition = rememberInfiniteTransition(label = "auraBreath")
    val phase by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Reverse),
            label = "auraPhase",
        )
    val breath = if (isPlaying) 0.74f + phase * 0.26f else 0.74f

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val w = size.width
                        val h = size.height
                        val base =
                            Brush.verticalGradient(
                                0f to c1.copy(alpha = 0.55f * breath),
                                0.45f to c1.copy(alpha = 0.22f * breath),
                                1f to Color(0xFF05070B),
                            )
                        val topGlow =
                            Brush.radialGradient(
                                listOf(c1.copy(alpha = 0.55f * breath), c1.copy(alpha = 0.22f * breath), Color.Transparent),
                                center = Offset(w * 0.5f, h * 0.06f),
                                radius = w * 0.95f,
                            )
                        val bottomGlow =
                            Brush.radialGradient(
                                listOf(c2.copy(alpha = 0.55f * breath), c2.copy(alpha = 0.20f * breath), Color.Transparent),
                                center = Offset(w * 0.30f, h * 0.98f),
                                radius = w * 0.85f,
                            )
                        onDrawBehind {
                            drawRect(base)
                            drawRect(topGlow)
                            drawRect(bottomGlow)
                        }
                    },
        )
    }
}

/** Oversized marquee title with the centralized tap/long-press actions, plus tappable artists. */
@Composable
fun AuraTitleSection(
    mediaMetadata: MediaMetadata,
    navController: NavController,
    state: BottomSheetState,
    centerAligned: Boolean = true,
    titleSize: TextUnit = 30.sp,
    maxLines: Int = 2,
    /** Gap between the title and the artist line; landscape trims it when height is scarce. */
    subtitleSpacing: Dp = 6.dp,
    modifier: Modifier = Modifier,
) {
    val actions =
        rememberPlayerTitleActions(
            mediaMetadata = mediaMetadata,
            navController = navController,
            state = state,
        )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (centerAligned) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        AnimatedContent(
            targetState = mediaMetadata.title,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "auraTitle",
        ) { title ->
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = titleSize),
                fontWeight = FontWeight.Bold,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                color = Color.White,
                textAlign = if (centerAligned) TextAlign.Center else TextAlign.Start,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .hushMarquee(enabled = true)
                        .hushBouncyClickable(
                            onClick = actions.onTitleClick,
                            onLongClick = actions.onCopyTitle,
                            pressScale = HushDesign.RowPressScale,
                            haloStrength = 0f,
                        ),
            )
        }

        Spacer(Modifier.height(subtitleSpacing))

        ClickableArtists(
            artists = mediaMetadata.artists,
            onArtistClick = actions.onArtistClick,
            style =
                MaterialTheme.typography.titleMedium.copy(
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 15.sp,
                ),
            onLongClick = actions.onCopyArtists,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .hushMarquee(enabled = true),
            textAlign = if (centerAligned) TextAlign.Center else TextAlign.Start,
        )
    }
}

/**
 * Gradient seek bar with a glowing thumb. Touch is jump-to-position with drag follow, on a 40dp
 * hit strip, so scrubbing is immediate without being twitchy.
 */
@Composable
fun AuraSeek(
    sliderPosition: Long?,
    position: Long,
    duration: Long,
    onValueChange: (Long) -> Unit,
    onValueChangeFinished: () -> Unit,
    accent: Color,
    accentSecondary: Color,
    isPlaying: Boolean = false,
    /** Touch strip height. Portrait keeps 40dp; landscape passes a slimmer strip. */
    hitHeight: Dp = 40.dp,
    /**
     * Landscape pins the track to the bottom of its touch strip, so the bar sits a few lines above
     * the time labels and the transport instead of floating in the middle of the strip. The hit
     * target keeps its full height above the bar, so scrubbing stays just as easy.
     */
    barBottomAligned: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val seekEnabled = duration > 0L
    val progress = (sliderPosition ?: position).coerceIn(0L, duration.coerceAtLeast(0L))
    val fraction = if (duration > 0L) progress.toFloat() / duration.toFloat() else 0f
    val animatedFraction by
        animateFloatAsState(
            targetValue = fraction,
            animationSpec = tween(110),
            label = "auraSeek",
        )
    var trackWidthPx by remember { mutableFloatStateOf(1f) }
    val glowTransition = rememberInfiniteTransition(label = "auraThumbGlow")
    val glowPhase by
        glowTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
            label = "auraThumbPhase",
        )
    val glowAlpha = if (isPlaying) 0.32f + glowPhase * 0.26f else 0.32f

    fun valueAt(x: Float): Long = ((x / trackWidthPx).coerceIn(0f, 1f) * duration).toLong()

    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(hitHeight)
                .onSizeChanged { trackWidthPx = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(duration) {
                    if (!seekEnabled) return@pointerInput
                    detectTapGestures { offset ->
                        onValueChange(valueAt(offset.x))
                        onValueChangeFinished()
                    }
                }
                .pointerInput(duration) {
                    if (!seekEnabled) return@pointerInput
                    var startValue = 0L
                    var accumulated = 0f
                    detectDragGestures(
                        onDragStart = { offset ->
                            accumulated = 0f
                            startValue = valueAt(offset.x)
                            onValueChange(startValue)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            accumulated += dragAmount.x
                            val next =
                                startValue + ((accumulated / trackWidthPx) * duration).toLong()
                            onValueChange(next.coerceIn(0L, duration))
                        },
                        onDragEnd = { onValueChangeFinished() },
                        onDragCancel = { onValueChangeFinished() },
                    )
                },
    ) {
        val trackHeight = 10.dp.toPx()
        val centerY =
            if (barBottomAligned) {
                size.height - trackHeight / 2f - 4.dp.toPx()
            } else {
                size.height / 2f
            }
        val topLeft = Offset(0f, centerY - trackHeight / 2f)

        drawRoundRect(
            color = Color.White.copy(alpha = 0.14f),
            topLeft = topLeft,
            size = Size(size.width, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f),
        )
        // Frost-white fill, never the accent: the aura wash is sampled from the artwork's
        // accent, so an accent-coloured bar painted itself into the background and the seek
        // position was invisible on most covers. The accent survives only as the thumb jewel.
        drawRoundRect(
            brush =
                Brush.horizontalGradient(
                    listOf(Color.White, Color.White.copy(alpha = 0.72f)),
                ),
            topLeft = topLeft,
            size = Size(size.width * animatedFraction, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f),
        )

        val thumbCenter = Offset(size.width * animatedFraction, centerY)
        drawCircle(
            color = Color.White.copy(alpha = glowAlpha),
            // A bottom-aligned bar has no room beneath it for the full halo, so the glow tightens
            // instead of bleeding onto the time labels below.
            radius = if (barBottomAligned) 14.dp.toPx() else 22.dp.toPx(),
            center = thumbCenter,
        )
        drawCircle(
            color = accent,
            radius = 8.dp.toPx(),
            center = thumbCenter,
        )
    }
}

/**
 * Slider dispatch: the fancy gradient bar is Aura's rendering of the Standard slider style, and
 * every other style from Settings \u2192 Player slider style renders through the shared
 * [PlayerSlider] so the user's choice is honored inside the Aura theme too.
 */
@Composable
fun AuraSeekRow(
    sliderStyle: SliderStyle,
    sliderPosition: Long?,
    position: Long,
    duration: Long,
    isPlaying: Boolean,
    onValueChange: (Long) -> Unit,
    onValueChangeFinished: () -> Unit,
    accent: Color,
    accentSecondary: Color,
    horizontalPadding: Dp,
    hitHeight: Dp = 40.dp,
    barBottomAligned: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (sliderStyle == SliderStyle.Standard) {
        AuraSeek(
            sliderPosition = sliderPosition,
            position = position,
            duration = duration,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            accent = accent,
            accentSecondary = accentSecondary,
            isPlaying = isPlaying,
            hitHeight = hitHeight,
            barBottomAligned = barBottomAligned,
            modifier = modifier.padding(horizontal = horizontalPadding),
        )
    } else {
        PlayerSlider(
            sliderStyle = sliderStyle,
            sliderPosition = sliderPosition,
            position = position,
            duration = duration,
            isPlaying = isPlaying,
            // Frost white instead of the accent - same reasoning as AuraSeek: the wash is
            // the accent's own colour, so a Wavy/Thick/Simple bar in accent vanished into it.
            textButtonColor = Color.White,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            horizontalPadding = horizontalPadding,
            modifier = modifier,
        )
    }
}

/** Time labels that line up exactly with the seek bar's edges. */
@Composable
fun AuraTimeRow(
    sliderPosition: Long?,
    position: Long,
    duration: Long,
    color: Color,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = makeTimeString(sliderPosition ?: position),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
        Text(
            text = makeTimeString(duration),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

/** The glass "NOW PLAYING" pill with a live three-bar equalizer - Aura's signature flourish. */
@Composable
fun AuraNowPlayingPill(
    isPlaying: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "auraEq")
    val phase by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
            label = "auraEqPhase",
        )

    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.12f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.height(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            repeat(3) { index ->
                val offset = phase + index * 0.33f
                val wave = if (isPlaying) (sin(offset * 2f * Math.PI).toFloat() * 0.5f + 0.5f) else 0f
                val barHeight = (4f + wave * 10f).dp
                Box(
                    modifier =
                        Modifier
                            .width(3.dp)
                            .height(barHeight)
                            .clip(RoundedCornerShape(2.dp))
                            .background(accent),
                )
            }
        }
        Text(
            text = stringResource(R.string.aura_now_playing),
            style =
                MaterialTheme.typography.labelSmall.copy(
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            color = Color.White.copy(alpha = 0.88f),
            maxLines = 1,
        )
    }
}

/**
 * Transport sizes: portrait runs [Full]; landscape picks a preset from the height it actually
 * got, so a short phone landscape row, a normal one and a car screen each get controls that
 * neither crowd the column nor shrink below a thumb's reach.
 */
data class AuraTransportSizes(
    val prevNextSize: Dp,
    val prevNextIcon: Dp,
    val playSize: Dp,
    val playIcon: Dp,
    /** Row spacing, already trimmed for the play button's halo wrapper. */
    val spacing: Dp,
    /**
     * Space the halo wrapper reserves around the play button. Landscape tightens it, because
     * every dp of that ring is a dp the row needs inside a short landscape box.
     */
    val haloReserve: Dp = 14.dp,
) {
    companion object {
        /** Portrait's default. Frozen: the portrait column was signed off with these numbers. */
        val Full = AuraTransportSizes(prevNextSize = 56.dp, prevNextIcon = 26.dp, playSize = 76.dp, playIcon = 36.dp, spacing = 20.dp)

        // Landscape presets. The landscape transport used to read small next to the oversized
        // artwork; with the provenance strip lifted out of the bottom band and the seek row no
        // longer paying Material's 48dp touch row, the freed height pays for a noticeably bigger
        // row on every landscape size.
        val Roomy =
            AuraTransportSizes(prevNextSize = 68.dp, prevNextIcon = 31.dp, playSize = 88.dp, playIcon = 41.dp, spacing = 22.dp, haloReserve = 12.dp)
        val Medium =
            AuraTransportSizes(prevNextSize = 62.dp, prevNextIcon = 29.dp, playSize = 80.dp, playIcon = 38.dp, spacing = 18.dp, haloReserve = 10.dp)
        // Tight is for short windows (a small phone on its side, a car screen). Its budget is the
        // thinnest of the three, and the title block is what pays for anything over it: at 62dp of
        // play button the row plus the seek strip, the action row and the provenance line still
        // leave room for a title *and* its artist line, which the previous numbers cut in half.
        val Tight =
            AuraTransportSizes(prevNextSize = 48.dp, prevNextIcon = 24.dp, playSize = 62.dp, playIcon = 28.dp, spacing = 12.dp, haloReserve = 8.dp)
    }
}

private const val TransportWiggleMillis = 560L
private const val TWO_PI_F = 6.2831853f

/**
 * The cover breathes: a barely-there swell in time with the glow, so the artwork reads as alive
 * while the music plays and sits perfectly still when it pauses. Frame-driven like everything
 * else here - a Compose animation would hold its first frame with the animator scale at zero.
 */
@Composable
private fun Modifier.auraArtBreath(isPlaying: Boolean): Modifier {
    if (!isPlaying) return this
    val phase = rememberFramePhase(5200L)
    val swell = 1f - abs(phase * 2f - 1f)
    val scale = 1f + 0.014f * swell
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
}

/**
 * Click wiggle for prev/next, in degrees: one emphatic swing each way, then two that die out.
 * A skip should read as a skip even if the track change is instant.
 */
private fun wiggleDegrees(elapsedMillis: Long): Float {
    if (elapsedMillis <= 0L || elapsedMillis >= TransportWiggleMillis) return 0f
    val t = elapsedMillis.toFloat() / TransportWiggleMillis
    return 17f * exp(-3.1f * t) * sin(TWO_PI_F * 2.4f * t)
}

/**
 * Click pop for the play icon: springs past its own size, dips through a small squash and
 * settles - the squash-and-stretch a transport button gets when it changes what it means.
 */
private fun popScale(elapsedMillis: Long): Float {
    if (elapsedMillis <= 0L || elapsedMillis >= TransportWiggleMillis) return 1f
    val t = elapsedMillis.toFloat() / TransportWiggleMillis
    return 1f + 0.24f * exp(-2.8f * t) * sin(TWO_PI_F * 1.1f * t)
}

/**
 * The wiggle for the click numbered [trigger], driven by frame time - a Compose animation would
 * paint one frame and hold it on a device with `animator_duration_scale = 0`, and the transport
 * must jiggle on every device. Zero until the first click, zero again once the motion is spent,
 * so an idle row costs no frames.
 */
@Composable
private fun rememberClickWiggle(trigger: Int): Float {
    if (trigger == 0) return 0f
    if (LocalAnimationsDisabled.current) return 0f
    var angle by remember(trigger) { mutableFloatStateOf(0f) }
    LaunchedEffect(trigger) {
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            val running =
                withFrameNanos {
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    angle = wiggleDegrees(elapsed)
                    elapsed < TransportWiggleMillis
                }
            if (!running) break
        }
        angle = 0f
    }
    return angle
}

/** [rememberClickWiggle]'s sibling for the play icon, returning a scale instead of degrees. */
@Composable
private fun rememberClickPop(trigger: Int): Float {
    if (trigger == 0) return 1f
    if (LocalAnimationsDisabled.current) return 1f
    var scale by remember(trigger) { mutableFloatStateOf(1f) }
    LaunchedEffect(trigger) {
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            val running =
                withFrameNanos {
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    scale = popScale(elapsed)
                    elapsed < TransportWiggleMillis
                }
            if (!running) break
        }
        scale = 1f
    }
    return scale
}

/** Glass transport: prev / gradient play / next with haptics, loading ring and ended-replay. */
@Composable
fun AuraTransportRow(
    playbackState: Int,
    isPlaying: Boolean,
    isLoading: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    playerConnection: PlayerConnection,
    accent: Color,
    accentSecondary: Color,
    sizes: AuraTransportSizes = AuraTransportSizes.Full,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val (hapticsEnabled) = rememberPreference(EnableHapticFeedbackKey, true)

    fun pulse() {
        if (hapticsEnabled) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    var prevTrigger by remember { mutableIntStateOf(0) }
    var nextTrigger by remember { mutableIntStateOf(0) }
    var playTrigger by remember { mutableIntStateOf(0) }
    val prevWiggle = rememberClickWiggle(prevTrigger)
    val nextWiggle = rememberClickWiggle(nextTrigger)
    val playPop = rememberClickPop(playTrigger)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(sizes.spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(sizes.prevNextSize)
                    .graphicsLayer { rotationZ = prevWiggle }
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f))
                    // A hairline ring so the glass buttons keep a visible edge against a
                    // bright accent wash - without it the wiggle tilts an invisible circle.
                    .border(1.dp, Color.White.copy(alpha = 0.26f), CircleShape)
                    .hushPressable(
                        onClick = {
                            prevTrigger++
                            pulse()
                            playerConnection.seekToPrevious()
                        },
                        enabled = canSkipPrevious,
                        pressScale = HushDesign.RowPressScale,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.skip_previous),
                contentDescription = null,
                tint = Color.White.copy(alpha = if (canSkipPrevious) 0.95f else 0.35f),
                modifier = Modifier.size(sizes.prevNextIcon),
            )
        }

        // The wrapper reserves room for the breathing halo, so the row's spacing stays honest
        // around the button even while the ring reaches 7dp past its edge.
        // The halo ring reaches ~5-7dp past the button's edge; the reserve shrinks with the
        // button so a Tight transport row does not spend 14dp of a short screen on air.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(sizes.playSize + sizes.haloReserve),
        ) {
            if (isPlaying) {
                // A halo that breathes with the track - frame-driven, so it never freezes on a
                // device that has its animation scale switched off.
                val haloPhase = rememberFramePhase(2800L)
                val breath = sin(TWO_PI_F * haloPhase) * 0.5f + 0.5f
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                alpha = 0.16f + 0.40f * breath
                                scaleX = 0.94f + 0.06f * breath
                                scaleY = 0.94f + 0.06f * breath
                            }.border(width = 2.dp, color = accent, shape = CircleShape),
                )
            }
            Box(
                modifier =
                    Modifier
                        .size(sizes.playSize)
                        .shadow(if (sizes.playSize >= 70.dp) 22.dp else 16.dp, CircleShape, ambientColor = accent, spotColor = accent)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(accent, accentSecondary)),
                        )
                        // The play button is accent-on-accent by construction; a light ring
                        // is what separates it from the wash so its pop reads on every cover.
                        .border(1.5.dp, Color.White.copy(alpha = 0.55f), CircleShape)
                        .hushPressable(
                            onClick = {
                                playTrigger++
                                pulse()
                                if (playbackState == STATE_ENDED) {
                                    playerConnection.player.seekTo(0, 0)
                                    playerConnection.player.playWhenReady = true
                                } else {
                                    playerConnection.player.togglePlayPause()
                                }
                            },
                            pressScale = HushDesign.TransportPressScale,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                if (isLoading) {
                    FetchingIndicator(
                        modifier = Modifier.size(sizes.playSize * 0.45f),
                        color = Color(0xFF0A0D12),
                    )
                } else {
                    Icon(
                        painter =
                            painterResource(
                                when {
                                    playbackState == STATE_ENDED -> R.drawable.replay
                                    isPlaying -> R.drawable.pause
                                    else -> R.drawable.play
                                },
                            ),
                        contentDescription = null,
                        tint = Color(0xFF0A0D12),
                        modifier =
                            Modifier
                                .size(sizes.playIcon)
                                .graphicsLayer {
                                    scaleX = playPop
                                    scaleY = playPop
                                },
                    )
                }
            }
        }

        Box(
            modifier =
                Modifier
                    .size(sizes.prevNextSize)
                    .graphicsLayer { rotationZ = nextWiggle }
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f))
                    .border(1.dp, Color.White.copy(alpha = 0.26f), CircleShape)
                    .hushPressable(
                        onClick = {
                            nextTrigger++
                            pulse()
                            playerConnection.seekToNext()
                        },
                        enabled = canSkipNext,
                        pressScale = HushDesign.RowPressScale,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.skip_next),
                contentDescription = null,
                tint = Color.White.copy(alpha = if (canSkipNext) 0.95f else 0.35f),
                modifier = Modifier.size(sizes.prevNextIcon),
            )
        }
    }
}

/**
 * The Aura player surface itself: glow backdrop, artwork, oversized title, gradient seek, glass
 * transport and the action chips. [landscape] swaps the stacked portrait column for a side-by-side
 * arrangement; every control stays identical.
 */
@Composable
fun AuraPlayerContent(
    mediaMetadata: MediaMetadata,
    playbackState: Int,
    isPlaying: Boolean,
    isLoading: Boolean,
    repeatMode: Int,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    sliderPosition: Long?,
    position: Long,
    duration: Long,
    playerConnection: PlayerConnection,
    navController: NavController,
    state: BottomSheetState,
    menuState: MenuState,
    bottomSheetPageState: BottomSheetPageState,
    context: Context,
    sliderStyle: SliderStyle,
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    landscape: Boolean,
    bottomPadding: Dp,
    /** Landscape paints the provenance line inside the player column, under the action row. */
    showCodecOnPlayer: Boolean = false,
    formatRow: FormatRow? = null,
    /**
     * Landscape publishes the artwork column's width here. The queue sheet renders the chip row in
     * a different composable tree and cannot measure the player, so without this it could only
     * guess where the controls column starts - which is how the chips ended up pinned to the far
     * right while the artwork reached the bottom on the left.
     */
    onChromeWidthChange: ((Dp) -> Unit)? = null,
    /** The offset the centred composition leaves on the right; see [onChromeWidthChange]. */
    onChromeInsetChange: ((Dp) -> Unit)? = null,
    onCollapseClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentSong by playerConnection.currentSong.collectAsStateWithLifecycle(initialValue = null)
    val currentSongLiked = currentSong?.song?.liked == true
    val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsStateWithLifecycle()
    val colors = rememberAuraColors(mediaMetadata.thumbnailUrl)
    val accent = colors.getOrElse(0) { MaterialTheme.colorScheme.primary }
    val accentSecondary = colors.getOrElse(1) { accent }
    val white = Color.White

    Box(modifier = modifier.fillMaxSize()) {
        // The glow must stop at the queue peek's top edge: the player content box carries zIndex 2
        // over the peek (siblings in the sheet), so a full-bleed wash would paint straight over the
        // codec provenance line and the Queue/Lyrics pills and drown them - which is exactly why
        // the bottom source labels read as invisible while every other control stayed legible.
        AuraGlowBackground(
            colors = colors,
            isPlaying = isPlaying,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(bottom = bottomPadding),
        )

        if (!landscape) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(
                            WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                        ).padding(bottom = bottomPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AuraNowPlayingPill(isPlaying = isPlaying, accent = accent)
                    IconButton(onClick = onCollapseClick) {
                        Icon(
                            painter = painterResource(R.drawable.expand_more),
                            contentDescription = stringResource(R.string.close),
                            tint = white.copy(alpha = 0.8f),
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }

                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Thumbnail(
                        sliderPositionProvider = { sliderPosition },
                        modifier =
                            Modifier
                                .fillMaxWidth(0.88f)
                                .aspectRatio(1f)
                                .shadow(
                                    30.dp,
                                    RoundedCornerShape(26.dp),
                                    ambientColor = accent,
                                    spotColor = accent,
                                ).auraArtBreath(isPlaying),
                        showNowPlayingHeader = false,
                        isPlayerExpanded = state.isExpanded,
                        // The cover fills the bounds it was given: the 32dp-per-side inset the
                        // other styles use takes a sixth of the artwork and left Aura's hero
                        // smaller than its own layout slot.
                        edgeToEdge = true,
                    )
                }

                AuraTitleSection(
                    mediaMetadata = mediaMetadata,
                    navController = navController,
                    state = state,
                    centerAligned = true,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )

                Spacer(Modifier.height(12.dp))

                AuraSeekRow(
                    sliderStyle = sliderStyle,
                    sliderPosition = sliderPosition,
                    position = position,
                    duration = duration,
                    isPlaying = isPlaying,
                    onValueChange = onSliderValueChange,
                    onValueChangeFinished = onSliderValueChangeFinished,
                    accent = accent,
                    accentSecondary = accentSecondary,
                    horizontalPadding = 24.dp,
                )

                Spacer(Modifier.height(2.dp))

                AuraTimeRow(
                    sliderPosition = sliderPosition,
                    position = position,
                    duration = duration,
                    color = white.copy(alpha = 0.72f),
                    horizontalPadding = 26.dp,
                )

                Spacer(Modifier.height(6.dp))

                AuraTransportRow(
                    playbackState = playbackState,
                    isPlaying = isPlaying,
                    isLoading = isLoading,
                    canSkipPrevious = canSkipPrevious,
                    canSkipNext = canSkipNext,
                    playerConnection = playerConnection,
                    accent = accent,
                    accentSecondary = accentSecondary,
                )

                Spacer(Modifier.height(14.dp))

                V6PortraitSingleActionRow(
                    mediaMetadata = mediaMetadata,
                    textBackgroundColor = white,
                    currentSongLiked = currentSongLiked,
                    shuffleModeEnabled = shuffleModeEnabled,
                    repeatMode = repeatMode,
                    accent = accent,
                    playerConnection = playerConnection,
                    navController = navController,
                    menuState = menuState,
                    state = state,
                    bottomSheetPageState = bottomSheetPageState,
                    context = context,
                    buttonSize = 44.dp,
                    iconSize = 21.dp,
                    buttonSpacing = 8.dp,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )

                Spacer(Modifier.height(4.dp))
            }
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // The bottom band - the queue sheet's chip row - is reserved for the *controls*
                // column only. The artwork does not pay that reserve: the chips and the provenance
                // line are right-aligned and the cover only ever occupies the left column, so the
                // canvas runs all the way down to the bottom of the screen.
                val controlsBottomReserve = bottomPadding
                val controlsHeight = (maxHeight - controlsBottomReserve).coerceAtLeast(120.dp)
                // Height classes are budget-driven, measured against the *controls* height. Every
                // preset has to fit its own fixed stack (seek row + transport + action row + the
                // provenance line) plus a readable title, so the thresholds below are those
                // measured sums: tight fits a ~220dp column, medium ~250dp, roomy anything larger.
                // The seek row is now the short landscape strip rather than Material's 48dp touch
                // row, so the same column buys a bigger transport than it used to - and choosing a
                // preset the column cannot pay for is exactly how the time labels used to end up
                // clipped behind the pinned transport.
                val roomy = controlsHeight >= 296.dp
                val tight = controlsHeight < 252.dp
                val transportSizes =
                    when {
                        roomy -> AuraTransportSizes.Roomy
                        tight -> AuraTransportSizes.Tight
                        else -> AuraTransportSizes.Medium
                    }
                val titleSize =
                    when {
                        roomy -> 28.sp
                        tight -> 17.sp
                        else -> 23.sp
                    }
                val presetActionSize =
                    when {
                        roomy -> 50.dp
                        tight -> 34.dp
                        else -> 44.dp
                    }
                val presetActionIcon =
                    when {
                        roomy -> 24.dp
                        tight -> 17.dp
                        else -> 22.dp
                    }
                val actionSpacing =
                    if (tight) 6.dp else 8.dp
                // Gaps between the fixed rows. Short windows close them right down: every dp here
                // comes out of the title block, and the title is the first thing to get clipped.
                val stackGap = if (tight) 2.dp else 4.dp
                // Slimmer seek strip than portrait: 40dp of touch padding is generous for a
                // thumb, and the dp it gives back go straight into the artwork.
                val seekHit =
                    when {
                        roomy -> 40.dp
                        tight -> 32.dp
                        else -> 36.dp
                    }
                // Material's slider (Wavy / Thick / Simple / Circular through the shared
                // component) centres its track inside whatever height it is handed, so the bar
                // lands (strip - stroke) / 2 above the strip's bottom. Landscape gives the row a
                // short strip and drops the shared slider by exactly that air, which is what puts
                // the track on the strip's bottom edge - right over the play button. Aura's own
                // gradient bar already pins its track to the bottom of its strip, so it is left
                // alone.
                val sharedSlider = sliderStyle != SliderStyle.Standard
                val seekStroke =
                    when (sliderStyle) {
                        SliderStyle.Simple -> 3.dp
                        SliderStyle.Thick -> 12.dp
                        else -> AuraLandscapeSeekSharedStroke
                    }
                val seekStrip = if (sharedSlider) AuraLandscapeSeekStripHeight else seekHit
                val seekDrop = if (sharedSlider) (AuraLandscapeSeekStripHeight - seekStroke) / 2 else 0.dp
                // Fixed width for the two time labels riding at the ends of the seek row, so a
                // running clock cannot jitter the bar across the track.
                val timeLabelWidth = if (tight) 30.dp else 34.dp
                // One width for every info line (title, seek, times) so they stay aligned on a
                // wide tablet or car screen instead of stretching edge to edge - plus the control
                // column's own start/end padding, which is the width the column actually occupies.
                val infoMaxWidth = 420.dp
                val controlsMaxWidth = infoMaxWidth + 32.dp

                // Artwork sizing: the cover gets every dp of height it can, keeping only a small
                // breath at the very bottom of the window, and it is bounded on three sides - by
                // that height, by 48% of the width, and by the control column's own cap.
                //
                // That third bound is what keeps the two halves in proportion. The info block is
                // capped at infoMaxWidth, so every dp the cover takes past it is a dp the controls
                // cannot use: measured on device, the pair was 312dp of cover against 308dp of
                // action row on this phone, but 552 against 308 at a 1280dp window - the cover of a
                // phone next to the control cluster of a phone, 125dp of empty wash between them,
                // where the phone has 32. Same layout, and it stops reading as one: the more the
                // window grows, the further apart the two drift. Bounding the cover by the same
                // block makes them grow together, so every size shows the phone's composition.
                val artByHeight = maxHeight - AuraLandscapeArtBottomReserve - 2.dp
                // The cover may claim at most 48% of the width and always leaves 300dp for the
                // control column, so a split-screen window or a car screen keeps usable controls
                // however wide the display is.
                val artByWidth = minOf(maxWidth * 0.48f, maxWidth - 300.dp, controlsMaxWidth)
                val artSize = minOf(artByWidth, artByHeight).coerceAtLeast(72.dp)
                val artColumnWidth = artSize + 16.dp
                // The controls column is capped at what its own content can use - any wider and the
                // info block and the action row just centre themselves inside a growing band of
                // nothing - and the *pair* is then centred in the window. Left-anchored (which is
                // what a `weight(1f)` column with a start-aligned row gives), the leftover width all
                // lands on the right: at a 1280dp window that is a 8dp margin on the left and 125dp
                // on the right, with the whole composition shoved to one side.
                val controlsColumnWidth =
                    (maxWidth - artColumnWidth).coerceIn(0.dp, controlsMaxWidth)
                val clusterOffset =
                    ((maxWidth - artColumnWidth - controlsColumnWidth) / 2).coerceAtLeast(0.dp)
                // The queue sheet's chip row centres itself in the space it is told the chrome takes
                // up, and it lives in its own composable tree - it cannot measure the player. It has
                // to be handed *both* ends of that space, and the offset is why. The controls column
                // starts at the offset plus the artwork column, and ends the same offset in from the
                // window's right edge; hand over only the start and the sheet centres itself half an
                // offset too far right - measured on device, the chips sat 57dp right of the action
                // row at a 1280dp window (offset/2), where the phone's was 0. Both ends, and the
                // sheet's midpoint is the controls column's midpoint at every size.
                if (onChromeWidthChange != null) {
                    val chromeStartGap = artColumnWidth + clusterOffset
                    LaunchedEffect(chromeStartGap) { onChromeWidthChange(chromeStartGap) }
                }
                if (onChromeInsetChange != null) {
                    LaunchedEffect(clusterOffset) { onChromeInsetChange(clusterOffset) }
                }

                // The action row is the widest thing in the column, and it is the one row whose
                // width does not come from a text measurement: on a narrow column - a split screen,
                // or a device that keeps a navigation rail in landscape - the preset-sized buttons
                // ran straight off the screen, silently, so the first and last button simply lost
                // their ends. The buttons shrink to what the column can actually pay for; on a
                // full-width landscape window the preset still wins, so bigger screens keep the
                // bigger row.
                val controlsContentWidth =
                    (controlsColumnWidth - 32.dp).coerceAtLeast(0.dp)
                val fittingActionSize =
                    (controlsContentWidth - 2.dp - actionSpacing * 5) / 6
                // The 30dp floor is a readability promise, and it is the one value in this row
                // that can make the row *wider* than the column it was measured against - and a
                // button pushed past the column's edge is clipped, not shrunk. So the floor
                // yields to the arithmetic instead: it holds at 30dp whenever the column can pay
                // for it (anything from roughly a 340dp window up), drops to whatever really
                // fits on a narrower one - a three-way split, a freeform window, a folded cover
                // display - and never goes below 16dp, the smallest a tappable button may be.
                val actionFloor = minOf(30.dp, fittingActionSize).coerceAtLeast(16.dp)
                val actionSize =
                    fittingActionSize.coerceAtMost(presetActionSize).coerceAtLeast(actionFloor)
                val actionIcon =
                    if (actionSize < presetActionSize) {
                        // Half the button, with the floor the row's own 16dp minimum allows. It
                        // used to be 16dp flat, which stops meaning "half" the moment the button
                        // itself drops below 32dp: the glyph then filled the whole button.
                        (actionSize * 0.5f).coerceAtLeast(8.dp)
                    } else {
                        presetActionIcon
                    }

                // The action row reports the width its buttons really span, so the provenance line
                // under it can be exactly that wide: its ends then line up with Share and the
                // overflow menu instead of running past them. Seeded with the row's common shape so
                // the first frame is already close rather than empty.
                var actionRowWidth by remember {
                    mutableStateOf(actionSize * 5 + actionSpacing * 4)
                }
                val actionsRow: @Composable () -> Unit = {
                    V6PortraitSingleActionRow(
                        mediaMetadata = mediaMetadata,
                        textBackgroundColor = white,
                        currentSongLiked = currentSongLiked,
                        shuffleModeEnabled = shuffleModeEnabled,
                        repeatMode = repeatMode,
                        accent = accent,
                        playerConnection = playerConnection,
                        navController = navController,
                        menuState = menuState,
                        state = state,
                        bottomSheetPageState = bottomSheetPageState,
                        context = context,
                        buttonSize = actionSize,
                        iconSize = actionIcon,
                        buttonSpacing = actionSpacing,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .widthIn(max = infoMaxWidth),
                        onContentWidthChange = { actionRowWidth = it },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Artwork column: the cover sized above, the glass header riding on its
                    // top edge (or in its own strip on short windows). edgeToEdge drops the
                    // 32dp-per-side inset the other styles live with - in a landscape column
                    // that tax was a sixth of the artwork.
                    //
                    // The cover is centred in its column rather than pinned to the bottom. Pinned,
                    // a cover that is width-bound - a split-screen window, a short car screen, a
                    // tall tablet - sat in the bottom-left corner with the void above it growing to
                    // 320dp at 1024x768, while the controls filled the full height beside it; the
                    // two halves then read as two unrelated blocks. Height-bound (the usual case,
                    // and this phone's) it fills the column either way, so this only ever moves a
                    // cover that was floating in the first place.
                    Box(
                        modifier =
                            Modifier
                                .width(artColumnWidth)
                                .fillMaxHeight()
                                .padding(bottom = AuraLandscapeArtBottomReserve),
                        contentAlignment = Alignment.Center,
                    ) {
                        Thumbnail(
                            sliderPositionProvider = { sliderPosition },
                            modifier =
                                Modifier
                                    .size(artSize)
                                    .shadow(
                                        34.dp,
                                        RoundedCornerShape(28.dp),
                                        ambientColor = accent,
                                        spotColor = accent,
                                    ).auraArtBreath(isPlaying),
                            showNowPlayingHeader = false,
                            isPlayerExpanded = state.isExpanded,
                            edgeToEdge = true,
                        )
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.TopCenter)
                                    .padding(top = 2.dp, start = 4.dp, end = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AuraNowPlayingPill(isPlaying = isPlaying, accent = accent)
                            IconButton(
                                onClick = onCollapseClick,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.expand_more),
                                    contentDescription = stringResource(R.string.close),
                                    tint = white.copy(alpha = 0.8f),
                                    modifier = Modifier.size(26.dp),
                                )
                            }
                        }
                    }

                    // Controls column, built so nothing can ever sit on top of anything else:
                    // the seek strip, its time labels, the transport and the action row are all
                    // fixed-size children measured first, and the title block is the only
                    // flexible one. A short window therefore shrinks the title, never the time
                    // labels - the failure mode that used to hide them behind the transport.
                    // The column clips to its own bounds, so even a box too short for the fixed
                    // stack degrades by clipping at the bottom edge instead of overlapping.
                    Column(
                        modifier =
                            Modifier
                                .width(controlsColumnWidth)
                                .fillMaxHeight()
                                .clipToBounds()
                                .padding(
                                    start = 12.dp,
                                    end = 20.dp,
                                    top = 4.dp,
                                    bottom = controlsBottomReserve,
                                ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // The only elastic part: centred while there is room, clipped from both
                        // ends when there is not.
                        Box(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .clipToBounds(),
                            contentAlignment = Alignment.Center,
                        ) {
                            AuraTitleSection(
                                mediaMetadata = mediaMetadata,
                                navController = navController,
                                state = state,
                                centerAligned = true,
                                titleSize = titleSize,
                                maxLines = 1,
                                subtitleSpacing = if (tight) 2.dp else 6.dp,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .widthIn(max = infoMaxWidth)
                                        .padding(horizontal = 12.dp),
                            )
                        }

                        // The times ride at the ends of the seek row instead of on their own line,
                        // which frees a whole line of height and puts the slider itself directly
                        // above the transport - the slider the user asked for, right over the
                        // buttons - while the timer never leaves the screen. The row itself lives
                        // in a strip as tall as the bar needs: Aura's own gradient bar pins its
                        // track to the strip's bottom, and the shared slider is dropped by the air
                        // Material keeps under its track, so either way the bar's bottom edge sits
                        // 4dp above the transport row on every style and every screen size.
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = infoMaxWidth)
                                    .height(seekStrip),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .offset(y = seekDrop),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = makeTimeString(sliderPosition ?: position),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = white.copy(alpha = 0.72f),
                                    maxLines = 1,
                                    modifier = Modifier.width(timeLabelWidth),
                                )
                                AuraSeekRow(
                                    sliderStyle = sliderStyle,
                                    sliderPosition = sliderPosition,
                                    position = position,
                                    duration = duration,
                                    isPlaying = isPlaying,
                                    onValueChange = onSliderValueChange,
                                    onValueChangeFinished = onSliderValueChangeFinished,
                                    accent = accent,
                                    accentSecondary = accentSecondary,
                                    horizontalPadding = 6.dp,
                                    hitHeight = seekHit,
                                    barBottomAligned = true,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = makeTimeString(duration),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = white.copy(alpha = 0.72f),
                                    maxLines = 1,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.width(timeLabelWidth),
                                )
                            }
                        }

                        Spacer(Modifier.height(stackGap))

                        AuraTransportRow(
                            playbackState = playbackState,
                            isPlaying = isPlaying,
                            isLoading = isLoading,
                            canSkipPrevious = canSkipPrevious,
                            canSkipNext = canSkipNext,
                            playerConnection = playerConnection,
                            accent = accent,
                            accentSecondary = accentSecondary,
                            sizes = transportSizes,
                        )

                        Spacer(Modifier.height(stackGap))

                        actionsRow()

                        // The provenance line: its own row, directly under the action buttons, and
                        // deliberately short - a footnote under the controls, capped so it never
                        // reads as a full-width banner, still marqueeing when the facts outgrow the
                        // cap. Because it no longer lives in the queue sheet, the sheet stopped
                        // reserving its height and the artwork gained that line back.
                        //
                        // Its glass is deliberately stronger than the portrait strip's (0.34 fill,
                        // 0.5dp hairline): the row is only as wide as the action row above it and
                        // its whole job is to *read* as a distinct pill there, over the accent
                        // wash the buttons sit on. At the portrait weights it sank into that wash -
                        // measured on device, its fill was only ~0.72 of the backdrop behind it, so
                        // neither a clip nor a screenshot could tell where the pill began. A 0.55
                        // scrim puts the fill at ~0.45 of the wash, and the rim has to clear the
                        // wash from the *outside* as well: `Modifier.border` draws it over the fill,
                        // so its own contrast is a fraction of the fill's - at 0.30 white it measured
                        // 110 against a bright wash of 107 (a 360dp window's wash measured 41, where
                        // the same rim read 89), i.e. no edge at all on the brighter palette. At 0.50
                        // it lands well above both the fill and the wash, which is what lets
                        // `scripts/aura-strip-align.py` find the pill's ends in a capture and assert
                        // they still line up with the first and last button.
                        val codecRow = formatRow
                        if (showCodecOnPlayer && codecRow != null) {
                            Spacer(Modifier.height(stackGap))
                            Box(
                                modifier =
                                    Modifier
                                        // Exactly the width the buttons span, and nothing else.
                                        // It used to be capped a second time at 320dp, which was
                                        // invisible until a window grew tall enough for the
                                        // 50dp-button preset: that row is 6*50 + 5*8 = 340dp, so
                                        // the cap silently pulled the pill 10dp short at each end
                                        // and the caption stopped spanning the first and last
                                        // button. Measured on device at an 804x600dp window, the
                                        // pill was 319.8dp against a 339.8dp row. The row is
                                        // already capped at infoMaxWidth, so there is nothing for
                                        // a second cap to protect.
                                        .width(actionRowWidth.coerceAtMost(infoMaxWidth))
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.Black.copy(alpha = 0.55f))
                                        .border(1.dp, Color.White.copy(alpha = 0.50f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                CodecInfoRow(
                                    codec = codecRow.format.containerLabel(),
                                    bitrate = codecRow.format.formattedBitrate(),
                                    fileSize = codecRow.format.formattedFileSize(),
                                    textColor = Color.White.copy(alpha = 0.88f),
                                    evidence = codecRow.source.displayLabel(),
                                    horizontalPadding = 14.dp,
                                    topPadding = 2.dp,
                                    bottomPadding = 2.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
