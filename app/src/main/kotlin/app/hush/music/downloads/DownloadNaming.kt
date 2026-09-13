/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.downloads

/**
 * Names and extensions for downloaded files.
 *
 * A download is a file the user owns, so it has to be named for what it is rather than
 * for the cache key it came from. Everything here is pure: the naming rules are the part
 * most likely to be subtly wrong (a stray slash silently creates a directory, a missing
 * extension makes the file unopenable), so they are kept free of Android and Media3 so
 * they can be tested directly.
 *
 * The extension is derived from the *source container*, not guessed - which is why a
 * SpotiFLAC download is a `.flac` and a YouTube audio stream is whatever container
 * YouTube actually served (`.m4a` for `audio/mp4`, `.webm` for `audio/webm`).
 */
object DownloadNaming {
    /** Longest path component built from a single field. */
    private const val MAX_COMPONENT_LENGTH = 80

    /** Characters that are illegal in a path component on Android (FAT/exFAT included). */
    private val ILLEGAL_COMPONENT_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    private val WHITESPACE_RUN = Regex("\\s+")

    /**
     * Makes [raw] safe to use as one path component.
     *
     * Illegal characters become `_` rather than being dropped, so two different titles
     * cannot collapse onto the same name. Control characters are stripped, runs of
     * whitespace collapse, and trailing dots/spaces are removed because some filesystems
     * silently discard them (which would make the stored path disagree with the file on
     * disk).
     */
    fun sanitizeComponent(raw: String?): String {
        val cleaned =
            buildString {
                raw.orEmpty().forEach { character ->
                    when {
                        character in ILLEGAL_COMPONENT_CHARS -> append('_')
                        character.code < 0x20 -> Unit
                        else -> append(character)
                    }
                }
            }
        val trimmed =
            cleaned
                .replace(WHITESPACE_RUN, " ")
                .take(MAX_COMPONENT_LENGTH)
                .trim()
                .trimEnd('.', ' ')
        // A field made entirely of punctuation says nothing about the track, so it is
        // reported as unknown rather than as a row of underscores. Anything with a letter
        // or digit in it is kept exactly, because that is the user's actual title.
        return if (trimmed.any { it.isLetterOrDigit() }) trimmed else UNKNOWN_FIELD
    }

    /**
     * Normalises an extension: no leading dot, lowercase, and nothing that could start a
     * new path component.
     *
     * Hyphens are kept: they are legal in a filename, and they are meaningful in mime
     * subtypes (`x-whatever`), where dropping one would misreport what the file is.
     */
    fun sanitizeExtension(extension: String?): String =
        extension
            .orEmpty()
            .trim()
            .trimStart('.')
            .lowercase()
            .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .take(MAX_COMPONENT_LENGTH)
            .ifBlank { FALLBACK_EXTENSION }

    /**
     * The container extension for [mimeType].
     *
     * Unknown types fall back to the mime subtype rather than to a fixed container, so
     * an unusual stream is still named after what it actually is. A file with a wrong
     * extension is worse than one with an odd extension, because it makes the failure
     * look like corruption.
     */
    fun extensionForMimeType(mimeType: String?): String {
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        when (mime) {
            "audio/mp4", "audio/m4a", "audio/x-m4a", "video/mp4", "application/mp4" -> return "m4a"
            "audio/webm", "video/webm" -> return "webm"
            "audio/mpeg" -> return "mp3"
            "audio/ogg", "application/ogg" -> return "ogg"
            "audio/opus" -> return "opus"
            "audio/flac", "audio/x-flac" -> return "flac"
            "audio/wav", "audio/x-wav", "audio/wave" -> return "wav"
            "audio/aac" -> return "aac"
        }
        val subtype = mime.substringAfter('/', "").substringBefore('+')
        return if (subtype.isNotBlank()) sanitizeExtension(subtype) else FALLBACK_EXTENSION
    }

    /** `<Artist> - <Title>.<extension>`, degrading to whichever half is known. */
    fun fileName(
        title: String?,
        artist: String?,
        extension: String,
    ): String {
        val safeTitle = sanitizeComponent(title)
        val safeArtist =
            sanitizeComponent(artist).takeUnless { it == UNKNOWN_FIELD || it.isBlank() }
        val base = if (safeArtist != null) "$safeArtist - $safeTitle" else safeTitle
        return "$base.${sanitizeExtension(extension)}"
    }

    /**
     * [fileName] itself, or `name (2).ext`, `name (3).ext`... until [taken] accepts it.
     *
     * Two different tracks legitimately share a title, and neither should be overwritten,
     * so the caller supplies what already exists rather than this function touching disk.
     */
    fun uniqueFileName(
        fileName: String,
        taken: (String) -> Boolean,
    ): String {
        if (!taken(fileName)) return fileName
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val extension = if (dot > 0) fileName.substring(dot) else ""
        var index = 2
        while (index < MAX_UNIQUE_ATTEMPTS) {
            val candidate = "$base ($index)$extension"
            if (!taken(candidate)) return candidate
            index++
        }
        // Absurdly unlikely; keeps the loop bounded and still produces a free name.
        return "$base (${System.currentTimeMillis()})$extension"
    }

    private const val UNKNOWN_FIELD = "Unknown"
    private const val FALLBACK_EXTENSION = "audio"
    private const val MAX_UNIQUE_ATTEMPTS = 1000
}
