/*
 * Hush (2026)
 * Audio visualizer shared data types for Android's Visualizer FFT/waveform capture.
 * This is a new feature not present in the parent ArchiveTune project.
 */

package app.hush.music.playback

/** Visualizer rendering style. */
enum class VisualizerStyle {
    BARS,
    WAVEFORM,
    CIRCULAR,
}

/** Where the visualizer gets its colors from. */
enum class VisualizerColorSource {
    /** Use the Material theme accent color. */
    THEME,
    /** Use a user-selected custom accent color. */
    ACCENT,
    /** Dynamically extract colors from the current album artwork. */
    ARTWORK,
}

/** Frequency band data (0..1 per band) with peak hold values. */
data class VisualizerBands(
    val bands: FloatArray,
    val peakBands: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VisualizerBands) return false
        return bands.contentEquals(other.bands) && peakBands.contentEquals(other.peakBands)
    }

    override fun hashCode(): Int {
        var result = bands.contentHashCode()
        result = 31 * result + peakBands.contentHashCode()
        return result
    }
}

/** Normalized waveform samples (0..1). */
data class VisualizerWaveform(
    val samples: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VisualizerWaveform) return false
        return samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int = samples.contentHashCode()
}
