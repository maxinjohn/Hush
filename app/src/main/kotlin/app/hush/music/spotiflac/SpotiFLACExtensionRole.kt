/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What an extension package says it is for, read from its own manifest.
 *
 * The download sweep must contain download providers and nothing else. Two of the registered
 * sources are not: `spotify-web` declares `["metadata_provider"]` and `apple-music` declares
 * `["metadata_provider", "lyrics_provider"]`. Both earn their place - Spotify Web is what turns a
 * Spotify link or ID into a title and artist, and Apple Music searches, validates ISRCs and enriches
 * a match (measured on device: `Metadata match (apple-music) ... isrc: IND292216298`, followed by
 * album type, UPC and comment enrichment) - but neither can serve audio, so a sweep that includes
 * them spends its last, most valuable budget on a provider that was never able to answer.
 *
 * This was previously expressed by naming `spotify-web` in two separate filters, which left Apple
 * Music in the sweep where it was reported as an "extension package unavailable" although its
 * package was installed and working - the message described a missing package for a source whose
 * package was present. It also meant the next metadata-only extension would have to be added by name
 * in both places to behave the same.
 */
internal object SpotiFLACExtensionRole {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * True when a manifest was readable and positively declares that it does not download.
     *
     * Only a *readable* declaration can answer this. A manifest that is absent, unparseable, or
     * carries no `type` at all says nothing either way and answers false, because a package that has
     * not been extracted yet is not a source that cannot download - treating it as one would take a
     * real provider out of the sweep, which is a certain loss traded for a tidier log.
     */
    fun declaresNoDownloadProvider(manifestJson: String?): Boolean {
        if (manifestJson.isNullOrBlank()) return false
        return runCatching {
            val root = json.parseToJsonElement(manifestJson).jsonObject
            val types = (root["type"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                .orEmpty()
            if (types.isEmpty()) return@runCatching false
            val declaresDownload = types.any { it.equals("download_provider", ignoreCase = true) }
            // `category: "download"` is the older way of saying the same thing, and the registry
            // model honours it too - so a manifest that only carries the category is a downloader.
            val category = root["category"]?.jsonPrimitive?.contentOrNull.orEmpty()
            !declaresDownload && !category.equals("download", ignoreCase = true)
        }.getOrDefault(false)
    }
}
