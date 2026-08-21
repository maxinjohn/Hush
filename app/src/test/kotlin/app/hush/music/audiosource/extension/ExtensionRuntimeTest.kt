/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.audiosource.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionRuntimeTest {
    @Test
    fun `v1 anonymous flow detected`() {
        val runtime =
            ExtensionRuntime(
                relayBaseUrl = "https://api.zarz.moe",
                downloadPath = "/v1/dl/pan",
                providerKey = "pan",
            )
        assertTrue(runtime.isV1Anonymous)
        assertEquals("pan", runtime.providerKey)
        assertEquals("/v1/dl/pan", runtime.downloadPath)
    }

    @Test
    fun `v2 session flow not anonymous`() {
        val runtime =
            ExtensionRuntime(
                relayBaseUrl = "https://api.zarz.moe/v2",
                downloadPath = "/dl/qbz",
                providerKey = "qbz",
            )
        assertFalse(runtime.isV1Anonymous)
        assertEquals("qbz", runtime.providerKey)
    }

    @Test
    fun `installed extension requires session from manifest`() {
        val installed =
            InstalledExtension(
                extensionId = "qobuz-web",
                repositoryId = "repo-1",
                displayName = "Qobuz",
                description = "q",
                version = "1.0.0",
                category = "download",
                tags = emptyList(),
                downloadUrl = "https://x/qobuz.sflx",
                sha256 = "",
                manifest =
                    ExtensionManifest(
                        signedSession =
                            ExtensionSignedSession(
                                namespace = "zarz-v2",
                                baseUrl = "https://api.zarz.moe/v2",
                                appVersion = "qobuz-web@1.0.0",
                            ),
                    ),
            )
        assertTrue(installed.hasSignedSession)
        assertTrue(installed.requiresSession)
    }

    @Test
    fun `installed extension without session does not require session`() {
        val installed =
            InstalledExtension(
                extensionId = "pandora",
                repositoryId = "repo-1",
                displayName = "Pandora",
                description = "p",
                version = "1.0.0",
                category = "download",
                tags = emptyList(),
                downloadUrl = "https://x/pandora.sflx",
                sha256 = "",
                manifest = ExtensionManifest(),
            )
        assertFalse(installed.hasSignedSession)
        assertFalse(installed.requiresSession)
    }
}