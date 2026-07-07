package app.hush.music.eq.data

/**
 * Saved EQ Profile with metadata.
 */
data class SavedEQProfile(
    val id: String,                       // Unique identifier
    val name: String,                     // Display name
    val bands: List<ParametricEQBand>,    // EQ bands
    val preamp: Double = 0.0,             // Preamp gain in dB
    val isCustom: Boolean = false,        // Whether this is a custom profile
    val isActive: Boolean = false,        // Whether this profile is currently active
    val addedTimestamp: Long = System.currentTimeMillis(),
)
