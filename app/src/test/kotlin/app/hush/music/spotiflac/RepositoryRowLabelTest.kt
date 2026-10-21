/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Two repository rows that name themselves the same thing.
 *
 * `tido.tido.tido.dpdns.org` publishes one addon at several addresses, one per settings profile, and
 * every manifest names itself `Tido` - so the repository section drew two identical rows, neither of
 * which could be told from the other or matched to the source beneath it. The manifests differ in
 * exactly one field: the `quality` their own settings default to. That is the profile, and it is
 * what the row takes its name from - the same fact the source row already shows.
 *
 * The documents here are the manifests as the host publishes them, cut to the fields Hush reads, so
 * the test walks the whole path: body -> [ExtensionRepositories.read] -> row label.
 */
class RepositoryRowLabelTest {

    /** The Tido manifest; [defaultQuality] is the only field that differs between profiles. */
    private fun tidoManifest(defaultQuality: String): String =
        """
        {
          "id": "com.yourname.tidal.hifi",
          "name": "Tido",
          "version": "1.33.5",
          "description": "Direct Tidal search & streaming.",
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
            }
          ]
        }
        """

    private fun document(
        url: String,
        body: String,
    ): RepositoryDocument = requireNotNull(ExtensionRepositories.read(url, body))

    @Test
    fun `the lossless profile's row is called lossless, and the top profile is still Tido`() {
        val top = document(ExtensionRepositories.TidoUrl, tidoManifest("MAX"))
        val lossless = document(ExtensionRepositories.TidoLosslessUrl, tidoManifest("LOSSLESS"))

        val topLabel = ExtensionRepositories.rowLabel(ExtensionRepositories.TidoUrl, top)
        val losslessLabel = ExtensionRepositories.rowLabel(ExtensionRepositories.TidoLosslessUrl, lossless)

        assertEquals("Tido", topLabel)
        assertEquals("Tido · Lossless", losslessLabel)
        assertNotEquals(topLabel, losslessLabel)
        // The row says what the source beneath it says, which is the whole point of naming it here.
        assertEquals(top.sources.single().displayName, topLabel)
        assertEquals(lossless.sources.single().displayName, losslessLabel)
    }

    /** A third profile added by hand is named for its own tier, with no list of siblings consulted. */
    @Test
    fun `a profile added on its own is named for the tier it declares`() {
        val url = "https://tido.tido.tido.dpdns.org/76f4007ae3b748278da4dd49b16c8661/manifest.json"
        val high = document(url, tidoManifest("HIGH"))

        assertEquals("Tido · High", ExtensionRepositories.rowLabel(url, high))
    }

    /**
     * An addon at its host root is the addon, not a profile of one.
     *
     * `gglos` publishes a real choice of tiers and defaults to lossless, exactly as the lossless
     * Tido profile does, and it is still called `gglos`: there is one of it, and appending the tier
     * to every addon that defaults below its top would rename rows that were never ambiguous.
     */
    @Test
    fun `an addon at its host root keeps its own name`() {
        val url = "https://gglos.ggottes.deno.net/manifest.json"
        val body =
            """
            {
              "id": "gg.gglos.addon",
              "name": "gglos",
              "version": "1.0.0",
              "resources": ["search", "stream"],
              "settings": [
                {
                  "key": "quality",
                  "type": "select",
                  "default": "lossless",
                  "options": [
                    { "value": "hi_res_lossless", "label": "Hi-Res Lossless (24-bit)" },
                    { "value": "lossless", "label": "Lossless (16-bit)" },
                    { "value": "high", "label": "High" },
                    { "value": "low", "label": "Low" }
                  ]
                }
              ]
            }
            """

        assertEquals("gglos", ExtensionRepositories.rowLabel(url, document(url, body)))
    }

    /** A repository with no name of its own is still named by its address, never by "null". */
    @Test
    fun `a nameless repository falls back to its host`() {
        val url = "https://raw.githubusercontent.com/zarzet/spotiflac-extension/main/registry.json"
        val body = """{ "extensions": [] }"""

        assertEquals(
            ExtensionRepositories.label(url),
            ExtensionRepositories.rowLabel(url, document(url, body)),
        )
    }
}
