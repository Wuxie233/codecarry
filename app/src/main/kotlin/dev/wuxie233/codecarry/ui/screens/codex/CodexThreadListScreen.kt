package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.data.codex.CodexThread
import dev.wuxie233.codecarry.ui.components.EmptyStateCard
import dev.wuxie233.codecarry.ui.components.ErrorStateCard
import dev.wuxie233.codecarry.ui.components.LoadingStateCard
import dev.wuxie233.codecarry.ui.theme.StatusConnected
import dev.wuxie233.codecarry.ui.theme.StatusError
import dev.wuxie233.codecarry.ui.theme.StatusWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexThreadListScreen(
    onNavigateBack: () -> Unit,
    onOpenThread: (String) -> Unit,
    viewModel: CodexThreadListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var createOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<CodexThread?>(null) }
    var deleteTarget by remember { mutableStateOf<CodexThread?>(null) }
    val noWorkspace = stringResource(R.string.codex_thread_no_workspace)

    LaunchedEffect(Unit) { viewModel.openThread.collect(onOpenThread) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.serverName, maxLines = 1)
                        Text(
                            stringResource(R.string.codex_threads_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.codex_refresh))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { createOpen = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.codex_thread_new))
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 960.dp).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::setSearchQuery,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.codex_thread_search)) },
                    singleLine = true,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    listOf(
                        false to stringResource(R.string.codex_thread_active),
                        true to stringResource(R.string.codex_thread_archived),
                    ).forEachIndexed { index, (archived, label) ->
                        SegmentedButton(
                            selected = state.showArchived == archived,
                            onClick = { viewModel.showArchived(archived) },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                        ) { Text(label) }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CodexThreadFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = state.filter == filter,
                            onClick = { viewModel.setFilter(filter) },
                            label = { Text(stringResource(when (filter) {
                                CodexThreadFilter.ALL -> R.string.codex_sessions_all
                                CodexThreadFilter.RUNNING -> R.string.codex_sessions_running
                                CodexThreadFilter.PENDING -> R.string.codex_sessions_pending
                            })) },
                        )
                    }
                }
                state.error?.takeIf { state.activeThreads.isNotEmpty() || state.archivedThreads.isNotEmpty() }?.let {
                    Text(it, modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                }
                Box(Modifier.fillMaxSize()) {
                    when {
                        state.isLoading && state.activeThreads.isEmpty() && state.archivedThreads.isEmpty() -> LoadingStateCard(Modifier.padding(12.dp), stringResource(R.string.codex_thread_loading))
                        state.error != null && state.activeThreads.isEmpty() && state.archivedThreads.isEmpty() -> ErrorStateCard(
                            title = stringResource(R.string.codex_thread_load_failed),
                            message = state.error.orEmpty(),
                            onRetry = viewModel::refresh,
                            modifier = Modifier.padding(12.dp),
                        )
                        state.visibleThreads.isEmpty() -> EmptyStateCard(
                            title = if (state.hasListConstraints) stringResource(R.string.codex_sessions_no_matches) else if (state.showArchived) stringResource(R.string.codex_thread_archived_empty) else stringResource(R.string.codex_thread_empty),
                            message = if (state.hasListConstraints) stringResource(R.string.codex_sessions_no_matches_hint) else if (state.showArchived) stringResource(R.string.codex_thread_archived_empty_message) else stringResource(R.string.codex_thread_empty_message),
                            action = when {
                                state.hasListConstraints -> ({
                                    TextButton(onClick = {
                                        viewModel.setSearchQuery("")
                                        viewModel.setFilter(CodexThreadFilter.ALL)
                                    }) { Text(stringResource(R.string.codex_sessions_clear_filters)) }
                                })
                                state.showArchived -> null
                                else -> ({ Button(onClick = { createOpen = true }) { Text(stringResource(R.string.codex_thread_new)) } })
                            },
                            modifier = Modifier.padding(12.dp),
                        )
                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.visibleThreads.groupBy { it.cwd.orEmpty().ifBlank { noWorkspace } }.forEach { (cwd, threads) ->
                                item("cwd:$cwd") {
                                    Text(
                                        cwd,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                items(threads, key = { it.id }) { thread ->
                                    CodexThreadRow(
                                        thread = thread,
                                        archived = state.showArchived,
                                        pendingCount = state.pendingRequestCounts.getOrDefault(thread.id, 0),
                                        onOpen = { onOpenThread(thread.id) },
                                        onRename = { renameTarget = thread },
                                        onFork = { viewModel.forkThread(thread.id) },
                                        onArchive = { viewModel.archiveThread(thread.id) },
                                        onRestore = { viewModel.unarchiveThread(thread.id) },
                                        onDelete = { deleteTarget = thread },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (createOpen) {
        val suggested = rememberSaveable { state.recentDirectories.firstOrNull().orEmpty() }
        PathDialog(
            title = stringResource(R.string.codex_thread_create_title),
            initial = suggested,
            directories = state.recentDirectories,
            confirmLabel = stringResource(R.string.codex_thread_create),
            onDismiss = { createOpen = false },
            onConfirm = { viewModel.createThread(it); createOpen = false },
        )
    }
    renameTarget?.let { thread ->
        PathDialog(
            title = stringResource(R.string.codex_thread_rename),
            initial = thread.name.orEmpty(),
            confirmLabel = stringResource(R.string.codex_thread_save),
            onDismiss = { renameTarget = null },
            onConfirm = { viewModel.renameThread(thread.id, it); renameTarget = null },
        )
    }
    deleteTarget?.let { thread ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.codex_thread_delete_title)) },
            text = { Text(stringResource(R.string.codex_thread_delete_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteThread(thread.id); deleteTarget = null }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun CodexThreadRow(
    thread: CodexThread,
    archived: Boolean,
    pendingCount: Int,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onFork: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val statusColor = when {
        pendingCount > 0 || thread.status.type == "active" -> StatusWarning
        thread.status.type == "systemError" -> StatusError
        else -> StatusConnected
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(statusColor, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    thread.name?.takeIf(String::isNotBlank) ?: thread.preview.lineSequence().firstOrNull()?.take(72) ?: thread.id.take(8),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (thread.preview.isNotBlank() && thread.preview != thread.name) {
                    Text(thread.preview, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (pendingCount > 0) stringResource(R.string.codex_sessions_pending_count, pendingCount)
                        else stringResource(when (thread.status.type) {
                            "active" -> R.string.codex_sessions_running
                            "idle" -> R.string.codex_sessions_idle
                            "systemError" -> R.string.codex_sessions_error
                            "notLoaded" -> R.string.codex_sessions_not_loaded
                            else -> R.string.codex_sessions_unknown
                        }),
                        style = MaterialTheme.typography.labelSmall, color = statusColor,
                    )
                    thread.updatedAt?.let { seconds ->
                        Text(
                            remember(seconds) {
                                SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(seconds * 1000))
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.codex_thread_actions))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.codex_thread_rename_action)) }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.codex_thread_fork_action)) }, leadingIcon = { Icon(Icons.Default.CallSplit, null) },
                        onClick = { menuOpen = false; onFork() },
                    )
                    if (archived) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.codex_thread_restore_action)) }, leadingIcon = { Icon(Icons.Default.Restore, null) },
                            onClick = { menuOpen = false; onRestore() },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.codex_thread_archive_action)) }, leadingIcon = { Icon(Icons.Default.Archive, null) },
                            onClick = { menuOpen = false; onArchive() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun PathDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    directories: List<String>? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value, { value = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = if (directories != null) ({ Text(stringResource(R.string.codex_sessions_directory)) }) else null,
                )
                if (directories != null) {
                    Text(stringResource(R.string.codex_sessions_directory_hint), style = MaterialTheme.typography.bodySmall)
                    if (directories.isNotEmpty()) {
                        Text(stringResource(R.string.codex_sessions_recent_directories), style = MaterialTheme.typography.labelMedium)
                        LazyColumn(Modifier.heightIn(max = 240.dp)) {
                            items(directories, key = { it }) { directory ->
                                TextButton(onClick = { value = directory }, modifier = Modifier.fillMaxWidth()) {
                                    Text(directory, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = if (value == directory) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                    TextButton(onClick = { value = "" }) { Text(stringResource(R.string.codex_sessions_default_directory)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
