package app.hush.music.eq.data

/**
 * Supported parametric EQ filter types.
 * - PK: Peaking filter — boosts or cuts around a center frequency
 * - LSC: Low-shelf filter — affects frequencies below the cutoff
 * - HSC: High-shelf filter — affects frequencies above the cutoff
 */
enum class FilterType {
    PK,
    LSC,
    HSC,
}
