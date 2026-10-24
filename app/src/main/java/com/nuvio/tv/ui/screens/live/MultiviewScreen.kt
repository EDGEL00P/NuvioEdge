@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.live

import android.content.Context
import android.view.ViewGroup
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.iptv.IptvChannel
import com.nuvio.tv.data.iptv.IptvRepository
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject

private const val TILE_STALL_MS = 10_000L
private const val TILE_START_GAP_MS = 600L

data class MultiviewState(
    val loading: Boolean = true,
    val channels: List<IptvChannel> = emptyList(),
    val tiles: List<IptvChannel?> = List(DecoderBudget.MAX_TILES) { null },
    /** Most tiles this device can play at once (hardware decoders, speed and memory). */
    val maxTiles: Int = 2,
    val tileCount: Int = 2,
    /** Set when the TV couldn't keep up and the layout was made smaller. */
    val reducedTo: Int? = null,
    val lowMemory: Boolean = false
)

/** The channel you were just watching first, then favourites; empty tiles after that. */
internal fun initialTiles(
    channels: List<IptvChannel>,
    favorites: Set<String>,
    currentId: String?,
    count: Int = DecoderBudget.MAX_TILES
): List<IptvChannel?> {
    val picks = (channels.filter { it.id == currentId } + channels.filter { it.id in favorites })
        .distinctBy { it.id }
        .take(count)
    return picks + List(count - picks.size) { null }
}

@HiltViewModel
class MultiviewViewModel @Inject constructor(
    private val repository: IptvRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(MultiviewState())
    val state: StateFlow<MultiviewState> = _state.asStateFlow()

    /** Stream client shared by the tile players: no disk cache, logging or crash breadcrumbs. */
    val streamClient: OkHttpClient get() = repository.streamClient

    init {
        viewModelScope.launch(liveErrors) {
            try {
                val profile = withContext(Dispatchers.Default) { DeviceProfile.read(context) }
                val maxTiles = DecoderBudget.tilesFor(profile)
                val sources = repository.sources.first()
                val channels = repository.loadChannels(sources, force = false).channels
                val favorites = repository.favorites.first()
                _state.value = MultiviewState(
                    loading = false,
                    channels = channels,
                    tiles = initialTiles(channels, favorites, LiveZapSession.currentId()),
                    maxTiles = maxTiles,
                    tileCount = DecoderBudget.bestOption(minOf(4, maxTiles)),
                    lowMemory = profile.lowRamDevice || profile.smallHeap
                )
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun setTile(index: Int, channel: IptvChannel) {
        _state.update { s -> s.copy(tiles = s.tiles.toMutableList().also { it[index] = channel }) }
    }

    fun setTileCount(count: Int) {
        _state.update { s -> s.copy(tileCount = DecoderBudget.bestOption(minOf(count, s.maxTiles)), reducedTo = null) }
    }

    private var lastReductionMs = 0L

    /** The TV ran out of decoders or kept dropping frames: drop to the next smaller layout. */
    fun onTvOverloaded() {
        // Several tiles usually report the same overload together; one step down per 15 s.
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastReductionMs != 0L && now - lastReductionMs < 15_000) return
        lastReductionMs = now
        _state.update { s ->
            if (s.tileCount <= 1) return@update s
            val limit = DecoderBudget.learnFailureAt(s.tileCount)
            val count = DecoderBudget.bestOption(limit)
            s.copy(maxTiles = minOf(s.maxTiles, limit), tileCount = count, reducedTo = count)
        }
    }
}

/**
 * 1, 2, 4 or 6 live channels at once, limited to what this TV's hardware can handle. D-pad moves
 * between tiles and only the highlighted tile decodes audio. OK picks a tile's channel; hold OK for
 * full screen. Each tile recovers on its own from freezes and errors, and the layout shrinks if
 * the TV can't keep up.
 */
@Composable
fun MultiviewScreen(
    onOpenFullScreen: (IptvChannel) -> Unit,
    viewModel: MultiviewViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var focused by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf<Int?>(null) }
    val firstTile = remember { FocusRequester() }
    val playing = remember { HashMap<Int, Boolean>() } // read only in callbacks, never in composition

    LaunchedEffect(state.loading) { if (!state.loading) runCatching { firstTile.requestFocus() } }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black).padding(8.dp)) {
        LayoutBar(state, onSelect = viewModel::setTileCount)
        val (columns, rows) = gridFor(state.tileCount)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (row in 0 until rows) {
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (col in 0 until columns) {
                        val index = row * columns + col
                        if (index >= state.tileCount) {
                            Box(Modifier.weight(1f))
                            continue
                        }
                        val channel = state.tiles[index]
                        Tile(
                            channel = channel,
                            settings = TileSettings(
                                hasAudio = index == focused,
                                tileCount = state.tileCount,
                                startDelayMs = index * TILE_START_GAP_MS,
                                lowMemory = state.lowMemory
                            ),
                            backup = channel?.let { backupCopies(it, state.channels).firstOrNull() },
                            streamClient = viewModel.streamClient,
                            onFocused = { focused = index },
                            onPick = { picking = index },
                            onOpen = {
                                channel?.let {
                                    LiveZapSession.start(state.channels, it, emptyMap(), state.channels)
                                    onOpenFullScreen(it)
                                }
                            },
                            events = TileEvents(
                                onPlaying = { playing[index] = it },
                                onFailover = { backup -> viewModel.setTile(index, backup) },
                                // Only blame the decoder limit when other tiles are using decoders right now.
                                onDecoderLimit = { if (playing.any { (i, on) -> on && i != index }) viewModel.onTvOverloaded() },
                                onOverloaded = viewModel::onTvOverloaded
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .then(if (index == 0) Modifier.focusRequester(firstTile) else Modifier)
                        )
                    }
                }
            }
        }
    }

    picking?.let { index ->
        ChannelPicker(
            channels = state.channels,
            onPick = { viewModel.setTile(index, it); picking = null },
            onDismiss = { picking = null }
        )
    }
}

private data class TileSettings(val hasAudio: Boolean, val tileCount: Int, val startDelayMs: Long, val lowMemory: Boolean)

private class TileEvents(
    val onPlaying: (Boolean) -> Unit,
    val onFailover: (IptvChannel) -> Unit,
    val onDecoderLimit: () -> Unit,
    val onOverloaded: () -> Unit
)

@Composable
private fun LayoutBar(state: MultiviewState, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.multiview_layout), color = Color(0xFF94A3B8))
        DecoderBudget.TILE_OPTIONS.forEach { count ->
            Button(
                onClick = { onSelect(count) },
                enabled = count <= state.maxTiles,
                colors = ButtonDefaults.colors(
                    containerColor = if (count == state.tileCount) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground
                )
            ) { Text("$count") }
        }
        Text(
            text = state.reducedTo?.let { stringResource(R.string.multiview_reduced, it) }
                ?: stringResource(R.string.multiview_device_limit, state.maxTiles),
            color = if (state.reducedTo != null) Color(0xFFFCD34D) else Color(0xFF94A3B8),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun Tile(
    channel: IptvChannel?,
    settings: TileSettings,
    backup: IptvChannel?,
    streamClient: OkHttpClient,
    onFocused: () -> Unit,
    onPick: () -> Unit,
    onOpen: () -> Unit,
    events: TileEvents,
    modifier: Modifier
) {
    var status by remember(channel?.id) { mutableStateOf<String?>(null) }

    Surface(
        onClick = onPick,
        onLongClick = { if (channel != null) onOpen() },
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF05070D),
            focusedContainerColor = Color(0xFF05070D)
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Color(0xFF1E293B))),
            focusedBorder = Border(BorderStroke(3.dp, NuvioTheme.colors.FocusRing))
        )
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (channel == null) {
                Text(stringResource(R.string.multiview_empty_tile), color = Color(0xFF94A3B8))
            } else {
                TilePlayer(channel, settings, backup, streamClient, events) { status = it }
                Text(
                    text = channel.name,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color(0xB3000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
                status?.let {
                    Text(
                        text = stringResource(R.string.multiview_tile_recovering, it),
                        color = Color(0xFFFCD34D),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                            .background(Color(0xB3000000), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

private fun PlaybackException.isDecoderLimit(): Boolean =
    errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED

@AndroidXOptIn(UnstableApi::class)
@Composable
private fun TilePlayer(
    channel: IptvChannel,
    settings: TileSettings,
    backup: IptvChannel?,
    streamClient: OkHttpClient,
    events: TileEvents,
    onStatus: (String?) -> Unit
) {
    val context = LocalContext.current
    val player = remember(channel.id) { buildTilePlayer(context, channel, streamClient, settings.lowMemory) }
    val currentEvents by rememberUpdatedState(events)
    val currentBackup by rememberUpdatedState(backup)
    DisposableEffect(player) {
        onDispose {
            currentEvents.onPlaying(false)
            player.release()
        }
    }

    // Only the highlighted tile decodes audio: saves CPU and keeps tiles from fighting over the audio output.
    LaunchedEffect(player, settings.hasAudio) {
        player.volume = if (settings.hasAudio) 1f else 0f
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !settings.hasAudio)
            .build()
    }
    // Smaller tiles ask for smaller video, so adaptive streams pick lighter variants.
    LaunchedEffect(player, settings.tileCount) {
        val (width, height) = maxTileVideoSize(settings.tileCount)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(width, height)
            .build()
    }
    // Start tiles one after another (not every decoder and connection at once), then watch each one:
    // an error, or 10 s stuck buffering, restarts it at the live edge with growing pauses; after
    // repeated trouble it moves to a backup copy of the channel. Running out of decoders or dropping
    // frames for several seconds shrinks the layout instead, since retrying can't fix those.
    LaunchedEffect(player) {
        delay(settings.startDelayMs)
        player.prepare()
        val recovery = TileRecovery()
        val overload = OverloadMonitor()
        var stuckForMs = 0L
        var lastDropped = 0
        var lastRendered = 0
        while (isActive) {
            delay(1_000)
            val error = player.playerError
            val ready = player.playbackState == Player.STATE_READY && error == null
            currentEvents.onPlaying(ready)
            if (ready) {
                recovery.onPlaying()
                onStatus(null)
                player.videoDecoderCounters?.let { counters ->
                    counters.ensureUpdated()
                    val dropped = counters.droppedBufferCount
                    val rendered = counters.renderedOutputBufferCount
                    if (overload.onSecond(dropped - lastDropped, rendered - lastRendered)) {
                        overload.reset()
                        currentEvents.onOverloaded()
                    }
                    lastDropped = dropped
                    lastRendered = rendered
                }
            }
            val stuck = player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE
            stuckForMs = if (stuck && error == null) stuckForMs + 1_000 else 0L
            if (error == null && stuckForMs < TILE_STALL_MS) continue

            stuckForMs = 0L
            onStatus(error?.errorCodeName ?: "STALLED")
            if (error?.isDecoderLimit() == true) currentEvents.onDecoderLimit()
            when (val action = recovery.onProblem(hasBackup = currentBackup != null)) {
                TileRecovery.Action.Failover -> {
                    currentBackup?.let(currentEvents.onFailover)
                    return@LaunchedEffect
                }
                is TileRecovery.Action.Restart -> {
                    delay(action.delayMs)
                    lastDropped = 0
                    lastRendered = 0
                    overload.reset()
                    player.seekToDefaultPosition()
                    player.prepare()
                    player.playWhenReady = true
                }
            }
        }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                this.player = player
            }
        },
        update = { it.player = player },
        modifier = Modifier.fillMaxSize().focusProperties { canFocus = false }
    )
}

/** Small, byte-capped buffers (smaller still on low-memory boxes) so several streams fit in a TV's RAM. */
@AndroidXOptIn(UnstableApi::class)
private fun buildTilePlayer(context: Context, channel: IptvChannel, client: OkHttpClient, lowMemory: Boolean): ExoPlayer {
    val http = OkHttpDataSource.Factory(client).setDefaultRequestProperties(channel.headers)
    val (minMs, maxMs) = if (lowMemory) 2_500 to 8_000 else 4_000 to 15_000
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(minMs, maxMs, 1_500, 2_500)
        .setTargetBufferBytes(tileBufferBytes(lowMemory))
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
        .setLoadControl(loadControl)
        .build()
        .apply {
            volume = 0f
            setMediaItem(MediaItem.fromUri(channel.streamUrl))
            playWhenReady = true
        }
}

@Composable
private fun ChannelPicker(channels: List<IptvChannel>, onPick: (IptvChannel) -> Unit, onDismiss: () -> Unit) {
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.multiview_pick_title), width = 560.dp) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(channels, key = { it.id }) { channel ->
                ListItem(
                    selected = false,
                    onClick = { onPick(channel) },
                    colors = ListItemDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground,
                        focusedContentColor = NuvioTheme.colors.FocusContent
                    ),
                    headlineContent = {
                        Text(listOfNotNull(channel.number?.toString(), channel.name).joinToString("  "))
                    },
                    supportingContent = { Text(channel.group, style = MaterialTheme.typography.bodySmall) }
                )
            }
        }
    }
}
