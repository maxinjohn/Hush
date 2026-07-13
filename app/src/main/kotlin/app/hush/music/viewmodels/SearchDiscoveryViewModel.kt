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
        private val loadedTabs = mutableMapOf<SearchDiscoveryTab, SearchDiscoveryUiModel>()

        init {
            load(SearchDiscoveryTab.EXPLORE)
        }

        fun selectTab(tab: SearchDiscoveryTab) {
            _selectedTab.value = tab
            load(tab)
        }

        fun retry() {
            load(_selectedTab.value, force = true)
        }

        private fun load(
            tab: SearchDiscoveryTab,
            force: Boolean = false,
        ) {
            if (!force && loadJob?.isActive == true) return
            if (!force && loadedTabs[tab] != null) {
                _state.value = SearchDiscoveryScreenState.Success(loadedTabs.getValue(tab))
                return
            }
            loadJob?.cancel()
            _state.value = SearchDiscoveryScreenState.Loading
            loadJob =
                viewModelScope.launch {
                    _state.value =
                        try {
                            when (tab) {
                                SearchDiscoveryTab.EXPLORE -> loadSearchDiscovery.loadExplore()
                                SearchDiscoveryTab.SUGGESTIONS -> loadSearchDiscovery.loadSuggestions()
                            }
                                .fold(
                                    onSuccess = { data ->
                                        loadedTabs[tab] = data
                                        if (data.isEmpty) {
                                            SearchDiscoveryScreenState.Empty
                                        } else {
                                            SearchDiscoveryScreenState.Success(data)
                                        }
                                    },
                                    onFailure = {
                                        SearchDiscoveryScreenState.Error(R.string.error_unknown)
                                    },
                                )
                        } catch (throwable: Throwable) {
                            if (throwable is CancellationException) throw throwable
                            SearchDiscoveryScreenState.Error(R.string.error_unknown)
                        }
                }
        }
    }
