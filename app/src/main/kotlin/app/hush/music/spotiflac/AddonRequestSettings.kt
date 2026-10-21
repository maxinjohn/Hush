/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * One setting a server addon declares in its own manifest.
 *
 * A BitChord/Eclipse addon describes the values a client may send it, and the client states them on
 * *every* request - search, stream and the detail endpoints - as query parameters keyed by [key]
 * verbatim (the Eclipse addon guide: "Eclipse appends the current values as query parameters, using
 * your key verbatim, on every request it makes to your addon"). Nothing is stored on the addon's
 * side, and the [default] is what a client that has no opinion yet sends.
 *
 * Hush read the `quality` default only to decorate a row's name ([ExtensionRepositories]) and sent
 * nothing at all, which is why the addon always answered with the tier its own manifest happened to
 * default to. Measured against `tido.tido.tido.dpdns.org`: the top-tier profile defaults to
 * `MAX` - "Max + Dolby Atmos" - so every request asked for an Atmos master, which
 * [SpotiFLACFileIntegrity] then had to reject as unplayable audio.
 */
@Serializable
data class AddonSetting(
    @SerialName("key") val key: String = "",
    /** `select`, `toggle`, `text` or `number`; only [options] is read here. */
    @SerialName("type") val type: String = "",
    /** The value the addon itself applies to a request that does not carry this key. */
    @SerialName("default") val default: String = "",
    /** The choices of a `select`, in the order the addon prefers them. */
    @SerialName("options") val options: List<AddonSettingOption> = emptyList(),
    /**
     * Whether the addon applies this setting itself, per network, rather than reading it back from
     * the request.
     *
     * Tido's manifest marks `quality`, `exactQuality`, `hifiApiUrl`, `videosEnabled` and
     * `videoQuality` this way, and that is why its host also publishes a `/generate` page: a profile
     * *is* the settings, stored on the addon's side and addressed by the URL. Sending them back as
     * query parameters is not what the client it was written for does - LastWave's `AddonClient`
     * sends only `q`, `quality` and `atmos` - and `hifiApiUrl=` in particular is a request to use an
     * instance at the empty string. See [AddonRequestSettings.forRequest].
     */
    @SerialName("perNetwork") val perNetwork: Boolean = false,
) {
    /** Whether this setting is a switch, which is the shape the Atmos preference arrives in. */
    val isToggle: Boolean get() = type.equals("toggle", ignoreCase = true)
}

/** One choice of an [AddonSetting] of type `select`. */
@Serializable
data class AddonSettingOption(
    @SerialName("value") val value: String = "",
    @SerialName("label") val label: String = "",
)

/**
 * What a request to a server addon carries, from what the addon declares and what Hush wants.
 *
 * Two facts are Hush's rather than the addon's, and both used to be left to whatever the manifest
 * defaulted to:
 *
 *  - **the quality tier**, from Audio Sources' Audio Quality. The addon's own vocabulary is not the
 *    runtime's (`MAX`, `HIRES`, `LOSSLESS`, `HIGH`, `LOW` on Tido; `hi_res_lossless`, `lossless`,
 *    `high`, `low` on gglos), so the tier is chosen from the options the addon itself declares,
 *    classified with the same rules the quality cascade already uses ([SpotiFLACQualityCascade]).
 *    An option the addon spells exactly as the requested tier wins outright; otherwise the nearest
 *    rung at or below the request is chosen, which is the addon's own fallback direction ("Off:
 *    falls back down the tiers when a track has no hi-res copy").
 *  - **the Atmos preference**, which is always *no*. A Dolby Atmos stream is an AC-4 file, and it
 *    is silent on a device with no AC-4 output path - the same finding that made
 *    [SpotiFLACQualityCascade] treat a spatial answer as "audio this app cannot play" and
 *    [SpotiFLACFileIntegrity] name the codec before the bytes are trusted. So a spatial option is
 *    never chosen, and an addon that declares an Atmos switch is told `false` rather than left to
 *    its own default.
 */
object AddonRequestSettings {
    /** The key every addon here uses for its tier; compared case-insensitively. */
    const val QUALITY_KEY = "quality"

    /** The key LastWave's client always sends beside the tier, so it is sent here too. */
    private const val ATMOS_KEY = "atmos"

    /**
     * Whether the addon stores its settings itself, which is when they must not be sent.
     *
     * Measured on Tido: the same addon is published at several directories, each one a settings
     * profile, and `/generate` hands out a new one. A client that states those settings per request
     * is describing a different protocol from the one that addon implements.
     */
    fun keepsSettingsOnServer(settings: List<AddonSetting>): Boolean =
        settings.any { setting -> setting.perNetwork }

    /**
     * The spellings an addon may give an Atmos switch.
     *
     * Tido has none - it spells the preference as the `MAX` tier, which is why avoiding the spatial
     * option is the half that matters there. The names are still sent when declared, because an
     * addon that offers the switch separately would otherwise apply its own default.
     */
    private val ATMOS_KEYS = setOf("atmos", "atmosenabled", "dolbyatmos")

    /**
     * The query parameters for one request.
     *
     * A *profile* addon - one whose manifest marks its settings `perNetwork` - is asked exactly what
     * its own client asks, because it already holds them for that URL. Every other addon is stated
     * its declared settings at their own defaults, then the two Hush has an opinion about are
     * replaced. Text settings whose default is empty are omitted rather than sent blank: the addon
     * applies its default to a missing key, and an empty `hifiApiUrl=` is the same fact with a
     * longer URL.
     */
    fun forRequest(
        settings: List<AddonSetting>,
        quality: String,
    ): Map<String, String> {
        if (settings.isEmpty()) return emptyMap()
        // The addon keeps these itself, so the request says only what its own client says.
        if (keepsSettingsOnServer(settings)) return lastWaveParameters(settings, quality)
        val params = LinkedHashMap<String, String>()
        for (setting in settings) {
            val key = setting.key.trim()
            if (key.isEmpty()) continue
            val value = setting.default.trim()
            if (value.isNotEmpty()) params[key] = value
        }
        val atmosKey =
            settings
                .firstOrNull { it.key.trim().lowercase(Locale.US) in ATMOS_KEYS }
                ?.key
                ?.trim()
        if (atmosKey != null) params[atmosKey] = "false"
        // `quality` is a tier of this addon's, so it is only overridden where the addon publishes a
        // list to choose from. A key with no options is a fixed value, not a preference.
        val qualitySetting =
            settings.firstOrNull { setting ->
                setting.key.trim().equals(QUALITY_KEY, ignoreCase = true) && setting.options.isNotEmpty()
            }
        if (qualitySetting != null) {
            val chosen =
                optionFor(quality, qualitySetting.options)
                    ?: atmosDefaultFallback(qualitySetting)
            if (chosen != null) params[qualitySetting.key.trim()] = chosen.value
        }
        return params
    }

    /**
     * What LastWave's own client sends a server-generated profile: a tier, and no Atmos.
     *
     * Its `AddonClient.search` builds `<base>/search?q=..&quality=..&atmos=..` and nothing else - no
     * `limit`, and none of the profile's settings, which the addon already holds for that URL. The
     * tier is in its own vocabulary ([LastWaveAddon.qualityToken]) rather than the manifest's
     * (`HIRES`), because that is the spelling its client has always sent.
     */
    private fun lastWaveParameters(
        settings: List<AddonSetting>,
        quality: String,
    ): Map<String, String> {
        val qualitySetting =
            settings.firstOrNull { setting ->
                setting.key.trim().equals(QUALITY_KEY, ignoreCase = true) &&
                    setting.options.isNotEmpty()
            }
        val chosen =
            qualitySetting?.let { setting ->
                optionFor(quality, setting.options) ?: atmosDefaultFallback(setting)
            }
        return linkedMapOf(
            QUALITY_KEY to LastWaveAddon.qualityToken(chosen?.value ?: quality),
            ATMOS_KEY to "none",
        )
    }

    /**
     * A tier to send when the request could not be mapped and the declared default is Atmos.
     *
     * The map is empty only for a tier Hush itself does not use, so this is a guard rather than a
     * path: leaving the default in place would ask a service for the one kind of audio the app
     * cannot play, and any other declared option is a better answer than that.
     */
    private fun atmosDefaultFallback(setting: AddonSetting): AddonSettingOption? {
        val default =
            setting.options.firstOrNull { option ->
                option.value.trim().equals(setting.default.trim(), ignoreCase = true)
            } ?: return null
        if (kindOf(default) != "spatial") return null
        return setting.options.firstOrNull { option -> kindOf(option) != "spatial" }
    }

    /**
     * Which of an addon's declared tiers answers [quality].
     *
     * The rules, in order:
     *
     *  - an option the addon spells exactly as the request (`HI_RES_LOSSLESS` against
     *    `hi_res_lossless`) is that tier and is taken as it is - the addon is speaking the runtime's
     *    own vocabulary and second-guessing it could only pick a different rung;
     *  - otherwise the option is classified by [SpotiFLACQualityCascade.kindOf] and the request is
     *    matched by kind: a lossless request takes a lossless option, a lossy one takes a lossy
     *    option, and a spatial option is never taken at all;
     *  - between the options of that kind, the highest rung at or below the request wins, falling
     *    back to the lowest rung the addon has - the addon's own "falls back down the tiers".
     *
     * Null means "leave the addon's default alone": an unrecognised request is not a reason to
     * invent a token no manifest declares.
     */
    internal fun optionFor(
        quality: String,
        options: List<AddonSettingOption>,
    ): AddonSettingOption? {
        if (options.isEmpty()) return null
        val requested = quality.trim().uppercase(Locale.US)
        if (requested.isEmpty()) return null
        val classified = options.map { option -> option to kindOf(option) }
        // Spatial is filtered everywhere below, so the Atmos tier cannot be reached even by an
        // exact spelling of its own value.
        val usable = classified.filterNot { (_, kind) -> kind == "spatial" }
        if (usable.isEmpty()) return null
        usable.firstOrNull { (option, _) -> option.value.trim().equals(requested, ignoreCase = true) }
            ?.let { return it.first }
        val lossless = usable.filter { (_, kind) -> kind == "lossless" }
        val lossy = usable.filter { (_, kind) -> kind == "lossy" }
        val target = Tier.of(requested) ?: return null
        return when (target.kind) {
            "lossless" ->
                lossless
                    .let { tiers ->
                        tiers.firstOrNull { (option, _) -> isHiRes(option) == target.hiRes } ?: tiers.firstOrNull()
                    }?.first
                    ?: lossy.firstOrNull()?.first
            "lossy" -> lossy.let { tiers -> rungAtOrBelow(tiers, target.bitrate) } ?: lossless.firstOrNull()?.first
            else -> null
        }
    }

    /**
     * The rung a request names: a kind and, for lossy, the bitrate it expects.
     *
     * `BEST` is the app's "Best Available", which asks for the highest lossless tier the source has
     * rather than for a rate the listener never chose.
     */
    private data class Tier(
        val kind: String,
        val hiRes: Boolean = false,
        val bitrate: Int = 0,
    ) {
        companion object {
            fun of(quality: String): Tier? =
                when (quality) {
                    "HI_RES_LOSSLESS", "HI_RES", "HIGH_RES", "HIRES", "FLAC_HIRES",
                    "BEST", "BESTAVAILABLE", "BEST_AVAILABLE", "HIGHEST", "AUTO",
                    -> Tier(kind = "lossless", hiRes = true)
                    "LOSSLESS", "FLAC", "CD" -> Tier(kind = "lossless")
                    "HIGH", "HIGH_320" -> Tier(kind = "lossy", bitrate = 320)
                    "MEDIUM", "MEDIUM_160", "NORMAL" -> Tier(kind = "lossy", bitrate = 160)
                    "LOW", "LOW_96" -> Tier(kind = "lossy", bitrate = 96)
                    else -> null
                }
        }
    }

    /** The highest declared rung at or below [bitrate], else the lowest one the addon has. */
    private fun rungAtOrBelow(
        options: List<Pair<AddonSettingOption, String>>,
        bitrate: Int,
    ): AddonSettingOption? {
        val scored =
            options.mapNotNull { (option, _) -> rateOf(option)?.let { rate -> option to rate } }
        if (scored.isEmpty()) return options.firstOrNull()?.first
        return (scored.filter { (_, rate) -> rate <= bitrate }.maxByOrNull { (_, rate) -> rate }
            ?: scored.minByOrNull { (_, rate) -> rate })
            ?.first
    }

    /**
     * The rate an option names, in kbps.
     *
     * Two spellings, because the manifests use both: `High (AAC 320 kbps)` names the rate, while
     * gglos' bare `high`/`low` values name the rung. The rate is read first - `Low (AAC 96 kbps)`
     * contains the word "low", and the number is the narrower answer.
     */
    private fun rateOf(option: AddonSettingOption): Int? {
        val text = (option.value + " " + option.label).lowercase(Locale.US)
        BITRATE.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return when {
            MEDIUM_WORD.containsMatchIn(text) -> 160
            LOW_WORD.containsMatchIn(text) -> 96
            HIGH_WORD.containsMatchIn(text) -> 320
            else -> null
        }
    }

    /**
     * What kind of audio an option is, by the cascade's own reading of ids and labels.
     *
     * The same classification the runtime uses, so a tier Hush calls spatial when it decides the
     * answer is unplayable is a tier this never asks for - one vocabulary, one answer.
     */
    private fun kindOf(option: AddonSettingOption): String =
        SpotiFLACQualityCascade.kindOf(
            SpotiFLACQualityCascade.QualityOption(id = option.value, label = option.label),
            downloadFallbackTier = null,
        )

    /** Whether an option is the hi-res rung rather than the CD-quality one. */
    private fun isHiRes(option: AddonSettingOption): Boolean {
        val text = (option.value + " " + option.label).lowercase(Locale.US)
        return HI_RES_WORDS.any { text.contains(it) }
    }

    private val BITRATE = Regex("(\\d{2,3})\\s*kbps")
    private val MEDIUM_WORD = Regex("\\b(medium|normal)\\b")
    private val LOW_WORD = Regex("\\blow\\b")
    private val HIGH_WORD = Regex("\\bhigh\\b")

    /**
     * What a hi-res lossless tier calls itself across the manifests Hush reads.
     *
     * Deliberately about the *tier* and not about lossless-ness: `16-bit` and `44.1` are the CD
     * rung, and treating them as hi-res would make "Lossless FLAC" ask for a 24-bit master on a
     * source that offers both.
     */
    private val HI_RES_WORDS =
        listOf("hi-res", "hi res", "hires", "hi_res", "24-bit", "24 bit", "24bit", "96 khz", "192 khz", "96khz", "192khz")
}
