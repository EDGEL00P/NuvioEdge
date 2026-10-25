@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.live

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.iptv.IptvSource
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
    var confirmRemove by remember { mutableStateOf<IptvSource?>(null) }

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
private fun SourceRow(source: IptvSource, onToggle: () -> Unit, onRemove: () -> Unit) {
    val type = stringResource(if (source.type == IptvSourceType.XTREAM) R.string.live_type_xtream else R.string.live_type_m3u)
    val status = stringResource(if (source.enabled) R.string.live_enabled else R.string.live_disabled)
    val security = stringResource(if (source.isUnencrypted) R.string.live_not_encrypted else R.string.live_encrypted)
    ListItem(
        selected = false,
        onClick = onToggle,
        onLongClick = onRemove,
        colors = ListItemDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.FocusContent
        ),
        headlineContent = { Text(source.name) },
        supportingContent = {
            Text(
                text = "$type · $status · $security\n${stringResource(R.string.live_source_hint)}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    )
}

@Composable
private fun AddSourceDialog(type: IptvSourceType, viewModel: LiveTvViewModel, onDone: () -> Unit) {
    val addState by viewModel.addState.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var epgUrl by remember { mutableStateOf("") }
    val isXtream = type == IptvSourceType.XTREAM
    val canSave = !addState.saving && url.isNotBlank() && (!isXtream || (username.isNotBlank() && password.isNotBlank()))
    val save = {
        if (canSave) {
            viewModel.addSource(name, type, url, username, password, epgUrl) {
                password = ""
                onDone()
            }
        }
    }

    LaunchedEffect(Unit) { viewModel.resetAddState() }

    NuvioDialog(
        onDismiss = { viewModel.cancelAdd(); onDone() },
        title = stringResource(if (isXtream) R.string.live_add_xtream else R.string.live_add_m3u),
        subtitle = stringResource(R.string.live_add_subtitle),
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
                text = stringResource(if (addState.saving) R.string.live_checking else R.string.live_save),
                onClick = save,
                primary = true,
                enabled = canSave
            )
        }
    }
}

@Composable
private fun Field(label: String, field: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary)
        field()
    }
}
