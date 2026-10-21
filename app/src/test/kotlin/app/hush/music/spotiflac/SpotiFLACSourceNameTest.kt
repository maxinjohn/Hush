/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The wording every surface outside Audio Sources reads a source's name from.
 *
 * Measured on the device before this existed: the player named the source of the track it was
 * playing as `SpotiFLAC • ytmusic-spotiflac • cached` and a fetch in flight read `Fetching from
 * bitchord-streaming-modules:bitchord-tidal-flac`. Both are answers to \"where is this track coming
 * from\", written in the registries' vocabulary.
 */
class SpotiFLACSourceNameTest {

    private val moduleIndex =
        RepositoryRow(
            url = "https://raw.githubusercontent.com/OreoMuncher45/bitchord-streaming-modules/main/manifest.json",
            label = "BitChord Streaming Modules",
            isBundled = true,
        )

    private val spotiflacRegistry =
        RepositoryRow(
            url = "https://raw.githubusercontent.com/zarzet/SpotiFLAC-Extension/main/registry.json",
            label = "zarzet/SpotiFLAC-Extension",
            isBundled = true,
        )

    @Test
    fun `a source from a module index is named with the index it came from`() {
        assertEquals(
            "Tidal FLAC (tracks.monochrome.st) · BitChord Streaming Modules",
            SpotiFLACSourceName.label(
                sourceId = "bitchord-streaming-modules:bitchord-tidal-flac",
                name = "Tidal FLAC (tracks.monochrome.st)",
                repositoryId = moduleIndex.url,
                repositories = listOf(moduleIndex),
            ),
        )
    }

    /**
     * The bundled SpotiFLAC registries are the one case the repository is left off: the player
     * already says `SpotiFLAC`, and naming its registry as well prints the same family twice in one
     * label (`SpotiFLAC · Deezer · zarzet/SpotiFLAC-Extension`).
     */
    @Test
    fun `a source from Hush's own registries is named without one`() {
        assertEquals(
            "Deezer",
            SpotiFLACSourceName.label(
                sourceId = "deezer",
                name = "Deezer",
                repositoryId = spotiflacRegistry.url,
                repositories = listOf(spotiflacRegistry),
            ),
        )
    }

    /** A built-in source is declared by no repository, so there is none to name. */
    @Test
    fun `a built-in source is named by itself`() {
        assertEquals(
            "Pandora",
            SpotiFLACSourceName.label(sourceId = "pandora", name = "Pandora", repositoryId = ""),
        )
    }

    /**
     * The fallback, for the window before the registry sync has landed or for a source that is no
     * longer published: the namespace of a module id is a second spelling of its repository, so it
     * is dropped. What is left is a service's name where the id carries one - the id is a registry
     * identifier, and `bitchord-tidal-flac` beside `Deezer` reads as a second provider rather than
     * as Tidal - and otherwise the id as it is, since it is the only thing left to match against a
     * registry that is not loaded.
     */
    @Test
    fun `an unknown source keeps its id without the publishing namespace`() {
        assertEquals(
            "Tidal",
            SpotiFLACSourceName.label(
                sourceId = "bitchord-streaming-modules:bitchord-tidal-flac",
                name = null,
                repositoryId = null,
            ),
        )
        assertEquals(
            "bitchord-archive-flac",
            SpotiFLACSourceName.label("bitchord-streaming-modules:bitchord-archive-flac", null, null),
        )
        assertEquals("Deezer", SpotiFLACSourceName.label("deezer", " ", null))
        assertEquals(SpotiFLACSourceName.UNKNOWN, SpotiFLACSourceName.label("  ", null, null))
    }

    /**
     * A module index already labels its entries `[<index>] (service)`, and repeating the index after
     * the name would put the same repository on one row twice.
     */
    @Test
    fun `a name that already carries its repository is not given it again`() {
        assertEquals(
            "[BitChord Streaming Modules] (archive.org)",
            SpotiFLACSourceName.label(
                sourceId = "bitchord-streaming-modules:archive-flac",
                name = "[BitChord Streaming Modules] (archive.org)",
                repositoryId = moduleIndex.url,
                repositories = listOf(moduleIndex),
            ),
        )
    }

    /** The user's own nickname for a repository is what the row is called, here too. */
    @Test
    fun `a nicknamed repository is named what the user called it`() {
        val nicknamed = moduleIndex.copy(nickname = "My modules", label = "My modules")
        assertEquals(
            "Tidal FLAC · My modules",
            SpotiFLACSourceName.label(
                sourceId = "bitchord-streaming-modules:bitchord-tidal-flac",
                name = "Tidal FLAC",
                repositoryId = moduleIndex.url,
                repositories = listOf(nicknamed),
            ),
        )
    }


    /**
     * The addon names itself after its own host, and the host is what its repository is called, so
     * the label put the same word on screen twice: `3rd-party · gglos · gglos (fetched)`.
     *
     * The addon is also the one source whose name says nothing about the service behind it: a
     * listener reading `gglos` beside `Deezer` and `Tidal` has no way to know it is Tidal. So the
     * known upstream is named, and the addon's own name stays beside it as the provenance - and the
     * repository tag is left off, because it would only print `gglos` a second time.
     */
    private val gglosAddon =
        RepositoryRow(
            url = "https://gglos.ggottes.deno.net/manifest.json",
            label = "gglos",
        )

    @Test
    fun `an addon named after its host is named for the service behind it`() {
        assertEquals(
            "Tidal · gglos",
            SpotiFLACSourceName.label(
                sourceId = "gglos-ggottes-deno-net:gg.gglos.addon",
                name = "gglos",
                repositoryId = gglosAddon.url,
                repositories = listOf(gglosAddon),
            ),
        )
    }

    /**
     * The Audio Sources row draws the name half of that label, with the repository on its own line
     * underneath - so the list and the player call one source the same words, and `gglos` is Tidal in
     * both. The row is where a source is switched on and ordered, which is exactly where a name that
     * says nothing about what the source serves costs the most.
     */
    @Test
    fun `the list row and the player name a source the same way`() {
        assertEquals(
            "Tidal · gglos",
            SpotiFLACSourceName.name("gglos-ggottes-deno-net:gg.gglos.addon", "gglos"),
        )
        assertEquals(
            "Tidal · gglos",
            SpotiFLACSourceName.label(
                sourceId = "gglos-ggottes-deno-net:gg.gglos.addon",
                name = "gglos",
                repositoryId = gglosAddon.url,
                repositories = listOf(gglosAddon),
            ),
        )
        // And a name a registry wrote is the same on both surfaces, unchanged - here it is one word
        // that is also the id's own token, so the row and the player both read the service.
        assertEquals("Deezer", SpotiFLACSourceName.name("deezer", "Deezer"))
        assertEquals("Tidal", SpotiFLACSourceName.name("tidal-web", "tidal-web"))
        assertEquals(
            "Tidal FLAC (tracks.monochrome.st)",
            SpotiFLACSourceName.name("bitchord-streaming-modules:bitchord-tidal-flac", "Tidal FLAC (tracks.monochrome.st)"),
        )
    }

    /**
     * The provider does not swallow the repository: an addon whose repository is called something of
     * its own keeps the tag that distinguishes it, the same way every other source does.
     */
    @Test
    fun `an addon from a differently named repository still names it`() {
        val renamed = gglosAddon.copy(label = "My addons")
        assertEquals(
            "Tidal · gglos · My addons",
            SpotiFLACSourceName.label(
                sourceId = "gglos-ggottes-deno-net:gg.gglos.addon",
                name = "gglos",
                repositoryId = renamed.url,
                repositories = listOf(renamed),
            ),
        )
    }

    /** An addon that has not said which service it streams from is left exactly as it named itself. */
    @Test
    fun `an addon Hush has not measured keeps its own name`() {
        assertEquals(
            "some-addon",
            SpotiFLACSourceName.label(
                sourceId = "some-host.example:gg.unknown.addon",
                name = "some-addon",
                repositoryId = null,
            ),
        )
    }

    /** A manifest that publishes one source per base URL keeps its own sub-name, with the service in front. */
    @Test
    fun `a sub-source of a known addon keeps its own name after the service`() {
        assertEquals(
            "Tidal · gglos · Source 1",
            SpotiFLACSourceName.label(
                sourceId = "gglos-ggottes-deno-net:gg.gglos.addon.s1",
                name = "gglos · Source 1",
                repositoryId = null,
            ),
        )
    }

    /**
     * Two sources of one service, and the provider name a listener looks for is on both - so how the
     * source is served is the only thing left that can tell them apart.
     *
     * The Qobuz addon publishes `Qobuz`, which is exactly what the bundled Qobuz extension reads.
     * Left alone, the Audio Sources list drew two rows called `Qobuz` and the codec bar said
     * `3rd-party · Qobuz` for either, so a track fetched through the addon could not be told from one
     * fetched through the extension. The service's own name stays in front either way, because that
     * is the word the listener is looking for.
     */
    @Test
    fun `an addon that publishes the service's own name is told apart by its kind`() {
        val qobuzAddon =
            RepositoryRow(
                url = "https://qobuz-addon.vercel.app/manifest.json",
                label = "Qobuz",
            )
        assertEquals(
            "Qobuz · addon",
            SpotiFLACSourceName.name(
                sourceId = "qobuz-addon-vercel-app:com.bitchord.qobuz",
                name = "Qobuz",
            ),
        )
        // The player's label too, with the repository tag still off: that repository is called
        // `Qobuz` as well, so the tag would print the provider a second time instead of separating
        // the two sources.
        assertEquals(
            "Qobuz · addon",
            SpotiFLACSourceName.label(
                sourceId = "qobuz-addon-vercel-app:com.bitchord.qobuz",
                name = "Qobuz",
                repositoryId = qobuzAddon.url,
                repositories = listOf(qobuzAddon),
            ),
        )
        // The bundled extension of the same service keeps the bare provider name, and the addon
        // beside it keeps its own words after the service - neither is renamed by this rule.
        assertEquals("Qobuz", SpotiFLACSourceName.name("qobuz-web", "Qobuz"))
        assertEquals(
            "Tidal · gglos",
            SpotiFLACSourceName.name("gglos-ggottes-deno-net:gg.gglos.addon", "gglos"),
        )
    }

    /**
     * A name is not an id just because the host it is served from begins with it.
     *
     * The Tido addon is published from `tido.tido.tido.dpdns.org`, calls itself `Tido`, and its
     * manifest id ends in `…tidal.hifi` - so the id *starts with* the name it publishes, and the row
     * was renamed `Tidal` from the vendor token further along the id. A namespaced id begins with its
     * repository's or host's name, which is not the source's name; only an id that is the name it was
     * registered under (`qobuz-web`) is evidence that the registry wrote no name at all.
     */
    @Test
    fun `a namespaced id does not swallow a name its host begins with`() {
        val id = "tido-tido-tido-dpdns-org-4bd14d1e2fa848febf1e9c03cdc237c0:com.yourname.tidal.hifi"
        assertEquals("Tido", SpotiFLACSourceName.name(id, "Tido"))
        assertEquals("Tido · Lossless", SpotiFLACSourceName.name(id, "Tido · Lossless"))
        // The id-as-a-name rule still holds where it was written for: a registry that publishes its
        // own id, namespaced or not.
        assertEquals(
            "Tidal",
            SpotiFLACSourceName.name(
                "bitchord-streaming-modules:bitchord-tidal-flac",
                "bitchord-tidal-flac",
            ),
        )
    }

    /**
     * A registry that publishes its own id as the name has named nothing a listener can read: the
     * player read `SpotiFLAC • ytmusic-spotiflac • cached` on a track that came from YouTube Music.
     */
    @Test
    fun `a source named after its own id is named for its service`() {
        assertEquals(
            "YouTube Music",
            SpotiFLACSourceName.label(sourceId = "ytmusic-spotiflac", name = "ytmusic-spotiflac", repositoryId = null),
        )
        assertEquals(
            "Tidal",
            SpotiFLACSourceName.label(sourceId = "tidal-web", name = "tidal-web", repositoryId = ""),
        )
    }

    /**
     * And a name a registry did give - even one word of it - is that registry's wording, not an id
     * to be translated: `Tidal FLAC (tracks.monochrome.st)` says more than `Tidal` can.
     */
    @Test
    fun `a name a registry wrote is kept as written`() {
        assertEquals(
            "JioSaavn",
            SpotiFLACSourceName.label(sourceId = "jiosaavn", name = "JioSaavn", repositoryId = null),
        )
        assertEquals(
            "Tidal FLAC (tracks.monochrome.st)",
            SpotiFLACSourceName.label(
                sourceId = "bitbord-tidal",
                name = "Tidal FLAC (tracks.monochrome.st)",
                repositoryId = null,
            ),
        )
    }

    @Test
    fun `the same name in another case is still the same name`() {
        assertEquals(
            "GGLOS",
            SpotiFLACSourceName.label(
                sourceId = "gglos",
                name = "GGLOS",
                repositoryId = gglosAddon.url,
                repositories = listOf(gglosAddon),
            ),
        )
    }

    /**
     * The tag is only dropped when it adds no word of its own. `Qobuz Web` beside `Qobuz` is
     * exactly what the repository is there to say, so it stays.
     */
    @Test
    fun `a repository that adds a word of its own is still named`() {
        val repo = gglosAddon.copy(label = "Qobuz Web")
        assertEquals(
            "Qobuz · Qobuz Web",
            SpotiFLACSourceName.label(
                sourceId = "qobuz-web",
                name = "Qobuz",
                repositoryId = repo.url,
                repositories = listOf(repo),
            ),
        )
    }

    @Test
    fun `a tag whose words the name already has is dropped`() {
        assertEquals(
            "Tidal FLAC",
            SpotiFLACSourceName.label(
                sourceId = "tidal",
                name = "Tidal FLAC",
                repositoryId = gglosAddon.url,
                repositories = listOf(gglosAddon.copy(label = "Tidal")),
            ),
        )
    }

    /**
     * A repository the list has not read yet is still named, from its address - the same fallback the
     * settings rows use, so the label and the row cannot disagree about which repository a source came
     * from.
     */
    @Test
    fun `an unread repository is named from its address`() {
        assertEquals(
            "Some Source · AlexRabbit/SpoitiLists",
            SpotiFLACSourceName.label(
                sourceId = "spoiti-lists",
                name = "Some Source",
                repositoryId = "https://raw.githubusercontent.com/AlexRabbit/SpoitiLists/refs/heads/main/registry.json",
                repositories = emptyList(),
            ),
        )
    }
}
