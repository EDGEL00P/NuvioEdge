@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.local.LiveTvChannelSort
import com.nuvio.tv.data.local.LiveTvSettings
import com.nuvio.tv.data.local.LiveTvStartScreen
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.ticker.SportsTickerViewModel
import com.nuvio.tv.ui.screens.live.LiveSourcesDialog
import com.nuvio.tv.ui.screens.live.LiveTvViewModel
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun LiveTvSettingsContent(
    initialFocusRequester: FocusRequester?,
    viewModel: LiveTvSettingsViewModel = hiltViewModel(),
    liveTvViewModel: LiveTvViewModel = hiltViewModel(),
    tickerViewModel: SportsTickerViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val tickerOn by tickerViewModel.enabled.collectAsStateWithLifecycle()
    var showSources by remember { mutableStateOf(false) }
    var startPickerOpen by remember { mutableStateOf(false) }
    var sortPickerOpen by remember { mutableStateOf(false) }

    if (settings == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
        return
    }
    val current = settings ?: LiveTvSettings()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_live_tv),
            subtitle = stringResource(R.string.settings_live_tv_subtitle)
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            item(key = "live_sources") {
                SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                    SettingsSectionLabel(stringResource(R.string.live_sources))
                    SettingsActionRow(
                        title = stringResource(R.string.live_sources),
                        subtitle = stringResource(R.string.settings_live_tv_sources_description),
                        onClick = { showSources = true },
                        modifier = initialFocusRequester?.let(Modifier::focusRequester) ?: Modifier
                    )
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_live_tv_auto_refresh),
                        subtitle = stringResource(R.string.settings_live_tv_auto_refresh_description),
                        checked = current.autoRefreshWhileVisible,
                        onToggle = { viewModel.setAutoRefreshWhileVisible(!current.autoRefreshWhileVisible) }
                    )
                }
            }
            item(key = "live_display") {
                SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                    SettingsSectionLabel(stringResource(R.string.settings_live_tv_display))
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_live_tv_show_numbers),
                        subtitle = null,
                        checked = current.showChannelNumbers,
                        onToggle = { viewModel.setShowChannelNumbers(!current.showChannelNumbers) }
                    )
                    SettingsActionRow(
                        title = stringResource(R.string.settings_live_tv_sort),
                        subtitle = stringResource(R.string.settings_live_tv_sort_description),
                        value = stringResource(current.channelSort.labelRes()),
                        onClick = { sortPickerOpen = true }
                    )
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_live_tv_osd_after_tune),
                        subtitle = null,
                        checked = current.showOsdAfterTune,
                        onToggle = { viewModel.setShowOsdAfterTune(!current.showOsdAfterTune) }
                    )
                }
            }
            item(key = "live_navigation") {
                SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                    SettingsSectionLabel(stringResource(R.string.settings_live_tv_navigation))
                    SettingsActionRow(
                        title = stringResource(R.string.settings_live_tv_start_screen),
                        subtitle = null,
                        value = stringResource(current.startScreen.labelRes()),
                        onClick = { startPickerOpen = true }
                    )
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_live_tv_wrap_zap),
                        subtitle = stringResource(R.string.settings_live_tv_wrap_zap_description),
                        checked = current.wrapZap,
                        onToggle = { viewModel.setWrapZap(!current.wrapZap) }
                    )
                }
            }
            item(key = "live_sports") {
                SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                    SettingsSectionLabel(stringResource(R.string.settings_live_tv_sports))
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_live_tv_ticker),
                        subtitle = stringResource(R.string.settings_live_tv_ticker_description),
                        checked = tickerOn,
                        onToggle = tickerViewModel::toggle
                    )
                }
            }
        }
    }

    if (showSources) {
        LiveSourcesDialog(viewModel = liveTvViewModel, onDismiss = { showSources = false })
    }
    if (startPickerOpen) {
        LiveTvPickerDialog(
            title = stringResource(R.string.settings_live_tv_start_screen),
            selected = current.startScreen,
            choices = LiveTvStartScreen.entries,
            label = { stringResource(it.labelRes()) },
            onSelect = { viewModel.setStartScreen(it); startPickerOpen = false },
            onDismiss = { startPickerOpen = false }
        )
    }
    if (sortPickerOpen) {
        LiveTvPickerDialog(
            title = stringResource(R.string.settings_live_tv_sort),
            selected = current.channelSort,
            choices = LiveTvChannelSort.entries,
            label = { stringResource(it.labelRes()) },
            onSelect = { viewModel.setChannelSort(it); sortPickerOpen = false },
            onDismiss = { sortPickerOpen = false }
        )
    }
}

@Composable
private fun <T> LiveTvPickerDialog(
    title: String,
    selected: T,
    choices: List<T>,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    NuvioDialog(onDismiss = onDismiss, title = title, width = 520.dp) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(vertical = NuvioTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(choices) { choice ->
                val text = label(choice)
                ListItem(
                    selected = choice == selected,
                    onClick = { onSelect(choice) },
                    colors = ListItemDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground,
                        focusedContentColor = NuvioTheme.colors.FocusContent
                    ),
                    headlineContent = { Text(text, style = MaterialTheme.typography.bodyLarge) }
                )
            }
        }
    }
}

@Composable
private fun LiveTvStartScreen.labelRes(): Int = when (this) {
    LiveTvStartScreen.CHANNELS -> R.string.settings_live_tv_start_channels
    LiveTvStartScreen.GUIDE -> R.string.settings_live_tv_start_guide
}

@Composable
private fun LiveTvChannelSort.labelRes(): Int = when (this) {
    LiveTvChannelSort.PROVIDER_ORDER -> R.string.settings_live_tv_sort_provider
    LiveTvChannelSort.NUMBER -> R.string.settings_live_tv_sort_number
    LiveTvChannelSort.NAME -> R.string.settings_live_tv_sort_name
}