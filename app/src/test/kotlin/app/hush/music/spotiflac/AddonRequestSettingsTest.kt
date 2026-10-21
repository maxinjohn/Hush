/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Hush tells a server addon it wants.
 *
 * A BitChord/Eclipse addon takes the client's settings as query parameters on every request and
 * applies its own manifest defaults to whatever is missing, so a client that says nothing always
 * gets the manifest's default. For Tido that was the wrong tier twice over: the top-profile
 * manifest defaults to `MAX`, which is "Max + Dolby Atmos" - audio [SpotiFLACFileIntegrity] rejects
 * as unplayable - and nothing carried Audio Sources' Audio Quality through to the addon at all.
 *
 * The tests below read the real manifests, so the manifest's own shape is what is being mapped.
 */
class AddonRequestSettingsTest {

    /** The Tido manifest; [defaultQuality] is the only field that differs between profiles. */
    private fun tidoManifest(defaultQuality: String): String =
        """
        {
          "id": "com.yourname.tidal.hifi",
          "name": "Tido",
          "version": "1.33.5",
          "resources": ["search", "stream", "catalog", "settings", "isrc", "resolve"],
          "settings": [
            {
              "key": "quality",
              "type": "select",
              "label": "Audio quality",
              "default": "$defaultQuality",
              "perNetwork": true,
              "options": [
                { "value": "HIRES", "label": "Max (Hi-Res 24-bit up to 192 kHz, no Atmos)" },
                { "value": "MAX", "label": "Max + Dolby Atmos (Hi-Res 24-bit up to 192 kHz)" },
                { "value": "LOSSLESS", "label": "Lossless 16-bit / 44.1 kHz FLAC" },
                { "value": "HIGH", "label": "High (AAC 320 kbps)" },
                { "value": "LOW", "label": "Low (AAC 96 kbps)" }
              ]
            },
            { "key": "exactQuality", "type": "toggle", "default": false, "perNetwork": true },
            { "key": "hifiApiUrl", "type": "text", "default": "", "placeholder": "https://..." },
            { "key": "videosEnabled", "type": "toggle", "default": false },
            { "key": "videoQuality", "type": "select", "default": "HIGH",
              "options": [
                { "value": "HIGH", "label": "High (1080p)" },
                { "value": "MEDIUM", "label": "Medium (720p)" },
                { "value": "LOW", "label": "Low (480p)" }
              ] },
            { "key": "hideExplicit", "type": "toggle", "default": false },
            { "key": "importsEnabled", "type": "toggle", "default": false },
            { "key": "artistGridMode", "type": "select", "default": "alongside",
              "options": [
                { "value": "off", "label": "Off" },
                { "value": "alongside", "label": "Alongside" },
                { "value": "only", "label": "ArtistGrid only" }
              ] }
          ]
        }
        """

    private fun addon(
        url: String,
        body: String,
    ): ExtensionSource = requireNotNull(ExtensionRepositories.read(url, body)).sources.single()

    private fun params(
        url: String,
        body: String,
        quality: String,
    ): Map<String, String> =
        AddonRequestSettings.forRequest(addon(url, body).addonSettings, quality)

    private val topProfile = ExtensionRepositories.TidoUrl
    private val losslessProfile = ExtensionRepositories.TidoLosslessUrl

    /**
     * The top profile's manifest defaults to Atmos, and a lossless request must not take it.
     *
     * `MAX` classifies as spatial through the same rules the cascade uses to decide an answer is
     * unplayable, so the lossless tier Hush asks for is `HIRES` - the profile's own hi-res master
     * without the Atmos mix. It travels in LastWave's own spelling, `hi_res`, because that is what
     * the client this addon was written for has always sent.
     */
    @Test
    fun `a lossless request never asks the Atmos-defaulting profile for Atmos`() {
        assertEquals("hi_res", params(topProfile, tidoManifest("MAX"), "HI_RES_LOSSLESS")["quality"])
        assertEquals("hi_res", params(topProfile, tidoManifest("MAX"), "BEST")["quality"])
        assertEquals("hi_res", params(topProfile, tidoManifest("LOSSLESS"), "HI_RES_LOSSLESS")["quality"])
    }

    /** The listener's own choice of tier is what the addon is told, not the manifest's default. */
    @Test
    fun `the configured tier is sent instead of the declared default`() {
        assertEquals("lossless", params(topProfile, tidoManifest("MAX"), "LOSSLESS")["quality"])
        assertEquals("high", params(topProfile, tidoManifest("MAX"), "HIGH")["quality"])
        assertEquals("low", params(topProfile, tidoManifest("MAX"), "LOW")["quality"])
        // Tido has no 160 kbps rung: a Medium request falls down to the addon's lowest one, which is
        // the fallback direction the addon itself documents.
        assertEquals("low", params(topProfile, tidoManifest("MAX"), "MEDIUM")["quality"])
    }

    /**
     * A profile addon is asked exactly what its own client asks - two keys, and nothing else.
     *
     * Measured against LastWave's `AddonClient.search`, which builds
     * `<base>/search?q=..&quality=..&atmos=..` and never states the profile's settings: Tido stores
     * them against the URL it generated, and `hifiApiUrl=` in particular is an instruction to use an
     * instance at the empty string. `limit` is not part of that request either.
     */
    @Test
    fun `a profile addon is asked only what its own client asks`() {
        val sent = params(topProfile, tidoManifest("MAX"), "LOSSLESS")

        assertEquals(mapOf("quality" to "lossless", "atmos" to "none"), sent)
        assertFalse(sent.containsKey("limit"))
        assertFalse(sent.containsKey("exactQuality"))
        assertFalse(sent.containsKey("videosEnabled"))
        assertFalse(sent.containsKey("videoQuality"))
        assertFalse(sent.containsKey("artistGridMode"))
        assertFalse(sent.containsKey("hifiApiUrl"))
    }

    /**
     * An addon that keeps its own settings is recognised from the manifest, not from its name.
     *
     * `perNetwork` is the field its generator writes, and it means the value is applied by the addon
     * per network - which is only possible if the client sends nothing and the addon already knows.
     */
    @Test
    fun `the addon that keeps its settings is the one that declares perNetwork`() {
        assertTrue(AddonRequestSettings.keepsSettingsOnServer(addon(topProfile, tidoManifest("MAX")).addonSettings))
        assertFalse(
            AddonRequestSettings.keepsSettingsOnServer(
                listOf(AddonSetting(key = "quality", type = "select", default = "lossless")),
            ),
        )
    }

    /**
     * An addon that names the rungs in the runtime's own words is taken at its word.
     *
     * gglos declares lowercase tokens (`hi_res_lossless`, `lossless`, `high`, `low`) and no Atmos
     * rung at all, so the exact token wins and the mapping has nothing to reinterpret.
     */
    @Test
    fun `a manifest that speaks the runtime's vocabulary is matched token for token`() {
        val url = "https://gglos.ggottes.deno.net/manifest.json"
        val body =
            """
            {"id":"gg.gglos.addon","name":"gglos","version":"1.0.0",
             "resources":["search","stream"],
             "settings":[{"key":"quality","type":"select","default":"lossless","options":[
               {"value":"hi_res_lossless","label":"Hi-Res Lossless (24-bit)"},
               {"value":"lossless","label":"Lossless (16-bit)"},
               {"value":"high","label":"High"},
               {"value":"low","label":"Low"}]}]}
            """.trimIndent()

        assertEquals("hi_res_lossless", params(url, body, "HI_RES_LOSSLESS")["quality"])
        assertEquals("lossless", params(url, body, "LOSSLESS")["quality"])
        assertEquals("high", params(url, body, "HIGH")["quality"])
        assertEquals("low", params(url, body, "LOW")["quality"])
    }

    /**
     * An addon with a separate Atmos switch is told no, rather than left to its own default.
     *
     * Tido spells the preference as a tier, but an addon that publishes the switch on its own would
     * otherwise apply whatever it defaulted to - and Hush has no Atmos playback path to honour it
     * with.
     */
    @Test
    fun `a declared Atmos switch is always told no`() {
        val url = "https://atmos.example/manifest.json"
        val body =
            """
            {"id":"com.example.atmos","name":"Atmos Addon","version":"1.0.0",
             "resources":["search","stream"],
             "settings":[
               {"key":"atmos","type":"toggle","default":true},
               {"key":"quality","type":"select","default":"ATMOS","options":[
                 {"value":"ATMOS","label":"Dolby Atmos"},
                 {"value":"LOSSLESS","label":"Lossless FLAC"}]}]}
            """.trimIndent()

        val sent = params(url, body, "LOSSLESS")

        assertEquals("false", sent["atmos"])
        // The spatial rung is not reachable even by asking for its own value.
        assertEquals("LOSSLESS", sent["quality"])
        assertEquals("LOSSLESS", params(url, body, "ATMOS")["quality"])
    }

    /** A manifest that declares no settings sends nothing, and a plain value is not a preference. */
    @Test
    fun `nothing declared means nothing rewritten`() {
        assertEquals(
            emptyMap<String, String>(),
            AddonRequestSettings.forRequest(emptyList(), "LOSSLESS"),
        )
        assertEquals(
            "fixed",
            AddonRequestSettings.forRequest(
                listOf(AddonSetting(key = "quality", type = "text", default = "fixed")),
                "LOSSLESS",
            )["quality"],
        )
        assertNull(
            AddonRequestSettings.forRequest(
                listOf(AddonSetting(key = "region", type = "text", default = "US")),
                "LOSSLESS",
            )["quality"],
        )
    }

    /**
     * The parameters reach the addon's own routes, and the addon's address is left alone.
     *
     * A server addon is asked at `<base>/search` and `<base>/stream/<id>`; the settings ride along as
     * query parameters in every protocol Hush speaks, sorted so the same request is the same URL.
     */
    @Test
    fun `the tier and the other settings are appended to search and stream addresses`() {
        val settings = params(topProfile, tidoManifest("MAX"), "HI_RES_LOSSLESS")
        val base = "https://tido.tido.tido.dpdns.org/4bd14d1e2fa848febf1e9c03cdc237c0"

        // A profile addon states its tier and the Atmos answer, and nothing it did not ask for.
        val search = addonSearchUrl(base, "Taste Back", 0, settings)
        assertEquals("$base/search?q=Taste+Back&atmos=none&quality=hi_res", search)

        val stream = addonStreamUrl(base, "tidal:417888540", settings)
        assertEquals("$base/stream/tidal:417888540?atmos=none&quality=hi_res", stream)
        // With nothing to say the route is exactly what it always was.
        assertEquals("$base/stream/tidal:417888540", addonStreamUrl(base, "tidal:417888540"))
        assertEquals("$base/search?q=x&limit=5", addonSearchUrl(base, "x", 5))
    }

    /** The settings are carried on the source, so playback can state them without re-reading it. */
    @Test
    fun `the manifest's settings are kept on the source`() {
        val source = addon(topProfile, tidoManifest("MAX"))

        assertEquals(
            listOf("quality", "exactQuality", "hifiApiUrl", "videosEnabled", "videoQuality", "hideExplicit", "importsEnabled", "artistGridMode"),
            source.addonSettings.map { it.key },
        )
        assertEquals(listOf("HIRES", "MAX", "LOSSLESS", "HIGH", "LOW"), source.addonSettings.first().options.map { it.value })
        // The row's own name still reads the profile it defaults to, from the same parse.
        assertEquals("Tido", source.name)
        assertEquals("Tido · Lossless", addon(losslessProfile, tidoManifest("LOSSLESS")).name)
    }
}
