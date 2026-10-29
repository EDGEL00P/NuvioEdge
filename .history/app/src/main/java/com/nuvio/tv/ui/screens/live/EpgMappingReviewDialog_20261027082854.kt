@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionButton
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionRow
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun EpgMappingReviewDialog(
    reports: List<EpgSourceReport>,
    viewModel: LiveTvViewModel,
    onDismiss: () -> Unit
) {
    val mappingState by viewModel.epgMappingState.collectAsStateWithLifecycle()
    val reviews = reports.flatMap(EpgSourceReport::mappingReviews)
    var selectedReview by remember { mutableStateOf<EpgMappingReview?>(null) }
    var query by remember(selectedReview?.channelId) { mutableStateOf("") }
    val selectedSource = reports.firstOrNull { it.sourceId == selectedReview?.sourceId }
    val allCandidates = selectedSource?.guideCandidates.orEmpty()
    val suggestedIds = selectedReview?.candidates.orEmpty().mapTo(HashSet()) { it.guideId }
    val searchResults by produceState(
        initialValue = emptyList<EpgGuideCandidate>(),
        allCandidates,
        suggestedIds,
        query
    ) {
        delay(120)
        val search = query.trim()
        value = withContext(Dispatchers.Default) {
            allCandidates.asSequence()
                .filter { candidate ->
                    search.isEmpty() || candidate.guideId.contains(search, ignoreCase = true) ||
                        candidate.displayNames.any { it.contains(search, ignoreCase = true) }
                }
                .sortedBy { it.guideId !in suggestedIds }
                .toList()
        }
    }

    LaunchedEffect(Unit) { viewModel.resetEpgMappingState() }

    NuvioDialog(
        onDismiss = onDismiss,
        title = if (selectedReview == null) {
            stringResource(R.string.live_epg_review_title)
        } else {
            stringResource(R.string.live_epg_review_channel_title, selectedReview!!.channelName)
        },
        subtitle = if (selectedReview == null) {
            stringResource(R.string.live_epg_review_subtitle)
        } else {
            stringResource(R.string.live_epg_review_search_subtitle, selectedSource?.sourceName.orEmpty())
        },
        width = 900.dp
    ) {
        mappingState.error?.let { error ->
            Text(text = error, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.Error)
        }
        if (selectedReview == null) {
            if (reports.any(EpgSourceReport::mappingReviewsTruncated)) {
                Text(
                    text = stringResource(R.string.live_epg_review_truncated),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.Warning
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(reviews, key = { "${it.sourceId}:${it.channelId}" }) { review ->
                    val sourceName = reports.firstOrNull { it.sourceId == review.sourceId }?.sourceName.orEmpty()
                    ListItem(
                        selected = false,
                        onClick = { selectedReview = review },
                        colors = reviewRowColors(),
                        headlineContent = { Text(review.channelName, maxLines = 1) },
                        supportingContent = {
                            Text(
                                text = stringResource(
                                    R.string.live_epg_review_channel_summary,
                                    sourceName,
                                    review.currentGuideId ?: stringResource(R.string.live_epg_auto_match)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        }
                    )
                }
            }
        } else {
            Column {
                InputField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.live_epg_review_search_hint),
                    modifier = Modifier.fillMaxWidth()
                )
                selectedSource?.takeIf { it.guideCatalogTruncated }?.let {
                    Text(
                        text = stringResource(R.string.live_epg_catalog_truncated),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.Warning,
                        modifier = Modifier.padding(vertical = NuvioTheme.spacing.xs)
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(searchResults, key = EpgGuideCandidate::guideId) { candidate ->
                        EpgCandidateRow(
                            review = selectedReview!!,
                            candidate = candidate,
                            suggested = candidate.guideId in suggestedIds,
                            saving = mappingState.saving,
                            onClick = {
                                viewModel.setManualEpgMapping(
                                    selectedReview!!.sourceId,
                                    selectedReview!!.channelId,
                                    candidate.guideId,
                                    onDismiss
                                )
                            }
                        )
                    }
                }
                if (searchResults.isEmpty() && query.isNotBlank()) {
                    Spacer(Modifier.height(NuvioTheme.spacing.sm))
                    Text(stringResource(R.string.live_epg_review_no_results), color = NuvioTheme.colors.TextSecondary)
                }
            }
        }
        SettingsDialogActionRow {
            if (selectedReview != null) {
                SettingsDialogActionButton(
                    text = stringResource(R.string.live_epg_review_back),
                    onClick = { selectedReview = null; query = "" }
                )
                if (selectedReview!!.currentGuideId != null) {
                    SettingsDialogActionButton(
                        text = stringResource(R.string.live_epg_reset_mapping),
                        onClick = {
                            viewModel.setManualEpgMapping(
                                selectedReview!!.sourceId,
                                selectedReview!!.channelId,
                                null,
                                onDismiss
                            )
                        },
                        enabled = !mappingState.saving
                    )
                }
            }
            SettingsDialogActionButton(
                text = stringResource(R.string.live_close),
                onClick = onDismiss,
                primary = true
            )
        }
    }
}

@Composable
private fun reviewRowColors() = ListItemDefaults.colors(
    containerColor = NuvioTheme.colors.BackgroundCard,
    contentColor = NuvioTheme.colors.TextPrimary,
    focusedContainerColor = NuvioTheme.colors.FocusBackground,
    focusedContentColor = NuvioTheme.colors.FocusContent
)

@Composable
private fun EpgCandidateRow(
    review: EpgMappingReview,
    candidate: EpgGuideCandidate,
    suggested: Boolean,
    saving: Boolean,
    onClick: () -> Unit
) {
    val displayName = candidate.displayNames.firstOrNull()?.takeIf { it.isNotBlank() }
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
        headlineContent = { Text(displayName ?: candidate.guideId, maxLines = 1) },
        supportingContent = {
            Text(
                text = stringResource(
                    if (suggested) R.string.live_epg_candidate_suggested else R.string.live_epg_candidate,
                    if (displayName == null) candidate.guideId else "${candidate.guideId} · ${review.channelName}"
                ),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
        }
    )
}
