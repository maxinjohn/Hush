/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

/**
 * What kind of document a repository URL serves.
 *
 * Hush reads repositories, and a repository is not one format. Its own two registries publish a
 * `extensions` array of SpotiFLAC packages, each with a digest and a package URL. A BitChord-style
 * repository publishes a module *index* instead: top-level `category:*` keys, each listing
 * JavaScript modules with a relative `download` path and a `type` of `MODULE`. Both are addresses a
 * user can paste and both list playable sources, so the format has to be recognised rather than
 * assumed - a module index read as a registry fails on its first field, and the failure mode is a
 * repository that looks dead when it in fact answered.
 */
enum class RepositoryKind(
    val id: String,
    val label: String,
) {
    /** A SpotiFLAC registry: `extensions`, each with a package URL and a digest. */
    SPOTIFLAC_REGISTRY("registry", "SpotiFLAC registry"),

    /** A module index: `category:*` arrays of JavaScript modules, as BitChord publishes. */
    MODULE_INDEX("module_index", "Module index"),

    /**
     * A server addon: one manifest naming an HTTP service that answers `search` and `stream`.
     *
     * The shape is BitChord's addon manifest (`id`, `version`, `resources`, optional `settings`)
     * without a category list, and it is what a host like `gglos.ggottes.deno.net` publishes. The
     * source is the service, not a script: nothing is downloaded or sandboxed, the addon is asked
     * about a track and answers with a URL.
     */
    SERVER_ADDON("server_addon", "Server addon"),
    ;

    companion object {
        fun fromId(id: String?): RepositoryKind? =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
    }
}

/**
 * What a repository answered with, once it has been read.
 *
 * [name] is the registry's own name for itself (`BitChord Streaming Modules`, `SpotiFLAC
 * Extensions`), which is a better label than a host when it has one and is absent often enough that
 * a row must work without it.
 */
data class RepositoryDocument(
    val kind: RepositoryKind,
    val name: String?,
    val sources: List<ExtensionSource>,
)

/**
 * The repositories Hush reads its sources from, and the rules for adding one.
 *
 * Two of them are shipped, and until now that was the whole list: the URLs lived as a private
 * constant in [ExtensionRepositoryManager], which made a registry a thing only Hush could name.
 * The sources themselves are already pluggable - a package can add one, the user orders and
 * enables them, and resolution walks that order - so the missing half was the *list*: a
 * repository that publishes sources Hush does not ship had no way in short of a new build.
 *
 * Kept as its own file, and free of Android and of any I/O, because everything worth getting
 * right here is a decision rather than a request: which URLs are accepted, what a row calls a
 * repository, and what a user-added repository is allowed to change about a source Hush already
 * knows. `ExtensionRepositoriesTest` pins all three; the fetching and the persistence stay in
 * [ExtensionRepositoryManager].
 */
object ExtensionRepositories {

    /** The repositories Hush ships with, in fetch order. */
    val Bundled =
        listOf(
            "https://raw.githubusercontent.com/spotiflacapp/spotiflac-extension/main/registry.json",
            "https://raw.githubusercontent.com/zarzet/spotiflac-extension/main/registry.json",
        )

    /**
     * Repositories Hush ships as a starting point, and lets the user take back out.
     *
     * The difference from [Bundled] is ownership, and it shows up in the row: a bundled registry
     * addresses source ids Hush's own runtime and signed-session contracts are written against, so
     * it can be switched off but not deleted - deleting it would leave a build whose sources have
     * no registry. A default repository introduces sources Hush does not ship and cannot vouch for,
     * so it is a row like any other: it can be switched off, renamed and removed, and an install
     * that removes it never sees the sources again.
     *
     * The module index is shipped rather than typed in because it is the one non-SpotiFLAC
     * repository whose format Hush understands, and the point of shipping it is that a user can see
     * what the other format looks like without hunting for a URL - and switch it off in one tap if
     * they do not want it.
     *
     * All of them are shipped, and none of them is typed in on a fresh install: an address a user
     * has to paste before the list works is an address most installs never have. The two
     * third-party registries below are here for the same reason as the module index - they publish
     * sources this build can already play (a Spotify playlist addon and the two Tido profiles), and
     * what they add is visible and reversible from the repository row. Shipping one is not a
     * promise about what it will publish next: every row can be switched off, renamed and removed,
     * and a source from a repository that has been removed never comes back on its own.
     *
     * Two server addons used to be here and are gone - `qobuz-addon.vercel.app` answers **402** and
     * `gglos.ggottes.deno.net` answers **404** as of 2026-10-20. A shipped row is a promise that the
     * address answers, and a row that cannot answer is worse than an absent one: it holds a place in
     * the list, names a source that will never play, and looks like Hush is broken. Their addon
     * *format* is untouched - a BitChord-style manifest is still read as a source by [read], every
     * request it needs is still served by `ServerAddonHost`, and the user can add any such addon
     * with one paste (including its bare host root, which [addCandidates] resolves to its
     * `manifest.json`). What is unchanged is the rule that decided to ship them: an address is
     * shipped only while it answers.
     *
     * Their order is the order a fresh install lists their sources in, so the two Tido profiles sit
     * next to each other, above the metadata-only rows at the bottom of the list.
     */
    val DefaultRemovable =
        listOf(
            "https://raw.githubusercontent.com/OreoMuncher45/bitchord-streaming-modules/main/manifest.json",
            "https://raw.githubusercontent.com/AlexRabbit/SpoitiLists/main/registry.json",
            TidoUrl,
            TidoLosslessUrl,
        )

    /**
     * The Tido server addon, at its top-tier profile.
     *
     * One addon published at two addresses, and the address is the profile: both manifests declare
     * the same `id` and name, and differ in the `quality` their own settings default to. This one
     * defaults to Max + Atmos ([TidoLosslessUrl] to lossless), which is why it is the row that reads
     * plain `Tido`; see [declaredQualityNote] for the other.
     */
    const val TidoUrl: String = "https://tido.tido.tido.dpdns.org/4bd14d1e2fa848febf1e9c03cdc237c0/manifest.json"

    /** The same Tido addon, published at the profile that defaults to lossless 16-bit / 44.1 kHz. */
    const val TidoLosslessUrl: String =
        "https://tido.tido.tido.dpdns.org/a48bf403dcfc40ae94f668c562d08201/manifest.json"

    /**
     * Server addons Hush used to ship, which have stopped answering.
     *
     * They are named here once, for one job: an install that has one stored must lose it. Removing a
     * shipped address from [DefaultRemovable] is not enough for that, because a build that shipped
     * it (or a user who added it while it worked) has it written into the stored list as a
     * repository of their own, and a stored row outlives the build that shipped it. Measured on the
     * reporting device after the two were removed from [DefaultRemovable]: they were still rows,
     * reported `origin=added kind=unknown declared=unread`, and the list held six repositories of
     * which two could never answer.
     *
     * Measured 2026-10-20: `qobuz-addon.vercel.app` answers **402** (the deployment's billing, not a
     * missing file) and `gglos.ggottes.deno.net` answers **404**. Neither is worth a row that is
     * drawn, switched on and permanently unable to play.
     *
     * What this list does **not** do is remove anything a user adds later, including the very same
     * kind of BitChord-style addon: [addCandidates] resolves a bare addon address to the
     * `manifest.json` inside it, [read] turns that manifest into a playable source, and nothing here
     * is consulted when an address is added.
     */
    val Retired: List<String> =
        listOf(
            "https://qobuz-addon.vercel.app/manifest.json",
            "https://gglos.ggottes.deno.net/manifest.json",
        )

    /** True when [url] is an address Hush shipped once and has retired. */
    fun isRetired(url: String): Boolean =
        Retired.any { retired ->
            retired.equals(url, ignoreCase = true) || sameRepositoryDocument(retired, url)
        }

    /**
     * Where a source Hush ships belongs in the list once the user has arranged theirs.
     *
     * The list is the resolve order, so a source that arrives last is asked last. On an install the
     * user has never touched that is already right (the order the repositories are read in decides
     * it), but every install that has ever reordered a row writes its order down, and a source no
     * stored order names is appended below all of them - so a source Hush starts shipping later
     * lands under Pandora until the user finds it and drags it up, which is not "shipped working".
     *
     * Read as *anchors*, not as a whole list: an id the user has not placed goes in front of the
     * first id they have placed that follows it here. So the Tido profile that defaults to Atmos is
     * placed above the lossless one - and, since jiosaavn is above both in the same order, never
     * above jiosaavn - while every source they have arranged stays exactly where they put it. A source this list does not name, or one named here with nothing of the user's to sit
     * in front of, keeps the order the sync published it in: an unarranged list is not reshuffled
     * by this.
     */
    val DefaultSourceOrder: List<String> =
        listOf(
            "jiosaavn",
            // `<namespace>:<manifest id>`, where a server addon's namespace is its host *and* the
            // directory its manifest lives in - that directory is what makes two profiles of one
            // addon two sources (see [addonNamespaceFor]). Both Tido rows name the same manifest id
            // on purpose: they are the same addon. `ExtensionRepositoriesTest` holds these against
            // the URLs above.
            "tido-tido-tido-dpdns-org-4bd14d1e2fa848febf1e9c03cdc237c0:com.yourname.tidal.hifi",
            "tido-tido-tido-dpdns-org-a48bf403dcfc40ae94f668c562d08201:com.yourname.tidal.hifi",
        )

    /** Every repository an install starts with, bundled first. */
    val Defaults: List<String> = Bundled + DefaultRemovable

    /**
     * The source list in order: what the user arranged, with the shipped sources placed.
     *
     * The user's order is kept entire - this moves nothing they moved - and only ids they have
     * never placed are decided here, by [DefaultSourceOrder]. A source that is not available at all
     * (a repository switched off, a source a registry dropped) is dropped rather than kept as a
     * hole, which is what the two loops this replaces did.
     */
    fun orderWithDefaults(
        storedOrder: List<String>,
        available: List<String>,
    ): List<String> {
        val present = available.toSet()
        val result = storedOrder.filter { it in present }.toMutableList()
        val placed = result.toMutableSet()
        val newcomers = available.filterNot { it in placed }
        // In default-order sequence, so two anchored arrivals keep the sequence this list names.
        newcomers
            .filter { rankInDefaultOrder(it) != null }
            .sortedBy { rankInDefaultOrder(it) }
            .forEach { id ->
                val mine = rankInDefaultOrder(id)
                val anchor =
                    result.indexOfFirst { other ->
                        val theirs = rankInDefaultOrder(other)
                        mine != null && theirs != null && theirs > mine
                    }
                if (anchor >= 0) {
                    result.add(anchor, id)
                    placed += id
                }
            }
        // Everything else - including a source named above with nothing of the user's to sit in
        // front of - keeps the order it was published in, after the list the user arranged.
        result.addAll(newcomers.filterNot { it in placed })
        return result
    }

    /** Where [id] sits in [DefaultSourceOrder], or null for a source that list does not name. */
    private fun rankInDefaultOrder(id: String): Int? =
        DefaultSourceOrder.indexOfFirst { it.equals(id, ignoreCase = true) }.takeIf { it >= 0 }

    /**
     * A user-supplied repository URL, or null when it is not one Hush will fetch.
     *
     * The rules are the ones a registry entry already has to satisfy for its own download URLs,
     * because a repository URL is the same kind of promise: https only, a real host, and no
     * credentials in the authority - `https://user:pass@host/registry.json` would hand the
     * registry a login Hush never agreed to make.
     *
     * What a person actually pastes is not always what a fetcher can use, and refusing those
     * pastes was the whole reason adding a repository looked broken: the link a browser and a
     * GitHub file page offer is `github.com/<owner>/<repo>/blob/<ref>/<file>`, which answers with
     * an HTML page, so Hush fetched the page and reported that it was not a repository. This is
     * the one place that turns such an address into the file it names; [addCandidates] keeps the
     * alternatives for a page that names a whole repository rather than a file.
     */
    fun canonical(rawUrl: String): String? = addCandidates(rawUrl).firstOrNull()

    /**
     * Every address Hush should try, in order, for what the user typed.
     *
     * More than one only where the address is ambiguous: a bare repository page names no file, so
     * the two names a repository publishes are probed at the default branch, and a GitHub blob link
     * whose branch contains a slash can be split in more than one way, so each reading is offered
     * and the first that answers wins. Empty means nothing fetchable can be made of the input.
     */
    fun addCandidates(rawUrl: String): List<String> {
        val normalized = normalizeAddress(rawUrl) ?: return emptyList()
        return fileCandidates(normalized) ?: addonRootCandidates(normalized) ?: listOf(normalized)
    }

    /**
     * A bare host, directory or token path as the addon file it may publish, or null when the
     * address already names a file.
     *
     * An addon is published as a `manifest.json` in a directory - at a host root, at `/s1`, at a
     * per-account `/a/<token>` - and the address its own README hands out is the directory, not the
     * file. Pasting that directory used to be fetched literally, answered the host's 404 or its
     * landing page, and was refused as "not a repository", which is a form of "the addon does not
     * work" that is entirely Hush's. The two names a repository can publish are offered in the
     * order that matters for an addon - `manifest.json` before `registry.json` - and the address as
     * typed stays last, so a directory that *is* the document keeps working.
     *
     * "Names a file" is a last path segment containing a dot: every document in either format ends
     * in `.json` or a package suffix, and no directory on an addon host has ever had one. A query
     * string is carried through, because `/a/<token>?profile=lossless` is one directory with two
     * answers.
     */
    private fun addonRootCandidates(normalized: String): List<String>? {
        val uri =
            runCatching { URI(normalized) }
                .getOrNull()
                ?: return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val path = uri.path.orEmpty().trimEnd('/')
        if (path.substringAfterLast('/').contains('.')) return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val base = "https://$host$port$path"
        return listOf("$base/manifest.json$query", "$base/registry.json$query", normalized)
    }

    /**
     * A pasted address, cleaned into the shape everything below expects.
     *
     * Copying a link is a lossy act, so the losses are absorbed here rather than reported: a
     * surrounding whitespace, quote or angle bracket from a share sheet, a markdown link copied
     * out of a README, a zero-width space, a `#readme` fragment, a missing scheme (a browser hides
     * it) and an `http://` the host now redirects to https anyway. Nothing here changes which file
     * is named - only how the same address is spelled.
     */
    private fun normalizeAddress(rawUrl: String): String? {
        var text =
            rawUrl.trim()
                .trim('\u200B', '\uFEFF', '\u00A0', '"', '\'')
                .trim()
        // A balanced wrapper from a share sheet or a chat message. Paired delimiters only: a path
        // that legitimately ends in `)` is a URL, not a wrapper, and stripping its last character
        // would break an address the user pasted correctly.
        if (text.length >= 2 && text.first() == '<' && text.last() == '>') text = text.substring(1, text.length - 1).trim()
        if (text.length >= 2 && text.first() == '(' && text.last() == ')') text = text.substring(1, text.length - 1).trim()
        MarkdownLink.find(text)?.let { link -> text = link.groupValues[1].trim() }
        if (text.isEmpty()) return null
        text = text.substringBefore('#').trim()
        if (text.isEmpty()) return null
        val withScheme = if (text.contains("://")) text else "https://$text"
        val uri =
            runCatching { URI(withScheme) }
                .getOrNull()
                ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        // http is read as https rather than refused: a host that still serves plain http will
        // redirect, and one that does not is one Hush must not talk to in the clear anyway.
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        if (!uri.userInfo.isNullOrBlank()) return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "https://$host$port${canonicalRefPath(host, uri.rawPath.orEmpty())}$query"
    }

    /**
     * A raw GitHub path with the one ref spelling that has two names written the same way.
     *
     * `refs/heads/main` and `main` are the same branch, and both are addresses GitHub hands out (its
     * copy-link menu writes the first, the Raw button the second). Storing both made one file two
     * rows, so the ref is normalised here - the only place an address is chosen.
     */
    private fun canonicalRefPath(
        host: String,
        path: String,
    ): String {
        if (!host.equals("raw.githubusercontent.com", ignoreCase = true)) return path
        val segments = path.split('/').filter { it.isNotBlank() }
        if (segments.size < 6 || segments[2] != "refs" || segments[3] != "heads") return path
        return "/" + (segments.take(2) + segments.drop(4)).joinToString("/")
    }

    /**
     * The file addresses for a forge *page*, or null when the address already names a file.
     *
     * Three hosts, because they are the three a repository is published on in practice, and each
     * spells the same two things differently: GitHub's `blob` page and `raw` page, GitLab's
     * `-/blob` page, and Gitea/Codeberg's `src/branch` page. A page that cannot be turned into one
     * file falls through to null, which leaves the address as typed - the fetch then reports what
     * the page actually was instead of the dialog refusing to accept it.
     */
    private fun fileCandidates(normalized: String): List<String>? {
        val uri =
            runCatching { URI(normalized) }
                .getOrNull()
                ?: return null
        val host = uri.host.orEmpty().lowercase()
        val segments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
        return when {
            host == "raw.githubusercontent.com" -> null
            host == "github.com" || host == "www.github.com" -> githubCandidates(segments)
            segments.size >= 6 && segments[2] == "-" && segments[3] == "blob" ->
                listOf(
                    "https://$host/${segments[0]}/${segments[1]}/-/raw/${segments[4]}/" +
                        segments.drop(5).joinToString("/"),
                )
            segments.size >= 6 && segments[2] == "src" &&
                (segments[3] == "branch" || segments[3] == "tag") ->
                listOf(
                    "https://$host/${segments[0]}/${segments[1]}/raw/${segments[3]}/${segments[4]}/" +
                        segments.drop(5).joinToString("/"),
                )
            else -> null
        }
    }

    /**
     * A GitHub page as the file it names, or the files a bare repository publishes.
     *
     * `blob/<ref>/<path>` does not have to be split into a branch and a path: the raw host takes
     * the rest of the path verbatim, so a branch that itself contains a slash resolves without
     * Hush having to know where the branch ends - which is knowledge it does not have. A repository
     * page names no file at all, so the two documents a repository publishes are probed at `HEAD`:
     * whatever the default branch is called, that is the branch a visitor is looking at, and storing
     * the resolved address is what makes the row readable afterwards.
     */
    private fun githubCandidates(segments: List<String>): List<String>? {
        if (segments.size < 2) return null
        val owner = segments[0]
        val repo = segments[1].removeSuffix(".git")
        val rawBase = "https://raw.githubusercontent.com/$owner/$repo"
        return when {
            segments.size >= 5 && (segments[2] == "blob" || segments[2] == "raw") ->
                // Everything after `blob/` is the branch and path exactly as GitHub wrote them,
                // and the raw host takes that remainder verbatim - so a branch that contains a
                // slash needs no special case, because nothing here has to find where it ends.
                listOf("$rawBase/" + segments.drop(3).joinToString("/"))
            segments.size == 2 -> probeRepository(owner, repo)
            segments.size == 4 && segments[2] == "tree" -> probeRepository(owner, repo, segments[3])
            else -> null
        }
    }

    /** The documents a repository page may be pointing at, at its default branch unless told. */
    private fun probeRepository(
        owner: String,
        repo: String,
        ref: String = "HEAD",
    ): List<String> =
        listOf("registry.json", "manifest.json").map { name ->
            "https://raw.githubusercontent.com/$owner/$repo/$ref/$name"
        }

    /** A markdown link as a README writes one: `[the registry](https://…)`. */
    private val MarkdownLink = Regex("^\\[[^]]*]\\(\\s*(\\S+?)\\s*\\)$")


    /**
     * What a row calls a repository.
     *
     * A URL is the honest identifier and a terrible label, and the two bundled ones are both
     * `raw.githubusercontent.com` - so the host alone would draw three identical rows. The owner
     * and repository are the part that tells them apart, and for anything else the host is what
     * a person recognises.
     */
    fun label(url: String): String {
        val uri =
            runCatching { URI(url) }
                .getOrNull()
                ?: return url
        val host = uri.host.orEmpty()
        val segments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
        return when {
            host.equals("raw.githubusercontent.com", ignoreCase = true) && segments.size >= 2 ->
                "${segments[0]}/${segments[1]}"
            host.isBlank() -> url
            else -> host.removePrefix("www.")
        }
    }

    /**
     * The URLs to fetch: the bundled ones, then whatever the user added and Hush does not already
     * have.
     *
     * Bundled first is not cosmetic. [merge] enriches a bundled source with what a registry
     * publishes, and the first registry to answer for an id fills the gaps the next one would
     * otherwise fill - so a user-added repository must not be able to take that turn away from
     * the repositories Hush ships with. The dedup is what keeps a re-added bundled URL from
     * being fetched twice, which would otherwise double every entry's provenance.
     */
    fun effective(
        bundled: List<String> = Bundled,
        custom: List<String>,
    ): List<String> {
        val seen = LinkedHashSet<String>()
        bundled.forEach { seen += it }
        custom
            .mapNotNull { canonical(it) }
            .forEach { seen += it }
        return seen.toList()
    }

    /** True when [url] is one of the repositories Hush ships with. */
    fun isBundled(url: String): Boolean = Bundled.any { it.equals(url, ignoreCase = true) }

    /**
     * The address Hush already knows for the same document, or null when it knows none.
     *
     * A document is not its URL: `…/main/registry.json`, `…/refs/heads/main/registry.json` and
     * `…/HEAD/registry.json` are three spellings of one file, and every one of them is an address
     * GitHub's own pages hand out. Without this, pasting a repository page after adding its Raw
     * link added the same registry a second time - measured on the device, where one repository
     * ended up as four rows, two of them copies of Hush's own bundled registry. Resolving to the
     * known spelling is also what lets a repository page be recognised as *Hush's own* rather than
     * as something new.
     */
    fun knownAddress(url: String): String? =
        Defaults.firstOrNull { known -> known.equals(url, ignoreCase = true) || sameRepositoryDocument(known, url) }

    /**
     * Whether two raw addresses name the same repository document.
     *
     * Only for `raw.githubusercontent.com`, where the shape is known: `/<owner>/<repo>/<ref>/<path>`.
     * `HEAD` is a wildcard, because it is not a branch - it is "whatever the default branch is" -
     * and the same file at `HEAD` and at `main` is one file. `refs/heads/<branch>` is the same ref as
     * `<branch>`. Anything else is compared literally, because guessing at a host's path shape is
     * how two different documents end up sharing one row.
     */
    fun sameRepositoryDocument(
        first: String,
        second: String,
    ): Boolean {
        if (first.equals(second, ignoreCase = true)) return true
        val one = documentIdentity(first) ?: return false
        val two = documentIdentity(second) ?: return false
        // An empty ref is `HEAD`, which stands for any branch; two named refs have to agree.
        return one.owner == two.owner &&
            one.repo == two.repo &&
            one.path == two.path &&
            (one.ref.isEmpty() || two.ref.isEmpty() || one.ref == two.ref)
    }

    private data class DocumentIdentity(
        val owner: String,
        val repo: String,
        /** Empty for `HEAD`, which matches any branch. */
        val ref: String,
        val path: String,
    )

    private fun documentIdentity(url: String): DocumentIdentity? {
        val uri =
            runCatching { URI(url) }
                .getOrNull()
                ?: return null
        if (!uri.host.equals("raw.githubusercontent.com", ignoreCase = true)) return null
        val segments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
        if (segments.size < 4) return null
        val (ref, path) =
            if (segments[2] == "refs" && segments.size >= 6 &&
                (segments[3] == "heads" || segments[3] == "tags")
            ) {
                segments[4] to segments.drop(5).joinToString("/")
            } else {
                segments[2] to segments.drop(3).joinToString("/")
            }
        if (path.isBlank()) return null
        return DocumentIdentity(
            owner = segments[0].lowercase(),
            repo = segments[1].lowercase(),
            ref = if (ref == "HEAD") "" else ref,
            path = path,
        )
    }

    /** True when [url] is a repository that ships with Hush but the user may remove. */
    fun isDefaultRemovable(url: String): Boolean = DefaultRemovable.any { it.equals(url, ignoreCase = true) }

    /**
     * Which format a repository answered in, or null when it is none Hush can read.
     *
     * Read from the document rather than from its address, because the address carries no format at
     * all: both of Hush's own registries are `registry.json` on a GitHub raw host, and a module
     * index is a `manifest.json` on the same host. Detection is by shape - `extensions` is a
     * SpotiFLAC registry, any top-level `category:*` key makes it a module index - so a repository
     * that renames its file keeps working, and one that answers with HTML (a login page, a 404 body)
     * is read as neither rather than as an empty registry.
     */
    fun detect(body: String): RepositoryKind? {
        val root =
            runCatching { Json.parseToJsonElement(body.trim()).jsonObject }
                .getOrNull()
                ?: return null
        if (root["extensions"] is JsonArray) return RepositoryKind.SPOTIFLAC_REGISTRY
        if (root.keys.any { it.startsWith(CategoryKeyPrefix) }) return RepositoryKind.MODULE_INDEX
        // Last, because a module index carries these same fields and is a list of modules - it is
        // identified by its categories above. What is left is one addon: an id and the resources it
        // serves, which is exactly what `gglos`-style manifests publish.
        if (root["id"]?.jsonPrimitive?.contentOrNull.orEmpty().isBlank()) return null
        // A LastWave-style personal addon publishes the same manifest, and may omit `resources`
        // entirely - its own client treats an empty list as "serves everything" - while naming the
        // service's root. An id plus either of those is one addon, not a registry.
        val namesRoot =
            !root["root"]?.jsonPrimitive?.contentOrNull.orEmpty().isBlank()
        if (root["resources"] is JsonArray || namesRoot) return RepositoryKind.SERVER_ADDON
        return null
    }

    /**
     * Reads a repository document into the sources it declares, of whichever format it is.
     *
     * One entry point for both formats on purpose: the add flow, the sync and the row that reports
     * what a repository declares all have to agree about a document, and three readers would be
     * three answers. The kind is returned with the sources because the *repository* row shows it and
     * the source rows inherit it - a module and a package are both sources, and only the repository
     * knows which of the two it published.
     */
    fun read(
        url: String,
        body: String,
    ): RepositoryDocument? {
        val kind = detect(body) ?: return null
        val root =
            runCatching { Json.parseToJsonElement(body.trim()).jsonObject }
                .getOrNull()
                ?: return null
        val name = root["name"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
        val sources =
            when (kind) {
                RepositoryKind.SPOTIFLAC_REGISTRY -> registrySources(root)
                RepositoryKind.MODULE_INDEX -> moduleIndexSources(url, root)
                RepositoryKind.SERVER_ADDON -> serverAddonSources(url, root)
            }
        return RepositoryDocument(
            kind = kind,
            name = name,
            sources = sources.map { it.copy(repositoryId = url) },
        )
    }

    /**
     * A registry's entries, read one at a time.
     *
     * The whole document used to be decoded as one [ExtensionRegistry], which makes every entry
     * depend on every other: one entry with a field of the wrong type failed the decode, the
     * failure was swallowed, and a registry that plainly declares sources was reported as
     * declaring none - the worst answer available, because it is indistinguishable from an empty
     * file and gives the user nothing to act on. A third-party registry is written by someone else
     * and may carry a field Hush has never seen, so the unit of failure is the entry: a bad one is
     * skipped, and the rest still arrive.
     */
    private fun registrySources(root: JsonObject): List<ExtensionSource> {
        val entries = root["extensions"] as? JsonArray ?: return emptyList()
        return entries.mapNotNull { element ->
            runCatching { DocumentJson.decodeFromJsonElement<ExtensionSource>(element) }.getOrNull()
        }
    }

    /** Lenient, because a registry is written by someone else and adds fields between Hush releases. */
    private val DocumentJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The modules a module index declares, as sources.
     *
     * Field names are the ones the format actually uses, which are not a registry's: `download`
     * rather than `download_url`, no digest at all, and `type: "MODULE"` where a registry entry
     * would carry `types`. What a module can do arrives as the index's own `resources`
     * (`search`, `stream`), so that is what is carried across - a module that advertises `stream`
     * is a provider of audio in the only vocabulary the rest of the app has.
     *
     * The id is namespaced by the repository host, because module ids are chosen by whoever wrote
     * the module and are not unique across indexes: two repositories that both ship `archive-flac`
     * would otherwise be one row, and disabling one would silently disable the other.
     */
    fun moduleIndexSources(
        url: String,
        root: JsonObject,
    ): List<ExtensionSource> {
        val namespace = namespaceFor(url)
        val resources =
            runCatching { root["resources"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } }
                .getOrNull()
                .orEmpty()
        return root.entries
            .filter { (key, value) -> key.startsWith(CategoryKeyPrefix) && value is JsonArray }
            .flatMap { (_, value) -> value.jsonArray }
            .mapNotNull { element ->
                val module = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
                val rawId = module["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (rawId.isBlank()) return@mapNotNull null
                val moduleName = module["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val description = module["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val tags =
                    runCatching {
                        module["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    }.getOrNull().orEmpty()
                // The script the module is, resolved against the index that published it: a module
                // index names its entry points relative to itself (`modules/archive-flac.js`), and an
                // address relative to nothing is the one thing no runtime can fetch. Carried on the
                // source so the row can name the entry point - and so a build that ever gains a
                // module runtime has the address to run rather than having to re-derive it.
                val entryPoint =
                    resolveAgainst(url, module["download"]?.jsonPrimitive?.contentOrNull)
                ExtensionSource(
                    id = "$namespace:$rawId",
                    name = moduleName.ifBlank { rawId },
                    description = description,
                    version = module["version"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    author = module["author"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    category = "download",
                    tags = tags,
                    downloadUrl = entryPoint,
                    // The module's own answer about what it does, so `declaredRoles` can read it the
                    // way it reads a registry entry's `types`.
                    requiredRuntimeFeatures = resources,
                    runtime = ModuleRuntime,
                    // No digest, on purpose: a module index publishes none, and the row that reads
                    // `isRuntimeCompatible` would otherwise show a verified-package tick for a file
                    // nothing has checked. `isPlayable` is what the list and the ordering ask, and it
                    // answers false for a module - a user sees the source, with the reason it cannot
                    // play yet attached to it, rather than a source that looks installable and then
                    // fails on the first track.
                )
            }
    }

    /**
     * The one source a server addon manifest describes.
     *
     * A server addon is not a list: its manifest is the addon. What it serves arrives as
     * `resources` (`search`, `stream`), which is the same vocabulary a module index uses, so the
     * roles the rest of the app reads come from the same place. The base address is the manifest's
     * own directory - `https://host/s1/manifest.json` is served at `https://host/s1` - and the
     * source id is namespaced like a module's, because two addons may both call themselves
     * `gg.gglos.addon`.
     */
    fun serverAddonSources(
        url: String,
        root: JsonObject,
    ): List<ExtensionSource> {
        val rawId = root["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (rawId.isBlank()) return emptyList()
        val base = addonBaseUrlFor(url) ?: return emptyList()
        val resources =
            runCatching { root["resources"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } }
                .getOrNull()
                .orEmpty()
        val name = root["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val namespace = addonNamespaceFor(url)
        // A manifest in a directory is one profile of the addon, not the addon itself; see
        // [declaredQualityNote]. An addon at its host root is the addon.
        val profile = namespace != namespaceFor(url)
        // The settings travel with the source: every request has to state them back
        // ([AddonRequestSettings]), and this is the one place the manifest is read.
        val declaredSettings = addonSettings(root)
        return listOf(
            ExtensionSource(
                id = "$namespace:$rawId",
                name = name.ifBlank { rawId } + declaredQualityNote(declaredSettings, profile),
                description = root["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                version = root["version"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                author = root["author"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                // A service that streams is a download provider in the vocabulary every surface
                // below already speaks; one that only searches describes what it can do.
                category =
                    if (resources.any { it.equals("stream", ignoreCase = true) }) "download" else "integration",
                requiredRuntimeFeatures = resources,
                runtime = AddonServerRuntime,
                addonBaseUrl = base,
                addonSettings = declaredSettings,
            ),
        )
    }

    /**
     * The settings a server addon declares, read one at a time.
     *
     * Entry by entry for the same reason a registry is: a setting this build has never seen is not
     * a reason to lose the rest of the manifest, and an addon whose `settings` is not an array at
     * all still publishes a service worth talking to.
     */
    private fun addonSettings(root: JsonObject): List<AddonSetting> =
        runCatching { root["settings"]?.jsonArray }.getOrNull()
            ?.mapNotNull { element ->
                runCatching { DocumentJson.decodeFromJsonElement<AddonSetting>(element) }.getOrNull()
            }
            ?.filter { setting -> setting.key.isNotBlank() }
            .orEmpty()

    /**
     * The namespace a server addon's id is published under.
     *
     * A module index names its own entries, so its host is enough to keep one index's modules apart
     * from another's. A server addon does not: the same addon can be published at several addresses,
     * each one a settings profile - `tido.tido.tido.dpdns.org/<uuid>/manifest.json` answers the same
     * `id`, `name` and version twice, once defaulting to Atmos and once to lossless, and only the
     * directory in the address tells them apart. Keying both by host made the second profile
     * overwrite the first, so two sources the user had added were one row. The manifest's own
     * directory is part of the id for that reason; an addon at the host root - `gglos`, the Qobuz
     * addon - keeps exactly the id it has always had, because it has no directory to add.
     */
    fun addonNamespaceFor(manifestUrl: String): String {
        val base = namespaceFor(manifestUrl)
        val segments =
            runCatching { URI(manifestUrl).path }
                .getOrNull()
                .orEmpty()
                .split('/')
                .filter { it.isNotBlank() }
        // The last segment is the manifest file; everything before it is this instance.
        val directory = segments.dropLast(1)
        if (directory.isEmpty()) return base
        return directory.joinToString(separator = "-", prefix = "$base-") { slug(it) }
    }

    /**
     * How a *profile* of an addon says which tier it defaults to, or "" for the addon itself.
     *
     * A host can publish one addon at several addresses, one per settings profile: `/generate` on
     * `tido.tido.tido.dpdns.org` answers a fresh manifest URL every time, and the manifest it serves
     * is the same addon (`id`, `name`, version) with a different quality default. The name is then the
     * only thing a listener sees on two identical rows, so the profile says what separates it in the
     * addon's own vocabulary: `Tido` for the profile that defaults to its top tier, `Tido · Lossless`
     * for one that defaults to lossless.
     *
     * Only a profile - a manifest in a directory rather than at the host root - is described this
     * way. `gglos` and the Qobuz addon publish the same `quality` setting with the same lossless
     * default and are not profiles of anything, so nothing is appended to them; and a top-tier
     * default (Hi-Res, Max) says nothing, because it is what the addon would do anyway.
     */
    private fun declaredQualityNote(
        settings: List<AddonSetting>,
        profile: Boolean,
    ): String {
        if (!profile) return ""
        val quality =
            settings.firstOrNull { setting ->
                setting.key.equals("quality", ignoreCase = true)
            } ?: return ""
        // No `options` means no choice: the value is a floor, not a profile.
        if (quality.options.isEmpty()) return ""
        val default = quality.default.trim()
        val tier =
            when (default.uppercase()) {
                "LOSSLESS" -> "Lossless"
                "HIGH" -> "High"
                "LOW" -> "Low"
                else -> return ""
            }
        return " · $tier"
    }

    /**
     * What one repository's row is called, now that its document has been read.
     *
     * The document's own name is the label - `BitChord Streaming Modules`, `Tido`, `gglos` - and a
     * repository that names itself nothing falls back to the address, which still tells two apart.
     *
     * The exception is a *profile*, and it is the case two identical rows came from. A host can
     * publish one addon at several addresses, each a settings profile, and every manifest names
     * itself the same thing: `tido.tido.tido.dpdns.org` answers `Tido` for its top-tier profile and
     * `Tido` for its lossless one, so the repository list drew two rows with the same word on them
     * while the sources beneath them were told apart (`Tido`, `Tido · Lossless`). Reading the row
     * from the source it declares is what keeps the two sections saying the same thing: the row for
     * the lossless profile reads `Tido · Lossless`, and the row for the top-tier one keeps `Tido`
     * because its own profile note is empty.
     *
     * Decided per row rather than by comparing rows, so it holds before the rest of the list has
     * been read: a repository the user has just added is labelled the same way one read on the next
     * sync will be, and it does not matter in which order the two profiles answered, or whether the
     * other one answered at all. Only a manifest in a directory is a profile - an addon at its host
     * root (`gglos`, the Qobuz addon) is the addon itself and keeps its name however it defaults.
     */
    fun rowLabel(
        url: String,
        document: RepositoryDocument,
    ): String {
        val own = document.name?.trim()?.takeIf { it.isNotBlank() } ?: label(url)
        if (document.kind != RepositoryKind.SERVER_ADDON) return own
        // Host root: the addon itself, not one of its profiles.
        if (addonNamespaceFor(url) == namespaceFor(url)) return own
        val declared = document.sources.firstOrNull()?.name?.trim().orEmpty()
        return declared.takeIf { it.startsWith("$own · ") } ?: own
    }

    /** A path segment reduced to the characters a source id may contain. */
    private fun slug(segment: String): String =
        segment.lowercase().map { if (it.isLetterOrDigit() || it == '-') it else '-' }.joinToString("")

    /**
     * A manifest's own directory: `https://host/s1/manifest.json` becomes `https://host/s1`.
     *
     * Null when the address is not an https URL with a host, which is the same rule every other
     * address in a registry is held to.
     */
    fun addonBaseUrlFor(manifestUrl: String): String? {
        val uri =
            runCatching { URI(manifestUrl) }
                .getOrNull()
                ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        if (!uri.userInfo.isNullOrBlank()) return null
        val path = uri.path.orEmpty()
        val cut = path.lastIndexOf('/')
        val basePath = if (cut <= 0) "" else path.substring(0, cut)
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "https://$host$port$basePath"
    }

    /**
     * A module's script address, absolute.
     *
     * A module index is read from a URL and its entries point at files beside it, so `modules/x.js`
     * has to be resolved against the index's own address before it names anything. Null for a module
     * that declares no entry point at all, and for one that names somewhere Hush will not fetch -
     * `isSafeRegistryEntry` then refuses the source rather than storing a URL that could not be
     * reached, which is the same rule a registry entry's `download_url` is held to.
     */
    private fun resolveAgainst(
        indexUrl: String,
        path: String?,
    ): String? {
        val raw = path?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val resolved =
            runCatching { URI(indexUrl).resolve(raw) }
                .getOrNull()
                ?: return null
        if (!resolved.scheme.equals("https", ignoreCase = true)) return null
        if (resolved.host.isNullOrBlank()) return null
        return resolved.toString()
    }

    /**
     * A short, stable namespace for a repository's module ids.
     *
     * The owner and repository name when the host is GitHub's raw one - `bitchord-streaming-modules`
     * reads better in a log than the host - and the host otherwise, lowercased and stripped of
     * anything that is not a letter, a digit or a dash so the id stays inside the shape a source id
     * is allowed to have.
     */
    fun namespaceFor(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull()
        val host = uri?.host.orEmpty()
        val segments = uri?.path.orEmpty().split('/').filter { it.isNotBlank() }
        val raw =
            when {
                host.equals("raw.githubusercontent.com", ignoreCase = true) && segments.size >= 2 -> segments[1]
                host.isNotBlank() -> host.removePrefix("www.")
                else -> "repo"
            }
        return raw.lowercase().map { if (it.isLetterOrDigit() || it == '-') it else '-' }.joinToString("")
    }

    /** The key prefix a module index groups its entries under, one array per media type. */
    private const val CategoryKeyPrefix = "category:"

    /**
     * The bundled sources plus everything the registries published, with one asymmetry.
     *
     * A bundled source is Hush's own record of a source: its relay, its provider key, and the id
     * the runtime and the signed-session contracts are written against. A registry may enrich it
     * - that is how a Tidal package becomes installable and how a version is known - and both
     * bundled registries are treated as trusted for that, because they are the registries Hush
     * ships and the source ids they address are Hush's own.
     *
     * A repository the *user* added is not, and the distinction is the whole reason this function
     * exists rather than an inlined merge. A third-party registry can introduce sources (that is
     * what adding it is for), but it cannot repoint one Hush already ships: declaring
     * `id = "tidal-web"` with its own `download_url` would otherwise replace the package that is
     * downloaded and run for a source the user never chose to change. Its metadata is still taken
     * - a better description or icon is not a risk - and anything it declares under an id of its
     * own arrives whole, digest and all.
     */
    fun merge(
        bundled: List<ExtensionSource>,
        remote: List<ExtensionSource>,
        customRepositoryUrls: Set<String>,
    ): List<ExtensionSource> {
        // A module's id is namespaced by its repository, so it can never collide with a bundled
        // source id and never reaches the branch below that refuses to let a third-party registry
        // repoint one of Hush's own sources. The rule still holds for a module index read as a
        // registry: an entry that reuses `tidal-web` is third-party metadata at most.
        val merged = LinkedHashMap<String, ExtensionSource>()
        bundled.forEach { merged[it.id] = it }

        remote.forEach { ext ->
            val existing = merged[ext.id]
            if (existing == null) {
                merged[ext.id] = ext
                return@forEach
            }
            merged[ext.id] =
                if (ext.repositoryId in customRepositoryUrls) {
                    existing.copy(
                        version = ext.version.ifBlank { existing.version },
                        description = ext.description.ifBlank { existing.description },
                        // Display name only: the source keeps the id it is addressed by.
                        displayNameFromRegistry = ext.displayNameFromRegistry ?: existing.displayNameFromRegistry,
                        author = ext.author.ifBlank { existing.author },
                        icon = ext.icon ?: ext.iconUrl ?: existing.icon,
                        category = ext.category ?: existing.category,
                        tags = ext.tags.ifEmpty { existing.tags },
                        types = ext.types.ifEmpty { existing.types },
                        minAppVersion = ext.minAppVersion ?: existing.minAppVersion,
                    )
                } else {
                    existing.copy(
                        version = ext.version.ifBlank { existing.version },
                        description = ext.description.ifBlank { existing.description },
                        relayUrl = ext.relayUrl ?: existing.relayUrl,
                        providerKey = ext.providerKey ?: existing.providerKey,
                        icon = ext.icon ?: ext.iconUrl ?: existing.icon,
                        downloadUrl = ext.downloadUrl ?: existing.downloadUrl,
                        sha256 = ext.sha256 ?: existing.sha256,
                        minAppVersion = ext.minAppVersion ?: existing.minAppVersion,
                        category = ext.category ?: existing.category,
                        repositoryId = ext.repositoryId.ifBlank { existing.repositoryId },
                    )
                }
        }

        return merged.values.toList()
    }
}
