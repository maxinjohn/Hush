/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import android.webkit.WebView

/**
 * Which engine will render a SpotiFLAC verification challenge, and whether it can pass it.
 *
 * A source's challenge is a Cloudflare Turnstile widget running inside the app's embedded
 * WebView. Cloudflare's supported-browser policy puts engines "more than five years old" and
 * "embedded browsers" into its limited-support bucket, and a limited-support engine is the
 * worst possible host for the widget: the page loads, the widget renders, and no token is
 * ever minted - which surfaces as a challenge that sits until the attempt times out, for
 * every source, on every run. Car head units are the extreme case: they ship a frozen AOSP
 * WebView (Chrome 83 on the unit this was diagnosed on) and, because
 * `com.google.android.webview` is not installed on them, there is no update path at all.
 *
 * The same head unit usually has a current Chrome, so the engine version is what decides
 * which route verification takes: the WebView when it can still solve the check, and the
 * device's real browser (with the grant coming back to Hush) when it cannot.
 */
object SpotiFLACChallengeEngine {

    /**
     * Oldest embedded-engine major version expected to pass Cloudflare's check. Chrome 100
     * shipped in early 2022, which keeps this safely inside the five-year line Cloudflare
     * documents as supported.
     *
     * Deliberately biased towards *keeping* the WebView route: an engine whose version cannot
     * be read is reported as capable, so only a device Hush can positively measure as too old
     * is moved off the in-app challenge. A phone with an up-to-date WebView never changes
     * behaviour because of this.
     */
    const val MIN_ENGINE_MAJOR = 100

    /** The WebView package currently in use, as far as it can be determined. */
    data class Engine(
        val packageName: String?,
        val versionName: String?,
        val major: Int?,
    ) {
        /** True when the version could be read, i.e. [major] is meaningful. */
        val known: Boolean get() = major != null
    }

    /** Version of the engine that would render a challenge right now. */
    fun current(): Engine = runCatching {
        // Public since API 26 (this app's minSdk), which is exactly the API level where
        // WebView became an independently updateable package.
        val info = WebView.getCurrentWebViewPackage()
        val version = info?.versionName
        Engine(
            packageName = info?.packageName,
            versionName = version,
            major = parseMajor(version),
        )
    }.getOrDefault(Engine(packageName = null, versionName = null, major = null))

    internal fun parseMajor(versionName: String?): Int? =
        versionName?.trim()?.substringBefore('.')?.takeIf { it.isNotEmpty() }?.toIntOrNull()

    /**
     * True when the WebView route is expected to mint a Cloudflare token.
     *
     * An unknown engine answers `true`: refusing to try because a version string could not be
     * parsed would break verification on devices where it works today.
     */
    fun canSolveCloudflare(engine: Engine): Boolean =
        engine.major?.let { it >= MIN_ENGINE_MAJOR } ?: true

    /** Engine label for diagnostics and for the notice shown when it is too old. */
    fun describe(engine: Engine): String = when {
        !engine.versionName.isNullOrBlank() -> "WebView ${engine.versionName}"
        !engine.packageName.isNullOrBlank() -> "WebView ${engine.packageName}"
        else -> "the system WebView"
    }

    /**
     * True when a clipboard payload is a verification callback the challenge page produced.
     *
     * The page's own "Copy link" button copies the full callback deep link
     * (`spotiflac://session-grant?grant=...`), so requiring a grant/code parameter is enough
     * to recognise it. Kept strict on purpose: anything looser would let unrelated clipboard
     * content be exchanged as a grant.
     */
    fun isVerificationCallback(text: String?): Boolean {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.length > MAX_CALLBACK_LENGTH) return false
        return trimmed.contains("grant=") || trimmed.contains("code=")
    }

    /** A grant deep link is short; anything longer is not one. */
    private const val MAX_CALLBACK_LENGTH = 8 * 1024
}
