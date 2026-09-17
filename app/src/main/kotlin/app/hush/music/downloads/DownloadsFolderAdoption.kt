/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & 5
 */

package app.hush.music.downloads

import java.io.File

/**
 * Matches a track to a file the downloads folder already holds.
 *
 * A downloads *index* lives in the app's private data, so a reinstall, a data clear, or a
 * restore onto another device loses it - while the files, ordinary files in a folder the user
 * chose, stay exactly where they were. Adopting them again is what stops a song whose file is
 * sitting right there from being streamed from the network.
 *
 * The rule is deliberately the one a person can apply by looking: a download is named
 * `<Artist> - <Title>.<container>` (see [DownloadNaming.fileName]), so the track's own metadata
 * produces the names to look for. Nothing here inspects audio, guesses from file size, or
 * rewrites a file: a wrong match would serve one song's bytes as another's.
 *
 * Pure - it takes a list of files rather than reading a directory - so the matching rules can be
 * tested directly, which is where the subtle cases live (an unknown artist, a title that
 * sanitises to nothing, two files that both match).
 */
object DownloadsFolderAdoption {

    /**
     * The file-name stems (a name without its extension) a download of this track could carry,
     * lowercased.
     *
     * Both shapes are offered because a track without a usable artist is written as the title
     * alone by [DownloadNaming.fileName], and metadata the app cannot read must not make a file
     * the user already has invisible.
     *
     * A title that carries no name at all yields nothing. Naming turns an unusable title into a
     * placeholder so a file always has *some* name, but that placeholder identifies no track -
     * matching a file called it would be adopting a song on the strength of both being
     * nameless.
     */
    fun candidateFileStems(title: String?, artist: String?): Set<String> {
        val placeholder = DownloadNaming.sanitizeComponent(null)
        val safeTitle = DownloadNaming.sanitizeComponent(title).trim()
        if (safeTitle.isEmpty() || safeTitle.equals(placeholder, ignoreCase = true)) return emptySet()
        val safeArtist = DownloadNaming.sanitizeComponent(artist).trim()
            .takeUnless { it.equals(placeholder, ignoreCase = true) }
        return buildSet {
            add(safeTitle.lowercase())
            if (safeArtist != null) add("$safeArtist - $safeTitle".lowercase())
        }
    }

    /**
     * The file to adopt for a track, or null when nothing in the folder is it.
     *
     * A file another download already claims is skipped: two tracks with the same name are
     * common, and the one that actually wrote the file owns it. Among the files left, the
     * largest wins - a truncated attempt is the one most likely to still be lying around beside
     * the complete copy.
     */
    fun match(
        files: List<File>,
        claimedNames: Set<String>,
        stems: Set<String>,
    ): File? {
        if (stems.isEmpty()) return null
        return files
            .asSequence()
            .filter { file -> file.isFile && file.length() > 0L }
            .filter { file -> file.name !in claimedNames }
            .filter { file -> file.name.substringBeforeLast('.').trim().lowercase() in stems }
            .maxByOrNull { it.length() }
    }
}
