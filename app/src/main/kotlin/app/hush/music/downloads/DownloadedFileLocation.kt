/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.downloads

import java.io.File

/**
 * Where a downloaded file sits, said in a way that survives the folder being changed.
 *
 * The download index used to record an absolute path, while the downloads folder is a
 * setting the user can move. Changing it relocated the audio and left every record
 * pointing at the folder it came from, so the store dropped all of them on load ("the file
 * has gone"): download badges vanished, playback fell back to resolving the track over the
 * network, and removal could no longer find the file to delete, leaking it into the old
 * folder. Nothing about a download is absolute - it is a name inside whichever folder is
 * current - so the name is what is recorded, and the folder is read at the moment of use.
 */
object DownloadedFileLocation {
    /**
     * The name this download is stored under inside the downloads folder.
     *
     * [DownloadedFile.path] is only consulted for records written before the index became
     * location-relative; its file name is the same name, so such a record is repaired
     * rather than discarded.
     */
    fun storedName(entry: DownloadedFile): String? =
        (entry.fileName.takeIf(String::isNotBlank) ?: entry.path.takeIf(String::isNotBlank)?.let { File(it).name })
            ?.takeIf(String::isNotBlank)

    /**
     * The file for [entry] inside [downloadsDirectory], or null when it is not there.
     *
     * The recorded byte count is part of the test: adoption is by name, and a name is only
     * trusted when the bytes behind it are the length this download was recorded at. That
     * keeps a folder the user picks - which may already hold files of its own - from being
     * mistaken for this download's contents.
     */
    fun resolve(
        downloadsDirectory: File,
        entry: DownloadedFile,
    ): File? {
        val name = storedName(entry) ?: return null
        val inFolder = File(downloadsDirectory, name)
        if (isIntact(inFolder, entry)) return inFolder
        // A record whose file is still at the absolute path it was written to (the folder
        // has not moved, but the entry predates [DownloadedFile.fileName]).
        val atRecordedPath = entry.path.takeIf(String::isNotBlank)?.let(::File)
        return atRecordedPath?.takeIf { isIntact(it, entry) }
    }

    private fun isIntact(
        file: File,
        entry: DownloadedFile,
    ): Boolean {
        if (!file.isFile) return false
        val length = file.length()
        if (length <= 0L) return false
        return entry.bytes <= 0L || entry.bytes == length
    }
}
