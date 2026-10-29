package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.LiveTvChannelSort
import com.nuvio.tv.data.local.LiveTvSettings
import com.nuvio.tv.data.local.LiveTvSettingsDataStore
import com.nuvio.tv.data.local.LiveTvStartScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LiveTvSettingsViewModel @Inject constructor(
    private val store: LiveTvSettingsDataStore
) : ViewModel() {
    val settings: StateFlow<LiveTvSettings?> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setAutoRefreshWhileVisible(enabled: Boolean) = viewModelScope.launch {
        store.setAutoRefreshWhileVisible(enabled)
    }

    fun setStartScreen(screen: LiveTvStartScreen) = viewModelScope.launch {
        store.setStartScreen(screen)
    }

    fun setShowChannelNumbers(show: Boolean) = viewModelScope.launch {
        store.setShowChannelNumbers(show)
    }

    fun setChannelSort(sort: LiveTvChannelSort) = viewModelScope.launch {
        store.setChannelSort(sort)
    }

    fun setWrapZap(wrap: Boolean) = viewModelScope.launch {
        store.setWrapZap(wrap)
    }

    fun setShowOsdAfterTune(show: Boolean) = viewModelScope.launch {
        store.setShowOsdAfterTune(show)
    }
}