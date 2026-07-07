/*
 * Hush (2026)
 * Shared color utility functions - reusable across visualizer components.
 */

package app.hush.music.utils

import androidx.compose.ui.graphics.Color

/** Parse a hex color string (e.g. "#FF0000") into a Compose [Color], or return null. */
fun parseHexColor(hex: String): Color? =
    if (hex.isNotBlank()) {
        runCatching {
            val argb = android.graphics.Color.parseColor(hex)
            Color(argb)
        }.getOrNull()
    } else null
