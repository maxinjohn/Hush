package app.hush.music.eq.data

import kotlinx.serialization.Serializable

/**
 * Represents a single parametric EQ filter/band.
 *
 * Serializable because it is nested inside [SavedEQProfile], which is persisted as JSON.
 */
@Serializable
data class ParametricEQBand(
    val frequency: Double,                      // Center frequency in Hz
    val gain: Double,                           // Gain in dB
    val q: Double = 1.41,                       // Q factor (bandwidth) — default sqrt(2)
    val filterType: FilterType = FilterType.PK, // Filter type
    val enabled: Boolean = true,                 // Whether this band is active
)

/**
 * Represents a complete parametric EQ configuration.
 */
@Serializable
data class ParametricEQ(
    val preamp: Double,                         // Preamp/gain in dB (to prevent clipping)
    val bands: List<ParametricEQBand>,          // List of EQ bands
)
