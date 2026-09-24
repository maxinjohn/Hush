package app.hush.music.eq.data

import kotlinx.serialization.Serializable

/**
 * Saved EQ Profile with metadata.
 *
 * `@Serializable` is load-bearing, not decoration: [app.hush.music.eq.EQProfileRepository]
 * persists these with `Json.encodeToString`/`decodeFromString`, and the reified overloads
 * resolve the serializer by reflection at runtime. Without the annotation there is no
 * generated serializer to find, and the lookup throws `SerializationException` on the
 * main thread the first time a profile is saved.
 */
@Serializable
data class SavedEQProfile(
    val id: String,                       // Unique identifier
    val name: String,                     // Display name
    val bands: List<ParametricEQBand>,    // EQ bands
    val preamp: Double = 0.0,             // Preamp gain in dB
    val isCustom: Boolean = false,        // Whether this is a custom profile
    val isActive: Boolean = false,        // Whether this profile is currently active
    val addedTimestamp: Long = System.currentTimeMillis(),
)
