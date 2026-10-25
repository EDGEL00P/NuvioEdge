@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.iptv.EpgProgramme
import com.nuvio.tv.data.iptv.IptvChannel
import com.nuvio.tv.data.iptv.XmltvParser
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.DateFormat
import java.util.Date

/**
 * Other copies of each channel (same guide id or same normalised name), for automatic failover.
 * Built once per channel list, off the main thread; a lookup is then a couple of map reads instead
 * of normalising every channel name again (which froze the UI on 20k+ channel playlists).
 */
internal class BackupIndex(private val channels: List<IptvChannel>) {
    private val position = HashMap<String, Int>(channels.size * 2)
    private val byGuideId = HashMap<String, MutableList<IptvChannel>>()
    private val byName = HashMap<String, MutableList<IptvChannel>>()

    init {
        channels.forEachIndexed { i, channel ->
            position[channel.id] = i
            channel.epgId?.lowercase()?.let { byGuideId.getOrPut(it) { ArrayList(2) }.add(channel) }
            nameKey(channel).takeIf { it.isNotEmpty() }?.let { byName.getOrPut(it) { ArrayList(2) }.add(channel) }
        }
    }

    /** True when this index was built for exactly [list] (same instance). */
    fun isFor(list: List<IptvChannel>): Boolean = list === channels

    /** Copies of [channel], in playlist order; never the channel itself or the same stream URL. */
    fun copiesOf(channel: IptvChannel): List<IptvChannel> {
        val byId = channel.epgId?.lowercase()?.let { byGuideId[it] }.orEmpty()
        val byNameMatch = nameKey(channel).takeIf { it.isNotEmpty() }?.let { byName[it] }.orEmpty()
        return (byId + byNameMatch)
            .asSequence()
            .filter { it.id != channel.id && it.streamUrl != channel.streamUrl }
            .distinctBy { it.id }
            .sortedBy { position[it.id] ?: Int.MAX_VALUE }
            .toList()
    }

    private fun nameKey(channel: IptvChannel) = XmltvParser.normalizeChannelName(channel.epgName ?: channel.name)
}

/** One-off lookup (tests, small lists). Hot paths keep a [BackupIndex]. */
internal fun backupCopies(channel: IptvChannel, all: List<IptvChannel>): List<IptvChannel> =
    BackupIndex(all).copiesOf(channel)

data class LiveSearchHit(val channel: IptvChannel, val programme: EpgProgramme?, val airingNow: Boolean)

/**
 * Finds [query] in channel names and in guide titles that haven't finished yet.
 * Channel-name matches first, then programmes airing now, then upcoming ones soonest first.
 */
internal fun searchLive(
    query: String,
    channels: List<IptvChannel>,
    guide: Map<String, List<EpgProgramme>>,
    nowMs: Long,
    limit: Int = 60
): List<LiveSearchHit> {
    val q = query.trim()
    if (q.length < 2) return emptyList()
    val byName = channels.filter { it.name.contains(q, ignoreCase = true) }
        .map { LiveSearchHit(it, null, airingNow = false) }
    val byGuide = channels.flatMap { channel ->
        guide[channel.id].orEmpty()
            .filter { it.endMs > nowMs && it.title.contains(q, ignoreCase = true) }
            .map { LiveSearchHit(channel, it, airingNow = it.startMs <= nowMs) }
    }.sortedWith(compareByDescending<LiveSearchHit> { it.airingNow }.thenBy { it.programme?.startMs ?: Long.MAX_VALUE })
    return (byName + byGuide).take(limit)
}

@Composable
internal fun LiveSearchDialog(
    channels: List<IptvChannel>,
    guide: Map<String, List<EpgProgramme>>,
    nowMs: Long,
    onPlay: (IptvChannel) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val hits = remember(query, channels, guide, nowMs) { searchLive(query, channels, guide, nowMs) }
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.live_search_title), width = 720.dp) {
        InputField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.live_search_hint),
            modifier = Modifier.fillMaxWidth()
        )
        if (query.trim().length >= 2 && hits.isEmpty()) {
            Text(stringResource(R.string.live_search_none), color = NuvioTheme.colors.TextSecondary)
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(hits, key = { i, hit -> "$i:" + hit.channel.id }) { _, hit ->
                ListItem(
                    selected = false,
                    onClick = { onPlay(hit.channel) },
                    colors = ListItemDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground,
                        focusedContentColor = NuvioTheme.colors.FocusContent
                    ),
                    headlineContent = { Text(hit.programme?.title ?: hit.channel.name) },
                    supportingContent = {
                        val where = listOfNotNull(hit.channel.number?.toString(), hit.channel.name).joinToString(" ")
                        val text = when {
                            hit.programme == null -> hit.channel.group
                            hit.airingNow -> stringResource(R.string.live_search_now, where)
                            else -> stringResource(R.string.live_search_at, formatClock(hit.programme.startMs), where)
                        }
                        Text(text, style = MaterialTheme.typography.bodySmall)
                    }
                )
            }
        }
    }
}

/** Big channel number shown while you type digits on the remote during live playback. */
@Composable
fun LiveNumberEntryOverlay() {
    val typed by LiveZapSession.typedNumber.collectAsStateWithLifecycle()
    val number = typed ?: return
    Box(modifier = Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.TopEnd) {
        Text(
            text = number,
            color = Color.White,
            fontSize = 48.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier
                .background(Color(0xCC0A0F1C), RoundedCornerShape(12.dp))
                .padding(horizontal = 24.dp, vertical = 8.dp)
        )
    }
}

private fun formatClock(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

/** Logs a failed Live TV / ticker background task instead of crashing the app (ViewModel scopes are supervisor scopes). */
internal val liveErrors = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
    android.util.Log.w("NuvioLive", "Background task failed", e)
}
