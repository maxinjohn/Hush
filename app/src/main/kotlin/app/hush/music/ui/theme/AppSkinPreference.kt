/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.hush.music.constants.AppSkinKey
import app.hush.music.utils.rememberEnumPreference

/**
 * The skin in force, and a setter.
 *
 * Reads through [rememberEnumPreference], which stores an enum by *name* - so an id stored by a
 * build that has since dropped a skin is normalized rather than thrown at, and [AppSkin.fromId]
 * answers for both spellings. The same shape as `rememberPlayerDesignStylePreference`, because the
 * two questions ("which player" and "which app") are the same kind of question and should not be
 * answered two different ways.
 */
@Composable
fun rememberAppSkinPreference(): Pair<AppSkin, (AppSkin) -> Unit> {
    val (storedSkin, onSkinChange) =
        rememberEnumPreference(
            key = AppSkinKey,
            defaultValue = AppSkin.DEFAULT,
        )
    val skin = AppSkin.fromId(storedSkin.name)

    LaunchedEffect(storedSkin) {
        if (storedSkin != skin) {
            onSkinChange(skin)
        }
    }

    return skin to onSkinChange
}

/** The skin in force, for a caller that only reads it. */
@Composable
fun rememberAppSkin(): AppSkin {
    val (skin, _) = rememberAppSkinPreference()
    return skin
}
