/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 and Section 5
 */

package app.hush.music.spotiflac

import java.net.URI
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The signed-request protocol of a LastWave-style *personal* addon server.
 *
 * A LastWave addon is the standard server-addon shape - `manifest.json`, `search?q=`, `stream/<id>` -
 * published under `/a/<token>` and gated by an HMAC the client has to compute. Without the two
 * headers the service answers 403 to every call; with them it answers like any other addon. Measured
 * against LastWave's own client (`com.lastwave.app`, `data/addon/AddonClient.kt`):
 *
 * ```
 * GET /a/<token>/search?q=...&quality=hi_res&atmos=none
 *   X-LW-TS:   1730000000
 *   X-LW-Sign: hex(HMAC-SHA256(secret, "<ts>\nGET\n/a/<token>/search\n<token>"))
 * GET /a/<token>/stream/<id>?quality=lossless&atmos=none
 *   X-LW-Intent: stream
 *   User-Agent:  LastWave-Player/1.0
 * ```
 *
 * The signed message is the timestamp, the method, the *path only* (the query is not part of it) and
 * the token, joined by newlines. The token is the segment after `/a/`, which is why only an address
 * of that shape is signed: an Eclipse-style addon - `https://host/<token>/manifest.json` - has no
 * `/a/` in its path, and LastWave itself skips signing for it (`signRequest` returns when the token
 * regex does not match). Sending the headers to such a host would be a rename, not a handshake.
 *
 * The secret is the client secret LastWave's own repository publishes as its fallback
 * (`DEFAULT_ADDON_SECRET`). It is the value a personal addon server is configured with, so a server
 * that works in LastWave answers Hush with the same signature - which is the whole point of this
 * class. Nothing here is used against a public addon service: an addon is signed only when its own
 * manifest address carries the `/a/<token>` shape.
 */
object LastWaveAddon {
    /**
     * LastWave's published fallback client secret, used when the app's native signer is absent.
     *
     * Kept verbatim rather than derived: a personal addon server is provisioned with this value, and
     * any other string produces a signature the server rejects.
     */
    private const val CLIENT_SECRET =
        "36d96a751b12ee481c281a8a8e64c482d0c1634a22061ab72f40175017818b85"

    /** The segment LastWave signs for: the token after `/a/`. */
    private val tokenPath = Regex("""/a/([^/]+)""")

    /** The user agent LastWave sends when it is asking for a file. */
    const val PLAYER_USER_AGENT = "LastWave-Player/1.0"

    /** The user agent LastWave sends when the file is a download. */
    const val DOWNLOADER_USER_AGENT = "LastWave-Downloader/1.0"

    /** The token in [url], or null when its path is not an addon address. */
    fun tokenOf(url: String): String? =
        runCatching {
            URI(url.trim()).path.orEmpty()
        }.getOrNull()?.let { path -> tokenPath.find(path)?.groupValues?.get(1) }

    /** Whether [url] is served by a personal addon that expects LastWave's signature. */
    fun isSigned(url: String): Boolean = tokenOf(url) != null

    /**
     * The signature over [path] at [timestampSeconds], as lowercase hex.
     *
     * The path is the request's own (`/a/<token>/search`), not the base and not the query: LastWave
     * signs `URI.path`, and a server that validated a URL with its query would reject the request.
     */
    fun signature(
        token: String,
        path: String,
        method: String,
        timestampSeconds: Long,
    ): String {
        val message = "$timestampSeconds\n${method.uppercase(Locale.US)}\n$path\n$token"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(CLIENT_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * The intent and identity LastWave sends when the request is for a file, without a signature.
     *
     * Its client sets these two headers on *every* `/stream/<id>` call - the branch that picks them
     * is before and independent of `signRequest` - so a LastWave-style addon that is not published
     * under `/a/` (Tido) still sees `X-LW-Intent: stream` and `LastWave-Player/1.0`. That is the
     * request [ServerAddonHost] reproduces for such an addon, which is why the pair lives here and
     * not only inside [headers].
     */
    fun intentHeaders(intent: String): Map<String, String> =
        linkedMapOf(
            "X-LW-Intent" to intent,
            "User-Agent" to if (intent == "stream") PLAYER_USER_AGENT else DOWNLOADER_USER_AGENT,
        )

    /**
     * The headers [url] has to be asked with, or null when it is not a personal addon.
     *
     * [intent] is `stream` or `download`; null for search, which LastWave sends without either. The
     * timestamp comes from the caller so a test can pin the signature to a known vector.
     */
    fun headers(
        url: String,
        method: String = "GET",
        intent: String? = null,
        nowSeconds: Long = System.currentTimeMillis() / 1000L,
    ): Map<String, String>? {
        val token = tokenOf(url) ?: return null
        val path =
            runCatching { URI(url.trim()).path.orEmpty() }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: return null
        val headers =
            linkedMapOf(
                "X-LW-TS" to nowSeconds.toString(),
                "X-LW-Sign" to signature(token, path, method, nowSeconds),
            )
        if (intent != null) headers.putAll(intentHeaders(intent))
        return headers
    }

    /**
     * The tier a personal addon is asked for, in LastWave's own vocabulary.
     *
     * The addon's manifest carries no `settings` array, so nothing declares these; LastWave sends
     * them on every request and the server reads them. An unrecognised request falls to `lossless`,
     * which is what LastWave sends for its default and for the Atmos tier's stereo fallback.
     */
    fun qualityToken(quality: String): String =
        when (quality.trim().uppercase(Locale.US)) {
            "HI_RES_LOSSLESS", "HI_RES", "HIRES", "HIGH_RES", "FLAC_HIRES", "BEST",
            "BESTAVAILABLE", "BEST_AVAILABLE", "HIGHEST", "AUTO",
            -> "hi_res"
            "HIGH", "HIGH_320" -> "high"
            "MEDIUM", "MEDIUM_160", "NORMAL", "LOW", "LOW_96" -> "low"
            else -> "lossless"
        }

    /**
     * [declared] with the two parameters a personal addon expects, where it did not declare its own.
     *
     * Atmos is always `none`: a Dolby stream is AC-4, which this device has no output path for (see
     * [SpotiFLACQualityCascade]), and `auto` is the value LastWave sends when it actually wants one.
     */
    fun requestParameters(
        declared: Map<String, String>,
        quality: String,
    ): Map<String, String> {
        val params = LinkedHashMap(declared)
        if (params.keys.none { it.equals("quality", ignoreCase = true) }) {
            params["quality"] = qualityToken(quality)
        }
        if (params.keys.none { it.equals("atmos", ignoreCase = true) }) {
            params["atmos"] = "none"
        }
        return params
    }
}
