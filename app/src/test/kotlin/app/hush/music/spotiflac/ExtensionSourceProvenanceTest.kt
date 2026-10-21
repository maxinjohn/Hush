/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins which repository a source row names, and that it always names one.
 *
 * The section above the list promises it out loud - "Every row names the repository it came from" -
 * and the failure this test exists for is a row that names none: a source whose `repositoryId`
 * matches no repository row drew no line at all, which is how Pandora (shipped by Hush, declared by
 * no registry, so its id is empty) became the one row in the list whose origin was nowhere on it.
 * The lookup that produced that is now a rule with cases, and each case is here, including the two
 * that are easy to lose: a source no repository row claims still gets named, and a row whose own
 * name already carries the index does not say it twice.
 */
class ExtensionSourceProvenanceTest {

    private val bundled =
        RepositoryRow(
            url = "https://raw.githubusercontent.com/spotiflacapp/spotiflac-extension/main/registry.json",
            label = "spotiflacapp/spotiflac-extension",
            isBundled = true,
            declaredSources = 12,
        )

    private val moduleIndex =
        RepositoryRow(
            url = "https://raw.githubusercontent.com/OreoMuncher45/bitchord-streaming-modules/main/manifest.json",
            label = "BitChord Streaming Modules",
            removable = true,
            kind = RepositoryKind.MODULE_INDEX,
            declaredSources = 2,
        )

    /** A source as one of the two bundled registries publishes it. */
    private val qobuz =
        ExtensionSource(
            id = "qobuz-web",
            name = "Qobuz",
            repositoryId = bundled.url,
            relayUrl = "https://api.zarz.moe/v2",
            providerKey = "qobuz",
        )

    @Test
    fun `a source a repository row owns names that row`() {
        assertEquals("[spotiflacapp/spotiflac-extension]", sourceProvenanceLine(qobuz, listOf(bundled)))
    }

    @Test
    fun `the name is the one the repository section draws, nickname and all`() {
        // One string, drawn by two sections: the repository row already carries the user's nickname
        // when they gave one, and the sources it publishes say the same words back, so a user who
        // renamed a repository can tell which rows that name belongs to.
        assertEquals("[Mine]", sourceProvenanceLine(qobuz, listOf(bundled.copy(label = "Mine"))))
    }

    @Test
    fun `a source no repository declares is still named`() {
        // The Pandora case. Empty is not "unknown": Hush's own record defines this source, and the
        // row has to say so rather than leaving the line off.
        val pandora =
            ExtensionSource(
                id = "pandora",
                name = "Pandora",
                relayUrl = "https://api.zarz.moe/v2",
                providerKey = "pandora",
            )
        assertEquals("[built-in]", sourceProvenanceLine(pandora, listOf(bundled, moduleIndex)))
        assertEquals("[built-in]", sourceProvenanceLine(pandora, emptyList()))
    }

    @Test
    fun `a source whose repository row is not there yet is named from its id`() {
        // The first moments of a launch, and the moment after a repository is removed: the source is
        // listed from the merge before the repository list has been drawn. Named from the address it
        // came from, because a row with no origin reads as a source with no repository behind it.
        assertEquals(
            "[spotiflacapp/spotiflac-extension]",
            sourceProvenanceLine(qobuz, emptyList()),
        )
        // A repository that is not GitHub's raw host is named by its host - the same rule the
        // repository section's own label uses.
        val added =
            ExtensionSource(
                id = "my-source",
                repositoryId = "https://mine.example/registry.json",
            )
        assertEquals("[mine.example]", sourceProvenanceLine(added, emptyList()))
    }

    @Test
    fun `a module index that names itself in the entry is not named twice`() {
        // BitChord's exporter labels its modules `[<index>] (module)`, so the entry already says which
        // index it came from. Printing the bracket under it repeats the same repository on two
        // consecutive lines.
        val namedByTheIndex =
            ExtensionSource(
                id = "bitchord-streaming-modules:archive-flac",
                name = "[BitChord Streaming Modules] (archive.org)",
                repositoryId = moduleIndex.url,
                downloadUrl = "https://raw.githubusercontent.com/OreoMuncher45/bitchord-streaming-modules/main/modules/archive-flac.js",
                runtime = ModuleRuntime,
            )
        assertNull(sourceProvenanceLine(namedByTheIndex, listOf(bundled, moduleIndex)))
    }


    @Test
    fun `a source named after its own repository is not given that name twice`() {
        // The addon is called `gglos` and the repository it comes from is labelled with the same
        // word, so the row read `gglos` above `[gglos]`. The name already answers the question the
        // line exists to answer, which is the same reason the bracketed module name needs no line.
        val addonRow =
            RepositoryRow(
                url = "https://gglos.ggottes.deno.net/manifest.json",
                label = "gglos",
                removable = true,
                kind = RepositoryKind.SERVER_ADDON,
                declaredSources = 1,
            )
        val gglos =
            ExtensionSource(
                id = "gglos-ggottes-deno-net:gg.gglos.addon",
                name = "gglos",
                repositoryId = addonRow.url,
                runtime = AddonServerRuntime,
                addonBaseUrl = "https://gglos.ggottes.deno.net",
            )
        assertNull(sourceProvenanceLine(gglos, listOf(addonRow)))
    }

    /**
     * The line is compared against the name the row *draws*. The addon is drawn as `Tidal · gglos` -
     * the service it streams from, with its own name kept - and a repository that adds a word of its
     * own to that is a fact the row still has to state.
     */
    @Test
    fun `an addon whose repository adds its own word still names it`() {
        val addonRow =
            RepositoryRow(
                url = "https://gglos.ggottes.deno.net/manifest.json",
                label = "My addons",
                removable = true,
                kind = RepositoryKind.SERVER_ADDON,
                declaredSources = 1,
            )
        val gglos =
            ExtensionSource(
                id = "gglos-ggottes-deno-net:gg.gglos.addon",
                name = "gglos",
                repositoryId = addonRow.url,
                runtime = AddonServerRuntime,
                addonBaseUrl = "https://gglos.ggottes.deno.net",
            )
        assertEquals("[My addons]", sourceProvenanceLine(gglos, listOf(addonRow)))
    }

    @Test
    fun `a module that does not name its index gets the line like any other source`() {
        val plain =
            ExtensionSource(
                id = "bitchord-streaming-modules:tidal-flac",
                name = "Tidal FLAC",
                repositoryId = moduleIndex.url,
                downloadUrl = "https://raw.githubusercontent.com/OreoMuncher45/bitchord-streaming-modules/main/modules/tidal-flac.js",
                runtime = ModuleRuntime,
            )
        assertEquals("[BitChord Streaming Modules]", sourceProvenanceLine(plain, listOf(bundled, moduleIndex)))
    }

    @Test
    fun `the address is matched the way every other origin comparison is`() {
        // Case-insensitively, because a stored URL is whatever the registry published: the two
        // spellings are one repository, and a source that missed its row would read as built-in -
        // the one answer that is wrong rather than merely vague.
        val shouted = bundled.copy(url = bundled.url.replace("/main/", "/MAIN/"))
        val source = qobuz.copy(repositoryId = bundled.url.uppercase())
        assertEquals("[spotiflacapp/spotiflac-extension]", sourceProvenanceLine(source, listOf(shouted)))
    }
}
