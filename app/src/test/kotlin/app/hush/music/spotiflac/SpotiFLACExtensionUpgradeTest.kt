package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An installed extension package used to be written once and trusted forever: the download ran
 * only when the file was missing, so `amazon` stayed at 2.3.8 on device while both registries
 * published 2.3.10. These pin the comparison that decides a package is stale, and - just as
 * importantly - the cases where it must *not* act, because a wrong "newer" either replaces a
 * working extension with nothing or re-downloads it on every track.
 */
class SpotiFLACExtensionUpgradeTest {

    @Test
    fun `a later registry build is newer than the installed one`() {
        // The measured case: 2.3.8 installed, 2.3.10 published. Plain string ordering gets this
        // wrong (\"2.3.10\" < \"2.3.8\"), which is why segments are compared as numbers.
        assertTrue(SpotiFLACExtensionUpgrade.isNewer("2.3.10", "2.3.8"))
        assertFalse(SpotiFLACExtensionUpgrade.isNewer("2.3.8", "2.3.10"))
        assertFalse(SpotiFLACExtensionUpgrade.isNewer("1.3.5", "1.3.5"))
    }

    @Test
    fun `a missing or unreadable version is never newer`() {
        // Anything unparseable must not be allowed to drive a replacement: a comparison that
        // cannot be trusted is not evidence that the package is out of date.
        assertFalse(SpotiFLACExtensionUpgrade.isNewer(null, "2.3.8"))
        assertFalse(SpotiFLACExtensionUpgrade.isNewer("2.3.8", null))
        assertFalse(SpotiFLACExtensionUpgrade.isNewer("latest", "2.3.8"))
        assertFalse(SpotiFLACExtensionUpgrade.isNewer("2.3.8", ""))
        assertFalse(SpotiFLACExtensionUpgrade.isNewer("   ", "2.3.8"))
    }

    @Test
    fun `shorter, prefixed and suffixed versions compare by their numbers`() {
        // Registries publish \"1.2\" as readily as \"1.2.0\", and a leading v is a label, not data.
        assertFalse(SpotiFLACExtensionUpgrade.isNewer("2.4", "2.4.0"))
        assertTrue(SpotiFLACExtensionUpgrade.isNewer("2.4.1", "2.4"))
        assertTrue(SpotiFLACExtensionUpgrade.isNewer("v2.4", "2.3.9"))
        // Build metadata is not part of the ordering.
        assertFalse(SpotiFLACExtensionUpgrade.isNewer("2.4.0+build7", "2.4.0"))
        // A pre-release sorts below the release it precedes, and above the one before it.
        assertFalse(SpotiFLACExtensionUpgrade.isNewer("1.0.0-beta", "1.0.0"))
        assertTrue(SpotiFLACExtensionUpgrade.isNewer("1.0.0", "1.0.0-beta"))
        assertTrue(SpotiFLACExtensionUpgrade.isNewer("1.0.0-beta.10", "1.0.0-beta.9"))
    }

    @Test
    fun `a package behind the registry is downloaded`() {
        assertEquals(
            SpotiFLACExtensionUpgrade.Action.DOWNLOAD,
            SpotiFLACExtensionUpgrade.plan(
                registryVersion = "2.3.10",
                storedVersion = "2.3.8",
                installedVersion = "2.3.8",
            ),
        )
        // No package at all is the ordinary first install.
        assertEquals(
            SpotiFLACExtensionUpgrade.Action.DOWNLOAD,
            SpotiFLACExtensionUpgrade.plan(
                registryVersion = "2.3.10",
                storedVersion = null,
                installedVersion = null,
            ),
        )
        // A package that cannot be read is re-fetched, which is also how a truncated file heals.
        assertEquals(
            SpotiFLACExtensionUpgrade.Action.DOWNLOAD,
            SpotiFLACExtensionUpgrade.plan(
                registryVersion = "2.3.10",
                storedVersion = "",
                installedVersion = "2.3.8",
            ),
        )
    }

    @Test
    fun `a current package the runtime has not loaded is reloaded`() {
        // The state a failed or interrupted upgrade leaves behind: the new package is on disk,
        // the runtime is still serving the old build.
        assertEquals(
            SpotiFLACExtensionUpgrade.Action.RELOAD,
            SpotiFLACExtensionUpgrade.plan(
                registryVersion = "2.3.10",
                storedVersion = "2.3.10",
                installedVersion = "2.3.8",
            ),
        )
    }

    @Test
    fun `nothing happens when the install already matches its registry`() {
        assertEquals(
            SpotiFLACExtensionUpgrade.Action.UP_TO_DATE,
            SpotiFLACExtensionUpgrade.plan("2.3.10", "2.3.10", "2.3.10"),
        )
        // An extension the runtime has never loaded is not an upgrade: the normal install-by-path
        // path handles it, and calling it an upgrade would fire on every fresh install.
        assertEquals(
            SpotiFLACExtensionUpgrade.Action.UP_TO_DATE,
            SpotiFLACExtensionUpgrade.plan("2.3.10", "2.3.10", null),
        )
        // A registry with nothing readable to say is not a reason to touch a working package.
        assertEquals(
            SpotiFLACExtensionUpgrade.Action.UP_TO_DATE,
            SpotiFLACExtensionUpgrade.plan(null, "2.3.8", "2.3.8"),
        )
        assertEquals(
            SpotiFLACExtensionUpgrade.Action.UP_TO_DATE,
            SpotiFLACExtensionUpgrade.plan("", "2.3.8", "2.3.8"),
        )
    }

    @Test
    fun `a registry that publishes an older build never downgrades`() {
        // Rolled back or stale registries exist; the install must keep what it has.
        assertEquals(
            SpotiFLACExtensionUpgrade.Action.UP_TO_DATE,
            SpotiFLACExtensionUpgrade.plan("2.3.8", "2.3.10", "2.3.10"),
        )
    }
}
