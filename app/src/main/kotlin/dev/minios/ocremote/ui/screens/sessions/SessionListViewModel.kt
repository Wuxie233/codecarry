package dev.minios.ocremote.ui.screens.sessions

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.minios.ocremote.data.api.FileNode
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.data.diagnostics.AppEventBreadcrumb
import dev.minios.ocremote.data.diagnostics.AppEventDiagnosticsGenerator
import dev.minios.ocremote.data.diagnostics.AppEventName
import dev.minios.ocremote.data.preferences.SessionFilter
import dev.minios.ocremote.data.preferences.SessionListPreferences
import dev.minios.ocremote.data.preferences.SessionListPreferencesRepository
import dev.minios.ocremote.data.preferences.SessionSort
import dev.minios.ocremote.data.preferences.SessionScope
import dev.minios.ocremote.data.repository.EventReducer
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.domain.model.Project
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.model.SseEvent
import dev.minios.ocremote.ui.screens.sessions.components.ActiveConversationItem
import dev.minios.ocremote.ui.screens.sessions.components.ConversationStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.net.URLDecoder
import javax.inject.Inject

private const val TAG = "SessionListViewModel"

private fun decodeRouteArg(value: String?): String {
    val raw = value.orEmpty()
    if (!raw.contains('%')) return raw
    var index = raw.indexOf('%')
    while (index >= 0) {
        if (index + 2 >= raw.length || !raw[index + 1].isDigitOrHex() || !raw[index + 2].isDigitOrHex()) {
            return raw
        }
        index = raw.indexOf('%', startIndex = index + 1)
    }
    return runCatching { URLDecoder.decode(raw, "UTF-8") }
        .getOrDefault(raw)
}

private fun Char.isDigitOrHex(): Boolean = this in '0'..'9' || this in 'A'..'F' || this in 'a'..'f'

private fun logErrorCompat(tag: String, message: String, throwable: Throwable) {
    try {
        Log.e(tag, message, throwable)
    } catch (error: RuntimeException) {
        if (!error.message.orEmpty().contains("android.util.Log not mocked")) {
            throw error
        }
    }
}

data class SessionListUiState(
    val serverName: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,

    val activeConversations: List<ActiveConversationItem> = emptyList(),
    val groups: List<ProjectGroup> = emptyList(),
    val sessionGroups: List<ProjectSessionGroup> = emptyList(),

    val sort: SessionSort = SessionSort.RECENT_UPDATED,
    val filter: SessionFilter = SessionFilter.ALL,
    val scope: SessionScope = SessionScope.INBOX,
    val archivedCount: Int = 0,
    val searchQuery: String = "",
    val hasAnySessions: Boolean = false,
    val isFilteredEmpty: Boolean = false,

    val selectedIds: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,

    val projects: List<Project> = emptyList(),

    val hiddenProjectCount: Int = 0,
    val showHiddenProjects: Boolean = false,
)

/** A group of sessions belonging to a project. */
data class ProjectSessionGroup(
    val projectId: String,
    val projectName: String,
    val directory: String,
    val sessions: List<SessionItem>,
    /** Per-session tilde-path labels (sessionId -> tildePath) for flat display. */
    val sessionDirLabels: Map<String, String> = emptyMap()
)

data class ProjectGroup(
    val directory: String,
    val projectName: String,
    val tildeDirectory: String,
    val isPinned: Boolean,
    val isCollapsed: Boolean,
    val isHidden: Boolean,
    val sessionCount: Int,
    val activeCount: Int,
    val additionsSum: Int,
    val deletionsSum: Int,
    val sessions: List<SessionItem>,
    val subagentRowsByParent: Map<String, SubagentRow>,
    val sessionDirLabels: Map<String, String> = emptyMap(),
    val unreadCount: Int = 0,
)

internal fun deriveAllDirectories(
    projectDirectories: List<String>,
    rootSessionDirectories: List<String>,
    pinnedDirectories: List<String>,
): List<String> = (projectDirectories + rootSessionDirectories + pinnedDirectories).distinct()

internal fun isProjectGroupVisible(
    group: ProjectGroup,
    showHiddenProjects: Boolean,
): Boolean {
    val visibleByDefault = group.sessionCount > 0 || group.isPinned
    val hiddenFilter = if (group.isHidden) showHiddenProjects else true
    return visibleByDefault && hiddenFilter
}

data class SubagentRow(
    val running: List<SessionItem>,
    val historical: List<SessionItem>,
) {
    val total: Int get() = running.size + historical.size

    companion object {
        val EMPTY = SubagentRow(emptyList(), emptyList())
    }
}

data class SessionItem(
    val session: Session,
    val status: SessionStatus = SessionStatus.Idle,
    val isUnread: Boolean = false,
)

internal fun matchesSessionFilter(item: SessionItem, filter: SessionFilter): Boolean {
    val session = item.session
    return when (filter) {
        SessionFilter.ALL -> !session.isArchived
        SessionFilter.WORKING -> !session.isArchived && item.status is SessionStatus.Busy
        SessionFilter.HAS_CHANGES -> !session.isArchived && ((session.summary?.additions ?: 0) + (session.summary?.deletions ?: 0) > 0)
        SessionFilter.HAS_ERRORS -> !session.isArchived && item.status is SessionStatus.Retry
    }
}

internal fun matchesScope(item: SessionItem, scope: SessionScope): Boolean {
    return when (scope) {
        SessionScope.INBOX -> !item.session.isArchived
        SessionScope.ARCHIVED -> item.session.isArchived
    }
}

internal fun matchesScopeAndFilter(
    item: SessionItem,
    scope: SessionScope,
    filter: SessionFilter,
): Boolean {
    if (!matchesScope(item, scope)) return false
    // In Archived scope, status filters are meaningless (the design forces filter=ALL).
    return when (scope) {
        SessionScope.ARCHIVED -> true
        SessionScope.INBOX -> matchesSessionFilter(item, filter)
    }
}

internal fun archiveableRootSessionIds(
    sessions: List<Session>,
    directory: String,
    normalizeDirectory: (String) -> String,
): List<String> {
    val normalizedDirectory = normalizeDirectory(directory)
    return sessions
        .filter { it.parentId == null }
        .filter { normalizeDirectory(it.directory) == normalizedDirectory }
        .filter { !it.isArchived }
        .map { it.id }
}

internal enum class PinDirectoryRefreshTarget {
    PROJECTS,
    SESSIONS,
}

internal fun pinDirectoryRefreshTargets(changed: Boolean): Set<PinDirectoryRefreshTarget> {
    return if (changed) {
        setOf(PinDirectoryRefreshTarget.PROJECTS, PinDirectoryRefreshTarget.SESSIONS)
    } else {
        setOf(PinDirectoryRefreshTarget.SESSIONS)
    }
}

@HiltViewModel
class SessionListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val eventReducer: EventReducer,
    private val api: OpenCodeApi,
    private val preferencesRepo: SessionListPreferencesRepository,
    private val settingsRepository: SettingsRepository,
    private val appEventDiagnosticsGenerator: AppEventDiagnosticsGenerator,
) : ViewModel() {

    val serverUrl: String = decodeRouteArg(savedStateHandle.get<String>("serverUrl"))
    private val username: String = decodeRouteArg(savedStateHandle.get<String>("username"))
    private val password: String = decodeRouteArg(savedStateHandle.get<String>("password"))
    val serverName: String = decodeRouteArg(savedStateHandle.get<String>("serverName"))
    val serverId: String = decodeRouteArg(savedStateHandle.get<String>("serverId"))

    private val conn = ServerConnection.from(serverUrl, username, password.ifEmpty { null })
    val currentConnection: ServerConnection = conn

    private val _error = MutableStateFlow<String?>(null)
    private val _isLoading = MutableStateFlow(true)
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    private val _homeDir = MutableStateFlow<String?>(null)
    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    private val _searchQuery = MutableStateFlow("")
    private val _filter = MutableStateFlow(SessionFilter.ALL)
    private val _scopeOverride = MutableStateFlow<SessionScope?>(null)
    private val _showHiddenProjects = MutableStateFlow(false)
    private val _navigateToSession = MutableSharedFlow<Session>(extraBufferCapacity = 1)
    val navigateToSession: SharedFlow<Session> = _navigateToSession.asSharedFlow()
    private val _undoState = Channel<UndoAction>(Channel.BUFFERED)
    internal val undoState: kotlinx.coroutines.flow.Flow<UndoAction> = _undoState.receiveAsFlow()
    private var isCreatingSession = false
    private val prefsFlow = preferencesRepo.preferences.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        SessionListPreferences.DEFAULT,
    )

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<SessionListUiState> = combine(
        listOf(
            eventReducer.sessions,
            eventReducer.sessionStatuses,
            eventReducer.serverSessions,
            _isLoading,
            _error,
            _projects,
            _homeDir,
            _selectedIds,
            prefsFlow,
            _searchQuery,
            _filter,
            _scopeOverride,
            eventReducer.questions,
            eventReducer.permissions,
            _showHiddenProjects,
        )
    ) { values ->
        val allSessions = values[0] as List<Session>
        val statuses = values[1] as Map<String, SessionStatus>
        val serverSessions = values[2] as Map<String, Set<String>>
        val loading = values[3] as Boolean
        val error = values[4] as String?
        val projects = values[5] as List<Project>
        val homeDir = values[6] as String?
        val selectedIds = values[7] as Set<String>
        val prefs = values[8] as SessionListPreferences
        val searchQuery = values[9] as String
        val filter = values[10] as SessionFilter
        val scope = (values[11] as SessionScope?) ?: prefs.scope
        val pendingQuestions = values[12] as Map<String, List<SseEvent.QuestionAsked>>
        val pendingPermissions = values[13] as Map<String, List<SseEvent.PermissionAsked>>
        val showHiddenProjects = values[14] as Boolean
        val unreadMainSessionIds = prefs.unreadMainSessionIds

        val serverSessionIds = serverSessions[serverId] ?: emptySet()
        val serverScopedSessions = allSessions.filter { it.id in serverSessionIds }
        val archivedCount = serverScopedSessions.count { it.parentId == null && it.isArchived }
        val sessionsById = serverScopedSessions.associateBy { it.id }
        val itemsById = serverScopedSessions.associate { session ->
            session.id to SessionItem(
                session = session,
                status = statuses[session.id] ?: SessionStatus.Idle,
                isUnread = session.parentId == null && session.id in unreadMainSessionIds,
            )
        }

        val (rootSessions, childSessions) = serverScopedSessions.partition { it.parentId == null }
        val childBuckets = childSessions.groupBy { it.parentId!! }
        val activeConversations = buildActiveConversations(
            rootSessions = rootSessions,
            childSessionsByParent = childBuckets,
            statuses = statuses,
            pendingQuestions = pendingQuestions,
            pendingPermissions = pendingPermissions,
            unreadSessionIds = unreadMainSessionIds,
        )
        val projectByDirectory = projects.associateBy { normalizeDirectory(it.worktree) }
        val allDirectories = deriveAllDirectories(
            projectDirectories = projects.map { normalizeDirectory(it.worktree) },
            rootSessionDirectories = rootSessions.map { normalizeDirectory(it.directory) },
            pinnedDirectories = prefs.pinnedDirs.map(::normalizeDirectory),
        )

        val rawGroups = allDirectories.map { directory ->
            val project = projectByDirectory[directory]
            val projectName = project?.displayName?.takeIf { it.isNotBlank() } ?: displayNameFromDirectory(directory)
            val tildeDirectory = toTildePath(directory, homeDir)
            val isPinned = directory in prefs.pinnedDirs
            val isCollapsed = directory in prefs.collapsedDirs
            val groupedRoots = rootSessions.filter { normalizeDirectory(it.directory) == directory }

            val filteredRoots = groupedRoots
                .filter { session ->
                    matchesScopeAndFilter(itemsById.getValue(session.id), scope, filter)
                }
                .filter { session ->
                    matchesSearch(
                        session = session,
                        directory = directory,
                        projectName = projectName,
                        query = searchQuery,
                    )
                }
                .sortedWith(rootSessionComparator(prefs.sort))

            val filteredRootItems = filteredRoots.map { itemsById.getValue(it.id) }
            val subagentRowsByParent = filteredRoots.associate { root ->
                val childItems = (childBuckets[root.id] ?: emptyList())
                    .filter { child -> matchesChildScope(child, scope) }
                    .map { itemsById.getValue(it.id) }
                    .sortedWith(sessionItemComparator(prefs.sort))
                root.id to partitionSubagentsByActivity(childItems)
            }

            ProjectGroup(
                directory = directory,
                projectName = projectName,
                tildeDirectory = tildeDirectory,
                isPinned = isPinned,
                isCollapsed = isCollapsed,
                isHidden = directory in prefs.hiddenDirs,
                sessionCount = filteredRootItems.size,
                activeCount = filteredRootItems.count { it.status is SessionStatus.Busy },
                unreadCount = filteredRootItems.count { it.isUnread },
                additionsSum = filteredRootItems.sumOf { it.session.summary?.additions ?: 0 },
                deletionsSum = filteredRootItems.sumOf { it.session.summary?.deletions ?: 0 },
                sessions = filteredRootItems,
                subagentRowsByParent = subagentRowsByParent,
                sessionDirLabels = filteredRootItems.associate { it.session.id to tildeDirectory },
            )
        }

        val hiddenProjectCount = rawGroups.count { it.isHidden }
        val groups = rawGroups
            .filter { group -> isProjectGroupVisible(group, showHiddenProjects) }
            .sortedWith(
                compareBy<ProjectGroup> { group ->
                    val pinnedIndex = prefs.pinnedDirs.indexOf(group.directory)
                    if (pinnedIndex >= 0) 0 else 1
                }.thenBy { group ->
                    val pinnedIndex = prefs.pinnedDirs.indexOf(group.directory)
                    if (pinnedIndex >= 0) pinnedIndex else Int.MAX_VALUE
                }.thenByDescending { group ->
                    group.sessions.maxOfOrNull { it.session.time.updated } ?: Long.MIN_VALUE
                }.thenBy { it.projectName.lowercase() }
            )

        val legacySessionGroups = groups.map { group ->
            ProjectSessionGroup(
                projectId = projectByDirectory[group.directory]?.id ?: group.directory,
                projectName = group.projectName,
                directory = group.tildeDirectory,
                sessions = group.sessions,
                sessionDirLabels = group.sessionDirLabels,
            )
        }

        val visibleSessionIds = groups.flatMap { group -> group.sessions.map { it.session.id } }.toSet()
        val validSelectedIds = selectedIds.intersect(visibleSessionIds)
        if (validSelectedIds != selectedIds) {
            _selectedIds.value = validSelectedIds
        }

        val emptyState = computeSessionListEmptyState(
            rootSessionCount = rootSessions.size,
            visibleGroupCount = groups.size,
        )

        SessionListUiState(
            serverName = serverName,
            isLoading = loading,
            error = error,
            activeConversations = activeConversations,
            groups = groups,
            sessionGroups = legacySessionGroups,
            sort = prefs.sort,
            filter = filter,
            scope = scope,
            archivedCount = archivedCount,
            searchQuery = searchQuery,
            hasAnySessions = emptyState.hasAnySessions,
            isFilteredEmpty = emptyState.isFilteredEmpty,
            selectedIds = validSelectedIds,
            isSelectionMode = validSelectedIds.isNotEmpty(),
            projects = projects,
            hiddenProjectCount = hiddenProjectCount,
            showHiddenProjects = showHiddenProjects,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        SessionListUiState(serverName = serverName)
    )

    init {
        loadHomeDir()
        loadSessions()
        viewModelScope.launch {
            preferencesRepo.setFilter(SessionFilter.ALL)
        }
    }

    fun loadSessions() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val projects = api.listProjects(conn)
                _projects.value = projects

                val roots = api.listSessions(conn, rootsOnly = true)
                eventReducer.setSessions(serverId, roots)
                if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${roots.size} root sessions, fetching children across ${projects.size} projects")

                coroutineScope {
                    projects.map { project ->
                        async {
                            try {
                                api.listSessions(conn, directory = project.worktree, rootsOnly = false)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to load children for project ${project.displayName}: ${e.message}")
                                emptyList()
                            }
                        }
                    }.awaitAll().flatten()
                }.takeIf { it.isNotEmpty() }?.let { all ->
                    // Store all sessions (roots + children) so that root sessions not captured
                    // by the global roots-only call are still shown (e.g. when the server
                    // scopes the root-less endpoint to its own directory). setSessions merges
                    // by ID, so duplicates from the global call are handled gracefully.
                    eventReducer.setSessions(serverId, all)
                    if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${all.size} project-scoped sessions (${all.count { it.parentId != null }} children, ${all.count { it.parentId == null }} roots)")
                }
            } catch (e: Exception) {
                logErrorCompat(TAG, "Failed to load sessions", e)
                _error.value = e.message ?: "Failed to load sessions"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun loadProjects() {
        viewModelScope.launch {
            try {
                val projects = api.listProjects(conn)
                _projects.value = projects
                if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${projects.size} projects")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load projects", e)
            }
        }
    }

    private fun loadHomeDir() {
        viewModelScope.launch {
            getHomeDirectory()
        }
    }

    fun createNewSession(directory: String? = null) {
        if (isCreatingSession) return
        isCreatingSession = true
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                persistCreateNewEvent(
                    name = AppEventName.CREATE_NEW_TAPPED,
                    directory = directory,
                )
                _filter.value = SessionFilter.ALL
                _scopeOverride.value = SessionScope.INBOX
                val session = api.createSession(conn, directory = directory)
                if (session.id.isBlank()) {
                    throw IllegalStateException("Failed to create session: blank session id")
                }
                val createdDirectory = if (session.directory.isBlank()) {
                    resolveCreatedSessionDirectory(directory)
                } else {
                    session.directory
                }
                val normalizedSession = session.copy(directory = createdDirectory)
                eventReducer.setSessions(serverId, listOf(normalizedSession))
                if (BuildConfig.DEBUG) Log.d(TAG, "Created new session: ${normalizedSession.id}")
                persistCreateNewEvent(
                    name = AppEventName.CREATE_NEW_SUCCESS,
                    directory = normalizedSession.directory,
                    sessionId = normalizedSession.id,
                )
                _navigateToSession.tryEmit(normalizedSession)
            } catch (e: Exception) {
                logErrorCompat(TAG, "Failed to create session", e)
                persistCreateNewEvent(
                    name = AppEventName.CREATE_NEW_FAILURE,
                    directory = directory,
                    details = mapOf(
                        "error_class" to e::class.java.name,
                        "error_message" to e.message.orEmpty(),
                    ),
                )
                _error.value = e.message ?: "Failed to create session"
            } finally {
                _isLoading.value = false
                yield()
                isCreatingSession = false
            }
        }
    }

    private fun persistCreateNewEvent(
        name: AppEventName,
        directory: String?,
        sessionId: String? = null,
        details: Map<String, String> = emptyMap(),
    ) {
        try {
            appEventDiagnosticsGenerator.createArtifact(
                breadcrumbs = listOf(
                    AppEventBreadcrumb(
                        name = name,
                        timestampMillis = System.currentTimeMillis(),
                        sessionId = sessionId,
                        serverId = serverId,
                        serverName = serverName,
                        directory = directory,
                        details = details,
                    )
                ),
                sessionId = sessionId,
                serverName = serverName,
            )
        } catch (error: Exception) {
            logErrorCompat(TAG, "Failed to persist create-new diagnostics", error)
        }
    }

    private suspend fun resolveCreatedSessionDirectory(requestedDirectory: String?): String {
        requestedDirectory?.takeIf { it.isNotBlank() }?.let { return it }
        val paths = api.getServerPaths(conn)
        return paths.directory.takeIf { it.isNotBlank() }
            ?: paths.worktree.takeIf { it.isNotBlank() }
            ?: paths.home
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            try {
                val success = api.deleteSession(conn, sessionId)
                if (success) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Deleted session $sessionId")
                    loadSessions()
                } else {
                    _error.value = "Failed to delete session"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete session", e)
                _error.value = e.message ?: "Failed to delete session"
            }
        }
    }

    fun toggleSelection(sessionId: String) {
        _selectedIds.update { selected ->
            if (sessionId in selected) selected - sessionId else selected + sessionId
        }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun selectAll() {
        val allIds = uiState.value.groups
            .flatMap { group -> group.sessions.map { it.session.id } }
            .toSet()
        _selectedIds.value = allIds
    }

    fun setSearchQuery(q: String) {
        _searchQuery.value = q
    }

    fun setSort(s: SessionSort) {
        viewModelScope.launch {
            preferencesRepo.setSort(s)
        }
    }

    fun setFilter(f: SessionFilter) {
        _filter.value = if (_filter.value == f && f != SessionFilter.ALL) SessionFilter.ALL else f
    }

    fun setScope(scope: SessionScope) {
        _scopeOverride.value = scope
        viewModelScope.launch {
            preferencesRepo.setScope(scope)
        }
    }

    fun clearFilter() {
        _filter.value = SessionFilter.ALL
    }

    fun togglePinned(dir: String) {
        viewModelScope.launch {
            preferencesRepo.togglePinned(normalizeDirectory(dir))
        }
    }

    fun pinDirectory(dir: String) {
        viewModelScope.launch {
            val normalizedDirectory = normalizeDirectory(dir)
            val refreshTargets = pinDirectoryRefreshTargets(
                changed = preferencesRepo.addPinned(normalizedDirectory),
            )
            if (PinDirectoryRefreshTarget.PROJECTS in refreshTargets) {
                loadProjects()
            }
            if (PinDirectoryRefreshTarget.SESSIONS in refreshTargets) {
                loadSessions()
            }
        }
    }

    fun toggleCollapsed(dir: String) {
        viewModelScope.launch {
            val normalized = normalizeDirectory(dir)
            preferencesRepo.setCollapsed(
                normalized,
                collapsed = normalized !in prefsFlow.value.collapsedDirs,
            )
        }
    }

    fun toggleHidden(dir: String) {
        viewModelScope.launch {
            preferencesRepo.toggleHidden(normalizeDirectory(dir))
        }
    }

    fun toggleShowHiddenProjects() {
        _showHiddenProjects.value = !_showHiddenProjects.value
    }

    fun archiveProjectSessions(dir: String) {
        viewModelScope.launch {
            val targetIds = archiveableRootSessionIds(
                sessions = serverScopedSessions(),
                directory = dir,
                normalizeDirectory = ::normalizeDirectory,
            )
            if (targetIds.isEmpty()) return@launch

            try {
                coroutineScope {
                    targetIds.map { sessionId ->
                        async { api.archiveSession(conn, sessionId) }
                    }.awaitAll()
                }
                loadSessions()
            } catch (e: Exception) {
                logErrorCompat(TAG, "Failed to archive sessions for directory $dir", e)
                _error.value = e.message ?: "Failed to archive sessions"
            }
        }
    }

    fun archiveSession(sessionId: String) {
        viewModelScope.launch {
            val title = serverScopedSessions().firstOrNull { it.id == sessionId }?.title.orEmpty()
            try {
                api.archiveSession(conn, sessionId)
                loadSessions()
                _undoState.send(UndoAction.Archive(sessionId = sessionId, title = title))
            } catch (e: Exception) {
                logErrorCompat(TAG, "Failed to archive session $sessionId", e)
                _error.value = e.message ?: "Failed to archive session"
                _undoState.send(UndoAction.Failure(messageResId = R.string.sessions_archive_failed))
            }
        }
    }

    fun restoreSession(sessionId: String) {
        viewModelScope.launch {
            val title = serverScopedSessions().firstOrNull { it.id == sessionId }?.title.orEmpty()
            try {
                api.restoreSession(conn, sessionId)
                loadSessions()
                _undoState.send(UndoAction.Restore(sessionId = sessionId, title = title))
            } catch (e: Exception) {
                logErrorCompat(TAG, "Failed to restore session $sessionId", e)
                _error.value = e.message ?: "Failed to restore session"
                _undoState.send(UndoAction.Failure(messageResId = R.string.sessions_restore_failed))
            }
        }
    }

    private fun serverScopedSessions(): List<Session> {
        val sessionIds = eventReducer.serverSessions.value[serverId] ?: emptySet()
        return eventReducer.sessions.value.filter { it.id in sessionIds }
    }

    fun deleteSelected() {
        viewModelScope.launch {
            val ids = _selectedIds.value
            if (ids.isEmpty()) return@launch
            try {
                val results = coroutineScope {
                    ids.map { id ->
                        async {
                            id to api.deleteSession(conn, id)
                        }
                    }.awaitAll()
                }
                val failed = results.filterNot { it.second }
                if (failed.isNotEmpty()) {
                    _error.value = "Failed to delete ${failed.size} session(s)"
                }
                clearSelection()
                loadSessions()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete selected sessions", e)
                _error.value = e.message ?: "Failed to delete selected sessions"
            }
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch {
            try {
                api.updateSession(conn, sessionId, newTitle)
                if (BuildConfig.DEBUG) Log.d(TAG, "Renamed session $sessionId to '$newTitle'")
                loadSessions()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rename session", e)
                _error.value = e.message ?: "Failed to rename session"
            }
        }
    }

    // ============ Directory browsing for Open Project ============

    /** Get the server's home directory (cached). */
    suspend fun getHomeDirectory(): String {
        _homeDir.value?.let { return it }
        return try {
            val paths = api.getServerPaths(conn)
            val home = paths.home
            _homeDir.value = home
            if (BuildConfig.DEBUG) Log.d(TAG, "Server home directory: $home")
            home
        } catch (e: Exception) {
            logErrorCompat(TAG, "Failed to get server paths", e)
            "/"
        }
    }

    /** List directories in a given path on the server. */
    suspend fun listDirectories(directory: String): List<FileNode> {
        return try {
            val nodes = api.listDirectory(conn, path = "", directory = directory)
            nodes.filter { it.type == "directory" }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list directory: $directory", e)
            emptyList()
        }
    }

    /** Search for directories matching a query, scoped to a base directory. */
    suspend fun searchDirectories(query: String, directory: String): List<String> {
        return try {
            api.findFiles(conn, query = query, type = "directory", directory = directory, limit = 50)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search directories", e)
            emptyList()
        }
    }

    /** Create a directory inside the currently browsed path. */
    suspend fun createDirectory(parentDirectory: String, folderName: String): Result<String> {
        val sanitized = folderName.trim().trim('/').replace(Regex("/+"), "/")
        if (sanitized.isBlank() || sanitized == "." || sanitized == "..") {
            return Result.failure(IllegalArgumentException("Invalid folder name"))
        }

        return runCatching {
            val targetDirectory = if (parentDirectory == "/") {
                "/$sanitized"
            } else {
                "${parentDirectory.trimEnd('/')}/$sanitized"
            }

            val tempSession = api.createSession(
                conn = conn,
                title = "mkdir",
                directory = parentDirectory,
            )

            try {
                val escaped = sanitized.replace("'", "'\"'\"'")
                val command = "mkdir -p -- '$escaped'"

                val runShellOk = runCatching {
                    api.runShellCommand(
                        conn = conn,
                        sessionId = tempSession.id,
                        command = command,
                        agent = "build",
                        directory = parentDirectory,
                    )
                }.getOrElse { false }

                if (!runShellOk) {
                    val executeOk = api.executeCommand(
                        conn = conn,
                        sessionId = tempSession.id,
                        command = "bash",
                        arguments = "-lc \"$command\"",
                        directory = parentDirectory,
                    )
                    if (!executeOk) {
                        throw IllegalStateException("Failed to create directory")
                    }
                }
            } finally {
                runCatching { api.deleteSession(conn, tempSession.id) }
            }

            repeat(6) {
                if (directoryExists(targetDirectory)) {
                    return@runCatching targetDirectory
                }
                delay(200)
            }

            throw IllegalStateException("Directory was not created")
        }
    }

    private suspend fun directoryExists(directory: String): Boolean {
        return try {
            api.listDirectory(conn, path = "", directory = directory)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun matchesChildScope(session: Session, scope: SessionScope): Boolean {
        return when (scope) {
            SessionScope.ARCHIVED -> session.isArchived
            SessionScope.INBOX -> !session.isArchived
        }
    }

    private fun matchesSearch(
        session: Session,
        directory: String,
        projectName: String,
        query: String,
    ): Boolean {
        if (query.isBlank()) return true
        return session.title.orEmpty().contains(query, ignoreCase = true) ||
            directory.contains(query, ignoreCase = true) ||
            projectName.contains(query, ignoreCase = true)
    }

    private fun rootSessionComparator(sort: SessionSort): Comparator<Session> {
        return when (sort) {
            SessionSort.RECENT_UPDATED -> compareByDescending<Session> { it.time.updated }
            SessionSort.CREATED_TIME -> compareByDescending<Session> { it.time.created }
            SessionSort.TITLE_ALPHA -> compareBy<Session> { it.title.orEmpty().lowercase() }
                .thenByDescending { it.time.updated }
        }
    }

    private fun sessionItemComparator(sort: SessionSort): Comparator<SessionItem> {
        return when (sort) {
            SessionSort.RECENT_UPDATED -> compareByDescending<SessionItem> { it.session.time.updated }
            SessionSort.CREATED_TIME -> compareByDescending<SessionItem> { it.session.time.created }
            SessionSort.TITLE_ALPHA -> compareBy<SessionItem> { it.session.title.orEmpty().lowercase() }
                .thenByDescending { it.session.time.updated }
        }
    }

    private fun normalizeDirectory(directory: String): String {
        return directory.trimEnd('/').ifEmpty { "/" }
    }

    private fun toTildePath(directory: String, homeDir: String?): String {
        val normalizedHome = homeDir?.let(::normalizeDirectory)
        return if (normalizedHome != null && directory.startsWith(normalizedHome)) {
            val suffix = directory.removePrefix(normalizedHome)
            if (suffix.isEmpty()) "~" else "~$suffix"
        } else {
            directory
        }
    }

    private fun displayNameFromDirectory(directory: String): String {
        return if (directory == "/") "/" else directory.substringAfterLast('/').ifEmpty { directory }
    }

    override fun onCleared() {
        super.onCleared()
        _undoState.close()
    }
}

internal data class SessionListEmptyState(
    val hasAnySessions: Boolean,
    val isFilteredEmpty: Boolean,
)

internal fun computeSessionListEmptyState(
    rootSessionCount: Int,
    visibleGroupCount: Int,
): SessionListEmptyState {
    val hasAny = rootSessionCount > 0
    return SessionListEmptyState(
        hasAnySessions = hasAny,
        isFilteredEmpty = hasAny && visibleGroupCount == 0,
    )
}

internal fun partitionSubagentsByActivity(subagents: List<SessionItem>): SubagentRow {
    if (subagents.isEmpty()) return SubagentRow.EMPTY
    val running = ArrayList<SessionItem>(subagents.size)
    val historical = ArrayList<SessionItem>(subagents.size)
    for (item in subagents) {
        when (item.status) {
            is SessionStatus.Busy, is SessionStatus.Retry -> running += item
            is SessionStatus.Idle -> historical += item
        }
    }
    return SubagentRow(running = running, historical = historical)
}

internal fun buildActiveConversations(
    rootSessions: List<Session>,
    childSessionsByParent: Map<String, List<Session>> = emptyMap(),
    statuses: Map<String, SessionStatus>,
    pendingQuestions: Map<String, List<SseEvent.QuestionAsked>>,
    pendingPermissions: Map<String, List<SseEvent.PermissionAsked>>,
    unreadSessionIds: Set<String>,
): List<ActiveConversationItem> {
    fun normalizeDir(directory: String): String = directory.trimEnd('/').ifEmpty { "/" }
    fun displayName(directory: String): String = if (directory == "/") "/" else directory.substringAfterLast('/').ifEmpty { directory }

    return rootSessions
        .asSequence()
        .filter { !it.isArchived }
        .mapNotNull { session ->
            val questionCount = pendingQuestions[session.id]?.size ?: 0
            val permissionCount = pendingPermissions[session.id]?.size ?: 0
            val status = statuses[session.id] ?: SessionStatus.Idle
            val childStatuses = childSessionsByParent[session.id]
                .orEmpty()
                .map { child -> statuses[child.id] ?: SessionStatus.Idle }

            val (conversationStatus, pendingCount) = when {
                session.id in unreadSessionIds -> ConversationStatus.UNREAD to 0
                questionCount > 0 -> ConversationStatus.AWAITING_QUESTION to questionCount
                permissionCount > 0 -> ConversationStatus.AWAITING_PERMISSION to permissionCount
                status is SessionStatus.Busy || childStatuses.any { it is SessionStatus.Busy } -> ConversationStatus.BUSY to 0
                status is SessionStatus.Retry || childStatuses.any { it is SessionStatus.Retry } -> ConversationStatus.RETRY to 0
                else -> return@mapNotNull null
            }

            ActiveConversationItem(
                sessionId = session.id,
                directory = session.directory,
                title = session.title,
                projectName = displayName(normalizeDir(session.directory)),
                status = conversationStatus,
                pendingCount = pendingCount,
                updatedAt = session.time.updated,
            )
        }
        .sortedWith(
            // Priority follows ConversationStatus declaration order:
            // UNREAD < AWAITING_QUESTION < AWAITING_PERMISSION < BUSY < RETRY.
            // Unread/awaiting items demand user attention and outrank background
            // busy/retry progress. Within the same priority, newer activity wins.
            compareBy<ActiveConversationItem> { it.status.ordinal }
                .thenByDescending { it.updatedAt }
        )
        .toList()
}
