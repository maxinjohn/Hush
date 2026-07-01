/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.innertube.pages

import app.hush.music.innertube.models.*

data class ChartsPage(
    val sections: List<ChartSection>,
    val continuation: String?,
) {
    data class ChartSection(
        val title: String,
        val items: List<YTItem>,
        val chartType: ChartType,
    )

    enum class ChartType {
        TRENDING,
        TOP,
        GENRE,
        NEW_RELEASES,
    }
}
