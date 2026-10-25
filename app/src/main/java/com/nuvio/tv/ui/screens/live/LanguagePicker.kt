@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.iptv.LanguageFilter
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionButton
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionRow
import com.nuvio.tv.ui.theme.NuvioTheme

/** "All languages", or the chosen language names. */
@Composable
internal fun languagesLabel(languages: List<String>): String =
    if (languages.isEmpty()) {
        stringResource(R.string.live_languages_all)
    } else {
        LanguageFilter.LANGUAGES.filter { it.first in languages }.joinToString(", ") { it.second }
    }

/**
 * Picks the languages a source keeps. Channels tagged with another language are hidden; channels
 * with no language tag stay.
 */
@Composable
internal fun LanguagePickerDialog(
    selected: List<String>,
    onDone: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var chosen by remember { mutableStateOf(selected.toSet()) }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.live_languages_title),
        subtitle = stringResource(R.string.live_languages_subtitle),
        width = 560.dp
    ) {
        val colors = ListItemDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.FocusContent
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item(key = "all") {
                ListItem(
                    selected = false,
                    onClick = { chosen = emptySet() },
                    colors = colors,
                    headlineContent = { Text(stringResource(R.string.live_languages_all)) },
                    trailingContent = { if (chosen.isEmpty()) Icon(Icons.Default.Check, null, Modifier.size(20.dp)) }
                )
            }
            items(LanguageFilter.LANGUAGES, key = { it.first }) { (id, name) ->
                ListItem(
                    selected = false,
                    onClick = { chosen = if (id in chosen) chosen - id else chosen + id },
                    colors = colors,
                    headlineContent = { Text(name) },
                    trailingContent = { if (id in chosen) Icon(Icons.Default.Check, null, Modifier.size(20.dp)) }
                )
            }
        }
        SettingsDialogActionRow {
            SettingsDialogActionButton(stringResource(R.string.action_cancel), onClick = onDismiss)
            SettingsDialogActionButton(
                text = stringResource(R.string.live_languages_done),
                primary = true,
                onClick = { onDone(LanguageFilter.LANGUAGES.map { it.first }.filter { it in chosen }) }
            )
        }
    }
}
