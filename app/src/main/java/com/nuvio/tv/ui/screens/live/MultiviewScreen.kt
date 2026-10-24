@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.live

import android.content.Context
import android.view.ViewGroup
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.iptv.IptvChannel
import com.nuvio.tv.data.iptv.IptvRepository
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

private const val TILE_COUNT = 4

data class MultiviewState(
    val loading: Boolean = true,
    val channels: List<IptvChannel> = emptyList(),
    val tiles: List<IptvChannel?> = List(TILE_COUNT) { null }
)

/** The channel you were just watching first, then favourites; empty tiles after that. */
internal fun initialTiles(
    channels: List<IptvChannel>,
    favorites: Set<String>,
    currentId: String?,
    count: Int = TILE_COUNT
): List<IptvChannel?> {
    val picks = (channels.filter { it.id == currentId } + channels.filter { it.id in favorites })
        .distinctBy { it.id }
        .take(count)
    return picks + List(count - picks.size) { null }
}

@HiltViewModel
class MultiviewViewModel @Inject constructor(
    private val repository: IptvRepository
) : ViewModel() {

    private val _state = MutableStateFlow(MultiviewState())
    val state: StateFlow<MultiviewState> = _state.asStateFlow()

    /** Stream client shared by the tile players: no disk cache, logging or crash breadcrumbs. */
    val streamClient: OkHttpClient get() = repository.streamClient

    init {
        viewModelScope.launch {
            val sources = repository.sources.first()
            val channels = repository.loadChannels(sources, force = false).channels
            val favorites = repository.favorites.first()
            _state.value = MultiviewState(
                loading = false,
                channels = channels,
                tiles = initialTiles(channels, favorites, LiveZapSession.currentId())
            )
        }
    }

    fun setTile(index: Int, channel: IptvChannel) {
        _state.update { s -> s.copy(tiles = s.tiles.toMutableList().also { it[index] = channel }) }
    }
}

/**
 * Up to four live channels at once. D-pad moves between tiles and the highlighted tile has the sound.
 * OK chooses a tile's channel; holding OK opens it full screen.
 *
 * How many streams play smoothly depends on the TV's hardware video decoders; a tile that can't start
 * shows an error instead of affecting the others.
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

    LaunchedEffect(state.loading) { if (!state.loading) runCatching { firstTile.requestFocus() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (row in 0 until 2) {
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (col in 0 until 2) {
                    val index = row * 2 + col
                    Tile(
                        channel = state.tiles[index],
                        hasAudio = index == focused,
                        streamClient = viewModel.streamClient,
                        onFocused = { focused = index },
                        onPick = { picking = index },
                        onOpen = {
                            state.tiles[index]?.let { channel ->
                                LiveZapSession.start(state.channels, channel, emptyMap(), state.channels)
                                onOpenFullScreen(channel)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .then(if (index == 0) Modifier.focusRequester(firstTile) else Modifier)
                    )
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

@Composable
private fun Tile(
    channel: IptvChannel?,
    hasAudio: Boolean,
    streamClient: OkHttpClient,
    onFocused: () -> Unit,
    onPick: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier
) {
    var error by remember(channel?.id) { mutableStateOf<String?>(null) }

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
            TilePlayer(channel, hasAudio, streamClient, onError = { error = it })
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
            error?.let {
                Text(
                    text = stringResource(R.string.multiview_tile_error, it),
                    color = Color(0xFFFCA5A5),
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
      }
    }
}

@AndroidXOptIn(UnstableApi::class)
@Composable
private fun TilePlayer(channel: IptvChannel, hasAudio: Boolean, streamClient: OkHttpClient, onError: (String) -> Unit) {
    val context = LocalContext.current
    val player = remember(channel.id) { buildTilePlayer(context, channel, streamClient) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = onError(error.errorCodeName)
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player, hasAudio) { player.volume = if (hasAudio) 1f else 0f }
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

/** Small buffers: four streams at once must fit in a TV's memory. */
@AndroidXOptIn(UnstableApi::class)
private fun buildTilePlayer(context: Context, channel: IptvChannel, client: OkHttpClient): ExoPlayer {
    val http = OkHttpDataSource.Factory(client).setDefaultRequestProperties(channel.headers)
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(4_000, 15_000, 1_500, 2_500)
        .build()
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
        .setLoadControl(loadControl)
        .build()
        .apply {
            volume = 0f
            setMediaItem(MediaItem.fromUri(channel.streamUrl))
            playWhenReady = true
            prepare()
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
