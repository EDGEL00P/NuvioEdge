@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.iptv.EpgGuideCandidate
import com.nuvio.tv.data.iptv.EpgMappingReview
import com.nuvio.tv.data.iptv.EpgSourceReport
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionButton
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionRow
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun EpgMappingReviewDialog(
    reports: List<EpgSourceReport>,
    viewModel: LiveTvViewModel,
    onDismiss: () -> Unit
) {
    val mappingState by viewModel.epgMappingState.collectAsStateWithLifecycle()
    val reviews = reports.flatMap(EpgSourceReport::mappingReviews)

    LaunchedEffect(Unit) { viewModel.resetEpgMappingState() }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.live_epg_review_title),
        subtitle = stringResource(R.string.live_epg_review_subtitle),
        width = 900.dp
    ) {
        if (reports.any(EpgSourceReport::mappingReviewsTruncated)) {
            Text(
                text = stringResource(R.string.live_epg_review_truncated),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.Warning
            )
        }
        mappingState.error?.let { error ->
            Text(text = error, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.Error)
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(
                items = reviews,
                key = { "${it.sourceId}:${it.channelId}" }
            ) { review ->
                EpgReviewChannel(
                    review = review,
                    saving = mappingState.saving,
                    onChoose = { guideId ->
                        viewModel.setManualEpgMapping(review.sourceId, review.channelId, guideId, onDismiss)
                    }
                )
            }
        }
        SettingsDialogActionRow {
            SettingsDialogActionButton(
                text = stringResource(R.string.live_close),
                onClick = onDismiss,
                primary = true
            )
        }
    }
}

@Composable
private fun EpgReviewChannel(
    review: EpgMappingReview,
    saving: Boolean,
    onChoose: (String?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
        review.candidates.forEach { candidate ->
            EpgCandidateRow(
                review = review,
                candidate = candidate,
                saving = saving,
                onClick = { onChoose(candidate.guideId) }
            )
        }
        if (review.currentGuideId != null) {
            ListItem(
                selected = false,
                enabled = !saving,
                onClick = { onChoose(null) },
                colors = ListItemDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    focusedContentColor = NuvioTheme.colors.FocusContent
                ),
                headlineContent = { Text(review.channelName) },
                supportingContent = { Text(stringResource(R.string.live_epg_reset_mapping)) }
            )
        }
    }
}

@Composable
private fun EpgCandidateRow(
    review: EpgMappingReview,
    candidate: EpgGuideCandidate,
    saving: Boolean,
    onClick: () -> Unit
) {
    val displayName = candidate.displayNames.firstOrNull()?.takeIf { it.isNotBlank() }
    val candidateLabel = displayName?.let { "$it · ${candidate.guideId}" } ?: candidate.guideId
    ListItem(
        selected = candidate.guideId == review.currentGuideId,
        enabled = !saving,
        onClick = onClick,
        colors = ListItemDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.FocusContent,
            selectedContainerColor = NuvioTheme.colors.BackgroundElevated
        ),
        headlineContent = { Text(review.channelName, maxLines = 1) },
        supportingContent = {
            Text(
                text = stringResource(R.string.live_epg_candidate, candidateLabel),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
        }
    )
}
