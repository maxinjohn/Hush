/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 and Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signed requests a personal (LastWave-style) addon server expects.
 *
 * The signature vector below was produced independently of this code (Python `hmac`), so a change
 * that reorders the signed message, adds the query string or switches to base64 fails here rather
 * than at the addon, where the symptom is an unexplained 403.
 */
class LastWaveAddonTest {

    private val token = "5145a183579346ca9102d80f40e38481"
    private val base = "https://personal.example/a/$token"

    /** The token is the segment after `/a/`, with or without a manifest file after it. */
    @Test
    fun `the token is read from the a path`() {
        assertEquals(token, LastWaveAddon.tokenOf("$base/manifest.json"))
        assertEquals(token, LastWaveAddon.tokenOf(base))
        assertEquals(token, LastWaveAddon.tokenOf("$base/search?q=taste"))
        // An Eclipse-style addon has no `/a/`, so it is not signed: sending the headers there would
        // be a rename, not the handshake that addon was built for.
        assertNull(LastWaveAddon.tokenOf("https://tido.tido.tido.dpdns.org/$token/manifest.json"))
        assertNull(LastWaveAddon.tokenOf("https://gglos.ggottes.deno.net/manifest.json"))
        assertFalse(LastWaveAddon.isSigned("https://host/$token/s1"))
        assertTrue(LastWaveAddon.isSigned("$base/s1"))
    }

    /** The message is `ts\nMETHOD\npath\ntoken`, signed with LastWave's published client secret. */
    @Test
    fun `the signature is computed over the path alone`() {
        assertEquals(
            "d1e0ba5632057c7776df09dcbcbf11e091902213b332cf6c0a0246055b7786b1",
            LastWaveAddon.signature(
                token = "abc",
                path = "/a/abc/search",
                method = "GET",
                timestampSeconds = 1_730_000_000L,
            ),
        )
        // A query string is not part of it, and the method is part of it - both pinned because
        // getting either wrong produces a valid-looking header the server rejects.
        assertEquals(
            "772a152df72f012d3df6bdea785dc262a0c8f4a114d5b6fcb761db333a613b9c",
            LastWaveAddon.signature(
                token = token,
                path = "/a/$token/stream/154008623620558848",
                method = "GET",
                timestampSeconds = 1_730_000_000L,
            ),
        )
    }

    /** Search is signed but carries no intent; a stream says what it is and who is asking. */
    @Test
    fun `headers carry the timestamp, the signature and the stream intent`() {
        val search =
            requireNotNull(
                LastWaveAddon.headers("$base/search?q=taste", nowSeconds = 1_730_000_000L),
            )
        assertEquals("1730000000", search["X-LW-TS"])
        assertEquals(
            LastWaveAddon.signature(token, "/a/$token/search", "GET", 1_730_000_000L),
            search["X-LW-Sign"],
        )
        assertNull(search["X-LW-Intent"])

        val stream =
            requireNotNull(
                LastWaveAddon.headers(
                    "$base/stream/154008623620558848?quality=lossless",
                    intent = "stream",
                    nowSeconds = 1_730_000_000L,
                ),
            )
        assertEquals("stream", stream["X-LW-Intent"])
        assertEquals(LastWaveAddon.PLAYER_USER_AGENT, stream["User-Agent"])
        // The query is excluded from the signature but still present in the request the server sees;
        // sign the path, not the URL.
        assertEquals(
            LastWaveAddon.signature(
                token,
                "/a/$token/stream/154008623620558848",
                "GET",
                1_730_000_000L,
            ),
            stream["X-LW-Sign"],
        )

        assertNull(LastWaveAddon.headers("https://gglos.ggottes.deno.net/search?q=x"))
    }

    /** The addon's manifest declares no settings, so the two parameters it reads are supplied. */
    @Test
    fun `a personal addon is asked with its quality tier and no Atmos`() {
        assertEquals("hi_res", LastWaveAddon.qualityToken("HI_RES_LOSSLESS"))
        assertEquals("hi_res", LastWaveAddon.qualityToken("HI_RES"))
        assertEquals("hi_res", LastWaveAddon.qualityToken("BEST"))
        assertEquals("lossless", LastWaveAddon.qualityToken("LOSSLESS"))
        assertEquals("high", LastWaveAddon.qualityToken("HIGH"))
        assertEquals("low", LastWaveAddon.qualityToken("LOW"))
        assertEquals("low", LastWaveAddon.qualityToken("MEDIUM"))
        // An unknown request is not a reason to invent a tier the server rejects.
        assertEquals("lossless", LastWaveAddon.qualityToken("SOMETHING_NEW"))

        val sent = LastWaveAddon.requestParameters(emptyMap(), "HI_RES_LOSSLESS")
        assertEquals("hi_res", sent["quality"])
        assertEquals("none", sent["atmos"])
    }

    /** A manifest that does declare either key keeps its own value. */
    @Test
    fun `declared parameters are not overwritten`() {
        val sent =
            LastWaveAddon.requestParameters(
                mapOf("quality" to "hi_res", "atmos" to "auto", "region" to "US"),
                "LOW",
            )
        assertEquals("hi_res", sent["quality"])
        assertEquals("auto", sent["atmos"])
        assertEquals("US", sent["region"])
        assertEquals(3, sent.size)
    }
}
