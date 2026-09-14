package dev.wuxie233.codecarry.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.wuxie233.codecarry.BuildConfig
import dev.wuxie233.codecarry.data.codex.CodexClientConnectionState
import dev.wuxie233.codecarry.data.codex.CodexConnectionManager
import dev.wuxie233.codecarry.data.codex.CodexManagedConnection
import dev.wuxie233.codecarry.data.codex.CodexServerRequest
import dev.wuxie233.codecarry.data.codex.CodexThreadKey
import dev.wuxie233.codecarry.data.codex.ScopedCodexNotification
import dev.wuxie233.codecarry.data.codex.requestKey
import dev.wuxie233.codecarry.MainActivity
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.data.api.OpenCodeApi
import dev.wuxie233.codecarry.data.api.ServerConnection
import dev.wuxie233.codecarry.data.dsh.DshConnectionManager
import dev.wuxie233.codecarry.data.api.SseClient
import dev.wuxie233.codecarry.data.preferences.SessionListPreferencesRepository
import dev.wuxie233.codecarry.data.preferences.hasUnreadReplyBeyondReadAnchor
import dev.wuxie233.codecarry.data.preferences.readableAssistantOutputIds
import dev.wuxie233.codecarry.data.repository.EventReducer
import dev.wuxie233.codecarry.data.repository.LocalServerManager
import dev.wuxie233.codecarry.data.repository.ServerRepository
import dev.wuxie233.codecarry.data.repository.SettingsRepository
import dev.wuxie233.codecarry.data.transport.OpenCodeTransport
import dev.wuxie233.codecarry.domain.model.Message
import dev.wuxie233.codecarry.domain.model.MessageWithParts
import dev.wuxie233.codecarry.domain.model.Part
import dev.wuxie233.codecarry.domain.model.ConnectionPhase
import dev.wuxie233.codecarry.domain.model.ServerConfig
import dev.wuxie233.codecarry.domain.model.ServerType
import dev.wuxie233.codecarry.domain.model.Session
import dev.wuxie233.codecarry.domain.model.SessionStatus
import dev.wuxie233.codecarry.domain.model.SseEvent
import dev.wuxie233.codecarry.domain.transport.AgentTransport
import dev.wuxie233.codecarry.domain.transport.TransportEvent
import dev.wuxie233.codecarry.domain.transport.TransportRoom
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

private const val TAG = "OpenCodeService"
private const val NOTIFICATION_CHANNEL_ID = "opencode_connection"
private const val NOTIFICATION_CHANNEL_TASKS_ID = "opencode_tasks"
private const val NOTIFICATION_CHANNEL_TASKS_SILENT_ID = "opencode_tasks_silent"
private const val NOTIFICATION_CHANNEL_PERMISSIONS_ID = "opencode_permissions"
private const val PERSISTENT_NOTIFICATION_ID = 1001
private const val WAKELOCK_TAG = "OpenCodeRemote::SSEConnection"

// Reconnect timing
private const val RECONNECT_BASE_DELAY_MS = 1_000L   // 1 second
private const val RECONNECT_MAX_DELAY_MS = 30_000L   // 30 seconds
private const val RECONNECT_BACKOFF_FACTOR = 2.0

// Unread read-model timing/limits
private const val SSE_SETTLEMENT_DELAY_MS = 250L
private const val UNREAD_RECOMPUTE_MESSAGE_LIMIT = 50

/**
 * Per-server connection state held by the service.
 */
private data class ServerConnectionState(
    val config: ServerConfig,
    val transport: AgentTransport,
    val sseJob: Job,
    val isConnected: Boolean = false,
    val projectDirectories: List<String>? = null,
)

internal class ForegroundStatusRefreshObserver(
    private val onForeground: () -> Unit,
) : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) = onForeground()
}

internal fun shouldReconcileForegroundStatus(serverType: ServerType, isConnected: Boolean): Boolean =
    isConnected && serverType == ServerType.OPENCODE

internal fun openCodeNotificationDedupKey(serverId: String, id: String): String = "$serverId\u0000$id"

internal fun unreadFetchKey(serverId: String, sessionId: String): String = "$serverId\u0000$sessionId"

/**
 * Decide whether the unread recompute needs a fresh REST history fetch for one
 * session before the cursor comparison is trustworthy.
 *
 * Fetch when the session changed after the newest message we already know
 * (`sessionUpdated` beyond the newest known message timestamp), or when the
 * snapshot showed this session settling from Busy/Retry to idle (completion may
 * have happened while we were not watching), or when we have no known history
 * at all. A previously fetched [lastFetchedSessionUpdated] at or beyond the
 * current session timestamp means the history is already fresh — the memo keeps
 * the recompute idempotent (same snapshot → no repeated fetches).
 */
internal fun shouldFetchUnreadHistory(
    sessionUpdated: Long,
    knownMessages: List<Message>,
    transitionedToIdle: Boolean,
    lastFetchedSessionUpdated: Long?,
): Boolean {
    if (knownMessages.isEmpty()) return true
    if (lastFetchedSessionUpdated != null && sessionUpdated <= lastFetchedSessionUpdated && !transitionedToIdle) {
        return false
    }
    val newestKnownMessageTime = knownMessages.maxOf { message ->
        maxOf(message.time.created, message.time.completed ?: 0L)
    }
    return transitionedToIdle || sessionUpdated > newestKnownMessageTime
}

internal suspend fun <T> reconcileConnectedOpenCodeTargets(
    targets: Collection<T>,
    serverType: (T) -> ServerType,
    isConnected: (T) -> Boolean,
    reconcile: suspend (T) -> Unit,
): List<Throwable> = supervisorScope {
    targets.filter { target -> shouldReconcileForegroundStatus(serverType(target), isConnected(target)) }
        .map { target ->
            async {
                try {
                    reconcile(target)
                    null
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    error
                }
            }
        }
        .awaitAll()
        .filterNotNull()
}

internal suspend fun resolveStatusProjectDirectories(
    cachedDirectories: List<String>?,
    discover: suspend () -> List<String>,
): List<String>? = try {
    discover().distinct()
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    cachedDirectories
}

internal suspend fun loadSessionStatusSnapshot(
    transport: AgentTransport,
    projectDirectories: List<String>,
): Map<String, SessionStatus> {
    val statuses = transport.getSessionStatuses().toMutableMap()
    for (directory in projectDirectories.distinct()) {
        statuses.putAll(transport.getSessionStatuses(directory))
    }
    return statuses
}

internal suspend fun EventReducer.reconcileSessionStatusSnapshot(
    serverId: String,
    transport: AgentTransport,
    projectDirectories: List<String>,
): Int {
    val baseline = captureSessionStatusBaseline(serverId)
    val statuses = loadSessionStatusSnapshot(transport, projectDirectories)
    reconcileSessionStatuses(statuses, baseline)
    return statuses.size
}

/**
 * Foreground Service for maintaining OpenCode SSE connections to multiple servers.
 *
 * This service:
 * - Maintains persistent SSE connections to one or more servers simultaneously
 * - Processes events via EventReducer (with serverId tracking)
 * - Shows notifications for task completion and permission requests
 * - Auto-reconnects with exponential backoff on disconnection/error
 * - Holds a single partial WakeLock while any server is connected
 * - Shows an InboxStyle persistent notification summarising connected servers
 * - Groups event notifications by server
 *
 * The connections stay alive until the user explicitly disconnects each server
 * (or uses "Disconnect All").
 */
@AndroidEntryPoint
class OpenCodeConnectionService : Service() {

    override fun attachBaseContext(newBase: Context) {
        val languageCode = SettingsRepository.getStoredLanguage(newBase)
        if (languageCode.isNotEmpty()) {
            val locale = MainActivity.parseLocale(languageCode)
            Locale.setDefault(locale)
            val config = newBase.resources.configuration
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    @Inject
    lateinit var api: OpenCodeApi

    @Inject
    lateinit var sseClient: SseClient

    @Inject
    lateinit var eventReducer: EventReducer

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var sessionListPreferencesRepository: SessionListPreferencesRepository

    @Inject
    lateinit var serverRepository: ServerRepository

    @Inject
    lateinit var dshConnectionManager: DshConnectionManager

    @Inject
    lateinit var foregroundResumeDispatcher: ForegroundResumeDispatcher

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** All active/pending server connections keyed by serverId. */
    @Inject lateinit var codexConnectionManager: CodexConnectionManager
    private val codexConnections = CodexOwnershipRegistry()
    private val codexConnectionErrors = ConcurrentHashMap<String, String>()
    private val handledCodexTurns = BoundedNotificationKeys(capacity = 512)
    private val postedCodexRequestIds = ConcurrentHashMap<String, Int>()
    private val connections = mutableMapOf<String, ServerConnectionState>()

    private var notificationWatchdogJob: Job? = null
    private var foregroundStatusRefreshJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var notificationManager: NotificationManager
    private var foregroundStarted: Boolean = false
    private val foregroundStatusRefreshObserver = ForegroundStatusRefreshObserver {
        reconcileConnectedOpenCodeStatuses()
        dshConnectionManager.refreshReadyCatalogs()
        foregroundResumeDispatcher.dispatch()
    }

    /** Observable set of server IDs that are actually connected (SSE stream active). */
    private val _connectedServerIds = MutableStateFlow<Set<String>>(emptySet())
    val connectedServerIds: StateFlow<Set<String>> = _connectedServerIds.asStateFlow()

    /** Observable set of server IDs that are attempting to connect (SSE not yet established or reconnecting). */
    private val _connectingServerIds = MutableStateFlow<Set<String>>(emptySet())
    val connectingServerIds: StateFlow<Set<String>> = _connectingServerIds.asStateFlow()

    private val _connectionPhases = MutableStateFlow<Map<String, ConnectionPhase>>(emptyMap())
    val connectionPhases: StateFlow<Map<String, ConnectionPhase>> = _connectionPhases.asStateFlow()
    private val _connectionErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val connectionErrors: StateFlow<Map<String, String>> = _connectionErrors.asStateFlow()

    /** Dedup response-ready notifications per server/session by last assistant message ID. */
    private val lastNotifiedAssistantMessageBySession = ConcurrentHashMap<String, String>()

    /** Dedup permission notifications fired from the connect-time bootstrap, keyed by server/request. */
    private val postedPermissionRequestIds = ConcurrentHashMap.newKeySet<String>()
    private val manuallyDisconnectedServerIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * Session-update timestamp each conversation was last history-fetched at during
     * unread recomputes, keyed by `serverId\u0000sessionId`. Keeps ON_START and
     * reconnect recomputes from re-fetching unchanged sessions.
     */
    private val lastUnreadFetchUpdated = ConcurrentHashMap<String, Long>()

    inner class LocalBinder : Binder() {
        fun getService(): OpenCodeConnectionService = this@OpenCodeConnectionService
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Log.d(TAG, "Service created")

        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()
        serviceScope.launch { observeCodexConnections() }
        serviceScope.launch { observeCodexNotifications() }
        serviceScope.launch { observeActiveCodexThreads() }
        ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundStatusRefreshObserver)

        serviceScope.launch {
            autoConnectConfiguredServers()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (BuildConfig.DEBUG) Log.d(TAG, "Service started, action=${intent?.action}")

        when (intent?.action) {
            ACTION_PERMISSION_REPLY -> {
                val serverId = intent.getStringExtra(EXTRA_SERVER_ID)
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
                val requestId = intent.getStringExtra(EXTRA_PERMISSION_REQUEST_ID)
                val replyValue = intent.getStringExtra(EXTRA_PERMISSION_REPLY_VALUE)

                if (serverId == null || sessionId == null || requestId == null || replyValue == null) {
                    Log.w(TAG, "Permission reply action missing required extras")
                    return START_NOT_STICKY
                }

                Log.i(TAG, "Permission reply requested for $requestId in session $sessionId on server $serverId: $replyValue")
                handlePermissionAction(serverId, sessionId, requestId, replyValue)
                return START_NOT_STICKY
            }
            ACTION_DISCONNECT_ALL -> {
                Log.i(TAG, "Disconnect All requested via notification")
                disconnectAllVisibleServers()
                return START_NOT_STICKY
            }
            ACTION_DISCONNECT -> {
                val serverId = intent.getStringExtra("server_id")
                if (serverId != null) {
                    Log.i(TAG, "Disconnect requested for server $serverId")
                    disconnect(serverId)
                }
                return START_NOT_STICKY
            }
        }

        ensureForegroundStarted()

        intent?.getStringExtra(EXTRA_SERVER_ID)?.let { serverId ->
            serviceScope.launch {
                serverRepository.getServer(serverId)?.let(::connect)
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(foregroundStatusRefreshObserver)
        super.onDestroy()
        if (BuildConfig.DEBUG) Log.d(TAG, "Service destroyed")
        disconnectAllInternal(stopService = false)
        serviceScope.cancel()
    }

    // ============ Public API ============

    /**
     * Connect to an OpenCode server. If already connected to this server, no-op.
     * Multiple servers can be connected simultaneously.
     */
    fun connect(server: ServerConfig) {
        manuallyDisconnectedServerIds.remove(server.id)
        connectInternal(server)
    }

    private fun connectInternal(server: ServerConfig) {
        if (server.type == ServerType.CODEX) {
            connectCodex(server)
            return
        }
        if (connections.containsKey(server.id)) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Already connected to server ${server.id}, skipping")
            return
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "Connecting to server: ${server.displayName} (${server.url})")

        ensureForegroundStarted()

        val transport = createTransport(server)

        // Acquire wake lock (shared — first connect acquires, last disconnect releases)
        acquireWakeLock()

        // Start SSE connection with auto-reconnect
        val job = startSseConnection(server, transport)

        connections[server.id] = ServerConnectionState(
            config = server,
            transport = transport,
            sseJob = job,
            isConnected = false
        )

        _connectingServerIds.update { it + server.id }
        updateConnectionPhase(server.id, ConnectionPhase.LoadingWorkspace)
        job.start()

        // Update persistent notification
        updatePersistentNotification()

        // Start watchdog if not already running
        startNotificationWatchdog()
    }

    /**
     * Disconnect from a single server.
     */
    fun disconnect(serverId: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "Disconnecting server $serverId")
        manuallyDisconnectedServerIds.add(serverId)

        if (codexConnections.current(serverId) != null) {
            disconnectCodex(serverId)
            return
        }
        val state = connections.remove(serverId) ?: return
        state.sseJob.cancel()
        clearOpenCodeNotificationDedup(serverId)

        _connectedServerIds.update { it - serverId }
        _connectingServerIds.update { it - serverId }
        _connectionPhases.update { it - serverId }
        _connectionErrors.update { it - serverId }

        eventReducer.clearForServer(serverId)

        if (!hasManagedConnections()) {
            // Last server disconnected — clean up and stop service
            releaseWakeLock()
            notificationWatchdogJob?.cancel()
            notificationWatchdogJob = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            stopSelf()
        } else {
            updatePersistentNotification()
        }
    }

    /**
     * Disconnect from all servers and stop the service.
     */
    fun disconnectAll() {
        disconnectAllInternal(stopService = true)
    }

    private fun disconnectAllVisibleServers() {
        val visibleServerIds = connections.values
            .filterNot { isLocalServer(it.config) }
            .map { it.config.id }
            .plus(codexConnections.snapshot().keys)

        if (visibleServerIds.isEmpty()) {
            updatePersistentNotification()
            return
        }

        for (serverId in visibleServerIds) {
            disconnect(serverId)
        }
    }

    private fun disconnectAllInternal(stopService: Boolean) {
        if (BuildConfig.DEBUG) Log.d(TAG, "Disconnecting all servers")

        for ((_, state) in connections) {
            state.sseJob.cancel()
        }
        val codexOwners = codexConnections.clear()
        codexOwners.forEach { owner ->
            owner.connectJob.cancel()
            codexConnectionManager.releasePersistent(owner.config.id)
        }
        codexConnectionErrors.clear()
        cancelAllCodexRequestNotifications()
        val serverIds = connections.keys.toList() + codexOwners.map { it.config.id }
        connections.clear()
        lastNotifiedAssistantMessageBySession.clear()
        postedPermissionRequestIds.clear()

        _connectedServerIds.value = emptySet()
        _connectingServerIds.value = emptySet()
        _connectionPhases.value = emptyMap()
        _connectionErrors.value = emptyMap()

        for (serverId in serverIds) {
            eventReducer.clearForServer(serverId)
        }

        releaseWakeLock()
        notificationWatchdogJob?.cancel()
        notificationWatchdogJob = null

        if (stopService) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            stopSelf()
        }
    }

    private suspend fun autoConnectConfiguredServers() {
        try {
            val autoConnectServers = serverRepository.servers.first().filter {
                it.autoConnect && it.type in setOf(ServerType.OPENCODE, ServerType.CODEX)
            }
            if (autoConnectServers.isEmpty()) return
            Log.i(TAG, "Auto-connecting ${autoConnectServers.size} server(s)")
            autoConnectServers.forEach { server ->
                if (server.id !in manuallyDisconnectedServerIds) connectInternal(server)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-connect servers", e)
        }
    }

    private fun ensureForegroundStarted() {
        if (foregroundStarted) return
        startForeground(PERSISTENT_NOTIFICATION_ID, createPersistentNotification())
        foregroundStarted = true
    }

    /**
     * Check if a specific server is connected.
     */
    fun isConnected(serverId: String): Boolean {
        return serverId in _connectedServerIds.value
    }

    private fun connectCodex(server: ServerConfig) {
        val owner = codexConnections.register(server) { generation ->
            serviceScope.launch(start = CoroutineStart.LAZY) {
                try {
                    codexConnectionManager.connect(server)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    if (codexConnections.isCurrent(server.id, generation)) {
                        codexConnectionErrors[server.id] = error.message ?: "Codex connection failed"
                        publishCodexConnectionStates(codexConnectionManager.connections.value)
                    }
                }
            }
        } ?: return
        ensureForegroundStarted()
        acquireWakeLock()
        _connectingServerIds.update { it + server.id }
        updateConnectionPhase(server.id, ConnectionPhase.OpeningLiveUpdates)
        owner.connectJob.start()
        updatePersistentNotification()
        startNotificationWatchdog()
    }

    private fun disconnectCodex(serverId: String) {
        val owned = codexConnections.remove(serverId) ?: return
        owned.connectJob.cancel()
        codexConnectionErrors.remove(serverId)
        cancelCodexRequestNotifications(serverId)
        codexConnectionManager.releasePersistent(serverId)
        _connectedServerIds.update { it - serverId }
        _connectingServerIds.update { it - serverId }
        _connectionPhases.update { it - serverId }
        _connectionErrors.update { it - serverId }
        if (!hasManagedConnections()) {
            releaseWakeLock()
            notificationWatchdogJob?.cancel()
            notificationWatchdogJob = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            stopSelf()
        } else {
            updatePersistentNotification()
        }
    }

    private suspend fun observeCodexConnections() {
        codexConnectionManager.connections.collect { managed ->
            publishCodexConnectionStates(managed)
            reconcileCodexRequestNotifications(managed)
        }
    }

    private fun publishCodexConnectionStates(managed: Map<String, CodexManagedConnection>) {
        val owned = codexConnections.snapshot()
        val ownedIds = owned.keys
        val states = ownedIds.associateWith { id -> codexServiceConnectionState(managed[id]) }
        val connected = states.filterValues(CodexServiceConnectionState::connected).keys
        val connecting = states.filterValues(CodexServiceConnectionState::connecting).keys
        managed.forEach { (id, connection) ->
            if (id !in ownedIds) return@forEach
            val failed = connection.state as? CodexClientConnectionState.Failed
            if (failed != null) codexConnectionErrors[id] = failed.error.message ?: "Codex connection failed"
            if (connection.state is CodexClientConnectionState.Connected) codexConnectionErrors.remove(id)
        }
        _connectionErrors.update { errors ->
            errors.filterKeys { it !in ownedIds } + codexConnectionErrors.filterKeys { it in ownedIds }
        }
        val nonCodexConnected = _connectedServerIds.value - ownedIds
        val nonCodexConnecting = _connectingServerIds.value - ownedIds
        _connectedServerIds.value = nonCodexConnected + connected
        _connectingServerIds.value = nonCodexConnecting + connecting
        _connectionPhases.update { phases ->
            phases.filterKeys { it !in ownedIds } + connecting.associateWith { id ->
                if (codexConnectionErrors.containsKey(id)) {
                    ConnectionPhase.WaitingToRetry
                } else {
                    ConnectionPhase.OpeningLiveUpdates
                }
            }
        }
        val currentOwnedIds = codexConnections.snapshot().keys
        val removedIds = ownedIds - currentOwnedIds
        if (removedIds.isNotEmpty()) {
            _connectedServerIds.update { it - removedIds }
            _connectingServerIds.update { it - removedIds }
            _connectionPhases.update { it - removedIds }
            _connectionErrors.update { it - removedIds }
        }
        if (foregroundStarted) updatePersistentNotification()
    }

    private suspend fun observeCodexNotifications() {
        codexConnectionManager.notificationEvents.collect { event ->
            val owned = codexConnections.current(event.serverId) ?: return@collect
            val currentId = codexConnectionManager.connections.value[event.serverId]?.connectionId
            if (currentId != event.connectionId) return@collect
            if (!codexConnections.isCurrent(event.serverId, owned.generation)) return@collect
            maybeShowCodexTurnNotification(owned.config, event)
        }
    }

    private suspend fun maybeShowCodexTurnNotification(
        server: ServerConfig,
        event: ScopedCodexNotification,
    ) {
        val threadId = event.notification.threadId ?: return
        val turn = event.notification.turn ?: return
        val decision = codexTurnNotificationDecision(
            serverId = event.serverId,
            notification = event.notification,
            activeThreads = codexConnectionManager.activeThreads.value,
            notificationsEnabled = settingsRepository.notificationsEnabled.first(),
        )
        if (decision == CodexTurnNotificationDecision.IGNORE) return
        val turnKey = "${event.serverId}:${event.connectionId}:$threadId:${turn.id}"
        if (!handledCodexTurns.add(turnKey)) return
        when (decision) {
            CodexTurnNotificationDecision.IGNORE -> Unit
            CodexTurnNotificationDecision.SUPPRESS_ACTIVE -> {
                cancelCodexResponseNotification(event.serverId, threadId)
                return
            }
            CodexTurnNotificationDecision.POST -> Unit
        }
        delay(150)
        if (CodexThreadKey(event.serverId, threadId) in codexConnectionManager.activeThreads.value) {
            cancelCodexResponseNotification(event.serverId, threadId)
            return
        }
        showCodexResponseNotification(server, threadId)
    }

    private suspend fun observeActiveCodexThreads() {
        codexConnectionManager.activeThreads.collect { active ->
            active.forEach { key -> cancelCodexResponseNotification(key.serverId, key.threadId) }
        }
    }

    private suspend fun reconcileCodexRequestNotifications(managed: Map<String, CodexManagedConnection>) {
        val currentKeys = mutableSetOf<String>()
        val ownedSnapshot = codexConnections.snapshot()
        for ((serverId, owned) in ownedSnapshot) {
            val requests = managed[serverId]?.pendingRequests.orEmpty()
            for (request in requests) {
                if (request.notificationKind() == null) continue
                val key = "$serverId:${request.id.requestKey()}"
                currentKeys += key
                if (postedCodexRequestIds.containsKey(key)) continue
                if (!settingsRepository.notificationsEnabled.first()) continue
                if (!codexConnections.isCurrent(serverId, owned.generation)) continue
                val id = CodexNotificationIdentity.requestId(serverId, request)
                postedCodexRequestIds[key] = id
                showCodexRequestNotification(owned.config, request, id)
            }
        }
        val removed = postedCodexRequestIds.keys - currentKeys
        removed.forEach { key ->
            val serverId = key.substringBefore(':')
            postedCodexRequestIds.remove(key)?.let { id -> dismissCodexChildNotification(serverId, id) }
        }
    }

    private suspend fun showCodexResponseNotification(server: ServerConfig, threadId: String) {
        val thread = codexConnectionManager.get(server.id)?.events?.value?.threads?.get(threadId)
        val body = thread?.displayTitle?.take(80)
            ?: getString(R.string.notification_new_session)
        val id = CodexNotificationIdentity.responseReadyId(server.id, threadId)
        val silent = settingsRepository.silentNotifications.first()
        val channel = if (silent) NOTIFICATION_CHANNEL_TASKS_SILENT_ID else NOTIFICATION_CHANNEL_TASKS_ID
        val notification = NotificationCompat.Builder(this, channel)
            .setContentTitle(getString(R.string.notification_response_ready))
            .setContentText(body)
            .setSubText(server.displayName)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(createCodexThreadPendingIntent(server.id, threadId, id))
            .setAutoCancel(true)
            .setPriority(if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setGroup(SessionNotificationIdentity.serverGroup(server.id))
            .build()
        notificationManager.notify(id, notification)
        showServerGroupSummary(server)
    }

    private fun showCodexRequestNotification(server: ServerConfig, request: CodexServerRequest, id: Int) {
        val threadId = request.params["threadId"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: return
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_PERMISSIONS_ID)
            .setContentTitle(request.notificationTitle())
            .setContentText(server.displayName)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(createCodexThreadPendingIntent(server.id, threadId, id))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setGroup(SessionNotificationIdentity.serverGroup(server.id))
            .build()
        notificationManager.notify(id, notification)
        showServerGroupSummary(server)
    }

    private fun createCodexThreadPendingIntent(serverId: String, threadId: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_CODEX_THREAD
            data = android.net.Uri.Builder()
                .scheme("ocremote")
                .authority("codex")
                .appendPath(serverId)
                .appendPath("thread")
                .appendPath(threadId)
                .build()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SERVER_ID, serverId)
            putExtra(EXTRA_THREAD_ID, threadId)
        }
        return PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun cancelCodexResponseNotification(serverId: String, threadId: String) {
        dismissCodexChildNotification(serverId, CodexNotificationIdentity.responseReadyId(serverId, threadId))
    }

    private fun cancelCodexRequestNotifications(serverId: String) {
        postedCodexRequestIds.keys.filter { it.startsWith("$serverId:") }.forEach { key ->
            postedCodexRequestIds.remove(key)?.let { id -> dismissCodexChildNotification(serverId, id) }
        }
    }

    private fun cancelAllCodexRequestNotifications() {
        postedCodexRequestIds.values.forEach(notificationManager::cancel)
        postedCodexRequestIds.clear()
    }

    private fun dismissCodexChildNotification(serverId: String, childId: Int) {
        val children = notificationManager.activeNotifications
            .filter { active -> active.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .map { active -> active.id to active.notification.group }
        val cancelSummary = shouldCancelServerSummary(children, serverId, childId)
        notificationManager.cancel(childId)
        if (cancelSummary) notificationManager.cancel(SessionNotificationIdentity.serverSummaryId(serverId))
    }

    private fun hasManagedConnections(): Boolean =
        connections.isNotEmpty() || !codexConnections.isEmpty()

    // ============ Notification Watchdog ============

    private fun startNotificationWatchdog() {
        if (notificationWatchdogJob?.isActive == true) return
        notificationWatchdogJob = serviceScope.launch {
            while (isActive && hasManagedConnections()) {
                delay(5_000)
                if (!isNotificationVisible()) {
                    Log.i(TAG, "Foreground notification was dismissed, restoring it")
                    startForeground(PERSISTENT_NOTIFICATION_ID, createPersistentNotification())
                }
            }
        }
    }

    private fun isNotificationVisible(): Boolean {
        return notificationManager.activeNotifications.any { it.id == PERSISTENT_NOTIFICATION_ID }
    }

    // ============ WakeLock ============

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            acquire()
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                if (BuildConfig.DEBUG) Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    // ============ SSE Connection with Auto-Reconnect ============

    private fun startSseConnection(server: ServerConfig, transport: AgentTransport): Job {
        return serviceScope.launch(start = CoroutineStart.LAZY) {
            var attempt = 0

            while (isActive) {
                attempt++
                Log.i(TAG, "[${server.displayName}] SSE connection attempt #$attempt")

                updateConnectionPhase(server.id, ConnectionPhase.LoadingWorkspace)
                val projects = try {
                    transport.listRoomScopes().also { scopes ->
                        updateProjectDirectories(server.id, scopes.map { it.directory })
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "[${server.displayName}] Failed to list projects: ${error.message}")
                    emptyList()
                }
                try {
                    updateConnectionPhase(server.id, ConnectionPhase.SyncingSessions)
                    val roots = transport.listRooms(rootsOnly = true).openCodeSessions()
                    eventReducer.setSessions(server.id, roots)
                    Log.i(TAG, "[${server.displayName}] Pre-loaded ${roots.size} root sessions")

                    var childCount = 0
                    for (project in projects) {
                        try {
                            val all = transport.listRooms(directory = project.directory, rootsOnly = false).openCodeSessions()
                            val children = all.filter { it.parentId != null }
                            if (children.isNotEmpty()) {
                                eventReducer.setSessions(server.id, children)
                                childCount += children.size
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "[${server.displayName}] Failed to pre-load children for ${project.displayName}: ${e.message}")
                        }
                    }
                    Log.i(TAG, "[${server.displayName}] Pre-loaded $childCount child sessions across ${projects.size} projects")
                } catch (e: Exception) {
                    Log.w(TAG, "[${server.displayName}] Failed to pre-load sessions: ${e.message}")
                }

                // Capture which permissions are carried over from a previous connection BEFORE
                // subscribing, so the permission reconcile can tell stale (replied while away)
                // from live (arriving on this fresh stream) apart.
                updateConnectionPhase(server.id, ConnectionPhase.RestoringActivity)
                val prePermissionIds = currentServerPermissionIds(server.id)

                try {
                    coroutineScope {
                        val streamScope = this
                        updateConnectionPhase(server.id, ConnectionPhase.OpeningLiveUpdates)
                        transport.openEventStream()
                            .catch { error ->
                                Log.e(TAG, "[${server.displayName}] SSE stream error", error)
                                updateServerConnected(server.id, false)
                                throw error
                            }
                            .collect { transportEvent ->
                                val streamJustOpened = connections[server.id]?.isConnected != true
                                if (streamJustOpened) {
                                    updateServerConnected(server.id, true)
                                    attempt = 0
                                    updatePersistentNotification()
                                }
                                when (transportEvent) {
                                    is TransportEvent.OpenCode -> processEvent(server, transportEvent.event)
                                }
                                if (streamJustOpened) {
                                    // Start snapshots only after the stream is live and its first event
                                    // has been reduced. Revision guards preserve any subsequent deltas.
                                    connections[server.id]?.projectDirectories?.let { directories ->
                                        streamScope.launch {
                                            // Capture pre-snapshot statuses so the unread recompute can
                                            // detect sessions that settled while this connection was down.
                                            val previousStatuses = eventReducer.serverSessionStatuses.value[server.id].orEmpty()
                                            bootstrapSessionStatuses(server, transport, directories)
                                            recomputeServerUnread(server, previousStatuses)
                                        }
                                    } ?: Log.w(TAG, "[${server.displayName}] Skipping status snapshot without a complete project scope list")
                                    streamScope.launch {
                                        bootstrapPendingPermissions(server, transport, prePermissionIds)
                                    }
                                }
                            }
                    }

                    // Flow completed normally (server closed connection)
                    Log.w(TAG, "[${server.displayName}] SSE stream completed")
                    updateServerConnected(server.id, false)
                } catch (e: CancellationException) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "[${server.displayName}] SSE job cancelled, not reconnecting")
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "[${server.displayName}] SSE connection failed: ${e.message}")
                    updateServerConnected(server.id, false)
                }

                // If this server was removed from connections, stop the loop
                if (!connections.containsKey(server.id)) break

                val delayMs = calculateBackoff(attempt)
                Log.i(TAG, "[${server.displayName}] Reconnecting in ${delayMs}ms (attempt #$attempt)")
                updateConnectionPhase(server.id, ConnectionPhase.WaitingToRetry)
                updatePersistentNotification()
                delay(delayMs)
            }
        }
    }

    private fun currentServerPermissionIds(serverId: String): Set<String> {
        return eventReducer.permissionsByServer.value[serverId]
            .orEmpty()
            .values
            .flatten()
            .mapTo(mutableSetOf()) { it.id }
    }

    private fun clearOpenCodeNotificationDedup(serverId: String) {
        val prefix = "$serverId\u0000"
        lastNotifiedAssistantMessageBySession.keys.removeAll { it.startsWith(prefix) }
        postedPermissionRequestIds.removeAll { it.startsWith(prefix) }
    }

    /**
     * Pull current session statuses (retry/cooldown/busy) on (re)connect so they show
     * immediately instead of waiting for the next pushed SSE event. Runs after the live stream
     * opens; revision guards prevent the snapshot from overwriting newer SSE deltas.
     */
    private suspend fun bootstrapSessionStatuses(
        server: ServerConfig,
        transport: AgentTransport,
        projectDirectories: List<String>,
    ) {
        try {
            val statusCount = eventReducer.reconcileSessionStatusSnapshot(
                serverId = server.id,
                transport = transport,
                projectDirectories = projectDirectories,
            )
            Log.i(TAG, "[${server.displayName}] Bootstrapped $statusCount non-idle session status(es)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "[${server.displayName}] Failed to bootstrap session statuses: ${e.message}")
        }
    }

    /**
     * Reconcile all live OpenCode connections when the app returns to the foreground. The SSE
     * jobs remain active in the background; this one-shot snapshot only repairs events missed by
     * the network or OS and is never scheduled as polling.
     */
    private fun reconcileConnectedOpenCodeStatuses() {
        foregroundStatusRefreshJob?.cancel()
        foregroundStatusRefreshJob = serviceScope.launch {
            reconcileConnectedOpenCodeTargets(
                targets = connections.values.toList(),
                serverType = { state -> state.config.type },
                isConnected = ServerConnectionState::isConnected,
                reconcile = ::reconcileConnectedOpenCodeStatus,
            )
        }
    }

    private suspend fun reconcileConnectedOpenCodeStatus(state: ServerConnectionState) {
        val server = state.config
        val discoveredDirectories = resolveStatusProjectDirectories(state.projectDirectories) {
            state.transport.listRoomScopes().map { it.directory }
        } ?: run {
            Log.w(TAG, "[${server.displayName}] Skipping foreground reconcile without a complete project scope list")
            return
        }
        updateProjectDirectories(server.id, discoveredDirectories)
        val trackedDirectories = eventReducer.serverSessions.value[server.id].orEmpty()
            .let { ids -> eventReducer.sessions.value.filter { it.id in ids }.map { it.directory } }
            .filter { it.isNotBlank() }
        val projectDirectories = (discoveredDirectories + trackedDirectories).distinct()

        try {
            val baseline = eventReducer.captureSessionStatusBaseline(server.id)
            val statuses = loadSessionStatusSnapshot(state.transport, projectDirectories)
            val current = connections[server.id]
            if (current?.transport !== state.transport || current.isConnected.not()) return
            // Capture pre-snapshot statuses before applying, then recompute unread
            // marks idempotently from the merged snapshots (issue #35).
            val previousStatuses = eventReducer.serverSessionStatuses.value[server.id].orEmpty()
            eventReducer.reconcileSessionStatuses(statuses, baseline)
            mergeForegroundSessionList(state, projectDirectories)
            recomputeServerUnread(server, previousStatuses)
            Log.i(TAG, "[${server.displayName}] Reconciled ${statuses.size} foreground session status(es)")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "[${server.displayName}] Foreground status reconcile failed: ${error.message}")
        }
    }

    private suspend fun mergeForegroundSessionList(
        state: ServerConnectionState,
        projectDirectories: List<String>,
    ) {
        try {
            val roots = state.transport.listRooms(rootsOnly = true)
                .mapNotNull { (it as? TransportRoom.OpenCode)?.session }
            if (roots.isNotEmpty()) eventReducer.setSessions(state.config.id, roots)
            for (directory in projectDirectories) {
                val scoped = state.transport.listRooms(directory = directory, rootsOnly = false)
                    .mapNotNull { (it as? TransportRoom.OpenCode)?.session }
                if (scoped.isNotEmpty()) eventReducer.setSessions(state.config.id, scoped)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "[${state.config.displayName}] Foreground session list merge failed: ${error.message}")
        }
    }

    private fun updateProjectDirectories(serverId: String, directories: List<String>) {
        val state = connections[serverId] ?: return
        connections[serverId] = state.copy(projectDirectories = directories.distinct())
    }

    /**
     * Pull pending permission requests on (re)connect and reconcile them, firing a deduped
     * notification for each genuinely new one. Called concurrently with the live SSE stream so
     * permissions asked during the fetch are not lost.
     */
    private suspend fun bootstrapPendingPermissions(
        server: ServerConfig,
        transport: AgentTransport,
        preExistingIds: Set<String>,
    ) {
        try {
            val pending = transport.listPendingPermissions()
            eventReducer.reconcilePermissions(server.id, pending, preExistingIds)
            if (pending.isNotEmpty() && settingsRepository.notificationsEnabled.first()) {
                for (perm in pending) {
                    if (isChildSession(server.id, perm.sessionId)) continue
                    if (!postedPermissionRequestIds.add(openCodeNotificationDedupKey(server.id, perm.id))) continue
                    showPermissionNotification(
                        server = server,
                        sessionId = perm.sessionId,
                        requestId = perm.id,
                        permission = perm.permission,
                    )
                }
            }
            Log.i(TAG, "[${server.displayName}] Bootstrapped ${pending.size} pending permission(s)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "[${server.displayName}] Failed to bootstrap pending permissions: ${e.message}")
        }
    }

    private fun updateServerConnected(serverId: String, connected: Boolean) {
        val state = connections[serverId] ?: return
        connections[serverId] = state.copy(isConnected = connected)
        if (connected) {
            _connectingServerIds.update { it - serverId }
            _connectedServerIds.update { it + serverId }
            _connectionPhases.update { it - serverId }
        } else {
            _connectedServerIds.update { it - serverId }
            _connectingServerIds.update { it + serverId }
        }
    }

    private fun updateConnectionPhase(serverId: String, phase: ConnectionPhase) {
        _connectionPhases.update { it + (serverId to phase) }
    }

    private suspend fun calculateBackoff(attempt: Int): Long {
        val maxDelay = when (settingsRepository.reconnectMode.first()) {
            "aggressive" -> 5_000L
            "conservative" -> 60_000L
            else -> RECONNECT_MAX_DELAY_MS // normal: 30s
        }
        val delay = (RECONNECT_BASE_DELAY_MS * Math.pow(RECONNECT_BACKOFF_FACTOR, (attempt - 1).coerceAtLeast(0).toDouble())).toLong()
        return delay.coerceAtMost(maxDelay)
    }

    // ============ Event Processing ============

    /**
     * Check if a session is a child/sub-agent session (has parentID set).
     * Child sessions should not trigger user-facing notifications,
     * matching the behavior of the official opencode WebUI and TUI.
     * Scoped to one server: another server may reuse the same session id.
     */
    private fun isChildSession(serverId: String, sessionId: String): Boolean {
        return eventReducer.serverSessionDetails.value[serverId]?.get(sessionId)?.parentId != null
    }

    private fun processEvent(server: ServerConfig, event: SseEvent) {
        if (BuildConfig.DEBUG) Log.d(TAG, "[${server.displayName}] SSE event: ${event.javaClass.simpleName}")

        eventReducer.processEvent(event, server.id)

        when (event) {
            is SseEvent.SessionStatus -> {
                if (event.status is dev.wuxie233.codecarry.domain.model.SessionStatus.Idle) {
                    evaluateSessionUnread(server.id, event.sessionId, settle = true)
                }
            }
            is SseEvent.SessionIdle -> {
                evaluateSessionUnread(server.id, event.sessionId, settle = true)
                if (isChildSession(server.id, event.sessionId)) return
                val sourceTransport = connections[server.id]?.transport ?: return
                serviceScope.launch {
                    if (!settingsRepository.notificationsEnabled.first()) return@launch

                    // Give reducer a brief moment to receive trailing message/part events.
                    delay(250)
                    val current = connections[server.id]
                    if (current?.transport !== sourceTransport || !current.isConnected) return@launch
                    if (eventReducer.isSessionVisible(server.id, event.sessionId)) return@launch

                    val assistantMessageId = latestNotifiableAssistantMessageId(server.id, event.sessionId)
                    if (assistantMessageId == null) {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "[${server.displayName}] Skip response-ready: no assistant text output (${event.sessionId})")
                        }
                        return@launch
                    }

                    val dedupKey = openCodeNotificationDedupKey(server.id, event.sessionId)
                    val previousNotified = lastNotifiedAssistantMessageBySession[dedupKey]
                    if (previousNotified == assistantMessageId) {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "[${server.displayName}] Skip duplicate response-ready (${event.sessionId}, msg=$assistantMessageId)")
                        }
                        return@launch
                    }

                    lastNotifiedAssistantMessageBySession[dedupKey] = assistantMessageId
                    Log.i(TAG, "[${server.displayName}] Session idle -> Response ready for ${event.sessionId}")
                        showTaskCompleteNotification(server, event.sessionId)
                }
            }
            is SseEvent.PermissionAsked -> {
                if (isChildSession(server.id, event.sessionId)) return
                if (!postedPermissionRequestIds.add(openCodeNotificationDedupKey(server.id, event.id))) return
                Log.i(TAG, "[${server.displayName}] Permission asked: ${event.permission} (id=${event.id})")
                showPermissionNotification(
                    server = server,
                    sessionId = event.sessionId,
                    requestId = event.id,
                    permission = event.permission
                )
            }
            is SseEvent.QuestionAsked -> {
                if (isChildSession(server.id, event.sessionId)) return
                Log.i(TAG, "[${server.displayName}] Question asked for session ${event.sessionId}")
                val questionText = event.questions.firstOrNull()?.question ?: getString(R.string.notification_has_question, getString(R.string.notification_new_session))
                showQuestionNotification(server, event.sessionId, questionText)
            }
            is SseEvent.SessionError -> {
                if (event.sessionId != null && isChildSession(server.id, event.sessionId)) return
                Log.i(TAG, "[${server.displayName}] Session error: ${event.error}")
                showErrorNotification(server, event.sessionId, event.error)
            }
            else -> { }
        }
    }

    /**
     * Cursor-based unread evaluation (issue #35): a session is unread when new
     * readable assistant output exists beyond the persisted last-read anchor —
     * never merely because a Busy/Retry session became Idle. Stop, failure, or
     * tool-only activity produces no readable output and therefore no unread
     * mark (it also clears a stale mark, which keeps the recompute idempotent:
     * the same reducer snapshot always yields the same result).
     *
     * `settle = true` gives the reducer a brief moment to receive trailing
     * message/part events after an idle event, mirroring the notification path.
     */
    private fun evaluateSessionUnread(serverId: String, sessionId: String, settle: Boolean) {
        if (isChildSession(serverId, sessionId)) return
        serviceScope.launch {
            if (settle) delay(SSE_SETTLEMENT_DELAY_MS)
            evaluateUnreadFromCursor(serverId, sessionId)
        }
    }

    /** Core cursor evaluation; skips child sessions and the currently visible chat. */
    private suspend fun evaluateUnreadFromCursor(serverId: String, sessionId: String) {
        if (isChildSession(serverId, sessionId)) return
        // Screen+app lifecycle visibility (issue #32): a backgrounded chat keeps
        // its ViewModel alive but must still mark unread and notify.
        if (eventReducer.isSessionVisible(serverId, sessionId)) return
        val messages = eventReducer.serverMessages.value[serverId]?.get(sessionId) ?: return
        val anchor = sessionListPreferencesRepository.readAnchor(serverId, sessionId).first()
        if (applyUnreadDecision(serverId, sessionId, anchor, messages)) {
            Log.i(TAG, "Session $sessionId unread on server $serverId (reply beyond read anchor)")
        }
    }

    /** Writes the derived unread state; returns true when the session is unread. */
    private suspend fun applyUnreadDecision(
        serverId: String,
        sessionId: String,
        anchor: String?,
        messages: List<Message>,
    ): Boolean {
        val partsByServer = eventReducer.serverParts.value[serverId].orEmpty()
        val readable = readableAssistantOutputIds(messages) { id -> partsByServer[id].orEmpty() }
        val unread = hasUnreadReplyBeyondReadAnchor(anchor, readable)
        if (unread) {
            sessionListPreferencesRepository.markConversationUnread(serverId, sessionId)
        } else {
            sessionListPreferencesRepository.markConversationRead(serverId, sessionId)
        }
        return unread
    }

    /**
     * Recompute unread marks for every root session of one server from the
     * current snapshots (issue #35: reconnect/restart/foreground reconcile).
     * Sessions whose known history may be stale — the session was updated after
     * the newest message we know, it went Busy/Retry→Idle through this snapshot,
     * or we only have an anchor and no history — get one bounded REST history
     * fetch merged into the reducer before evaluating. Sessions never seen on
     * this device (no anchor, no history) are skipped so old pre-install
     * sessions are not mass-marked unread; live SSE completions still cover them.
     */
    private suspend fun recomputeServerUnread(server: ServerConfig, previousStatuses: Map<String, SessionStatus>) {
        val details = eventReducer.serverSessionDetails.value[server.id].orEmpty()
        val statuses = eventReducer.serverSessionStatuses.value[server.id].orEmpty()
        for ((sessionId, session) in details) {
            if (session.parentId != null) continue
            if (eventReducer.isSessionVisible(server.id, sessionId)) continue
            // Only evaluate settled sessions; a busy session will be evaluated
            // again from live events when it goes idle.
            val status = statuses[sessionId]
            if (status is SessionStatus.Busy || status is SessionStatus.Retry) continue

            val anchor = sessionListPreferencesRepository.readAnchor(server.id, sessionId).first()
            val messages = eventReducer.serverMessages.value[server.id]?.get(sessionId)
            if (anchor == null && messages.isNullOrEmpty()) continue

            val transitionedToIdle = previousStatuses[sessionId] is SessionStatus.Busy ||
                previousStatuses[sessionId] is SessionStatus.Retry
            if (shouldFetchUnreadHistory(
                    sessionUpdated = session.time.updated,
                    knownMessages = messages.orEmpty(),
                    transitionedToIdle = transitionedToIdle,
                    lastFetchedSessionUpdated = lastUnreadFetchUpdated[unreadFetchKey(server.id, sessionId)],
                )
            ) {
                val fetched = fetchSessionHistory(server, session)
                if (fetched != null) {
                    eventReducer.mergeMessages(server.id, sessionId, fetched)
                    lastUnreadFetchUpdated[unreadFetchKey(server.id, sessionId)] = session.time.updated
                    val merged = eventReducer.serverMessages.value[server.id]?.get(sessionId).orEmpty()
                    applyUnreadDecision(server.id, sessionId, anchor, merged)
                }
                continue
            }
            if (messages != null) {
                applyUnreadDecision(server.id, sessionId, anchor, messages)
            }
        }
    }

    private suspend fun fetchSessionHistory(
        server: ServerConfig,
        session: Session,
    ): List<MessageWithParts>? = try {
        val conn = ServerConnection.from(server.url, server.username, server.password)
        api.listMessages(
            conn = conn,
            sessionId = session.id,
            limit = UNREAD_RECOMPUTE_MESSAGE_LIMIT,
            directory = session.directory.ifBlank { null },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "[${server.displayName}] Unread history fetch failed for ${session.id}: ${e.message}")
        null
    }

    // ============ Helpers ============

    private fun getSessionInfo(sessionId: String): Pair<String?, String?> {
        val session = eventReducer.sessions.value.find { it.id == sessionId }
        return Pair(session?.title, session?.directory)
    }

    private fun createTransport(server: ServerConfig): AgentTransport =
        OpenCodeTransport(server, api, sseClient)

    private fun List<TransportRoom>.openCodeSessions(): List<dev.wuxie233.codecarry.domain.model.Session> =
        mapNotNull { room -> (room as? TransportRoom.OpenCode)?.session }

    private fun handlePermissionAction(serverId: String, sessionId: String, requestId: String, replyValue: String) {
        if (replyValue !in setOf(PERMISSION_REPLY_ONCE, PERMISSION_REPLY_ALWAYS, PERMISSION_REPLY_REJECT)) {
            Log.w(TAG, "Ignoring unknown permission reply value for $requestId: $replyValue")
            return
        }

        val state = connections[serverId]
        if (state == null) {
            Log.w(TAG, "Ignoring permission reply for missing server $serverId (requestId=$requestId)")
            notificationManager.cancel(eventNotificationId(serverId, sessionId, 1000))
            return
        }

        val (_, directory) = getSessionInfo(sessionId)
        serviceScope.launch {
            val success = try {
                state.transport.replyToPermission(
                    requestId = requestId,
                    reply = replyValue,
                    directory = directory
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send permission reply for $requestId: ${e.message}", e)
                false
            }

            if (success) {
                eventReducer.removePermission(serverId, requestId)
                notificationManager.cancel(eventNotificationId(state.config.id, sessionId, 1000))
                Log.i(TAG, "Permission reply sent for $requestId: $replyValue")
            } else {
                Log.w(TAG, "Permission reply failed for $requestId; leaving chat fallback active")
            }
        }
    }

    private fun latestNotifiableAssistantMessageId(serverId: String, sessionId: String): String? {
        val sessionMessages = eventReducer.serverMessages.value[serverId]?.get(sessionId) ?: return null
        val latestAssistant = sessionMessages
            .asReversed()
            .firstOrNull { it is Message.Assistant } as? Message.Assistant ?: return null

        if (!latestAssistant.error?.message.isNullOrBlank()) return latestAssistant.id

        val parts = eventReducer.serverParts.value[serverId]?.get(latestAssistant.id) ?: return null
        val hasTextOutput = parts.any { part ->
            when (part) {
                is Part.Text -> part.text.isNotBlank()
                is Part.Reasoning -> part.text.isNotBlank()
                else -> false
            }
        }
        return if (hasTextOutput) latestAssistant.id else null
    }

    private fun getProjectName(directory: String?): String? {
        if (directory.isNullOrBlank()) return null
        return directory.trimEnd('/').substringAfterLast('/')
    }

    private fun base64UrlEncode(value: String): String {
        val encoded = android.util.Base64.encodeToString(
            value.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        return encoded
            .replace('+', '-')
            .replace('/', '_')
            .replace("=", "")
    }

    private fun buildSessionPath(sessionId: String): String? {
        val session = eventReducer.sessions.value.find { it.id == sessionId }
        if (session == null) {
            Log.w(TAG, "buildSessionPath: session $sessionId not found")
            return null
        }
        val encodedDir = base64UrlEncode(session.directory)
        return "/$encodedDir/session/$sessionId"
    }

    private fun createSessionPendingIntent(server: ServerConfig, sessionId: String?, requestCode: Int): PendingIntent {
        val sessionPath = sessionId?.let { buildSessionPath(it) }

        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_SESSION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SERVER_URL, server.url)
            putExtra(EXTRA_SERVER_USERNAME, server.username)
            putExtra(EXTRA_SERVER_PASSWORD, server.password ?: "")
            putExtra(EXTRA_SERVER_NAME, server.displayName)
            sessionPath?.let { putExtra(EXTRA_SESSION_PATH, it) }
            sessionId?.let { putExtra(EXTRA_SESSION_ID, it) }
        }

        return PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createAppPendingIntent(requestCode: Int): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun buildPermissionReplyPendingIntent(
        server: ServerConfig,
        sessionId: String,
        requestId: String,
        replyValue: String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(this, OpenCodeConnectionService::class.java).apply {
            action = ACTION_PERMISSION_REPLY
            putExtra(EXTRA_SERVER_ID, server.id)
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_PERMISSION_REQUEST_ID, requestId)
            putExtra(EXTRA_PERMISSION_REPLY_VALUE, replyValue)
        }

        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** Generate a stable notification ID for a server+session event type. */
    private fun eventNotificationId(serverId: String, sessionId: String, typeOffset: Int): Int {
        return SessionNotificationIdentity.eventId(serverId, sessionId, typeOffset)
    }

    companion object {
        const val EXTRA_THREAD_ID = "thread_id"
        const val ACTION_OPEN_CODEX_THREAD = "dev.wuxie233.codecarry.OPEN_CODEX_THREAD"
        const val ACTION_OPEN_SESSION = "dev.wuxie233.codecarry.OPEN_SESSION"
        const val ACTION_PERMISSION_REPLY = "dev.wuxie233.codecarry.PERMISSION_REPLY"
        const val ACTION_DISCONNECT = "dev.wuxie233.codecarry.DISCONNECT"
        const val ACTION_DISCONNECT_ALL = "dev.wuxie233.codecarry.DISCONNECT_ALL"
        const val EXTRA_SERVER_ID = "server_id"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_SERVER_USERNAME = "server_username"
        const val EXTRA_SERVER_PASSWORD = "server_password"
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_SESSION_PATH = "session_path"
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_PERMISSION_REQUEST_ID = "permission_request_id"
        const val EXTRA_PERMISSION_REPLY_VALUE = "permission_reply_value"
        const val PERMISSION_REPLY_ONCE = "once"
        const val PERMISSION_REPLY_ALWAYS = "always"
        const val PERMISSION_REPLY_REJECT = "reject"
    }

    // ============ Notification Channels ============

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val connectionChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_connection),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_connection_desc)
                setShowBadge(false)
            }

            val tasksChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_TASKS_ID,
                getString(R.string.notification_channel_tasks),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_tasks_desc)
                setShowBadge(true)
                enableVibration(true)
                enableLights(true)
            }

            val tasksSilentChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_TASKS_SILENT_ID,
                getString(R.string.notification_channel_tasks_silent),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_tasks_silent_desc)
                setShowBadge(true)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
            }

            val permissionsChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_PERMISSIONS_ID,
                getString(R.string.notification_channel_permissions),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_permissions_desc)
                setShowBadge(true)
                enableVibration(true)
                enableLights(true)
            }

            notificationManager.createNotificationChannel(connectionChannel)
            notificationManager.createNotificationChannel(tasksChannel)
            notificationManager.createNotificationChannel(tasksSilentChannel)
            notificationManager.createNotificationChannel(permissionsChannel)
        }
    }

    // ============ Persistent Notification (InboxStyle, multi-server) ============

    private fun isLocalServer(server: ServerConfig): Boolean {
        val normalizedUrl = server.url.trim().lowercase(Locale.US).removeSuffix("/")
        if (normalizedUrl == LocalServerManager.LOCAL_SERVER_URL.lowercase(Locale.US)) return true

        val host = server.host.lowercase(Locale.US)
        val port = server.port
        return (host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]") &&
            port == 4096
    }

    private fun createPersistentNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val tapPendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Disconnect All action
        val disconnectAllIntent = Intent(this, OpenCodeConnectionService::class.java).apply {
            action = ACTION_DISCONNECT_ALL
        }
        val disconnectAllPendingIntent = PendingIntent.getService(
            this, 1, disconnectAllIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val visibleConnections = connections.values
            .filterNot { isLocalServer(it.config) }
            .map { it.config to it.isConnected } +
            codexConnections.snapshot().values.map { owned ->
                owned.config to (owned.config.id in _connectedServerIds.value)
            }
        val serverCount = visibleConnections.size
        val connectedCount = visibleConnections.count { it.second }

        val title = if (serverCount == 0) {
            getString(R.string.app_name)
        } else if (serverCount == 1) {
            val server = visibleConnections.first()
            if (server.second) getString(R.string.notification_connected, server.first.displayName)
            else getString(R.string.notification_connecting, server.first.displayName)
        } else {
            getString(R.string.notification_connected_count, connectedCount, serverCount)
        }

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(title)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tapPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (serverCount > 0) {
            builder.addAction(
                R.mipmap.ic_launcher,
                getString(R.string.notification_disconnect_all),
                disconnectAllPendingIntent,
            )
        }

        // InboxStyle when multiple servers
        if (serverCount > 1) {
            val inboxStyle = NotificationCompat.InboxStyle()
                .setBigContentTitle(getString(R.string.notification_inbox_title, connectedCount, serverCount))
            for (state in visibleConnections) {
                val status = if (state.second) getString(R.string.notification_status_connected) else getString(R.string.notification_status_connecting)
                inboxStyle.addLine("${state.first.displayName}: $status")
            }
            builder.setStyle(inboxStyle)
        }

        return builder.build()
    }

    private fun updatePersistentNotification() {
        val notification = createPersistentNotification()
        notificationManager.notify(PERSISTENT_NOTIFICATION_ID, notification)
    }

    // ============ Event Notifications (grouped by server) ============

    private suspend fun showTaskCompleteNotification(server: ServerConfig, sessionId: String) {
        val (sessionTitle, _) = getSessionInfo(sessionId)
        val body = sessionTitle?.takeIf { it.isNotBlank() } ?: getString(R.string.notification_new_session)

        val pendingIntent = createSessionPendingIntent(server, sessionId, sessionId.hashCode())

        val silent = settingsRepository.silentNotifications.first()
        val channelId = if (silent) NOTIFICATION_CHANNEL_TASKS_SILENT_ID else NOTIFICATION_CHANNEL_TASKS_ID

        val notifId = SessionNotificationIdentity.responseReadyId(server.id, sessionId)
        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notification_response_ready))
            .setContentText(body)
            .setSubText(server.displayName)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setGroup(SessionNotificationIdentity.serverGroup(server.id))

        if (!silent) {
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
                .setVibrate(longArrayOf(0, 500, 200, 500))
        }

        notificationManager.notify(notifId, builder.build())
        showServerGroupSummary(server)
    }

    private fun showPermissionNotification(server: ServerConfig, sessionId: String, requestId: String, permission: String) {
        val (sessionTitle, directory) = getSessionInfo(sessionId)
        val displayTitle = sessionTitle ?: getString(R.string.notification_new_session)
        val projectName = getProjectName(directory)
        val body = if (projectName != null) {
            getString(R.string.notification_needs_permission_project, displayTitle, projectName)
        } else {
            getString(R.string.notification_needs_permission, displayTitle)
        }

        val notifId = eventNotificationId(server.id, sessionId, 1000)
        val pendingIntent = createSessionPendingIntent(server, sessionId, notifId)

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_PERMISSIONS_ID)
            .setContentTitle(getString(R.string.notification_permission_required))
            .setContentText(body)
            .setSubText(server.displayName)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 300, 100, 300))
            .setGroup(SessionNotificationIdentity.serverGroup(server.id))
            .addAction(
                R.mipmap.ic_launcher,
                getString(R.string.notification_permission_action_allow_once),
                buildPermissionReplyPendingIntent(server, sessionId, requestId, PERMISSION_REPLY_ONCE, notifId + 1)
            )
            .addAction(
                R.mipmap.ic_launcher,
                getString(R.string.notification_permission_action_allow_always),
                buildPermissionReplyPendingIntent(server, sessionId, requestId, PERMISSION_REPLY_ALWAYS, notifId + 2)
            )
            .addAction(
                R.mipmap.ic_launcher,
                getString(R.string.notification_permission_action_reject),
                buildPermissionReplyPendingIntent(server, sessionId, requestId, PERMISSION_REPLY_REJECT, notifId + 3)
            )
            .build()

        notificationManager.notify(notifId, notification)
        showServerGroupSummary(server)
    }

    private fun showQuestionNotification(server: ServerConfig, sessionId: String, questionText: String) {
        val (sessionTitle, directory) = getSessionInfo(sessionId)
        val displayTitle = sessionTitle ?: getString(R.string.notification_new_session)
        val projectName = getProjectName(directory)
        val body = if (projectName != null) {
            getString(R.string.notification_has_question_project, displayTitle, projectName)
        } else {
            getString(R.string.notification_has_question, displayTitle)
        }

        val notifId = eventNotificationId(server.id, sessionId, 2000)
        val pendingIntent = createSessionPendingIntent(server, sessionId, notifId)

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_PERMISSIONS_ID)
            .setContentTitle(getString(R.string.notification_question))
            .setContentText(body)
            .setSubText(server.displayName)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 300, 100, 300))
            .setGroup(SessionNotificationIdentity.serverGroup(server.id))
            .build()

        notificationManager.notify(notifId, notification)
        showServerGroupSummary(server)
    }

    private fun showErrorNotification(server: ServerConfig, sessionId: String?, error: String) {
        val body = if (sessionId != null) {
            val (sessionTitle, _) = getSessionInfo(sessionId)
            sessionTitle ?: error.ifBlank { getString(R.string.error_unknown) }
        } else {
            error.ifBlank { getString(R.string.error_unknown) }
        }

        val notifId = eventNotificationId(server.id, sessionId ?: "error", 3000)
        val pendingIntent = createSessionPendingIntent(server, sessionId, notifId)

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_TASKS_ID)
            .setContentTitle(getString(R.string.notification_session_error))
            .setContentText(body)
            .setSubText(server.displayName)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setGroup(SessionNotificationIdentity.serverGroup(server.id))
            .build()

        notificationManager.notify(notifId, notification)
        showServerGroupSummary(server)
    }

    private fun showServerGroupSummary(server: ServerConfig) {
        val summaryId = SessionNotificationIdentity.serverSummaryId(server.id)
        val summary = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_TASKS_SILENT_ID)
            .setContentTitle(server.displayName)
            .setContentText(getString(R.string.notification_group_summary))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setGroup(SessionNotificationIdentity.serverGroup(server.id))
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(summaryId, summary)
    }
}
