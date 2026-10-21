/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 and Section 5
 */

package app.hush.music.spotiflac

import app.hush.music.BuildConfig
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Who Hush says it is when it talks to an addon or a repository.
 *
 * A BitChord addon can be published behind a client check, and the check is the product token at
 * the front of the user agent: measured against `gglos.ggottes.deno.net`, `BitChord/1.0` and
 * `BitChord/1.0.0` answer 200, while no user agent at all, a browser's, `BitChord` without a
 * version and `BitChord/0.5.0` all answer `403 {"error":"This addon only serves BitChord"}`. The
 * host is not checking for a browser and cannot be satisfied by one - it is checking for the
 * client whose addon API the manifest was written against.
 *
 * Hush runs that same module API ([SpotiFLACModuleHost]) and ships the module index by default, so
 * presenting the product token is what makes such an addon addable and playable here at all. The
 * token is followed by Hush's own name and version rather than replacing it, because a host that
 * ever wants to tell the two clients apart should be able to: `BitChord/1.0 Hush/13.14.7` answers
 * 200 where the bare token does, and it is the address Hush is honest about.
 *
 * Kept in one place because two requests need it and they are the two halves of the same
 * capability: reading the repository (where the gate answers first) and running the module (where
 * its own API calls are gated once it is running). Two literals would drift, and the half that
 * drifted would look like a broken addon rather than a missing header.
 *
 * ## The second identity
 *
 * The token is a *client* name, and a host that is not that client may refuse it outright rather
 * than ignore it. Measured against `tido.tido.tido.dpdns.org`: its manifest answers 200 to every
 * user agent except one starting with `BitChord/`, which its edge answers with a Cloudflare block
 * page - so Hush's repository check reported "It answered 403 - that file is private or blocks
 * Hush" about a file that is public in a browser. The token is what that host refuses, not Hush,
 * and the address Hush should fall back to is its own name: `Hush/13.14.7` answers 200 there, and
 * `gg.gglos.addon` refuses it in its turn. So the token is tried first - it is the identity that
 * gets past a check - and [alternativeFor] is the second attempt for a host that says 401 or 403,
 * asked once per host per process because the answer does not change inside one launch.
 */
object AddonClientIdentity {
    /** The token the BitChord addon hosts check for, and the version they accept. */
    private const val ProductToken = "BitChord/1.0"

    /** What every addon and repository request is sent as, and the first attempt at any host. */
    val userAgent: String = "$ProductToken Hush/${BuildConfig.VERSION_NAME}"

    /**
     * Hush's own name, without the product token.
     *
     * The second attempt: a host that refuses `BitChord/` still knows Hush, and a host that refuses
     * *this* one is not asking for a client name at all - it answered 403 for its own reason, which
     * the caller reports as it is rather than as a failed rename.
     */
    val neutralUserAgent: String = "Hush/${BuildConfig.VERSION_NAME}"

    /** Hosts that answered 401/403 to [userAgent]; they are asked as [neutralUserAgent] instead. */
    private val refusedProductToken: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** What to send first to [url]: Hush's own name once this host has refused the token. */
    fun userAgentFor(url: String): String =
        if (hostOf(url)?.let { it in refusedProductToken } == true) neutralUserAgent else userAgent

    /**
     * The identity to retry [url] as after a 401/403, or null when there is nothing left to try.
     *
     * Null for a host that already refused the token - the retry has happened, and a third request
     * with the same name would only cost the caller another round trip to learn nothing.
     */
    fun alternativeFor(url: String): String? {
        val host = hostOf(url) ?: return null
        return if (host in refusedProductToken) null else neutralUserAgent
    }

    /**
     * Record that [url]'s host refused [userAgent].
     *
     * Per host and per process: the same file on the same host answers the same way for as long as
     * the process lives, and remembering it is what keeps a source that is asked about a track
     * twenty times from doubling every request.
     */
    fun noteProductTokenRefused(url: String) {
        hostOf(url)?.let { refusedProductToken.add(it) }
    }

    /** Test seam: forget which hosts refused, so one case cannot colour the next. */
    internal fun forgetRefusals() = refusedProductToken.clear()

    private fun hostOf(url: String): String? =
        runCatching {
            URI(url.trim()).host?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }
        }.getOrNull()
}
