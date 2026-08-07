package dev.wuxie233.codecarry.ui.screens.diagnostics

import dev.wuxie233.codecarry.data.diagnostics.DiagnosticsLogItem
import dev.wuxie233.codecarry.data.diagnostics.DiagnosticsLogType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class DiagnosticsLogRowState(
    val id: String,
    val typeLabel: String,
    val displayName: String,
    val timestampLabel: String,
    val sizeLabel: String,
    val isSelected: Boolean,
)

data class DiagnosticsActionState(
    val selectedCount: Int,
    val hasLogs: Boolean,
    val hasSelection: Boolean,
    val canSelectAll: Boolean,
    val canClearSelection: Boolean,
    val canDeleteSelected: Boolean,
    val canUpload: Boolean,
)

fun DiagnosticsUiState.toDiagnosticsActionState(): DiagnosticsActionState {
    val hasLogs = logs.isNotEmpty()
    val hasSelection = selectedLogIds.isNotEmpty()
    return DiagnosticsActionState(
        selectedCount = selectedLogIds.size,
        hasLogs = hasLogs,
        hasSelection = hasSelection,
        canSelectAll = hasLogs && selectedLogIds.size < logs.size,
        canClearSelection = hasSelection,
        canDeleteSelected = hasSelection && !isUploading,
        canUpload = canUpload,
    )
}

fun DiagnosticsUiState.toDiagnosticsLogRows(
    locale: Locale = Locale.getDefault(),
    timeZone: TimeZone = TimeZone.getDefault(),
): List<DiagnosticsLogRowState> {
    return logs.map { item ->
        item.toDiagnosticsLogRowState(
            isSelected = item.id in selectedLogIds,
            locale = locale,
            timeZone = timeZone,
        )
    }
}

fun DiagnosticsLogItem.toDiagnosticsLogRowState(
    isSelected: Boolean,
    locale: Locale = Locale.getDefault(),
    timeZone: TimeZone = TimeZone.getDefault(),
): DiagnosticsLogRowState {
    return DiagnosticsLogRowState(
        id = id,
        typeLabel = diagnosticsLogTypeLabel(type),
        displayName = displayName.ifBlank { diagnosticsLogTypeLabel(type) },
        timestampLabel = formatDiagnosticsTimestamp(createdAtMillis, locale, timeZone),
        sizeLabel = formatDiagnosticsSize(sizeBytes),
        isSelected = isSelected,
    )
}

fun diagnosticsLogTypeLabel(type: DiagnosticsLogType): String {
    return when (type) {
        DiagnosticsLogType.APP_EVENT -> "App"
        DiagnosticsLogType.SESSION_DIAGNOSTIC -> "Session"
        DiagnosticsLogType.NETWORK_DIAGNOSTIC -> "Network"
    }
}

fun formatDiagnosticsTimestamp(
    timestampMillis: Long,
    locale: Locale = Locale.getDefault(),
    timeZone: TimeZone = TimeZone.getDefault(),
): String {
    val formatter = SimpleDateFormat("MMM d, HH:mm", locale)
    formatter.timeZone = timeZone
    return formatter.format(Date(timestampMillis))
}

fun formatDiagnosticsSize(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0L)
    val value = safeBytes.toDouble()
    return when {
        value >= 1024.0 * 1024.0 -> String.format(Locale.US, "%.2f MB", value / (1024.0 * 1024.0))
        value >= 1024.0 -> String.format(Locale.US, "%.1f KB", value / 1024.0)
        else -> "$safeBytes B"
    }
}
