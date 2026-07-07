/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.home

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.google.common.collect.ImmutableList
import app.hush.music.constants.QuickPicksDisplayMode
import app.hush.music.db.entities.LocalItem
import app.hush.music.db.entities.Song
import app.hush.music.innertube.models.PlaylistItem
import app.hush.music.innertube.pages.HomePage
import app.hush.music.models.SimilarRecommendation

sealed interface HomeScreenState {
    data object Loading : HomeScreenState

    @Immutable
    data class Success(
        val uiState: HomeUiState,
    ) : HomeScreenState

    data object Empty : HomeScreenState

    @Immutable
    data class Error(
        @StringRes val messageResId: Int,
    ) : HomeScreenState
}

@Immutable
data class HomeUiState(
    val quickPicks: ImmutableList<Song>,
    val speedDialItems: ImmutableList<LocalItem>,
    val forgottenFavorites: ImmutableList<Song>,
    val keepListening: ImmutableList<LocalItem>,
    val similarRecommendations: ImmutableList<SimilarRecommendation>,
    val accountPlaylists: ImmutableList<PlaylistItem>,
    val homePage: HomePage?,
    val selectedChip: HomePage.Chip?,
    val accountName: String,
    val accountImageUrl: String?,
    val quickPicksDisplayMode: QuickPicksDisplayMode,
    val showCategoryChips: Boolean,
    val showTonalBackdrop: Boolean,
    val isRefreshing: Boolean,
    val isLoadingMore: Boolean,
    val isChipLoading: Boolean,
)

sealed interface HomeAction {
    data object Refresh : HomeAction

    data class SelectChip(
        val chip: HomePage.Chip?,
    ) : HomeAction

    data class LoadMore(
        val continuation: String?,
    ) : HomeAction
}
