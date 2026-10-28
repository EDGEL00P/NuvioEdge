@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.live

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.iptv.IptvSource
import com.nuvio.tv.data.iptv.IptvSourceHealth
import com.nuvio.tv.data.iptv.IptvSourceHealthStatus
import com.nuvio.tv.data.iptv.EpgSourceReport
import com.nuvio.tv.data.iptv.EpgSourceStatus
import com.nuvio.tv.data.iptv.IptvSourceType
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionButton
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionRow
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun LiveSourcesDialog(viewModel: LiveTvViewModel, onDismiss: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf<IptvSourceType?>(null) }
    var editing by remember { mutableStateOf<IptvSource?>(null) }
    var confirmRemove by remember { mutableStateOf<IptvSource?>(null) }

    editing?.let { source ->
        AddSourceDialog(type = source.type, source = source, viewModel = viewModel, onDone = { editing = null })
        return
    }
    adding?.let { type ->
        AddSourceDialog(type = type, viewModel = viewModel, onDone = { adding = null })
        return
    }
    confirmRemove?.let { source ->
        NuvioDialog(
            onDismiss = { confirmRemove = null },
            title = stringResource(R.string.live_remove_title),
            subtitle = stringResource(R.string.live_remove_message, source.name)
        ) {
            SettingsDialogActionRow {
                SettingsDialogActionButton(stringResource(R.string.action_cancel), onClick = { confirmRemove = null })
                SettingsDialogActionButton(
                    text = stringResource(R.string.live_remove),
                    primary = true,
                    onClick = {
                        viewModel.removeSource(source.id)
                        confirmRemove = null
                    }
                )
            }
        }
        return
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.live_sources),
        subtitle = stringResource(R.string.live_sources_subtitle),
        width = 640.dp
    ) {
        if (state.sources.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(state.sources, key = { it.id }) { source ->
                    SourceRow(
                        source = source,
                        health = state.sourceHealth.firstOrNull { it.sourceId == source.id },
                        guideReport = state.guideReports.firstOrNull { it.sourceId == source.id },
                        sourceChannelCount = state.allChannels.count { it.sourceId == source.id },
                        refreshing = state.loading,
                        refreshingGuide = state.guideLoading,
                        onEdit = { editing = source },
                        onRetry = { viewModel.retrySource(source.id) },
                        onRetryGuide = { viewModel.retryGuide(source.id) },
                        onToggle = { viewModel.setSourceEnabled(source.id, !source.enabled) },
                        onRemove = { confirmRemove = source }
                    )
                }
            }
        }
        SettingsDialogActionRow {
            SettingsDialogActionButton(stringResource(R.string.live_add_m3u), onClick = { adding = IptvSourceType.M3U })
            SettingsDialogActionButton(stringResource(R.string.live_add_xtream), onClick = { adding = IptvSourceType.XTREAM })
            SettingsDialogActionButton(stringResource(R.string.live_close), onClick = onDismiss, primary = true)
        }
    }
}

@Composable
private fun SourceRow(
    source: IptvSource,
    health: IptvSourceHealth?,
    guideReport: EpgSourceReport?,
    sourceChannelCount: Int,
    refreshing: Boolean,
    refreshingGuide: Boolean,
    onEdit: () -> Unit,
    onRetry: () -> Unit,
    onRetryGuide: () -> Unit,
    onToggle: () -> Unit,
    onRemove: () -> Unit
) {
    val type = stringResource(if (source.type == IptvSourceType.XTREAM) R.string.live_type_xtream else R.string.live_type_m3u)
    val status = stringResource(if (source.enabled) R.string.live_enabled else R.string.live_disabled)
    val security = stringResource(if (source.isUnencrypted) R.string.live_not_encrypted else R.string.live_encrypted)
    val healthLabel = stringResource(
        when (health?.status ?: IptvSourceHealthStatus.NOT_CHECKED) {
            IptvSourceHealthStatus.READY -> R.string.live_source_health_ready
            IptvSourceHealthStatus.LIMITED -> R.string.live_source_health_limited
            IptvSourceHealthStatus.STALE -> R.string.live_source_health_stale
            IptvSourceHealthStatus.ERROR -> R.string.live_source_health_error
            IptvSourceHealthStatus.DISABLED -> R.string.live_source_health_disabled
            IptvSourceHealthStatus.NOT_CHECKED -> R.string.live_source_health_not_checked
        }
    )
    val healthDetail = when {
        health?.detail != null -> health.detail
        health != null && (health.status == IptvSourceHealthStatus.READY || health.status == IptvSourceHealthStatus.LIMITED) ->
            stringResource(R.string.live_source_health_channels, health.channelCount)
        else -> null
    }
    val sourceHint = stringResource(R.string.live_source_hint)
    val guideStatus = when {
        guideReport == null -> null
        guideReport.stale -> stringResource(
            R.string.live_source_guide_stale_data,
            guideReport.matched,
            guideReport.channelCount
        )
        guideReport.status == EpgSourceStatus.NOT_CONFIGURED -> stringResource(R.string.live_source_guide_not_configured)
        guideReport.status == EpgSourceStatus.LOAD_ERROR -> stringResource(R.string.live_source_guide_unavailable)
        guideReport.channelCount == 0 -> stringResource(R.string.live_source_guide_empty)
        else -> stringResource(
            R.string.live_source_guide_matches,
            guideReport.matched * 100 / guideReport.channelCount,
            guideReport.matched,
            guideReport.channelCount
        )
    }
    val staleMappings = guideReport?.staleManual ?: 0
    ListItem(
        selected = false,
        onClick = onEdit,
        colors = ListItemDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.FocusContent
        ),
        headlineContent = { Text(source.name) },
        supportingContent = {
            Column {
                Text("$type · $status · $security", style = MaterialTheme.typography.bodySmall)
                Text(
                    text = buildString {
                        append(healthLabel)
                        healthDetail?.let { append(" · ").append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (health?.status) {
                        IptvSourceHealthStatus.ERROR -> NuvioTheme.colors.Error
                        IptvSourceHealthStatus.STALE -> NuvioTheme.colors.Warning
                        else -> NuvioTheme.colors.TextSecondary
                    }
                )
                if (source.enabled && (health?.status == IptvSourceHealthStatus.ERROR || health == null)) {
                    Button(
                        onClick = onRetry,
                        enabled = !refreshing,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundElevated,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground,
                            focusedContentColor = NuvioTheme.colors.FocusContent
                        )
                    ) {
                        Text(stringResource(if (health == null) R.string.live_source_check else R.string.live_source_retry))
                    }
                }
                guideStatus?.let { detail ->
                    Text(
                        text = if (staleMappings > 0) {
                            stringResource(R.string.live_source_guide_stale, detail, staleMappings)
                        } else detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (
                            staleMappings > 0 ||
                            guideReport?.stale == true ||
                            (guideReport?.ambiguous ?: 0) > 0 ||
                            (guideReport?.unmatched ?: 0) > 0
                        ) {
                            NuvioTheme.colors.Warning
                        } else NuvioTheme.colors.TextSecondary
                    )
                }
                if (
                    source.enabled &&
                    (guideReport?.status == EpgSourceStatus.LOAD_ERROR ||
                        (guideReport?.stale == true && health?.status != IptvSourceHealthStatus.STALE)) &&
                    sourceChannelCount > 0
                ) {
                    Button(
                        onClick = onRetryGuide,
                        enabled = !refreshing && !refreshingGuide,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundElevated,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground,
                            focusedContentColor = NuvioTheme.colors.FocusContent
                        )
                    ) {
                        Text(stringResource(R.string.live_source_retry_guide))
                    }
                }
                Text(sourceHint, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextTertiary)
            }
        },
        trailingContent = {
            Row {
                Button(
                    onClick = onToggle,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundElevated,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground,
                        focusedContentColor = NuvioTheme.colors.FocusContent
                    )
                ) {
                    Text(stringResource(if (source.enabled) R.string.live_disable else R.string.live_enable))
                }
                Spacer(Modifier.width(NuvioTheme.spacing.xs))
                Button(
                    onClick = onRemove,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundElevated,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground,
                        focusedContentColor = NuvioTheme.colors.FocusContent
                    )
                ) { Text(stringResource(R.string.live_remove)) }
            }
        }
    )
}

@Composable
private fun AddSourceDialog(
    type: IptvSourceType,
    viewModel: LiveTvViewModel,
    onDone: () -> Unit,
    source: IptvSource? = null
) {
    val addState by viewModel.addState.collectAsStateWithLifecycle()
    var name by remember(source?.id, type) { mutableStateOf(source?.name.orEmpty()) }
    var url by remember(source?.id, type) { mutableStateOf(source?.url.orEmpty()) }
    var username by remember(source?.id, type) { mutableStateOf(source?.username.orEmpty()) }
    var password by remember(source?.id, type) { mutableStateOf(source?.password.orEmpty()) }
    var epgUrl by remember(source?.id, type) { mutableStateOf(source?.epgUrl.orEmpty()) }
    var guideShift by remember(source?.id, type) { mutableStateOf(source?.guideShiftMinutes ?: 0) }
    var catchupShift by remember(source?.id, type) { mutableStateOf(source?.catchupShiftMinutes ?: 0) }
    val isXtream = type == IptvSourceType.XTREAM
    val canSave = !addState.saving && url.isNotBlank() && (!isXtream || (username.isNotBlank() && password.isNotBlank()))
    val save = {
        if (canSave) {
            val onSaved = {
                password = ""
                onDone()
            }
            if (source == null) {
                viewModel.addSource(name, type, url, username, password, epgUrl, guideShift, catchupShift, onSaved)
            } else {
                viewModel.updateSource(source.id, name, type, url, username, password, epgUrl, guideShift, catchupShift, onSaved)
            }
        }
    }

    LaunchedEffect(source?.id, type) { viewModel.resetAddState() }

    NuvioDialog(
        onDismiss = { viewModel.cancelAdd(); onDone() },
        title = stringResource(
            when {
                source != null -> R.string.live_edit_source
                isXtream -> R.string.live_add_xtream
                else -> R.string.live_add_m3u
            }
        ),
        subtitle = stringResource(if (source == null) R.string.live_add_subtitle else R.string.live_edit_source_subtitle),
        width = 640.dp
    ) {
        // Fields scroll; the buttons stay pinned. On 540dp-tall TV screens (Chromecast, Fire TV)
        // the Xtream form is taller than the dialog, and Save/Cancel would otherwise be cut off.
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
        ) {
        Field(stringResource(R.string.live_field_name)) {
            InputField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.live_field_name_hint),
                imeAction = ImeAction.Next,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Field(stringResource(if (isXtream) R.string.live_field_server else R.string.live_field_playlist)) {
            InputField(
                value = url,
                onValueChange = { url = it },
                placeholder = stringResource(if (isXtream) R.string.live_field_server_hint else R.string.live_field_playlist_hint),
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (isXtream) {
            Field(stringResource(R.string.servers_username)) {
                InputField(
                    value = username,
                    onValueChange = { username = it },
                    placeholder = stringResource(R.string.servers_username),
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Field(stringResource(R.string.servers_password)) {
                InputField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = stringResource(R.string.servers_password),
                    keyboardType = KeyboardType.Password,
                    isPassword = true,
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Field(stringResource(R.string.live_field_epg)) {
            InputField(
                value = epgUrl,
                onValueChange = { epgUrl = it },
                placeholder = stringResource(R.string.live_field_epg_hint),
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
                onImeAction = save,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Field(stringResource(R.string.live_field_guide_shift)) {
            SettingsDialogActionRow(horizontalAlignment = androidx.compose.ui.Alignment.Start) {
                SettingsDialogActionButton(
                    text = "−",
                    onClick = { guideShift = IptvSource.clampGuideShift(guideShift - IptvSource.GUIDE_SHIFT_STEP_MINUTES) },
                    enabled = guideShift > -IptvSource.MAX_GUIDE_SHIFT_MINUTES
                )
                Text(
                    text = formatGuideShift(guideShift),
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioTheme.colors.TextPrimary,
                    modifier = Modifier.width(140.dp)
                )
                SettingsDialogActionButton(
                    text = "+",
                    onClick = { guideShift = IptvSource.clampGuideShift(guideShift + IptvSource.GUIDE_SHIFT_STEP_MINUTES) },
                    enabled = guideShift < IptvSource.MAX_GUIDE_SHIFT_MINUTES
                )
            }
            Text(
                text = stringResource(R.string.live_field_guide_shift_hint),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary
            )
        }
        Field(stringResource(R.string.live_field_catchup_shift)) {
            SettingsDialogActionRow(horizontalAlignment = androidx.compose.ui.Alignment.Start) {
                SettingsDialogActionButton(
                    text = "−",
                    onClick = { catchupShift = IptvSource.clampCatchupShift(catchupShift - IptvSource.CATCHUP_SHIFT_STEP_MINUTES) },
                    enabled = catchupShift > -IptvSource.MAX_CATCHUP_SHIFT_MINUTES
                )
                Text(
                    text = formatGuideShift(catchupShift),
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioTheme.colors.TextPrimary,
                    modifier = Modifier.width(140.dp)
                )
                SettingsDialogActionButton(
                    text = "+",
                    onClick = { catchupShift = IptvSource.clampCatchupShift(catchupShift + IptvSource.CATCHUP_SHIFT_STEP_MINUTES) },
                    enabled = catchupShift < IptvSource.MAX_CATCHUP_SHIFT_MINUTES
                )
            }
            Text(
                text = stringResource(R.string.live_field_catchup_shift_hint),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary
            )
        }
        // Xtream addresses typed without a scheme become http://, so warn for those too.
        val effectiveUrl = if (isXtream && url.isNotBlank()) com.nuvio.tv.data.iptv.XtreamClient.normalizeServer(url) else url
        if (IptvSource.isCleartext(effectiveUrl) || IptvSource.isCleartext(epgUrl)) {
            Text(
                text = stringResource(R.string.live_http_warning),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.Warning
            )
        }
        addState.error?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.Error)
        }
        }
        SettingsDialogActionRow {
            SettingsDialogActionButton(stringResource(R.string.action_cancel), onClick = { viewModel.cancelAdd(); onDone() })
            SettingsDialogActionButton(
                text = stringResource(
                    when {
                        addState.saving -> R.string.live_checking
                        source != null -> R.string.live_save_changes
                        else -> R.string.live_save
                    }
                ),
                onClick = save,
                primary = true,
                enabled = canSave
            )
        }
    }
}

@Composable
private fun formatGuideShift(minutes: Int): String {
    if (minutes == 0) return stringResource(R.string.live_guide_shift_none)
    val sign = if (minutes > 0) "+" else "−"
    val abs = kotlin.math.abs(minutes)
    return "%s%d:%02d".format(sign, abs / 60, abs % 60)
}

@Composable
private fun Field(label: String, field: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary)
        field()
    }
}
