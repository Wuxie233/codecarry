package dev.wuxie233.codecarry.data.codex

import dev.wuxie233.codecarry.domain.model.ServerConfig
import dev.wuxie233.codecarry.data.repository.SettingsRepository
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private const val DEFAULT_IDLE_DISCONNECT_MILLIS = 30_000L
private const val DEFAULT_RECONNECT_INITIAL_MILLIS = 1_000L
private const val DEFAULT_RECONNECT_MAX_MILLIS = 30_000L

class CodexServerConnection internal constructor(
    val serverId: String,
    val client: CodexAppServerClient,
    val state: StateFlow<CodexClientConnectionState>,
    val pendingRequests: StateFlow<List<CodexServerRequest>>,
    val events: StateFlow<CodexEventState>,
    val reducer: CodexEventReducer,
    private val resolveRequest: (JsonPrimitive) -> Unit,
    private val validateRequest: (CodexServerRequest) -> Unit,
    private val validateApproval: (CodexApprovalRequest) -> Unit,
    private val validateUserInput: (CodexToolUserInputRequest) -> Unit,
) {
    suspend fun reply(request: CodexServerRequest, result: kotlinx.serialization.json.JsonElement) {
        validateRequest(request)
        client.reply(request, result)
        resolveRequest(request.id)
    }

    suspend fun replyError(
        request: CodexServerRequest,
        code: Long,
        message: String,
        data: kotlinx.serialization.json.JsonElement? = null,
    ) {
        validateRequest(request)
        client.replyError(request, code, message, data)
        resolveRequest(request.id)
    }

    suspend fun replyApproval(request: CodexApprovalRequest, decision: String) {
        validateApproval(request)
        client.replyApproval(request, decision)
        resolveRequest(request.requestId)
    }

    suspend fun replyPermissionApproval(
        request: CodexApprovalRequest,
        decision: String,
        grant: CodexPermissionGrant,
    ) {
        validateApproval(request)
        client.replyPermissionApproval(request, decision, grant)
        resolveRequest(request.requestId)
    }

    suspend fun replyUserInput(
        request: CodexToolUserInputRequest,
        answers: Map<String, List<String>>,
    ) {
        validateUserInput(request)
        client.replyUserInput(request, answers)
        resolveRequest(request.requestId)
    }
}

data class CodexManagedConnection(
    val connectionId: Long,
    val client: CodexAppServerClient,
    val state: CodexClientConnectionState,
    val pendingRequests: List<CodexServerRequest>,
)

data class CodexThreadKey(
    val serverId: String,
    val threadId: String,
)

data class ScopedCodexNotification(
    val serverId: String,
    val connectionId: Long,
    val notification: CodexNotification,
)

class CodexConnectionLease internal constructor(
    val connection: CodexServerConnection,
    private val releaseAction: () -> Unit,
) : Closeable {
    private var released = false

    override fun close() {
        if (released) return
        released = true
        releaseAction()
    }
}

@Singleton
class CodexConnectionManager {
    private val createClient: (ServerConfig) -> CodexAppServerClient
    private val scope: CoroutineScope
    private val ownsScope: Boolean
    private val idleDisconnectMillis: Long
    private val reconnectInitialMillis: Long
    private val reconnectMaxMillis: suspend () -> Long
    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()
    private val connectionIds = AtomicLong()
    private val usageStores = mutableMapOf<String, Pair<ConnectionKey, CodexAccountUsageStore>>()
    private val _accountUsage = MutableStateFlow<Map<String, CodexAccountUsageState>>(emptyMap())
    val accountUsage: StateFlow<Map<String, CodexAccountUsageState>> = _accountUsage.asStateFlow()

    fun refreshUsage(serverId: String) {
        synchronized(lock) {
            val entry = entries[serverId] ?: return
            if (entry.client.connectionState.value !is CodexClientConnectionState.Connected || entry.usageJob?.isActive == true) return
            val generation = entry.client.currentConnectionGeneration()
            val read = entry.usage.beginRead()
            publishUsage(entry)
            entry.usageJob = scope.launch {
                try {
                    val result = withTimeout(15_000) { entry.client.readAccountRateLimits() }
                    synchronized(lock) {
                        if (entries[serverId] === entry && generation == entry.client.currentConnectionGeneration()) {
                            entry.usage.applyRead(read, result, System.currentTimeMillis())
                            publishUsage(entry)
                        }
                    }
                } catch (error: CancellationException) {
                    if (error is kotlinx.coroutines.TimeoutCancellationException) synchronized(lock) {
                        if (entries[serverId] === entry && generation == entry.client.currentConnectionGeneration()) {
                            entry.usage.fail(read, error)
                            publishUsage(entry)
                        }
                    } else throw error
                } catch (error: Throwable) {
                    synchronized(lock) {
                        if (entries[serverId] === entry && generation == entry.client.currentConnectionGeneration()) {
                            entry.usage.fail(read, error)
                            publishUsage(entry)
                        }
                    }
                }
            }
        }
    }

    private fun publishUsage(entry: Entry) {
        _accountUsage.update { it + (entry.connection.serverId to entry.usage.state) }
    }
    private val activeThreadReferences = mutableMapOf<CodexThreadKey, Int>()
    private val _connections = MutableStateFlow<Map<String, CodexManagedConnection>>(emptyMap())
    val connections: StateFlow<Map<String, CodexManagedConnection>> = _connections.asStateFlow()
    private val _notificationEvents = MutableSharedFlow<ScopedCodexNotification>(extraBufferCapacity = 64)
    val notificationEvents: SharedFlow<ScopedCodexNotification> = _notificationEvents.asSharedFlow()
    private val _activeThreads = MutableStateFlow<Set<CodexThreadKey>>(emptySet())
    val activeThreads: StateFlow<Set<CodexThreadKey>> = _activeThreads.asStateFlow()

    @Inject
    constructor(
        factory: CodexAppServerClientFactory,
        settingsRepository: SettingsRepository,
    ) : this(
        createClient = factory::create,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ownsScope = true,
        idleDisconnectMillis = DEFAULT_IDLE_DISCONNECT_MILLIS,
        reconnectInitialMillis = DEFAULT_RECONNECT_INITIAL_MILLIS,
        reconnectMaxMillis = {
            when (settingsRepository.reconnectMode.first()) {
                "aggressive" -> 5_000L
                "conservative" -> 60_000L
                else -> DEFAULT_RECONNECT_MAX_MILLIS
            }
        },
    )

    internal constructor(
        createClient: (ServerConfig) -> CodexAppServerClient,
        scope: CoroutineScope,
        idleDisconnectMillis: Long = DEFAULT_IDLE_DISCONNECT_MILLIS,
        reconnectInitialMillis: Long = DEFAULT_RECONNECT_INITIAL_MILLIS,
        reconnectMaxMillis: Long = DEFAULT_RECONNECT_MAX_MILLIS,
    ) : this(
        createClient = createClient,
        scope = scope,
        ownsScope = false,
        idleDisconnectMillis = idleDisconnectMillis,
        reconnectInitialMillis = reconnectInitialMillis,
        reconnectMaxMillis = { reconnectMaxMillis },
    )

    private constructor(
        createClient: (ServerConfig) -> CodexAppServerClient,
        scope: CoroutineScope,
        ownsScope: Boolean,
        idleDisconnectMillis: Long,
        reconnectInitialMillis: Long,
        reconnectMaxMillis: suspend () -> Long,
    ) {
        this.createClient = createClient
        this.scope = scope
        this.ownsScope = ownsScope
        this.idleDisconnectMillis = idleDisconnectMillis
        this.reconnectInitialMillis = reconnectInitialMillis
        this.reconnectMaxMillis = reconnectMaxMillis
    }

    suspend fun connect(server: ServerConfig): CodexServerConnection {
        val entry = getOrCreate(server)
        synchronized(lock) {
            entry.persistent = true
            entry.idleDisconnectJob?.cancel()
            entry.idleDisconnectJob = null
        }
        try {
            entry.client.connect()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            scheduleReconnect(entry)
            throw error
        }
        return entry.connection
    }

    suspend fun client(serverId: String): CodexAppServerClient {
        val entry = synchronized(lock) { entries[serverId] }
            ?: throw IllegalStateException("Codex server $serverId is not connected")
        entry.client.connect()
        return entry.client
    }

    suspend fun acquire(server: ServerConfig, threadId: String? = null): CodexConnectionLease {
        val entry = synchronized(lock) {
            getOrCreateLocked(server).also { current ->
                current.references += 1
                threadId?.let { id ->
                    current.recentThreadJobs.remove(id)?.cancel()
                    current.openThreadReferences[id] = current.openThreadReferences.getOrDefault(id, 0) + 1
                }
                current.idleDisconnectJob?.cancel()
                current.idleDisconnectJob = null
            }
        }
        val pendingUnsubscribe = threadId?.let { id ->
            synchronized(lock) { entry.threadUnsubscribeJobs[id] }
        }
        try {
            pendingUnsubscribe?.join()
            entry.client.connect()
        } catch (error: CancellationException) {
            release(entry, threadId)
            throw error
        } catch (error: Throwable) {
            release(entry, threadId)
            throw error
        }
        return CodexConnectionLease(entry.connection) { release(entry, threadId) }
    }

    fun get(serverId: String): CodexServerConnection? = synchronized(lock) {
        entries[serverId]?.connection
    }

    fun isCurrent(connection: CodexServerConnection): Boolean = synchronized(lock) {
        entries[connection.serverId]?.connection === connection
    }

    // A failed optional/shared RPC must not cancel the manager's owning scope.
    private fun <T> sharedRead(block: suspend () -> T): Deferred<Result<T>> =
        scope.async(start = CoroutineStart.LAZY) {
            try {
                Result.success(block())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
        }

    /** Full-history subscription shared by screen entry and reconnect; waiter cancellation never cancels it. */
    suspend fun resumeThread(connection: CodexServerConnection, threadId: String): CodexThreadSession {
        val entry = synchronized(lock) {
            entries[connection.serverId]?.takeIf { it.connection === connection }
                ?: error("Codex server connection is no longer current")
        }
        synchronized(lock) { entry.threadUnsubscribeJobs[threadId] }?.join()
        val task = synchronized(lock) {
            check(entries[connection.serverId] === entry)
            check(entry.client.connectionState.value is CodexClientConnectionState.Connected)
            val generation = entry.client.currentConnectionGeneration()
            val version = entry.threadLifecycleVersions.getOrDefault(threadId, 0L)
            entry.threadSessions[threadId]?.takeIf {
                it.generation == generation && it.lifecycleVersion == version
            }?.let { cached ->
                return cached.session.copy(thread = entry.reducer.state.value.threads[threadId] ?: cached.session.thread)
            }
            entry.threadResumeJobs[threadId]?.takeIf {
                it.isActive && entry.threadResumeIdentities[threadId] == (generation to version)
            } ?: sharedRead {
                entry.reducer.invalidateThreadControlState(threadId)
                val baseline = entry.reducer.state.value.threads[threadId]
                val resumed = entry.client.resumeThread(threadId, excludeTurns = false)
                val payloadBytes = jsonPayloadBytes(resumed.thread.raw)
                synchronized(lock) {
                    check(entries[connection.serverId] === entry &&
                        entry.client.currentConnectionGeneration() == generation &&
                        entry.client.connectionState.value is CodexClientConnectionState.Connected &&
                        entry.threadLifecycleVersions.getOrDefault(threadId, 0L) == version) {
                        "Codex thread resume belongs to an earlier connection or subscription"
                    }
                    entry.reducer.upsertThreadSnapshot(resumed.thread, baseline)
                    // The reducer owns history; the subscription cache keeps only session metadata.
                    val metadata = resumed.copy(
                        thread = resumed.thread.copy(turns = emptyList(), raw = JsonObject(resumed.thread.raw - "turns")),
                        raw = JsonObject(resumed.raw - "thread"),
                    )
                    entry.threadSessions[threadId] = ResumedThread(generation, version, metadata, payloadBytes)
                    reconcileRetainedThread(entry, threadId)
                    resumed.copy(thread = entry.reducer.state.value.threads[threadId] ?: resumed.thread)
                }
            }.also { deferred ->
                entry.threadResumeJobs[threadId] = deferred
                entry.threadResumeIdentities[threadId] = generation to version
                deferred.invokeOnCompletion {
                    synchronized(lock) {
                        if (entry.threadResumeJobs[threadId] === deferred) {
                            entry.threadResumeJobs.remove(threadId)
                            entry.threadResumeIdentities.remove(threadId)
                        }
                    }
                }
                deferred.start()
            }
        }
        return task.await().getOrThrow()
    }

    fun updateThreadSession(
        connection: CodexServerConnection,
        threadId: String,
        update: (CodexThreadSession) -> CodexThreadSession,
    ) = synchronized(lock) {
        val entry = entries[connection.serverId]?.takeIf { it.connection === connection } ?: return@synchronized
        val cached = entry.threadSessions[threadId] ?: return@synchronized
        if (isThreadReady(connection, threadId)) {
            entry.threadSessions[threadId] = cached.copy(session = update(cached.session))
        }
    }

    suspend fun listModels(connection: CodexServerConnection): List<CodexModel> {
        val task = synchronized(lock) {
            val entry = entries[connection.serverId]?.takeIf { it.connection === connection }
                ?: error("Codex server connection is no longer current")
            val generation = entry.client.currentConnectionGeneration()
            check(entry.client.connectionState.value is CodexClientConnectionState.Connected)
            entry.modelCatalog?.takeIf { it.first == generation }?.let { return it.second }
            entry.modelJob?.takeIf { it.isActive && entry.modelJobGeneration == generation } ?: sharedRead {
                val models = mutableListOf<CodexModel>()
                val cursors = mutableSetOf<String>()
                var cursor: String? = null
                do {
                    val page = entry.client.listModels(cursor = cursor, limit = 100)
                    models += page.models
                    cursor = page.nextCursor
                    check(cursor == null || cursors.add(cursor)) { "Codex model catalog repeated a cursor" }
                } while (cursor != null)
                synchronized(lock) {
                    check(entries[connection.serverId] === entry &&
                        entry.client.currentConnectionGeneration() == generation &&
                        entry.client.connectionState.value is CodexClientConnectionState.Connected)
                    models.toList().also { entry.modelCatalog = generation to it }
                }
            }.also { deferred ->
                entry.modelJob = deferred
                entry.modelJobGeneration = generation
                deferred.invokeOnCompletion {
                    synchronized(lock) { if (entry.modelJob === deferred) entry.modelJob = null }
                }
                deferred.start()
            }
        }
        return task.await().getOrThrow()
    }

    fun isThreadReady(connection: CodexServerConnection, threadId: String): Boolean = synchronized(lock) {
        val entry = entries[connection.serverId]?.takeIf { it.connection === connection } ?: return false
        val cached = entry.threadSessions[threadId] ?: return false
        entry.client.connectionState.value is CodexClientConnectionState.Connected &&
            cached.generation == entry.client.currentConnectionGeneration() &&
            cached.lifecycleVersion == entry.threadLifecycleVersions.getOrDefault(threadId, 0L)
    }

    fun disconnect(serverId: String) {
        val entry = synchronized(lock) { entries.remove(serverId) } ?: return
        entry.close()
        _connections.update { current -> current - serverId }
    }

    fun releasePersistent(serverId: String) {
        val removed = synchronized(lock) {
            val entry = entries[serverId] ?: return
            entry.persistent = false
            val retainedThreadIds = entry.retainedThreadIds.toList()
            entry.retainedThreadIds.clear()
            if (entry.references > 0) {
                retainedThreadIds.forEach { threadId ->
                    scheduleThreadUnsubscribeLocked(entry, threadId)
                }
                publish(entry)
                return
            }
            entry.reconnectJob?.cancel()
            entry.reconnectJob = null
            entry.recovering = false
            entries.remove(serverId)
            entry
        }
        removed.close()
        _connections.update { current -> current - serverId }
    }

    fun disconnectAll() {
        val removed = synchronized(lock) {
            entries.values.toList().also { entries.clear() }
        }
        removed.forEach(Entry::close)
        _connections.value = emptyMap()
    }

    fun resolveRequest(serverId: String, requestId: JsonPrimitive) {
        val entry = synchronized(lock) { entries[serverId] } ?: return
        resolveRequest(entry, requestId)
    }

    fun releaseRetainedThread(serverId: String, threadId: String) {
        val entry = synchronized(lock) { entries[serverId] } ?: return
        releaseRetainedThread(entry, threadId)
    }

    fun retainProvisionalTurn(serverId: String, threadId: String) {
        synchronized(lock) {
            val entry = entries[serverId] ?: return
            entry.provisionalThreadIds += threadId
            entry.retainedThreadIds += threadId
            entry.idleDisconnectJob?.cancel()
            entry.idleDisconnectJob = null
        }
    }

    fun releaseProvisionalTurn(serverId: String, threadId: String) {
        val entry = synchronized(lock) { entries[serverId] } ?: return
        synchronized(lock) {
            if (entries[serverId] !== entry) return
            entry.provisionalThreadIds -= threadId
        }
        reconcileRetainedThread(entry, threadId)
    }

    fun activateThread(key: CodexThreadKey): Closeable {
        synchronized(lock) {
            activeThreadReferences[key] = activeThreadReferences.getOrDefault(key, 0) + 1
            _activeThreads.value = activeThreadReferences.keys.toSet()
        }
        var closed = false
        return Closeable {
            synchronized(lock) {
                if (closed) return@synchronized
                closed = true
                val remaining = activeThreadReferences.getOrDefault(key, 0) - 1
                if (remaining > 0) activeThreadReferences[key] = remaining
                else activeThreadReferences.remove(key)
                _activeThreads.value = activeThreadReferences.keys.toSet()
            }
        }
    }

    private fun release(entry: Entry, threadId: String? = null) {
        synchronized(lock) {
            val serverId = entry.connection.serverId
            if (entries[serverId] !== entry) return
            entry.references = (entry.references - 1).coerceAtLeast(0)
            threadId?.let { id ->
                val remaining = entry.openThreadReferences.getOrDefault(id, 0) - 1
                if (remaining > 0) entry.openThreadReferences[id] = remaining
                else {
                    entry.openThreadReferences.remove(id)
                    retainRecentThreadLocked(entry, id)
                }
            }
            scheduleIdleDisconnectLocked(entry)
        }
    }

    private fun scheduleIdleDisconnectLocked(entry: Entry) {
        val serverId = entry.connection.serverId
        if (entry.hasOwnersLocked() || entry.idleDisconnectJob != null) return
        entry.idleDisconnectJob = scope.launch {
            if (idleDisconnectMillis > 0) delay(idleDisconnectMillis)
            synchronized(lock) {
                val current = entries[serverId]
                if (current === entry && !current.hasOwnersLocked()) {
                    entries.remove(serverId)
                    current.close()
                    _connections.update { connections -> connections - serverId }
                }
            }
        }
    }

    private fun getOrCreate(server: ServerConfig): Entry = synchronized(lock) {
        getOrCreateLocked(server)
    }

    private fun getOrCreateLocked(server: ServerConfig): Entry {
        val key = ConnectionKey(server.url.trim(), server.token)
        val existing = entries[server.id]
        if (existing != null && existing.key == key) return existing
        existing?.close()

        val usage = usageStores[server.id]?.takeIf { it.first == key }?.second ?: CodexAccountUsageStore()
        usageStores[server.id] = key to usage
        val client = createClient(server)
        val connectionId = connectionIds.incrementAndGet()
        val pending = MutableStateFlow<List<CodexServerRequest>>(emptyList())
        val state = MutableStateFlow<CodexClientConnectionState>(client.connectionState.value)
        val reducer = CodexEventReducer()
        lateinit var entry: Entry
        fun resolve(id: JsonPrimitive) = resolveRequest(entry, id)
        fun validate(request: CodexServerRequest) = validateRequest(entry, request)
        fun validate(approval: CodexApprovalRequest) = validateApproval(entry, approval)
        fun validate(userInput: CodexToolUserInputRequest) = validateUserInput(entry, userInput)
        val connection = CodexServerConnection(
            serverId = server.id,
            client = client,
            state = state.asStateFlow(),
            pendingRequests = pending.asStateFlow(),
            events = reducer.state,
            reducer = reducer,
            resolveRequest = ::resolve,
            validateRequest = ::validate,
            validateApproval = ::validate,
            validateUserInput = ::validate,
        )
        val inboundJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.inboundEvents.collect { event ->
                if (event.connectionGeneration != client.currentConnectionGeneration()) return@collect
                when (event) {
                    is CodexInboundEvent.ServerRequest -> {
                        val request = event.value
                        synchronized(lock) {
                            if (entries[entry.connection.serverId] !== entry) return@collect
                            pending.value = pending.value.filterNot { existingRequest ->
                                existingRequest.id.requestKey() == request.id.requestKey()
                            } + request
                        }
                        request.threadId()?.let { threadId -> reconcileRetainedThread(entry, threadId) }
                        publish(entry)
                    }
                    is CodexInboundEvent.Notification -> {
                        val notification = event.value
                        synchronized(lock) {
                            if (entries[server.id] !== entry) return@collect
                            when (notification.method) {
                                "account/rateLimits/updated" -> {
                                    entry.usage.applyPush(notification.params, System.currentTimeMillis())
                                    publishUsage(entry)
                                }
                                "account/updated" -> {
                                    entry.usageJob?.cancel()
                                    entry.usageJob = null
                                    entry.usage.invalidate(clear = true)
                                    publishUsage(entry)
                                    refreshUsage(server.id)
                                }
                            }
                        }
                        reducer.process(notification)
                        when (notification.method) {
                            "thread/deleted" -> notification.threadId?.let { threadId ->
                                synchronized(lock) {
                                    entry.threadSessions.remove(threadId)
                                    entry.threadResumeJobs.remove(threadId)?.cancel()
                                    entry.recentThreadJobs.remove(threadId)?.cancel()
                                }
                                releaseRetainedThread(entry, threadId)
                            }
                            "serverRequest/resolved" -> {
                                (notification.params["requestId"] as? JsonPrimitive)?.let(::resolve)
                            }
                        }
                        val threadId = notification.threadId
                        if (
                            notification.method != "thread/deleted" &&
                            !threadId.isNullOrBlank() &&
                            !notification.turnId.isNullOrBlank()
                        ) {
                            reconcileRetainedThread(entry, threadId)
                        }
                        _notificationEvents.emit(
                            ScopedCodexNotification(
                                serverId = server.id,
                                connectionId = connectionId,
                                notification = notification,
                            ),
                        )
                    }
                }
            }
        }
        entry = Entry(
            key = key,
            connectionId = connectionId,
            client = client,
            connection = connection,
            state = state,
            pending = pending,
            reducer = reducer,
            inboundJob = inboundJob,
            publish = ::publish,
            usage = usage,
            usageClosed = { synchronized(lock) { usage.invalidate(); _accountUsage.update { it + (server.id to usage.state) } } },
        )
        entries[server.id] = entry
        publish(entry)
        val stateJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.connectionState.collect { clientState ->
                if (synchronized(lock) { entries[server.id] !== entry }) return@collect
                if (
                    clientState is CodexClientConnectionState.Disconnected ||
                    clientState is CodexClientConnectionState.Failed
                ) {
                    synchronized(lock) {
                        entry.threadResumeJobs.values.toList().forEach { it.cancel() }
                        entry.threadResumeJobs.clear()
                        entry.threadSessions.clear()
                        entry.modelJob?.cancel()
                        entry.modelJob = null
                        entry.modelCatalog = null
                        entry.usageJob?.cancel()
                        entry.usageJob = null
                        entry.usage.invalidate()
                        publishUsage(entry)
                    }
                    entry.pending.value = emptyList()
                    entry.state.value = clientState
                    scheduleReconnect(entry)
                } else {
                    entry.state.value = clientState
                }
                if (clientState is CodexClientConnectionState.Connected) refreshUsage(server.id)
                publish(entry)
            }
        }
        entry.stateJob = stateJob
        return entry
    }

    private fun publish(entry: Entry) {
        val registered = synchronized(lock) { entries[entry.connection.serverId] === entry }
        if (!registered) return
        _connections.update { current ->
            current + (
                entry.connection.serverId to CodexManagedConnection(
                    connectionId = entry.connectionId,
                    client = entry.client,
                    state = entry.state.value,
                    pendingRequests = entry.pending.value,
                )
            )
        }
    }

    private fun resolveRequest(entry: Entry, requestId: JsonPrimitive) {
        val key = requestId.requestKey()
        val resolvedThreadId = synchronized(lock) {
            if (entries[entry.connection.serverId] !== entry) return
            val request = entry.pending.value.firstOrNull { pendingRequest ->
                pendingRequest.id.requestKey() == key
            }
            entry.pending.value = entry.pending.value.filterNot { pendingRequest ->
                pendingRequest.id.requestKey() == key
            }
            request?.threadId()
        }
        publish(entry)
        resolvedThreadId?.let { threadId -> reconcileRetainedThread(entry, threadId) }
    }

    private fun validateRequest(entry: Entry, request: CodexServerRequest) {
        synchronized(lock) {
            check(entries[entry.connection.serverId] === entry) {
                "Codex server connection is no longer current"
            }
            val current = entry.pending.value.firstOrNull { pendingRequest ->
                pendingRequest.id.requestKey() == request.id.requestKey()
            }
            check(current === request) { "Codex server request is no longer pending" }
            check(request.connectionGeneration == entry.client.currentConnectionGeneration()) {
                "Codex server request belongs to an earlier connection"
            }
        }
    }

    private fun validateApproval(entry: Entry, approval: CodexApprovalRequest) {
        val current = synchronized(lock) {
            entry.pending.value.firstOrNull { request ->
                request.id.requestKey() == approval.requestId.requestKey()
            }
        } ?: error("Codex server request is no longer pending")
        check(current.approval === approval) { "Codex server request is no longer pending" }
        validateRequest(entry, current)
    }

    private fun validateUserInput(entry: Entry, userInput: CodexToolUserInputRequest) {
        val current = synchronized(lock) {
            entry.pending.value.firstOrNull { request ->
                request.id.requestKey() == userInput.requestId.requestKey()
            }
        } ?: error("Codex server request is no longer pending")
        check(current.userInput === userInput) { "Codex server request is no longer pending" }
        validateRequest(entry, current)
    }

    private fun reconcileRetainedThread(entry: Entry, threadId: String) {
        synchronized(lock) {
            if (entries[entry.connection.serverId] !== entry) return
            val hasAuthoritativeTurn = entry.reducer.state.value.threads[threadId]
                ?.turns
                ?.isNotEmpty() == true
            if (hasAuthoritativeTurn) entry.provisionalThreadIds -= threadId
            val shouldRetain = entry.pending.value.any { request -> request.threadId() == threadId } ||
                threadId in entry.provisionalThreadIds ||
                entry.reducer.state.value.threads[threadId]
                    ?.turns
                    ?.any { turn -> turn.status == "inProgress" } == true
            if (shouldRetain) {
                entry.retainedThreadIds += threadId
                entry.idleDisconnectJob?.cancel()
                entry.idleDisconnectJob = null
            } else {
                entry.retainedThreadIds -= threadId
                scheduleThreadUnsubscribeLocked(entry, threadId)
                scheduleIdleDisconnectLocked(entry)
            }
        }
    }

    private fun releaseRetainedThread(entry: Entry, threadId: String) {
        synchronized(lock) {
            if (entries[entry.connection.serverId] !== entry) return
            entry.retainedThreadIds -= threadId
            scheduleThreadUnsubscribeLocked(entry, threadId)
            scheduleIdleDisconnectLocked(entry)
        }
    }

    private fun retainRecentThreadLocked(entry: Entry, threadId: String) {
        if ((entry.threadSessions[threadId]?.payloadBytes ?: Long.MAX_VALUE) > 16L * 1024 * 1024) {
            scheduleThreadUnsubscribeLocked(entry, threadId)
            return
        }
        entry.recentThreadJobs.remove(threadId)?.cancel()
        entry.recentThreadJobs[threadId] = scope.launch {
            delay(30_000)
            synchronized(lock) {
                entry.recentThreadJobs.remove(threadId)
                scheduleThreadUnsubscribeLocked(entry, threadId)
            }
        }
        // Only three recently closed subscriptions; active and running threads retain their normal ownership.
        while (entry.recentThreadJobs.size > 3 ||
            entry.recentThreadJobs.keys.sumOf { entry.threadSessions[it]?.payloadBytes ?: 0L } > 32L * 1024 * 1024) {
            val oldest = entry.recentThreadJobs.keys.first()
            entry.recentThreadJobs.remove(oldest)?.cancel()
            scheduleThreadUnsubscribeLocked(entry, oldest)
        }
    }

    private fun scheduleThreadUnsubscribeLocked(entry: Entry, threadId: String) {
        if (entry.openThreadReferences.containsKey(threadId) || threadId in entry.retainedThreadIds ||
            threadId in entry.recentThreadJobs) return
        if (entry.threadUnsubscribeJobs[threadId]?.isActive == true) return
        if (entry.client.connectionState.value !is CodexClientConnectionState.Connected) return
        entry.threadSessions.remove(threadId)
        entry.threadResumeJobs.remove(threadId)?.cancel()
        entry.threadLifecycleVersions[threadId] = entry.threadLifecycleVersions.getOrDefault(threadId, 0L) + 1L
        lateinit var unsubscribeJob: Job
        unsubscribeJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                entry.client.unsubscribeThread(threadId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
            } finally {
                synchronized(lock) {
                    if (
                        entries[entry.connection.serverId] === entry &&
                        entry.threadUnsubscribeJobs[threadId] === unsubscribeJob
                    ) {
                        entry.threadUnsubscribeJobs.remove(threadId)
                        scheduleIdleDisconnectLocked(entry)
                    }
                }
            }
        }
        entry.threadUnsubscribeJobs[threadId] = unsubscribeJob
        unsubscribeJob.start()
    }

    private fun scheduleReconnect(entry: Entry) {
        synchronized(lock) {
            if (entries[entry.connection.serverId] !== entry) return
            if (!entry.hasOwnersLocked()) return
            if (entry.reconnectJob?.isActive == true) return
            entry.recovering = true
            entry.reconnectJob = scope.launch {
                var delayMillis = reconnectInitialMillis
                val restoredThreads = mutableMapOf<String, Long>()
                try {
                    while (shouldMaintainConnection(entry)) {
                        if (delayMillis > 0) delay(delayMillis)
                        if (!shouldMaintainConnection(entry)) break

                        if (entry.client.connectionState.value !is CodexClientConnectionState.Connected) {
                            try {
                                entry.client.connect()
                                restoredThreads.clear()
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Throwable) {
                                delayMillis = nextReconnectDelay(delayMillis)
                                continue
                            }
                        }

                        if (
                            restoreOpenThreads(entry, restoredThreads) &&
                            entry.client.connectionState.value is CodexClientConnectionState.Connected
                        ) {
                            if (finishRecovery(entry, restoredThreads)) break
                        }
                        delayMillis = nextReconnectDelay(delayMillis)
                    }
                } finally {
                    var publishFinalState = false
                    val retry = synchronized(lock) {
                        if (entries[entry.connection.serverId] !== entry) return@synchronized false
                        entry.reconnectJob = null
                        shouldReconnectSocketLocked(entry).also { shouldRetry ->
                            if (!shouldRetry && entry.recovering) {
                                entry.recovering = false
                                entry.state.value = entry.client.connectionState.value
                                publishFinalState = true
                            }
                        }
                    }
                    if (retry) scheduleReconnect(entry)
                    else if (publishFinalState) publish(entry)
                }
            }
        }
    }

    private fun finishRecovery(entry: Entry, restoredThreads: Map<String, Long>): Boolean {
        val connectedState = entry.client.connectionState.value as? CodexClientConnectionState.Connected
            ?: return false
        val finished = synchronized(lock) {
            if (entries[entry.connection.serverId] !== entry) return@synchronized false
            val fullyRestored = entry.requiredThreadIdsLocked().all { threadId ->
                restoredThreads[threadId] == entry.threadLifecycleVersions.getOrDefault(threadId, 0L) &&
                    isThreadReady(entry.connection, threadId)
            }
            if (!fullyRestored) {
                return@synchronized false
            }
            entry.recovering = false
            entry.state.value = connectedState
            true
        }
        if (finished) publish(entry)
        return finished
    }

    private suspend fun restoreOpenThreads(
        entry: Entry,
        restoredThreads: MutableMap<String, Long>,
    ): Boolean {
        val openThreads = synchronized(lock) {
            entry.requiredThreadIdsLocked().sortedBy { id ->
                if (CodexThreadKey(entry.connection.serverId, id) in activeThreadReferences) 0
                else if (id in entry.openThreadReferences) 1 else 2
            }
        }
        restoredThreads.keys.retainAll(openThreads.toSet())
        val permits = Semaphore(3)
        coroutineScope {
            openThreads.map { threadId ->
                async {
                    permits.withPermit {
                        val lifecycleVersion = synchronized(lock) {
                            if (entries[entry.connection.serverId] !== entry ||
                                threadId !in entry.requiredThreadIdsLocked()) null
                            else entry.threadLifecycleVersions.getOrDefault(threadId, 0L)
                        } ?: return@withPermit
                        if (synchronized(lock) {
                            restoredThreads[threadId] == lifecycleVersion && isThreadReady(entry.connection, threadId)
                        }) return@withPermit
                        try {
                            resumeThread(entry.connection, threadId)
                            synchronized(lock) {
                                if (isThreadReady(entry.connection, threadId)) restoredThreads[threadId] = lifecycleVersion
                            }
                        } catch (error: CancellationException) {
                            currentCoroutineContext().ensureActive()
                        } catch (_: Throwable) {
                            // Other subscriptions become usable independently; only this thread retries.
                        }
                    }
                }
            }.awaitAll()
        }
        val latestOpenThreads = synchronized(lock) {
            if (entries[entry.connection.serverId] !== entry) return@synchronized emptyMap()
            entry.requiredThreadIdsLocked().associateWith { threadId ->
                entry.threadLifecycleVersions.getOrDefault(threadId, 0L)
            }
        }
        restoredThreads.keys.retainAll(latestOpenThreads.keys)
        return latestOpenThreads.all { (threadId, lifecycleVersion) ->
            restoredThreads[threadId] == lifecycleVersion && isThreadReady(entry.connection, threadId)
        }
    }

    private fun shouldMaintainConnection(entry: Entry): Boolean = synchronized(lock) {
        entries[entry.connection.serverId] === entry &&
            entry.hasOwnersLocked()
    }

    private fun shouldReconnectSocketLocked(entry: Entry): Boolean {
        val state = entry.client.connectionState.value
        return entry.hasOwnersLocked() &&
            (state is CodexClientConnectionState.Disconnected || state is CodexClientConnectionState.Failed)
    }

    private suspend fun nextReconnectDelay(current: Long): Long =
        (current.coerceAtLeast(1) * 2).coerceAtMost(reconnectMaxMillis())

    private data class ResumedThread(
        val generation: Long,
        val lifecycleVersion: Long,
        val session: CodexThreadSession,
        val payloadBytes: Long,
    )

    private data class ConnectionKey(val url: String, val token: String?)

    private class Entry(
        val key: ConnectionKey,
        val connectionId: Long,
        val client: CodexAppServerClient,
        val connection: CodexServerConnection,
        val state: MutableStateFlow<CodexClientConnectionState>,
        val pending: MutableStateFlow<List<CodexServerRequest>>,
        val reducer: CodexEventReducer,
        val inboundJob: Job,
        val publish: (Entry) -> Unit,
        val usage: CodexAccountUsageStore,
        val usageClosed: () -> Unit,
        var usageJob: Job? = null,
        var references: Int = 0,
        var persistent: Boolean = false,
        var idleDisconnectJob: Job? = null,
        var reconnectJob: Job? = null,
        var stateJob: Job? = null,
        var recovering: Boolean = false,
        var modelJobGeneration: Long = -1L,
        var modelCatalog: Pair<Long, List<CodexModel>>? = null,
        var modelJob: Deferred<Result<List<CodexModel>>>? = null,
        val threadSessions: MutableMap<String, ResumedThread> = mutableMapOf(),
        val threadResumeIdentities: MutableMap<String, Pair<Long, Long>> = mutableMapOf(),
        val threadResumeJobs: MutableMap<String, Deferred<Result<CodexThreadSession>>> = mutableMapOf(),
        val recentThreadJobs: MutableMap<String, Job> = linkedMapOf(),
        val openThreadReferences: MutableMap<String, Int> = mutableMapOf(),
        val retainedThreadIds: MutableSet<String> = mutableSetOf(),
        val provisionalThreadIds: MutableSet<String> = mutableSetOf(),
        val threadUnsubscribeJobs: MutableMap<String, Job> = mutableMapOf(),
        val threadLifecycleVersions: MutableMap<String, Long> = mutableMapOf(),
    ) {
        fun requiredThreadIdsLocked(): Set<String> = openThreadReferences.keys + retainedThreadIds + recentThreadJobs.keys

        fun hasOwnersLocked(): Boolean = persistent || references > 0 || retainedThreadIds.isNotEmpty()

        fun close() {
            modelJob?.cancel()
            threadResumeJobs.values.toList().forEach { it.cancel() }
            threadResumeJobs.clear()
            recentThreadJobs.values.forEach(Job::cancel)
            recentThreadJobs.clear()
            usageJob?.cancel()
            usageClosed()
            idleDisconnectJob?.cancel()
            reconnectJob?.cancel()
            threadUnsubscribeJobs.values.forEach(Job::cancel)
            inboundJob.cancel()
            stateJob?.cancel()
            pending.value = emptyList()
            reducer.clear()
            client.close()
        }
    }

    internal fun closeForTest() {
        disconnectAll()
        if (ownsScope) scope.cancel()
    }
}

internal fun JsonPrimitive.requestKey(): String =
    if (isString) "string:${contentOrNull.orEmpty()}" else "number:${contentOrNull ?: this}"

private fun CodexServerRequest.threadId(): String? =
    (params["threadId"] as? JsonPrimitive)?.contentOrNull

// Conservative UTF-16 payload accounting without serializing image-bearing history into a second large string.
private fun jsonPayloadBytes(value: JsonElement): Long = when (value) {
    is JsonPrimitive -> value.content.length.toLong() * 2 + 32
    is JsonArray -> value.sumOf(::jsonPayloadBytes) + 32
    is JsonObject -> value.entries.sumOf { (key, item) -> key.length * 2L + jsonPayloadBytes(item) + 32 }
}
