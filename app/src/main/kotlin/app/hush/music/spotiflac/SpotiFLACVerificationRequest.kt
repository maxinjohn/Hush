/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridges "playback needs SpotiFLAC verification" (raised by MusicService) to the UI.
 *
 * There are deliberately two signals, because they have two different owners:
 *
 * - [pending] is raised by playback and is purely passive. It never opens a screen, never
 *   opens a dialog and never navigates. The UI is expected to surface it as a dismissible
 *   notice with a "Verify" action.
 * - [challenge] is raised only when the *user* chooses to run the Cloudflare challenge. The
 *   verification overlay observes this one and shows the WebView.
 *
 * The split exists because a single signal used to do both jobs: playback needing a
 * verification also yanked the app to the Audio Sources screen mid-track (and again on
 * every later track that could not resolve), which is indistinguishable from the app
 * changing pages at random. Renewing in the background is the job of the extension's own session
 * renewal, which playback runs before it raises this signal.
 */
object SpotiFLACVerificationRequest {
    private val _pending = MutableStateFlow<String?>(null)

    /** Which extension last blocked playback, or null when nothing needs the user. */
    val pending: StateFlow<String?> = _pending.asStateFlow()

    private val _challenge = MutableStateFlow<String?>(null)

    /** The extension whose challenge the user asked to run now, or null. */
    val challenge: StateFlow<String?> = _challenge.asStateFlow()

    /** Playback needs verification. Passive: it raises the notice, nothing more. */
    fun request(extensionId: String?) {
        val id = extensionId.normalizedExtensionId() ?: return
        _pending.value = id
    }

    /** The user asked to solve the challenge now, defaulting to whatever is pending. */
    fun openChallenge(extensionId: String? = null) {
        val id = (extensionId ?: _pending.value).normalizedExtensionId() ?: return
        _pending.value = null
        _challenge.value = id
    }

    /** The challenge window was closed (completed or cancelled). */
    fun closeChallenge() {
        _challenge.value = null
    }

    /** The user dismissed the notice; stop showing it until playback needs verification again. */
    fun dismiss() {
        _pending.value = null
    }

    /**
     * Hands the pending extension to the Audio Sources screen, which owns its own inline
     * WebView. Consuming is what stops the notice from also appearing there.
     */
    fun consume(): String? {
        val value = _pending.value
        _pending.value = null
        return value
    }
}

private fun String?.normalizedExtensionId(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
