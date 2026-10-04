package app.hush.music.canvas

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Unit tests for the hand-rolled canvaz-cache protobuf wire codec in [SpotifyCanvasProvider].
 *
 * The writers and parsers here are built from the wire format itself (tag = field number
 * shl 3 or wire type, base-128 varint lengths) instead of by calling the production helpers,
 * so a change that alters the bytes on the wire fails these tests rather than merely
 * agreeing with itself.
 */
class SpotifyCanvasWireCodecTest {

    // ── request encoding ────────────────────────────────────────────────────────

    @Test
    fun `a one character uri matches the wire bytes written by hand`() {
        // repeated CanvasRequest { string uri = 1; }  ->  0x0A len(3) [ 0x0A len(1) 'a' ]
        val expected = byteArrayOf(0x0A, 0x03, 0x0A, 0x01, 'a'.code.toByte())

        assertArrayEquals(expected, SpotifyCanvasProvider.encodeRequest(listOf("a")))
    }

    @Test
    fun `uris round trip through an independent parser`() {
        val uris = listOf("spotify:track:one", "spotify:track:two", "spotify:track:three")

        assertEquals(uris, parseRequest(SpotifyCanvasProvider.encodeRequest(uris)))
    }

    @Test
    fun `a uri past 127 bytes forces multi byte varints and still round trips`() {
        val uris = listOf("spotify:track:" + "x".repeat(300), "spotify:track:short")

        val bytes = SpotifyCanvasProvider.encodeRequest(uris)

        assertEquals(0x0A, bytes[0].toInt() and 0xFF) // field 1, wire type 2
        assertTrue("length must need more than one varint byte", bytes[1].toInt() and 0x80 != 0)
        assertEquals(uris, parseRequest(bytes))
    }

    @Test
    fun `an empty track list encodes to no bytes`() {
        assertArrayEquals(ByteArray(0), SpotifyCanvasProvider.encodeRequest(emptyList()))
    }

    // ── response decoding ───────────────────────────────────────────────────────

    @Test
    fun `url and uri are read out of a canvas message`() {
        val uri = "spotify:track:abc123"
        val url = "https://canvaz.scdn.co/upload/artist/6qq/video/def.cnvs.mp4"

        val decoded = SpotifyCanvasProvider.decodeResponse(responseBytes(canvasBytes(url, uri)))

        assertEquals(mapOf(uri to url), decoded)
    }

    @Test
    fun `several canvases decode in wire order`() {
        val first = "spotify:track:first" to "https://canvaz.scdn.co/a.cnvs.mp4"
        val second = "spotify:track:second" to "https://canvaz.scdn.co/b.cnvs.mp4"

        val decoded =
            SpotifyCanvasProvider.decodeResponse(
                responseBytes(
                    canvasBytes(second.second, second.first),
                    canvasBytes(first.second, first.first),
                ),
            )

        assertEquals(listOf(second.first, first.first), decoded.keys.toList())
        assertEquals(mapOf(first, second), decoded)
    }

    @Test
    fun `unknown fields of every supported wire type are skipped`() {
        val uri = "spotify:track:1"
        val url = "https://canvaz.scdn.co/x.cnvs.mp4"
        val noise =
            ByteArrayOutputStream().apply {
                write(varintField(1, 7)) // varint
                write(fixed64Field(3)) // fixed64
                write(lenDelimited(4, "ignored".toByteArray())) // delimited, unknown field
                write(fixed32Field(6)) // fixed32
            }.toByteArray()

        val decoded = SpotifyCanvasProvider.decodeResponse(responseBytes(canvasBytes(url, uri, noise)))

        assertEquals(mapOf(uri to url), decoded)
    }

    @Test
    fun `a canvas missing url or missing uri is dropped`() {
        val url = "https://canvaz.scdn.co/x.cnvs.mp4"
        val uri = "spotify:track:1"

        val decoded =
            SpotifyCanvasProvider.decodeResponse(
                responseBytes(
                    canvasBytes(url = url, uri = null),
                    canvasBytes(url = null, uri = uri),
                    canvasBytes(url, uri),
                ),
            )

        assertEquals(mapOf(uri to url), decoded)
    }

    @Test
    fun `only https canvaz mp4 urls are kept`() {
        val good = "https://canvaz.scdn.co/upload/licensor/abc/video/xyz.cnvs.mp4"
        val rejected =
            listOf(
                "http://canvaz.scdn.co/x.mp4", // not https
                "https://evil.example.com/x.mp4", // wrong host
                "https://canvaz.scdn.co/x.m3u8", // wrong extension
                "https://canvaz.scdn.co/x.mp4.bak", // wrong extension
                "https://canvaz.scdn.co/", // no file at all
                "https://[broken", // unparsable
            )

        val canvases = rejected.mapIndexed { index, url -> canvasBytes(url, "spotify:track:$index") }
        val withGood = (canvases + canvasBytes(good, "spotify:track:good")).toTypedArray()

        val decoded = SpotifyCanvasProvider.decodeResponse(responseBytes(*withGood))

        assertEquals(mapOf("spotify:track:good" to good), decoded)
    }

    @Test
    fun `an empty response decodes to an empty map`() {
        assertEquals(emptyMap<String, String>(), SpotifyCanvasProvider.decodeResponse(ByteArray(0)))
    }

    @Test
    fun `unknown top level fields are skipped before the canvases`() {
        val uri = "spotify:track:1"
        val url = "https://canvaz.scdn.co/x.cnvs.mp4"
        val bytes =
            ByteArrayOutputStream().apply {
                write(varintField(9, 12_345)) // varint
                write(fixed64Field(10)) // fixed64
                write(fixed32Field(11)) // fixed32
                write(lenDelimited(7, "ignored".toByteArray())) // delimited, but not a canvas
                write(lenDelimited(1, canvasBytes(url, uri)))
            }.toByteArray()

        assertEquals(mapOf(uri to url), SpotifyCanvasProvider.decodeResponse(bytes))
    }

    // ── malformed input ─────────────────────────────────────────────────────────

    @Test
    fun `a top level length that runs past the buffer throws`() {
        // field 1, length delimited, claiming 10 bytes when only one follows
        expectIOException { SpotifyCanvasProvider.decodeResponse(byteArrayOf(0x0A, 0x0A, 0x00)) }
    }

    @Test
    fun `an inner length that runs past the buffer throws`() {
        // a canvas whose first field claims 127 bytes and has one
        expectIOException { SpotifyCanvasProvider.decodeResponse(responseBytes(byteArrayOf(0x0A, 0x7F, 0x01))) }
    }

    @Test
    fun `an unterminated varint throws`() {
        expectIOException { SpotifyCanvasProvider.decodeResponse(ByteArray(11) { 0x80.toByte() }) }
    }

    @Test
    fun `an unsupported wire type at the top level throws`() {
        expectIOException { SpotifyCanvasProvider.decodeResponse(byteArrayOf(((3 shl 3) or 7).toByte())) }
    }

    @Test
    fun `an unsupported wire type inside a canvas throws`() {
        val inside = byteArrayOf(((2 shl 3) or 7).toByte()) // field 2, wire type 7

        expectIOException { SpotifyCanvasProvider.decodeResponse(responseBytes(inside)) }
    }

    // ── helpers: independent writers/parsers for the wire format ────────────────

    private fun ByteArrayOutputStream.putVarint(value: Int) {
        var remaining = value
        while (true) {
            val bits = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining == 0) {
                write(bits)
                return
            }
            write(bits or 0x80)
        }
    }

    private fun lenDelimited(fieldNumber: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.putVarint((fieldNumber shl 3) or 2)
        out.putVarint(payload.size)
        out.write(payload)
        return out.toByteArray()
    }

    private fun varintField(fieldNumber: Int, value: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.putVarint(fieldNumber shl 3) // wire type 0
        out.putVarint(value)
        return out.toByteArray()
    }

    private fun fixed64Field(fieldNumber: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.putVarint((fieldNumber shl 3) or 1)
        out.write(ByteArray(8))
        return out.toByteArray()
    }

    private fun fixed32Field(fieldNumber: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.putVarint((fieldNumber shl 3) or 5)
        out.write(ByteArray(4))
        return out.toByteArray()
    }

    /** Response canvas: optional url = 2, optional uri = 5, preceded by [noise]. */
    private fun canvasBytes(url: String?, uri: String?, noise: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(noise)
        if (url != null) out.write(lenDelimited(2, url.toByteArray(Charsets.UTF_8)))
        if (uri != null) out.write(lenDelimited(5, uri.toByteArray(Charsets.UTF_8)))
        return out.toByteArray()
    }

    private fun responseBytes(vararg canvases: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (one in canvases) out.write(lenDelimited(1, one))
        return out.toByteArray()
    }

    /** Parses repeated CanvasRequest messages written by [SpotifyCanvasProvider.encodeRequest]. */
    private fun parseRequest(bytes: ByteArray): List<String> {
        val result = mutableListOf<String>()
        var cursor = 0
        while (cursor < bytes.size) {
            assertEquals("requests carry only field 1, length delimited", 0x0A, bytes[cursor].toInt() and 0xFF)
            cursor++
            val (outerLength, afterLength) = readVarint(bytes, cursor)
            cursor = afterLength
            val track = bytes.copyOfRange(cursor, cursor + outerLength)
            cursor += outerLength

            assertEquals(0x0A, track[0].toInt() and 0xFF)
            val (innerLength, valueStart) = readVarint(track, 1)
            result += String(track, valueStart, innerLength, Charsets.UTF_8)
        }
        return result
    }

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Int, Int> {
        var value = 0
        var shift = 0
        var at = start
        while (true) {
            if (at >= bytes.size) throw AssertionError("truncated varint in test input")
            val b = bytes[at].toInt() and 0xFF
            at++
            value = value or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return value to at
            shift += 7
        }
    }

    private fun expectIOException(block: () -> Unit) {
        try {
            block()
            fail("expected an IOException for malformed input")
        } catch (expected: IOException) {
            // the contract under test
        }
    }
}
