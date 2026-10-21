/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 and Section 5
 */

package app.hush.music.spotiflac

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The request a personal addon actually receives, as opposed to the headers a helper builds.
 *
 * The unit tests around [LastWaveAddon] pin the signature; this one pins that [ServerAddonHost]
 * *sends* it - on search and on stream, with the tier and the Atmos answer a personal addon reads,
 * and with the stream's own intent and identity. A helper that is correct but never called is
 * exactly the class of bug that looks fixed until the addon answers 403.
 */
class ServerAddonHostLastWaveTest {

    private val token = "5145a183579346ca9102d80f40e38481"
    private val base = "https://personal.example/a/$token"

    private val source =
        ExtensionSource(
            id = "personal-example-a-$token:dev.lastwave.tidal",
            name = "Personal",
            runtime = AddonServerRuntime,
            addonBaseUrl = base,
        )

    private fun host(
        seen: MutableList<HttpRequestData>,
        body: String,
    ): ServerAddonHost =
        ServerAddonHost(
            HttpClient(
                MockEngine { request ->
                    seen += request
                    respond(
                        content = body,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ),
        )

    @Test
    fun `a personal addon's search is signed and asked for its own tier`() =
        runBlocking {
            val seen = mutableListOf<HttpRequestData>()
            val host =
                host(
                    seen,
                    """{"tracks":[{"id":1,"title":"Taste","artist":"Sabrina Carpenter","duration":157.5}]}""",
                )

            val rows = host.search(source, "Taste", 5, "HI_RES_LOSSLESS").getOrThrow()

            assertEquals(1, rows.size)
            assertEquals(157_500L, rows.single().durationMs)

            val request = seen.single()
            assertEquals("/a/$token/search", request.url.encodedPath)
            // The tier is in LastWave's vocabulary, and no Atmos stream is ever asked for.
            assertEquals("hi_res", request.url.parameters["quality"])
            assertEquals("none", request.url.parameters["atmos"])
            assertEquals("5", request.url.parameters["limit"])

            val timestamp = requireNotNull(request.headers["X-LW-TS"])
            assertEquals(
                LastWaveAddon.signature(token, request.url.encodedPath, "GET", timestamp.toLong()),
                request.headers["X-LW-Sign"],
            )
            // Search says nothing about intent, exactly like the client whose protocol this is.
            assertEquals(null, request.headers["X-LW-Intent"])
        }

    @Test
    fun `a personal addon's stream says what it is and who is asking`() =
        runBlocking {
            val seen = mutableListOf<HttpRequestData>()
            val host =
                host(seen, """{"url":"https://cdn.example/x.flac","format":"flac","quality":"HI_RES"}""")

            val stream = host.stream(source, "154008623620558848", "LOSSLESS").getOrThrow()

            assertEquals("https://cdn.example/x.flac", stream!!.url)

            val request = seen.single()
            assertEquals("/a/$token/stream/154008623620558848", request.url.encodedPath)
            assertEquals("lossless", request.url.parameters["quality"])
            assertEquals("none", request.url.parameters["atmos"])
            assertEquals("stream", request.headers["X-LW-Intent"])
            assertEquals(LastWaveAddon.PLAYER_USER_AGENT, request.headers[HttpHeaders.UserAgent])

            val timestamp = requireNotNull(request.headers["X-LW-TS"])
            assertEquals(
                LastWaveAddon.signature(token, request.url.encodedPath, "GET", timestamp.toLong()),
                request.headers["X-LW-Sign"],
            )
        }

    /**
     * A profile addon - Tido - is asked exactly what its own client asks, on the wire.
     *
     * Its manifest marks the settings `perNetwork`, which is the addon saying it already holds them
     * for that URL. LastWave's `AddonClient.search` therefore sends `q`, `quality` and `atmos` and
     * nothing else - no `limit`, no profile settings - and the tier in its own vocabulary. This is
     * the request a 403 has to be read against: if this shape is refused, the refusal is the
     * addon's, not a missing parameter of Hush's.
     */
    private fun profileSource(): ExtensionSource =
        ExtensionSource(
            id = "tido-tido-tido-dpdns-org-$token:com.yourname.tidal.hifi",
            name = "Tido",
            runtime = AddonServerRuntime,
            addonBaseUrl = "https://tido.tido.tido.dpdns.org/$token",
            addonSettings =
                listOf(
                    AddonSetting(
                        key = "quality",
                        type = "select",
                        default = "MAX",
                        perNetwork = true,
                        options =
                            listOf(
                                AddonSettingOption("HIRES", "Max (Hi-Res 24-bit, no Atmos)"),
                                AddonSettingOption("MAX", "Max + Dolby Atmos"),
                                AddonSettingOption("LOSSLESS", "Lossless"),
                                AddonSettingOption("HIGH", "High"),
                                AddonSettingOption("LOW", "Low"),
                            ),
                    ),
                    AddonSetting(key = "hifiApiUrl", type = "text", default = "", perNetwork = true),
                    AddonSetting(key = "videosEnabled", type = "toggle", default = "false", perNetwork = true),
                    AddonSetting(key = "artistGridMode", type = "select", default = "alongside"),
                ),
        )

    @Test
    fun `a profile addon's search is the request its own client sends`() =
        runBlocking {
            val seen = mutableListOf<HttpRequestData>()
            val host = host(seen, """{"tracks":[]}""")

            host.search(profileSource(), "Taste Back", 20, "HI_RES_LOSSLESS").getOrThrow()

            val request = seen.single()
            assertEquals("/$token/search", request.url.encodedPath)
            assertEquals("Taste Back", request.url.parameters["q"])
            // The tier in LastWave's spelling, and no Atmos, which this device cannot play.
            assertEquals("hi_res", request.url.parameters["quality"])
            assertEquals("none", request.url.parameters["atmos"])
            // Nothing the addon already holds, and no `limit` it never asks for.
            assertNull(request.url.parameters["limit"])
            assertNull(request.url.parameters["hifiApiUrl"])
            assertNull(request.url.parameters["videosEnabled"])
            assertNull(request.url.parameters["artistGridMode"])
            // Not published under `/a/`, so there is no signature to add - and search has no intent.
            assertNull(request.headers["X-LW-TS"])
            assertNull(request.headers["X-LW-Sign"])
            assertNull(request.headers["X-LW-Intent"])
        }

    /** A profile addon's stream carries the intent and identity its client sets unconditionally. */
    @Test
    fun `a profile addon's stream says what it is and who is asking`() =
        runBlocking {
            val seen = mutableListOf<HttpRequestData>()
            val host =
                host(seen, """{"url":"https://cdn.example/x.flac","format":"flac"}""")

            val stream = host.stream(profileSource(), "154008623620558848", "LOSSLESS").getOrThrow()

            assertEquals("https://cdn.example/x.flac", stream!!.url)
            val request = seen.single()
            assertEquals("/$token/stream/154008623620558848", request.url.encodedPath)
            assertEquals("lossless", request.url.parameters["quality"])
            assertEquals("none", request.url.parameters["atmos"])
            assertEquals("stream", request.headers["X-LW-Intent"])
            assertEquals(LastWaveAddon.PLAYER_USER_AGENT, request.headers[HttpHeaders.UserAgent])
            // Still not a signed addon: the intent pair is the whole of what it gets.
            assertNull(request.headers["X-LW-TS"])
        }

    /** An Eclipse-style addon keeps the request it had: no signature, no invented parameters. */
    @Test
    fun `an unsigned addon is not sent personal-addon headers`() =
        runBlocking {
            val seen = mutableListOf<HttpRequestData>()
            val host = host(seen, """{"tracks":[]}""")
            val eclipse =
                source.copy(
                    id = "tido:com.yourname.tidal.hifi",
                    addonBaseUrl = "https://tido.tido.tido.dpdns.org/$token",
                )

            host.search(eclipse, "Taste", 5, "HI_RES_LOSSLESS").getOrThrow()

            val request = seen.single()
            assertEquals(null, request.headers["X-LW-TS"])
            assertEquals(null, request.headers["X-LW-Sign"])
            // Nothing declared and not a personal addon: no `quality`/`atmos` is made up.
            assertEquals(null, request.url.parameters["quality"])
            assertEquals(null, request.url.parameters["atmos"])
            assertTrue(request.headers[HttpHeaders.UserAgent].orEmpty().startsWith("BitChord/1.0 Hush/"))
        }
}
