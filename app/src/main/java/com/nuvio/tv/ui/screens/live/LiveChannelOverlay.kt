@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.data.iptv.EpgProgramme
import com.nuvio.tv.data.iptv.IptvChannel
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

private const val LIVE_OSD_CLOCK_TICK_MS = 15_000L

@Composable
internal fun LiveChannelOsd(snapshot: LiveZapSnapshot, visible: Boolean) {
    val channel = snapshot.current ?: return
    if (!visible) return
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(snapshot.revision) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(LIVE_OSD_CLOCK_TICK_MS)
        }
    }
    val (now, next) = LiveZapSession.programmesFor(channel.id, nowMs)
    val timeFormat = rememberTimeFormatter()
    val progress = now?.takeIf { it.endMs > it.startMs }?.let {
        ((nowMs - it.startMs).toFloat() / (it.endMs - it.startMs)).coerceIn(0f, 1f)
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 48.dp, top = 42.dp)
                .width(620.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.98f),
                            NuvioTheme.colors.BackgroundCard.copy(alpha = 0.96f)
                        )
                    )
                )
                .border(1.dp, NuvioTheme.colors.Primary.copy(alpha = 0.2f), RoundedCornerShape(18.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            channel.logoUrl?.let { logo ->
                AsyncImage(
                    model = logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(width = 72.dp, height = 48.dp)
                )
                Spacer(Modifier.width(14.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(NuvioTheme.colors.Primary))
                    Text(
                        text = stringResource(R.string.live_osd_live),
                        color = NuvioTheme.colors.Primary,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(start = 7.dp, end = 10.dp)
                    )
                    if (snapshot.failoverActive) {
                        Text(
                            text = stringResource(R.string.live_osd_backup),
                            color = NuvioTheme.colors.Warning,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier
                                .padding(end = 10.dp)
                                .clip(CircleShape)
                                .background(NuvioTheme.colors.Warning.copy(alpha = 0.14f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Text(
                        text = listOfNotNull(channel.number?.takeIf { snapshot.showChannelNumbers }?.toString(), channel.name)
                            .joinToString("  ·  "),
                        color = NuvioTheme.colors.TextPrimary,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = now?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.live_guide_no_info),
                    color = NuvioTheme.colors.TextPrimary,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        if (progress != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(NuvioTheme.colors.Divider)
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(NuvioTheme.colors.Primary, NuvioTheme.colors.PrimaryVariant)
                            )
                        )
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = now?.let { "${timeFormat(it.startMs)}–${timeFormat(it.endMs)}" }.orEmpty(),
                color = NuvioTheme.colors.TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            next?.let {
                Spacer(Modifier.width(18.dp))
                Text(
                    text = stringResource(R.string.live_osd_next, timeFormat(it.startMs), it.title),
                    color = NuvioTheme.colors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            text = stringResource(R.string.live_osd_guide_hint),
            color = NuvioTheme.colors.TextTertiary,
            style = MaterialTheme.typography.labelSmall
        )
        }
    }
}

@Composable
internal fun LiveMiniGuideOverlay(
    snapshot: LiveZapSnapshot,
    focusRequester: FocusRequester,
    onDismiss: () -> Unit,
    onTune: (IptvChannel) -> Unit
) {
    val listState = rememberLazyListState()
    val currentFocus = remember(snapshot.currentId) { FocusRequester() }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val timeFormat = rememberTimeFormatter()

    LaunchedEffect(Unit) {
        val index = snapshot.channels.indexOfFirst { it.id == snapshot.currentId }
        if (index >= 0) {
            listState.scrollToItem(index)
            withFrameNanos { }
            runCatching { currentFocus.requestFocus() }
        } else {
            runCatching { focusRequester.requestFocus() }
        }
    }
    LaunchedEffect(snapshot.revision) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(LIVE_OSD_CLOCK_TICK_MS)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(620.dp)
                .background(NuvioTheme.colors.Background)
                .padding(horizontal = 26.dp, vertical = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.live_mini_guide_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = NuvioTheme.colors.TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.live_mini_guide_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(focusRequester),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground,
                        focusedContentColor = NuvioTheme.colors.FocusContent
                    )
                ) { Text(stringResource(R.string.live_close)) }
            }
            Spacer(Modifier.height(16.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                items(snapshot.channels, key = { it.id }) { channel ->
                    val (now, next) = remember(channel.id, nowMs) {
                        LiveZapSession.programmesFor(channel.id, nowMs)
                    }
                    MiniGuideChannelRow(
                        channel = channel,
                        now = now,
                        next = next,
                        nowMs = nowMs,
                        timeFormat = timeFormat,
                        showNumber = snapshot.showChannelNumbers,
                        selected = channel.id == snapshot.currentId,
                        modifier = if (channel.id == snapshot.currentId) Modifier.focusRequester(currentFocus) else Modifier,
                        onClick = { onTune(channel) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniGuideChannelRow(
    channel: IptvChannel,
    now: EpgProgramme?,
    next: EpgProgramme?,
    nowMs: Long,
    timeFormat: (Long) -> String,
    showNumber: Boolean,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    ListItem(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        colors = ListItemDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.FocusContent,
            selectedContainerColor = NuvioTheme.colors.BackgroundElevated
        ),
        leadingContent = {
            Box(
                Modifier.size(width = 68.dp, height = 44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(NuvioTheme.colors.BackgroundElevated),
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
            Text(
                text = listOfNotNull(channel.number?.takeIf { showNumber }?.toString(), channel.name).joinToString("  "),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = now?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.live_guide_no_info),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (now != null && now.endMs > now.startMs) {
                    val fraction = ((nowMs - now.startMs).toFloat() / (now.endMs - now.startMs)).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(NuvioTheme.colors.Divider)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(NuvioTheme.colors.Primary))
                    }
                }
                if (next != null) {
                    Text(
                        text = stringResource(R.string.live_osd_next, timeFormat(next.startMs), next.title),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    )
}