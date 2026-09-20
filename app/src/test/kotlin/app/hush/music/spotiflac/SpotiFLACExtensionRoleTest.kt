/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the rule that keeps a metadata-only extension out of the download sweep.
 *
 * The manifests below are the real ones from the device, trimmed to the fields that decide it.
 */
class SpotiFLACExtensionRoleTest {

    private val spotifyWeb = """{"name":"spotify-web","type":["metadata_provider"]}"""

    private val appleMusic =
        """{"name":"apple-music","type":["metadata_provider","lyrics_provider"]}"""

    private val tidal = """{"name":"tidal-web","type":["metadata_provider","download_provider"]}"""

    @Test
    fun `a metadata-only extension declares no download provider`() {
        assertTrue(SpotiFLACExtensionRole.declaresNoDownloadProvider(spotifyWeb))
        assertTrue(SpotiFLACExtensionRole.declaresNoDownloadProvider(appleMusic))
    }

    @Test
    fun `a download provider is not excluded`() {
        assertFalse(SpotiFLACExtensionRole.declaresNoDownloadProvider(tidal))
        // Even alongside every other role it can carry.
        assertFalse(
            SpotiFLACExtensionRole.declaresNoDownloadProvider(
                """{"type":["metadata_provider","download_provider","lyrics_provider"]}""",
            ),
        )
    }

    /** The older way of saying "this one downloads" must not be read as the opposite. */
    @Test
    fun `a category of download counts as a download provider`() {
        assertFalse(
            SpotiFLACExtensionRole.declaresNoDownloadProvider(
                """{"type":["metadata_provider"],"category":"download"}""",
            ),
        )
    }

    /**
     * Nothing readable is never an exclusion: a package that has not been extracted yet is not a
     * source that cannot download, and dropping it would take a real provider out of the sweep.
     */
    @Test
    fun `an unreadable or silent manifest decides nothing`() {
        assertFalse(SpotiFLACExtensionRole.declaresNoDownloadProvider(null))
        assertFalse(SpotiFLACExtensionRole.declaresNoDownloadProvider(""))
        assertFalse(SpotiFLACExtensionRole.declaresNoDownloadProvider("not json"))
        assertFalse(SpotiFLACExtensionRole.declaresNoDownloadProvider("""{"name":"mystery"}"""))
        assertFalse(SpotiFLACExtensionRole.declaresNoDownloadProvider("""{"type":[]}"""))
    }
}
