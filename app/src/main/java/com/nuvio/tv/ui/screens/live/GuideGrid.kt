@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.live

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.iptv.EpgProgramme
import com.nuvio.tv.data.iptv.IptvChannel
import com.nuvio.tv.data.iptv.IptvRepository
import com.nuvio.tv.ui.theme.NuvioTheme

private const val SLOT_MS = 30 * 60_000L
private const val WINDOW_SLOTS = 6 // 3 hours on screen
private const val WINDOW_MS = WINDOW_SLOTS * SLOT_MS
private const val PAST_MS = 2 * 60 * 60_000L // the guide parser keeps 2 h of past
private const val FUTURE_MS = 24 * 60 * 60_000L
private const val MIN_CELL_MS = 60_000L

/** One box in a channel's row: a programme clipped to the window, or a gap with no guide data. */
internal data class GuideCell(val programme: EpgProgramme?, val startMs: Long, val endMs: Long)

/** Start of the half hour containing [nowMs]. */
internal fun windowStartFor(nowMs: Long): Long = nowMs - Math.floorMod(nowMs, SLOT_MS)

/**
 * Cells covering [windowStart, windowEnd) for one channel: programmes clipped to the window,
 * gaps filled with empty cells, overlapping or sub-minute entries dropped.
 */
internal fun guideCells(programmes: List<EpgProgramme>?, windowStart: Long, windowEnd: Long): List<GuideCell> {
    val cells = ArrayList<GuideCell>()
    var cursor = windowStart
    for (p in programmes.orEmpty()) {
        if (p.endMs <= cursor || p.startMs >= windowEnd) continue
        val start = maxOf(p.startMs, cursor)
        val end = minOf(p.endMs, windowEnd)
        if (end - start < MIN_CELL_MS) continue
        if (start > cursor) cells += GuideCell(null, cursor, start)
        cells += GuideCell(p, start, end)
        cursor = end
    }
    if (windowEnd - cursor >= MIN_CELL_MS) cells += GuideCell(null, cursor, windowEnd)
    else if (cells.isNotEmpty()) cells[cells.lastIndex] = cells.last().copy(endMs = windowEnd)
    else cells += GuideCell(null, windowStart, windowEnd)
    return cells
}

/**
 * Timeline guide: channels down the side, three hours across. D-pad moves between programmes;
 * stepping past either edge moves the window by half an hour. OK plays the channel.
 */
@Composable
internal fun GuideGrid(
    channels: List<IptvChannel>,
    guide: Map<String, List<EpgProgramme>>,
    nowMs: Long,
    showChannelNumbers: Boolean = true,
    onPlay: (IptvChannel, EpgProgramme?) -> Unit,
    onPlayArchive: (IptvChannel, EpgProgramme) -> Unit,
    modifier: Modifier = Modifier
) {
    val timeFormat = rememberTimeFormatter()
    val nowSlot = windowStartFor(nowMs)
    var windowStart by remember { mutableLongStateOf(nowSlot) }
    val windowEnd = windowStart + WINDOW_MS
    var focused by remember { mutableStateOf<Pair<IptvChannel, GuideCell>?>(null) }
    // After the window moves, focus goes back to this channel at this time.
    var target by remember { mutableStateOf<Pair<String, Long>?>(null) }

    Column(modifier) {
        GuideInfo(focused, timeFormat)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val nameWidth = 220.dp
            val gridWidth = maxWidth - nameWidth
            val dpPerMs = gridWidth / WINDOW_MS.toFloat()
            Column {
                Row(Modifier.fillMaxWidth().height(28.dp)) {
                    Spacer(Modifier.width(nameWidth))
                    Box(Modifier.width(gridWidth)) {
                        for (slot in 0 until WINDOW_SLOTS) {
                            val slotStart = windowStart + slot * SLOT_MS
                            Text(
                                text = timeFormat(slotStart),
                                style = MaterialTheme.typography.labelMedium,
                                color = NuvioTheme.colors.TextSecondary,
                                modifier = Modifier.offset(x = dpPerMs * (slot * SLOT_MS).toFloat()).padding(start = 4.dp)
                            )
                        }
                    }
                }
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(channels, key = { it.id }) { channel ->
                        Row(Modifier.fillMaxWidth().height(56.dp)) {
                            Text(
                                text = listOfNotNull(channel.number?.takeIf { showChannelNumbers }?.toString(), channel.name).joinToString("  "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(nameWidth).padding(end = 8.dp, top = 6.dp)
                            )
                            Box(Modifier.width(gridWidth).fillMaxHeight()) {
                                val programmes = guide[channel.id]
                                val cells = remember(programmes, windowStart) { guideCells(programmes, windowStart, windowEnd) }
                                for (cell in cells) {
                                    val requester = remember { FocusRequester() }
                                    val isTarget = target?.let { (id, at) -> id == channel.id && at >= cell.startMs && at < cell.endMs } == true
                                    LaunchedEffect(isTarget) {
                                        if (isTarget) {
                                            runCatching { requester.requestFocus() }
                                            target = null
                                        }
                                    }
                                    GuideCellView(
                                        cell = cell,
                                        airing = cell.programme != null && cell.startMs <= nowMs && nowMs < cell.endMs,
                                        catchupAvailable = channel.catchupDays > 0 &&
                                            cell.programme?.let {
                                                it.endMs <= nowMs && it.endMs - it.startMs >= MIN_ARCHIVE_ICON_DURATION_MS &&
                                                    IptvRepository.isCatchupAvailable(channel, it, nowMs)
                                            } == true,
                                        restartAvailable = channel.catchupDays > 0 &&
                                            cell.programme?.let {
                                                it.startMs <= nowMs && nowMs < it.endMs &&
                                                    it.endMs - it.startMs >= MIN_ARCHIVE_ICON_DURATION_MS &&
                                                    IptvRepository.isCatchupAvailable(channel, it, nowMs)
                                            } == true,
                                        onClick = { onPlay(channel, cell.programme) },
                                        onPlayArchive = { cell.programme?.let { onPlayArchive(channel, it) } },
                                        modifier = Modifier
                                            .offset { IntOffset((dpPerMs * (cell.startMs - windowStart).toFloat()).roundToPx(), 0) }
                                            .width(dpPerMs * (cell.endMs - cell.startMs).toFloat())
                                            .fillMaxHeight()
                                            .padding(2.dp)
                                            .focusRequester(requester)
                                            .onFocusChanged { if (it.isFocused) focused = channel to cell }
                                            .onPreviewKeyEvent { event ->
                                                val native = event.nativeKeyEvent
                                                if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                                                when {
                                                    native.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && cell.endMs >= windowEnd &&
                                                        windowStart + WINDOW_MS < nowSlot + FUTURE_MS -> {
                                                        target = channel.id to windowEnd
                                                        windowStart += SLOT_MS
                                                        true
                                                    }
                                                    native.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && cell.startMs <= windowStart &&
                                                        windowStart > nowSlot - PAST_MS -> {
                                                        target = channel.id to windowStart - 1
                                                        windowStart -= SLOT_MS
                                                        true
                                                    }
                                                    else -> false
                                                }
                                            }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideCellView(
    cell: GuideCell,
    airing: Boolean,
    catchupAvailable: Boolean,
    restartAvailable: Boolean,
    onClick: () -> Unit,
    onPlayArchive: () -> Unit,
    modifier: Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (airing) NuvioTheme.colors.BackgroundElevated else NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContentColor = NuvioTheme.colors.FocusContent
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(
                text = cell.programme?.title?.ifBlank { null } ?: stringResource(R.string.live_guide_no_info),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (catchupAvailable || restartAvailable) {
                IconButton(onClick = onPlayArchive, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Replay,
                        contentDescription = stringResource(
                            if (restartAvailable) R.string.live_restart_available else R.string.live_catchup_available
                        ),
                        modifier = Modifier.size(16.dp),
                        tint = NuvioTheme.colors.Primary
                    )
                }
            }
        }
    }
}

private const val MIN_ARCHIVE_ICON_DURATION_MS = 15 * 60_000L

/** Details of the highlighted programme, above the grid. Fixed height so the grid doesn't jump. */
@Composable
private fun GuideInfo(focused: Pair<IptvChannel, GuideCell>?, timeFormat: (Long) -> String) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(84.dp)
            .background(NuvioTheme.colors.BackgroundCard, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        val (channel, cell) = focused ?: return@Column
        val programme = cell.programme
        Text(
            text = programme?.title?.ifBlank { null } ?: stringResource(R.string.live_guide_no_info),
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = listOfNotNull(
                programme?.let { "${timeFormat(it.startMs)} – ${timeFormat(it.endMs)}" },
                channel.name
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextSecondary,
            maxLines = 1
        )
        programme?.description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
