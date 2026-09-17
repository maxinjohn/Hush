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

    /**
     * How much is read when the question is not just "which container" but "which audio".
     *
     * The container signature lives in the first bytes, but in an MP4 the *codec* is named by the
     * sample entry inside the `stsd` box, which sits well past them - an Atmos or Dolby Digital
     * Plus track is an ordinary MP4 until you read far enough to see `ac-4`/`ec-3`. Four kilobytes
     * covers the sample-entry table of anything a music source returns, and is one read.
     */
    const val PROBE_BYTES = 4_096

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

    /**
     * True when [head] begins with a container this app is expected to play.
     *
     * A still-encrypted stream does not count, even though its container is a perfectly ordinary
     * MP4: it is a file nothing can decode, and treating it as audio is how a cached Amazon entry
     * would keep being served as silence on every replay. This is the same judgement the download
     * path makes, so a file that was accepted before that check existed is dropped now instead of
     * being trusted forever.
     */
    fun looksLikeAudio(head: ByteArray): Boolean =
        containerOf(head) != null && !isEncryptedStream(head)

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

    /**
     * A Dolby-family stream, which is not what a music request asked for.
     *
     * Amazon's extension offers Dolby Digital Plus (`eac3`) and Atmos (`ac4`) beside its lossless
     * FLAC, and a track it answers with one of those is silent on a device with no AC-3/AC-4 output
     * path - the player advances and nothing is audible. A silence cannot be told apart from a
     * working track by any of Hush's own log lines, because nothing looks at the bytes, which is why
     * this is detected rather than assumed.
     *
     * Returns the codec token (`eac3`, `ac3` or `ac4`) or null when the stream is not Dolby.
     */
    fun dolbyFormatOf(probe: ByteArray): String? {
        if (probe.size >= 2 &&
            probe[0] == 0x0B.toByte() &&
            probe[1] == 0x77.toByte()
        ) {
            // A bare AC-3/E-AC-3 sync frame: the stream is Dolby with no container around it.
            return "eac3"
        }
        // Inside an MP4 the sample entry names the codec: ac-4 for Atmos, ec-3 (with its
        // configuration boxes dac3/dec3) for Dolby Digital Plus, ac-3 for plain Dolby Digital.
        if (probe.size < 8 || !probe.startsWithAscii("ftyp", offset = 4)) return null
        val text = String(probe, 0, probe.size.coerceAtMost(PROBE_BYTES), Charsets.ISO_8859_1)
        return when {
            text.contains("ac-4") || text.contains("dac4") -> "ac4"
            text.contains("ec-3") || text.contains("dec3") -> "eac3"
            text.contains("ac-3") || text.contains("dac3") -> "ac3"
            else -> null
        }
    }

    /**
     * Whether a probe shows a stream that is still encrypted.
     *
     * Amazon's extension hands the runtime an encrypted stream plus a decryption key
     * (`ffmpeg.mov_key` over an ISO-BMFF payload), so what reaches Hush is only playable if that
     * decryption actually happened. When it does not, the result is a file of exactly the right size
     * that carries no decodable audio - the player advances and nothing is audible, and no other
     * player opens it either. An encrypted sample entry (`enca`/`encv`) or the protection scheme
     * boxes (`sinf`/`schm`/`tenc`) are what name that condition, and they sit at the head of the
     * file, inside [PROBE_BYTES].
     */
    fun isEncryptedStream(probe: ByteArray): Boolean {
        if (probe.size < 8 || !probe.startsWithAscii("ftyp", offset = 4)) return false
        val text = String(probe, 0, probe.size.coerceAtMost(PROBE_BYTES), Charsets.ISO_8859_1)
        return text.contains("enca") || text.contains("encv") ||
            text.contains("sinf") || text.contains("schm") || text.contains("tenc")
    }

    /**
     * The Dolby token a runtime-reported codec name names, or null.
     *
     * The runtime reports the codec it downloaded (`audio_codec`), which is a second, independent
     * way to see the same thing the bytes show - and the one that still works when a stream's
     * sample entry sits past [PROBE_BYTES].
     */
    fun dolbyFormatOfCodecName(name: String?): String? {
        val token = name?.trim()?.lowercase(Locale.US) ?: return null
        return when {
            token.contains("ac4") || token.contains("ac-4") -> "ac4"
            token.contains("eac3") || token.contains("e-ac-3") || token.contains("ec-3") -> "eac3"
            token.contains("ac3") || token.contains("ac-3") -> "ac3"
            else -> null
        }
    }

    /** Reads at most [PROBE_BYTES] from [file], for a container *and* codec check. */
    fun readProbe(file: File): ByteArray? = runCatching {
        file.inputStream().use { stream ->
            val buffer = ByteArray(PROBE_BYTES)
            val read = stream.read(buffer)
            if (read <= 0) ByteArray(0) else buffer.copyOf(read)
        }
    }.getOrNull()

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
