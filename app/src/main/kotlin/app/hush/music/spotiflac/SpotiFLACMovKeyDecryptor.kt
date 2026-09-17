/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.ArrayDeque
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts the streams Amazon's extension hands over encrypted, and repairs the container.
 *
 * The extension downloads an Amazon stream *exactly as Amazon serves it* - an ISO-BMFF
 * payload under Common Encryption - and answers with the key instead of decrypting it:
 *
 * ```
 * decryption: { strategy: "ffmpeg.mov_key", key: "<hex>", input_format: "mov", output_extension: "" }
 * ```
 *
 * That name is the contract `ffmpeg -decryption_key <key> -i file` implements, and the Go
 * runtime deliberately does not implement it: it parses `decryption`/`decryption_key` off
 * the extension result and forwards them to the platform (`exports_download.go`), so the
 * bytes on disk stay encrypted. Nothing in the app ever looked at the key, so an Amazon
 * track arrived with exactly the right size, played position-with-no-sound, and was then
 * rejected as an unplayable format. This is the missing step.
 *
 * ## What it does
 *
 * `moov` is read first - it names the protection scheme and the format that was protected -
 * and then the file is rewritten in one streaming pass:
 *
 * - each `moof` has its sample encryption table neutralised (`senc`/`saiz`/`saio` become
 *   `free`), which is what makes a player treat these very samples as ordinary audio;
 * - the `stsd` sample entry is renamed from `enca` back to the format `sinf`/`frma` names,
 *   and its `sinf` box is neutralised;
 * - every `mdat`'s samples are decrypted with AES-CTR, using the per-sample IVs from `senc`
 *   as the initial counter block (CENC's rule: an 8-byte IV is the low half of a 16-byte
 *   big-endian counter).
 *
 * No box ever changes size, so every offset in the file - including `sidx`, which is what
 * keeps seeking working on a repaired file - stays valid. That is why the types are patched
 * in place instead of the container being rebuilt.
 *
 * ## FLAC
 *
 * Amazon serves its lossless tier as FLAC, and the extension's own note for that case is
 * "decrypt to .flac". FLAC inside an MP4 is not a `.flac` file, so for a `fLaC` sample entry
 * this writes a **native FLAC** stream instead: the `fLaC` marker, the metadata blocks
 * carried in `dfLa` (byte for byte, so the STREAMINFO and its MD5 are the encoder's), then
 * the decrypted frames in order. If the splice cannot be trusted - subsample encryption, a
 * `dfLa` that does not start with STREAMINFO, frames that do not begin with a FLAC sync
 * code - it falls back to the repaired MP4, which plays the same lossless audio through the
 * app's own extractor.
 *
 * Everything is buffered and streamed: a lossless Amazon track is tens to hundreds of
 * megabytes and this has to run on a low-RAM device without a second copy of it in memory.
 */
object SpotiFLACMovKeyDecryptor {

    /** Read/write buffer for both passes. */
    private const val IO_BUFFER = 256 * 1024

    /** `moov` is metadata and is read whole; this is the sanity ceiling for it. */
    private const val MAX_MOOV_ATOM = 32L * 1024 * 1024

    /** A single `moof` is metadata too, and is always small. */
    private const val MAX_FRAGMENT_ATOM = 8L * 1024 * 1024

    /** How many top-level boxes are inspected before giving up on finding an `moov`. */
    private const val MAX_TOP_LEVEL_BOXES = 256

    /** How many samples the counter-convention probe decrypts before it decides. */
    private const val PROBE_SAMPLES = 12

    /** How much of the first fragment is read for that probe. */
    private const val PROBE_WINDOW_BYTES = 512 * 1024

    /**
     * The convention used when a stream has no frame structure to verify one against.
     *
     * This project's own runtime left-aligns a short IV when it decrypts per-sample CENC media, so
     * that is the convention a stream we cannot verify is read with.
     */
    private val DEFAULT_CONVENTION = CounterConvention.LEADING_IV

    private const val FLAC = "fLaC"

    /** Which of the two outputs was produced. */
    enum class Output {
        /** A real `.flac` file, frames spliced out of the decrypted container. */
        NATIVE_FLAC,

        /** The same container, decrypted and with its protection boxes neutralised. */
        REPAIRED_MP4,
    }

    /** A decrypted, playable file. */
    data class DecryptedFile(
        val file: File,
        val output: Output,
        /** The fourcc of the sample entry that was protected: `fLaC`, `Opus`, `ec-3`, `ac-4`. */
        val originalFormat: String,
        val fragmentsDecrypted: Int,
        val samplesDecrypted: Int,
        val bytesDecrypted: Long,
        /** The counter convention the stream's own frame structure confirmed. */
        val counterConvention: CounterConvention,
    )

    /**
     * Which half of the 16-byte AES counter block an 8-byte per-sample IV occupies.
     *
     * CENC fixes the IV's size, not its placement, and the two ecosystems that decrypt these
     * streams disagree about it - a stream can only be decrypted one way, so guessing produces
     * noise that plays. `LEADING_IV` is what this project's own runtime uses for per-sample CENC
     * decryption (`decryptCTRSegments` in the Go backend left-aligns a short IV); `TRAILING_IV` is
     * what ffmpeg - the tool the provider's contract is named after - does. Which one a given
     * stream needs is established from the audio itself before anything is written.
     */
    enum class CounterConvention(val label: String) {
        /** The IV occupies the high 8 bytes; the low 8 bytes count blocks from zero. */
        LEADING_IV("iv-high"),

        /** The IV occupies the low 8 bytes; the counter counts on from it. */
        TRAILING_IV("iv-low"),
    }

    /** Raised when the stream cannot be decrypted; the caller treats it as a source failure. */
    class DecryptionFailedException(
        message: String,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    /**
     * Decrypts [source] with [keyHex] and returns the file to serve.
     *
     * [outputExtension] is the extension the provider asked for (`decryption.output_extension`);
     * empty means "the format's own", which is how the FLAC case arrives. The source is
     * consumed, so exactly one file is left behind and a 200 MB track never exists twice.
     */
    fun decrypt(
        source: File,
        keyHex: String?,
        outputExtension: String? = null,
        log: (String) -> Unit = {},
    ): DecryptedFile {
        val key = decodeKey(keyHex)
            ?: throw DecryptionFailedException("decryption key is not a 16-byte hex key")
        val layout = scanLayout(source)
        if (!layout.protected) {
            // The provider sent a key for a stream it served in the clear. Handing the file
            // straight back is the honest answer: "repairing" it would rename a `fLaC` sample
            // entry that was never `enca` in the first place.
            log("mov_key: id=${source.name} not encrypted (sample entry=${layout.originalFormat})")
            return DecryptedFile(
                file = source,
                output = Output.REPAIRED_MP4,
                originalFormat = layout.originalFormat,
                fragmentsDecrypted = 0,
                samplesDecrypted = 0,
                bytesDecrypted = 0L,
                counterConvention = CounterConvention.LEADING_IV,
            )
        }
        if (!layout.scheme.equals("cenc", ignoreCase = true)) {
            throw DecryptionFailedException(
                "unsupported protection scheme '${layout.scheme}' (only cenc is implemented)",
            )
        }
        if (!layout.fragmented) {
            // Non-fragmented CENC finds its samples through stbl tables rather than traf runs.
            // Amazon's muxer writes fragmented output, and guessing at the other layout risks
            // producing a file that is subtly wrong, which is worse than declining.
            throw DecryptionFailedException("non-fragmented CENC layout is not supported")
        }

        val repaired = File(source.parentFile, source.name + ".hush-dec")
        val nativeFlac = File(source.parentFile, source.name.substringBeforeLast('.') + ".flac")
        repaired.delete()
        nativeFlac.delete()

        // Which counter convention this stream uses is settled from the stream itself: the two
        // conventions in use decrypt to different bytes, and only one of them decodes.
        val convention = chooseCounterConvention(source, layout, key, frameOracleFor(layout.originalFormat), log)

        val stats = try {
            transform(source, repaired, nativeFlac, key, layout, convention, log)
        } catch (error: Throwable) {
            repaired.delete()
            nativeFlac.delete()
            throw if (error is DecryptionFailedException) {
                error
            } else {
                DecryptionFailedException("decryption failed: ${error.message}", error)
            }
        }

        if (stats.output == Output.NATIVE_FLAC) {
            source.delete()
            log(
                "mov_key: id=${source.name} -> ${nativeFlac.name} native flac " +
                    "counter=${convention.label} fragments=${stats.fragments} " +
                    "samples=${stats.samples} bytes=${stats.bytes}",
            )
            return DecryptedFile(
                file = nativeFlac,
                output = Output.NATIVE_FLAC,
                originalFormat = layout.originalFormat,
                fragmentsDecrypted = stats.fragments,
                samplesDecrypted = stats.samples,
                bytesDecrypted = stats.bytes,
                counterConvention = convention,
            )
        }

        // The provider already named the container correctly (`.m4a` for Opus and E-AC-3,
        // `.mp4` for AC-4), so the repaired bytes usually land back on the name it wrote and
        // the runtime's own bookkeeping still lines up.
        val target = outputFileFor(source, outputExtension)
        source.delete()
        val placed = if (repaired.renameTo(target)) target else repaired
        log(
            "mov_key: id=${source.name} -> ${placed.name} repaired container " +
                "format=${layout.originalFormat} counter=${convention.label} " +
                "fragments=${stats.fragments} samples=${stats.samples} bytes=${stats.bytes}",
        )
        return DecryptedFile(
            file = placed,
            output = Output.REPAIRED_MP4,
            originalFormat = layout.originalFormat,
            fragmentsDecrypted = stats.fragments,
            samplesDecrypted = stats.samples,
            bytesDecrypted = stats.bytes,
            counterConvention = convention,
        )
    }

    /**
     * Whether [keyHex] is a key this decryptor can use.
     *
     * Cheap and total: the download path asks before it commits to decrypting, and an
     * unusable key has to be reported as such instead of surfacing as a silent file.
     */
    fun isUsableKey(keyHex: String?): Boolean = decodeKey(keyHex) != null

    /* --------------------------------------------------------------------------------- *
     * Which counter convention this stream uses.
     * --------------------------------------------------------------------------------- */

    /** Reads bytes out of a decrypted sample and says whether they look like this codec's audio. */
    private fun interface FrameOracle {
        fun matches(first: Int, second: Int): Boolean
    }

    /**
     * How to tell a correctly decrypted sample of [originalFormat] from a wrongly decrypted one.
     *
     * This is the whole basis for choosing a counter convention (and for declining a decryption),
     * so it is deliberately narrow: both checks read the *first* bytes of a sample, which is where
     * these codecs put something that must hold for every frame - FLAC's 14-bit sync code, and for
     * Opus the TOC byte whose top five bits select a 10 ms or 20 ms CELT/hybrid configuration, the
     * only ones a streaming bitrate uses. A wrong key or a wrong counter produces essentially
     * random leading bytes, so a handful of samples is decisive, and a format with no such marker
     * gets no oracle rather than a guess.
     */
    private fun frameOracleFor(originalFormat: String): FrameOracle? = when {
        originalFormat.equals(FLAC, ignoreCase = true) -> FrameOracle { first, second ->
            first == 0xFF && (second and 0xFC) == 0xF8
        }

        originalFormat.equals("Opus", ignoreCase = true) -> FrameOracle { first, _ ->
            (first ushr 3) >= 12
        }

        else -> null
    }

    /**
     * Establishes which counter convention decrypts this stream, from the audio itself.
     *
     * The two conventions in use produce completely different bytes for the same key, and the
     * stream decides which one is right: the codec's frame structure only appears under the correct
     * one. Only the first fragment is read, so the cost is a few hundred kilobytes rather than a
     * second pass over a whole lossless track. When neither convention produces recognisable frames
     * the key does not decrypt this stream at all, and that is reported as a failure rather than
     * written out as a file that plays noise.
     */
    private fun chooseCounterConvention(
        source: File,
        layout: Layout,
        key: ByteArray,
        oracle: FrameOracle?,
        log: (String) -> Unit,
    ): CounterConvention {
        if (oracle == null) return DEFAULT_CONVENTION
        // A probe that cannot run is reported with its reason rather than silently falling back: a
        // fallback that is never verified is exactly how a stream gets decrypted to noise.
        val attempt = runCatching { probeSamples(source, layout) }
        val probe = attempt.getOrNull()
        if (probe.isNullOrEmpty()) {
            val reason = attempt.exceptionOrNull()?.message ?: "no whole samples in the first fragment"
            log(
                "mov_key: counter convention not established ($reason); " +
                    "using ${DEFAULT_CONVENTION.label}",
            )
            return DEFAULT_CONVENTION
        }
        val secret = SecretKeySpec(key, "AES")
        val hits = CounterConvention.entries.associateWith { convention ->
            probe.count { sample ->
                val decrypted = sample.bytes.copyOf()
                val cipher = cipherFor(secret, sample.iv, convention) ?: return@count false
                cipher.update(decrypted, 0, decrypted.size, decrypted, 0)
                oracle.matches(
                    decrypted[0].toInt() and 0xFF,
                    if (decrypted.size > 1) decrypted[1].toInt() and 0xFF else -1,
                )
            }
        }
        val winner = CounterConvention.entries.maxByOrNull { hits.getValue(it) } ?: DEFAULT_CONVENTION
        log(
            "mov_key: counter probe samples=${probe.size} " +
                CounterConvention.entries.joinToString(" ") { "${it.label}=${hits.getValue(it)}" },
        )
        if (hits.getValue(winner) == 0) {
            throw DecryptionFailedException(
                "decrypted frames are not ${layout.originalFormat} audio under either counter " +
                    "convention (the key does not decrypt this stream)",
            )
        }
        return winner
    }

    private class ProbeSample(val iv: ByteArray, val bytes: ByteArray)

    /**
     * The first samples of the first fragment, read straight off disk.
     *
     * Only whole-sample (non-subsample) samples are probed: a sample that is part clear and part
     * protected has a leading clear region, so its first bytes say nothing about the cipher.
     */
    private fun probeSamples(source: File, layout: Layout): List<ProbeSample> {
        RandomAccessFile(source, "r").use { file ->
            val total = file.length()
            var position = 0L
            var inspected = 0
            var fragment: Pair<Long, Long>? = null
            var media: Pair<Long, Long>? = null
            var fragmentBodyStart = 0L
            var fragmentBodySize = 0L
            var mediaPayloadStart = 0L
            var mediaPayloadEnd = 0L
            while (position + 8 <= total && inspected < MAX_TOP_LEVEL_BOXES) {
                inspected++
                val header = readBoxHeader(file, position, total)
                if (header.type == "moof" && fragment == null) {
                    fragment = position to header.size
                    fragmentBodyStart = position + header.headerSize
                    fragmentBodySize = header.size - header.headerSize
                }
                if (header.type == "mdat" && fragment != null && media == null) {
                    media = position to header.size
                    mediaPayloadStart = position + header.headerSize
                    mediaPayloadEnd = position + header.size
                }
                if (fragment != null && media != null) break
                position += header.size
            }
            if (fragment == null || media == null) return emptyList()
            if (fragmentBodySize <= 0L || fragmentBodySize > MAX_FRAGMENT_ATOM) return emptyList()

            // `parseFragment` reads the moof's body, while the sample offsets it reports are file
            // coordinates based at the moof box itself - the two are different offsets.
            val fragmentBytes = ByteArray(fragmentBodySize.toInt())
            file.seek(fragmentBodyStart)
            file.readFully(fragmentBytes)
            val payloadStart = mediaPayloadStart
            val windowEnd = minOf(mediaPayloadEnd, payloadStart + PROBE_WINDOW_BYTES)
            val samples = parseFragment(fragmentBytes, fragment.first, layout)
                .filter { it.segments == null && it.offset >= payloadStart && it.end <= windowEnd }
                .take(PROBE_SAMPLES)
            return samples.map { sample ->
                val bytes = ByteArray(sample.size)
                file.seek(sample.offset)
                file.readFully(bytes)
                ProbeSample(sample.iv, bytes)
            }
        }
    }

    /** The name the decrypted output should take, honouring what the provider asked for. */
    private fun outputFileFor(source: File, outputExtension: String?): File {
        val wanted = outputExtension?.trim()?.trimStart('.')?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: return source
        val stem = source.name.substringBeforeLast('.', source.name)
        val candidate = File(source.parentFile, "$stem.$wanted")
        return if (candidate.name == source.name) source else candidate
    }

    /* --------------------------------------------------------------------------------- *
     * Layout discovery: the moov-level facts that decide how to transform the file.
     * --------------------------------------------------------------------------------- */

    private class Box(
        val type: String,
        val start: Long,
        val headerSize: Int,
        val bodyStart: Int,
        val bodyEnd: Int,
        val size: Long,
    )

    /**
     * A rewrite inside `moov`, expressed in file coordinates and applied to the bytes read back
     * for that box.
     */
    private sealed class MoovPatch {
        /** Renames a box: the sample entry, whose fourcc must name the real format again. */
        class Retype(val typeOffset: Long, val newType: String) : MoovPatch() {
            override fun apply(bytes: ByteArray, bodyOffset: Long) {
                val local = (typeOffset - bodyOffset).toInt()
                if (local >= 0 && local + 4 <= bytes.size) {
                    newType.toByteArray(Charsets.ISO_8859_1).copyInto(bytes, local)
                }
            }
        }

        /**
         * Renames a box and empties it: how the protection boxes are removed.
         *
         * Zeroing the body rather than only re-typing it is the point. A `sinf` carries the key
         * id, the scheme and the IV size of an encryption that no longer applies - metadata that
         * has become a lie, and that a probe reading the file's head would still read as "this
         * is encrypted". The box keeps its size, so every offset in the file stays valid.
         */
        class Strip(val boxStart: Long, val boxEnd: Long, val newType: String) : MoovPatch() {
            override fun apply(bytes: ByteArray, bodyOffset: Long) {
                val start = (boxStart - bodyOffset).toInt()
                val end = (boxEnd - bodyOffset).toInt()
                if (start < 0 || end > bytes.size || end - start < 8) return
                newType.toByteArray(Charsets.ISO_8859_1).copyInto(bytes, start + 4)
                java.util.Arrays.fill(bytes, start + 8, end, 0)
            }
        }

        abstract fun apply(bytes: ByteArray, bodyOffset: Long)
    }

    private class Layout(
        val protected: Boolean,
        val fragmented: Boolean,
        val scheme: String,
        val originalFormat: String,
        /** The metadata blocks carried in `dfLa`, for the native FLAC output. */
        val flacMetadata: ByteArray?,
        /** The per-sample IV size `tenc` declares, when it declares one. */
        val defaultPerSampleIvSize: Int,
        /** A `tenc` constant IV, when the stream uses one instead of `senc` IVs. */
        val constantIv: ByteArray?,
        /** `moov`-level rewrites, in file coordinates. */
        val moovPatches: List<MoovPatch>,
    )

    private fun scanLayout(source: File): Layout {
        if (!source.isFile || source.length() < 16L) {
            throw DecryptionFailedException("encrypted stream is missing or too small")
        }
        RandomAccessFile(source, "r").use { file ->
            val total = file.length()
            var position = 0L
            var inspected = 0
            while (position + 8L <= total && inspected < MAX_TOP_LEVEL_BOXES) {
                inspected++
                val header = readBoxHeader(file, position, total)
                if (header.type == "moov") {
                    if (header.size > MAX_MOOV_ATOM) {
                        throw DecryptionFailedException("moov is implausibly large (${header.size} bytes)")
                    }
                    val bytes = ByteArray(header.size.toInt())
                    file.seek(position)
                    file.readFully(bytes)
                    return parseMoov(bytes, position)
                }
                position += header.size
            }
        }
        throw DecryptionFailedException("no moov box found; cannot tell what was encrypted")
    }

    private fun parseMoov(bytes: ByteArray, moovOffset: Long): Layout {
        var fragmented = false
        var protected = false
        var scheme = ""
        var originalFormat = ""
        var perSampleIvSize = 0
        var constantIv: ByteArray? = null
        var flacMetadata: ByteArray? = null
        val patches = mutableListOf<MoovPatch>()

        eachBox(bytes, 8, bytes.size) { box ->
            when (box.type) {
                "mvex" -> fragmented = true
                // A protection-system header on a file whose samples are plain audio only
                // invites a player to ask for keys before it plays anything.
                "pssh" -> patches += MoovPatch.Strip(
                    boxStart = moovOffset + box.start,
                    boxEnd = moovOffset + box.start + box.size,
                    newType = "free",
                )
                "trak" -> {
                    val entry = findSampleEntry(bytes, box, moovOffset) ?: return@eachBox
                    if (entry.type == "enca" || entry.type == "encv") {
                        protected = true
                        scheme = entry.scheme
                        originalFormat = entry.originalFormat.ifEmpty { "audio" }
                        perSampleIvSize = entry.perSampleIvSize
                        constantIv = entry.constantIv
                        flacMetadata = entry.flacMetadata
                        // The sample entry's fourcc is what tells a player "this is encrypted
                        // audio"; the format named by `frma` is what it should read instead.
                        patches += MoovPatch.Retype(
                            typeOffset = moovOffset + entry.headerStart + 4,
                            newType = originalFormat,
                        )
                        if (entry.sinfStart >= 0) {
                            patches += MoovPatch.Strip(
                                boxStart = moovOffset + entry.sinfStart,
                                boxEnd = moovOffset + entry.sinfEnd,
                                newType = "free",
                            )
                        }
                    } else {
                        originalFormat = entry.type
                    }
                }
            }
        }
        return Layout(
            protected = protected,
            fragmented = fragmented,
            scheme = scheme,
            originalFormat = originalFormat,
            flacMetadata = flacMetadata,
            defaultPerSampleIvSize = perSampleIvSize,
            constantIv = constantIv,
            moovPatches = patches,
        )
    }

    private class SampleEntry(
        val type: String,
        val headerStart: Int,
        val originalFormat: String,
        val scheme: String,
        /** Extent of the `sinf` box inside `moov`, or both -1 when the entry has none. */
        val sinfStart: Int,
        val sinfEnd: Int,
        val constantIv: ByteArray?,
        val perSampleIvSize: Int,
        val flacMetadata: ByteArray?,
    )

    /** Walks `trak/mdia/minf/stbl/stsd` and describes the first sample entry with its `sinf`. */
    private fun findSampleEntry(moov: ByteArray, trak: Box, moovOffset: Long): SampleEntry? {
        var entry: SampleEntry? = null
        eachBox(moov, trak.bodyStart, trak.bodyEnd) { mdia ->
            if (mdia.type != "mdia" || entry != null) return@eachBox
            eachBox(moov, mdia.bodyStart, mdia.bodyEnd) { minf ->
                if (minf.type != "minf" || entry != null) return@eachBox
                eachBox(moov, minf.bodyStart, minf.bodyEnd) { stbl ->
                    if (stbl.type != "stbl" || entry != null) return@eachBox
                    eachBox(moov, stbl.bodyStart, stbl.bodyEnd) { stsd ->
                        if (stsd.type == "stsd") entry = firstStsdEntry(moov, stsd, moovOffset)
                    }
                }
            }
        }
        return entry
    }

    private fun firstStsdEntry(moov: ByteArray, stsd: Box, moovOffset: Long): SampleEntry? {
        // stsd: full box (4) + entry_count (4), then the entries.
        val entryStart = stsd.bodyStart + 8
        if (entryStart + 8 > stsd.bodyEnd) return null
        val type = fourCc(moov, entryStart + 4)
        val entryEnd = entryStart + u32(moov, entryStart).toInt()
        if (entryEnd > stsd.bodyEnd || entryEnd <= entryStart) return null

        // An audio sample entry's children start after 8 bytes of box header, 6 of reserved,
        // 2 of data-reference index and 20 of audio fields.
        val childStart = entryStart + 8 + 8 + 20
        var originalFormat = ""
        var scheme = ""
        var sinfStart = -1
        var sinfEnd = -1
        var perSampleIvSize = 0
        var constantIv: ByteArray? = null
        var flacMetadata: ByteArray? = null

        if (childStart < entryEnd) {
            eachBox(moov, childStart, entryEnd) { child ->
                when (child.type) {
                    "dfLa" -> {
                        // A full box header, then the FLAC metadata blocks.
                        val blocksStart = child.bodyStart + 4
                        if (blocksStart < child.bodyEnd) flacMetadata = moov.copyOfRange(blocksStart, child.bodyEnd)
                    }

                    "sinf" -> {
                        sinfStart = child.start.toInt()
                        sinfEnd = child.bodyEnd
                        eachBox(moov, child.bodyStart, child.bodyEnd) { sinfChild ->
                            when (sinfChild.type) {
                                // `frma` holds the fourcc directly; `schm` is a full box, so its
                                // scheme follows the version/flags word.
                                "frma" -> originalFormat = fourCc(moov, sinfChild.bodyStart)
                                "schm" -> scheme = fourCc(moov, sinfChild.bodyStart + 4)
                                "schi" -> eachBox(moov, sinfChild.bodyStart, sinfChild.bodyEnd) { schiChild ->
                                    if (schiChild.type == "tenc") {
                                        val parsed = parseTenc(moov, schiChild)
                                        constantIv = parsed.first
                                        perSampleIvSize = parsed.second
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return SampleEntry(
            type = type,
            headerStart = entryStart,
            originalFormat = originalFormat,
            scheme = scheme,
            sinfStart = sinfStart,
            sinfEnd = sinfEnd,
            constantIv = constantIv,
            perSampleIvSize = perSampleIvSize,
            flacMetadata = flacMetadata,
        )
    }

    /**
     * `(constant IV, default per-sample IV size)` from a `tenc` box.
     *
     * Only a fallback: `saiz` states the size of the auxiliary information the muxer actually
     * wrote per sample, which is the number that decrypts the file, and providers do not all
     * fill `tenc` in consistently. The layout here is the spec's - version/flags, one reserved
     * byte, `default_isProtected`, `default_Per_Sample_IV_Size`, the key id, then an optional
     * constant IV - and a `tenc` that does not match it simply contributes nothing, which is a
     * safe outcome when `saiz` is present and a refusal when it is not.
     */
    private fun parseTenc(bytes: ByteArray, tenc: Box): Pair<ByteArray?, Int> {
        val start = tenc.bodyStart + 4
        if (start + 20 > tenc.bodyEnd) return null to 0
        val isProtected = bytes[start + 1].toInt() and 0xFF
        val perSampleIvSize = bytes[start + 2].toInt() and 0xFF
        val constantIvSize = bytes[start + 19].toInt() and 0xFF
        if (isProtected == 0) return null to 0
        val constantIvStart = start + 20
        if (constantIvSize in 1..16 && constantIvStart + constantIvSize <= tenc.bodyEnd) {
            return bytes.copyOfRange(constantIvStart, constantIvStart + constantIvSize) to perSampleIvSize
        }
        return null to (perSampleIvSize.takeIf { it in 1..16 } ?: 0)
    }

    /* --------------------------------------------------------------------------------- *
     * The transformation pass.
     * --------------------------------------------------------------------------------- */

    private class Stats(
        val output: Output,
        val fragments: Int,
        val samples: Int,
        val bytes: Long,
    )

    private fun transform(
        source: File,
        repaired: File,
        nativeFlac: File,
        key: ByteArray,
        layout: Layout,
        convention: CounterConvention,
        log: (String) -> Unit,
    ): Stats {
        // A `fLaC` sample entry is Amazon's lossless tier, which the provider asks to be turned
        // into a real `.flac`. Anything else keeps its own container.
        val wantsNativeFlac = layout.originalFormat.equals(FLAC, ignoreCase = true) &&
            layout.flacMetadata?.isNotEmpty() == true
        if (wantsNativeFlac) {
            val metadata = runCatching { nativeFlacMetadata(layout.flacMetadata!!) }
            val attempt = metadata.mapCatching { blocks ->
                BufferedOutputStream(FileOutputStream(nativeFlac), IO_BUFFER).use { output ->
                    output.write(FLAC_MAGIC)
                    output.write(blocks)
                    rewrite(source, output, key, layout, Output.NATIVE_FLAC, convention)
                }
            }
            val stats = attempt.getOrNull()
            if (stats != null) return stats
            nativeFlac.delete()
            log(
                "mov_key: native flac extraction declined (${attempt.exceptionOrNull()?.message}); " +
                    "keeping the repaired container",
            )
        }
        BufferedOutputStream(FileOutputStream(repaired), IO_BUFFER).use { output ->
            return rewrite(source, output, key, layout, Output.REPAIRED_MP4, convention)
        }
    }

    /**
     * One streaming pass producing one of the two outputs.
     *
     * Box sizes are reproduced exactly, so for a repaired container the input and output stay
     * positionally identical; the native FLAC output holds nothing but the decrypted frames.
     */
    private fun rewrite(
        source: File,
        output: OutputStream,
        key: ByteArray,
        layout: Layout,
        mode: Output,
        convention: CounterConvention,
    ): Stats {
        val secret = SecretKeySpec(key, "AES")
        val queue = ArrayDeque<Sample>()
        var position = 0L
        var fragments = 0
        var samples = 0
        var bytes = 0L
        var firstFrameVerified = false

        BufferedInputStream(FileInputStream(source), IO_BUFFER).use { input ->
            while (position + 8L <= source.length()) {
                val header = ByteArray(16)
                readFully(input, header, 0, 8)
                var headerSize = 8
                var size = u32(header, 0)
                if (size == 1L) {
                    readFully(input, header, 8, 8)
                    size = u64(header, 8)
                    headerSize = 16
                } else if (size == 0L) {
                    size = source.length() - position
                }
                if (size < headerSize || position + size > source.length()) {
                    throw DecryptionFailedException("box at $position declares an impossible size ($size)")
                }
                val type = fourCc(header, headerSize - 4)
                val body = size - headerSize

                when (type) {
                    "moov" -> {
                        val bytesRead = readAtom(input, body, MAX_MOOV_ATOM)
                        layout.moovPatches.forEach { it.apply(bytesRead, position + headerSize) }
                        if (mode == Output.REPAIRED_MP4) {
                            output.write(header, 0, headerSize)
                            output.write(bytesRead)
                        }
                    }

                    "moof" -> {
                        val fragment = readAtom(input, body, MAX_FRAGMENT_ATOM)
                        val parsed = parseFragment(fragment, position, layout)
                        if (mode == Output.REPAIRED_MP4) {
                            output.write(header, 0, headerSize)
                            output.write(neutraliseEncryptionBoxes(fragment))
                        }
                        queue.addAll(parsed)
                        if (parsed.isNotEmpty()) fragments++
                    }

                    "mdat" -> {
                        if (mode == Output.REPAIRED_MP4) output.write(header, 0, headerSize)
                        val copied = copySamples(
                            input = input,
                            output = output,
                            payloadStart = position + headerSize,
                            payloadEnd = position + size,
                            queue = queue,
                            secret = secret,
                            convention = convention,
                            nativeFlac = mode == Output.NATIVE_FLAC,
                            onSample = { decrypted ->
                                samples++
                                bytes += decrypted
                            },
                            onFirstProtectedByte = { first, second ->
                                if (mode == Output.NATIVE_FLAC && !firstFrameVerified) {
                                    firstFrameVerified = true
                                    if (!isFlacFrameSync(first, second)) {
                                        throw DecryptionFailedException("decrypted frames are not FLAC frames")
                                    }
                                }
                            },
                        )
                        require(copied >= 0L)
                    }

                    else -> {
                        if (mode == Output.REPAIRED_MP4) {
                            output.write(header, 0, headerSize)
                            copyBytes(input, output, body)
                        } else {
                            skipBytes(input, body)
                        }
                    }
                }
                position += size
            }
        }

        if (queue.isNotEmpty()) {
            throw DecryptionFailedException("${queue.size} encrypted samples were never reached")
        }
        if (samples == 0) {
            throw DecryptionFailedException("the stream announced encryption but carried no samples")
        }
        if (mode == Output.NATIVE_FLAC && !firstFrameVerified) {
            throw DecryptionFailedException("no FLAC frames were produced")
        }
        return Stats(mode, fragments, samples, bytes)
    }

    private fun readAtom(input: InputStream, length: Long, ceiling: Long): ByteArray {
        if (length > ceiling) throw DecryptionFailedException("metadata box of $length bytes is too large")
        val bytes = ByteArray(length.toInt())
        readFully(input, bytes, 0, bytes.size)
        return bytes
    }

    /* --------------------------------------------------------------------------------- *
     * Samples.
     * --------------------------------------------------------------------------------- */

    /** One encrypted sample: where its bytes are and the counter block to start from. */
    private class Sample(
        val offset: Long,
        val size: Int,
        val iv: ByteArray,
        /** Clear/protected ranges, or null when the whole sample is protected. */
        val segments: List<Segment>?,
    ) {
        val end: Long get() = offset + size
    }

    /** A contiguous run inside a sample. Only the protected ones are ciphered. */
    private class Segment(val offset: Long, val length: Int, val protectedBytes: Boolean)

    /**
     * Parses one `moof` into the samples it describes.
     *
     * Both the sample table (`tfhd`/`trun`) and the encryption table (`senc`) are needed: a
     * fragment that announces encryption yet carries no IVs cannot be decrypted, and guessing
     * - a zero IV, a constant one - produces noise that plays, which is the exact failure this
     * path exists to remove.
     */
    private fun parseFragment(fragment: ByteArray, moofStart: Long, layout: Layout): List<Sample> {
        val samples = mutableListOf<Sample>()
        // `fragment` is the moof's body, so the walk starts at its first child - while the
        // sample offsets it produces are file coordinates, based at the moof box itself.
        eachBox(fragment, 0, fragment.size) { traf ->
            if (traf.type != "traf") return@eachBox

            var baseDataOffset: Long? = null
            var defaultBaseIsMoof = false
            var defaultSampleSize = 0
            var truns = emptyList<Trun>()
            var senc: Senc? = null
            var saizDefaultInfoSize = -1

            eachBox(fragment, traf.bodyStart, traf.bodyEnd) { child ->
                when (child.type) {
                    // `saiz` precedes `senc` in the fragments this muxer writes, so the size it
                    // declares is already known when the IVs are read. A stream that ordered them
                    // the other way still works, through `tenc`'s default in `parseSenc`.
                    "tfhd" -> {
                        val flags = u32(fragment, child.start.toInt() + 8) and 0xFFFFFF
                        defaultBaseIsMoof = flags and 0x020000L != 0L
                        // The version/flags word is part of the box body, so the fields this
                        // header declares start after it. Reading them from the unpadded body
                        // start shifts every field by four bytes - and the one that matters,
                        // `default_sample_size`, is what sizes the samples when `trun` does not.
                        var cursor = child.bodyStart + 4
                        if (flags and 0x01L != 0L) {
                            baseDataOffset = u64(fragment, cursor)
                            cursor += 8
                        }
                        cursor += 4 // track_ID
                        if (flags and 0x02L != 0L) cursor += 4 // sample_description_index
                        if (flags and 0x08L != 0L) cursor += 4 // default_sample_duration
                        if (flags and 0x10L != 0L) {
                            defaultSampleSize = u32(fragment, cursor).toInt()
                            cursor += 4
                        }
                    }

                    "trun" -> truns = truns + parseTrun(fragment, child)

                    "senc" -> senc = parseSenc(fragment, child, saizDefaultInfoSize, layout)

                    "saiz" -> if (child.bodyStart + 5 <= child.bodyEnd) {
                        // `default_sample_info_size` follows the version/flags word.
                        saizDefaultInfoSize = fragment[child.bodyStart + 4].toInt() and 0xFF
                    }
                }
            }

            val base = baseDataOffset ?: moofStart
            var cursor = base
            var ordinal = 0
            truns.forEach { trun ->
                if (trun.dataOffset != null) cursor = base + trun.dataOffset
                trun.sizes.forEach { declared ->
                    val size = declared.takeIf { it > 0 } ?: defaultSampleSize
                    val entry = senc?.entries?.getOrNull(ordinal)
                    val iv = entry?.iv ?: layout.constantIv ?: ByteArray(0)
                    if (iv.isEmpty()) {
                        throw DecryptionFailedException(
                            "no initialization vector for sample $ordinal (default-base-is-moof=$defaultBaseIsMoof)",
                        )
                    }
                    samples += Sample(
                        offset = cursor,
                        size = size,
                        iv = iv,
                        segments = buildSegments(cursor, size, entry?.subsamples),
                    )
                    cursor += size
                    ordinal++
                }
            }
        }
        return samples
    }

    private class Trun(val dataOffset: Long?, val sizes: List<Int>)

    private fun parseTrun(fragment: ByteArray, trun: Box): Trun {
        val flags = u32(fragment, trun.start.toInt() + 8) and 0xFFFFFF
        var cursor = trun.bodyStart + 4
        val sampleCount = u32(fragment, cursor).toInt()
        cursor += 4
        var dataOffset: Long? = null
        if (flags and 0x01L != 0L) {
            dataOffset = u32(fragment, cursor)
            cursor += 4
        }
        if (flags and 0x04L != 0L) cursor += 4 // first_sample_flags
        val sizes = ArrayList<Int>(sampleCount.coerceAtLeast(0))
        var index = 0
        while (index < sampleCount && cursor + 4 <= trun.bodyEnd) {
            if (flags and 0x100L != 0L) cursor += 4 // sample_duration
            val size = if (flags and 0x200L != 0L) {
                val value = u32(fragment, cursor).toInt()
                cursor += 4
                value
            } else {
                0
            }
            if (flags and 0x400L != 0L) cursor += 4 // sample_flags
            if (flags and 0x800L != 0L) cursor += 4 // sample_composition_time_offset
            sizes += size
            index++
        }
        return Trun(dataOffset, sizes)
    }

    private class Senc(val entries: List<SencEntry>)

    private class SencEntry(val iv: ByteArray, val subsamples: List<Pair<Int, Int>>?)

    /**
     * The per-sample IVs (and, when the scheme uses them, the clear/protected patterns).
     *
     * The IV size comes from `saiz` because that is the number of auxiliary bytes the muxer
     * really wrote per sample; `tenc` only declares a default, and muxers routinely leave it
     * at zero while still writing IVs.
     */
    private fun parseSenc(
        fragment: ByteArray,
        senc: Box,
        saizDefaultInfoSize: Int,
        layout: Layout,
    ): Senc? {
        if (senc.bodyEnd - senc.bodyStart < 8) return null
        val ivSize = when {
            saizDefaultInfoSize > 0 -> saizDefaultInfoSize
            layout.defaultPerSampleIvSize > 0 -> layout.defaultPerSampleIvSize
            else -> 0
        }
        if (ivSize <= 0 || ivSize > 16) return null
        val flags = u32(fragment, senc.start.toInt() + 8) and 0xFFFFFF
        var cursor = senc.bodyStart + 4
        val count = u32(fragment, cursor).toInt()
        cursor += 4
        val entries = ArrayList<SencEntry>(count.coerceAtLeast(0))
        var index = 0
        while (index < count) {
            if (cursor + ivSize > senc.bodyEnd) {
                throw DecryptionFailedException("senc ran out of initialization vectors at sample $index")
            }
            val iv = fragment.copyOfRange(cursor, cursor + ivSize)
            cursor += ivSize
            var subsamples: List<Pair<Int, Int>>? = null
            if (flags and 0x02L != 0L) {
                if (cursor + 2 > senc.bodyEnd) {
                    throw DecryptionFailedException("senc subsample table is truncated")
                }
                val subsampleCount = u16(fragment, cursor)
                cursor += 2
                val ranges = ArrayList<Pair<Int, Int>>(subsampleCount)
                repeat(subsampleCount) {
                    if (cursor + 6 > senc.bodyEnd) {
                        throw DecryptionFailedException("senc subsample entry is truncated")
                    }
                    ranges += u16(fragment, cursor) to u32(fragment, cursor + 2).toInt()
                    cursor += 6
                }
                subsamples = ranges
            }
            entries += SencEntry(iv, subsamples)
            index++
        }
        return Senc(entries)
    }

    /**
     * A sample as alternating clear/protected runs.
     *
     * The runs matter because CENC's counter keeps advancing across a sample's protected runs
     * and resets at the next sample; null means the whole sample is protected, which is the
     * common case and the one the native FLAC output requires.
     */
    private fun buildSegments(
        sampleStart: Long,
        sampleSize: Int,
        subsamples: List<Pair<Int, Int>>?,
    ): List<Segment>? {
        if (subsamples.isNullOrEmpty()) return null
        val segments = mutableListOf<Segment>()
        var cursor = sampleStart
        var consumed = 0
        subsamples.forEach { (clear, protectedBytes) ->
            if (clear > 0) {
                segments += Segment(cursor, clear, false)
                cursor += clear
                consumed += clear
            }
            if (protectedBytes > 0) {
                segments += Segment(cursor, protectedBytes, true)
                cursor += protectedBytes
                consumed += protectedBytes
            }
        }
        if (consumed < sampleSize) {
            segments += Segment(cursor, sampleSize - consumed, false)
        }
        return segments
    }

    /**
     * Copies one `mdat`, decrypting the samples inside it.
     *
     * The queue is shared across `mdat` boxes because a fragment's samples are consumed in
     * order as their bytes stream past: only one fragment's worth of sample metadata is ever
     * held, no matter how long the file is.
     */
    private fun copySamples(
        input: InputStream,
        output: OutputStream,
        payloadStart: Long,
        payloadEnd: Long,
        queue: ArrayDeque<Sample>,
        secret: SecretKeySpec,
        convention: CounterConvention,
        nativeFlac: Boolean,
        onSample: (Long) -> Unit,
        onFirstProtectedByte: (Int, Int) -> Unit,
    ): Long {
        val buffer = ByteArray(IO_BUFFER)
        var position = payloadStart
        var active: Sample? = null
        var cipher: Cipher? = null
        var writtenForSample = 0L
        var firstProtectedByteSeen = false

        while (position < payloadEnd) {
            val want = minOf(buffer.size.toLong(), payloadEnd - position).toInt()
            val read = input.read(buffer, 0, want)
            if (read <= 0) throw DecryptionFailedException("mdat ended early at $position")
            var index = 0
            while (index < read) {
                val absolute = position + index
                val sample = queue.peekFirst()
                when {
                    sample == null -> {
                        if (!nativeFlac) output.write(buffer, index, read - index)
                        index = read
                    }

                    absolute < sample.offset -> {
                        val n = minOf((read - index).toLong(), sample.offset - absolute).toInt()
                        if (!nativeFlac) output.write(buffer, index, n)
                        index += n
                    }

                    absolute >= sample.end -> queue.removeFirst()

                    else -> {
                        if (active !== sample) {
                            if (nativeFlac && sample.segments != null) {
                                // Frames spliced out of a subsample-encrypted sample would need
                                // the clear bytes interleaved exactly as the muxer laid them out;
                                // declining is what keeps the audio honest.
                                throw DecryptionFailedException("subsample encryption cannot be spliced into FLAC")
                            }
                            active = sample
                            cipher = cipherFor(secret, sample.iv, convention)
                            writtenForSample = 0L
                        }
                        val into = (absolute - sample.offset).toInt()
                        val segment = segmentAt(sample, into)
                        val segmentEnd = segment.offset + segment.length
                        val n = minOf((read - index).toLong(), segmentEnd - absolute).toInt()
                        val protectedBytes = segment.protectedBytes && cipher != null
                        if (protectedBytes) {
                            cipher!!.update(buffer, index, n, buffer, index)
                        }
                        if (!nativeFlac || protectedBytes) {
                            if (protectedBytes && !firstProtectedByteSeen) {
                                firstProtectedByteSeen = true
                                onFirstProtectedByte(
                                    buffer[index].toInt() and 0xFF,
                                    if (n > 1) buffer[index + 1].toInt() and 0xFF else -1,
                                )
                            }
                            output.write(buffer, index, n)
                        }
                        writtenForSample += n
                        index += n
                        if (into + n >= sample.size) {
                            queue.removeFirst()
                            active = null
                            cipher = null
                            onSample(writtenForSample)
                        }
                    }
                }
            }
            position += read
        }
        return position - payloadStart
    }

    /** One cipher per sample: CENC resets the counter at every sample boundary. */
    private fun cipherFor(
        secret: SecretKeySpec,
        iv: ByteArray,
        convention: CounterConvention,
    ): Cipher? {
        if (iv.isEmpty()) return null
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret, IvParameterSpec(counterBlock(iv, convention)))
        return cipher
    }

    /**
     * The 16-byte counter block for a per-sample IV.
     *
     * CENC defines the IV as the initial counter block, and an 8-byte IV occupies its low half
     * with the high half zeroed - which is what `ffmpeg -decryption_key` does, so a stream
     * decrypted here matches one decrypted by the tool the provider named.
     */
    private fun counterBlock(iv: ByteArray, convention: CounterConvention): ByteArray {
        val block = ByteArray(16)
        if (iv.size >= 16) {
            iv.copyInto(block, 0, 0, 16)
            return block
        }
        when (convention) {
            CounterConvention.LEADING_IV -> iv.copyInto(block, 0)
            CounterConvention.TRAILING_IV -> iv.copyInto(block, 16 - iv.size)
        }
        return block
    }

    private fun segmentAt(sample: Sample, offsetInSample: Int): Segment {
        val segments = sample.segments
            ?: return Segment(sample.offset + offsetInSample, sample.size - offsetInSample, true)
        var consumed = 0
        segments.forEach { segment ->
            if (offsetInSample < consumed + segment.length) return segment
            consumed += segment.length
        }
        return Segment(sample.offset + offsetInSample, sample.size - offsetInSample, false)
    }

    /**
     * Rewrites the encryption signalling inside one `moof` to emptied `free` boxes, in place.
     *
     * Same sizes, so nothing shifts. `senc` (the per-sample IVs), `saiz` (how big each entry is)
     * and `saio` (where they live) mean nothing once the bytes they describe are plain audio, and
     * leaving them in is what would make a player wait for a key that no longer applies. Their
     * bodies are cleared as well - one with the file's own IVs in it - because metadata about an
     * encryption that has been undone is at best dead weight and at worst read as "still
     * encrypted" by a probe of the file's head.
     */
    private fun neutraliseEncryptionBoxes(fragment: ByteArray): ByteArray {
        val copy = fragment.copyOf()
        fun walk(start: Int, end: Int) {
            var cursor = start
            while (cursor + 8 <= end) {
                val size = u32(fragment, cursor).toInt()
                val type = fourCc(fragment, cursor + 4)
                if (size < 8 || cursor + size > end) return
                when (type) {
                    "senc", "saiz", "saio" -> {
                        FREE.copyInto(copy, cursor + 4)
                        java.util.Arrays.fill(copy, cursor + 8, cursor + size, 0)
                    }

                    "traf" -> walk(cursor + 8, cursor + size)
                }
                cursor += size
            }
        }
        // `fragment` is the moof's body, so its first child is at zero.
        walk(0, fragment.size)
        return copy
    }

    /* --------------------------------------------------------------------------------- *
     * FLAC specifics.
     * --------------------------------------------------------------------------------- */

    private val FLAC_MAGIC = "fLaC".toByteArray(Charsets.ISO_8859_1)
    private val FREE = "free".toByteArray(Charsets.ISO_8859_1)

    /**
     * The `dfLa` metadata blocks, laid out as a native FLAC metadata section.
     *
     * The blocks are copied byte for byte - which is what keeps the STREAMINFO and its MD5 the
     * encoder's - with one correction: the last-metadata-block flag belongs on the final block
     * of the stream, and muxers are not consistent about setting it inside `dfLa`. Any trailing
     * bytes after the last parsed block are dropped rather than emitted as metadata, which
     * would corrupt the stream.
     */
    private fun nativeFlacMetadata(raw: ByteArray): ByteArray {
        val headers = mutableListOf<Int>()
        var cursor = 0
        var end = 0
        while (cursor + 4 <= raw.size) {
            val length = ((raw[cursor + 1].toInt() and 0xFF) shl 16) or
                ((raw[cursor + 2].toInt() and 0xFF) shl 8) or
                (raw[cursor + 3].toInt() and 0xFF)
            if (cursor + 4 + length > raw.size) break
            headers += cursor
            cursor += 4 + length
            end = cursor
        }
        if (headers.isEmpty() || end <= 0) {
            throw DecryptionFailedException("dfLa carries no metadata blocks")
        }
        if ((raw[headers.first()].toInt() and 0x7F) != 0) {
            throw DecryptionFailedException("dfLa does not start with STREAMINFO")
        }
        val blocks = raw.copyOf(end)
        headers.forEachIndexed { index, header ->
            val value = blocks[header].toInt()
            blocks[header] = if (index == headers.lastIndex) {
                (value or 0x80).toByte()
            } else {
                (value and 0x7F).toByte()
            }
        }
        return blocks
    }

    /** A FLAC frame starts with the 14-bit sync code `11111111111110`. */
    private fun isFlacFrameSync(first: Int, second: Int): Boolean =
        first == 0xFF && second >= 0 && (second and 0xFC) == 0xF8

    /* --------------------------------------------------------------------------------- *
     * Box walkers and numbers.
     * --------------------------------------------------------------------------------- */

    /** Iterates the boxes between [start] and [end] of [bytes]. */
    private inline fun eachBox(
        bytes: ByteArray,
        start: Int,
        end: Int,
        action: (Box) -> Unit,
    ) {
        var cursor = start
        while (cursor + 8 <= end) {
            var size = u32(bytes, cursor)
            var headerSize = 8
            if (size == 1L) {
                if (cursor + 16 > end) return
                size = u64(bytes, cursor + 8)
                headerSize = 16
            } else if (size == 0L) {
                size = (end - cursor).toLong()
            }
            if (size < headerSize || cursor + size > end) return
            action(
                Box(
                    type = fourCc(bytes, cursor + headerSize - 4),
                    start = cursor.toLong(),
                    headerSize = headerSize,
                    bodyStart = cursor + headerSize,
                    bodyEnd = (cursor + size).toInt(),
                    size = size,
                ),
            )
            cursor += size.toInt()
        }
    }

    private class Header(val type: String, val size: Long, val headerSize: Int)

    private fun readBoxHeader(file: RandomAccessFile, start: Long, total: Long): Header {
        val header = ByteArray(16)
        file.seek(start)
        file.readFully(header)
        val size32 = u32(header, 0)
        return when {
            size32 == 1L -> Header(fourCc(header, 12), u64(header, 8), 16)
            size32 == 0L -> Header(fourCc(header, 4), total - start, 8)
            else -> Header(fourCc(header, 4), size32, 8)
        }
    }

    private fun copyBytes(input: InputStream, output: OutputStream, length: Long) {
        val buffer = ByteArray(IO_BUFFER)
        var remaining = length
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) throw DecryptionFailedException("file ended mid-box")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun skipBytes(input: InputStream, length: Long) {
        val buffer = ByteArray(IO_BUFFER)
        var remaining = length
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) throw DecryptionFailedException("file ended mid-box")
            remaining -= read
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var done = 0
        while (done < length) {
            val read = input.read(buffer, offset + done, length - done)
            if (read <= 0) throw DecryptionFailedException("file ended early")
            done += read
        }
    }

    /**
     * The key bytes a provider's `decryption_key` names.
     *
     * Hex is what the contract means - it mirrors ffmpeg's `-decryption_key` - and a 16-byte
     * string is accepted as its own bytes so a provider answering with the raw key still
     * works. Anything else is refused rather than guessed at: a wrong key produces noise that
     * plays, which is the failure this whole path exists to remove.
     */
    private fun decodeKey(keyHex: String?): ByteArray? {
        val raw = keyHex ?: return null
        val hex = raw.trim().removePrefix("0x").removePrefix("0X")
        if (hex.length == 32 && hex.all { isHexDigit(it) }) {
            val bytes = ByteArray(16)
            for (index in bytes.indices) {
                bytes[index] = ((hexDigit(hex[index * 2]) shl 4) or hexDigit(hex[index * 2 + 1])).toByte()
            }
            return bytes
        }
        // A raw key is used exactly as it arrives: trimming it would silently shorten a key
        // whose last byte is whitespace, and a shortened key decrypts to noise that plays.
        return raw.toByteArray(Charsets.ISO_8859_1).takeIf { it.size == 16 }
    }

    private fun isHexDigit(character: Char): Boolean =
        character.isDigit() || character in 'a'..'f' || character in 'A'..'F'

    private fun hexDigit(character: Char): Int = when (character) {
        in '0'..'9' -> character - '0'
        in 'a'..'f' -> character - 'a' + 10
        else -> character - 'A' + 10
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        if (offset + 2 > bytes.size) {
            0
        } else {
            ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
        }

    private fun u32(bytes: ByteArray, offset: Int): Long =
        if (offset + 4 > bytes.size) {
            0L
        } else {
            ((bytes[offset].toLong() and 0xFF) shl 24) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
                (bytes[offset + 3].toLong() and 0xFF)
        }

    private fun u64(bytes: ByteArray, offset: Int): Long =
        (u32(bytes, offset) shl 32) or u32(bytes, offset + 4)

    private fun fourCc(bytes: ByteArray, offset: Int): String =
        if (offset + 4 > bytes.size) "" else String(bytes, offset, 4, Charsets.ISO_8859_1)
}
