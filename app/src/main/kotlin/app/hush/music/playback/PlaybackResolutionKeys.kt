/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

/**
 * The identity of an in-flight playback resolution, and which of them are abandoned.
 *
 * A resolution is keyed by media id *and* by the stream client it was asked for, so a request
 * for the default client and one for an explicit client are two entries for the same track.
 * Both belong to the same track, which is what matters when a skip makes that track's requests
 * irrelevant: keying the decision on the whole key would leave one of the two running.
 *
 * Pure on purpose - it is the part of the skip path that a unit test can hold still, and the
 * part that a later change to the key format would silently break.
 */
internal object PlaybackResolutionKeys {

    /** The map key for one request. [preferredClientOverride] is null for the default route. */
    fun of(
        mediaId: String,
        preferredClientOverride: String?,
    ): String = "$mediaId|${preferredClientOverride ?: DEFAULT_OVERRIDE}"

    /**
     * The media id a key was built for, whichever client it was asked for.
     *
     * Split from the *last* separator: the client name never contains one, so a media id that
     * does still round-trips. Truncating such an id would leave the track now playing looking
     * abandoned, and its own resolve would be cancelled out from under the load waiting for it.
     */
    fun mediaIdOf(key: String): String = key.substringBeforeLast(KEY_SEPARATOR)

    /**
     * Keys that no longer belong to anything the player wants.
     *
     * [keepMediaIds] is the track now playing, so everything else is a request for a track the
     * user has navigated away from and is safe to abandon.
     */
    fun abandoned(
        keys: Collection<String>,
        keepMediaIds: Set<String>,
    ): List<String> = keys.filter { mediaIdOf(it) !in keepMediaIds }

    private const val KEY_SEPARATOR = '|'
    private const val DEFAULT_OVERRIDE = "default"
}
