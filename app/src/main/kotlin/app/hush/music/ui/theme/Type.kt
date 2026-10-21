/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.hush.music.R


fun getTypography(
    brandFont: FontFamily,
    plainFont: FontFamily = FontFamily.Default,
): Typography =
    Typography(
        displayLarge =
            TextStyle(
                fontFamily = brandFont,
                fontWeight = FontWeight.Bold,
                fontSize = 57.sp,
                lineHeight = 64.sp,
                letterSpacing = (-0.5).sp,
            ),
        displayMedium =
            TextStyle(
                fontFamily = brandFont,
                fontWeight = FontWeight.Bold,
                fontSize = 45.sp,
                lineHeight = 52.sp,
                letterSpacing = (-0.25).sp,
            ),
        displaySmall =
            TextStyle(
                fontFamily = brandFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 36.sp,
                lineHeight = 44.sp,
                letterSpacing = 0.sp,
            ),
        headlineLarge =
            TextStyle(
                fontFamily = brandFont,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                lineHeight = 40.sp,
                letterSpacing = (-0.25).sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = brandFont,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                lineHeight = 36.sp,
                letterSpacing = 0.sp,
            ),
        headlineSmall =
            TextStyle(
                fontFamily = brandFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
                lineHeight = 32.sp,
                letterSpacing = 0.sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = brandFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                letterSpacing = 0.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = brandFont,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.15.sp,
            ),
        titleSmall =
            TextStyle(
                fontFamily = brandFont,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = plainFont,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.5.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = plainFont,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.25.sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = plainFont,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.4.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = plainFont,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = plainFont,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = plainFont,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp,
            ),
    )

/**
 * The bundled families.
 *
 * Each of these was a `FontFamily.Default` until now, which is why the font setting offered Outfit
 * and Plus Jakarta and rendered the system font for both: the files have been in the APK the whole
 * time (`res/font/outfit.ttf`, `plus_jakarta_sans.ttf`, `poppins.ttf`, `sfprodisplaybold.ttf`) and
 * nothing pointed at them, so the choice was a label with no effect. They are one file per family
 * here for the same reason the files are: Outfit and Plus Jakarta are variable fonts and Compose
 * synthesises the weights from one file, which is what the parents these skins copy do too.
 */
val AppFontFamily: FontFamily = FontFamily.Default

/** A bundled family, one file and the weights Compose synthesises from it. */
private fun bundledFontFamily(resId: Int): FontFamily =
    FontFamily(
        Font(resId = resId, weight = FontWeight.Normal, style = FontStyle.Normal),
        Font(resId = resId, weight = FontWeight.Medium, style = FontStyle.Normal),
        Font(resId = resId, weight = FontWeight.SemiBold, style = FontStyle.Normal),
        Font(resId = resId, weight = FontWeight.Bold, style = FontStyle.Normal),
    )

val OutfitFontFamily: FontFamily by lazy { bundledFontFamily(R.font.outfit) }
val PlusJakartaSansFontFamily: FontFamily by lazy { bundledFontFamily(R.font.plus_jakarta_sans) }
val PoppinsFontFamily: FontFamily by lazy { bundledFontFamily(R.font.poppins) }
val GoogleSansFlexFontFamily: FontFamily by lazy { bundledFontFamily(R.font.google_sans_flex) }

/**
 * SF Pro Display, which BitChord ships five weights of and Hush can ship one of.
 *
 * The bundled file is the bold cut, so the lighter styles in BitChord's scale render with a
 * synthesised weight rather than a real one - the sizes and the tracking are exact, the stroke is
 * the closest a redistributable file can be. Shipping the four other cuts is a licensing question,
 * not a technical one, and it is the reason this family is one file.
 */
val SfProDisplayFontFamily: FontFamily by lazy { bundledFontFamily(R.font.sfprodisplaybold) }

val LyricsFontFamily: FontFamily = FontFamily.Default
val AppTypography = getTypography(brandFont = AppFontFamily, plainFont = FontFamily.Default)
val SystemTypography = getTypography(brandFont = FontFamily.Default, plainFont = FontFamily.Default)

fun fontFamilyFor(preference: app.hush.music.constants.AppFontPreference): FontFamily =
    when (preference) {
        app.hush.music.constants.AppFontPreference.DEFAULT -> AppFontFamily
        app.hush.music.constants.AppFontPreference.SYSTEM -> FontFamily.Default
        app.hush.music.constants.AppFontPreference.OUTFIT -> OutfitFontFamily
        app.hush.music.constants.AppFontPreference.PLUS_JAKARTA -> PlusJakartaSansFontFamily
        app.hush.music.constants.AppFontPreference.CUSTOM -> AppFontFamily
        else -> AppFontFamily
    }

fun plainFontFamilyFor(preference: app.hush.music.constants.AppFontPreference): FontFamily =
    when (preference) {
        app.hush.music.constants.AppFontPreference.DEFAULT,
        app.hush.music.constants.AppFontPreference.SYSTEM,
        -> FontFamily.Default

        else -> FontFamily.Default
    }

fun typographyFor(
    brandFont: FontFamily,
    plainFont: FontFamily = FontFamily.Default,
) = getTypography(brandFont, plainFont)
