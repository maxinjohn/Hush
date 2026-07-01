/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.utils

import app.hush.music.constants.AudioQuality

/**
 * Applies low-data savings without overriding an explicit Max quality choice.
 */
fun resolveEffectiveAudioQuality(
    requested: AudioQuality,
    lowDataModeActive: Boolean,
): AudioQuality {
    if (!lowDataModeActive) return requested
    return when (requested) {
        AudioQuality.HIGHEST -> AudioQuality.HIGHEST
        AudioQuality.HIGH -> AudioQuality.HIGH
        AudioQuality.AUTO -> AudioQuality.HIGH
        AudioQuality.LOW -> AudioQuality.LOW
    }
}
