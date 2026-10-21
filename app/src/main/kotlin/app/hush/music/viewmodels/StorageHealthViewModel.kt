/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import app.hush.music.storage.ObserveStorageFolderHealthUseCase
import app.hush.music.storage.StorageFolderHealth
import app.hush.music.storage.StorageFolderKind
import javax.inject.Inject

/**
 * Whether the folder downloads live in can still be used.
 *
 * Downloads are ordinary files in a folder the user chose, and that folder is the only place they
 * exist: when it goes away - the card is pulled, the access is revoked, the folder is deleted -
 * every download becomes unreachable and new ones cannot be written, while everything else in the
 * app carries on looking normal. The banner this feeds is the app saying so, rather than the user
 * finding out one failed download at a time.
 *
 * Starts as [StorageFolderHealth.Healthy] because a warning that appears before the check has run
 * would be a claim the app cannot make yet.
 */
@HiltViewModel
class StorageHealthViewModel
    @Inject
    constructor(
        observeStorageFolderHealth: ObserveStorageFolderHealthUseCase,
    ) : ViewModel() {
        val downloadsFolderHealth: StateFlow<StorageFolderHealth> =
            observeStorageFolderHealth(StorageFolderKind.DOWNLOADS)
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = StorageFolderHealth.Healthy,
                )
    }
