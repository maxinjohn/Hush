/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The parts of a verification challenge that do not depend on the screen hosting it.
 *
 * Every verification surface Hush has - the player overlay and the Audio Sources screen - runs
 * the same Cloudflare page, and on a device whose embedded WebView is too old for that page
 * ([SpotiFLACChallengeEngine]) every one of them has to fall back to the same alternative: solve
 * the check in a real browser, then hand the callback back. Keeping that route in one place is
 * what makes verification behave the same wherever the user starts it, instead of the WebView
 * surfaces failing while one of them happens to have an escape hatch.
 */
object SpotiFLACChallengeRoute {

    /**
     * Reads the challenge page's own status line, plus whether Cloudflare's widget rendered.
     *
     * A stalled challenge and a solving one are indistinguishable from outside, and an
     * automatic run has no screen to look at, so this is what turns "it never finished" into a
     * cause worth logging.
     */
    const val STATUS_PROBE = """
(function() {
    try {
        var status = document.getElementById('status');
        var text = status ? status.textContent.trim() : '';
        var widget = document.querySelector('.cf-turnstile iframe') ? 'widget' : 'no-widget';
        return (text || '(no status)') + ' | ' + widget;
    } catch (e) { return 'probe-failed'; }
})();
"""

    /** What to tell the user when this device's WebView cannot run the check at all. */
    fun unsupportedNotice(engine: SpotiFLACChallengeEngine.Engine): String =
        "${SpotiFLACChallengeEngine.describe(engine)} cannot run Cloudflare's check, so the " +
            "challenge has to be solved in your browser. Solve it there and Hush finishes on its " +
            "own - there is nothing to copy."

    /**
     * The gateway only issues a callback to Hush's own `spotiflac` scheme.
     *
     * Measured against the live challenge endpoint: `spotiflac://session-grant?...`,
     * `spotiflac://anything?x=1` and even `spotiflac://session-grant/extra` come back as the
     * page's `callbackUrl`, while `hush://spotiflac-grant`, `https://...` and any other scheme
     * are silently dropped to an empty string. An empty `callbackUrl` leaves the page with no
     * target at all, which is why a browser could only ever offer its copy button.
     */
    fun callbackBelongsToScheme(url: String): Boolean {
        val trimmed = url.trim()
        if (!trimmed.contains("://")) return false
        return trimmed.substringBefore("://").equals(CALLBACK_SCHEME, ignoreCase = true)
    }

    private const val CALLBACK_SCHEME = "spotiflac"

    /**
     * The grant a browser-solved challenge re-delivers on its own page.
     *
     * The page cannot hand the grant back by navigating: the redirect it performs is
     * script-initiated, and every browser refuses to launch an app from one without a user
     * gesture. What the page *does* expose is its own self-heal mode - once a challenge has been
     * verified it embeds the still-unspent grant as `redeliverGrant`, so reading that page is
     * the delivery channel that needs no copy, no paste and no gesture, in any browser, on any
     * device.
     */
    /** What a challenge page currently holds. */
    sealed interface PageState {
        /** Not solved yet: a check in a browser can still produce a grant. */
        data object Pending : PageState

        /** Solved, and the page is still publishing its unspent grant. */
        data class Grant(val token: String) : PageState

        /**
         * The challenge is marked used and publishes no grant, so nothing can ever come from it.
         *
         * The gateway marks a challenge used as soon as it is verified and drops the grant once it
         * has been exchanged. Handing such a page to a browser can only end in the page's own
         * "Invalid request" - which is what a stale challenge URL (kept from an earlier bootstrap)
         * produced - so callers must refresh instead of opening it.
         */
        data object Spent : PageState
    }

    /** Classifies a challenge page, using the same markers the page's own script branches on. */
    fun pageState(html: String?): PageState {
        val grant = grantFromChallengePage(html)
        if (grant != null) return PageState.Grant(grant)
        if (html.isNullOrBlank()) return PageState.Pending
        val used = Regex("var\\s+challengeUsed\\s*=\\s*" + "\"?([^\";]+)\"?")
            .find(html)?.groupValues?.get(1)?.trim()
        return if (used == "1") PageState.Spent else PageState.Pending
    }

    /** One read of a challenge URL, classified. Null when the page could not be read at all. */
    suspend fun readPageState(url: String): PageState? =
        fetchChallengePage(url)?.let { pageState(it) }

    internal fun grantFromChallengePage(html: String?): String? {
        if (html.isNullOrBlank()) return null
        // Only a *used* challenge carries a grant; without this the loose match below could
        // pick a token out of the page's own example text and hand the app a spent value.
        val used = Regex("var\\s+challengeUsed\\s*=\\s*" + "\"?([^\";]+)\"?").find(html)
            ?.groupValues?.get(1)?.trim()
        if (used != "1") return null
        val quoted = Regex("var\\s+redeliverGrant\\s*=\\s*\"([^\"]+)\"")
            .find(html)?.groupValues?.get(1)?.trim()
        if (!quoted.isNullOrBlank()) return quoted
        // Some responses only inline it in the fallback text (`grant=gr_...`).
        return Regex("grant=?(gr_[A-Za-z0-9_-]{8,})").find(html)?.groupValues?.get(1)
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * One GET of the challenge page, or null when it could not be read.
     *
     * Exposed because a challenge the user already passed still publishes its unspent grant, so
     * every route checks the page once before spending the user's attention on a browser or a
     * WebView - which is what lets a solved check survive Hush's process being killed.
     */
    suspend fun readChallengePage(url: String): String? = fetchChallengePage(url)

    /** One GET of the challenge page, or null when it could not be read. */
    private suspend fun fetchChallengePage(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "text/html")
                .header("User-Agent", "SpotiFLAC-Mobile/${SpotiFLACSessionManager.APP_VERSION}")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()
            }
        }.getOrNull()
    }

    /**
     * Waits for the browser-solved challenge to publish its grant, and reads it back.
     *
     * This is what makes the browser route tap-only: the user solves the check in whatever
     * browser the device has, and Hush finishes the verification from its own side without ever
     * asking them to copy a code back.
     */
    suspend fun awaitPageGrant(
        challengeUrl: String,
        timeoutMs: Long = 180_000L,
        intervalMs: Long = 3_000L,
    ): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (val state = readPageState(challengeUrl)) {
                is PageState.Grant -> return state.token
                // Nothing can ever come from a spent challenge, so waiting the full window would
                // hold the user in front of a dead page for three minutes. Stop now; the caller
                // refreshes the challenge instead.
                PageState.Spent -> return null
                else -> delay(intervalMs)
            }
        }
        return null
    }

    /**
     * Opens the challenge in the device's real browser.
     *
     * Custom Tabs first, so the user stays in a tab they can back out of and the work is done by
     * an engine that is actually current (a head unit's frozen WebView never is). Falls back to a
     * plain VIEW intent when no Custom Tabs provider is installed.
     */
    fun openInBrowser(context: Context, url: String) {
        val uri = Uri.parse(url)
        runCatching { CustomTabsIntent.Builder().build().launchUrl(context, uri) }
            .onFailure {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
    }

    /** The clipboard's text, or null when it is empty or the read is refused. */
    fun clipboardText(context: Context): String? = runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString()
    }.getOrNull()

    /**
     * The grant for a browser-solved challenge, from whichever channel delivers it first.
     *
     * The page's own re-delivery is the primary channel, because it needs nothing from the
     * user; the clipboard is kept because a browser that *can* launch the app (or a user who
     * copies deliberately) must still work, and a slow phone should not lose a verification
     * merely because one of the two paths was unavailable.
     */
    suspend fun awaitBrowserGrant(
        owner: LifecycleOwner?,
        context: Context,
        challengeUrl: String?,
        timeoutMs: Long = 180_000L,
    ): String? = coroutineScope {
        val page = challengeUrl?.let { url -> async { awaitPageGrant(url, timeoutMs) } }
        // The clipboard channel needs a resumed owner (a background read is what puts a paste
        // notification on the user's screen), so a run started without a screen - the
        // notification action, which is the car's route - uses the page channel alone. That is
        // the one that needs nothing from the user anyway.
        val clipboard = owner?.let { resumed ->
            async { awaitCopiedCallback(resumed, context, attemptTimeoutMs = timeoutMs) }
        }
        var found: String? = null
        val deadline = System.currentTimeMillis() + timeoutMs + 5_000L
        while (found == null && System.currentTimeMillis() < deadline) {
            found = listOfNotNull(page, clipboard)
                .filter { it.isCompleted && !it.isCancelled }
                .firstNotNullOfOrNull { runCatching { it.getCompleted() }.getOrNull() }
            if (found == null) delay(250)
        }
        listOfNotNull(page, clipboard).forEach { it.cancel() }
        found
    }

    /**
     * Waits for the callback the challenge page copies for the user.
     *
     * The browser route ends with the page putting its callback link on the clipboard, which is
     * the only delivery an old-WebView device can complete. Watching for it turns that into one
     * tap in the browser instead of a manual code exchange.
     *
     * Only read while [owner] is resumed: a clipboard read from the background is what puts a
     * paste notification on the user's screen.
     */
    suspend fun awaitCopiedCallback(
        owner: LifecycleOwner,
        context: Context,
        attemptTimeoutMs: Long = 60_000L,
        intervalMs: Long = 1_500L,
    ): String? {
        val deadline = System.currentTimeMillis() + attemptTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(intervalMs)
            if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) continue
            val copied = clipboardText(context) ?: continue
            if (SpotiFLACChallengeEngine.isVerificationCallback(copied)) return copied
        }
        return null
    }
}
