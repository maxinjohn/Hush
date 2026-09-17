/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import java.io.ByteArrayOutputStream
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The decryptor is judged on bytes, because the failure it exists to remove is a file that
 * looks perfect and plays silence: a test that only asserted "no exception" would pass on
 * exactly the behaviour the listener reported.
 *
 * Every fixture is a real fragmented CENC stream - `moov` with an `enca` sample entry, `moof`
 * fragments carrying `senc` IVs, `mdat` payloads encrypted here with the same CENC counter
 * convention (`0^8 || IV`) that `ffmpeg -decryption_key` uses - so decryption is proven
 * against ciphertext rather than against a mock.
 */
class SpotiFLACMovKeyDecryptorTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val key = ByteArray(16) { (0x10 + it).toByte() }
    private val keyHex = key.joinToString("") { "%02x".format(it) }

    /* --------------------------------------------------------------------------------- *
     * Repaired containers.
     * --------------------------------------------------------------------------------- */

    @Test
    fun `decrypts every sample and repairs an Opus container`() {
        val sizes = listOf(listOf(64, 32, 100), listOf(48, 96))
        val plaintext = sizes.flatten().map { opusSample(it) }
        val file = write(
            "track.m4a",
            encryptedFixture(
                originalFormat = "Opus",
                codecBox = box("dOps", ByteArray(11)),
                fragments = sizes,
                plaintext = plaintext,
            ),
        )

        val result = SpotiFLACMovKeyDecryptor.decrypt(file, keyHex, ".m4a")

        assertEquals(SpotiFLACMovKeyDecryptor.Output.REPAIRED_MP4, result.output)
        assertEquals("Opus", result.originalFormat)
        assertEquals(5, result.samplesDecrypted)
        assertEquals(2, result.fragmentsDecrypted)
        // One file, under the name the provider wrote: nothing is left duplicated on disk.
        assertEquals(file.name, result.file.name)
        assertEquals(plaintext.sumOf { it.size }.toLong(), result.bytesDecrypted)
        assertEquals(
            "the ecosystem's counter convention is used when a stream confirms it",
            SpotiFLACMovKeyDecryptor.CounterConvention.LEADING_IV,
            result.counterConvention,
        )

        val bytes = result.file.readBytes()
        assertArrayEquals(concatAll(plaintext), mdatPayload(bytes))

        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse("the encrypted sample entry must be renamed", text.contains("enca"))
        assertFalse("the protection boxes must be gone", text.contains("sinf"))
        assertFalse("the sample encryption table must be gone", text.contains("senc"))
        assertFalse("the auxiliary info box must be gone", text.contains("saiz"))
        assertFalse("the auxiliary offset box must be gone", text.contains("saio"))
        assertFalse("the protection-system header must be gone", text.contains("pssh"))
        assertTrue("the original format must be restored", text.contains("Opus"))
        // A repaired file has to be recognisable as audio, or the cache would refuse to serve it.
        assertTrue(SpotiFLACFileIntegrity.looksLikeAudio(bytes.copyOf(SpotiFLACFileIntegrity.PROBE_BYTES)))
    }

    @Test
    fun `respects the clear and protected runs of subsample encryption`() {
        // Audio that leaves part of each sample in the clear: the keystream advances across a
        // sample's protected runs and resets at the next sample, so clear bytes must come out
        // byte-identical while protected ones are deciphered.
        val sizes = listOf(listOf(120, 80))
        val plaintext = sizes.flatten().map { opusSample(it) }
        val file = write(
            "subsample.m4a",
            encryptedFixture(
                originalFormat = "Opus",
                codecBox = box("dOps", ByteArray(11)),
                fragments = sizes,
                plaintext = plaintext,
                subsampleRuns = listOf(listOf(listOf(16 to 104), listOf(0 to 80))),
            ),
        )

        val result = SpotiFLACMovKeyDecryptor.decrypt(file, keyHex, ".m4a")

        assertEquals(SpotiFLACMovKeyDecryptor.Output.REPAIRED_MP4, result.output)
        assertEquals(2, result.samplesDecrypted)
        assertArrayEquals(concatAll(plaintext), mdatPayload(result.file.readBytes()))
    }

    /* --------------------------------------------------------------------------------- *
     * The lossless tier.
     * --------------------------------------------------------------------------------- */

    @Test
    fun `writes a native flac for a fLaC stream and removes the container`() {
        val frames = listOf(frame(180, 0xF8), frame(240, 0xF9))
        val streamInfo = ByteArray(34) { (it + 1).toByte() }
        val file = write(
            "lossless.m4a",
            encryptedFixture(
                originalFormat = "fLaC",
                codecBox = box("dfLa", ByteArray(4) + flacBlock(0x00, streamInfo)),
                fragments = listOf(frames.map { it.size }),
                plaintext = frames,
                // Deliberately the convention this project's runtime does not default to: the
                // stream's own frame structure has to establish which one it needs.
                convention = SpotiFLACMovKeyDecryptor.CounterConvention.TRAILING_IV,
            ),
        )

        val result = SpotiFLACMovKeyDecryptor.decrypt(file, keyHex, "")

        assertEquals(SpotiFLACMovKeyDecryptor.Output.NATIVE_FLAC, result.output)
        assertEquals("fLaC", result.originalFormat)
        assertEquals(
            "the convention the stream needs is discovered from its frames",
            SpotiFLACMovKeyDecryptor.CounterConvention.TRAILING_IV,
            result.counterConvention,
        )
        assertTrue("the output is a real flac", result.file.name.endsWith(".flac"))
        assertFalse("the encrypted container is gone", file.exists())

        val bytes = result.file.readBytes()
        assertEquals("fLaC", String(bytes, 0, 4, Charsets.ISO_8859_1))
        // STREAMINFO with the last-metadata-block flag set: one block, so it is the last one.
        assertEquals(0, bytes[4].toInt() and 0x7F)
        assertEquals(0x80, bytes[4].toInt() and 0x80)
        assertArrayEquals(streamInfo, bytes.copyOfRange(8, 42))
        assertEquals(frames.size, result.samplesDecrypted)
        assertArrayEquals(concatAll(frames), bytes.copyOfRange(42, bytes.size))
        // What the app probes has to be recognisable as the lossless format it claims to be.
        assertEquals("flac", SpotiFLACFileIntegrity.containerOf(bytes.copyOf(SpotiFLACFileIntegrity.HEAD_BYTES)))
    }

    @Test
    fun `refuses a flac stream whose decrypted frames are not flac frames`() {
        // The frames are the only evidence that a key and a counter convention are right, so a
        // lossless stream whose "decrypted" samples carry no FLAC sync is not written out as a file
        // that plays noise: it is reported, and the sweep moves to the next source.
        val plaintext = listOf(sample(100), sample(120))
        val file = write(
            "notflac.m4a",
            encryptedFixture(
                originalFormat = "fLaC",
                codecBox = box("dfLa", ByteArray(4) + flacBlock(0x00, ByteArray(34))),
                fragments = listOf(plaintext.map { it.size }),
                plaintext = plaintext,
                convention = SpotiFLACMovKeyDecryptor.CounterConvention.TRAILING_IV,
            ),
        )

        val failure = runCatching {
            SpotiFLACMovKeyDecryptor.decrypt(file, keyHex, "")
        }.exceptionOrNull()

        assertTrue(
            "a decryption that cannot be confirmed is a failure, not a file",
            failure is SpotiFLACMovKeyDecryptor.DecryptionFailedException,
        )
        assertTrue("the reason names the stream", failure!!.message!!.contains("not fLaC audio"))
        assertTrue("the encrypted source is left alone", file.exists())
        assertFalse("nothing was written", file.parentFile!!.listFiles()!!.any { it.name.endsWith(".flac") })
    }

    @Test
    fun `refuses a subsample-encrypted flac stream rather than splicing it`() {
        // The frames of a subsample-encrypted sample carry clear bytes whose positions the
        // container chose; splicing them into a native FLAC would quietly drop or reorder
        // audio, so the repaired container is kept instead.
        val plaintext = listOf(frame(64, 0xF8))
        val file = write(
            "subflac.m4a",
            encryptedFixture(
                originalFormat = "fLaC",
                codecBox = box("dfLa", ByteArray(4) + flacBlock(0x00, ByteArray(34))),
                fragments = listOf(listOf(64)),
                plaintext = plaintext,
                subsampleRuns = listOf(listOf(listOf(8 to 56))),
            ),
        )

        val result = SpotiFLACMovKeyDecryptor.decrypt(file, keyHex, "")

        assertEquals(SpotiFLACMovKeyDecryptor.Output.REPAIRED_MP4, result.output)
        assertArrayEquals(concatAll(plaintext), mdatPayload(result.file.readBytes()))
    }

    /* --------------------------------------------------------------------------------- *
     * What must be refused.
     * --------------------------------------------------------------------------------- */

    @Test
    fun `refuses a key that is not a 16-byte key`() {
        assertFalse(SpotiFLACMovKeyDecryptor.isUsableKey(null))
        assertFalse(SpotiFLACMovKeyDecryptor.isUsableKey(""))
        assertFalse(SpotiFLACMovKeyDecryptor.isUsableKey("abc"))
        assertFalse(SpotiFLACMovKeyDecryptor.isUsableKey("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"))
        assertTrue(SpotiFLACMovKeyDecryptor.isUsableKey(keyHex))
        assertTrue(SpotiFLACMovKeyDecryptor.isUsableKey(keyHex.uppercase()))
        assertTrue(SpotiFLACMovKeyDecryptor.isUsableKey("0x$keyHex"))
        assertTrue(SpotiFLACMovKeyDecryptor.isUsableKey(String(key, Charsets.ISO_8859_1)))

        val file = write(
            "track.m4a",
            encryptedFixture("Opus", box("dOps", ByteArray(11)), listOf(listOf(64)), listOf(sample(64))),
        )
        val failure = runCatching { SpotiFLACMovKeyDecryptor.decrypt(file, "nope") }.exceptionOrNull()
        assertTrue(
            "an unusable key is reported as such",
            failure is SpotiFLACMovKeyDecryptor.DecryptionFailedException,
        )
        assertTrue("the source is left alone", file.exists())
    }

    @Test
    fun `leaves a clear stream alone when the provider sent a key anyway`() {
        val plaintext = sample(64)
        val clear = write("clear.m4a", clearFixture("fLaC", plaintext))

        val result = SpotiFLACMovKeyDecryptor.decrypt(clear, keyHex, "")

        assertEquals(SpotiFLACMovKeyDecryptor.Output.REPAIRED_MP4, result.output)
        assertEquals(0, result.samplesDecrypted)
        assertEquals("clear.m4a", result.file.name)
        assertArrayEquals(clear.readBytes(), result.file.readBytes())
    }

    /* --------------------------------------------------------------------------------- *
     * Fixtures.
     * --------------------------------------------------------------------------------- */

    private fun write(name: String, fixture: ByteArray): File =
        folder.newFile(name).also { it.writeBytes(fixture) }

    private fun sample(size: Int): ByteArray = ByteArray(size) { (it * 7 + 3).toByte() }

    /** A plaintext sample that begins with the FLAC frame sync code. */
    private fun frame(size: Int, syncHigh: Int): ByteArray = sample(size).also {
        it[0] = 0xFF.toByte()
        it[1] = syncHigh.toByte()
    }

    /**
     * A plaintext sample that begins with a valid Opus TOC byte.
     *
     * The decryption is verified against the codec's own frame structure, so a fixture has to look
     * like its codec at the first byte - which is exactly the property that makes a wrong key or a
     * wrong counter convention detectable.
     */
    private fun opusSample(size: Int): ByteArray = sample(size).also { it[0] = 0xF8.toByte() }

    /**
     * A fragmented encrypted ISO-BMFF: `ftyp`, `moov` with an `enca` sample entry, one
     * `moof`/`mdat` pair per fragment, and samples encrypted with CENC's counter convention.
     */
    private fun encryptedFixture(
        originalFormat: String,
        codecBox: ByteArray,
        fragments: List<List<Int>>,
        plaintext: List<ByteArray>,
        subsampleRuns: List<List<List<Pair<Int, Int>>>>? = null,
        convention: SpotiFLACMovKeyDecryptor.CounterConvention = SpotiFLACMovKeyDecryptor.CounterConvention.LEADING_IV,
    ): ByteArray {
        val moov = box(
            "moov",
            box("mvhd", ByteArray(8)),
            box(
                "trak",
                box("mdia", box("minf", box("stbl", box("stsd", fullBoxBody(0, 0, u32(1) + encaEntry(originalFormat, codecBox)))))),
            ),
            box("mvex", box("trex", ByteArray(24))),
            box("pssh", ByteArray(16)),
        )

        val out = ByteArrayOutputStream()
        out.write(box("ftyp", "mp41".toByteArray() + u32(0) + "iso8".toByteArray() + "mp41".toByteArray()))
        out.write(moov)

        var sampleIndex = 0
        fragments.forEachIndexed { fragmentIndex, sizes ->
            val runs = subsampleRuns?.getOrNull(fragmentIndex)
            val encrypted = sizes.mapIndexed { index, _ ->
                val iv = ByteArray(8) { (fragmentIndex * 31 + index + 1).toByte() }
                val source = plaintext[sampleIndex]
                val sampleRuns = runs?.getOrNull(index)
                val payload = if (sampleRuns == null) {
                    ctr(iv, source, convention)
                } else {
                    encryptRuns(iv, source, sampleRuns, convention)
                }
                sampleIndex++
                EncryptedSample(payload, iv, sampleRuns)
            }
            out.write(buildFragment(encrypted, sizes))
        }
        return out.toByteArray()
    }

    private class EncryptedSample(
        val payload: ByteArray,
        val iv: ByteArray,
        val runs: List<Pair<Int, Int>>?,
    )

    private fun buildFragment(samples: List<EncryptedSample>, sizes: List<Int>): ByteArray {
        val payload = ByteArrayOutputStream().also { stream ->
            samples.forEach { stream.write(it.payload) }
        }.toByteArray()
        val subSampled = samples.first().runs != null

        // The data offset is relative to the moof box, so it can only be known once the moof
        // exists - and the value never changes a box's size, so measuring a draft is exact.
        val draft = buildMoof(samples, sizes, subSampled, 0)
        val moof = buildMoof(samples, sizes, subSampled, draft.size + 8)
        require(moof.size == draft.size)
        return moof + box("mdat", payload)
    }

    private fun buildMoof(
        samples: List<EncryptedSample>,
        sizes: List<Int>,
        subSampled: Boolean,
        dataOffset: Int,
    ): ByteArray {
        val sencBody = ByteArrayOutputStream().also { stream ->
            stream.write(u32(samples.size))
            samples.forEach { sample ->
                stream.write(sample.iv)
                if (sample.runs != null) {
                    stream.write(u16(sample.runs.size))
                    sample.runs.forEach { (clear, protectedBytes) ->
                        stream.write(u16(clear))
                        stream.write(u32(protectedBytes))
                    }
                }
            }
        }.toByteArray()
        return box(
            "moof",
            box("mfhd", fullBoxBody(0, 0, u32(1))),
            box(
                "traf",
                // default-base-is-moof + per-sample-index + default duration + default size.
                box("tfhd", fullBoxBody(0, 0x02003A, u32(1) + u32(1) + u32(1000) + u32(sizes.first()))),
                box("tfdt", fullBoxBody(1, 0, ByteArray(8))),
                // Sample sizes are declared per sample, as they are in real fragments whose
                // samples differ (the fixture writes frames of different lengths on purpose).
                box(
                    "trun",
                    fullBoxBody(0, 0x201, u32(samples.size) + u32(dataOffset) + concatAll(sizes.map { u32(it) })),
                ),
                box("saiz", fullBoxBody(0, 0, byteArrayOf(8) + u32(samples.size))),
                box("saio", fullBoxBody(0, 0, u32(1) + u32(0))),
                box("senc", fullBoxBody(0, if (subSampled) 0x2 else 0, sencBody)),
            ),
        )
    }

    /** An `enca` entry: an audio sample-entry body, the codec box, then the protection boxes. */
    private fun encaEntry(originalFormat: String, codecBox: ByteArray): ByteArray =
        box(
            "enca",
            // 6 reserved + 2 data-reference index + 20 audio fields, then the codec and `sinf`.
            ByteArray(28) + codecBox +
                box(
                    "sinf",
                    box("frma", originalFormat.toByteArray(Charsets.ISO_8859_1)) +
                        // `schm` is a full box: version/flags first, then the scheme.
                        box("schm", u32(0x00010000) + "cenc".toByteArray()) +
                        box("schi", box("tenc", byteArrayOf(0, 1, 8, 0) + ByteArray(16))),
                ),
        )

    /** A clear (unencrypted) fixture, to prove the "key but no encryption" path is total. */
    private fun clearFixture(format: String, payload: ByteArray): ByteArray =
        box("ftyp", "mp41".toByteArray() + u32(0) + "mp41".toByteArray()) +
            box(
                "moov",
                box(
                    "trak",
                    box("mdia", box("minf", box("stbl", box("stsd", fullBoxBody(0, 0, u32(1) + box(format, ByteArray(28))))))),
                ),
                box("mvex", box("trex", ByteArray(24))),
            ) +
            box("mdat", payload)

    /* --------------------------------------------------------------------------------- *
     * Byte helpers.
     * --------------------------------------------------------------------------------- */

    private fun counterBlock(
        iv: ByteArray,
        convention: SpotiFLACMovKeyDecryptor.CounterConvention,
    ): ByteArray {
        val block = ByteArray(16)
        when (convention) {
            SpotiFLACMovKeyDecryptor.CounterConvention.LEADING_IV -> iv.copyInto(block, 0)
            SpotiFLACMovKeyDecryptor.CounterConvention.TRAILING_IV -> iv.copyInto(block, 16 - iv.size)
        }
        return block
    }

    private fun ctr(
        iv: ByteArray,
        data: ByteArray,
        convention: SpotiFLACMovKeyDecryptor.CounterConvention,
    ): ByteArray =
        Cipher.getInstance("AES/CTR/NoPadding")
            .apply {
                init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    IvParameterSpec(counterBlock(iv, convention)),
                )
            }
            .doFinal(data)

    private fun encryptRuns(
        iv: ByteArray,
        data: ByteArray,
        runs: List<Pair<Int, Int>>,
        convention: SpotiFLACMovKeyDecryptor.CounterConvention,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            .apply {
                init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    IvParameterSpec(counterBlock(iv, convention)),
                )
            }
        val out = data.copyOf()
        var cursor = 0
        runs.forEach { (clear, protectedBytes) ->
            cursor += clear
            if (protectedBytes > 0) {
                // One cipher across a sample's protected runs: the counter does not reset
                // between them, which is exactly what the decryptor has to reproduce.
                cipher.update(data, cursor, protectedBytes).copyInto(out, cursor)
                cursor += protectedBytes
            }
        }
        return out
    }

    /** The concatenated `mdat` payloads of a file, to compare with the plaintext samples. */
    private fun mdatPayload(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var cursor = 0
        while (cursor + 8 <= bytes.size) {
            val size = ((bytes[cursor].toInt() and 0xFF) shl 24) or
                ((bytes[cursor + 1].toInt() and 0xFF) shl 16) or
                ((bytes[cursor + 2].toInt() and 0xFF) shl 8) or
                (bytes[cursor + 3].toInt() and 0xFF)
            val type = String(bytes, cursor + 4, 4, Charsets.ISO_8859_1)
            if (size < 8 || cursor + size > bytes.size) break
            if (type == "mdat") out.write(bytes, cursor + 8, size - 8)
            cursor += size
        }
        return out.toByteArray()
    }

    private fun box(type: String, vararg children: ByteArray): ByteArray {
        val body = concatAll(children.toList())
        return u32(8 + body.size) + type.toByteArray(Charsets.ISO_8859_1) + body
    }

    /** Byte-array concatenation; `flatten()` does not apply to a list of arrays. */
    private fun concatAll(chunks: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        chunks.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun fullBoxBody(version: Int, flags: Int, body: ByteArray): ByteArray =
        byteArrayOf(
            version.toByte(),
            (flags shr 16).toByte(),
            (flags shr 8).toByte(),
            flags.toByte(),
        ) + body

    private fun flacBlock(type: Int, body: ByteArray): ByteArray =
        byteArrayOf(type.toByte()) +
            byteArrayOf((body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()) +
            body

    private fun u16(value: Int): ByteArray = byteArrayOf((value shr 8).toByte(), value.toByte())

    private fun u32(value: Int): ByteArray = byteArrayOf(
        (value shr 24).toByte(),
        (value shr 16).toByte(),
        (value shr 8).toByte(),
        value.toByte(),
    )
}
