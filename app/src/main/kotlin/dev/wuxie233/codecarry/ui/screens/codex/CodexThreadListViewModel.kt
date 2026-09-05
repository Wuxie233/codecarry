package dev.wuxie233.codecarry.ui.screens.codex

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wuxie233.codecarry.data.codex.CodexConnectionLease
import dev.wuxie233.codecarry.data.codex.CodexConnectionManager
import dev.wuxie233.codecarry.data.codex.CodexEventState
import dev.wuxie233.codecarry.data.codex.CodexServerRequest
import dev.wuxie233.codecarry.data.codex.CodexServerConnection
import dev.wuxie233.codecarry.data.codex.CodexThread
import dev.wuxie233.codecarry.data.codex.CodexThreadListPage
import dev.wuxie233.codecarry.data.repository.ServerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

import dev.wuxie233.codecarry.data.preferences.SessionListViewMode
import dev.wuxie233.codecarry.data.codex.CodexDirectoryListing

enum class CodexThreadFilter { ALL, RUNNING, PENDING }

data class CodexThreadListUiState(
    val serverName: String = "Codex",
    val projectPreferences: CodexProjectPreferences = CodexProjectPreferences(),
    val showHiddenProjects: Boolean = false,
    val activeThreads: List<CodexThread> = emptyList(),
    val archivedThreads: List<CodexThread> = emptyList(),
    val showArchived: Boolean = false,
    val searchQuery: String = "",
    val filter: CodexThreadFilter = CodexThreadFilter.ALL,
    val pendingRequestCounts: Map<String, Int> = emptyMap(),
    val isLoading: Boolean = true,
    val error: String? = null,
) {
    val activityThreads: List<CodexThread>
        get() = copy(showArchived = false).visibleThreads.filter {
            it.status.type == "active" || it.status.type == "systemError" || pendingRequestCounts.getOrDefault(it.id, 0) > 0
        }

    val projects: List<CodexThreadProject>
        get() = buildCodexThreadProjects(visibleThreads, projectPreferences, showHiddenProjects, searchQuery.isNotBlank())

    val hasListConstraints: Boolean
        get() = filter != CodexThreadFilter.ALL || searchQuery.isNotBlank()

    val visibleThreads: List<CodexThread>
        get() {
            val source = if (showArchived) archivedThreads else activeThreads
            val query = searchQuery.trim()
            return source.filter { thread ->
                val matchesFilter = when (filter) {
                    CodexThreadFilter.ALL -> true
                    CodexThreadFilter.RUNNING -> thread.status.type == "active"
                    CodexThreadFilter.PENDING -> pendingRequestCounts.getOrDefault(thread.id, 0) > 0
                }
                matchesFilter && (query.isEmpty() ||
                    thread.name.orEmpty().contains(query, ignoreCase = true) ||
                    thread.preview.contains(query, ignoreCase = true) ||
                    thread.cwd.orEmpty().contains(query, ignoreCase = true))
            }.sortedByDescending { it.recencyAt ?: it.updatedAt ?: it.createdAt ?: 0L }
        }

    val recentDirectories: List<String>
        get() = (activeThreads + archivedThreads)
            .sortedByDescending { it.recencyAt ?: it.updatedAt ?: it.createdAt ?: 0L }
            .mapNotNull { it.cwd?.let(::codexThreadWorkingDirectory) }
            .distinct()
            .take(8)
}

/** Empty means the daemon default; whitespace in a filesystem path is significant. */
internal fun codexThreadWorkingDirectory(directory: String): String? = directory.takeIf(String::isNotEmpty)

internal fun codexPendingRequestCounts(requests: List<CodexServerRequest>): Map<String, Int> =
    requests.mapNotNull { (it.params["threadId"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
        .groupingBy { it }.eachCount()

@HiltViewModel
class CodexThreadListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverRepository: ServerRepository,
    private val connectionManager: CodexConnectionManager,
    private val projectPreferencesRepository: CodexProjectPreferencesRepository,
) : ViewModel() {
    val serverId: String = decodeCodexRouteArg(savedStateHandle["serverId"])
    private val _uiState = MutableStateFlow(CodexThreadListUiState())
    val uiState: StateFlow<CodexThreadListUiState> = _uiState.asStateFlow()
    private val _openThread = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openThread: SharedFlow<String> = _openThread.asSharedFlow()
    private var lease: CodexConnectionLease? = null
    private var connection: CodexServerConnection? = null
    private var eventsJob: Job? = null
    private var observedEventState: CodexEventState? = null
    private var refreshJob: Job? = null
    private val connectionMutex = Mutex()

    init {
        viewModelScope.launch {
            projectPreferencesRepository.observe(serverId).collect { preferences ->
                _uiState.update { it.copy(projectPreferences = preferences) }
            }
        }
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val server = serverRepository.getServer(serverId) ?: error("Codex server is no longer configured")
                val acquired = acquireCurrentConnection(server)
                acquired.connection.client.connect()
                val baseline = acquired.connection.events.value
                val active = loadAllCodexThreads { cursor ->
                    acquired.connection.client.listThreads(
                        cursor = cursor,
                        archived = false,
                        limit = 200,
                        modelProviders = emptyList(),
                        sortKey = "recency_at",
                        sortDirection = "desc",
                    )
                }
                val archived = loadAllCodexThreads { cursor ->
                    acquired.connection.client.listThreads(
                        cursor = cursor,
                        archived = true,
                        limit = 200,
                        modelProviders = emptyList(),
                        sortKey = "recency_at",
                        sortDirection = "desc",
                    )
                }
                acquired.connection.reducer.reconcileThreads(active, archived, baseline)
                val eventState = acquired.connection.events.value
                observedEventState = eventState
                _uiState.update {
                    it.copy(
                        serverName = server.displayName,
                        activeThreads = eventState.threads.values.filter { thread ->
                            thread.id !in eventState.archivedThreadIds
                        },
                        archivedThreads = eventState.threads.values.filter { thread ->
                            thread.id in eventState.archivedThreadIds
                        },
                        isLoading = false,
                        error = null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(isLoading = false, error = error.message ?: "Failed to load Codex threads")
                }
            }
        }
    }

    fun setViewMode(mode: SessionListViewMode) = viewModelScope.launch {
        projectPreferencesRepository.setViewMode(serverId, mode)
    }

    fun toggleProjectCollapsed(directory: String) = viewModelScope.launch {
        projectPreferencesRepository.toggle(serverId, "collapsed", directory)
    }

    fun toggleProjectPinned(directory: String) = viewModelScope.launch {
        projectPreferencesRepository.toggle(serverId, "pinned", directory)
    }

    fun toggleProjectHidden(directory: String) = viewModelScope.launch {
        projectPreferencesRepository.toggle(serverId, "hidden", directory)
    }

    fun toggleShowHiddenProjects() = _uiState.update { it.copy(showHiddenProjects = !it.showHiddenProjects) }

    fun archiveProject(directory: String) = mutate { connected ->
        _uiState.value.activeThreads.filter { it.cwd.orEmpty() == directory }.forEach {
            connected.client.archiveThread(it.id)
        }
    }

    suspend fun defaultDirectory(preferred: String?): String? {
        val client = requireConnection().client
        client.connect()
        return client.defaultDirectory(preferred)
    }

    suspend fun readDirectory(path: String): CodexDirectoryListing = requireConnection().client.readDirectory(path)

    fun setSearchQuery(query: String) = _uiState.update { it.copy(searchQuery = query) }

    fun setFilter(filter: CodexThreadFilter) = _uiState.update { it.copy(filter = filter) }

    fun showArchived(show: Boolean) = _uiState.update { it.copy(showArchived = show) }

    fun createThread(cwd: String) {
        viewModelScope.launch {
            runCatching {
                val connected = requireConnection()
                val created = connected.client.startThread(cwd = codexThreadWorkingDirectory(cwd))
                connected.reducer.upsertThread(created.thread)
                _openThread.emit(created.thread.id)
                refresh()
            }.onFailure(::showError)
        }
    }

    fun forkThread(threadId: String) {
        viewModelScope.launch {
            runCatching {
                val connected = requireConnection()
                val fork = connected.client.forkThread(threadId, excludeTurns = true)
                connected.reducer.upsertThread(fork.thread)
                _openThread.emit(fork.thread.id)
                refresh()
            }.onFailure(::showError)
        }
    }

    fun renameThread(threadId: String, name: String) = mutate {
        it.client.setThreadName(threadId, name.trim())
    }

    fun archiveThread(threadId: String) = mutate { it.client.archiveThread(threadId) }

    fun unarchiveThread(threadId: String) = mutate { it.client.unarchiveThread(threadId) }

    fun deleteThread(threadId: String) = mutate { it.client.deleteThread(threadId) }

    private fun mutate(action: suspend (CodexServerConnection) -> Unit) {
        viewModelScope.launch {
            runCatching { action(requireConnection()); refresh() }.onFailure(::showError)
        }
    }

    private fun observeEvents(connected: CodexServerConnection) {
        eventsJob?.cancel()
        eventsJob = viewModelScope.launch {
            combine(connected.events, connected.pendingRequests) { events, requests ->
                events to codexPendingRequestCounts(requests)
            }.collect { (eventState, pendingCounts) ->
                val previous = observedEventState
                observedEventState = eventState
                _uiState.update {
                    it.applyCodexEventState(previous, eventState).copy(pendingRequestCounts = pendingCounts)
                }
            }
        }
    }

    private suspend fun requireConnection(): CodexServerConnection {
        val server = serverRepository.getServer(serverId) ?: error("Codex server is no longer configured")
        return acquireCurrentConnection(server).connection
    }

    private suspend fun acquireCurrentConnection(server: dev.wuxie233.codecarry.domain.model.ServerConfig): CodexConnectionLease {
        return connectionMutex.withLock {
            val currentLease = lease
            if (currentLease != null && connectionManager.isCurrent(currentLease.connection)) {
                return@withLock currentLease
            }
            resetConnectionLocked()
            val acquired = connectionManager.acquire(server)
            lease = acquired
            connection = acquired.connection
            observeEvents(acquired.connection)
            acquired
        }
    }

    private fun resetConnectionLocked() {
        eventsJob?.cancel()
        eventsJob = null
        observedEventState = null
        _uiState.update { it.copy(pendingRequestCounts = emptyMap()) }
        lease?.close()
        lease = null
        connection = null
    }

    private fun showError(error: Throwable) {
        _uiState.update { it.copy(error = error.message ?: "Codex operation failed") }
    }

    override fun onCleared() {
        refreshJob?.cancel()
        eventsJob?.cancel()
        lease?.close()
        lease = null
        connection = null
        super.onCleared()
    }
}

internal suspend fun loadAllCodexThreads(
    loadPage: suspend (cursor: String?) -> CodexThreadListPage,
): List<CodexThread> {
    val threadsById = linkedMapOf<String, CodexThread>()
    val seenCursors = mutableSetOf<String>()
    var cursor: String? = null
    while (true) {
        val page = loadPage(cursor)
        page.threads.forEach { thread -> threadsById[thread.id] = thread }
        val nextCursor = page.nextCursor?.takeIf(String::isNotBlank) ?: break
        if (!seenCursors.add(nextCursor)) break
        cursor = nextCursor
    }
    return threadsById.values.toList()
}

internal fun CodexThreadListUiState.applyCodexEventState(
    previous: CodexEventState?,
    current: CodexEventState,
): CodexThreadListUiState {
    val active = activeThreads.associateByTo(linkedMapOf(), CodexThread::id)
    val archived = archivedThreads.associateByTo(linkedMapOf(), CodexThread::id)

    val deletedIds = previous?.threads?.keys.orEmpty() - current.threads.keys
    deletedIds.forEach { threadId ->
        active.remove(threadId)
        archived.remove(threadId)
    }

    current.threads.forEach { (threadId, thread) ->
        when {
            active.containsKey(threadId) -> active[threadId] = thread
            archived.containsKey(threadId) -> archived[threadId] = thread
            threadId in current.archivedThreadIds -> archived[threadId] = thread
            else -> active[threadId] = thread
        }
    }

    current.archivedThreadIds.forEach { threadId ->
        active.remove(threadId)?.let { thread -> archived[threadId] = thread }
    }
    val unarchivedIds = previous?.archivedThreadIds.orEmpty() - current.archivedThreadIds
    unarchivedIds.forEach { threadId ->
        archived.remove(threadId)?.let { thread -> active[threadId] = thread }
    }

    return copy(
        activeThreads = active.values.toList(),
        archivedThreads = archived.values.toList(),
        error = error,
    )
}
