@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import android.graphics.Bitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/**
 * The file's chapters, from the right edge like the episodes panel. Focus opens on the chapter
 * playing, so the next and previous ones are one key away; selecting one seeks to its start.
 */
@Composable
internal fun ChaptersSidePanel(
    chapters: List<PlayerChapter>,
    positionMs: Long,
    durationMs: Long,
    artworkUrl: String?,
    loadFrameAt: suspend (Long) -> Bitmap?,
    focusRequester: FocusRequester,
    onChapterSelected: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    // Fixed while the panel is open: it marks where focus starts, not a live highlight.
    val openedAtIndex = remember(chapters) {
        PlayerChapters.indexAt(chapters, positionMs).coerceAtLeast(0)
    }
    val currentIndex = PlayerChapters.indexAt(chapters, positionMs)

    LaunchedEffect(chapters) {
        runCatching {
            listState.scrollToItem((openedAtIndex - 2).coerceAtLeast(0))
            delay(32)
            focusRequester.requestFocus()
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(520.dp)
            .clip(RoundedCornerShape(topStart = NuvioTheme.spacing.lg, bottomStart = NuvioTheme.spacing.lg))
            .background(NuvioTheme.colors.BackgroundElevated)
    ) {
        Column(modifier = Modifier.padding(NuvioTheme.spacing.xl)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.player_chapters_title, chapters.size),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioTheme.colors.TextPrimary
                )
                DialogButton(
                    text = stringResource(R.string.episodes_panel_close),
                    onClick = onClose,
                    isPrimary = false
                )
            }

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                contentPadding = PaddingValues(vertical = NuvioTheme.spacing.xs),
                modifier = Modifier.fillMaxHeight()
            ) {
                itemsIndexed(chapters, key = { _, chapter -> chapter.startMs }) { index, chapter ->
                    val endMs = chapters.getOrNull(index + 1)?.startMs ?: durationMs
                    ChapterItem(
                        number = index + 1,
                        chapter = chapter,
                        artworkUrl = PlayerChapters.artworkFor(chapter, artworkUrl),
                        loadFrameAt = loadFrameAt,
                        isCurrent = index == currentIndex,
                        progress = if (index == currentIndex && endMs > chapter.startMs) {
                            ((positionMs - chapter.startMs).toFloat() / (endMs - chapter.startMs)).coerceIn(0f, 1f)
                        } else {
                            null
                        },
                        remainingMs = if (index == currentIndex) (endMs - positionMs).coerceAtLeast(0L) else null,
                        modifier = if (index == openedAtIndex) Modifier.focusRequester(focusRequester) else Modifier,
                        onClick = { onChapterSelected(index) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterItem(
    number: Int,
    chapter: PlayerChapter,
    artworkUrl: String?,
    loadFrameAt: suspend (Long) -> Bitmap?,
    isCurrent: Boolean,
    progress: Float?,
    remainingMs: Long?,
    modifier: Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember(chapter.startMs) { mutableStateOf(false) }
    var frameBitmap by remember(chapter.startMs, artworkUrl) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(isFocused, chapter.startMs, chapter.artworkUrl) {
        if (!isFocused || !chapter.artworkUrl.isNullOrBlank()) return@LaunchedEffect
        delay(300L)
        frameBitmap = runCatching { loadFrameAt(chapter.startMs) }.getOrNull()
    }

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.xl)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1.01f),
        shape = CardDefaults.shape(shape = RoundedCornerShape(NuvioTheme.radii.xl))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            Box(
                modifier = Modifier
                    .width(144.dp)
                    .height(82.dp)
                    .clip(RoundedCornerShape(NuvioTheme.radii.md))
                    .background(NuvioTheme.colors.SurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (frameBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = frameBitmap!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                        contentScale = ContentScale.Crop
                    )
                } else if (!artworkUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = artworkUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        NuvioTheme.colors.Primary.copy(alpha = 0.48f),
                                        NuvioTheme.colors.BackgroundElevated,
                                        NuvioTheme.colors.Secondary.copy(alpha = 0.3f)
                                    )
                                )
                            )
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.68f))
                            )
                        )
                )
                if (isCurrent) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(NuvioTheme.colors.Primary.copy(alpha = 0.92f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.cd_current),
                            tint = NuvioTheme.colors.OnPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.player_chapter_number, number),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(NuvioTheme.spacing.xs)
                            .clip(RoundedCornerShape(NuvioTheme.radii.xs))
                            .background(Color.Black.copy(alpha = 0.68f))
                            .padding(horizontal = NuvioTheme.spacing.xs, vertical = 2.dp)
                    )
                }
                Text(
                    text = formatTime(chapter.startMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(NuvioTheme.spacing.xs)
                        .clip(RoundedCornerShape(NuvioTheme.radii.xs))
                        .background(Color.Black.copy(alpha = 0.68f))
                        .padding(horizontal = NuvioTheme.spacing.xs, vertical = 2.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chapter.title ?: stringResource(R.string.player_chapter_number, number),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isCurrent) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (progress != null && remainingMs != null) {
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = 0.2f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(progress)
                                    .background(NuvioTheme.colors.Primary)
                            )
                        }
                        Text(
                            text = stringResource(R.string.player_chapter_remaining, formatTime(remainingMs)),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.extendedColors.textTertiary
                        )
                    }
                }
            }
        }
    }
}
