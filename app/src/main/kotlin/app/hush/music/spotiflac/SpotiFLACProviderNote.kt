/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

/**
 * The one line a user reads when a source's *own* service is refusing this client.
 *
 * The Audio Sources screen shows two verdicts about the same source and they answer different
 * questions: the session line ("Verified - renews automatically") is about the signed session on
 * this device, while the Test and its health check are about the service behind the source. Read
 * next to each other without a scope, "Verified - 3h left" above a red "Zarz API is offline" looks
 * like a contradiction, and the reported device said exactly that: *"why is session active for
 * deezer but test failed"*. Nothing was broken - measured at that moment, the gateway's own status
 * page answered `services.deezer.ok=false, error="fetch failed"` and a real download through Deezer
 * stalled on `resolving_stream` for 20s and died - so the fix is to say whose service it is.
 *
 * The reason is kept verbatim from [SpotiFLACProviderHealth] so the sentence names what the provider
 * actually reported, and the scope prefix is the part that has to fit: a row ellipsizes from the
 * right, so the words that resolve the contradiction are the ones that come first.
 *
 * Deliberately pure - no clock, no runtime - so the phrasing is pinned by tests rather than by
 * whichever device happened to be attached.
 */
object SpotiFLACProviderNote {

    /**
     * Who is failing, said before what failed.
     *
     * "Own" is the load-bearing word: the provider reports on itself, so this is never a statement
     * about the user's session, their verification, or their address.
     */
    fun headline(reason: String): String = "Provider's own service - ${reason.trim()}"

    /**
     * The same headline with how long the source stays at the back of the chain.
     *
     * "tried last" rather than "skipped", because that is what the sweep does: a source whose own
     * health says it cannot serve is demoted for the cooldown, never dropped - a sweep whose only
     * sources are unreachable still asks them.
     */
    fun row(
        reason: String,
        remainingMs: Long,
    ): String = "${headline(reason)} · tried last for ${SpotiFLACSessionRenewer.formatBlockRemaining(remainingMs)}"
}
