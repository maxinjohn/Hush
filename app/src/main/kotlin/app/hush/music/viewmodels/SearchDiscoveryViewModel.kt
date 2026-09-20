/*
 * ArchiveTune (2026)
 * Â© Rukamori â€” github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.viewmodels

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import app.hush.music.R
import app.hush.music.search.LoadSearchDiscoveryUseCase
import app.hush.music.search.SearchDiscoveryUiModel
import javax.inject.Inject

sealed interface SearchDiscoveryScreenState {
    data object Loading : SearchDiscoveryScreenState

    data class Success(
        val data: SearchDiscoveryUiModel,
    ) : SearchDiscoveryScreenState

    data object Empty : SearchDiscoveryScreenState

    data class Error(
        @StringRes val messageResId: Int,
    ) : SearchDiscoveryScreenState
}

enum class SearchDiscoveryTab {
    EXPLORE,
    SUGGESTIONS,
}

@HiltViewModel
class SearchDiscoveryViewModel
    @Inject
    constructor(
        private val loadSearchDiscovery: LoadSearchDiscoveryUseCase,
    ) : ViewModel() {
        private val _state = MutableStateFlow<SearchDiscoveryScreenState>(SearchDiscoveryScreenState.Loading)
        val state: StateFlow<SearchDiscoveryScreenState> = _state.asStateFlow()

        private val _selectedTab = MutableStateFlow(SearchDiscoveryTab.EXPLORE)
        val selectedTab: StateFlow<SearchDiscoveryTab> = _selectedTab.asStateFlow()

        private var loadJob: Job? = null

        /** The tab whose result the current load may paint. */
        private var loadingTab: SearchDiscoveryTab? = null
        private val loadedTabs = mutableMapOf<SearchDiscoveryTab, SearchDiscoveryUiModel>()

        init {
            startLoad(SearchDiscoveryTab.EXPLORE)
        }

        fun selectTab(tab: SearchDiscoveryTab) {
            _selectedTab.value = tab
            loadedTabs[tab]?.let { cached ->
                _state.value = SearchDiscoveryScreenState.Success(cached)
                return
            }
            // Selecting a tab that has nothing cached always starts its own load. It used to
            // return early whenever any load was in flight, so tapping the other tab while the
            // first one was still loading showed the *first* tab's content under the second tab's
            // name - and if that first load failed, the second tab reported its error too.
            startLoad(tab)
        }

        fun retry() {
            startLoad(_selectedTab.value)
        }

        private fun startLoad(tab: SearchDiscoveryTab) {
            loadJob?.cancel()
            loadingTab = tab
            _state.value = SearchDiscoveryScreenState.Loading
            loadJob =
                viewModelScope.launch {
                    // The request layer retries transient failures on its own, so the answer to
                    // "is this ever going to load?" can be a minute away on a bad connection.
                    // A tab that is being looked at gets a bounded wait and can then offer its
                    // retry instead of sitting on skeletons indefinitely.
                    val loaded =
                        withTimeoutOrNull(LoadBudgetMillis) {
                            when (tab) {
                                SearchDiscoveryTab.EXPLORE -> loadSearchDiscovery.loadExplore()
                                SearchDiscoveryTab.SUGGESTIONS -> loadSearchDiscovery.loadSuggestions()
                            }
                        }

                    val state =
                        loaded
                            ?.fold(
                                onSuccess = { data ->
                                    loadedTabs[tab] = data
                                    if (data.isEmpty) {
                                        SearchDiscoveryScreenState.Empty
                                    } else {
                                        SearchDiscoveryScreenState.Success(data)
                                    }
                                },
                                onFailure = { SearchDiscoveryScreenState.Error(R.string.error_unknown) },
                            )
                            ?: SearchDiscoveryScreenState.Error(R.string.error_unknown)

                    // A load that finished after the user moved on must not repaint the screen
                    // with the tab they left.
                    if (loadingTab == tab) {
                        _state.value = state
                    }
                }
        }

        private companion object {
            const val LoadBudgetMillis = 25_000L
        }
    }
