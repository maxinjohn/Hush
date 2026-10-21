/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import java.util.Locale

/**
 * What a source is called where a *name* is shown rather than a list.
 *
 * Every surface outside Audio Sources used to name a source by its id, and the ids are registry
 * identifiers rather than names: the player read `SpotiFLAC • ytmusic-spotiflac • cached`, and a
 * fetch in flight read `Fetching from bitchord-streaming-modules:bitchord-tidal-flac`. Neither says
 * which source the audio came from - one is an abbreviated id and the other is a namespaced one, so
 * the row a listener reads to answer "where is this coming from" answers with the vocabulary of the
 * registries instead.
 *
 * Two things are carried out of the id, and both are facts the list already holds:
 *
 * - **the source's own name**, as the registry that published it declares it (`Tidal FLAC
 *   (tracks.monochrome.st)`, `Qobuz`, `Deezer`);
 * - **the repository it came from**, because a name alone is ambiguous the moment two indexes
 *   publish sources for the same service, and because "which repository is this" is the question a
 *   user asks when one source works and its neighbour does not. The bundled SpotiFLAC registries are
 *   the one case where it is left off: the player already says `SpotiFLAC`, and the sentence would
 *   then read `SpotiFLAC · Deezer · spotiflacapp/spotiflac-extension`, which is the same word twice.
 *
 * Deliberately pure and free of Android and of the manager: this is the wording every label in the
 * app shares, and a label assembled in three places is three answers about one track.
 */
object SpotiFLACSourceName {

    /** What a source with nothing readable about it is called - never a blank slot in a label. */
    const val UNKNOWN = "unknown source"

    /**
     * The name to show for a source.
     *
     * @param sourceId the registry or extension id, shown only when nothing better is known.
     * @param name the source's own display name, when the list holds it.
     * @param repositoryId the repository the source came from, or null/blank for a built-in source.
     * @param repositories the live repository rows, so a repository the user nicknamed is called
     *   what they called it.
     */
    fun label(
        sourceId: String,
        name: String?,
        repositoryId: String?,
        repositories: List<RepositoryRow> = emptyList(),
    ): String {
        val base = name(sourceId, name)
        val repository = repositoryTag(repositoryId, repositories) ?: return base
        // Re-stating something the name already says is how a label came to read `gglos · gglos`:
        // the addon calls itself after its own host, and the host is also what the repository is
        // called. There is nothing to disambiguate there, so the tag is dropped.
        if (namesRepository(base, repository)) return base
        return "$base · $repository"
    }

    /**
     * The name on its own, for a surface that draws the repository on a line of its own.
     *
     * One function rather than two readings of the same facts: the Audio Sources row shows this and
     * the player shows [label], which is this plus the repository - so a source cannot be `Tidal ·
     * gglos` in the list and something else on the codec bar. Before this, the row printed the
     * registry's `display_name` and the player a translated one, which is how `gglos` came to be
     * read as a provider in one place and dropped for the host it is in another.
     *
     * The two rules are the addon's service (see [ADDON_SERVICES]) and an id used as a name; a name a
     * registry actually wrote is returned untouched, since it says more than either can.
     */
    fun name(
        sourceId: String,
        name: String?,
    ): String {
        val published = name?.trim()?.takeIf { it.isNotBlank() }
        // A known server addon is named by the service it streams from, with the name it published
        // kept beside it as the provenance - `gglos` alone is a source no listener can place among
        // the providers listed beside it. Its repository tag is then dropped by [namesRepository],
        // because an addon named after its own host names that host in its name already.
        //
        // The exception is the addon that publishes the service's own name: `Qobuz` is what the
        // listener must read, and it is also what the bundled Qobuz extension reads, so the name
        // alone put two different sources on screen as one. Nothing in the addon's own words can
        // separate them, so what is left - the kind of pipe it is - says it instead.
        serviceForAddon(sourceId)?.let { service ->
            return if (published == null || published.equals(service, ignoreCase = true)) {
                "$service · $ADDON_QUALIFIER"
            } else {
                "$service · $published"
            }
        }
        return when {
            published == null -> brandFor(sourceId) ?: fallback(sourceId)
            // A registry that publishes its own id as the name (`ytmusic-spotiflac`, `tidal-web`)
            // has named nothing a listener can read, so the service the id belongs to answers
            // instead - the same reading [SpotiFLACSourceMark] already takes from the id.
            looksLikeId(published, sourceId) -> brandFor(sourceId) ?: published
            else -> published
        }
    }

    /**
     * Whether [base] already says [repository].
     *
     * Two spellings of the same fact, and both are silent for the same reason - the tag would add
     * nothing to the line:
     *
     * - a module index names its own entries `[<index>] (service)`, brackets included;
     * - every word of the repository's name is already in the source's own name (`gglos` for an
     *   addon called `gglos`, `Tidal` for `Tidal FLAC`). Only whole words count, so a repository
     *   that adds a word of its own still distinguishes itself: `Qobuz` against `Qobuz Web` keeps
     *   its tag, which is exactly the case the tag exists for.
     *
     * Compared as words rather than as a substring because `Deezer` inside `Deezerized` is not the
     * same statement, and case-insensitively because the two names come from different files.
     */
    internal fun namesRepository(
        base: String,
        repository: String,
    ): Boolean {
        if (base.contains("[$repository]")) return true
        val repositoryWords = words(repository)
        if (repositoryWords.isEmpty()) return false
        val nameWords = words(base)
        return repositoryWords.all { it in nameWords }
    }

    /** The lower-case alphanumeric words in [text]; punctuation and separators are not words. */
    private fun words(text: String): Set<String> =
        text
            .lowercase()
            .split(NON_WORD)
            .filterTo(LinkedHashSet()) { it.isNotEmpty() }

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    /**
     * The repository tag for a source, or null when naming it would add nothing.
     *
     * Null in two cases, and they are different reasons for the same silence: a source published by
     * a repository Hush ships (the player already names SpotiFLAC), and a source no repository
     * declares at all - a built-in - where there is no repository to name.
     */
    fun repositoryTag(
        repositoryId: String?,
        repositories: List<RepositoryRow> = emptyList(),
    ): String? {
        val id = repositoryId?.trim().orEmpty()
        if (id.isEmpty()) return null
        if (ExtensionRepositories.isBundled(id)) return null
        return repositories
            .firstOrNull { it.url.equals(id, ignoreCase = true) }
            ?.label
            ?.takeIf { it.isNotBlank() }
            ?: ExtensionRepositories.label(id)
    }

    /**
     * The service a known server addon streams from, or null for one Hush has not measured.
     *
     * A server addon chooses its own name, and some name themselves after their host (`gglos`) or
     * their author's handle instead of the service behind them - so the one fact a listener wants
     * from that row, *which provider is this*, is the fact the addon leaves out. Where the app has
     * measured the upstream, the label says it.
     *
     * Keyed by the addon's own id rather than by a name, because a name is prose its author can
     * change at any time while the id is what every call is addressed to. The evidence behind the
     * entries here: `gglos`' rows are Tidal-backed, and its `/stream` answers carry Tidal's
     * `dash`/preview shape (see [ServerAddonHost] and [addonFormatIsPlayable]); `com.bitchord.qobuz`
     * is Qobuz's own catalogue, which it names itself and resolves to Qobuz ids and lossless files.
     */
    private val ADDON_SERVICES: Map<String, String> =
        mapOf(
            "gg.gglos.addon" to "Tidal",
            "com.bitchord.qobuz" to "Qobuz",
        )

    /**
     * What an addon is called when its name cannot separate it from a source beside it.
     *
     * The list's own word for this sort of source (`Streams audio · addon 1.0.0`, kind `Server
     * addon`), so the row that reads `Qobuz · addon` is telling the listener which of the two pipes
     * it is in the vocabulary the rest of the screen already uses.
     */
    private const val ADDON_QUALIFIER = "addon"

    /** The service a server addon's id is known to stream from; see [ADDON_SERVICES]. */
    internal fun serviceForAddon(sourceId: String): String? {
        val id = sourceId.trim().lowercase(Locale.US)
        // A server addon's registry id is `<host>:<addon id>`; the addon id is what the service
        // table is keyed by, and a bare id is asked as it is.
        val own = id.substringAfterLast(':')
        if (own.isEmpty()) return null
        return ADDON_SERVICES.entries
            .firstOrNull { (addon, _) -> own == addon || own.startsWith("$addon.") }
            ?.value
    }

    /**
     * The human name of the service an id belongs to, from the vendor token inside it.
     *
     * The same tokens [SpotiFLACSourceMark] matches icons by, and for the same reason: a registry id
     * is the vendor's own (`tidal-web`), while a module id is namespaced by the index that published
     * it (`bitchord-streaming-modules:bitchord-tidal-flac`). Read only where the published name is
     * missing or is an id itself - a registry that names its source `Tidal FLAC (tracks.monochrome.st)`
     * has said more than this table can, and its wording is kept.
     */
    private fun brandFor(sourceId: String): String? {
        val id = sourceId.trim().lowercase(Locale.US)
        BRANDS.entries.firstOrNull { (token, _) -> id.startsWith(token) }?.let { return it.value }
        val tokens = id.split(NON_ID_TOKEN).filter { it.isNotBlank() }
        return BRANDS.entries
            .firstOrNull { (token, _) -> tokens.any { part -> part == token || part.startsWith(token) } }
            ?.value
    }

    /**
     * Whether [name] says nothing more than the id it belongs to.
     *
     * Deliberately narrow, because the cost of guessing wrong is a service's own wording replaced by
     * Hush's: the name has to be a single token with no spaces *and* be that id (or the id's tail
     * after its namespace, or the head the rest of the id extends). `Tidal FLAC (tracks.monochrome.st)`
     * and `Qobuz` have spaces or are not the id's token, so they are left to the registry that wrote
     * them; `ytmusic-spotiflac` and `tidal-web` are the id itself and read as words beside it.
     */
    internal fun looksLikeId(
        name: String,
        sourceId: String,
    ): Boolean {
        if (name.any { it.isWhitespace() }) return false
        val id = sourceId.trim().lowercase(Locale.US)
        val published = name.lowercase(Locale.US)
        if (published == id || published == id.substringAfterLast(':')) return true
        // The head rule is for an id that *is* the name it was registered under (`qobuz-web`,
        // `ytmusic-spotiflac`). A namespaced id begins with its repository's or host's name, which
        // is not the source's name at all: the Tido addon is served from `tido.tido.tido.dpdns.org`
        // and calls itself `Tido`, and reading its id's head as evidence renamed the row `Tidal`
        // from the vendor token further along the id. What follows the namespace is asked about
        // above; the namespace itself never is.
        if (!id.contains(':') && id.startsWith(published)) return true
        return published.any { !it.isLetterOrDigit() } && published in id.split(NON_ID_TOKEN)
    }

    /** Vendor token to the name the service is known by, for a name the registry did not give. */
    private val BRANDS: Map<String, String> =
        linkedMapOf(
            "spotify" to "Spotify",
            "deezer" to "Deezer",
            "tidal" to "Tidal",
            "apple" to "Apple Music",
            "soundcloud" to "SoundCloud",
            "ytmusic" to "YouTube Music",
            "youtube" to "YouTube Music",
            "amazon" to "Amazon Music",
            "qobuz" to "Qobuz",
            "jiosaavn" to "JioSaavn",
            "bugs" to "Bugs",
            "melon" to "Melon",
            "genie" to "Genie Music",
            "pandora" to "Pandora",
        )

    /** Whatever separates the parts of a namespaced id: `:`, `-`, `_`, `.`, `/`. */
    private val NON_ID_TOKEN = Regex("[^a-z0-9]+")

    /**
     * The id, made readable when nothing else is known.
     *
     * A module id is namespaced by the repository that published it
     * (`bitchord-streaming-modules:bitchord-tidal-flac`), and the namespace is a second spelling of
     * the repository rather than part of the source's name - so it is dropped. What is left is an
     * id, not a sentence: where the id names a service it is called by that name ([brandFor]), and
     * otherwise it is shown as it is, because inventing a title out of dashes would be a name no
     * registry uses on the one row where the user is trying to match what a registry published.
     */
    private fun fallback(sourceId: String): String {
        val id = sourceId.trim()
        if (id.isEmpty()) return UNKNOWN
        val lastColon = id.lastIndexOf(':')
        return if (lastColon in 0 until id.lastIndex) id.substring(lastColon + 1) else id
    }
}
