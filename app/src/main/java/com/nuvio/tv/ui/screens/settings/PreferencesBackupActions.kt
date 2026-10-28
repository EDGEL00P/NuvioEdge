@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.local.PreferencesBackupPreview
import com.nuvio.tv.data.local.PreferencesBackupRepository
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PreferencesBackupStatus { EXPORTED, RESTORED, FAILED }
enum class PreferencesBackupFileAction { EXPORT, IMPORT }

data class PreferencesBackupUiState(
    val busy: Boolean = false,
    val previewCount: Int? = null,
    val status: PreferencesBackupStatus? = null
)

@HiltViewModel
class PreferencesBackupViewModel @Inject constructor(
    private val repository: PreferencesBackupRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val mutableState = MutableStateFlow(PreferencesBackupUiState(
        busy = savedStateHandle.get<String>("backup_file_action") != null
    ))
    val state = mutableState.asStateFlow()
    private var targetProfileId: Int?
        get() = savedStateHandle["backup_target_profile"]
        set(value) { savedStateHandle["backup_target_profile"] = value }
    private var pendingRestore: PreferencesBackupPreview? = null

    fun beginFileAction(action: PreferencesBackupFileAction): Boolean {
        if (state.value.busy || pendingRestore != null) return false
        targetProfileId = repository.currentProfileId()
        savedStateHandle["backup_file_action"] = action.name
        mutableState.value = PreferencesBackupUiState(busy = true)
        return true
    }

    private fun consumeFileAction(action: PreferencesBackupFileAction): Boolean {
        if (savedStateHandle.get<String>("backup_file_action") != action.name) return false
        savedStateHandle.remove<String>("backup_file_action")
        return true
    }

    fun pickerFailed() {
        savedStateHandle.remove<String>("backup_file_action")
        targetProfileId = null
        mutableState.value = PreferencesBackupUiState(status = PreferencesBackupStatus.FAILED)
    }

    fun export(uri: Uri?) {
        if (!consumeFileAction(PreferencesBackupFileAction.EXPORT)) return
        if (uri == null) {
            cancel()
            return
        }
        val profileId = targetProfileId ?: run { pickerFailed(); return }
        viewModelScope.launch {
            try {
                repository.exportTo(uri, profileId)
                mutableState.value = PreferencesBackupUiState(status = PreferencesBackupStatus.EXPORTED)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.value = PreferencesBackupUiState(status = PreferencesBackupStatus.FAILED)
            } finally {
                targetProfileId = null
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun readBackup(uri: Uri?) {
        if (!consumeFileAction(PreferencesBackupFileAction.IMPORT)) return
        if (uri == null) {
            cancel()
            return
        }
        if (targetProfileId == null) {
            pickerFailed()
            return
        }
        viewModelScope.launch {
            try {
                val preview = repository.preview(uri)
                pendingRestore = preview
                mutableState.value = PreferencesBackupUiState(previewCount = preview.settingCount)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                targetProfileId = null
                mutableState.value = PreferencesBackupUiState(status = PreferencesBackupStatus.FAILED)
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun restore() {
        if (state.value.busy) return
        val preview = pendingRestore ?: return
        val profileId = targetProfileId ?: return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                repository.restore(preview, profileId)
                mutableState.value = PreferencesBackupUiState(status = PreferencesBackupStatus.RESTORED)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.value = PreferencesBackupUiState(status = PreferencesBackupStatus.FAILED)
            } finally {
                pendingRestore = null
                targetProfileId = null
                mutableState.update { it.copy(busy = false, previewCount = null) }
            }
        }
    }

    fun cancel() {
        savedStateHandle.remove<String>("backup_file_action")
        pendingRestore = null
        targetProfileId = null
        mutableState.value = PreferencesBackupUiState()
    }
}

@Composable
internal fun PreferencesBackupActions(viewModel: PreferencesBackupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), viewModel::export)
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), viewModel::readBackup)

    SettingsGroupCard(modifier = Modifier.fillMaxWidth(), title = stringResource(R.string.backup_title)) {
        Text(stringResource(R.string.backup_exclusions), color = NuvioTheme.colors.TextSecondary)
        SettingsActionRow(
            title = stringResource(R.string.backup_export),
            subtitle = stringResource(if (state.busy) R.string.backup_busy else R.string.backup_export_subtitle),
            trailingIcon = Icons.Default.FileDownload,
            onClick = {
                if (viewModel.beginFileAction(PreferencesBackupFileAction.EXPORT)) {
                    try {
                        exportPicker.launch("NuvioEdge-preferences.json")
                    } catch (_: Exception) {
                        viewModel.pickerFailed()
                    }
                }
            }
        )
        SettingsActionRow(
            title = stringResource(R.string.backup_import),
            subtitle = stringResource(if (state.busy) R.string.backup_busy else R.string.backup_import_subtitle),
            trailingIcon = Icons.Default.FileUpload,
            onClick = {
                if (viewModel.beginFileAction(PreferencesBackupFileAction.IMPORT)) {
                    try {
                        importPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    } catch (_: Exception) {
                        viewModel.pickerFailed()
                    }
                }
            }
        )
        state.status?.let { status ->
            Text(
                text = stringResource(when (status) {
                    PreferencesBackupStatus.EXPORTED -> R.string.backup_exported
                    PreferencesBackupStatus.RESTORED -> R.string.backup_restored
                    PreferencesBackupStatus.FAILED -> R.string.backup_failed
                }),
                color = if (status == PreferencesBackupStatus.FAILED) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary
            )
        }
    }
    state.previewCount?.let { count ->
        NuvioDialog(
            onDismiss = { if (!state.busy) viewModel.cancel() },
            title = stringResource(R.string.backup_restore_title)
        ) {
            Text(stringResource(R.string.backup_restore_confirmation, count), color = NuvioTheme.colors.TextPrimary)
            SettingsDialogActionRow {
                SettingsDialogActionButton(stringResource(R.string.action_cancel), onClick = viewModel::cancel, enabled = !state.busy)
                SettingsDialogActionButton(stringResource(R.string.backup_restore), onClick = viewModel::restore, primary = true, enabled = !state.busy)
            }
        }
    }
}