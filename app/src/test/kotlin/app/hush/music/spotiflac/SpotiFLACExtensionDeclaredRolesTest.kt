/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the roles the session list describes a source with before its package has been read.
 *
 * The registry publishes no `types` list - read from `raw.githubusercontent.com/zarzet/…/registry.json`,
 * every one of the eight entries omits it - so a row built from the registry alone knew nothing about
 * what a source was for. Apple Music and Spotify Web were described with the same sentence as
 * SoundCloud, which is what made them look like sources whose session had gone missing rather than
 * ones that never had one.
 */
class SpotiFLACExtensionDeclaredRolesTest {

    private fun source(
        id: String,
        category: String? = null,
        tags: List<String> = emptyList(),
        types: List<String> = emptyList(),
    ) = ExtensionSource(id = id, name = id, category = category, tags = tags, types = types)

    /** The download sources as the registry actually publishes them. */
    @Test
    fun `a download source is a download provider`() {
        listOf("soundcloud", "ytmusic-spotiflac", "amazon", "deezer", "qobuz-web", "tidal-web").forEach { id ->
            assertEquals(listOf("download_provider"), source(id, category = "download").declaredRoles)
        }
    }

    /** And the two that cannot download, which is why they have no session to authorise. */
    @Test
    fun `the metadata sources are described by what they do`() {
        assertEquals(
            listOf("metadata_provider", "lyrics_provider"),
            source("apple-music", category = "integration", tags = listOf("apple", "metadata", "lyrics", "search"))
                .declaredRoles,
        )
        assertEquals(
            listOf("metadata_provider"),
            source("spotify-web", category = "integration", tags = listOf("spotify", "web", "streaming", "homefeed"))
                .declaredRoles,
        )
    }

    /** A manifest that does declare its types is the stronger answer and is used as written. */
    @Test
    fun `declared types win over the registry category`() {
        assertEquals(
            listOf("metadata_provider", "download_provider"),
            source("odd-one", category = "integration", types = listOf("metadata_provider", "download_provider"))
                .declaredRoles,
        )
    }

    /**
     * A category nobody has seen says neither thing: the row keeps the neutral sentence, because
     * calling an unknown source metadata-only would be an invention about a source the user is
     * looking at precisely because it is unfamiliar.
     */
    @Test
    fun `an unknown category declares nothing`() {
        assertEquals(emptyList<String>(), source("mystery").declaredRoles)
        assertEquals(emptyList<String>(), source("mystery", category = "").declaredRoles)
    }

    /** A download source with a lyrics tag stays a downloader, so it keeps the plain sentence. */
    @Test
    fun `a downloader that also serves lyrics is still a downloader`() {
        assertEquals(
            listOf("download_provider", "lyrics_provider"),
            source("weird", category = "download", tags = listOf("lyrics")).declaredRoles,
        )
    }

    // --- what the same declaration says about audio, before any package has been read -----------

    /**
     * The registry is the only place this can be answered before the package exists - which is the
     * window a resolve starts in on a fresh install. `spotify-web`, `apple-music`, `bugs-music`,
     * `melon-music`, `genie-music` and `spoiti-lists` all publish `category: "integration"`, and
     * none of them can hand over a file.
     */
    @Test
    fun `a metadata provider declares that it cannot serve audio`() {
        assertTrue(
            source("spotify-web", category = "integration", tags = listOf("spotify", "web", "streaming", "homefeed"))
                .declaresMetadataOnly,
        )
        assertTrue(
            source("apple-music", category = "integration", tags = listOf("apple", "metadata", "lyrics", "search"))
                .declaresMetadataOnly,
        )
        assertTrue(source("spoiti-lists", category = "integration", tags = listOf("spotify", "playlist", "oauth"))
            .declaresMetadataOnly)
    }

    @Test
    fun `a downloader does not declare itself metadata only`() {
        listOf("soundcloud", "ytmusic-spotiflac", "amazon", "deezer", "qobuz-web", "tidal-web", "jiosaavn")
            .forEach { id -> assertFalse(id, source(id, category = "download").declaresMetadataOnly) }
    }

    /**
     * The sources that do both must keep being swept. Every provider that enriches metadata *and*
     * serves audio publishes both roles, and reading "metadata_provider" alone as "no audio" would
     * switch off most of the catalogue.
     */
    @Test
    fun `a source that serves audio and metadata is not metadata only`() {
        assertFalse(
            source("amazon", category = "download", types = listOf("metadata_provider", "download_provider"))
                .declaresMetadataOnly,
        )
        assertFalse(
            source("deezer", types = listOf("metadata_provider", "download_provider", "lyrics_provider"))
                .declaresMetadataOnly,
        )
    }

    /**
     * `category: "download"` is the older way of saying the same thing and outranks a `types` list
     * that only mentions metadata - the same precedence the installed-package rule already gives it,
     * so a registry entry and its package cannot be read two different ways.
     */
    @Test
    fun `a download category keeps a source in the sweep despite a metadata types list`() {
        assertFalse(
            source("odd-one", category = "download", types = listOf("metadata_provider"))
                .declaresMetadataOnly,
        )
    }

    /**
     * An entry nobody can classify says nothing, in either direction - so it is not taken out of the
     * audio sweep. Dropping an id here is the one mistake that is certain to lose a source.
     */
    @Test
    fun `an unknown entry is not declared metadata only`() {
        assertFalse(source("mystery").declaresMetadataOnly)
        assertFalse(source("mystery", category = "").declaresMetadataOnly)
    }

    /** A module's own `resources` is its complete declaration, so search-only cannot serve audio. */
    @Test
    fun `a module that only searches cannot serve audio`() {
        assertTrue(
            source("bitchord:search-only", types = emptyList()).copy(
                runtime = ModuleRuntime,
                downloadUrl = "https://example.test/module.js",
                requiredRuntimeFeatures = listOf("search"),
            ).declaresMetadataOnly,
        )
        assertFalse(
            source("bitchord:tidal", types = emptyList()).copy(
                runtime = ModuleRuntime,
                downloadUrl = "https://example.test/module.js",
                requiredRuntimeFeatures = listOf("search", "stream"),
            ).declaresMetadataOnly,
        )
    }
}
