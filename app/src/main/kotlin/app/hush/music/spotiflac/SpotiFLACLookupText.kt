/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

/**
 * The title and artist a SpotiFLAC provider is asked to match, cleaned of the noise a YouTube
 * video carries and never a channel name.
 *
 * Providers resolve a track from the text Hush hands them, and the text it used to hand them was
 * the video's own metadata. A YouTube song is routinely titled
 * `"Mizhiyil Mizhiyil | Maayabazar | Mammootty | Sheela Koul | Rahul Raj - HD Video Song | Sujatha Mohan"`
 * and credited to a *channel* (`"Malayalam Hits"`, `"Sony Music Malayalam"`, `"<Artist> - Topic"`).
 * Measured on device, that is what every provider did with it: no match, an empty provider track
 * ID, and `Invalid Tidal/Deezer track ID` - which reads as "SpotiFLAC is broken" for any track whose
 * video title is not already a clean song name, while the same pipeline resolves a clean title to a
 * 23 MB lossless file in seconds.
 *
 * So this is the one place that decides what a catalogue sees:
 *  - the song name is the *first* segment of a `|`-separated title, because in this catalogue style
 *    every later segment is cast, crew, film or format ("Video Song", "4K", the singer);
 *  - parenthesised/bracketed format tags are dropped, but only ones that are format tags, so a real
 *    variant ("Slowed Down Version", "Remix") survives and keeps matching the right recording;
 *  - a channel-shaped artist is refused outright rather than sent, because a channel name matches
 *    the wrong recordings more often than it matches the right one. An empty artist lets the
 *    provider search on the title alone, which is what its own search does with a missing artist.
 *
 * Deliberately pure, so the rules are testable without a runtime, a network or a device.
 */
object SpotiFLACLookupText {

    /**
     * Format noise that is never part of a song's identity, lowercased for matching.
     *
     * Ordered longest-first where one contains another, so `"hd video song"` is removed before
     * `"video song"` leaves a dangling `"hd"`.
     */
    private val FORMAT_NOISE = listOf(
        "full video song",
        "hd video song",
        "video with lyrics",
        "official music video",
        "official video song",
        "lyric video",
        "lyrics video",
        "music video",
        "video song",
        "official audio",
        "official video",
        "official lyric",
        "audio song",
        "full song",
        "with lyrics",
        "lyrical video",
        "visualizer",
        "making video",
        "title track video",
        "4k",
        "hd",
        "hq",
    )

    /** Standalone tokens that only ever describe the upload, not the song. */
    private val NOISE_TOKENS = setOf("video", "song", "audio", "lyrics", "lyrical", "official")

    /**
     * Words that mark a credited name as a channel or a label rather than a performer.
     *
     * Matched as whole words anywhere in the name, because the branding moves around:
     * `"Sony Music Malayalam"`, `"Saregama Music"`, `"Malayalam Hits"`, `"<Artist> - Topic"`,
     * `"Music Zone"`. A refusal is cheap and an empty artist is a working answer - the provider then
     * searches on the title alone - while a channel name sent as an artist costs a wrong match, so
     * the rules lean towards refusing. A performing credit (`"Ed Sheeran"`, `"Jakes Bejoy"`) shares
     * none of these words.
     */
    private val BRAND_WORDS = setOf(
        "music",
        "records",
        "recordings",
        "audios",
        "audio",
        "entertainment",
        "official",
        "topic",
        "hits",
        "songs",
        "label",
        "labels",
        "studios",
        "tv",
        "zone",
        "films",
        "movies",
    )

    /** Names that are a channel and nothing else, whatever surrounding whitespace they arrive with. */
    private val CHANNEL_EXACT = setOf(
        "music",
        "music zone",
        "t-series",
        "t series",
        "topic",
        "various artists",
        "vevo",
    )

    /**
     * The song name a catalogue should search for.
     *
     * Falls back to the trimmed input when cleaning would leave nothing, because sending an empty
     * title can only ever resolve to nothing.
     */
    fun title(raw: String): String {
        val original = raw.trim()
        if (original.isEmpty()) return original
        var text = original.substringBefore('|')
        // A trailing `" - <format>"` is on the first segment whenever the title has no pipes at all:
        // `"Song Name - HD Video Song"`.
        text = text.substringBefore(" - ").takeIf { it.isNotBlank() } ?: text
        text = dropFormatNoise(text)
        val cleaned = trimEdges(text)
        return cleaned.ifBlank { original }
    }

    /**
     * The artist a catalogue should search for, or `""` when the credited name is a channel.
     *
     * An empty answer is deliberate and is not a failure: the provider's own search then matches on
     * the title alone, which is strictly better than matching a song against a channel name.
     */
    fun artist(raw: String): String {
        val original = raw.trim()
        if (original.isEmpty()) return ""
        var text = original
        // A single video can credit several names; the first is the performing artist, the rest are
        // usually the label's own marketing list.
        if (text.contains(", ")) text = text.substringBefore(", ").trim()
        text = dropFormatNoise(text)
        text = trimEdges(text)
        val lower = text.lowercase()
        if (lower in CHANNEL_EXACT) return ""
        val words = lower.split(' ', '-', '_', '&', '/', '.').filter { it.isNotBlank() }
        if (words.any { it in BRAND_WORDS }) return ""
        // A name that is only a format token ("Official") is noise, not an artist.
        if (words.isNotEmpty() && words.all { it in NOISE_TOKENS }) return ""
        return text
    }

    /** Removes format tags, whole-word noise and empty bracket pairs left behind. */
    private fun dropFormatNoise(text: String): String {
        var working = text
        FORMAT_NOISE.forEach { noise -> working = working.replace(noise, " ", ignoreCase = true) }
        // Anything still bracketed is a format tag this list does not know; the ones worth keeping
        // are variants, and those are matched by the provider from the unbracketed title anyway.
        working = BRACKETED.replace(working) { match -> if (isVariant(match.value)) match.value else " " }
        working = working.replace(Regex("\\s{2,}"), " ")
        return working
    }

    /** A bracketed variant that changes which recording this is, so it must survive cleaning. */
    private fun isVariant(bracketed: String): Boolean {
        val body = bracketed.trim('(', ')', '[', ']').lowercase()
        return VARIANTS.any { body.contains(it) }
    }

    /** Trims separators a removed segment leaves behind, at both ends. */
    private fun trimEdges(text: String): String =
        text.trim().trim('-', '|', ':', '–', '—', ',', '.', '_', ' ').replace(Regex("\\s{2,}"), " ")

    private val BRACKETED = Regex("\\((?:[^()]{0,80})\\)|\\[(?:[^\\[\\]]{0,80})\\]")

    private val VARIANTS = listOf(
        "slowed",
        "reverb",
        "remix",
        "sped up",
        "acoustic",
        "live",
        "instrumental",
        "extended",
        "radio edit",
        "demo",
        "unplugged",
        "version",
        "mix",
    )
}
