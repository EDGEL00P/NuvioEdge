package com.nuvio.tv.ui.screens.player

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.accentBrush
import androidx.compose.ui.res.stringResource

@Composable
internal fun SeekPreviewCard(
    positionMs: Long,
    durationMs: Long,
    title: String,
    chapterTitle: String?,
    artworkUrl: String?,
    loadFrameAt: suspend (Long) -> Bitmap?,
    modifier: Modifier = Modifier
) {
    var frameBitmap by remember(positionMs, artworkUrl) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(positionMs, artworkUrl) {
        frameBitmap = null
        delay(350L)
        frameBitmap = runCatching { loadFrameAt(positionMs) }.getOrNull()
    }
    val shape = RoundedCornerShape(NuvioTheme.radii.lg)
    val safeDuration = durationMs.coerceAtLeast(0L)
    val progress = if (safeDuration > 0L) {
        (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val displayedTitle = chapterTitle?.takeIf { it.isNotBlank() }
        ?: title.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.player_seek_preview)

    Column(
        modifier = modifier
            .width(240.dp)
            .clip(shape)
            .background(NuvioTheme.colors.BackgroundElevated)
            .border(1.dp, NuvioTheme.colors.Primary.copy(alpha = 0.62f), shape)
            .semantics(mergeDescendants = true) {
                contentDescription = "$displayedTitle, ${formatTime(positionMs)}"
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(RoundedCornerShape(topStart = NuvioTheme.radii.lg, topEnd = NuvioTheme.radii.lg))
                .background(NuvioTheme.colors.SurfaceVariant)
        ) {
            if (frameBitmap != null) {
                Image(
                    bitmap = frameBitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } else if (!artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = artworkUrl,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    NuvioTheme.colors.Primary.copy(alpha = 0.52f),
                                    NuvioTheme.colors.BackgroundElevated,
                                    NuvioTheme.colors.Secondary.copy(alpha = 0.35f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.82f),
                        modifier = Modifier.width(38.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.16f), Color.Transparent, Color.Black.copy(alpha = 0.88f))
                        )
                    )
            )

            Text(
                text = stringResource(R.string.player_seek_preview),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(NuvioTheme.spacing.sm)
                    .clip(RoundedCornerShape(NuvioTheme.radii.sm))
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs)
            )

            Text(
                text = displayedTitle,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatTime(positionMs),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = NuvioTheme.colors.TextPrimary
            )
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Text(
                text = formatTime(safeDuration),
                style = MaterialTheme.typography.labelSmall,
                color = NuvioTheme.extendedColors.textTertiary
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = NuvioTheme.spacing.md, end = NuvioTheme.spacing.md, bottom = NuvioTheme.spacing.sm)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.18f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(3.dp)
                    .background(NuvioTheme.palette.accentBrush())
            )
        }
    }
}
