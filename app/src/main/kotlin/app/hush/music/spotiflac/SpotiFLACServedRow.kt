/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

/**
 * The format-row marker for audio SpotiFLAC served.
 *
 * A row written with this itag claims the audio came from a SpotiFLAC source, which is what lets the
 * player say where a track is coming from and what the stored row is allowed to assert. It is kept
 * here, next to the engine it describes, rather than inside the resolver that used to produce such a
 * row: that resolver is gone (it could only run with a gateway session Hush can no longer obtain -
 * see [SpotiFLACInstallIdentity]), while the rows it marked are still in every library.
 *
 * [app.hush.music.playback.DecodedAudioRow.DECODED_ITAG] is deliberately a *different* value: a row
 * built from what Media3 is decoding must never be read as one that says SpotiFLAC served a file.
 */
object SpotiFLACServedRow {
    const val ITAG = -8001
}
