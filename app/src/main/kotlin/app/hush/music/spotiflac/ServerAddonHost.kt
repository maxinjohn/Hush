/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 and Section 5
 */

package app.hush.music.spotiflac

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays a *server* addon: a manifest whose source is an HTTP service rather than a script.
 *
 * The BitChord addon ecosystem contains two shapes, and Hush read only one of them. A module index
 * publishes JavaScript Hush runs in its own sandbox ([SpotiFLACModuleHost]); a server addon
 * publishes a manifest and two endpoints and does all the work itself. Measured against
 * `gglos.ggottes.deno.net`, whose own page says to paste its `manifest.json` into BitChord:
 *
 * ```
 * GET /manifest.json   {"id":"gg.gglos.addon","name":"gglos","resources":["search","stream"],...}
 * GET /search?q=taste  {"tracks":[{"id":"154008623620558848","title":"Taste","artist":"Sabrina Carpenter",
 *                                 "duration":157,"audioQuality":"HI_RES_LOSSLESS",...}]}
 * GET /stream/<id>     {"url":"https://tracks.monochrome.st/track/...","format":"flac",
 *                       "codec":"flac","container":"flac",...}
 * ```
 *
 * Before this class the manifest was rejected as "not a source repository": it has no `extensions`
 * and no `category:` list, which is every shape Hush knew. The service is what a listener wants
 * from such an addon, so it is spoken to directly - no sandbox, no download of code, and the same
 * match/quality/step-down path a module's rows already take.
 *
 * The endpoints are the addon's public API as its own manifest advertises them; the client identity
 * is [AddonClientIdentity], because these hosts serve BitChord addons only and refuse anything else
 * with `403 {"error":"This addon only serves BitChord"}`.
 */
@Singleton
class ServerAddonHost @Inject constructor(
    private val httpClient: HttpClient,
) {
    companion object {
        private const val TIMEOUT_MS = 12_000L

        /** The rows a search may return; the matcher only needs the plausible ones. */
        internal const val DEFAULT_LIMIT = 20

        /**
         * The tier asked for when the caller names none.
         *
         * The runtime's own word for CD-quality lossless, and the one
         * [AddonRequestSettings] maps onto whatever the addon calls that rung. A caller that knows
         * what the listener chose (the playback walk does) passes it rather than relying on this.
         */
        internal const val DEFAULT_QUALITY = "LOSSLESS"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }

    /**
     * Asks the addon for its own rows.
     *
     * A failure is a `Result`, never an exception: an addon is one candidate among several, and an
     * addon that cannot answer is a candidate that did not answer.
     */
    suspend fun search(
        source: ExtensionSource,
        query: String,
        limit: Int = DEFAULT_LIMIT,
        quality: String = DEFAULT_QUALITY,
    ): Result<List<SpotiFLACModuleHost.ModuleTrack>> {
        val base = source.addonBaseUrl?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("addon declares no address"))
        val settings = requestSettings(source, quality)
        logRequest(source, "search", settings)
        val body =
            get(source, addonSearchUrl(base, query, searchLimit(source, limit), settings))
                .getOrElse { return Result.failure(it) }
        return runCatching { parseAddonTracks(body) }
    }

    /**
     * Asks the addon for the file behind one of its ids.
     *
     * A manifest without a URL for the track is a *successful* call with nothing in it - the addon
     * answered about this track and had no file - and is reported as an empty result rather than as
     * a failure, so one unservable track does not take the addon out of the whole queue.
     */
    suspend fun stream(
        source: ExtensionSource,
        trackId: String,
        quality: String = DEFAULT_QUALITY,
    ): Result<SpotiFLACModuleHost.ModuleStream?> {
        val base = source.addonBaseUrl?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("addon declares no address"))
        val settings = requestSettings(source, quality)
        logRequest(source, "stream", settings)
        val body =
            get(source, addonStreamUrl(base, trackId, settings), intent = "stream")
                .getOrElse { return Result.failure(it) }
        return runCatching { parseAddonStream(body) }
    }

    /**
     * What this addon is asked with: what it declares, and the tier the listener chose.
     *
     * A personal (LastWave-style) addon published under `/a/` declares no `settings` at all - its
     * manifest is an id, a name and a resource list - so the two parameters it reads on every
     * request are added here rather than left out: `<quality>` in its own vocabulary and
     * `atmos=none`, which is the only Atmos answer this device can play (see
     * [LastWaveAddon.requestParameters]). An addon that does declare either key keeps its own value.
     *
     * A profile addon - Tido, which marks its settings `perNetwork` - already answers the settings
     * for the URL it was published at, so [AddonRequestSettings.forRequest] gives back exactly the
     * two parameters LastWave's client sends and nothing else.
     */
    private fun requestSettings(
        source: ExtensionSource,
        quality: String,
    ): Map<String, String> {
        val declared = AddonRequestSettings.forRequest(source.addonSettings, quality)
        val base = source.addonBaseUrl
        return if (base != null && LastWaveAddon.isSigned(base)) {
            LastWaveAddon.requestParameters(declared, quality)
        } else {
            declared
        }
    }

    /**
     * The `limit` a search states, or 0 for an addon that keeps its settings itself.
     *
     * LastWave's client sends `q`, `quality` and `atmos` on a search and nothing else. A profile
     * addon was written against that client, so Hush does not add a `limit` it never asks for: this
     * is a request-shape match, not a preference, and the addon's own default is the count the
     * client that works against it gets.
     */
    private fun searchLimit(
        source: ExtensionSource,
        limit: Int,
    ): Int =
        if (AddonRequestSettings.keepsSettingsOnServer(source.addonSettings)) 0 else limit

    /**
     * One line per call, so a track that comes back at the wrong tier names the question asked.
     *
     * The tier is a claim Hush makes to a service, and a service that answers in its own default
     * instead is indistinguishable from one that honoured the request unless what was asked is on
     * the record - which is the first thing to look at when a lossless setting plays an Atmos or
     * 320 kbps file.
     */
    private fun logRequest(
        source: ExtensionSource,
        stage: String,
        settings: Map<String, String>,
    ) {
        val quality =
            settings.entries
                .firstOrNull { (key, _) -> key.equals(AddonRequestSettings.QUALITY_KEY, ignoreCase = true) }
                ?.value
                ?: "default"
        SpotiFLACDiag.log(
            "addon request source=${source.id} stage=$stage quality=$quality params=" +
                settings.entries.joinToString(",") { (key, value) -> "$key=$value" },
        )
    }

    /**
     * Whether the addon answers at all, for the Audio Sources row's Test.
     *
     * One small search rather than the manifest: the manifest was read when the repository was
     * added, and the question a Test asks is whether the service behind it works now.
     */
    suspend fun probe(source: ExtensionSource): Result<String> =
        search(source, source.displayName, 1).map { rows ->
            if (rows.isEmpty()) "answered with no rows" else "answered with ${rows.size} row(s)"
        }

    /**
     * One GET, as [userAgent]: the only difference between the two attempts is who is asking.
     *
     * A personal addon's address additionally carries its signed headers - computed per request, so
     * a retry gets its own timestamp ([LastWaveAddon.headers]) - and the intent/identity LastWave
     * sends when the request is for a file. A profile addon ([lastWave]) is not signed, but its
     * stream is still asked with that same intent and identity, because the client it was written
     * for sets them unconditionally.
     */
    private suspend fun getJson(
        url: String,
        userAgent: String,
        intent: String? = null,
        lastWave: Boolean = false,
    ) = httpClient.get(url) {
        val signed = LastWaveAddon.headers(url, intent = intent).orEmpty()
        val identity =
            signed.ifEmpty {
                if (lastWave && intent != null) LastWaveAddon.intentHeaders(intent) else emptyMap()
            }
        // The identity map may name its own user agent (a stream is `LastWave-Player/1.0`); it
        // replaces the addon identity rather than being sent beside it, which would be two.
        header("Accept", "application/json")
        header("User-Agent", identity["User-Agent"] ?: userAgent)
        identity.forEach { (name, value) ->
            if (!name.equals("User-Agent", ignoreCase = true)) header(name, value)
        }
    }

    /**
     * [intent] is `stream` for a stream request and null for a search, matching what the addon's own
     * client sends; it only has an effect on a personal addon ([LastWaveAddon]).
     */
    private suspend fun get(
        source: ExtensionSource,
        url: String,
        intent: String? = null,
    ): Result<String> =
        try {
            val lastWave = AddonRequestSettings.keepsSettingsOnServer(source.addonSettings)
            val response =
                withTimeout(TIMEOUT_MS) {
                    val first = getJson(url, AddonClientIdentity.userAgentFor(url), intent, lastWave)
                    if (first.status != HttpStatusCode.OK &&
                        (first.status.value == 403 || first.status.value == 401)
                    ) {
                        // The host may refuse the BitChord token and serve Hush itself; see
                        // [AddonClientIdentity]. One retry, only for a refusal.
                        AddonClientIdentity.noteProductTokenRefused(url)
                        getJson(url, AddonClientIdentity.neutralUserAgent, intent, lastWave)
                    } else {
                        first
                    }
                }
            if (response.status != HttpStatusCode.OK) {
                // The addon's own word for the refusal when it gives one: measured against
                // `tido.tido.tido.dpdns.org`, every one of its API routes answers `403 {"error":
                // "Unavailable"}` - which is the service saying it cannot reach its upstream, and
                // is a different fact from "this addon blocks Hush". Reporting the number alone
                // sent a reader looking for a client token that no token fixes.
                val reason =
                    runCatching { response.bodyAsText() }
                        .getOrNull()
                        ?.let(::addonRefusalReason)
                        .orEmpty()
                return Result.failure(
                    IllegalStateException(
                        "${source.displayName} answered HTTP ${response.status.value}$reason",
                    ),
                )
            }
            Result.success(response.bodyAsText())
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Result.failure(IllegalStateException("${source.displayName} did not answer within 12 seconds"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
}

/**
 * The search address an addon advertises: `<base>/search?q=<query>`.
 *
 * Built with Ktor's URL builder so the query is encoded once, by the layer that sends it - a title
 * with an ampersand or a slash is a title, not a second parameter.
 *
 * [settings] are the addon's own declared values, stated on every request in the order the addon
 * protocols expect ([AddonRequestSettings]). Sorted, so the same request is the same URL: the
 * diagnostic log is only worth reading if two attempts at one tier are comparable.
 */
internal fun addonSearchUrl(
    base: String,
    query: String,
    limit: Int,
    settings: Map<String, String> = emptyMap(),
): String {
    val builder = URLBuilder(base.trimEnd('/') + "/search")
    builder.parameters.append("q", query)
    if (limit > 0) builder.parameters.append("limit", limit.toString())
    settings.toSortedMap().forEach { (key, value) -> builder.parameters.append(key, value) }
    return builder.buildString()
}

/**
 * The stream address for one of the addon's ids: `<base>/stream/<id>`.
 *
 * The id is one path segment even when the addon's own ids contain a slash, which is why it is
 * encoded rather than concatenated raw - a raw slash would address a different route.
 */
internal fun addonStreamUrl(
    base: String,
    trackId: String,
    settings: Map<String, String> = emptyMap(),
): String {
    val path = base.trimEnd('/') + "/stream/" + trackId.encodeURLPathPart()
    if (settings.isEmpty()) return path
    val builder = URLBuilder(path)
    settings.toSortedMap().forEach { (key, value) -> builder.parameters.append(key, value) }
    return builder.buildString()
}

/**
 * The addon's own word for a refusal, as ` (Unavailable)`, or "" when it did not give one.
 *
 * Bounded and single-line on purpose: the text is written by a stranger and lands in a row's check
 * line and in a fetch-failure sentence, so it is read as a short parenthetical and nothing more.
 * Only a JSON `error` string is taken - an HTML error page (a Cloudflare block, a proxy's 502) has
 * no word to quote and is left as the status code it answered with.
 */
internal fun addonRefusalReason(body: String): String {
    val text = body.trim()
    if (!text.startsWith("{")) return ""
    val error =
        runCatching { Json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull }
            .getOrNull()
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.take(60)
            ?.takeIf { it.isNotEmpty() }
            ?: return ""
    return " ($error)"
}

/** A server addon's search answer, read leniently: it is written by someone else. */
internal fun parseAddonTracks(body: String): List<SpotiFLACModuleHost.ModuleTrack> {
    val root =
        runCatching { Json.parseToJsonElement(body.trim()).jsonObject }.getOrNull()
            ?: return emptyList()
    val tracks = root["tracks"] as? JsonArray ?: return emptyList()
    return tracks.mapNotNull { element ->
        val track = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
        val id = track.string("id") ?: return@mapNotNull null
        val title = track.string("title") ?: return@mapNotNull null
        SpotiFLACModuleHost.ModuleTrack(
            id = id,
            title = title,
            artist = track.string("artist").orEmpty(),
            album = track.string("album"),
            // The addon protocol reports seconds, and a queue item is compared in milliseconds.
            // Read as a double: a personal addon answers `157.0`, which `longOrNull` refuses - and a
            // dropped duration is missing evidence in the matcher, not a harmless zero.
            durationMs = ((track["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000.0).toLong(),
            quality = track.string("audioQuality") ?: track.string("quality"),
            format = track.string("format"),
        )
    }
}

/**
 * Whether a row's declared format is a file Hush can play.
 *
 * A manifest is not audio. Measured against `gglos`: its Tidal-backed rows answer `format: dash`,
 * and `/stream/<id>` for one of them returns a 1,894-byte `.mpd` whose
 * `mediaPresentationDuration` is `PT29.994S` - a 30-second preview playlist, not the track Hush
 * asked for. Hush downloaded that XML as if it were the song, played it as a FLAC, and reported
 * ExoPlayer's `3003` (network timeout) after the file was discarded as `NOT_AUDIO`. A row that
 * advertises one of these is a row this source cannot serve as a file, and saying so before the
 * download is what turns a broken track into a source that simply did not answer.
 */
internal fun addonFormatIsPlayable(format: String?): Boolean {
    val value = format?.trim()?.lowercase(Locale.ROOT).orEmpty()
    if (value.isEmpty()) return true
    return when (value) {
        "dash", "mpd", "hls", "m3u8", "m3u" -> false
        else -> true
    }
}

/** Whether a stream answer points at a manifest rather than at a file. */
internal fun addonUrlIsManifest(url: String): Boolean {
    val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
    return path.endsWith(".mpd") || path.endsWith(".m3u8") || path.endsWith(".m3u")
}

/** A server addon's stream answer, read leniently; null when it has no file for the track. */
internal fun parseAddonStream(body: String): SpotiFLACModuleHost.ModuleStream? {
    val root =
        runCatching { Json.parseToJsonElement(body.trim()).jsonObject }.getOrNull()
            ?: return null
    val url = root.string("url")?.takeIf { it.startsWith("https://") } ?: return null
    // A manifest is a description of audio, not audio; see [addonUrlIsManifest]. Reported as
    // "this addon has no file for this track", which is the truth, instead of downloading XML and
    // letting the player fail on it as a broken FLAC.
    if (addonUrlIsManifest(url)) return null
    val format = root.string("format") ?: root.string("container") ?: root.string("codec")
    return SpotiFLACModuleHost.ModuleStream(
        url = url,
        quality = root.string("quality") ?: root.string("audioQuality"),
        mimeType = format?.let(::mimeTypeFor),
    )
}

private fun JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun mimeTypeFor(format: String): String? =
    when (format.lowercase(Locale.ROOT)) {
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "m4a", "aac", "alac" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        // `dash` and anything unknown: let the player read the container itself rather than claim
        // a type that would make it refuse a file the addon can actually serve.
        else -> null
    }
