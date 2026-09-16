/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.waze

/**
 * What a Bridge install attempt actually did, and how to say it.
 *
 * Polling the package manager could never close this hole on its own: a sideloaded Bridge is scanned
 * by the device's package verifier (Google Play Protect) and the install only lands once that
 * prompt is answered. Until then the package is simply absent, so the screen reported "not
 * installed" about an install the user had just watched - with the prompt blocking it still on
 * screen, unmentioned. Naming the cause, and re-raising the installer once after the answer, is the
 * whole fix; the rules are kept here, pure, so they can be tested without a device.
 */
object WazeBridgeInstallOutcome {

    /** How an attempt ended. */
    enum class Kind {
        /** The bundled Bridge is now installed where nothing was. */
        INSTALLED,

        /** An installed Bridge was replaced by a different version. */
        UPDATED,

        /** Nothing to do: the installed Bridge already is the bundled one. */
        ALREADY_CURRENT,

        /** The install did not land. */
        FAILED_INSTALL,

        /** The update did not land - the old version is still what is installed. */
        FAILED_UPDATE,

        /** An ending the cases above do not describe (for example an update that removed it). */
        OTHER,
    }

    fun kind(
        wasInstalled: Boolean,
        previousVersionCode: Long?,
        finalInspection: WazeBridgeInspection,
    ): Kind = when {
        !wasInstalled && finalInspection.isInstalled -> Kind.INSTALLED

        wasInstalled && finalInspection.isInstalled &&
            finalInspection.installedVersionCode != previousVersionCode -> Kind.UPDATED

        finalInspection.state == WazeBridgeState.BRIDGE_CURRENT -> Kind.ALREADY_CURRENT

        !wasInstalled && !finalInspection.isInstalled -> Kind.FAILED_INSTALL

        wasInstalled && finalInspection.isInstalled -> Kind.FAILED_UPDATE

        else -> Kind.OTHER
    }

    /** Whether the attempt ended with the bundled Bridge in place. */
    fun landed(kind: Kind): Boolean = kind == Kind.INSTALLED || kind == Kind.UPDATED

    /**
     * Whether the device's package verifier could be what interrupted this attempt.
     *
     * Only when the device verifies sideloaded packages and the install did not land: a verifier
     * prompt is the one such failure the user can clear themselves, so it is worth naming with what
     * to do about it - but turning a plain failure into a Play Protect story would be a guess.
     */
    fun mayBeAwaitingVerifier(kind: Kind, verificationEnabled: Boolean): Boolean =
        verificationEnabled && (kind == Kind.FAILED_INSTALL || kind == Kind.FAILED_UPDATE)

    /**
     * Whether to raise the installer again, once, after a failure.
     *
     * The answer to the verifier's prompt is what the first attempt was waiting for, and it is given
     * after that attempt's installer screen has already closed - so nothing observes the install
     * until it is asked for a second time.
     */
    fun shouldRetry(kind: Kind, verificationEnabled: Boolean, alreadyRetried: Boolean): Boolean =
        !alreadyRetried && mayBeAwaitingVerifier(kind, verificationEnabled)

    /**
     * The line to show while an attempt has not settled yet.
     *
     * Shown before the attempt is over, so it says what to do rather than what went wrong - a
     * sideloaded install that is taking a while is almost always a prompt awaiting an answer.
     */
    fun waitingMessage(displayName: String, verificationEnabled: Boolean): String =
        if (verificationEnabled) {
            "$displayName is waiting on Google Play Protect: if it asks whether to scan the app, " +
                "choose Scan app."
        } else {
            "Still installing $displayName..."
        }

    /**
     * The line to show once an attempt has finished.
     *
     * [fallbackText] is the Bridge's own state in the user's terms, used only for endings the cases
     * above do not recognise.
     */
    fun message(
        kind: Kind,
        displayName: String,
        fallbackText: String,
        verificationEnabled: Boolean,
        alreadyRetried: Boolean,
    ): String = when (kind) {
        Kind.INSTALLED -> "$displayName installed successfully."
        Kind.UPDATED -> "$displayName updated successfully."
        Kind.ALREADY_CURRENT -> "$displayName is installed and up to date."

        Kind.FAILED_INSTALL, Kind.FAILED_UPDATE ->
            if (!mayBeAwaitingVerifier(kind, verificationEnabled)) {
                if (kind == Kind.FAILED_INSTALL) {
                    "$displayName was not installed."
                } else {
                    "$displayName was not updated."
                }
            } else if (alreadyRetried) {
                "$displayName is still not installed. Google Play Protect is holding it: open its " +
                    "prompt, choose Scan app, then install again."
            } else {
                waitingMessage(displayName, verificationEnabled)
            }

        Kind.OTHER -> "$displayName: $fallbackText."
    }
}
