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
    ): File? = match(byStem(files), claimedNames, stems)

    /**
     * The same match, asked of a folder that has already been read into a stem index.
     *
     * The whole-folder sweep asks this question once per library track, so the folder is read
     * once ([byStem]) and each track is then a handful of map lookups rather than a scan of
     * every file. The rule is the one [match] applies to a plain list, which is the form the
     * subtle cases are tested in.
     */
    fun match(
        byStem: Map<String, List<File>>,
        claimedNames: Set<String>,
        stems: Set<String>,
    ): File? {
        if (stems.isEmpty()) return null
        return stems
            .asSequence()
            .flatMap { stem -> byStem[stem].orEmpty().asSequence() }
            .filter { file -> file.name !in claimedNames }
            .maxByOrNull { it.length() }
    }

    /**
     * The files of [files] that can be matched at all, grouped by their lowercased stem.
     *
     * Largest first, so the first file of a stem is the one a single-file match would have
     * chosen - two files can share a stem when the same song arrived in two containers.
     */
    fun byStem(files: List<File>): Map<String, List<File>> =
        files
            .asSequence()
            .filter { file -> file.isFile && file.length() > 0L }
            .groupBy { file -> file.name.substringBeforeLast('.').trim().lowercase() }
            .mapValues { (_, group) -> group.sortedByDescending { it.length() } }

    /**
     * The whole-folder sweep: every track in [tracks] whose download file the folder holds.
     *
     * The one-track adoption the store does while a song is played re-establishes a single
     * record, which is too late for anything that reports on the downloads as a whole - the
     * size the Storage screen shows, the download badges, the downloaded-songs list. This asks
     * the same question for the entire library at once, from the folder's own files.
     *
     * A file is claimed by the first track that matches it, so two tracks with the same name can
     * never come to share one file's bytes; a track that already has a record ([alreadyRecorded])
     * is left alone. Pure - it takes the folder's files rather than reading a directory - so the
     * rules can be tested without a device.
     */
    fun sweep(
        files: List<File>,
        tracks: List<DownloadTrack>,
        alreadyRecorded: Set<String>,
        claimedNames: Set<String>,
    ): List<AdoptedDownload> {
        if (files.isEmpty() || tracks.isEmpty()) return emptyList()
        val filesByStem = byStem(files)
        val claimed = claimedNames.toMutableSet()
        val adopted = mutableListOf<AdoptedDownload>()
        tracks.forEach { track ->
            if (track.mediaId.isBlank() || track.mediaId in alreadyRecorded) return@forEach
            val match = match(filesByStem, claimed, candidateFileStems(track.title, track.artist)) ?: return@forEach
            claimed += match.name
            adopted += AdoptedDownload(track, match)
        }
        return adopted
    }
}

/** A library track whose download file may already be sitting in the downloads folder. */
data class DownloadTrack(
    val mediaId: String,
    val title: String?,
    val artist: String?,
)

/** A track and the file in the downloads folder that is its download. */
data class AdoptedDownload(
    val track: DownloadTrack,
    val file: File,
)

