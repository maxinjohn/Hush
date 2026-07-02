/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.search

import androidx.compose.runtime.Immutable
import app.hush.music.innertube.YouTube
import app.hush.music.innertube.pages.SearchSummaryPage
import app.hush.music.models.ItemsPage
import app.hush.music.viewmodels.OnlineSearchSort

@Immutable
data class OnlineSearchResultUiState(
    val query: String,
    val summaryPage: SearchSummaryPage?,
    val viewStateMap: Map<String, ItemsPage?>,
    val selectedFilter: YouTube.SearchFilter?,
    val sort: OnlineSearchSort,
)

sealed interface OnlineSearchScreenState {
    data object Loading : OnlineSearchScreenState

    @Immutable
    data class Success(
        val uiState: OnlineSearchResultUiState,
    ) : OnlineSearchScreenState

    data object Empty : OnlineSearchScreenState
}
