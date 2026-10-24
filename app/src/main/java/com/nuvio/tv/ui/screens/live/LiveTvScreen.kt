@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.live

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
import androidx.compose.material.icons.filled.Refresh
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
import com.nuvio.tv.data.iptv.IptvChannel
import com.nuvio.tv.data.iptv.IptvRepository
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.DateFormat
import java.util.Date

@Composable
fun LiveTvScreen(
    onPlayChannel: (IptvChannel, String?) -> Unit,
    viewModel: LiveTvViewModel = hiltViewModel(),
    tickerViewModel: com.nuvio.tv.ui.components.ticker.SportsTickerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tickerOn by tickerViewModel.enabled.collectAsStateWithLifecycle()
    var showSources by remember { mutableStateOf(false) }

    LiveTvContent(
        state = state,
        tickerOn = tickerOn,
        onRefresh = viewModel::refresh,
        onToggleTicker = tickerViewModel::toggle,
        onOpenSources = { showSources = true },
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
}

/** The Live TV layout, without view models, so it can be previewed and rendered in tests. */
@Composable
internal fun LiveTvContent(
    state: LiveTvUiState,
    tickerOn: Boolean,
    onRefresh: () -> Unit,
    onToggleTicker: () -> Unit,
    onOpenSources: () -> Unit,
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
            HeaderButton(Icons.Default.Refresh, stringResource(R.string.live_refresh), enabled = !state.loading, onClick = onRefresh)
            Spacer(Modifier.width(NuvioTheme.spacing.sm))
            HeaderButton(
                Icons.Default.Star,
                stringResource(if (tickerOn) R.string.ticker_on else R.string.ticker_off),
                onClick = onToggleTicker
            )
            Spacer(Modifier.width(NuvioTheme.spacing.sm))
            HeaderButton(Icons.Default.Settings, stringResource(R.string.live_sources), onClick = onOpenSources)
        }

        if (state.allChannels.isNotEmpty()) {
            Text(
                text = stringResource(R.string.live_hint),
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
        Spacer(Modifier.height(NuvioTheme.spacing.md))

        when {
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
                        channel.number?.let {
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

private fun formatTime(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

/** Bottom space so the last rows can scroll above the sports ticker. */
private val TICKER_CLEARANCE = 96.dp
