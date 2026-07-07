/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.innertube.pages

import app.hush.music.innertube.models.Artist
import app.hush.music.innertube.models.Menu
import app.hush.music.innertube.models.MusicResponsiveListItemRenderer.FlexColumn
import app.hush.music.innertube.models.Run

object PageHelper {
    data class LibraryFeedbackTokens(
        val addToken: String?,
        val removeToken: String?,
    )

    private val LIBRARY_ADD_ICONS = setOf("BOOKMARK_BORDER", "LIBRARY_ADD")
    private val LIBRARY_SAVED_ICONS = setOf("BOOKMARK", "LIBRARY_SAVED", "LIBRARY_REMOVE")

    fun isLibraryIcon(iconType: String?): Boolean {
        if (iconType == null) return false
        if (iconType == "KEEP" || iconType == "KEEP_OFF") return false
        return iconType in LIBRARY_ADD_ICONS || iconType in LIBRARY_SAVED_ICONS || iconType.startsWith("LIBRARY_")
    }

    fun isAddLibraryIcon(iconType: String?): Boolean = iconType in LIBRARY_ADD_ICONS

    fun extractLibraryTokensFromMenuItems(
        menuItems: List<Menu.MenuRenderer.Item>?,
    ): LibraryFeedbackTokens {
        if (menuItems.isNullOrEmpty()) return LibraryFeedbackTokens(null, null)
        return LibraryFeedbackTokens(null, null)
    }

    fun extractRuns(
        columns: List<FlexColumn>,
        typeLike: String,
    ): List<Run> {
        val filteredRuns = mutableListOf<Run>()
        for (column in columns) {
            val runs =
                column.musicResponsiveListItemFlexColumnRenderer.text?.runs
                    ?: continue

            for (run in runs) {
                val typeStr =
                    run.navigationEndpoint
                        ?.watchEndpoint
                        ?.watchEndpointMusicSupportedConfigs
                        ?.watchEndpointMusicConfig
                        ?.musicVideoType
                        ?: run.navigationEndpoint
                            ?.browseEndpoint
                            ?.browseEndpointContextSupportedConfigs
                            ?.browseEndpointContextMusicConfig
                            ?.pageType
                        ?: continue

                if (typeLike in typeStr) {
                    filteredRuns.add(run)
                }
            }
        }
        return filteredRuns
    }

    fun extractArtists(runs: List<Run>?): List<Artist> {
        if (runs == null) return emptyList()
        return runs
            .filter { run -> run.text.trim().isNotBlank() && run.text != " • " }
            .map { run ->
                Artist(
                    name = run.text,
                    id = run.navigationEndpoint?.browseEndpoint?.browseId,
                )
            }
    }
}
