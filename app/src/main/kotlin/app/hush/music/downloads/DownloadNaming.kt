/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.downloads

import java.io.File

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

    /**
     * The container extension for the bytes actually in [file].
     *
     * Read from the file's own header rather than from anything a caller *believes* the
     * container is. That belief is wrong often enough to matter: a source with no lossless
     * match answers with a lossy container instead, and the SpotiFLAC branch used to name
     * every one of its files `.flac` on the assumption that its output is always FLAC. A
     * track downloaded from `amazon` at its `opus` fallback was therefore written as a
     * 4.7 MB `.flac` whose first bytes were `ftypmp42` - an MP4/AAC file that every player
     * treats as corrupt, which is exactly how the user described it.
     *
     * [fallback] is used only when the header is unrecognised (an empty file, or a container
     * added after this was written). It deliberately does not default to a real container:
     * naming an unknown file `.flac` is the bug this exists to prevent.
     */
    fun extensionForFile(
        file: File,
        fallback: String = FALLBACK_EXTENSION,
    ): String {
        containerFromHeader(readHeader(file))?.let { return it }
        // No header this knows. The file's own extension is the next best evidence, but only
        // names this recognises are trusted, so the runtime's `.media` placeholder cannot
        // become a file's extension either.
        val named = sanitizeExtension(file.extension)
        return if (named in KNOWN_CONTAINERS) named else sanitizeExtension(fallback)
    }

    /** How much of a file is read to identify its container. */
    private const val HEADER_BYTES = 16

    /** Reads up to [HEADER_BYTES] bytes, or null when the file cannot be read. */
    private fun readHeader(file: File): ByteArray? =
        runCatching {
            file.inputStream().use { stream ->
                val buffer = ByteArray(HEADER_BYTES)
                var filled = 0
                while (filled < buffer.size) {
                    val read = stream.read(buffer, filled, buffer.size - filled)
                    if (read <= 0) break
                    filled += read
                }
                if (filled == 0) null else buffer.copyOf(filled)
            }
        }.getOrNull()

    /**
     * The container a leading header identifies, or null when it is not one of them.
     *
     * Only unambiguous signatures are matched. A container is announced by its own magic
     * rather than guessed from a length, so a file that is actually something else is never
     * labelled for the sake of a match.
     */
    private fun containerFromHeader(header: ByteArray?): String? {
        val bytes = header ?: return null
        fun ascii(start: Int, length: Int): String? =
            if (bytes.size >= start + length) {
                String(bytes, start, length, Charsets.US_ASCII)
            } else {
                null
            }
        fun byte(at: Int): Int? = if (bytes.size > at) bytes[at].toInt() and 0xFF else null

        // "fLaC"
        if (ascii(0, 4) == "fLaC") return "flac"
        // ISO base media: "....ftyp", as written by every MP4/M4A muxer.
        if (ascii(4, 4) == "ftyp") return "m4a"
        // Matroska/WebM EBML header (0x1A45DFA3).
        if (byte(0) == 0x1A && byte(1) == 0x45 && byte(2) == 0xDF && byte(3) == 0xA3) return "webm"
        // Ogg page ("OggS"); codec detection would need the header body, and the container
        // is what the extension names.
        if (ascii(0, 4) == "OggS") return "ogg"
        // RIFF....WAVE
        if (ascii(0, 4) == "RIFF" && ascii(8, 4) == "WAVE") return "wav"
        // ID3-tagged MP3, or a bare MPEG audio frame sync (0xFFEx/0xFFFx).
        if (ascii(0, 3) == "ID3") return "mp3"
        byte(0)?.let { first ->
            val second = byte(1) ?: return@let
            if (first == 0xFF && second and 0xE0 == 0xE0) {
                // 0xFFF_ is an ADTS AAC frame (layer bits 00); MPEG audio has a real layer.
                val layer = second and 0x06
                return if (layer == 0x00) "aac" else "mp3"
            }
        }
        return null
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

    /**
     * Container names this trusts when a file has no recognisable header.
     *
     * A fixed set rather than "anything alphabetic": `extensionForFile` falls back to a
     * file's own name, and the runtime writes `.media` there before it knows the container.
     */
    private val KNOWN_CONTAINERS =
        setOf("flac", "m4a", "mp4", "webm", "ogg", "oga", "opus", "mp3", "wav", "aac", "aiff", "wma")
    private const val MAX_UNIQUE_ATTEMPTS = 1000
}
