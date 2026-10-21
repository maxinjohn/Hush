/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.storage

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule that decides what the app says about the folder downloads live in.
 *
 * Downloads are ordinary files in a folder the user chose and nothing else holds them, so a folder
 * that has gone away or stopped being writable has to be reported rather than quietly worked
 * around: the app falls back to its own directory, downloads start landing there, and the files the
 * user already had become unreachable - all without anything on screen saying so.
 *
 * What is worth pinning is which answer wins when several could apply at once: a folder that is not
 * there cannot also be "unwritable", and the access question is only worth asking about a folder
 * that exists and could actually be opened if the grant were given.
 */
class StorageFolderHealthTest {
    private val chosenPath = "/storage/emulated/0/Download/Hush/Music"

    @Test
    fun `no chosen folder is healthy without looking at anything`() {
        // The app's own directory cannot be pulled out or revoked, so the default location is never
        // the subject of a warning - and it costs nothing to answer.
        assertEquals(StorageFolderHealth.Healthy, health(configuredPath = null))
        assertEquals(StorageFolderHealth.Healthy, health(configuredPath = "  "))
    }

    @Test
    fun `the app's own folder is healthy even when nothing is known about it`() {
        // Every "no" here is about a folder that was never chosen; none of it may produce a warning.
        assertEquals(
            StorageFolderHealth.Healthy,
            health(
                configuredPath = null,
                exists = false,
                isDirectory = false,
                writable = false,
                needsAllFilesAccess = true,
            ),
        )
    }

    @Test
    fun `a chosen folder that is not there is reported as gone`() {
        assertEquals(StorageFolderHealth.Missing(chosenPath), health(exists = false))
    }

    @Test
    fun `a chosen folder that is not a directory cannot be written in`() {
        // A file sitting where the folder used to be: the location is unusable, and it is not gone
        // in the way a removed card is, so the user is told it cannot be written rather than erased.
        assertEquals(
            StorageFolderHealth.NotWritable(chosenPath),
            health(exists = true, isDirectory = false),
        )
    }

    @Test
    fun `a chosen folder that can be written in is healthy`() {
        assertEquals(
            StorageFolderHealth.Healthy,
            health(exists = true, isDirectory = true, writable = true),
        )
    }

    @Test
    fun `a folder that cannot be written in and no grant can fix is not writable`() {
        assertEquals(
            StorageFolderHealth.NotWritable(chosenPath),
            health(exists = true, isDirectory = true, writable = false, needsAllFilesAccess = false),
        )
    }

    @Test
    fun `a folder that only needs All files access says so`() {
        assertEquals(
            StorageFolderHealth.NeedsAllFilesAccess(chosenPath),
            health(exists = true, isDirectory = true, writable = false, needsAllFilesAccess = true),
        )
    }

    @Test
    fun `being gone wins over every other answer`() {
        assertMissingWins(
            health(
                exists = false,
                isDirectory = false,
                writable = false,
                needsAllFilesAccess = true,
            ),
        )
        assertMissingWins(
            health(
                exists = false,
                isDirectory = false,
                writable = true,
                needsAllFilesAccess = false,
            ),
        )
    }

    @Test
    fun `a folder that cannot be written in never reports the access answer when it can be written`() {
        // The probe is the authority: if it succeeded, an access request would be asking for
        // something that is not needed, which is how a screen ends up nagging about a folder that
        // works.
        assertEquals(
            StorageFolderHealth.Healthy,
            health(exists = true, isDirectory = true, writable = true, needsAllFilesAccess = true),
        )
    }

    private fun assertMissingWins(health: StorageFolderHealth) {
        assertEquals(StorageFolderHealth.Missing(chosenPath), health)
    }

    private fun health(
        configuredPath: String? = chosenPath,
        exists: Boolean = true,
        isDirectory: Boolean = true,
        writable: Boolean = false,
        needsAllFilesAccess: Boolean = false,
    ): StorageFolderHealth =
        classifyStorageFolderHealth(
            configuredPath = configuredPath,
            exists = exists,
            isDirectory = isDirectory,
            writable = writable,
            needsAllFilesAccess = needsAllFilesAccess,
        )
}
