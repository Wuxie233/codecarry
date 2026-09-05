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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import dev.wuxie233.codecarry.data.preferences.SessionScope
import dev.wuxie233.codecarry.domain.model.SessionStatus
import dev.wuxie233.codecarry.ui.screens.sessions.SessionRecentWorkItem
import dev.wuxie233.codecarry.ui.screens.sessions.components.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
) {
    val state by viewModel.uiState.collectAsState()
    var createOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<CodexThread?>(null) }
    var deleteTarget by remember { mutableStateOf<CodexThread?>(null) }
    LaunchedEffect(Unit) { viewModel.openThread.collect(onOpenThread) }

    CodexThreadListContent(
        state = state, onNavigateBack = onNavigateBack, onOpenThread = onOpenThread,
        actions = CodexThreadListActions(
            refresh = viewModel::refresh, create = { createOpen = true },
            setViewMode = viewModel::setViewMode, showArchived = viewModel::showArchived,
            setSearchQuery = viewModel::setSearchQuery, setFilter = viewModel::setFilter,
            toggleShowHiddenProjects = viewModel::toggleShowHiddenProjects,
            rename = { renameTarget = it }, delete = { deleteTarget = it },
            forkThread = viewModel::forkThread, archiveThread = viewModel::archiveThread,
            unarchiveThread = viewModel::unarchiveThread,
            toggleProjectCollapsed = viewModel::toggleProjectCollapsed,
            toggleProjectPinned = viewModel::toggleProjectPinned,
            toggleProjectHidden = viewModel::toggleProjectHidden,
            createThread = { viewModel.createThread(it) }, archiveProject = viewModel::archiveProject,
        ),
    )

    if (createOpen) {
        CodexDirectoryPicker(
            recentDirectories = state.recentDirectories,
            defaultDirectory = viewModel::defaultDirectory,
            readDirectory = viewModel::readDirectory,
            onDismiss = { createOpen = false },
            onSelect = { viewModel.createThread(it); createOpen = false },
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
) {
    var menuOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val isAmoled = colors.background == Color.Black && colors.surface == Color.Black
    val statusColor = when {
        thread.status.type == "systemError" -> colors.error
        else -> colors.tertiary
    }
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { direction ->
            when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> onRename()
                SwipeToDismissBoxValue.EndToStart -> if (archived) onRestore() else onArchive()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
        positionalThreshold = { it * 0.3f },
    )
    SwipeToDismissBox(
        state = swipeState,
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
            colors = CardDefaults.cardColors(containerColor = if (isAmoled) Color.Black else colors.surfaceVariant),
            border = if (isAmoled) BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.65f)) else null,
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        thread.name?.takeIf(String::isNotBlank)
                            ?: thread.preview.lineSequence().firstOrNull()?.takeIf(String::isNotBlank)
                            ?: stringResource(R.string.session_untitled),
                        style = MaterialTheme.typography.bodyLarge,
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
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodexThreadListContent(
    state: CodexThreadListUiState,
    onNavigateBack: () -> Unit,
    onOpenThread: (String) -> Unit,
    actions: CodexThreadListActions,
) {
    val clipboard = LocalClipboardManager.current
    val noWorkspace = stringResource(R.string.codex_thread_no_workspace)
    val projectsView = state.projectPreferences.viewMode == SessionListViewMode.PROJECTS
    val displayedThreads = if (projectsView) state.projects.flatMap { it.threads } else state.activityThreads
    val recentWork = remember(state.activeThreads, state.projectPreferences.hidden) {
        state.activeThreads.filter { it.cwd.orEmpty() !in state.projectPreferences.hidden }
            .sortedByDescending { it.recencyAt ?: it.updatedAt ?: it.createdAt ?: 0L }.take(6).map {
                SessionRecentWorkItem(it.id, it.name?.takeIf(String::isNotBlank) ?: it.preview.take(72),
                    it.cwd.orEmpty(), (it.recencyAt ?: it.updatedAt ?: it.createdAt ?: 0L) * 1000,
                    if (it.status.type == "active") SessionStatus.Busy else SessionStatus.Idle)
            }
    }

    Scaffold(
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
                    IconButton(onClick = actions.refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.codex_refresh))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = actions.create) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.codex_thread_new))
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 960.dp).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SessionWorkspaceOverview(
                    recentWork = if (recentWork.isNotEmpty()) ({
                        SessionRecentWork(recentWork, onSessionClick = { id, _ -> onOpenThread(id) })
                    }) else null,
                    viewControl = {
                        SessionWorkspaceViewControl(
                            state.projectPreferences.viewMode, state.activityThreads.size, actions.setViewMode,
                        )
                    },
                    projectControls = if (projectsView) ({
                        SessionScopeSegmentedControl(
                            if (state.showArchived) SessionScope.ARCHIVED else SessionScope.INBOX,
                            state.archivedThreads.size,
                            { actions.showArchived(it == SessionScope.ARCHIVED) },
                        )
                    }) else null,
                )
                CodexListControls(
                    searchQuery = state.searchQuery,
                    onSearchQueryChange = actions.setSearchQuery,
                    filter = state.filter,
                    onFilterChange = actions.setFilter,
                )
                if (projectsView && state.projectPreferences.hidden.isNotEmpty()) {
                    TextButton(onClick = actions.toggleShowHiddenProjects, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(stringResource(if (state.showHiddenProjects) R.string.codex_projects_hide_hidden else R.string.codex_projects_show_hidden,
                            state.projectPreferences.hidden.size))
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
                            onRetry = actions.refresh,
                            modifier = Modifier.padding(12.dp),
                        )
                        displayedThreads.isEmpty() -> EmptyStateCard(
                            title = if (!projectsView) stringResource(R.string.codex_projects_no_activity) else if (state.hasListConstraints) stringResource(R.string.codex_sessions_no_matches) else if (state.showArchived) stringResource(R.string.codex_thread_archived_empty) else stringResource(R.string.codex_thread_empty),
                            message = if (!projectsView) stringResource(R.string.codex_projects_no_activity_hint) else if (state.hasListConstraints) stringResource(R.string.codex_sessions_no_matches_hint) else if (state.showArchived) stringResource(R.string.codex_thread_archived_empty_message) else stringResource(R.string.codex_thread_empty_message),
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
                            fun androidx.compose.foundation.lazy.LazyListScope.threadRows(threads: List<CodexThread>, archived: Boolean) {
                                items(threads, key = { "thread:${it.id}" }) { thread ->
                                    CodexThreadRow(
                                        thread = thread,
                                        archived = archived,
                                        pendingCount = state.pendingRequestCounts.getOrDefault(thread.id, 0),
                                        onOpen = { onOpenThread(thread.id) },
                                        onRename = { actions.rename(thread) },
                                        onFork = { actions.forkThread(thread.id) },
                                        onArchive = { actions.archiveThread(thread.id) },
                                        onRestore = { actions.unarchiveThread(thread.id) },
                                        onDelete = { actions.delete(thread) },
                                    )
                                }
                            }
                            if (!projectsView) {
                                threadRows(displayedThreads, false)
                            } else state.projects.forEach { project ->
                                item("project:${project.directory}") {
                                    ProjectGroupHeader(
                                        projectName = project.directory.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { noWorkspace },
                                        tildeDirectory = project.directory.ifBlank { noWorkspace },
                                        sessionCount = project.threads.size,
                                        activeCount = project.threads.count { it.status.type == "active" },
                                        unreadCount = 0, additions = 0, deletions = 0,
                                        isPinned = project.pinned, isCollapsed = project.collapsed, isHidden = project.hidden,
                                        onToggleCollapsed = { actions.toggleProjectCollapsed(project.directory) },
                                        onTogglePinned = { actions.toggleProjectPinned(project.directory) },
                                        onToggleHidden = { actions.toggleProjectHidden(project.directory) },
                                        onNewSession = { actions.createThread(project.directory) },
                                        onCopyPath = { clipboard.setText(AnnotatedString(project.directory)) },
                                        onArchiveAll = if (!state.showArchived) ({ actions.archiveProject(project.directory) }) else null,
                                    )
                                }
                                if (!project.collapsed) threadRows(project.threads, state.showArchived)
                            }
                        }
                    }
                }
            }
        }
    }

}
