package dev.wuxie233.codecarry.ui.screens.diagnostics

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.wuxie233.codecarry.R

@Composable
fun DiagnosticsScreen(
    onNavigateBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    DiagnosticsScreenContent(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onToggleSelection = viewModel::toggleSelection,
        onSelectAll = viewModel::selectAll,
        onClearSelection = viewModel::clearSelection,
        onDeleteSelected = viewModel::deleteSelected,
        onGenerateNow = viewModel::generateNow,
        onUploadSelected = { viewModel.uploadSelected() },
        onRefresh = viewModel::refresh,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiagnosticsScreenContent(
    uiState: DiagnosticsUiState,
    onNavigateBack: () -> Unit,
    onToggleSelection: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onGenerateNow: () -> Unit,
    onUploadSelected: () -> Unit,
    onRefresh: () -> Unit,
) {
    val actionState = uiState.toDiagnosticsActionState()
    val rows = uiState.toDiagnosticsLogRows()
    val isAmoled = rememberIsDiagnosticsAmoledTheme()
    var showDeleteDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = actionState.hasSelection) {
        onClearSelection()
    }

    Scaffold(
        topBar = {
            DiagnosticsTopBar(
                actionState = actionState,
                isAmoled = isAmoled,
                onNavigateBack = onNavigateBack,
                onSelectAll = onSelectAll,
                onClearSelection = onClearSelection,
                onDeleteSelected = { showDeleteDialog = true },
                onRefresh = onRefresh,
                isRefreshing = uiState.isRefreshing,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (rows.isEmpty()) {
                DiagnosticsEmptyState(
                    isGenerating = uiState.isGenerating,
                    onGenerateNow = onGenerateNow,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    item(key = "diagnostics-actions") {
                        DiagnosticsActionCard(
                            state = uiState,
                            actionState = actionState,
                            onGenerateNow = onGenerateNow,
                            onUploadSelected = onUploadSelected,
                        )
                    }
                    item(key = "diagnostics-divider") {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    }
                    items(rows, key = { it.id }) { row ->
                        DiagnosticsLogRow(
                            row = row,
                            onToggleSelection = onToggleSelection,
                        )
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.diagnostics_delete_selected_title)) },
            text = { Text(stringResource(R.string.diagnostics_delete_selected_confirm, actionState.selectedCount)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSelected()
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsTopBar(
    actionState: DiagnosticsActionState,
    isAmoled: Boolean,
    onNavigateBack: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onRefresh: () -> Unit,
    isRefreshing: Boolean,
) {
    if (actionState.hasSelection) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.diagnostics_selected_count, actionState.selectedCount),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            navigationIcon = {
                IconButton(onClick = onClearSelection) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.diagnostics_clear_selection))
                }
            },
            actions = {
                TextButton(
                    onClick = onSelectAll,
                    enabled = actionState.canSelectAll,
                ) {
                    Text(stringResource(R.string.diagnostics_select_all))
                }
                TextButton(onClick = onClearSelection) {
                    Text(stringResource(R.string.diagnostics_clear))
                }
                IconButton(
                    onClick = onDeleteSelected,
                    enabled = actionState.canDeleteSelected,
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.diagnostics_delete_selected_title),
                        tint = if (actionState.canDeleteSelected) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        },
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    } else {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.diagnostics_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
            actions = {
                IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.diagnostics_refresh))
                    }
                }
            },
        )
    }
}

@Composable
private fun DiagnosticsActionCard(
    state: DiagnosticsUiState,
    actionState: DiagnosticsActionState,
    onGenerateNow: () -> Unit,
    onUploadSelected: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Description, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.diagnostics_generated_logs),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.diagnostics_privacy_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.statusMessage?.let { message ->
                DiagnosticsMessageBanner(
                    message = message,
                    isError = false,
                )
            }
            state.errorMessage?.let { message ->
                DiagnosticsMessageBanner(
                    message = message,
                    isError = true,
                )
            }

            if (!state.uploadUrlConfigured || !state.uploadTokenConfigured) {
                Text(
                    text = stringResource(R.string.diagnostics_missing_config),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (!actionState.hasSelection) {
                Text(
                    text = stringResource(R.string.diagnostics_select_to_upload),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onGenerateNow,
                    enabled = !state.isGenerating && !state.isUploading,
                ) {
                    if (state.isGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.diagnostics_generating))
                    } else {
                        Text(stringResource(R.string.diagnostics_generate_now))
                    }
                }
                Button(
                    onClick = onUploadSelected,
                    enabled = actionState.canUpload,
                ) {
                    if (state.isUploading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_diagnostics_uploading))
                    } else {
                        Text(stringResource(R.string.diagnostics_upload_selected))
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsMessageBanner(
    message: String,
    isError: Boolean,
) {
    val contentColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val containerColor = contentColor.copy(alpha = 0.12f)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = BorderStroke(1.dp, contentColor.copy(alpha = 0.22f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isError) Icons.Default.Warning else Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = contentColor,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiagnosticsLogRow(
    row: DiagnosticsLogRowState,
    onToggleSelection: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val backgroundColor = if (row.isSelected) {
        colors.primaryContainer.copy(alpha = 0.32f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .combinedClickable(
                onClick = { onToggleSelection(row.id) },
                onLongClick = { onToggleSelection(row.id) },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = row.isSelected,
            onCheckedChange = { onToggleSelection(row.id) },
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = { onToggleSelection(row.id) },
                    label = { Text(row.typeLabel) },
                )
                Text(
                    text = row.displayName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.timestampLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = "·",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    text = row.sizeLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsEmptyState(
    isGenerating: Boolean,
    onGenerateNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Description,
            contentDescription = null,
            modifier = Modifier.size(52.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.diagnostics_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.diagnostics_empty_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onGenerateNow,
            enabled = !isGenerating,
        ) {
            if (isGenerating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.diagnostics_generating))
            } else {
                Text(stringResource(R.string.diagnostics_generate_now))
            }
        }
    }
}

@Composable
private fun rememberIsDiagnosticsAmoledTheme(): Boolean {
    val colors = MaterialTheme.colorScheme
    return colors.background == Color.Black && colors.surface == Color.Black
}
