package com.nuvio.tv.ui.screens.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.data.iptv.IptvSource
import com.nuvio.tv.data.iptv.IptvSourceType
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.ticker.SportsTickerViewModel
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionButton
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionRow
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow

/** Settings > Live TV & Sports: sources, languages, the score ticker and downloaded data. */
@Composable
internal fun LiveTvSettingsContent(
    initialFocusRequester: FocusRequester? = null,
    viewModel: LiveTvViewModel = hiltViewModel(),
    tickerViewModel: SportsTickerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tickerOn by tickerViewModel.enabled.collectAsStateWithLifecycle()
    val tickerOverLive by tickerViewModel.overLiveChannels.collectAsStateWithLifecycle()
    var showSources by remember { mutableStateOf(false) }
    var languagesFor by remember { mutableStateOf<IptvSource?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_live_title),
            subtitle = stringResource(R.string.settings_live_subtitle)
        )
        SettingsGroupCard(title = stringResource(R.string.live_sources)) {
            SettingsActionRow(
                title = stringResource(R.string.settings_live_manage_sources),
                subtitle = stringResource(R.string.settings_live_manage_sources_subtitle),
                value = state.sources.size.toString(),
                onClick = { showSources = true },
                modifier = Modifier.fillMaxWidth().let { m ->
                    initialFocusRequester?.let { m.focusRequester(it) } ?: m
                }
            )
            state.sources.forEach { source ->
                val type = stringResource(
                    if (source.type == IptvSourceType.XTREAM) R.string.live_type_xtream else R.string.live_type_m3u
                )
                SettingsActionRow(
                    title = stringResource(R.string.settings_live_source_languages, source.name),
                    subtitle = type,
                    value = languagesLabel(source.languages),
                    onClick = { languagesFor = source },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        SettingsGroupCard(title = stringResource(R.string.settings_live_ticker)) {
            SettingsToggleRow(
                title = stringResource(R.string.settings_live_ticker_show),
                subtitle = stringResource(R.string.settings_live_ticker_show_subtitle),
                checked = tickerOn,
                onToggle = { tickerViewModel.setEnabled(!tickerOn) },
                modifier = Modifier.fillMaxWidth()
            )
            SettingsToggleRow(
                title = stringResource(R.string.settings_live_ticker_over_live),
                subtitle = stringResource(R.string.settings_live_ticker_over_live_subtitle),
                checked = tickerOverLive,
                enabled = tickerOn,
                onToggle = { tickerViewModel.setOverLiveChannels(!tickerOverLive) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        SettingsGroupCard(title = stringResource(R.string.settings_live_data)) {
            SettingsActionRow(
                title = stringResource(R.string.settings_live_refresh),
                subtitle = stringResource(R.string.settings_live_refresh_subtitle),
                enabled = !state.loading && state.sources.isNotEmpty(),
                onClick = viewModel::refresh,
                modifier = Modifier.fillMaxWidth()
            )
            SettingsActionRow(
                title = stringResource(R.string.settings_live_clear),
                subtitle = stringResource(R.string.settings_live_clear_subtitle),
                onClick = { confirmClear = true },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (showSources) {
        LiveSourcesDialog(viewModel = viewModel, onDismiss = { showSources = false })
    }
    languagesFor?.let { source ->
        LanguagePickerDialog(
            selected = source.languages,
            onDone = { languages ->
                viewModel.setSourceLanguages(source.id, languages)
                languagesFor = null
            },
            onDismiss = { languagesFor = null }
        )
    }
    if (confirmClear) {
        NuvioDialog(
            onDismiss = { confirmClear = false },
            title = stringResource(R.string.settings_live_clear),
            subtitle = stringResource(R.string.settings_live_clear_confirm)
        ) {
            SettingsDialogActionRow {
                SettingsDialogActionButton(stringResource(R.string.action_cancel), onClick = { confirmClear = false })
                SettingsDialogActionButton(
                    text = stringResource(R.string.settings_live_clear_action),
                    primary = true,
                    onClick = {
                        viewModel.clearDownloads()
                        confirmClear = false
                    }
                )
            }
        }
    }
}
