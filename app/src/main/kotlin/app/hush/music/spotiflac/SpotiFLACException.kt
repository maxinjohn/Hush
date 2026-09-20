/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

/**
 * A SpotiFLAC operation that could not be completed, with the reason as its message.
 *
 * It lives on its own because the two things that raise it are not the same thing: the engine's
 * bridge raises it for a source that will not serve, and the gateway probes raise it for a client
 * the gateway is refusing. It used to share a file with Hush's own relay *client*, which is gone -
 * that client signed its requests with a session for Hush's version, and such a session can serve no
 * extension (see [SpotiFLACInstallIdentity]).
 */
class SpotiFLACException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
