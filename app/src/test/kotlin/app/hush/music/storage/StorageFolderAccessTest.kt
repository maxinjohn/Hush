/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The rule that decides whether a folder is one this app owns.
 *
 * It is what tells "the user pointed Hush at Download/Hush, and Android is holding shared storage
 * back" apart from "the folder cannot be written for a reason no switch can fix". Getting it wrong
 * in the first direction is what made a picked folder look saved while every download went
 * somewhere else; getting it wrong in the second asks the user for access that cannot help.
 */
class StorageFolderAccessTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a folder inside the app's own tree is owned`() {
        val ownRoot = File(folder.root, "files")

        assertTrue(File(ownRoot, "download/song.flac").isWithin(ownRoot))
        assertTrue("the tree's own root counts as inside it", ownRoot.isWithin(ownRoot))
    }

    @Test
    fun `a folder that only shares a name prefix is not inside the tree`() {
        val ownRoot = File(folder.root, "files")

        assertFalse(File(folder.root, "files2/download").isWithin(ownRoot))
        assertFalse(File(folder.root, "files-other").isWithin(ownRoot))
    }

    @Test
    fun `a folder beside the app's own tree is not owned`() {
        val ownRoot = File(folder.root, "files")

        assertFalse(File(folder.root, "Download/Hush").isWithin(ownRoot))
    }
}
