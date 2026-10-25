@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.iptv.IptvChannel
import com.nuvio.tv.ui.components.ticker.SportsTickerStrip
import com.nuvio.tv.ui.components.ticker.SportsTickerViewModel
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Sports tab: today's games across every sport, the selected game's box score, standings,
 * injuries and headlines, and the channels in the user's own guide showing it.
 */
@Composable
fun SportsScreen(
    onPlayChannel: (IptvChannel, String?) -> Unit,
    viewModel: LiveTvViewModel = hiltViewModel(),
    tickerViewModel: SportsTickerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The channel list and guide are only needed for "On your channels"; load them while shown.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setVisible(true)
                Lifecycle.Event.ON_STOP -> viewModel.setVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setVisible(false)
        }
    }

    Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxl, top = NuvioTheme.spacing.xl)
        ) {
            Text(
                text = stringResource(R.string.sports_title),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioTheme.colors.TextPrimary
            )
            Text(
                text = stringResource(R.string.sports_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
            )
            Spacer(Modifier.height(NuvioTheme.spacing.md))
            ScoresBrowser(
                loadGames = tickerViewModel::gamesNow,
                loadDetail = tickerViewModel::detail,
                channels = state.allChannels,
                guide = state.guide,
                nowMs = state.nowMs,
                onPlay = { channel ->
                    viewModel.startZapSession(channel, fromAll = true)
                    onPlayChannel(channel, LiveZapSession.nowTitle(channel))
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
                refreshMs = 60_000L,
                bottomPadding = TICKER_CLEARANCE
            )
        }
        SportsTickerStrip(modifier = Modifier.align(Alignment.BottomCenter), viewModel = tickerViewModel)
    }
}
