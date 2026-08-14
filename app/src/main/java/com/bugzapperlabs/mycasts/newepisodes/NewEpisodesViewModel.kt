package com.bugzapperlabs.mycasts.newepisodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bugzapperlabs.mycasts.data.local.NewEpisode
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Episodes found since the app was last opened (issue #161) -- linked from the new-episodes
 *  notification, see [com.bugzapperlabs.mycasts.data.settings.AppSettings.newEpisodeIdsToShow]. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NewEpisodesViewModel @Inject constructor(
    feedRepository: FeedRepository,
    settingsDataStore: SettingsDataStore,
) : ViewModel() {
    val uiState: StateFlow<List<NewEpisode>> = settingsDataStore.settings
        .map { it.newEpisodeIdsToShow.toList() }
        .distinctUntilChanged()
        .flatMapLatest { ids -> feedRepository.observeNewEpisodes(ids) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
