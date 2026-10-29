@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.live

import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Scoreboard
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.data.iptv.EpgProgramme
import com.nuvio.tv.data.iptv.EpgSourceReport
import com.nuvio.tv.data.iptv.EpgSourceStatus
import com.nuvio.tv.data.iptv.IptvChannel
import com.nuvio.tv.data.iptv.IptvRepository
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun LiveTvScreen(
    onPlayChannel: (IptvChannel, String?) -> Unit,
    onOpenMultiview: () -> Unit,
    viewModel: LiveTvViewModel = hiltViewModel(),
    tickerViewModel: com.nuvio.tv.ui.components.ticker.SportsTickerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tickerOn by tickerViewModel.enabled.collectAsStateWithLifecycle()
    var showSources by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showScores by remember { mutableStateOf(false) }
    var showEpgReview by remember { mutableStateOf(false) }
    var showGuide by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var appliedStartScreen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.settingsLoaded) {
        if (state.settingsLoaded && !appliedStartScreen) {
            showGuide = state.settings.startScreen == com.nuvio.tv.data.local.LiveTvStartScreen.GUIDE
            appliedStartScreen = true
        }
    }

    // Guide refresh and the clock only run while this screen is visible.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> viewModel.setVisible(true)
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> viewModel.setVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setVisible(false)
        }
    }
    LiveTvContent(
        state = state,
        showGuide = showGuide,
        showChannelNumbers = state.settings.showChannelNumbers,
        onRefresh = viewModel::refresh,
        onToggleGuide = { showGuide = !showGuide },
        onOpenScores = { showScores = true },
        onOpenEpgReview = { showEpgReview = true },
        onOpenSources = { showSources = true },
        onOpenMultiview = onOpenMultiview,
        onOpenSearch = { showSearch = true },
        onSelectGroup = viewModel::selectGroup,
        onPlay = { channel ->
            viewModel.startZapSession(channel)
            val now = IptvRepository.nowAndNext(state.guide[channel.id], System.currentTimeMillis()).first
            onPlayChannel(channel, now?.title)
        },
        onToggleFavorite = viewModel::toggleFavorite
    )

    if (showSources) {
        LiveSourcesDialog(viewModel = viewModel, onDismiss = { showSources = false })
    }
    if (showEpgReview) {
        EpgMappingReviewDialog(
            reports = state.guideReports,
            viewModel = viewModel,
            onDismiss = { showEpgReview = false }
        )
    }
    if (showScores) {
        ScoresDialog(
            loadGames = tickerViewModel::gamesNow,
            loadDetail = tickerViewModel::detail,
            channels = state.allChannels,
            guide = state.guide,
            nowMs = state.nowMs,
            tickerOn = tickerOn,
            onToggleTicker = tickerViewModel::toggle,
            onPlay = { channel ->
                showScores = false
                viewModel.startZapSession(channel)
                onPlayChannel(channel, LiveZapSession.nowTitle(channel))
            },
            onDismiss = { showScores = false }
        )
    }
    if (showSearch) {
        LiveSearchDialog(
            channels = state.allChannels,
            guide = state.guide,
            nowMs = state.nowMs,
            onPlay = { channel ->
                showSearch = false
                viewModel.startZapSession(channel)
                onPlayChannel(channel, LiveZapSession.nowTitle(channel))
            },
            onDismiss = { showSearch = false }
        )
    }
}

/** The Live TV layout, without view models, so it can be previewed and rendered in tests. */
@Composable
internal fun LiveTvContent(
    state: LiveTvUiState,
    showGuide: Boolean,
    showChannelNumbers: Boolean = true,
    onRefresh: () -> Unit,
    onToggleGuide: () -> Unit,
    onOpenScores: () -> Unit,
    onOpenEpgReview: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenMultiview: () -> Unit,
    onOpenSearch: () -> Unit,
    onSelectGroup: (String) -> Unit,
    onPlay: (IptvChannel) -> Unit,
    onToggleFavorite: (IptvChannel) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .padding(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxl, top = NuvioTheme.spacing.xl)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.live_title),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            if (state.loading) {
                LoadingIndicator(modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(NuvioTheme.spacing.md))
            } else if (state.guideLoading) {
                Text(
                    text = stringResource(R.string.live_guide_loading),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
                Spacer(Modifier.width(NuvioTheme.spacing.md))
            }
            HeaderButton(Icons.Default.Refresh, stringResource(R.string.live_refresh), enabled = !state.loading && !state.guideLoading, onClick = onRefresh)
            Spacer(Modifier.width(NuvioTheme.spacing.sm))
            HeaderButton(Icons.Default.Scoreboard, stringResource(R.string.scores_button), onClick = onOpenScores)
            Spacer(Modifier.width(NuvioTheme.spacing.sm))
            if (state.guideReports.any { it.mappingReviews.isNotEmpty() }) {
                HeaderButton(Icons.Default.Edit, stringResource(R.string.live_epg_review_button), onClick = onOpenEpgReview)
                Spacer(Modifier.width(NuvioTheme.spacing.sm))
            }
            if (state.allChannels.isNotEmpty()) {
                HeaderButton(Icons.Default.Search, stringResource(R.string.live_search), onClick = onOpenSearch)
                Spacer(Modifier.width(NuvioTheme.spacing.sm))
                HeaderButton(
                    Icons.Default.CalendarViewWeek,
                    stringResource(if (showGuide) R.string.live_channel_list else R.string.live_guide),
                    onClick = onToggleGuide
                )
                Spacer(Modifier.width(NuvioTheme.spacing.sm))
                HeaderButton(Icons.Default.GridView, stringResource(R.string.multiview_button), onClick = onOpenMultiview)
                Spacer(Modifier.width(NuvioTheme.spacing.sm))
            }
            HeaderButton(Icons.Default.Settings, stringResource(R.string.live_sources), onClick = onOpenSources)
        }

        if (state.allChannels.isNotEmpty()) {
            Text(
                text = stringResource(R.string.live_hint_numbers),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
            )
        }
        state.errors.forEach { (name, message) ->
            Text(
                text = stringResource(R.string.live_source_error, name, message),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.Error,
                modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
            )
        }
        if (!state.guideLoading && state.guideReports.isNotEmpty()) {
            GuideHealthSummary(state.guideReports)
        }
        Spacer(Modifier.height(NuvioTheme.spacing.md))

        when {
            !state.settingsLoaded -> Box(Modifier.fillMaxSize(), Alignment.Center) { LoadingIndicator() }
            !state.sourcesLoaded -> Unit
            state.sources.isEmpty() -> EmptyState(onAdd = onOpenSources)
            state.allChannels.isEmpty() && state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { LoadingIndicator() }
            state.allChannels.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(stringResource(R.string.live_no_channels), color = NuvioTheme.colors.TextSecondary)
            }
            else -> Row(modifier = Modifier.fillMaxSize()) {
                GroupList(
                    state = state,
                    onSelect = onSelectGroup,
                    modifier = Modifier.width(300.dp).fillMaxHeight()
                )
                Spacer(Modifier.width(NuvioTheme.spacing.lg))
                if (showGuide) {
                    GuideGrid(
                        channels = state.channels,
                        guide = state.guide,
                        nowMs = state.nowMs,
                        showChannelNumbers = showChannelNumbers,
                        onPlay = onPlay,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                } else {
                    ChannelList(
                        state = state,
                        onPlay = onPlay,
                        onToggleFavorite = onToggleFavorite,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideHealthSummary(reports: List<EpgSourceReport>) {
    val available = reports.filter { it.status == EpgSourceStatus.AVAILABLE }
    val notConfiguredChannels = reports
        .filter { it.status == EpgSourceStatus.NOT_CONFIGURED }
        .sumOf { it.channelCount }
    val failedChannels = reports
        .filter { it.status == EpgSourceStatus.LOAD_ERROR }
        .sumOf { it.channelCount }

    if (available.isNotEmpty()) {
        val matched = available.sumOf { it.matched }
        val total = available.sumOf { it.channelCount }
        val ambiguous = available.sumOf { it.ambiguous }
        val unmatched = available.sumOf { it.unmatched }
        Text(
            text = stringResource(R.string.live_epg_match_report, matched, total, ambiguous, unmatched),
            style = MaterialTheme.typography.labelSmall,
            color = if (ambiguous + unmatched > 0) NuvioTheme.colors.Warning else NuvioTheme.colors.TextSecondary,
            modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
        )
    }
    if (notConfiguredChannels > 0) {
        Text(
            text = stringResource(R.string.live_epg_not_configured, notConfiguredChannels),
            style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.colors.Warning,
            modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
        )
    }
    if (failedChannels > 0) {
        Text(
            text = stringResource(R.string.live_epg_unavailable, failedChannels),
            style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.colors.Error,
            modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
        )
    }
}

@Composable
private fun HeaderButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.FocusContent
        )
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(NuvioTheme.spacing.xs))
        Text(label)
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.live_empty_title),
            style = MaterialTheme.typography.titleLarge,
            color = NuvioTheme.colors.TextPrimary
        )
        Spacer(Modifier.height(NuvioTheme.spacing.sm))
        Text(
            text = stringResource(R.string.live_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        Spacer(Modifier.height(NuvioTheme.spacing.lg))
        HeaderButton(Icons.Default.Settings, stringResource(R.string.live_add_source), onClick = onAdd)
    }
}

@Composable
private fun listColors() = ListItemDefaults.colors(
    containerColor = NuvioTheme.colors.Background,
    contentColor = NuvioTheme.colors.TextPrimary,
    focusedContainerColor = NuvioTheme.colors.FocusBackground,
    focusedContentColor = NuvioTheme.colors.FocusContent,
    selectedContainerColor = NuvioTheme.colors.BackgroundCard,
    selectedContentColor = NuvioTheme.colors.TextPrimary
)

@Composable
private fun GroupList(state: LiveTvUiState, onSelect: (String) -> Unit, modifier: Modifier) {
    val allLabel = stringResource(R.string.live_group_all)
    val favLabel = stringResource(R.string.live_group_favorites)
    LazyColumn(
        modifier = modifier.focusRestorer(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(bottom = TICKER_CLEARANCE)
    ) {
        items(state.groups, key = { it.key }) { group ->
            val title = when (group.key) {
                LiveTvViewModel.GROUP_ALL -> allLabel
                LiveTvViewModel.GROUP_FAVORITES -> favLabel
                else -> group.title
            }
            ListItem(
                selected = group.key == state.selectedGroup,
                onClick = { onSelect(group.key) },
                // Moving through the groups updates the channel list, as in most live TV apps.
                modifier = Modifier.onFocusChanged { if (it.isFocused) onSelect(group.key) },
                colors = listColors(),
                headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = { Text(group.count.toString(), style = MaterialTheme.typography.labelMedium) }
            )
        }
    }
}

@Composable
private fun ChannelList(
    state: LiveTvUiState,
    onPlay: (IptvChannel) -> Unit,
    onToggleFavorite: (IptvChannel) -> Unit,
    modifier: Modifier
) {
    val channels = state.channels
    val listState = rememberLazyListState()
    val currentFocus = remember { FocusRequester() }
    // Read when the screen (re)appears, e.g. after backing out of the player or zapping with CH+/CH-.
    val currentId = remember { LiveZapSession.currentId() }
    LaunchedEffect(Unit) {
        val index = channels.indexOfFirst { it.id == currentId }
        if (index >= 0) {
            listState.scrollToItem(index)
            withFrameNanos { }
            runCatching { currentFocus.requestFocus() }
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.focusRestorer(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(bottom = TICKER_CLEARANCE)
    ) {
        items(channels, key = { it.id }) { channel ->
            val (now, next) = IptvRepository.nowAndNext(state.guide[channel.id], state.nowMs)
            val supporting: (@Composable () -> Unit)? = if (now != null) {
                { ProgrammeLine(now, state.nowMs) }
            } else null
            val trailing: (@Composable () -> Unit)? = if (next != null) {
                {
                    Text(
                        text = stringResource(R.string.live_next, formatTime(next.startMs), next.title),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(260.dp)
                    )
                }
            } else null
            ListItem(
                selected = channel.id == currentId,
                onClick = { onPlay(channel) },
                modifier = if (channel.id == currentId) Modifier.focusRequester(currentFocus) else Modifier,
                onLongClick = { onToggleFavorite(channel) },
                colors = listColors(),
                leadingContent = {
                    Box(
                        modifier = Modifier
                            .size(width = 72.dp, height = 44.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(NuvioTheme.colors.BackgroundCard),
                        contentAlignment = Alignment.Center
                    ) {
                        if (channel.logoUrl != null) {
                            AsyncImage(
                                model = channel.logoUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize().padding(4.dp)
                            )
                        } else {
                            Text(channel.name.take(3).uppercase(), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                },
                headlineContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        channel.number?.takeIf { state.settings.showChannelNumbers }?.let {
                            Text("$it", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
                        }
                        Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (channel.id in state.favorites) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = stringResource(R.string.live_group_favorites),
                                modifier = Modifier.padding(start = 6.dp).size(16.dp)
                            )
                        }
                    }
                },
                supportingContent = supporting,
                trailingContent = trailing
            )
        }
    }
}

@Composable
private fun ProgrammeLine(programme: EpgProgramme, nowMs: Long) {
    Column {
        // Title first: it's what matters, and the time range is the part that can be cut off.
        Text(
            text = "${programme.title}  ·  ${formatTime(programme.startMs)}–${formatTime(programme.endMs)}",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (programme.endMs > programme.startMs) {
            val fraction = ((nowMs - programme.startMs).toFloat() / (programme.endMs - programme.startMs)).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .width(220.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(NuvioTheme.colors.Divider)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .background(NuvioTheme.colors.TextPrimary)
                )
            }
        }
    }
}

@Composable
private fun formatTime(ms: Long): String = rememberTimeFormatter()(ms)

/** Bottom space so the last rows can scroll above the sports ticker. */
private val TICKER_CLEARANCE = 96.dp
