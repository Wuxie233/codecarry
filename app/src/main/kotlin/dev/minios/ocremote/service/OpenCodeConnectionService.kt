package dev.minios.ocremote.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.MainActivity
import dev.minios.ocremote.R
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.PiApi
import dev.minios.ocremote.data.api.SseClient
import dev.minios.ocremote.data.codex.CodexClientConnectionState
import dev.minios.ocremote.data.codex.CodexConnectionManager
import dev.minios.ocremote.data.codex.CodexManagedConnection
import dev.minios.ocremote.data.codex.CodexServerRequest
import dev.minios.ocremote.data.codex.CodexThreadKey
import dev.minios.ocremote.data.codex.ScopedCodexNotification
import dev.minios.ocremote.data.codex.requestKey
import dev.minios.ocremote.data.preferences.SessionListPreferencesRepository
import dev.minios.ocremote.data.repository.EventReducer
import dev.minios.ocremote.data.repository.LocalServerManager
import dev.minios.ocremote.data.repository.ServerRepository
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.data.transport.OpenCodeTransport
import dev.minios.ocremote.data.transport.PiRoundtableTransport
import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.ConnectionPhase
import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.model.ServerType
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.model.SseEvent
import dev.minios.ocremote.domain.transport.AgentTransport
import dev.minios.ocremote.domain.transport.PiTransportEvent
import dev.minios.ocremote.domain.transport.TransportEvent
import dev.minios.ocremote.domain.transport.TransportRoom
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
import kotlinx.serialization.json.Json

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

/**
 * Per-server connection state held by the service.
 */
private data class ServerConnectionState(
    val config: ServerConfig,
    val transport: AgentTransport,
    val sseJob: Job,
    val isConnected: Boolean = false
)

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
    val statuses = loadSessionStatusSnapshot(transport, projectDirectories)
    setSessionStatuses(serverId, statuses)
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
    lateinit var piApi: PiApi

    @Inject
    lateinit var sseClient: SseClient

    @Inject
    lateinit var json: Json

    @Inject
    lateinit var eventReducer: EventReducer

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var sessionListPreferencesRepository: SessionListPreferencesRepository

    @Inject
    lateinit var serverRepository: ServerRepository

    @Inject
    lateinit var codexConnectionManager: CodexConnectionManager

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** All active/pending server connections keyed by serverId. */
    private val connections = mutableMapOf<String, ServerConnectionState>()
    private val codexConnections = CodexOwnershipRegistry()
    private val codexConnectionErrors = ConcurrentHashMap<String, String>()

    private var notificationWatchdogJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var notificationManager: NotificationManager
    private var foregroundStarted: Boolean = false

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

    /** Dedup response-ready notifications per session by last assistant message ID. */
    private val lastNotifiedAssistantMessageBySession = ConcurrentHashMap<String, String>()
    private val postedPiNotificationKeys = ConcurrentHashMap.newKeySet<String>()

    /** Dedup permission notifications fired from the connect-time bootstrap, keyed by request ID. */
    private val postedPermissionRequestIds = ConcurrentHashMap.newKeySet<String>()
    private val handledCodexTurns = BoundedNotificationKeys(capacity = 512)
    private val postedCodexRequestIds = ConcurrentHashMap<String, Int>()
    private val manuallyDisconnectedServerIds = ConcurrentHashMap.newKeySet<String>()

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
        val serverIds = connections.keys.toList() + codexOwners.map { it.config.id }
        connections.clear()
        codexOwners.forEach { owner ->
            owner.connectJob.cancel()
            codexConnectionManager.releasePersistent(owner.config.id)
        }
        codexConnectionErrors.clear()
        cancelAllCodexRequestNotifications()

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
                it.autoConnect
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
        val body = thread?.name?.takeIf(String::isNotBlank)
            ?: thread?.preview?.lineSequence()?.firstOrNull()?.take(80)
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

    private fun hasManagedConnections(): Boolean = connections.isNotEmpty() || !codexConnections.isEmpty()

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
                val projects = try { transport.listRoomScopes() } catch (_: Exception) { emptyList() }
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

                updateConnectionPhase(server.id, ConnectionPhase.RestoringActivity)
                bootstrapSessionStatuses(
                    server = server,
                    transport = transport,
                    projectDirectories = projects.map { it.directory },
                )

                // Capture which permissions are carried over from a previous connection BEFORE
                // subscribing, so the permission reconcile can tell stale (replied while away)
                // from live (arriving on this fresh stream) apart.
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
                                if (connections[server.id]?.isConnected != true) {
                                    updateServerConnected(server.id, true)
                                    attempt = 0
                                    updatePersistentNotification()
                                    // Fetch the permission snapshot only once the stream is confirmed
                                    // open (first event received). Any permission asked after this point
                                    // arrives on the live stream, so fetching now closes the no-replay
                                    // gap a fetch-before-subscribe order would leave open.
                                    streamScope.launch {
                                        bootstrapPendingPermissions(server, transport, prePermissionIds)
                                    }
                                }
                                when (transportEvent) {
                                    is TransportEvent.OpenCode -> processEvent(server, transportEvent.event)
                                    is TransportEvent.Pi -> {
                                        eventReducer.processEvent(transportEvent, server.id)
                                        maybeShowPiNotification(server, transportEvent.event)
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
        val sessionIds = eventReducer.serverSessions.value[serverId] ?: emptySet()
        return eventReducer.permissions.value
            .filterKeys { it in sessionIds }
            .values
            .flatten()
            .mapTo(mutableSetOf()) { it.id }
    }

    /**
     * Pull current session statuses (retry/cooldown/busy) on (re)connect so they show
     * immediately instead of waiting for the next pushed SSE event. Runs before subscribing:
     * no SSE deltas exist yet, so the status reconcile (absent-from-snapshot -> idle) is safe,
     * and a status missed in the tiny window self-heals from the session's ongoing status events.
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
                    if (isChildSession(perm.sessionId)) continue
                    if (!postedPermissionRequestIds.add(perm.id)) continue
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
     */
    private fun isChildSession(sessionId: String): Boolean {
        val session = eventReducer.sessions.value.find { it.id == sessionId }
        return session?.parentId != null
    }

    private fun processEvent(server: ServerConfig, event: SseEvent) {
        if (BuildConfig.DEBUG) Log.d(TAG, "[${server.displayName}] SSE event: ${event.javaClass.simpleName}")

        val previousStatus = when (event) {
            is SseEvent.SessionStatus -> eventReducer.sessionStatuses.value[event.sessionId]
            is SseEvent.SessionIdle -> eventReducer.sessionStatuses.value[event.sessionId]
            else -> null
        }

        eventReducer.processEvent(event, server.id)

        when (event) {
            is SseEvent.SessionStatus -> {
                if (event.status is dev.minios.ocremote.domain.model.SessionStatus.Idle) {
                    maybeMarkSessionUnread(event.sessionId, previousStatus)
                }
            }
            is SseEvent.SessionIdle -> {
                maybeMarkSessionUnread(event.sessionId, previousStatus)
                if (isChildSession(event.sessionId)) return
                serviceScope.launch {
                    if (!settingsRepository.notificationsEnabled.first()) return@launch

                    // Give reducer a brief moment to receive trailing message/part events.
                    delay(250)
                    if (eventReducer.activeSessionId.value == event.sessionId) return@launch

                    val assistantMessageId = latestNotifiableAssistantMessageId(event.sessionId)
                    if (assistantMessageId == null) {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "[${server.displayName}] Skip response-ready: no assistant text output (${event.sessionId})")
                        }
                        return@launch
                    }

                    val previousNotified = lastNotifiedAssistantMessageBySession[event.sessionId]
                    if (previousNotified == assistantMessageId) {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "[${server.displayName}] Skip duplicate response-ready (${event.sessionId}, msg=$assistantMessageId)")
                        }
                        return@launch
                    }

                    lastNotifiedAssistantMessageBySession[event.sessionId] = assistantMessageId
                    Log.i(TAG, "[${server.displayName}] Session idle -> Response ready for ${event.sessionId}")
                        showTaskCompleteNotification(server, event.sessionId)
                }
            }
            is SseEvent.PermissionAsked -> {
                if (isChildSession(event.sessionId)) return
                if (!postedPermissionRequestIds.add(event.id)) return
                Log.i(TAG, "[${server.displayName}] Permission asked: ${event.permission} (id=${event.id})")
                showPermissionNotification(
                    server = server,
                    sessionId = event.sessionId,
                    requestId = event.id,
                    permission = event.permission
                )
            }
            is SseEvent.QuestionAsked -> {
                if (isChildSession(event.sessionId)) return
                Log.i(TAG, "[${server.displayName}] Question asked for session ${event.sessionId}")
                val questionText = event.questions.firstOrNull()?.question ?: getString(R.string.notification_has_question, getString(R.string.notification_new_session))
                showQuestionNotification(server, event.sessionId, questionText)
            }
            is SseEvent.SessionError -> {
                if (event.sessionId != null && isChildSession(event.sessionId)) return
                Log.i(TAG, "[${server.displayName}] Session error: ${event.error}")
                showErrorNotification(server, event.sessionId, event.error)
            }
            else -> { }
        }
    }

    private fun maybeShowPiNotification(server: ServerConfig, event: PiTransportEvent) {
        val decision = decidePiNotification(event) ?: return
        val scopedKey = "${server.id}:${decision.eventKey}"
        if (!postedPiNotificationKeys.add(scopedKey)) return

        serviceScope.launch {
            if (!settingsRepository.notificationsEnabled.first()) return@launch
            showPiRoundtableNotification(server, decision)
        }
    }

    private fun maybeMarkSessionUnread(sessionId: String, previousStatus: dev.minios.ocremote.domain.model.SessionStatus?) {
        if (isChildSession(sessionId)) return
        if (previousStatus !is dev.minios.ocremote.domain.model.SessionStatus.Busy && previousStatus !is dev.minios.ocremote.domain.model.SessionStatus.Retry) {
            return
        }
        if (eventReducer.activeSessionId.value == sessionId) return

        serviceScope.launch {
            sessionListPreferencesRepository.markMainSessionUnread(sessionId)
        }
    }

    // ============ Helpers ============

    private fun getSessionInfo(sessionId: String): Pair<String?, String?> {
        val session = eventReducer.sessions.value.find { it.id == sessionId }
        return Pair(session?.title, session?.directory)
    }

    private fun createTransport(server: ServerConfig): AgentTransport = when (server.type) {
        ServerType.OPENCODE -> OpenCodeTransport(server, api, sseClient)
        ServerType.CODEX -> error("Codex app-server connections are owned by CodexConnectionManager")
        ServerType.PI_ROUNDTABLE -> PiRoundtableTransport(server, piApi, json)
    }

    private fun List<TransportRoom>.openCodeSessions(): List<dev.minios.ocremote.domain.model.Session> =
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
                eventReducer.removePermission(requestId)
                notificationManager.cancel(eventNotificationId(state.config.id, sessionId, 1000))
                Log.i(TAG, "Permission reply sent for $requestId: $replyValue")
            } else {
                Log.w(TAG, "Permission reply failed for $requestId; leaving chat fallback active")
            }
        }
    }

    private fun latestNotifiableAssistantMessageId(sessionId: String): String? {
        val sessionMessages = eventReducer.messages.value[sessionId] ?: return null
        val latestAssistant = sessionMessages
            .asReversed()
            .firstOrNull { it is Message.Assistant } as? Message.Assistant ?: return null

        if (!latestAssistant.error?.message.isNullOrBlank()) return latestAssistant.id

        val parts = eventReducer.parts.value[latestAssistant.id] ?: return null
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
        const val ACTION_OPEN_SESSION = "dev.minios.ocremote.OPEN_SESSION"
        const val ACTION_OPEN_CODEX_THREAD = "dev.minios.ocremote.OPEN_CODEX_THREAD"
        const val ACTION_PERMISSION_REPLY = "dev.minios.ocremote.PERMISSION_REPLY"
        const val ACTION_DISCONNECT = "dev.minios.ocremote.DISCONNECT"
        const val ACTION_DISCONNECT_ALL = "dev.minios.ocremote.DISCONNECT_ALL"
        const val EXTRA_SERVER_ID = "server_id"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_SERVER_USERNAME = "server_username"
        const val EXTRA_SERVER_PASSWORD = "server_password"
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_SESSION_PATH = "session_path"
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_THREAD_ID = "thread_id"
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

    private suspend fun showPiRoundtableNotification(server: ServerConfig, decision: PiNotificationDecision) {
        val silent = settingsRepository.silentNotifications.first()
        val channelId = if (silent) NOTIFICATION_CHANNEL_TASKS_SILENT_ID else NOTIFICATION_CHANNEL_TASKS_ID
        val notifId = (server.id + decision.eventKey).hashCode()
        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(decision.title)
            .setContentText(decision.message)
            .setSubText(server.displayName)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(createAppPendingIntent(notifId))
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

    /**
     * Post a group summary notification for a server so Android bundles
     * event notifications from the same server together.
     */
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
