/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The sweep runs where Hush's own cached music lives, so the property that matters is what it
 * leaves: the SpotiFLAC playback files and the download cache are in the same root as the old
 * Media3 spans, and only the spans may go.
 */
class PlayerCacheLayoutMigrationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(directory: File, name: String, bytes: Int = 8): File =
        File(directory, name).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(bytes))
        }

    @Test
    fun `legacy spans and shards go, hush's own folders stay`() {
        val root = tmp.newFolder("song-cache")
        val playerCache = File(root, "player-cache")

        val legacySpan = file(root, "15.10485760.1789300859647.v3.exo")
        val legacyUid = file(root, "27130d95dc936504.uid")
        val sharded = file(root, "9/42.5242880.1789300859611.v3.exo")
        val spotiflac = file(root, "spotiflac-playback/078df57722aa0438b2b220c1.media", bytes = 32)
        val downloadCache = file(root, "download-cache/7.10485760.1789300859611.v3.exo")

        val removed = PlayerCacheLayoutMigration.sweepLegacyRoot(root, playerCache)

        assertEquals(3, removed)
        assertFalse(legacySpan.exists())
        assertFalse(legacyUid.exists())
        assertFalse(sharded.exists())
        // Hush's own bytes are what this sweep must never be able to reach.
        assertTrue(spotiflac.exists())
        assertTrue(downloadCache.exists())
        assertEquals(32L, spotiflac.length())
    }

    @Test
    fun `a shard holding something else is left in place`() {
        val root = tmp.newFolder("song-cache")
        val playerCache = File(root, "player-cache")
        val kept = file(root, "3/not-a-cache-file.bin")

        assertEquals(0, PlayerCacheLayoutMigration.sweepLegacyRoot(root, playerCache))

        assertTrue(File(root, "3").isDirectory)
        assertTrue(kept.exists())
    }

    @Test
    fun `a folder already on the new layout is never touched`() {
        val root = tmp.newFolder("song-cache")
        val playerCache = File(root, "player-cache").apply { mkdirs() }
        val span = file(root, "15.10485760.1789300859647.v3.exo")

        assertEquals(0, PlayerCacheLayoutMigration.sweepLegacyRoot(root, playerCache))

        assertTrue(span.exists())
    }
}
