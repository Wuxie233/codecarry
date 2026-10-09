package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.Surface
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import dev.wuxie233.codecarry.data.codex.CodexProject
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.data.codex.CodexThread
import dev.wuxie233.codecarry.ui.components.EmptyStateCard
import dev.wuxie233.codecarry.ui.components.ErrorStateCard
import dev.wuxie233.codecarry.ui.components.LoadingStateCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import dev.wuxie233.codecarry.data.preferences.SessionListViewMode
import dev.wuxie233.codecarry.ui.screens.sessions.components.*
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.platform.testTag

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexThreadListScreen(
    onNavigateBack: () -> Unit,
    onOpenThread: (String) -> Unit,
    viewModel: CodexThreadListViewModel = hiltViewModel(),
    onNewConversation: (String?) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    var projectEditorOpen by remember { mutableStateOf(false) }
    var projectTarget by remember { mutableStateOf<CodexProject?>(null) }
    var removeProjectTarget by remember { mutableStateOf<CodexProject?>(null) }
    val accessibility = LocalAccessibilityManager.current
    val scope = rememberCoroutineScope()
    var renameTarget by remember { mutableStateOf<CodexThread?>(null) }
    var deleteTarget by remember { mutableStateOf<CodexThread?>(null) }
    LaunchedEffect(Unit) { viewModel.refresh(); viewModel.openThread.collect(onOpenThread) }

    CodexThreadListContent(
        state = state, onNavigateBack = onNavigateBack, onOpenThread = { id ->
            viewModel.selectProject((state.activeThreads + state.archivedThreads).firstOrNull { it.id == id }?.projectId)
            onOpenThread(id)
        },
        actions = CodexThreadListActions(
            refresh = viewModel::refresh, create = {
                onNewConversation(state.projectPreferences.selectedProjectId?.takeIf { id -> state.projectCatalog.any { it.id == id } && state.pendingProjectRemovals.none { it.projectId == id } && id !in state.deletingProjectIds })
            },
            createUnassigned = { viewModel.selectProject(null); onNewConversation(null) },
            addProject = { projectTarget = null; projectEditorOpen = true },
            editProject = { id -> projectTarget = state.projectCatalog.firstOrNull { it.id == id }; projectEditorOpen = projectTarget != null },
            removeProject = { id -> removeProjectTarget = state.projectCatalog.firstOrNull { it.id == id } },
            moveProject = viewModel::moveProject,
            undoProjectRemoval = viewModel::undoProjectRemoval,
            setViewMode = viewModel::setViewMode, showArchived = viewModel::showArchived,
            setSearchQuery = viewModel::setSearchQuery, setFilter = viewModel::setFilter,
            toggleShowHiddenProjects = viewModel::toggleShowHiddenProjects,
            rename = { renameTarget = it }, delete = { deleteTarget = it },
            forkThread = viewModel::forkThread, archiveThread = viewModel::archiveThread,
            unarchiveThread = viewModel::unarchiveThread,
            undoArchive = viewModel::undoArchive, dismissArchiveFeedback = viewModel::dismissArchiveFeedback,
            presentArchiveFeedback = viewModel::presentArchiveFeedback,
            toggleProjectCollapsed = viewModel::toggleProjectCollapsed,
            toggleProjectPinned = viewModel::toggleProjectPinned,
            toggleProjectHidden = viewModel::toggleProjectHidden,
            createThread = { viewModel.selectProject(it); onNewConversation(it) }, archiveProject = viewModel::archiveProject,
        ),
    )

    if (projectEditorOpen) {
        CodexProjectEditor(
            project = projectTarget, state = state, viewModel = viewModel,
            onDismiss = { projectEditorOpen = false },
            onSave = { name, roots, key -> scope.launch {
                if (viewModel.saveProject(projectTarget?.id, name, roots, key)) projectEditorOpen = false
            } },
        )
    }
    removeProjectTarget?.let { project ->
        AlertDialog(
            onDismissRequest = { removeProjectTarget = null },
            title = { Text(stringResource(R.string.codex_project_remove)) },
            text = { Text(stringResource(R.string.codex_project_remove_hint, project.name)) },
            confirmButton = { TextButton(onClick = {
                viewModel.removeProject(project.id, accessibility?.calculateRecommendedTimeoutMillis(8000L, containsIcons = false, containsText = true, containsControls = true) ?: 8000L)
                removeProjectTarget = null
            }) { Text(stringResource(R.string.codex_project_remove)) } },
            dismissButton = { TextButton(onClick = { removeProjectTarget = null }) { Text(stringResource(R.string.cancel)) } },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodexThreadRow(
    thread: CodexThread,
    archived: Boolean,
    pendingCount: Int,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onFork: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    isSubagent: Boolean = false,
    unread: Boolean = false,
    archiveBusy: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val isAmoled = colors.background == Color.Black && colors.surface == Color.Black
    val statusColor = when {
        thread.status.type == "systemError" -> colors.error
        else -> colors.tertiary
    }
    // Shared read model marker (issue #30); matches the native session unread accent.
    val unreadColor = Color(0xFF2196F3)
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { direction ->
            when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> onRename()
                SwipeToDismissBoxValue.EndToStart -> if (!archiveBusy) { if (archived) onRestore() else onArchive() }
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
        positionalThreshold = { it * 0.3f },
    )
    SwipeToDismissBox(
        state = swipeState,
        enableDismissFromEndToStart = !archiveBusy,
        modifier = Modifier.testTag("codex_thread_swipe:${thread.id}"),
        backgroundContent = {
            val right = swipeState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val label = stringResource(if (right) R.string.session_rename else if (archived) R.string.sessions_restore_action else R.string.sessions_archive_action)
            Box(Modifier.fillMaxSize().clip(CardDefaults.shape).background(if (right) colors.primaryContainer else if (archived) colors.secondaryContainer else colors.tertiaryContainer).padding(horizontal = 20.dp),
                contentAlignment = if (right) Alignment.CenterStart else Alignment.CenterEnd) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (right) Icons.Default.Edit else if (archived) Icons.Default.Restore else Icons.Default.Archive, null)
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
        },
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
            colors = CardDefaults.cardColors(containerColor = if (isSubagent) {
                if (isAmoled) colors.surfaceContainerLow else colors.surface
            } else if (isAmoled) Color.Black else colors.surfaceVariant),
            border = if (isAmoled) BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.65f)) else null,
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = if (isSubagent) 12.dp else 16.dp, vertical = if (isSubagent) 6.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (unread) {
                    Box(
                        Modifier
                            .padding(end = 8.dp)
                            .size(8.dp)
                            .testTag("codex_thread_unread:${thread.id}")
                            .background(unreadColor, androidx.compose.foundation.shape.CircleShape),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        thread.displayTitle
                            ?: stringResource(R.string.session_untitled),
                        style = if (isSubagent) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        (thread.recencyAt ?: thread.updatedAt ?: thread.createdAt)?.let { seconds ->
                            Text(
                                remember(seconds) {
                                    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(seconds * 1000))
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurface.copy(alpha = 0.5f),
                            )
                        }
                        if (pendingCount > 0 || thread.status.type == "active" || thread.status.type == "systemError") {
                            if (thread.status.type == "active" && pendingCount == 0) {
                                dev.wuxie233.codecarry.ui.screens.chat.PulsingDotsIndicator(
                                    dotSize = 4.dp, dotSpacing = 2.dp, color = statusColor,
                                )
                            }
                            Text(
                                if (pendingCount > 0) stringResource(R.string.codex_sessions_pending_count, pendingCount)
                                else stringResource(if (thread.status.type == "active") R.string.sessions_working else R.string.sessions_status_error),
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor,
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
                                onClick = { menuOpen = false; onRestore() }, enabled = !archiveBusy,
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.codex_thread_archive_action)) }, leadingIcon = { Icon(Icons.Default.Archive, null) },
                                onClick = { menuOpen = false; onArchive() }, enabled = !archiveBusy,
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

}

@Composable
private fun PathDialog(
    title: String,
    initial: String,
    confirmLabel: String,
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
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Native session control styling with Codex's own supported filters. */
@Composable
internal fun CodexListControls(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    filter: CodexThreadFilter,
    onFilterChange: (CodexThreadFilter) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(searchQuery.isNotBlank()) }
    val colors = MaterialTheme.colorScheme
    val isAmoled = colors.background == Color.Black && colors.surface == Color.Black
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (expanded || searchQuery.isNotBlank()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { onSearchQueryChange(""); expanded = false }) {
                        Icon(Icons.Default.Close, stringResource(R.string.sessions_search_clear))
                    }
                },
                placeholder = { Text(stringResource(R.string.sessions_search_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp)).clickable { expanded = true },
                shape = RoundedCornerShape(16.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, if (isAmoled) colors.outlineVariant else colors.outlineVariant.copy(alpha = 0.55f)),
            ) {
                Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { expanded = true }) {
                        Icon(Icons.Default.Search, stringResource(R.string.sessions_search_hint))
                    }
                    Text(stringResource(R.string.sessions_search_hint), style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CodexThreadFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { onFilterChange(option) },
                    label = { Text(stringResource(when (option) {
                        CodexThreadFilter.ALL -> R.string.codex_sessions_all
                        CodexThreadFilter.RUNNING -> R.string.codex_sessions_running
                        CodexThreadFilter.PENDING -> R.string.codex_sessions_pending
                        CodexThreadFilter.FAILED -> R.string.codex_topology_failed
                    })) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = if (isAmoled) Color.Black else colors.surface,
                        selectedContainerColor = colors.primaryContainer,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true, selected = filter == option,
                        borderColor = if (isAmoled) colors.outlineVariant else colors.outlineVariant.copy(alpha = 0.6f),
                        selectedBorderColor = if (isAmoled) colors.primaryContainer else colors.outlineVariant.copy(alpha = 0.3f),
                    ),
                )
            }
        }
    }
}

internal data class CodexThreadListActions(
    val refresh: () -> Unit = {},
    val create: () -> Unit = {},
    val createUnassigned: () -> Unit = {},
    val addProject: () -> Unit = {},
    val editProject: (String) -> Unit = {},
    val removeProject: (String) -> Unit = {},
    val moveProject: (String, String?) -> Unit = { _, _ -> },
    val undoProjectRemoval: (String) -> Unit = {},
    val setViewMode: (SessionListViewMode) -> Unit = {},
    val showArchived: (Boolean) -> Unit = {},
    val setSearchQuery: (String) -> Unit = {},
    val setFilter: (CodexThreadFilter) -> Unit = {},
    val toggleShowHiddenProjects: () -> Unit = {},
    val rename: (CodexThread) -> Unit = {},
    val delete: (CodexThread) -> Unit = {},
    val forkThread: (String) -> Unit = {},
    val archiveThread: (String) -> Unit = {},
    val unarchiveThread: (String) -> Unit = {},
    val toggleProjectCollapsed: (String) -> Unit = {},
    val toggleProjectPinned: (String) -> Unit = {},
    val toggleProjectHidden: (String) -> Unit = {},
    val createThread: (String) -> Unit = {},
    val archiveProject: (String) -> Unit = {},
    val undoArchive: (Long) -> Unit = {},
    val dismissArchiveFeedback: (Long) -> Unit = {},
    val presentArchiveFeedback: (Long, Long) -> Unit = { _, _ -> },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodexThreadListContent(
    state: CodexThreadListUiState,
    onNavigateBack: () -> Unit,
    onOpenThread: (String) -> Unit,
    actions: CodexThreadListActions,
) {
    var collapsedRunning by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var expandedHistory by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val archiveSnackbar = remember { SnackbarHostState() }
    var controlsOpen by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        snackbarHost = {
            CodexArchiveFeedbackHost(
                feedback = state.archiveFeedback.firstOrNull(),
                hostState = archiveSnackbar,
                onUndo = actions.undoArchive,
                onDismiss = actions.dismissArchiveFeedback,
                onPresent = actions.presentArchiveFeedback,
            )
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.serverName, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { controlsOpen = !controlsOpen }) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.codex_project_filters))
                    }
                    IconButton(onClick = actions.refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.codex_refresh))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 960.dp).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = actions.create, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.codex_project_new_conversation))
                    }
                    IconButton(onClick = actions.addProject) {
                        Icon(Icons.Default.FolderOpen, stringResource(R.string.codex_project_add))
                    }
                }
                if (controlsOpen) {
                    CodexListControls(state.searchQuery, actions.setSearchQuery, state.filter, actions.setFilter)
                    Row(Modifier.padding(horizontal = 16.dp)) {
                        FilterChip(selected = state.showArchived, onClick = { actions.showArchived(!state.showArchived) },
                            label = { Text(stringResource(R.string.codex_project_archived)) })
                    }
                }
                state.projectError?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
                state.pendingProjectRemovals.forEach { removal ->
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.codex_project_removed, removal.name), Modifier.weight(1f))
                        TextButton(onClick = { actions.undoProjectRemoval(removal.projectId) }) { Text(stringResource(R.string.codex_project_undo)) }
                    }
                }
                state.error?.takeIf { state.activeThreads.isNotEmpty() || state.archivedThreads.isNotEmpty() }?.let {
                    Text(it, modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                }
                Box(Modifier.fillMaxSize()) {
                    when {
                        (if (state.showArchived) state.isLoadingArchived && state.archivedThreads.isEmpty() else (state.isLoading || state.projectsLoading) && state.activeThreads.isEmpty() && state.projects.isEmpty()) -> LoadingStateCard(Modifier.padding(12.dp), stringResource(R.string.codex_thread_loading))
                        state.error != null && state.activeThreads.isEmpty() && state.archivedThreads.isEmpty() && state.projects.isEmpty() -> ErrorStateCard(
                            title = stringResource(R.string.codex_thread_load_failed),
                            message = state.error.orEmpty(),
                            onRetry = actions.refresh,
                            modifier = Modifier.padding(12.dp),
                        )
                        state.projects.isEmpty() && !state.projectsLoading -> EmptyStateCard(
                            title = if (state.hasListConstraints) stringResource(R.string.codex_sessions_no_matches) else if (state.showArchived) stringResource(R.string.codex_thread_archived_empty) else stringResource(R.string.codex_thread_empty),
                            message = if (state.hasListConstraints) stringResource(R.string.codex_sessions_no_matches_hint) else if (state.showArchived) stringResource(R.string.codex_thread_archived_empty_message) else stringResource(R.string.codex_thread_empty_message),
                            action = when {
                                state.hasListConstraints -> ({
                                    TextButton(onClick = {
                                        actions.setSearchQuery("")
                                        actions.setFilter(CodexThreadFilter.ALL)
                                    }) { Text(stringResource(R.string.codex_sessions_clear_filters)) }
                                })
                                state.showArchived -> null
                                else -> ({ Button(onClick = actions.create) { Text(stringResource(R.string.codex_thread_new)) } })
                            },
                            modifier = Modifier.padding(12.dp),
                        )
                        else -> SessionProjectsViewport(modifier = Modifier.fillMaxSize()) {
                            fun androidx.compose.foundation.lazy.LazyListScope.threadRows(nodes: List<CodexThreadNode>, archived: Boolean, depth: Int = 0) {
                                nodes.forEach { node ->
                                    val thread = node.thread
                                    item("thread:${thread.id}") {
                                        Row(
                                            Modifier.padding(start = (depth.coerceAtMost(4) * 24).dp),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            if (depth > 0 || node.orphan) {
                                                Box(Modifier.padding(top = 10.dp).width(2.dp).height(36.dp)
                                                    .clip(RoundedCornerShape(999.dp))
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)))
                                            }
                                            CodexThreadRow(
                                                thread = thread,
                                                isSubagent = depth > 0 || node.orphan,
                                                archived = archived,
                                                pendingCount = state.pendingRequestCounts.getOrDefault(thread.id, 0),
                                                unread = thread.id in state.unreadThreadIds,
                                                archiveBusy = thread.id in state.archiveBusyThreadIds,
                                                onOpen = { onOpenThread(thread.id) },
                                                onRename = { actions.rename(thread) },
                                                onFork = { actions.forkThread(thread.id) },
                                                onArchive = { actions.archiveThread(thread.id) },
                                                onRestore = { actions.unarchiveThread(thread.id) },
                                                onDelete = { actions.delete(thread) },
                                            )
                                        }
                                    }
                                    val (running, historical) = node.children.partition { it.runningCount > 0 }
                                    fun androidx.compose.foundation.lazy.LazyListScope.childGroup(
                                        children: List<CodexThreadNode>,
                                        history: Boolean,
                                    ) {
                                        if (children.isEmpty()) return
                                        val expanded = state.hasListConstraints || if (history) {
                                            thread.id in expandedHistory
                                        } else thread.id !in collapsedRunning
                                        item("disclosure:${thread.id}:$history") {
                                            SubagentDisclosureRow(
                                                label = stringResource(
                                                    if (history) R.string.sessions_subagents_historical_toggle
                                                    else R.string.sessions_subagents_running_toggle,
                                                    children.size,
                                                ),
                                                expanded = expanded,
                                                secondary = history,
                                                modifier = Modifier.padding(start = (depth.coerceAtMost(4) * 24).dp)
                                                    .testTag("codex_subagents:${thread.id}:$history"),
                                                onToggle = {
                                                    if (history) {
                                                        expandedHistory = if (thread.id in expandedHistory) expandedHistory - thread.id else expandedHistory + thread.id
                                                    } else {
                                                        collapsedRunning = if (thread.id in collapsedRunning) collapsedRunning - thread.id else collapsedRunning + thread.id
                                                    }
                                                },
                                            )
                                        }
                                        if (expanded) threadRows(children, archived, depth + 1)
                                    }
                                    childGroup(running, history = false)
                                    childGroup(historical, history = true)
                                }
                            }
                            state.projects.forEachIndexed { index, project ->
                                item("project:${project.projectId ?: "unassigned"}") {
                                    CompactCodexProjectHeader(
                                        project = project,
                                        busy = project.projectId in state.deletingProjectIds,
                                        onToggle = { project.projectId?.let(actions.toggleProjectCollapsed) },
                                        onNew = { project.projectId?.let(actions.createThread) ?: actions.createUnassigned() },
                                        onEdit = { project.projectId?.let(actions.editProject) },
                                        onRemove = { project.projectId?.let(actions.removeProject) },
                                        onMoveUp = if (index > 0 && project.projectId != null) ({ actions.moveProject(project.projectId, state.projects[index - 1].projectId) }) else null,
                                        onMoveDown = if (index < state.projects.indexOfLast { it.projectId != null } && project.projectId != null) ({ actions.moveProject(project.projectId, state.projects.getOrNull(index + 2)?.projectId) }) else null,
                                    )
                                }
                                if (!project.collapsed) threadRows(project.roots, state.showArchived)
                            }
                        }
                    }
                }
            }
        }
    }

}

@Composable
private fun CompactCodexProjectHeader(
    project: CodexThreadProject,
    busy: Boolean,
    onToggle: () -> Unit,
    onNew: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (project.projectId != null) Icon(
            if (project.collapsed) Icons.Default.ChevronRight else Icons.Default.ExpandMore,
            contentDescription = null, modifier = Modifier.size(24.dp),
        )
        Text(project.name ?: stringResource(R.string.codex_project_unassigned),
            Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(project.threads.size.toString(), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(enabled = !busy, onClick = onNew) { Icon(Icons.Default.Add, stringResource(R.string.codex_project_new_conversation)) }
        if (project.projectId != null) Box {
            IconButton(enabled = !busy, onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.codex_project_actions)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.codex_project_edit)) }, onClick = { menu = false; onEdit() })
                onMoveUp?.let { action -> DropdownMenuItem(text = { Text(stringResource(R.string.codex_project_move_up)) }, onClick = { menu = false; action() }) }
                onMoveDown?.let { action -> DropdownMenuItem(text = { Text(stringResource(R.string.codex_project_move_down)) }, onClick = { menu = false; action() }) }
                DropdownMenuItem(text = { Text(stringResource(R.string.codex_project_remove)) }, onClick = { menu = false; onRemove() })
            }
        }
    }
}

@Composable
private fun CodexProjectEditor(
    project: CodexProject?,
    state: CodexThreadListUiState,
    viewModel: CodexThreadListViewModel,
    onDismiss: () -> Unit,
    onSave: (String, List<String>, String) -> Unit,
) {
    var name by rememberSaveable(project?.id) { mutableStateOf(project?.name.orEmpty()) }
    var roots by rememberSaveable(project?.id) { mutableStateOf(project?.roots?.map { it.path }.orEmpty()) }
    var picker by remember { mutableStateOf(false) }
    val idempotencyKey = rememberSaveable(project?.id) { java.util.UUID.randomUUID().toString() }
    AlertDialog(
        onDismissRequest = { if (!state.projectBusy) onDismiss() },
        title = { Text(stringResource(if (project == null) R.string.codex_project_add else R.string.codex_project_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, enabled = !state.projectBusy,
                    label = { Text(stringResource(R.string.codex_project_name)) }, singleLine = true)
                Text(stringResource(R.string.codex_project_roots), style = MaterialTheme.typography.labelLarge)
                roots.forEachIndexed { index, root ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(root, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        IconButton(enabled = !state.projectBusy, onClick = { roots = roots.filterIndexed { i, _ -> i != index } }) {
                            Icon(Icons.Default.Close, stringResource(R.string.codex_project_remove_root))
                        }
                    }
                }
                TextButton(enabled = !state.projectBusy, onClick = { picker = true }) { Text(stringResource(R.string.codex_project_add_root)) }
                state.projectError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = !state.projectBusy && name.isNotBlank() && roots.isNotEmpty(),
            onClick = { onSave(name, roots, idempotencyKey) }) { Text(stringResource(R.string.codex_thread_save)) } },
        dismissButton = { TextButton(enabled = !state.projectBusy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    if (picker) CodexDirectoryPicker(
        recentDirectories = (roots + state.recentDirectories).distinct(),
        defaultDirectory = viewModel::defaultDirectory,
        readDirectory = viewModel::readDirectory,
        onDismiss = { picker = false },
        onSelect = { path ->
            roots = (roots + path).distinct()
            if (name.isBlank()) name = path.trimEnd('/').substringAfterLast('/')
            picker = false
        },
    )
}
