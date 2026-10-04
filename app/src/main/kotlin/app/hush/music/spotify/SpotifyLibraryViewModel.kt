/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.hush.music.spotify.models.SpotifyPlaylist
import javax.inject.Inject

@HiltViewModel
class SpotifyLibraryViewModel
    @Inject
    constructor(
        private val repository: SpotifyLibraryRepository,
    ) : ViewModel() {
        val playlists: StateFlow<List<SpotifyPlaylist>> =
            repository.playlists.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

        val isRefreshing: StateFlow<Boolean> =
            repository.isRefreshing.stateIn(viewModelScope, SharingStarted.Lazily, false)

        val errorMessage: StateFlow<String?> =
            repository.errorMessage.stateIn(viewModelScope, SharingStarted.Lazily, null)

        /**
         * How many tracks the account has saved, or `null` while it has not been asked.
         *
         * Liked Songs is not one of [playlists], so nothing that lists the account's playlists can
         * discover it - and it must not be pushed into that list either, because it is not a
         * playlist: the one API that adds tracks to a playlist would fail on it, which is exactly
         * where an Android Auto quick-add destination points. It is therefore offered as its own
         * entry, and only when the account can say it would open onto something.
         */
        private val _likedSongsTotal = MutableStateFlow<Int?>(null)

        val likedSongsTotal: StateFlow<Int?> = _likedSongsTotal.asStateFlow()

        init {
            viewModelScope.launch(Dispatchers.IO) {
                repository.restoreCachedPlaylists()
                _likedSongsTotal.value = repository.likedSongsCount()
            }
        }

        fun refreshPlaylists() {
            viewModelScope.launch(Dispatchers.IO) {
                repository.refreshPlaylists()
                _likedSongsTotal.value = repository.likedSongsCount()
            }
        }
    }
