/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 and Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins a server addon's protocol, as measured against a real one.
 *
 * `gglos.ggottes.deno.net` is a BitChord addon with no JavaScript at all: a manifest, a
 * `/search?q=` endpoint and a `/stream/<id>` endpoint. Its shapes are the contract here - the
 * duration is seconds, the stream answer nests the file under `url`, and the formats are names
 * rather than MIME types. All of it is read leniently, because it is written by someone else.
 */
class ServerAddonHostTest {

    @Test
    fun `a search answer is read with seconds turned into milliseconds`() {
        val body =
            """
            {"tracks":[
              {"id":"154008623620558848","title":"Taste","artist":"Sabrina Carpenter","duration":157,
               "artworkURL":"https://tracks.monochrome.st/x.jpg","format":"flac",
               "audioQuality":"HI_RES_LOSSLESS"},
              {"id":"tidal:417888540","title":"Taste","artist":"Coco Jones","duration":165,
               "format":"dash","audioQuality":"HI_RES_LOSSLESS"},
              {"title":"no id, ignored"},
              {"id":"","title":"blank id, ignored"}
            ]}
            """.trimIndent()

        val rows = parseAddonTracks(body)

        assertEquals(listOf("154008623620558848", "tidal:417888540"), rows.map { it.id })
        assertEquals("Taste", rows.first().title)
        assertEquals("Sabrina Carpenter", rows.first().artist)
        assertEquals(157_000L, rows.first().durationMs)
        assertEquals("HI_RES_LOSSLESS", rows.first().quality)
        // A row with no duration is zero rather than a reason to drop it: an addon that omits one
        // still knows the song, and the matcher treats an unknown duration as no evidence.
        assertEquals(165_000L, rows[1].durationMs)
        // The declared delivery format is carried out of the row, because it is the only thing that
        // can tell a file from a manifest before the download starts.
        assertEquals("flac", rows[0].format)
        assertEquals("dash", rows[1].format)
    }

    /**
     * A manifest is not a file, and the difference is a track that plays versus one that does not.
     *
     * Measured on gglos: its Tidal-backed rows answer `format: dash`, and `/stream/<id>` returns a
     * 1,894-byte `.mpd` whose `mediaPresentationDuration` is `PT29.994S`. Hush downloaded it as if it
     * were the song and then reported ExoPlayer `3003` - "this song seems to resolve and then fails"
     * from the listener's side. The row is now either dropped before matching or answered as a
     * refusal, so the sweep moves on to a source that can serve a file.
     */
    @Test
    fun `a row that only offers a manifest is not a file`() {
        assertEquals(false, addonFormatIsPlayable("dash"))
        assertEquals(false, addonFormatIsPlayable("HLS"))
        assertEquals(false, addonFormatIsPlayable("m3u8"))
        // A module names no format, and a format Hush has never seen is still worth trying: the
        // download is what decides, and refusing an unknown name would drop working sources.
        assertEquals(true, addonFormatIsPlayable(null))
        assertEquals(true, addonFormatIsPlayable(""))
        assertEquals(true, addonFormatIsPlayable("flac"))
        assertEquals(true, addonFormatIsPlayable("mp3"))

        assertTrue(
            addonUrlIsManifest("https://im-fa.manifest.tidal.com/1/manifests/Egk3.mpd?token=abc"),
        )
        assertTrue(addonUrlIsManifest("https://host/x/index.m3u8"))
        assertTrue(!addonUrlIsManifest("https://tracks.monochrome.st/track/154008623620558848"))

        // The stream answer that names a manifest is "no file for this track", not a URL to fetch.
        assertNull(
            parseAddonStream(
                """{"url":"https://im-fa.manifest.tidal.com/1/manifests/Egk3.mpd?token=abc",
                   "format":"dash","quality":"FLAC_HIRES","codec":"flac","container":"mp4"}""",
            ),
        )
    }

    @Test
    fun `a stream answer carries the file, its format and a MIME type`() {
        val flac =
            parseAddonStream(
                """{"url":"https://tracks.monochrome.st/track/154008623620558848","format":"flac",
                   "quality":"HI_RES_LOSSLESS","codec":"flac","container":"flac","manifest":"none"}""",
            )!!
        assertEquals("https://tracks.monochrome.st/track/154008623620558848", flac.url)
        assertEquals("HI_RES_LOSSLESS", flac.quality)
        assertEquals("audio/flac", flac.mimeType)

        // A format Hush does not know is left for the player to read from the bytes rather than
        // claimed as something it is not.
        assertNull(parseAddonStream("""{"url":"https://host/x","format":"dash"}""")!!.mimeType)
        // An answer with no file is the addon saying it has none - not a URL to try.
        assertNull(parseAddonStream("""{"error":"not found"}"""))
        // A file URL has to be https: this is the address the player is handed.
        assertNull(parseAddonStream("""{"url":"http://host/x","format":"flac"}"""))
        assertNull(parseAddonStream("not json"))
    }

    @Test
    fun `an addon's endpoints are built with its own address and an encoded query`() {
        val base = "https://gglos.ggottes.deno.net"
        assertEquals(
            "https://gglos.ggottes.deno.net/search?q=Taste+Back&limit=20",
            addonSearchUrl(base, "Taste Back", 20),
        )
        // A title with an ampersand is a title, not a second query parameter.
        assertTrue(addonSearchUrl(base, "Taste & Back", 5).contains("Taste+%26+Back"))
        assertEquals(
            "https://gglos.ggottes.deno.net/stream/154008623620558848",
            addonStreamUrl(base, "154008623620558848"),
        )
        // An id with a colon is one path segment, which needs no escaping; a slash inside one cannot
        // silently become a different route.
        assertEquals("https://host/s1/stream/tidal:417888540", addonStreamUrl("https://host/s1/", "tidal:417888540"))
        assertTrue(addonStreamUrl("https://host", "a/b").endsWith("/stream/a%2Fb"))
    }

    @Test
    fun `a server addon manifest is read as one playable source`() {
        val url = "https://gglos.ggottes.deno.net/manifest.json"
        val body =
            """
            {"id":"gg.gglos.addon","name":"gglos","version":"1.0.0",
             "resources":["search","stream"],"allowDownloads":1,"checkValidLossless":0,
             "settings":[{"key":"quality","type":"select","default":"lossless","options":[]}]}
            """.trimIndent()

        assertEquals(RepositoryKind.SERVER_ADDON, ExtensionRepositories.detect(body))
        val document = ExtensionRepositories.read(url, body)!!
        assertEquals(RepositoryKind.SERVER_ADDON, document.kind)
        assertEquals("gglos", document.name)
        assertEquals(1, document.sources.size)
        val addon = document.sources.single()
        assertEquals(url, addon.repositoryId)
        // Namespaced like a module, because an addon id is chosen by whoever wrote the manifest.
        assertEquals("gglos-ggottes-deno-net:gg.gglos.addon", addon.id)
        assertEquals("https://gglos.ggottes.deno.net", addon.addonBaseUrl)
        assertEquals(listOf("search", "stream"), addon.requiredRuntimeFeatures)
        assertTrue(addon.isServerAddon)
        assertTrue(addon.isPlayable)
        assertTrue(addon.isPlayedOutsideRuntime)
        // Not the native runtime's, and not a session's: the addon's own service does the work.
        assertEquals(false, addon.isNativePlayable)
        assertEquals(false, addon.requiresSpotiFLACSession)
        assertEquals(false, addon.isRuntimeCompatible)
        assertEquals("Streams audio", addon.primaryRole)
        assertTrue(addon.isSafeRegistryEntry)
        // The base is what every request goes to, so it must survive the same check a package URL
        // does - an addon pointing at plain http is not usable and is not stored as if it were.
        assertEquals(
            false,
            addon.copy(addonBaseUrl = "http://host").isSafeRegistryEntry,
        )
    }

    /**
     * An addon that refuses says so in its own words, and the row quotes them.
     *
     * Measured against `tido.tido.tido.dpdns.org`: every API route answers `403 {"error":
     * "Unavailable"}` no matter which client asks, which is the service reporting its own upstream
     * rather than a gate on the client. `answered HTTP 403` alone sent a reader hunting for a token
     * that does not exist; `answered HTTP 403 (Unavailable)` is the same fact with the addon's half
     * of it attached. Anything that is not a one-word JSON reason - an HTML block page, an empty
     * body - is left as the status code.
     */
    @Test
    fun `a refusal is reported with the addon's own word for it`() {
        assertEquals(" (Unavailable)", addonRefusalReason("{\n  \"error\": \"Unavailable\"\n}"))
        assertEquals("", addonRefusalReason("<html>blocked</html>"))
        assertEquals("", addonRefusalReason(""))
        // Written by a stranger: read as one short parenthetical, never as a paragraph.
        assertEquals("", addonRefusalReason("{\"error\":\"\"}"))
        assertEquals(60 + 3, addonRefusalReason("{\"error\":\"${"x".repeat(200)}\"}").length)
    }

    /**
     * A personal addon's manifest may omit `resources` and name its root instead.
     *
     * LastWave's own model treats an empty resource list as "serves everything", and its manifests
     * are the shape a `/a/<token>` service publishes. Without this an addon that plainly answers
     * search and stream is reported as "not a source repository", which is the one answer a user
     * cannot act on.
     */
    @Test
    fun `a personal addon manifest without resources is still one playable source`() {
        val token = "5145a183579346ca9102d80f40e38481"
        val url = "https://personal.example/a/$token/manifest.json"
        val body =
            """{"id":"dev.lastwave.tidal","name":"Personal","version":"1.0.0",
             "root":"https://personal.example"}"""

        assertEquals(RepositoryKind.SERVER_ADDON, ExtensionRepositories.detect(body))
        val addon = ExtensionRepositories.read(url, body)!!.sources.single()
        assertEquals("https://personal.example/a/$token", addon.addonBaseUrl)
        assertTrue(addon.isServerAddon)
        assertTrue(addon.isPlayable)
        // Only this shape is signed; an Eclipse token path is not.
        assertTrue(LastWaveAddon.isSigned(addon.addonBaseUrl!!))
    }

    /** A personal addon answers a duration as a double; a dropped one is missing match evidence. */
    @Test
    fun `a fractional duration is read rather than discarded`() {
        val row =
            parseAddonTracks(
                """{"tracks":[{"id":154008623620558848,"title":"Taste",
                   "artist":"Sabrina Carpenter","duration":157.5}]}""",
            ).single()

        assertEquals("154008623620558848", row.id)
        assertEquals(157_500L, row.durationMs)
    }

    @Test
    fun `a manifest under a path keeps the path as its base`() {
        val document =
            ExtensionRepositories.read(
                "https://gglos.ggottes.deno.net/s1/manifest.json",
                """{"id":"gg.gglos.addon.s1","name":"gglos · Source 1","resources":["search","stream"]}""",
            )!!
        assertEquals("https://gglos.ggottes.deno.net/s1", document.sources.single().addonBaseUrl)
    }
}
