/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
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
}
