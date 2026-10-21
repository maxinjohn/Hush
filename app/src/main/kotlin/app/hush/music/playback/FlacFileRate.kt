/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 and Section 5
 */

package app.hush.music.playback

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

/**
 * What a FLAC file's own header says about the audio inside it.
 *
 * [totalSamples] is the one fact that exists nowhere else in the app: a FLAC stream carries its exact
 * sample count, so the file's duration is a measurement rather than an estimate. [audioBytes] is the
 * part of the file that is audio - everything after the metadata blocks - so a file with a cover art
 * embedded in it is not credited with that cover art's bytes as bitrate.
 */
data class FlacStreamInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val totalSamples: Long,
    val audioBytes: Long = 0L,
) {
    /** Exact duration of the stream, from its own sample count. Zero when the count is unknown. */
    val durationMs: Long
        get() =
            if (sampleRate > 0 && totalSamples > 0L) {
                totalSamples * 1_000L / sampleRate
            } else {
                0L
            }
}

/**
 * The real bitrate of a FLAC file on this device, read from the file itself.
 *
 * The player's codec row had nothing to say about a device-file FLAC's rate, and both figures that
 * were tried before were wrong: the SpotiFLAC cache entry's `sampleRate` does not describe the file
 * (measured on device: three FLAC entries claimed `29539`, which is not a sample rate), and
 * `sampleRate * bitDepth * 2` describes a decode format rather than the bytes on disk, which is why a
 * 24-bit file was credited with a number no file of its size could have. Media3 does not fill the gap
 * either: its FLAC extractor reports no bitrate, because a VBR lossless file's rate is not in any
 * frame header. So the codec row read `FLAC - Unknown` for every lossless file on disk.
 *
 * The file does know. `STREAMINFO` - the mandatory first metadata block - carries the sample rate and
 * the exact sample count, and the file carries its own length, so
 *
 *     bitrate = audio bytes x 8 / (total samples / sample rate)
 *
 * is a measurement of the bytes being played rather than a guess about them. It is the *average* rate
 * of the file, which is the only figure a VBR file has and the one every player reports.
 *
 * Deliberately narrow: nothing is read from the audio frames, no decoder is opened, and a file whose
 * header does not parse yields no rate rather than a plausible one. Reading one file costs a handful
 * of seeks and one 34-byte read of the metadata chain, so a caller may do it while deciding what to
 * serve.
 *
 * Only FLAC is covered because only FLAC states its total sample count in a header. An Opus or AAC
 * file's rate is already in the row written when it was fetched, and deriving one from a file length
 * and an approximate duration would be the same mistake this replaces.
 */
object FlacFileRate {
    /** The magic every FLAC stream starts with. */
    private val MAGIC = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())

    /** `STREAMINFO` is always the first metadata block and its body is exactly 34 bytes. */
    private const val STREAMINFO_BODY_BYTES = 34

    /**
     * A lid on the metadata chain, so a malformed file cannot keep the reader seeking.
     *
     * A real chain is a handful of blocks - `STREAMINFO`, a picture, a tag block, a seek table - and
     * this is far beyond any of them while still bounding the walk.
     */
    private const val MAX_METADATA_BLOCKS = 128

    /**
     * The average bitrate of the FLAC file at [file], or null when it is not a FLAC file, is too small
     * to hold a header, or does not state its sample count.
     *
     * Never throws: a file that is being written, has been truncated, or sits on a volume that has
     * gone away is a reason to show no rate, not a reason for playback to fail.
     */
    fun bitrateBpsOf(file: File): Int? =
        runCatching { streamInfoOf(file)?.let { info -> bitrateBps(info) } }.getOrNull()

    /** The header's own account of the stream, or null when [file] is not a readable FLAC file. */
    fun streamInfoOf(file: File): FlacStreamInfo? {
        if (!file.isFile) return null
        val length = file.length()
        if (length <= 0L) return null
        return runCatching { readStreamInfo(file, length) }.getOrNull()
    }

    /**
     * The rate the header's numbers imply, in bits per second.
     *
     * `totalSamples` and `sampleRate` are the whole calculation, so a stream that does not state a
     * sample count (the format allows zero, meaning "unknown" while a stream is still being written)
     * has no rate to report.
     */
    fun bitrateBps(info: FlacStreamInfo): Int? {
        if (info.audioBytes <= 0L) return null
        if (info.sampleRate <= 0 || info.totalSamples <= 0L) return null
        val seconds = info.totalSamples.toDouble() / info.sampleRate.toDouble()
        if (seconds <= 0.0) return null
        val bps = (info.audioBytes.toDouble() * 8.0 / seconds).roundToInt()
        return bps.takeIf { it > 0 }
    }

    /**
     * Reads the metadata chain, not the audio, and answers what `STREAMINFO` says plus where the audio
     * begins.
     *
     * Blocks are walked by seeking rather than by reading in between, because a picture block can be
     * larger than any buffer worth holding for this: only each block's four-byte header and the
     * 34-byte `STREAMINFO` body are ever read.
     */
    private fun readStreamInfo(
        file: File,
        length: Long,
    ): FlacStreamInfo? {
        RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(MAGIC.size)
            if (raf.read(magic) != magic.size) return null
            if (!magic.contentEquals(MAGIC)) return null

            var position = MAGIC.size.toLong()
            var header: FlacStreamInfo? = null
            var audioStart: Long? = null
            var blocks = 0
            while (blocks < MAX_METADATA_BLOCKS) {
                blocks++
                raf.seek(position)
                val blockHeader = ByteArray(4)
                if (raf.read(blockHeader) != blockHeader.size) break
                val isLast = (blockHeader[0].toInt() and 0x80) != 0
                val type = blockHeader[0].toInt() and 0x7F
                val blockLength =
                    ((blockHeader[1].toInt() and 0xFF) shl 16) or
                        ((blockHeader[2].toInt() and 0xFF) shl 8) or
                        (blockHeader[3].toInt() and 0xFF)
                val bodyStart = position + blockHeader.size
                if (type == 0 && header == null && blockLength >= STREAMINFO_BODY_BYTES) {
                    raf.seek(bodyStart)
                    val body = ByteArray(STREAMINFO_BODY_BYTES)
                    if (raf.read(body) == body.size) header = parseStreamInfoBody(body)
                }
                position = bodyStart + blockLength
                if (isLast) {
                    audioStart = position
                    break
                }
            }

            val fields = header ?: return null
            // A chain whose terminator fell outside the file is a truncated file; the header it did
            // carry is still true, and the audio is then whatever follows it.
            val start = audioStart ?: position
            return fields.copy(audioBytes = (length - start).coerceAtLeast(0L))
        }
    }

    /**
     * The 34-byte `STREAMINFO` body, per the format's own layout: two 16-bit block sizes, two 24-bit
     * frame sizes, then one 64-bit field holding a 20-bit sample rate, three bits of channels minus
     * one, five bits of bits-per-sample minus one, and the 36-bit sample count.
     *
     * [FlacStreamInfo.audioBytes] is left at zero here: this function only knows the header, and the
     * caller fills the length in once it has walked the chain.
     */
    fun parseStreamInfoBody(body: ByteArray): FlacStreamInfo? {
        if (body.size < STREAMINFO_BODY_BYTES) return null
        val offset = 2 + 2 + 3 + 3
        var packed = 0L
        for (index in offset until offset + 8) {
            packed = (packed shl 8) or (body[index].toLong() and 0xFF)
        }
        return FlacStreamInfo(
            sampleRate = ((packed ushr 44) and 0xFFFFFL).toInt(),
            channels = (((packed ushr 41) and 0x7L).toInt()) + 1,
            bitsPerSample = (((packed ushr 36) and 0x1FL).toInt()) + 1,
            totalSamples = packed and 0xFFFFFFFFFL,
            audioBytes = 0L,
        )
    }
}
