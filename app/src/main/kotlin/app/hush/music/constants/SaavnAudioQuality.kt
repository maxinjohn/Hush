/*
 * Hush — GPL-3.0
 * Adapted from Vivi Music (GPL-3.0).
 */

package app.hush.music.constants

enum class SaavnAudioQuality {
    QUALITY_320,
    QUALITY_160,
    QUALITY_96,
    ;

    fun toApiValue(): String =
        when (this) {
            QUALITY_320 -> "320kbps"
            QUALITY_160 -> "160kbps"
            QUALITY_96 -> "96kbps"
        }

    fun toLabel(): String =
        when (this) {
            QUALITY_320 -> "High (320 kbps)"
            QUALITY_160 -> "Medium (160 kbps)"
            QUALITY_96 -> "Low (96 kbps)"
        }
}
