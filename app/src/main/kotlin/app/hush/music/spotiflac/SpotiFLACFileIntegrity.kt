/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import java.io.File
import java.util.Locale

/**
 * Whether a cached SpotiFLAC playback file is still the audio it claims to be.
 *
 * The cache used to accept any file that existed and was non-empty, which is not the
 * same statement as "this file is playable". Two realistic ways an entry passes that
 * check but cannot play:
 *
 * - **Truncated.** The download was interrupted, or the process was killed mid-write,
 *   so the file is a prefix of the real audio. Non-zero length, reads fine, and the
 *   container parser then fails at whatever offset the bytes stop.
 * - **Not audio at all.** A gateway returned an HTML error page or a JSON body that got
 *   written to the requested path, so the file is well-formed *as text* and the
 *   container parser rejects it immediately.
 *
 * Both look identical to the listener - a track that plays every time from a bad copy -
 * and neither can be fixed by re-preparing the file, which is what the playback
 * recovery paths did, because they purge Media3's caches and a playback file is served
 * straight off disk. Validating here is what turns "this track never plays" into "this
 * track re-resolves through every source".
 *
 * Deliberately cheap: a length comparison and the first few bytes. Playing the file
 * through a decoder to prove it works would cost more than the download it is meant to
 * avoid, and the container signature plus the recorded size already catch the two
 * failure modes above.
 */
object SpotiFLACFileIntegrity {
    /** How much of the file is read to identify its container. */
    const val HEAD_BYTES = 16

    /** What a cached file looked like when it was checked. */
    enum class Verdict {
        /** Playable as far as a signature and a length can tell. */
        OK,

        /** Shorter than what was downloaded: a partial write. */
        TRUNCATED,

        /** Does not begin with any known audio container. */
        NOT_AUDIO,

        /** Gone, empty, or unreadable. */
        MISSING,
    }

    /**
     * Judges a cached file.
     *
     * [recordedLength] is the size measured when the file was downloaded (0 when it was
     * never recorded, in which case only the signature is checked). The comparison is
     * one-sided on purpose: a file that grew is still a valid file, and containers are
     * sometimes rewritten to their real extension, but a file that shrank cannot be the
     * audio that was verified at [recordedLength] bytes.
     */
    fun verdict(
        fileLength: Long,
        recordedLength: Long,
        head: ByteArray?,
    ): Verdict {
        if (fileLength <= 0L) return Verdict.MISSING
        if (recordedLength > 0L && fileLength < recordedLength) return Verdict.TRUNCATED
        if (head == null || head.isEmpty()) return Verdict.MISSING
        return if (looksLikeAudio(head)) Verdict.OK else Verdict.NOT_AUDIO
    }

    /** True when [head] begins with a container this app is expected to play. */
    fun looksLikeAudio(head: ByteArray): Boolean = containerOf(head) != null

    /** What a cache lookup should do with a file. */
    enum class Action {
        /** Serve it: the bytes are complete and recognisable. */
        SERVE,

        /** Serve it and leave it alone - a user download is theirs to remove. */
        SERVE_KEEP,

        /** Do not serve, do not touch: resolve instead. */
        RESOLVE,

        /** Unusable beyond repair: drop it so the next resolve re-downloads. */
        DISCARD,
    }

    /**
     * The decision for one lookup, from the integrity verdict and the file's write state.
     *
     * [rewriting] takes precedence over [verdict] on purpose, and that precedence is the
     * whole point of this function existing. A re-download writes to the same path as the
     * entry it replaces, so mid-write the index still describes the previous, larger file
     * and the verdict is genuinely TRUNCATED - `DISCARD` would then delete a file an
     * in-progress download is writing, removing it from under the resolver. Being
     * rewritten is not the same statement as being damaged, so it resolves instead.
     */
    fun actionFor(
        verdict: Verdict,
        rewriting: Boolean,
        pinned: Boolean,
    ): Action = when {
        rewriting -> Action.RESOLVE
        verdict == Verdict.OK -> Action.SERVE
        verdict == Verdict.MISSING -> Action.RESOLVE
        pinned -> Action.SERVE_KEEP
        else -> Action.DISCARD
    }

    /**
     * The container named by [head], or null.
     *
     * Covers every container the SpotiFLAC sources actually return - FLAC for the
     * lossless sources, and M4A/AAC/Opus/MP3 for the lossy ones - plus the two formats a
     * user download can hold. An unlisted container is treated as not-audio, which costs
     * one re-download of a file that would otherwise fail at playback anyway.
     */
    fun containerOf(head: ByteArray): String? {
        if (head.isEmpty()) return null
        if (head.startsWithAscii("fLaC")) return "flac"
        if (head.startsWithAscii("OggS")) return "ogg"
        if (head.startsWithAscii("ID3")) return "mp3"
        if (head.startsWithAscii("RIFF")) return "wav"
        // MP4/M4A (and ALAC inside it) declares its brand at offset 4.
        if (head.size >= 8 && head.startsWithAscii("ftyp", offset = 4)) return "m4a"
        // Matroska/WebM, the container of YouTube's audio-only streams.
        if (head.size >= 4 &&
            head[0] == 0x1A.toByte() &&
            head[1] == 0x45.toByte() &&
            head[2] == 0xDF.toByte() &&
            head[3] == 0xA3.toByte()
        ) {
            return "webm"
        }
        // A bare MPEG/ADTS frame: 11 sync bits, so 0xFF followed by 0xE0-0xFF.
        if (head.size >= 2 &&
            head[0] == 0xFF.toByte() &&
            (head[1].toInt() and 0xE0) == 0xE0
        ) {
            return "mpeg"
        }
        return null
    }

    private fun ByteArray.startsWithAscii(
        value: String,
        offset: Int = 0,
    ): Boolean {
        if (size < offset + value.length) return false
        return value.indices.all { index ->
            this[offset + index].toInt().toChar().lowercase(Locale.US) ==
                value[index].lowercase(Locale.US)
        }
    }

    /** Reads at most [HEAD_BYTES] from [file], or null when it cannot be read. */
    fun readHead(file: File): ByteArray? = runCatching {
        file.inputStream().use { stream ->
            val buffer = ByteArray(HEAD_BYTES)
            val read = stream.read(buffer)
            if (read <= 0) ByteArray(0) else buffer.copyOf(read)
        }
    }.getOrNull()
}
