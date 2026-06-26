/*
 * ArchiveTune (2026)
 * (c) Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.moriextractor

enum class ExtractorAudioQuality {
    AUTO,
    HIGH,
    HIGHEST,
    LOW,
    ;

    val apiValue: String
        get() =
            when (this) {
                HIGHEST -> "best"
                HIGH -> "high"
                AUTO -> "auto"
                LOW -> "low"
            }
}
