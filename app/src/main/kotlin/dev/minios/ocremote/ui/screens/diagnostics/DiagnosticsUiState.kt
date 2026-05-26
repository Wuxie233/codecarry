package dev.minios.ocremote.ui.screens.diagnostics

import dev.minios.ocremote.data.diagnostics.DiagnosticsLogItem

data class DiagnosticsUiState(
    val logs: List<DiagnosticsLogItem> = emptyList(),
    val selectedLogIds: Set<String> = emptySet(),
    val uploadUrlConfigured: Boolean = false,
    val uploadTokenConfigured: Boolean = false,
    val isRefreshing: Boolean = false,
    val isGenerating: Boolean = false,
    val isUploading: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
) {
    val selectedCount: Int get() = selectedLogIds.size
    val isEmpty: Boolean get() = logs.isEmpty()
    val canUpload: Boolean
        get() = selectedLogIds.isNotEmpty() && uploadUrlConfigured && uploadTokenConfigured && !isUploading
}
