package dev.wuxie233.codecarry.ui.screens.codex

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wuxie233.codecarry.data.codex.CodexClientConnectionState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOn
import dev.wuxie233.codecarry.data.codex.CodexRpcException
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

import dev.wuxie233.codecarry.data.preferences.SessionListViewMode
import dev.wuxie233.codecarry.data.codex.CodexDirectoryListing

enum class CodexThreadFilter { ALL, RUNNING, PENDING, FAILED }

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
    val isLoadingArchived: Boolean = false,
    val error: String? = null,
) {
    val topology: List<CodexThreadNode> by lazy {
        buildCodexThreadTopology(if (showArchived) archivedThreads else activeThreads)
    }

    val visibleRoots: List<CodexThreadNode> by lazy {
        filterCodexThreadTopology(topology, searchQuery, filter, pendingRequestCounts)
    }

    val activityRoots: List<CodexThreadNode> by lazy {
        (if (showArchived) copy(showArchived = false).visibleRoots else visibleRoots).filter { root ->
            root.runningCount > 0 || root.failedCount > 0 || root.members.any { pendingRequestCounts.getOrDefault(it.id, 0) > 0 }
        }
    }

    val activityThreads: List<CodexThread> get() = activityRoots.map { it.thread }

    val projects: List<CodexThreadProject> by lazy {
        buildCodexTopologyProjects(visibleRoots, projectPreferences, showHiddenProjects, searchQuery.isNotBlank())
    }

    val hasListConstraints: Boolean
        get() = filter != CodexThreadFilter.ALL || searchQuery.isNotBlank()

    val visibleThreads: List<CodexThread> get() = visibleRoots.flatMap { it.members }

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
    private var connectionStateJob: Job? = null
    private var observedEventState: CodexEventState? = null
    private var refreshJob: Job? = null
    private var archiveJob: Job? = null
    private var calibrationJob: Job? = null
    private var archivesLoaded = false
    private var stateDbSupported = true
    private val connectionMutex = Mutex()

    init {
        viewModelScope.launch {
            connectionManager.connections.map { it[serverId]?.connectionId }.distinctUntilChanged().collect { connectionId ->
                val observed = connection
                if (observed != null && connectionId != null && !connectionManager.isCurrent(observed)) {
                    refresh()
                }
            }
        }
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
                val generation = acquired.connection.client.currentConnectionGeneration()
                fun ensureCurrent() {
                    check(connectionManager.isCurrent(acquired.connection) &&
                        acquired.connection.client.currentConnectionGeneration() == generation) {
                        "Codex connection changed while loading threads; retry the refresh"
                    }
                }
                var pageBaseline = acquired.connection.events.value
                _uiState.update { it.copy(serverName = server.displayName) }
                // Publish each database page immediately. A partial or database-only catalog
                // must never infer deletion from absence.
                loadAllCodexThreads(onPage = { page ->
                    ensureCurrent()
                    acquired.connection.reducer.mergeThreadPage(page.threads, false, pageBaseline)
                    _uiState.update { it.copy(isLoading = false) }
                }) { cursor ->
                    ensureCurrent()
                    pageBaseline = acquired.connection.events.value
                    loadCatalogPage(acquired.connection, cursor, archived = false)
                }
                ensureCurrent()
                _uiState.update { it.copy(isLoading = false) }
                if (_uiState.value.showArchived) loadArchives()
                scheduleCalibration(acquired.connection, generation)

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
        buildCodexThreadTopology(_uiState.value.activeThreads)
            .filter { !it.orphan && it.thread.cwd.orEmpty() == directory }
            .flatMap { it.members }.forEach {
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

    fun showArchived(show: Boolean) {
        _uiState.update { it.copy(showArchived = show) }
        if (show && !archivesLoaded) loadArchives()
    }

    private suspend fun loadCatalogPage(
        connected: CodexServerConnection,
        cursor: String?,
        archived: Boolean,
        databaseOnly: Boolean = stateDbSupported,
    ): CodexThreadListPage = try {
        connected.client.listThreads(
            cursor = cursor, archived = archived, limit = 200,
            modelProviders = emptyList(), sortKey = "created_at", sortDirection = "desc",
            useStateDbOnly = databaseOnly,
        )
    } catch (error: CodexRpcException) {
        if (!databaseOnly || !codexStateDbUnsupported(error)) throw error
        stateDbSupported = false
        loadCatalogPage(connected, cursor, archived, databaseOnly = false)
    }

    private fun loadArchives() {
        if (archiveJob?.isActive == true) return
        archiveJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingArchived = true) }
            try {
                val connected = requireConnection()
                connected.client.connect()
                val generation = connected.client.currentConnectionGeneration()
                var baseline = connected.events.value
                loadAllCodexThreads(onPage = { page ->
                    check(connectionManager.isCurrent(connected) && connected.client.currentConnectionGeneration() == generation)
                    connected.reducer.mergeThreadPage(page.threads, true, baseline)
                    _uiState.update { it.copy(isLoadingArchived = false) }
                }) { cursor ->
                    check(connectionManager.isCurrent(connected) && connected.client.currentConnectionGeneration() == generation)
                    baseline = connected.events.value
                    loadCatalogPage(connected, cursor, archived = true)
                }
                archivesLoaded = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showError(error)
            } finally {
                _uiState.update { it.copy(isLoadingArchived = false) }
            }
        }
    }

    private fun scheduleCalibration(connected: CodexServerConnection, generation: Long) {
        if (calibrationJob?.isActive == true) return
        calibrationJob = viewModelScope.launch {
            // Keep filesystem repair scans away from first paint and initial chat opening.
            delay(30_000)
            if (!connectionManager.isCurrent(connected) || connected.client.currentConnectionGeneration() != generation) return@launch
            if (!CodexCatalogCalibration.claim(connected, System.nanoTime())) return@launch
            try {
                fun ensureCurrent() {
                    check(connectionManager.isCurrent(connected) && connected.client.currentConnectionGeneration() == generation)
                }
                val baseline = connected.events.value
                val active = loadAllCodexThreads { cursor ->
                    ensureCurrent()
                    loadCatalogPage(connected, cursor, archived = false, databaseOnly = false)
                }
                val archived = loadAllCodexThreads { cursor ->
                    ensureCurrent()
                    loadCatalogPage(connected, cursor, archived = true, databaseOnly = false)
                }
                ensureCurrent()
                connected.reducer.reconcileThreads(active, archived, baseline)
                archivesLoaded = true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // Fast catalog remains usable; a later refresh retries low-frequency repair.
            }
        }
    }

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
        connectionStateJob?.cancel()
        connectionStateJob = viewModelScope.launch {
            var seenConnected = false
            connected.state.collect { state ->
                if (state is CodexClientConnectionState.Connected) {
                    if (seenConnected) {
                        archivesLoaded = false
                        archiveJob?.cancel()
                        calibrationJob?.cancel()
                    }
                    if (seenConnected || refreshJob?.isActive != true) refresh()
                    seenConnected = true
                }
            }
        }
        eventsJob = viewModelScope.launch {
            combine(connected.events, connected.pendingRequests) { events, requests ->
                events.toCodexCatalogState() to codexPendingRequestCounts(requests)
            }.flowOn(Dispatchers.Default).distinctUntilChanged().collect { (eventState, pendingCounts) ->
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
        connectionStateJob?.cancel()
        connectionStateJob = null
        observedEventState = null
        // Initial acquire may itself belong to the archive job.
        if (connection != null) archiveJob?.cancel()
        calibrationJob?.cancel()
        archivesLoaded = false
        stateDbSupported = true
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
        archiveJob?.cancel()
        calibrationJob?.cancel()
        eventsJob?.cancel()
        lease?.close()
        lease = null
        connection = null
        connectionStateJob?.cancel()
        super.onCleared()
    }
}

internal suspend fun loadAllCodexThreads(
    onPage: suspend (CodexThreadListPage) -> Unit = {},
    loadPage: suspend (cursor: String?) -> CodexThreadListPage,
): List<CodexThread> {
    val threadsById = linkedMapOf<String, CodexThread>()
    val seenCursors = mutableSetOf<String>()
    var cursor: String? = null
    while (true) {
        val page = loadPage(cursor)
        onPage(page)
        page.threads.forEach { thread -> threadsById[thread.id] = thread }
        val nextCursor = page.nextCursor?.takeIf(String::isNotBlank) ?: break
        check(seenCursors.add(nextCursor)) { "Codex thread pagination repeated a cursor; retry the refresh" }
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

    // A connection reset is not a server-side deletion or unarchive.
    val comparablePrevious = previous?.takeIf { it.resetGeneration == current.resetGeneration }
    val deletedIds = comparablePrevious?.threads?.keys.orEmpty() - current.threads.keys
    deletedIds.forEach { threadId ->
        active.remove(threadId)
        archived.remove(threadId)
    }

    current.threads.forEach { (threadId, thread) ->
        if (!thread.hasMetadata) {
            return@forEach
        }
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
    val unarchivedIds = comparablePrevious?.archivedThreadIds.orEmpty() - current.archivedThreadIds
    unarchivedIds.forEach { threadId ->
        archived.remove(threadId)?.let { thread -> active[threadId] = thread }
    }

    return copy(
        activeThreads = active.values.toList(),
        archivedThreads = archived.values.toList(),
        error = error,
    )
}

/** Strip token-bearing history before StateFlow equality and topology planning. */
internal fun CodexEventState.toCodexCatalogState(): CodexEventState = CodexEventState(
    resetGeneration = resetGeneration,
    archivedThreadIds = archivedThreadIds,
    threads = threads.mapValues { (_, thread) ->
        thread.copy(
            turns = thread.turns.lastOrNull()?.let { last ->
                listOf(dev.wuxie233.codecarry.data.codex.CodexTurn(id = last.id, status = last.status))
            }.orEmpty(),
            raw = JsonObject(emptyMap()),
            extra = JsonObject(emptyMap()),
        )
    },
)

internal fun codexStateDbUnsupported(error: CodexRpcException): Boolean =
    error.code == -32602L && (error.message.contains("useStateDbOnly", ignoreCase = true) ||
        error.message.contains("use_state_db_only", ignoreCase = true))

/** Weak connection keys avoid retaining accounts after their manager entry is released. */
private object CodexCatalogCalibration {
    private val lastAttempt = java.util.WeakHashMap<CodexServerConnection, Long>()
    @Synchronized fun claim(connection: CodexServerConnection, now: Long): Boolean {
        val previous = lastAttempt[connection]
        if (previous != null && now - previous < 15L * 60 * 1_000_000_000) return false
        lastAttempt[connection] = now
        return true
    }
}
