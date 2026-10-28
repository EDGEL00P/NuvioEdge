@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.live

import com.nuvio.tv.ui.screens.account.InputField
import androidx.compose.runtime.produceState
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
import androidx.compose.foundation.layout.width
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
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
    val lowMemory: Boolean = false,
    /** Streams the IPTV account allows at once, when it is lower than what the TV can do. */
    val accountLimit: Int? = null,
    val assignmentBlocked: Boolean = false
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

    /** Built off the main thread once the channels arrive; null until then. */
    @Volatile
    private var backups: BackupIndex? = null
    @Volatile
    private var accountBudgets: Map<String, MultiviewAccountBudget> = emptyMap()

    /** A backup copy of [channel], looked up only when a tile actually fails. */
    fun backupFor(index: Int, channel: IptvChannel): IptvChannel? =
        backups?.copiesOf(channel)?.firstOrNull { canAssign(index, it) }

    fun canAssign(index: Int, channel: IptvChannel): Boolean {
        val current = _state.value
        return canAssignMultiviewChannel(current.tiles, index, channel, current.tileCount, accountBudgets)
    }

    init {
        viewModelScope.launch(liveErrors) {
            try {
                val profile = withContext(Dispatchers.Default) { DeviceProfile.read(context) }
                val deviceMax = DecoderBudget.tilesFor(profile)
                val sources = repository.sources.first()
                val channels = repository.loadChannels(sources, force = false).channels
                val sourceBudgets = sources.filter { it.enabled }.mapNotNull { source ->
                    val limit = repository.connectionLimit(listOf(source.id)) ?: return@mapNotNull null
                    val identity = com.nuvio.tv.data.iptv.XtreamClient.normalizeServer(source.url) + "\u0000" + source.username
                    val accountKey = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(identity.toByteArray(Charsets.UTF_8))
                        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                    source.id to MultiviewAccountBudget(accountKey, limit.coerceAtLeast(1))
                }
                val limitsByAccount = sourceBudgets.map { it.second }.groupBy { it.accountKey }
                    .mapValues { (_, budgets) -> budgets.minOf { it.maxStreams } }
                accountBudgets = sourceBudgets.associate { (sourceId, budget) ->
                    sourceId to budget.copy(maxStreams = limitsByAccount.getValue(budget.accountKey))
                }
                val activeSources = channels.mapTo(HashSet()) { it.sourceId }
                val combinedCapacity = if (activeSources.any { it !in accountBudgets }) {
                    deviceMax
                } else {
                    activeSources.mapNotNull(accountBudgets::get).distinctBy { it.accountKey }
                        .sumOf { it.maxStreams.coerceAtMost(deviceMax) }.coerceAtLeast(1)
                }
                val accountLimit = combinedCapacity.takeIf { it < deviceMax }
                val maxTiles = minOf(deviceMax, combinedCapacity)
                val tileCount = DecoderBudget.bestOption(minOf(4, maxTiles))
                val favorites = repository.favorites.first()
                _state.value = MultiviewState(
                    loading = false,
                    channels = channels,
                    tiles = enforceMultiviewBudgets(
                        initialTiles(channels, favorites, LiveZapSession.currentId()), tileCount, accountBudgets
                    ),
                    maxTiles = maxTiles,
                    tileCount = tileCount,
                    lowMemory = profile.lowRamDevice || profile.smallHeap,
                    accountLimit = accountLimit
                )
                backups = withContext(Dispatchers.Default) { BackupIndex(channels) }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun setTile(index: Int, channel: IptvChannel): Boolean {
        var accepted = false
        _state.update { current ->
            if (!canAssignMultiviewChannel(current.tiles, index, channel, current.tileCount, accountBudgets)) {
                accepted = false
                current.copy(assignmentBlocked = true)
            } else {
                accepted = true
                current.copy(tiles = current.tiles.toMutableList().also { it[index] = channel }, assignmentBlocked = false)
            }
        }
        return accepted
    }

    fun setTileCount(count: Int) {
        _state.update { current ->
            val active = DecoderBudget.bestOption(minOf(count, current.maxTiles))
            current.copy(
                tileCount = active,
                tiles = enforceMultiviewBudgets(current.tiles, active, accountBudgets),
                reducedTo = null,
                assignmentBlocked = false
            )
        }
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

    // Stop every tile when the app leaves the screen (Home, screensaver, another app): otherwise
    // 2-6 streams keep downloading and decoding, with sound, behind the launcher.
    var inForeground by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> inForeground = true
                Lifecycle.Event.ON_STOP -> inForeground = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.loading) { if (!state.loading) runCatching { firstTile.requestFocus() } }

    // TV safe area: 48dp sides, 27dp top and bottom (Android TV layout guidelines).
    Column(modifier = Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(horizontal = 48.dp, vertical = 27.dp)) {
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
                            active = inForeground,
                            settings = TileSettings(
                                hasAudio = index == focused,
                                tileCount = state.tileCount,
                                startDelayMs = index * TILE_START_GAP_MS,
                                lowMemory = state.lowMemory
                            ),
                            findBackup = { channel?.let { viewModel.backupFor(index, it) } },
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
            onPick = { if (viewModel.setTile(index, it)) picking = null },
            onDismiss = { picking = null },
            canPick = { viewModel.canAssign(index, it) }
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
        Text(stringResource(R.string.multiview_layout), color = NuvioTheme.colors.TextSecondary)
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
            text = if (state.assignmentBlocked) stringResource(R.string.multiview_account_busy) else state.reducedTo?.let { stringResource(R.string.multiview_reduced, it) }
                ?: state.accountLimit?.let { stringResource(R.string.multiview_account_limit, it) }
                ?: stringResource(R.string.multiview_device_limit, state.maxTiles),
            color = if (state.reducedTo != null || state.assignmentBlocked) NuvioTheme.colors.Warning else NuvioTheme.colors.TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun Tile(
    channel: IptvChannel?,
    active: Boolean,
    settings: TileSettings,
    findBackup: () -> IptvChannel?,
    streamClient: OkHttpClient,
    onFocused: () -> Unit,
    onPick: () -> Unit,
    onOpen: () -> Unit,
    events: TileEvents,
    modifier: Modifier
) {
    var status by remember(channel?.id) { mutableStateOf<TileProblem?>(null) }

    Surface(
        onClick = onPick,
        onLongClick = { if (channel != null) onOpen() },
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.BackgroundCard
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, NuvioTheme.colors.Border)),
            focusedBorder = Border(BorderStroke(3.dp, NuvioTheme.colors.FocusRing))
        )
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (channel == null) {
                Text(stringResource(R.string.multiview_empty_tile), color = NuvioTheme.colors.TextSecondary)
            } else {
                if (active) TilePlayer(channel, settings, findBackup, streamClient, events) { status = it }
                Text(
                    text = channel.name,
                    color = NuvioTheme.colors.TextPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(NuvioTheme.colors.PlayerOverlay, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
                status?.let { problem ->
                    Text(
                        text = if (problem.gaveUp) stringResource(R.string.multiview_tile_offline)
                        else stringResource(R.string.multiview_tile_recovering, problem.code),
                        color = NuvioTheme.colors.Warning,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                            .background(NuvioTheme.colors.PlayerOverlay, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

/** What a tile shows while it recovers ([gaveUp]: retries stopped). */
private data class TileProblem(val code: String, val gaveUp: Boolean = false)

private fun PlaybackException.isDecoderLimit(): Boolean =
    errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED

@AndroidXOptIn(UnstableApi::class)
@Composable
private fun TilePlayer(
    channel: IptvChannel,
    settings: TileSettings,
    findBackup: () -> IptvChannel?,
    streamClient: OkHttpClient,
    events: TileEvents,
    onStatus: (TileProblem?) -> Unit
) {
    val context = LocalContext.current
    val player = remember(channel.id) { buildTilePlayer(context, channel, streamClient, settings.lowMemory) }
    val currentEvents by rememberUpdatedState(events)
    val currentFindBackup by rememberUpdatedState(findBackup)
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
            onStatus(TileProblem(error?.errorCodeName ?: "STALLED"))
            if (error?.isDecoderLimit() == true) currentEvents.onDecoderLimit()
            val backup = currentFindBackup()
            when (val action = recovery.onProblem(hasBackup = backup != null)) {
                TileRecovery.Action.Failover -> {
                    backup?.let(currentEvents.onFailover)
                    return@LaunchedEffect
                }
                TileRecovery.Action.GiveUp -> {
                    player.stop()
                    onStatus(TileProblem(error?.errorCodeName ?: "STALLED", gaveUp = true))
                    return@LaunchedEffect
                }
                is TileRecovery.Action.Restart -> {
                    delay(action.delayMs)
                    lastDropped = 0
                    lastRendered = 0
                    overload.reset()
                    // prepare() does nothing unless the player is idle, and a seek to the current
                    // position is dropped, so a tile stuck buffering needs stop() first.
                    player.stop()
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
                // Keep the TV awake while tiles play; tied to this view, so it ends when the tile goes.
                keepScreenOn = true
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
    // Same default User-Agent as the full-screen player when the playlist sets none.
    if (channel.headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
        http.setUserAgent(PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
    }
    com.nuvio.tv.data.iptv.LiveStreamGuard.protect(channel.streamUrl)
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

/** Channels in [group] (null = all) whose name contains [query], any case. */
internal fun filterChannels(channels: List<IptvChannel>, query: String, group: String?): List<IptvChannel> {
    val q = query.trim()
    return channels.filter { (group == null || it.group == group) && (q.isEmpty() || it.name.contains(q, ignoreCase = true)) }
}

@Composable
private fun ChannelPicker(
    channels: List<IptvChannel>,
    onPick: (IptvChannel) -> Unit,
    onDismiss: () -> Unit,
    canPick: (IptvChannel) -> Boolean = { true }
) {
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf<String?>(null) }
    val groups = remember(channels) { channels.map { it.group }.distinct() }
    // Filtering 50k+ channels runs off the main thread, after typing pauses.
    val shown by produceState(channels, channels, query, group) {
        delay(200)
        value = withContext(Dispatchers.Default) { filterChannels(channels, query, group) }
    }
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.multiview_pick_title), width = 860.dp) {
        InputField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.multiview_pick_search),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LazyColumn(modifier = Modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item { PickerRow(stringResource(R.string.multiview_pick_all), selected = group == null) { group = null } }
                items(groups) { name -> PickerRow(name, selected = group == name) { group = name } }
            }
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(shown, key = { it.id }) { channel ->
                    PickerRow(
                        text = listOfNotNull(channel.number?.toString(), channel.name).joinToString("  "),
                        selected = false,
                        enabled = canPick(channel),
                        onClick = { onPick(channel) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerRow(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        colors = ListItemDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            selectedContainerColor = NuvioTheme.colors.BackgroundElevated,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.FocusContent
        ),
        headlineContent = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    )
}
