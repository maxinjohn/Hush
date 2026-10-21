/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.theme

import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.ktx.toHct
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.hush.music.constants.AppFontPreference
import app.hush.music.ui.theme.fontFamilyFor
import kotlin.math.abs
import kotlin.math.min

val DefaultThemeColor = Color(0xFFFF2D55)
val LocalArchiveTuneFontPreference = staticCompositionLocalOf { AppFontPreference.DEFAULT }
val LocalArchiveTuneFontFamily = staticCompositionLocalOf { AppFontFamily }

@Composable
fun rememberHushLyricsFontFamily(): FontFamily {
    val fontPreference = LocalArchiveTuneFontPreference.current
    val fontFamily = LocalArchiveTuneFontFamily.current
    return remember(fontPreference, fontFamily) {
        if (fontPreference == AppFontPreference.DEFAULT) LyricsFontFamily else fontFamily
    }
}

data class ThemeSeedPalette(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val neutral: Color,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HushTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    pureBlack: Boolean = false,
    themeColor: Color = DefaultThemeColor,
    seedPalette: ThemeSeedPalette? = null,
    disableAnimations: Boolean = false,
    fontPreference: AppFontPreference = AppFontPreference.DEFAULT,
    customFontUri: String = "",
    /**
     * The look the whole app renders in; see [AppSkin].
     *
     * Defaults to the shipped one, whose spec is every value this composable already used - so a
     * caller that has not been taught about skins renders exactly as it did before they existed.
     */
    skin: AppSkin = AppSkin.DEFAULT,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val skinSpec = skin.spec
    // Before anything below composes, because every component that draws a card or a chip reads
    // these radii as plain values - see [HushDesign.applyRadiusScale] for why they are not a
    // composition-local like the colours are.
    HushDesign.applyRadiusScale(skinSpec.radiusScale)
    val useSystemDynamicColor =
        (seedPalette == null && themeColor == DefaultThemeColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)

    val customFontFamily =
        produceState<FontFamily?>(
            initialValue = null,
            context,
            fontPreference,
            customFontUri,
        ) {
            value =
                if (fontPreference == AppFontPreference.CUSTOM && customFontUri.isNotBlank()) {
                    CustomFontLoader.loadFontFamily(context.applicationContext, customFontUri)
                } else {
                    null
                }
        }.value
    val resolvedFontFamily =
        remember(fontPreference, customFontFamily) {
            when (fontPreference) {
                AppFontPreference.CUSTOM -> customFontFamily ?: AppFontFamily
                else -> fontFamilyFor(fontPreference)
            }
        }
    val plainFontFamily =
        remember(fontPreference) {
            plainFontFamilyFor(fontPreference)
        }
    val brandTypography =
        remember(resolvedFontFamily, plainFontFamily) {
            getTypography(
                brandFont = resolvedFontFamily,
                plainFont = plainFontFamily,
            )
        }
    // The skin's type scale, on top of the font the user chose: a skin decides the shape of the
    // scale (weights, tracking, display sizes), never which font is being used.
    val typography =
        remember(brandTypography, skin) {
            skinTypography(skin, brandTypography)
        }
    val motionScheme =
        remember(skinSpec.expressiveMotion) {
            if (skinSpec.expressiveMotion) MotionScheme.expressive() else MotionScheme.standard()
        }
    val paletteStyle =
        remember(themeColor, seedPalette) {
            paletteStyleFor(seedPalette?.primary ?: themeColor)
        }

    val appColorScheme =
        remember(seedPalette, themeColor, darkTheme) {
            if (seedPalette != null) {
                exactPaletteColorScheme(
                    palette = seedPalette,
                    isDark = darkTheme,
                )
            } else {
                materialKolorDynamicColorScheme(
                    keyColor = themeColor,
                    isDark = darkTheme,
                    style = paletteStyle,
                )
            }
        }

    val baseColorScheme =
        when {
            // A monochrome skin keeps the user's palette as *intensity* and throws the hue away: the
            // scheme is still theirs to change, it just arrives in grey. Painting a fixed palette
            // over the setting they chose would make the two controls fight each other.
            // Remembered on the same inputs the non-monochrome branch is. It used to be computed
            // inline, so every recomposition of `HushTheme` - a font or skin change, a preference
            // landing after launch - re-solved the whole HCT palette on the main thread. Measured
            // with the startup profiler: `materialKolorDynamicColorScheme` was 28% of a 535 ms
            // first-composition frame, and it appeared again in a later 1,006 ms frame.
            skinSpec.color == SkinColor.MONOCHROME ->
                remember(seedPalette?.primary, themeColor, darkTheme) {
                    materialKolorDynamicColorScheme(
                        keyColor = seedPalette?.primary ?: themeColor,
                        isDark = darkTheme,
                        style = PaletteStyle.Monochrome,
                    )
                }

            useSystemDynamicColor -> if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

            else -> appColorScheme
        }

    // A solid skin asks for flat surfaces, and on a dark scheme the flat version of a tonal surface
    // is black - so the skin's treatment is expressed through the same switch Pure black uses rather
    // than as a second way of painting backgrounds.
    val flatSurfaces = pureBlack || skinSpec.surface == SkinSurface.SOLID
    val colorScheme =
        remember(baseColorScheme, flatSurfaces, darkTheme) {
            if (darkTheme && flatSurfaces) baseColorScheme.pureBlack(true) else baseColorScheme
        }

    val animatedColorScheme = if (disableAnimations) colorScheme else animateColorScheme(colorScheme)

    // Identical to the shipped shapes at scale 1, and the same ratios thereafter: a skin bends the
    // scale, it does not replace it.
    val expressiveShapes =
        remember(skinSpec.radiusScale) {
            Shapes(
                extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(skinRadius(12.dp, skinSpec)),
                small = androidx.compose.foundation.shape.RoundedCornerShape(skinRadius(18.dp, skinSpec)),
                medium = androidx.compose.foundation.shape.RoundedCornerShape(skinRadius(24.dp, skinSpec)),
                large = androidx.compose.foundation.shape.RoundedCornerShape(skinRadius(32.dp, skinSpec)),
                extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(skinRadius(40.dp, skinSpec)),
            )
        }

    CompositionLocalProvider(
        LocalArchiveTuneFontPreference provides fontPreference,
        LocalArchiveTuneFontFamily provides resolvedFontFamily,
        LocalAppSkin provides skin,
        // The bottom panel's measurements, published here beside the skin that chose them: the pill
        // itself, the inset arithmetic in MainActivity and the sheet's collapsed bound all have to
        // agree, and none of them can see a `SkinSpec` field on its own.
        LocalMiniPlayerAnatomy provides skinMiniPlayerAnatomy(skin),
        // The bottom bar's measurements, for the same reason: the bar's height is the skin's, and
        // the screens that have to clear it cannot read a `SkinNav` field.
        LocalNavigationAnatomy provides skinNavigationAnatomy(skin),
    ) {
        MaterialExpressiveTheme(
            colorScheme = animatedColorScheme,
            motionScheme = motionScheme,
            typography = typography,
            shapes = expressiveShapes,
            content = content,
        )
    }
}

@Composable
private fun animateColorScheme(targetColorScheme: ColorScheme): ColorScheme {
    val animationSpec = spring<Color>(stiffness = Spring.StiffnessLow)
    return ColorScheme(
        primary = animateColorAsState(targetColorScheme.primary, animationSpec, label = "primary").value,
        onPrimary = animateColorAsState(targetColorScheme.onPrimary, animationSpec, label = "onPrimary").value,
        primaryContainer = animateColorAsState(targetColorScheme.primaryContainer, animationSpec, label = "primaryContainer").value,
        onPrimaryContainer = animateColorAsState(targetColorScheme.onPrimaryContainer, animationSpec, label = "onPrimaryContainer").value,
        inversePrimary = animateColorAsState(targetColorScheme.inversePrimary, animationSpec, label = "inversePrimary").value,
        secondary = animateColorAsState(targetColorScheme.secondary, animationSpec, label = "secondary").value,
        onSecondary = animateColorAsState(targetColorScheme.onSecondary, animationSpec, label = "onSecondary").value,
        secondaryContainer = animateColorAsState(targetColorScheme.secondaryContainer, animationSpec, label = "secondaryContainer").value,
        onSecondaryContainer =
            animateColorAsState(
                targetColorScheme.onSecondaryContainer,
                animationSpec,
                label = "onSecondaryContainer",
            ).value,
        tertiary = animateColorAsState(targetColorScheme.tertiary, animationSpec, label = "tertiary").value,
        onTertiary = animateColorAsState(targetColorScheme.onTertiary, animationSpec, label = "onTertiary").value,
        tertiaryContainer = animateColorAsState(targetColorScheme.tertiaryContainer, animationSpec, label = "tertiaryContainer").value,
        onTertiaryContainer =
            animateColorAsState(
                targetColorScheme.onTertiaryContainer,
                animationSpec,
                label = "onTertiaryContainer",
            ).value,
        background = animateColorAsState(targetColorScheme.background, animationSpec, label = "background").value,
        onBackground = animateColorAsState(targetColorScheme.onBackground, animationSpec, label = "onBackground").value,
        surface = animateColorAsState(targetColorScheme.surface, animationSpec, label = "surface").value,
        onSurface = animateColorAsState(targetColorScheme.onSurface, animationSpec, label = "onSurface").value,
        surfaceVariant = animateColorAsState(targetColorScheme.surfaceVariant, animationSpec, label = "surfaceVariant").value,
        onSurfaceVariant = animateColorAsState(targetColorScheme.onSurfaceVariant, animationSpec, label = "onSurfaceVariant").value,
        surfaceTint = animateColorAsState(targetColorScheme.surfaceTint, animationSpec, label = "surfaceTint").value,
        inverseSurface = animateColorAsState(targetColorScheme.inverseSurface, animationSpec, label = "inverseSurface").value,
        inverseOnSurface = animateColorAsState(targetColorScheme.inverseOnSurface, animationSpec, label = "inverseOnSurface").value,
        error = animateColorAsState(targetColorScheme.error, animationSpec, label = "error").value,
        onError = animateColorAsState(targetColorScheme.onError, animationSpec, label = "onError").value,
        errorContainer = animateColorAsState(targetColorScheme.errorContainer, animationSpec, label = "errorContainer").value,
        onErrorContainer = animateColorAsState(targetColorScheme.onErrorContainer, animationSpec, label = "onErrorContainer").value,
        outline = animateColorAsState(targetColorScheme.outline, animationSpec, label = "outline").value,
        outlineVariant = animateColorAsState(targetColorScheme.outlineVariant, animationSpec, label = "outlineVariant").value,
        scrim = animateColorAsState(targetColorScheme.scrim, animationSpec, label = "scrim").value,
        surfaceBright = animateColorAsState(targetColorScheme.surfaceBright, animationSpec, label = "surfaceBright").value,
        surfaceDim = animateColorAsState(targetColorScheme.surfaceDim, animationSpec, label = "surfaceDim").value,
        surfaceContainer = animateColorAsState(targetColorScheme.surfaceContainer, animationSpec, label = "surfaceContainer").value,
        surfaceContainerLow =
            animateColorAsState(
                targetColorScheme.surfaceContainerLow,
                animationSpec,
                label = "surfaceContainerLow",
            ).value,
        surfaceContainerLowest =
            animateColorAsState(
                targetColorScheme.surfaceContainerLowest,
                animationSpec,
                label = "surfaceContainerLowest",
            ).value,
        surfaceContainerHigh =
            animateColorAsState(
                targetColorScheme.surfaceContainerHigh,
                animationSpec,
                label = "surfaceContainerHigh",
            ).value,
        surfaceContainerHighest =
            animateColorAsState(
                targetColorScheme.surfaceContainerHighest,
                animationSpec,
                label = "surfaceContainerHighest",
            ).value,
    )
}

private fun exactPaletteColorScheme(
    palette: ThemeSeedPalette,
    isDark: Boolean,
): ColorScheme =
    mergedSeedColorScheme(
        primarySeed = palette.primary,
        secondarySeed = palette.secondary,
        tertiarySeed = palette.tertiary,
        neutralSeed = palette.neutral,
        isDark = isDark,
    )

private fun materialKolorDynamicColorScheme(
    keyColor: Color,
    isDark: Boolean,
    contrastLevel: Double = 0.0,
    style: PaletteStyle,
): ColorScheme =
    mergedSeedColorScheme(
        primarySeed = keyColor,
        secondarySeed = keyColor,
        tertiarySeed = keyColor,
        neutralSeed = keyColor,
        isDark = isDark,
        contrastLevel = contrastLevel,
        style = style,
    )

private fun mergedSeedColorScheme(
    primarySeed: Color,
    secondarySeed: Color,
    tertiarySeed: Color,
    neutralSeed: Color,
    isDark: Boolean,
    contrastLevel: Double = 0.0,
    style: PaletteStyle = paletteStyleFor(primarySeed),
): ColorScheme {
    val primaryScheme = materialKolorScheme(primarySeed, isDark, contrastLevel, style)
    val secondaryScheme = materialKolorScheme(secondarySeed, isDark, contrastLevel, paletteStyleFor(secondarySeed))
    val tertiaryScheme = materialKolorScheme(tertiarySeed, isDark, contrastLevel, paletteStyleFor(tertiarySeed))
    val neutralScheme = materialKolorScheme(neutralSeed, isDark, contrastLevel, paletteStyleFor(neutralSeed))

    return ColorScheme(
        primary = primaryScheme.primary,
        onPrimary = primaryScheme.onPrimary,
        primaryContainer = primaryScheme.primaryContainer,
        onPrimaryContainer = primaryScheme.onPrimaryContainer,
        inversePrimary = primaryScheme.inversePrimary,
        secondary = secondaryScheme.primary,
        onSecondary = secondaryScheme.onPrimary,
        secondaryContainer = secondaryScheme.primaryContainer,
        onSecondaryContainer = secondaryScheme.onPrimaryContainer,
        tertiary = tertiaryScheme.primary,
        onTertiary = tertiaryScheme.onPrimary,
        tertiaryContainer = tertiaryScheme.primaryContainer,
        onTertiaryContainer = tertiaryScheme.onPrimaryContainer,
        background = neutralScheme.background,
        onBackground = neutralScheme.onBackground,
        surface = neutralScheme.surface,
        onSurface = neutralScheme.onSurface,
        surfaceVariant = neutralScheme.surfaceVariant,
        onSurfaceVariant = neutralScheme.onSurfaceVariant,
        inverseSurface = neutralScheme.inverseSurface,
        inverseOnSurface = neutralScheme.inverseOnSurface,
        surfaceBright = neutralScheme.surfaceBright,
        surfaceDim = neutralScheme.surfaceDim,
        surfaceContainer = neutralScheme.surfaceContainer,
        surfaceContainerLow = neutralScheme.surfaceContainerLow,
        surfaceContainerLowest = neutralScheme.surfaceContainerLowest,
        surfaceContainerHigh = neutralScheme.surfaceContainerHigh,
        surfaceContainerHighest = neutralScheme.surfaceContainerHighest,
        outline = neutralScheme.outline,
        outlineVariant = neutralScheme.outlineVariant,
        error = primaryScheme.error,
        onError = primaryScheme.onError,
        errorContainer = primaryScheme.errorContainer,
        onErrorContainer = primaryScheme.onErrorContainer,
        scrim = neutralScheme.scrim,
        surfaceTint = primaryScheme.surfaceTint,
    )
}

internal fun materialKolorScheme(
    seedColor: Color,
    isDark: Boolean,
    contrastLevel: Double,
    style: PaletteStyle,
): ColorScheme {
    val key = SchemeKey(seedColor.value, isDark, contrastLevel, style)
    synchronized(schemeCache) { schemeCache[key] }?.let { return it }
    // Solved outside the lock: the solve is the expensive part, and holding a monitor across it
    // would serialise the pre-warm below against the first composition for no benefit - the cache
    // is only ever a lookup table, and two threads that race on one key write the same scheme.
    val scheme =
        dynamicColorScheme(
            seedColor = seedColor,
            isDark = isDark,
            contrastLevel = contrastLevel,
            style = style,
        )
    synchronized(schemeCache) { schemeCache[key] = scheme }
    return scheme
}

/**
 * What makes one generated scheme the same scheme as another.
 *
 * The colour is compared by its packed value, not by `Color` equality, because that is what the
 * solver actually reads - two `Color`s that differ only in colour space would otherwise be two
 * cache entries for one palette.
 */
private data class SchemeKey(
    val seed: ULong,
    val dark: Boolean,
    val contrast: Double,
    val style: PaletteStyle,
)

/**
 * The last eight solved schemes, least recently used first out.
 *
 * A cold start used to solve the *same* palette four times before the first frame: the dynamic
 * scheme feeds one key colour in as primary, secondary, tertiary and neutral, and each of those
 * asks the HCT solver for a full Material scheme. `mergedSeedColorScheme` also asks for a scheme
 * per seed on an exact palette, and `HushTheme` recomposes whenever a font, skin or palette
 * preference lands - so the same handful of schemes were being re-solved several times a launch.
 * Measured with the startup profiler, `materialKolorDynamicColorScheme` was 28% of a 535 ms
 * first-composition frame and 12% of a later 1,006 ms one.
 *
 * Eight entries because a session realistically holds two (light and dark) or four (an exact
 * palette's four seeds) at a time; the bound is there so a theme picker dragged through a gradient
 * cannot grow this without limit. [LinkedHashMap] with access order is the whole LRU.
 */
private val schemeCache =
    object : LinkedHashMap<SchemeKey, ColorScheme>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<SchemeKey, ColorScheme>?,
        ): Boolean = size > 8
    }

private fun paletteStyleFor(seedColor: Color): PaletteStyle {
    val chroma = seedColor.toHct().chroma
    return when {
        chroma < 4.0 -> PaletteStyle.Monochrome
        chroma < 12.0 -> PaletteStyle.Neutral
        else -> PaletteStyle.TonalSpot
    }
}

private fun Int.toComposeColor(): Color = Color(this.toLong() and 0xFFFFFFFFL)

fun Bitmap.extractThemeColor(): Color {
    val palette =
        Palette
            .from(this)
            .maximumColorCount(16)
            .generate()

    val swatch =
        palette.vibrantSwatch
            ?: palette.dominantSwatch
            ?: palette.mutedSwatch
            ?: palette.lightVibrantSwatch
            ?: palette.darkVibrantSwatch
            ?: palette.lightMutedSwatch
            ?: palette.darkMutedSwatch

    return swatch?.rgb?.toComposeColor() ?: DefaultThemeColor
}

fun Bitmap.extractGradientColors(): List<Color> {
    val palette =
        Palette
            .from(this)
            .maximumColorCount(48)
            .generate()

    val swatches =
        palette.swatches
            .filter { it.population > 0 }
            .sortedByDescending { it.population }

    if (swatches.isEmpty()) {
        return listOf(Color(0xFF595959), Color(0xFF0D0D0D))
    }

    val first = swatches.first()
    val firstHsv = FloatArray(3)
    android.graphics.Color.colorToHSV(first.rgb, firstHsv)

    val second =
        swatches
            .drop(1)
            .maxByOrNull { candidate ->
                val hsv = FloatArray(3)
                android.graphics.Color.colorToHSV(candidate.rgb, hsv)

                val hueDiffRaw = abs(hsv[0] - firstHsv[0])
                val hueDiff = min(hueDiffRaw, 360f - hueDiffRaw) / 180f
                val satDiff = abs(hsv[1] - firstHsv[1])
                val valueDiff = abs(hsv[2] - firstHsv[2])

                hueDiff * 0.65f + satDiff * 0.2f + valueDiff * 0.15f
            }
            ?: first

    return listOf(first.rgb.toComposeColor(), second.rgb.toComposeColor())
        .sortedByDescending { it.luminance() }
}

fun ColorScheme.pureBlack(apply: Boolean) =
    if (apply) {
        copy(
            surface = Color.Black,
            background = Color.Black,
        )
    } else {
        this
    }

val ColorSaver =
    object : Saver<Color, Int> {
        override fun restore(value: Int): Color = value.toComposeColor()

        override fun SaverScope.save(value: Color): Int = value.toArgb()
    }

@Serializable
data class ThemeExportV1(
    val version: Int = 1,
    val name: String? = null,
    val primary: String,
    val secondary: String,
    val tertiary: String,
    val neutral: String,
)

object ThemeSeedPaletteCodec {
    private const val PreferencePrefix = "seedPalette:"
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
        }

    fun encodeForPreference(
        palette: ThemeSeedPalette,
        name: String? = null,
    ): String {
        val payload =
            json.encodeToString(
                ThemeExportV1(
                    name = name,
                    primary = palette.primary.toHexArgbString(),
                    secondary = palette.secondary.toHexArgbString(),
                    tertiary = palette.tertiary.toHexArgbString(),
                    neutral = palette.neutral.toHexArgbString(),
                ),
            )
        val b64 = Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
        return PreferencePrefix + b64
    }

    fun decodeFromPreference(value: String): ThemeSeedPalette? {
        if (!value.startsWith(PreferencePrefix)) return null
        val b64 = value.removePrefix(PreferencePrefix)
        val decoded =
            runCatching {
                val bytes = Base64.decode(b64, Base64.URL_SAFE or Base64.NO_WRAP)
                bytes.toString(Charsets.UTF_8)
            }.getOrNull()
                ?: return null
        return decodeFromJson(decoded)
    }

    fun encodeAsJson(
        palette: ThemeSeedPalette,
        name: String? = null,
    ): String =
        json.encodeToString(
            ThemeExportV1(
                name = name,
                primary = palette.primary.toHexArgbString(),
                secondary = palette.secondary.toHexArgbString(),
                tertiary = palette.tertiary.toHexArgbString(),
                neutral = palette.neutral.toHexArgbString(),
            ),
        )

    fun decodeFromJson(text: String): ThemeSeedPalette? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            val element = json.parseToJsonElement(trimmed)
            val obj = element.jsonObject

            val version = obj["version"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
            if (version != 1) return@runCatching null

            fun getColor(key: String): Color? =
                obj[key]
                    ?.jsonPrimitive
                    ?.content
                    ?.toColorOrNull()

            val primary = getColor("primary") ?: return@runCatching null
            val secondary = getColor("secondary") ?: primary
            val tertiary = getColor("tertiary") ?: primary
            val neutral = getColor("neutral") ?: primary

            ThemeSeedPalette(
                primary = primary,
                secondary = secondary,
                tertiary = tertiary,
                neutral = neutral,
            )
        }.getOrNull()
            ?: decodeFromLegacyObject(trimmed)
    }

    fun extractNameFromJsonOrNull(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            val element: JsonElement = json.parseToJsonElement(trimmed)
            element.jsonObject["name"]
                ?.jsonPrimitive
                ?.content
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun extractNameFromPreference(value: String): String? {
        if (!value.startsWith(PreferencePrefix)) return null
        val b64 = value.removePrefix(PreferencePrefix)
        val decoded =
            runCatching {
                val bytes = Base64.decode(b64, Base64.URL_SAFE or Base64.NO_WRAP)
                bytes.toString(Charsets.UTF_8)
            }.getOrNull()
                ?: return null
        return extractNameFromJsonOrNull(decoded)
    }

    private fun decodeFromLegacyObject(text: String): ThemeSeedPalette? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return null
        return runCatching {
            val element = json.parseToJsonElement(trimmed)
            val obj = element.jsonObject

            fun getHex(key: String): String? =
                obj[key]
                    ?.jsonPrimitive
                    ?.content
                    ?.takeIf { it.isNotBlank() }

            val primary = getHex("primary")?.toColorOrNull() ?: return@runCatching null
            val secondary = getHex("secondary")?.toColorOrNull() ?: primary
            val tertiary = getHex("tertiary")?.toColorOrNull() ?: primary
            val neutral = getHex("neutral")?.toColorOrNull() ?: primary

            ThemeSeedPalette(primary, secondary, tertiary, neutral)
        }.getOrNull()
    }

    private fun Color.toHexArgbString(): String = String.format("#%08X", this.toArgb())

    private fun String.toColorOrNull(): Color? {
        val normalized = trim()
        if (normalized.isEmpty()) return null
        return runCatching {
            val withHash = if (normalized.startsWith("#")) normalized else "#$normalized"
            Color(android.graphics.Color.parseColor(withHash))
        }.getOrNull()
    }
}
