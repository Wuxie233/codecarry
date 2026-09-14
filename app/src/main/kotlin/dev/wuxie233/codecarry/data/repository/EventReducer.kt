package dev.wuxie233.codecarry.data.repository

import android.util.Log
import dev.wuxie233.codecarry.BuildConfig
import dev.wuxie233.codecarry.domain.model.*
import dev.wuxie233.codecarry.domain.transport.TransportEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "EventReducer"

data class SessionStatusBaseline(
    val serverId: String,
    val sessionIds: Set<String>,
    val conflictingSessionIds: Set<String>,
    val revisions: Map<String, Long?>,
)

/**
 * Event Reducer - processes SSE events and updates app state
 *
 * This is the central state management for the app.
 * All SSE events flow through here and mutate the reactive state.
 *
 * Supports multiple servers simultaneously. Session statuses, messages, and
 * message parts are scoped by serverId (`serverSessionStatuses`,
 * `serverMessages`, `serverParts`) because server-local session, message, and
 * part IDs are not unique across servers: duplicate IDs on two servers must
 * never overwrite each other, and per-server cleanup must not clear another
 * server's state. Pending user actions retain server ownership for the same
 * reason. The flat [sessions] list remains a cross-server aggregate; any
 * derivation that joins by session ID must first restrict IDs through the
 * server-scoped maps.
 *
 * Similar to the event-reducer.ts in the WebUI.
 */
@Singleton
class EventReducer @Inject constructor() {

    private val sessionStatusRevisions = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Long>()
    private val nextSessionStatusRevision = java.util.concurrent.atomic.AtomicLong()
    private val sessionStatusLock = Any()
    
    // ============ State ============
    
    /** Maps serverId → set of sessionIds belonging to that server */
    private val _serverSessions = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val serverSessions: StateFlow<Map<String, Set<String>>> = _serverSessions.asStateFlow()

    private val _serverSessionDetails = MutableStateFlow<Map<String, Map<String, Session>>>(emptyMap())
    val serverSessionDetails: StateFlow<Map<String, Map<String, Session>>> = _serverSessionDetails.asStateFlow()
    
    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()
    
    private val _serverSessionStatuses = MutableStateFlow<Map<String, Map<String, SessionStatus>>>(emptyMap())
    val serverSessionStatuses: StateFlow<Map<String, Map<String, SessionStatus>>> =
        _serverSessionStatuses.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    /**
     * Chat screens currently visible to the user, keyed by serverId. This follows
     * screen/app lifecycle (ON_START/ON_STOP), not ViewModel lifetime, so a
     * backgrounded chat with a live ViewModel does not suppress unread marks or
     * response notifications. A duplicate sessionId on another server never
     * matches here: visibility is checked per (serverId, sessionId) pair.
     */
    private val _visibleSessionsByServer = MutableStateFlow<Map<String, String>>(emptyMap())
    val visibleSessionsByServer: StateFlow<Map<String, String>> = _visibleSessionsByServer.asStateFlow()

    
    // serverId -> sessionId -> messages
    private val _serverMessages = MutableStateFlow<Map<String, Map<String, List<Message>>>>(emptyMap())
    val serverMessages: StateFlow<Map<String, Map<String, List<Message>>>> = _serverMessages.asStateFlow()
    
    // serverId -> messageId -> parts
    private val _serverParts = MutableStateFlow<Map<String, Map<String, List<Part>>>>(emptyMap())
    val serverParts: StateFlow<Map<String, Map<String, List<Part>>>> = _serverParts.asStateFlow()
    
    private val _sessionDiffs = MutableStateFlow<Map<String, List<FileDiff>>>(emptyMap())
    val sessionDiffs: StateFlow<Map<String, List<FileDiff>>> = _sessionDiffs.asStateFlow()
    
    private val _permissionsByServer = MutableStateFlow<Map<String, Map<String, List<SseEvent.PermissionAsked>>>>(emptyMap())
    val permissionsByServer: StateFlow<Map<String, Map<String, List<SseEvent.PermissionAsked>>>> =
        _permissionsByServer.asStateFlow()
    
    private val _questionsByServer = MutableStateFlow<Map<String, Map<String, List<SseEvent.QuestionAsked>>>>(emptyMap())
    val questionsByServer: StateFlow<Map<String, Map<String, List<SseEvent.QuestionAsked>>>> =
        _questionsByServer.asStateFlow()
    
    private val _todos = MutableStateFlow<Map<String, List<SseEvent.TodoUpdated.Todo>>>(emptyMap())
    val todos: StateFlow<Map<String, List<SseEvent.TodoUpdated.Todo>>> = _todos.asStateFlow()
    
    private val _vcsBranch = MutableStateFlow<String?>(null)
    val vcsBranch: StateFlow<String?> = _vcsBranch.asStateFlow()
    
    private val _projectInfo = MutableStateFlow<Project?>(null)
    val projectInfo: StateFlow<Project?> = _projectInfo.asStateFlow()

    // ============ Event Processing ============
    
    /**
     * Process an SSE event and update state.
     * @param event The SSE event to process
     * @param serverId The server this event came from (used for session tracking)
     */
    fun processEvent(event: SseEvent, serverId: String) {
        when (event) {
            is SseEvent.ServerConnected -> handleServerConnected()
            is SseEvent.ServerHeartbeat -> { /* No-op */ }
            is SseEvent.ServerInstanceDisposed -> handleServerInstanceDisposed(event)
            
            is SseEvent.SessionCreated -> handleSessionCreated(event, serverId)
            is SseEvent.SessionUpdated -> handleSessionUpdated(event, serverId)
            is SseEvent.SessionDeleted -> handleSessionDeleted(event, serverId)
            is SseEvent.SessionStatus -> handleSessionStatus(event, serverId)
            is SseEvent.SessionIdle -> handleSessionIdle(event, serverId)
            is SseEvent.SessionDiff -> handleSessionDiff(event)
            is SseEvent.SessionError -> handleSessionError(event)
            
            is SseEvent.MessageUpdated -> handleMessageUpdated(event, serverId)
            is SseEvent.MessageRemoved -> handleMessageRemoved(event, serverId)
            
            is SseEvent.MessagePartUpdated -> handleMessagePartUpdated(event, serverId)
            is SseEvent.MessagePartDelta -> handleMessagePartDelta(event, serverId)
            is SseEvent.MessagePartRemoved -> handleMessagePartRemoved(event, serverId)
            
            is SseEvent.PermissionAsked -> handlePermissionAsked(event, serverId)
            is SseEvent.PermissionReplied -> handlePermissionReplied(event, serverId)
            
            is SseEvent.QuestionAsked -> handleQuestionAsked(event, serverId)
            is SseEvent.QuestionReplied -> handleQuestionReplied(event, serverId)
            is SseEvent.QuestionRejected -> handleQuestionRejected(event, serverId)
            
            is SseEvent.TodoUpdated -> handleTodoUpdated(event)
            is SseEvent.VcsBranchUpdated -> handleVcsBranchUpdated(event)
            is SseEvent.LspUpdated -> { /* LSP events not needed in mobile */ }
            is SseEvent.ProjectUpdated -> handleProjectUpdated(event)
        }
    }

    fun processEvent(event: TransportEvent, serverId: String) {
        when (event) {
            is TransportEvent.OpenCode -> processEvent(event.event, serverId)
        }
    }

    private fun handleServerConnected() {
        if (BuildConfig.DEBUG) Log.d(TAG, "Server connected")
    }
    
    private fun handleServerInstanceDisposed(event: SseEvent.ServerInstanceDisposed) {
        if (BuildConfig.DEBUG) Log.d(TAG, "Server instance disposed: ${event.directory}")
        // State cleanup for the directory is handled by clearForServer() on disconnect
    }
    
    // ============ Session Events ============
    
    private fun handleSessionCreated(event: SseEvent.SessionCreated, serverId: String) {
        trackSession(serverId, event.info.id)
        upsertServerSession(serverId, event.info)
        _sessions.update { current ->
            val existingIndex = current.indexOfFirst { it.id == event.info.id }
            if (existingIndex >= 0) {
                current.toMutableList()
                    .apply { set(existingIndex, event.info) }
                    .sortedByDescending { it.time.updated }
            } else {
                (current + event.info).sortedByDescending { it.time.updated }
            }
        }
        synchronized(sessionStatusLock) {
            recordSessionStatusChange(serverId, event.info.id)
            _serverSessionStatuses.update { current ->
                val serverStatuses = current[serverId].orEmpty()
                if (event.info.id in serverStatuses) current
                else current + (serverId to (serverStatuses + (event.info.id to SessionStatus.Idle)))
            }
        }
    }
    
    private fun handleSessionUpdated(event: SseEvent.SessionUpdated, serverId: String) {
        trackSession(serverId, event.info.id)
        upsertServerSession(serverId, event.info)
        _sessions.update { current ->
            val existingIndex = current.indexOfFirst { it.id == event.info.id }
            if (existingIndex >= 0) {
                // Update existing
                current.toMutableList()
                    .apply { set(existingIndex, event.info) }
                    .sortedByDescending { it.time.updated }
            } else {
                // Upsert: session wasn't in list (no session.created received), add it
                if (BuildConfig.DEBUG) Log.d(TAG, "Session ${event.info.id} not found, upserting (title=${event.info.title})")
                (current + event.info).sortedByDescending { it.time.updated }
            }
        }
    }
    
    /** Register a session as belonging to a server */
    private fun trackSession(serverId: String, sessionId: String) {
        _serverSessions.update { current ->
            val existing = current[serverId] ?: emptySet()
            current + (serverId to (existing + sessionId))
        }
    }

    private fun upsertServerSession(serverId: String, session: Session) {
        _serverSessionDetails.update { current ->
            current + (serverId to (current[serverId].orEmpty() + (session.id to session)))
        }
    }
    
    private fun handleSessionDeleted(event: SseEvent.SessionDeleted, serverId: String) {
        val sessionId = event.info.id
        _permissionsByServer.update { current -> removeServerSessionRequests(current, serverId, sessionId) }
        _questionsByServer.update { current -> removeServerSessionRequests(current, serverId, sessionId) }
        _serverSessions.update { current ->
            val remaining = current[serverId].orEmpty() - sessionId
            if (remaining.isEmpty()) current - serverId else current + (serverId to remaining)
        }
        _serverSessionDetails.update { current ->
            val remaining = current[serverId].orEmpty() - sessionId
            if (remaining.isEmpty()) current - serverId else current + (serverId to remaining)
        }
        // Server-scoped live state: removing this server's copy never touches
        // another server's state for the same session ID.
        removeServerSessionLiveState(serverId, sessionId)
        val ownedByAnotherServer = _serverSessions.value.any { (ownerId, ids) ->
            ownerId != serverId && sessionId in ids
        }
        if (ownedByAnotherServer) return
        _sessions.update { it.filter { session -> session.id != sessionId } }
        _sessionDiffs.update { it - sessionId }
    }

    /**
     * Remove one server's own status, messages, and their parts for a session.
     * Other servers keep their copies even when they track the same IDs.
     */
    private fun removeServerSessionLiveState(serverId: String, sessionId: String) {
        synchronized(sessionStatusLock) {
            recordSessionStatusChange(serverId, sessionId)
            _serverSessionStatuses.update { current -> removeServerScopedEntry(current, serverId, sessionId) }
        }
        val messageIds = _serverMessages.value[serverId]?.get(sessionId).orEmpty().map { it.id }.toSet()
        _serverMessages.update { current -> removeServerScopedEntry(current, serverId, sessionId) }
        if (messageIds.isEmpty()) return
        _serverParts.update { current ->
            val remaining = current[serverId].orEmpty() - messageIds
            if (remaining.isEmpty()) current - serverId else current + (serverId to remaining)
        }
    }

    /** Remove [key] from the server's inner map, dropping the server bucket when it becomes empty. */
    private fun <K, V> removeServerScopedEntry(
        current: Map<String, Map<K, V>>,
        serverId: String,
        key: K,
    ): Map<String, Map<K, V>> {
        val serverMap = current[serverId] ?: return current
        val updated = serverMap - key
        return if (updated.isEmpty()) current - serverId else current + (serverId to updated)
    }
    
    private fun handleSessionStatus(event: SseEvent.SessionStatus, serverId: String) {
        trackSession(serverId, event.sessionId)
        synchronized(sessionStatusLock) {
            recordSessionStatusChange(serverId, event.sessionId)
            _serverSessionStatuses.update { current ->
                current + (serverId to (current[serverId].orEmpty() + (event.sessionId to event.status)))
            }
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Session ${event.sessionId} status: ${event.status}")
    }
    
    private fun handleSessionIdle(event: SseEvent.SessionIdle, serverId: String) {
        trackSession(serverId, event.sessionId)
        synchronized(sessionStatusLock) {
            recordSessionStatusChange(serverId, event.sessionId)
            _serverSessionStatuses.update { current ->
                current + (serverId to (current[serverId].orEmpty() + (event.sessionId to SessionStatus.Idle)))
            }
        }
    }
    
    private fun handleSessionDiff(event: SseEvent.SessionDiff) {
        _sessionDiffs.update { it + (event.sessionId to event.diff) }
    }
    
    private fun handleSessionError(event: SseEvent.SessionError) {
        Log.e(TAG, "Session ${event.sessionId} error: ${event.error}")
    }
    
    // ============ Message Events ============
    
    private fun handleMessageUpdated(event: SseEvent.MessageUpdated, serverId: String) {
        val sessionId = event.info.sessionId
        _serverMessages.update { current -> upsertMessage(current, serverId, sessionId, event.info) }
    }
    
    private fun handleMessageRemoved(event: SseEvent.MessageRemoved, serverId: String) {
        _serverMessages.update { current ->
            val sessionMessages = current[serverId]?.get(event.sessionId)?.filter { it.id != event.messageId }
            if (sessionMessages != null) {
                current + (serverId to (current[serverId].orEmpty() + (event.sessionId to sessionMessages)))
            } else {
                current
            }
        }
        _serverParts.update { current -> removeServerScopedEntry(current, serverId, event.messageId) }
    }
    
    // ============ Part Events ============

    private fun Part.isRenderablePart(): Boolean {
        return this !is Part.Tool || (callId.isNotBlank() && tool.isNotBlank())
    }

    private fun handleMessagePartUpdated(event: SseEvent.MessagePartUpdated, serverId: String) {
        if (!event.part.isRenderablePart()) return

        _serverParts.update { current -> upsertPart(current, serverId, event.part) }
    }
    
    private fun handleMessagePartDelta(event: SseEvent.MessagePartDelta, serverId: String) {
        // Append text delta to existing part
        _serverParts.update { current -> appendPartDelta(current, serverId, event.messageId, event.partId, event.delta) }
    }

    private fun upsertMessage(
        current: Map<String, Map<String, List<Message>>>,
        serverId: String,
        conversationId: String,
        message: Message,
    ): Map<String, Map<String, List<Message>>> {
        val serverMessages = current[serverId].orEmpty()
        val conversationMessages = serverMessages[conversationId]?.toMutableList() ?: mutableListOf()
        val existingIndex = conversationMessages.indexOfFirst { it.id == message.id }

        if (existingIndex >= 0) {
            conversationMessages[existingIndex] = message
        } else {
            conversationMessages.add(message)
            conversationMessages.sortBy { it.time.created }
        }

        return current + (serverId to (serverMessages + (conversationId to conversationMessages)))
    }

    private fun upsertPart(
        current: Map<String, Map<String, List<Part>>>,
        serverId: String,
        part: Part,
    ): Map<String, Map<String, List<Part>>> {
        if (!part.isRenderablePart()) return current
        val serverParts = current[serverId].orEmpty()
        val messageParts = serverParts[part.messageId]?.toMutableList() ?: mutableListOf()
        val existingIndex = messageParts.indexOfFirst { it.id == part.id }

        if (existingIndex >= 0) {
            messageParts[existingIndex] = part
        } else {
            messageParts.add(part)
        }

        return current + (serverId to (serverParts + (part.messageId to messageParts)))
    }

    private fun appendPartDelta(
        current: Map<String, Map<String, List<Part>>>,
        serverId: String,
        messageId: String,
        partId: String,
        delta: String,
    ): Map<String, Map<String, List<Part>>> {
        val serverParts = current[serverId] ?: return current
        val messageParts = serverParts[messageId]?.toMutableList() ?: return current
        val partIndex = messageParts.indexOfFirst { it.id == partId }

        if (partIndex < 0) return current

        val part = messageParts[partIndex]
        val updatedPart = when (part) {
            is Part.Text -> part.copy(text = part.text + delta)
            is Part.Reasoning -> part.copy(text = part.text + delta)
            else -> part
        }

        messageParts[partIndex] = updatedPart
        return current + (serverId to (serverParts + (messageId to messageParts)))
    }
    
    private fun handleMessagePartRemoved(event: SseEvent.MessagePartRemoved, serverId: String) {
        _serverParts.update { current ->
            val messageParts = current[serverId]?.get(event.messageId)?.filter { it.id != event.partId }
            if (messageParts != null) {
                current + (serverId to (current[serverId].orEmpty() + (event.messageId to messageParts)))
            } else {
                current
            }
        }
    }
    
    // ============ Permission Events ============
    
    private fun handlePermissionAsked(event: SseEvent.PermissionAsked, serverId: String) {
        _permissionsByServer.update { current ->
            val serverPermissions = current[serverId].orEmpty()
            val sessionPermissions = serverPermissions[event.sessionId]?.toMutableList() ?: mutableListOf()
            if (sessionPermissions.any { it.id == event.id }) return@update current
            sessionPermissions.add(event)
            current + (serverId to (serverPermissions + (event.sessionId to sessionPermissions)))
        }
    }
    
    private fun handlePermissionReplied(event: SseEvent.PermissionReplied, serverId: String) {
        _permissionsByServer.update { current ->
            updateServerSessionRequests(current, serverId, event.sessionId) { permissions ->
                permissions.filter { it.id != event.requestId }
            }
        }
    }

    /**
     * Optimistically remove a permission request from the pending list.
     * Called after a successful service-side or chat-side reply, in case the
     * SSE `permission.replied` event arrives late or is missed entirely
     * (e.g. when the user replies via a notification action while the chat
     * screen is closed).
     *
     * Idempotent: removing an already-removed request is a no-op.
     */
    fun removePermission(serverId: String, requestId: String) {
        _permissionsByServer.update { current ->
            val serverPermissions = current[serverId] ?: return@update current
            val updated = serverPermissions.mapValues { (_, permissions) ->
                permissions.filter { it.id != requestId }
            }.filterValues { it.isNotEmpty() }
            if (updated.isEmpty()) current - serverId else current + (serverId to updated)
        }
    }

    /**
     * Additively merge a REST permission snapshot into a session's pending list, deduped by
     * request ID. Used when opening a session: it surfaces permissions asked before open without
     * ever wiping a permission that arrived concurrently via SSE.
     */
    fun mergePermissions(serverId: String, sessionId: String, permissions: List<SseEvent.PermissionAsked>) {
        if (permissions.isEmpty()) return
        _permissionsByServer.update { current ->
            val serverPermissions = current[serverId].orEmpty()
            val existing = serverPermissions[sessionId]?.toMutableList() ?: mutableListOf()
            val existingIds = existing.mapTo(mutableSetOf()) { it.id }
            for (permission in permissions) {
                if (existingIds.add(permission.id)) existing.add(permission)
            }
            current + (serverId to (serverPermissions + (sessionId to existing)))
        }
    }

    /**
     * Reconcile a server's pending permissions against a full REST snapshot (GET /permission) on
     * connect/reconnect. Adds snapshot entries, keeps permissions that arrived live during the
     * bootstrap window (ids not in [preExistingIds]), and drops stale pre-existing permissions that
     * the snapshot no longer lists (replied while the client was disconnected). [preExistingIds] is
     * the set of permission ids already held for this server when the bootstrap began.
     */
    fun reconcilePermissions(
        serverId: String,
        snapshot: List<SseEvent.PermissionAsked>,
        preExistingIds: Set<String>,
    ) {
        val snapshotIds = snapshot.mapTo(mutableSetOf()) { it.id }
        val snapshotBySession = snapshot.groupBy { it.sessionId }
        val serverSessionIds = _serverSessions.value[serverId] ?: emptySet()
        val sessionsToReconcile = serverSessionIds + snapshotBySession.keys
        _permissionsByServer.update { current ->
            val next = current[serverId].orEmpty().toMutableMap()
            for (sessionId in sessionsToReconcile) {
                val existing = next[sessionId].orEmpty()
                val keptIds = mutableSetOf<String>()
                val kept = existing.filter { (it.id in snapshotIds || it.id !in preExistingIds) && keptIds.add(it.id) }
                val additions = snapshotBySession[sessionId].orEmpty().filter { keptIds.add(it.id) }
                val merged = kept + additions
                if (merged.isEmpty()) next.remove(sessionId) else next[sessionId] = merged
            }
            if (next.isEmpty()) current - serverId else current + (serverId to next)
        }
    }
    
    // ============ Question Events ============
    
    private fun handleQuestionAsked(event: SseEvent.QuestionAsked, serverId: String) {
        _questionsByServer.update { current ->
            val serverQuestions = current[serverId].orEmpty()
            val sessionQuestions = serverQuestions[event.sessionId]?.toMutableList() ?: mutableListOf()
            if (sessionQuestions.any { it.id == event.id }) return@update current
            sessionQuestions.add(event)
            current + (serverId to (serverQuestions + (event.sessionId to sessionQuestions)))
        }
    }
    
    private fun handleQuestionReplied(event: SseEvent.QuestionReplied, serverId: String) {
        _questionsByServer.update { current ->
            updateServerSessionRequests(current, serverId, event.sessionId) { questions ->
                questions.filter { it.id != event.requestId }
            }
        }
    }
    
    private fun handleQuestionRejected(event: SseEvent.QuestionRejected, serverId: String) {
        _questionsByServer.update { current ->
            updateServerSessionRequests(current, serverId, event.sessionId) { questions ->
                questions.filter { it.id != event.requestId }
            }
        }
    }

    /**
     * Optimistically remove a question from the pending list.
     * Called after a successful API reply/reject, in case the SSE event doesn't arrive.
     */
    fun removeQuestion(serverId: String, questionId: String) {
        _questionsByServer.update { current ->
            val serverQuestions = current[serverId] ?: return@update current
            val updated = serverQuestions.mapValues { (_, questions) ->
                questions.filter { it.id != questionId }
            }.filterValues { it.isNotEmpty() }
            if (updated.isEmpty()) current - serverId else current + (serverId to updated)
        }
    }

    /**
     * Set pending questions for a session (loaded from REST API on session open).
     */
    fun setQuestions(serverId: String, sessionId: String, questions: List<SseEvent.QuestionAsked>) {
        _questionsByServer.update { current ->
            val serverQuestions = current[serverId].orEmpty()
            if (serverQuestions[sessionId].orEmpty() == questions) return@update current
            val updated = if (questions.isEmpty()) serverQuestions - sessionId else serverQuestions + (sessionId to questions)
            if (updated.isEmpty()) current - serverId else current + (serverId to updated)
        }
    }

    fun setPermissions(serverId: String, sessionId: String, permissions: List<SseEvent.PermissionAsked>) {
        _permissionsByServer.update { current ->
            val serverPermissions = current[serverId].orEmpty()
            if (serverPermissions[sessionId].orEmpty() == permissions) return@update current
            val updated = if (permissions.isEmpty()) {
                serverPermissions - sessionId
            } else {
                serverPermissions + (sessionId to permissions)
            }
            if (updated.isEmpty()) current - serverId else current + (serverId to updated)
        }
    }

    private fun <T> updateServerSessionRequests(
        current: Map<String, Map<String, List<T>>>,
        serverId: String,
        sessionId: String,
        transform: (List<T>) -> List<T>,
    ): Map<String, Map<String, List<T>>> {
        val serverRequests = current[serverId] ?: return current
        val sessionRequests = serverRequests[sessionId] ?: return current
        val updatedSession = transform(sessionRequests)
        val updatedServer = if (updatedSession.isEmpty()) {
            serverRequests - sessionId
        } else {
            serverRequests + (sessionId to updatedSession)
        }
        return if (updatedServer.isEmpty()) current - serverId else current + (serverId to updatedServer)
    }

    private fun <T> removeServerSessionRequests(
        current: Map<String, Map<String, List<T>>>,
        serverId: String,
        sessionId: String,
    ): Map<String, Map<String, List<T>>> {
        val updatedServer = current[serverId].orEmpty() - sessionId
        return if (updatedServer.isEmpty()) current - serverId else current + (serverId to updatedServer)
    }
    
    // ============ Batch Updates ============
    
    /**
     * Load initial session list for a server.
     * Registers all session IDs as belonging to the given serverId.
     */
    fun replaceSessions(serverId: String, sessions: List<Session>) {
        val previousIds = _serverSessions.value[serverId].orEmpty()
        val sessionIds = sessions.map { it.id }.toSet()
        val nextDetails = sessions.associateBy(Session::id)
        val currentDetails = _serverSessionDetails.value[serverId].orEmpty()
        val idsUnchanged = previousIds == sessionIds
        val detailsUnchanged = idsUnchanged && currentDetails.size == nextDetails.size &&
            nextDetails.all { (id, session) -> currentDetails[id] == session }
        if (!idsUnchanged) {
            _serverSessions.update { current -> current + (serverId to sessionIds) }
        }
        if (!detailsUnchanged) {
            _serverSessionDetails.update { current ->
                current + (serverId to nextDetails)
            }
        }
        _sessions.update { current ->
            val kept = current.filterNot { it.id in previousIds || it.id in sessionIds }
            val merged = (kept + sessions).distinctBy { it.id }.sortedByDescending { it.time.updated }
            if (merged == current) current else merged
        }
    }

    fun setSessions(serverId: String, sessions: List<Session>) {
        val sessionIds = sessions.map { it.id }.toSet()
        _serverSessions.update { current ->
            val existing = current[serverId] ?: emptySet()
            current + (serverId to (existing + sessionIds))
        }
        _serverSessionDetails.update { current ->
            current + (serverId to (current[serverId].orEmpty() + sessions.associateBy(Session::id)))
        }
        _sessions.update { current ->
            // Merge: replace existing sessions by ID, add new ones
            val updated = current.toMutableList()
            for (session in sessions) {
                val idx = updated.indexOfFirst { it.id == session.id }
                if (idx >= 0) {
                    updated[idx] = session
                } else {
                    updated.add(session)
                }
            }
            updated.sortedByDescending { it.time.updated }
        }
    }

    /**
     * Manually update the session status for one server.
     * Useful for optimistic updates (e.g. aborting a session).
     */
    fun updateSessionStatus(serverId: String, sessionId: String, status: SessionStatus) {
        synchronized(sessionStatusLock) {
            if (_serverSessionStatuses.value[serverId]?.get(sessionId) == status) return
            recordSessionStatusChange(serverId, sessionId)
            _serverSessionStatuses.update { current ->
                current + (serverId to (current[serverId].orEmpty() + (sessionId to status)))
            }
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Manually updated session $sessionId status to $status on $serverId")
    }

    /**
     * Reconcile session statuses from a REST snapshot (GET /session/status) on connect/reconnect.
     * The snapshot only lists non-idle sessions, so this server's tracked sessions that are
     * absent from [statuses] are reset to Idle (they finished while the client was disconnected).
     * Other servers' sessions are left untouched.
     */
    fun setSessionStatuses(serverId: String, statuses: Map<String, SessionStatus>) {
        reconcileSessionStatuses(
            statuses = statuses,
            baseline = captureSessionStatusBaseline(serverId),
        )
    }

    fun captureSessionStatusBaseline(serverId: String): SessionStatusBaseline {
        val serverSessionIds = _serverSessions.value[serverId] ?: emptySet()
        val conflictingSessionIds = _serverSessions.value
            .filterKeys { it != serverId }
            .values
            .flatten()
            .toSet()
            .intersect(serverSessionIds)
        return synchronized(sessionStatusLock) {
            SessionStatusBaseline(
                serverId = serverId,
                sessionIds = serverSessionIds,
                conflictingSessionIds = conflictingSessionIds,
                revisions = serverSessionIds.associateWith { sessionStatusRevisions[serverId to it] },
            )
        }
    }

    fun reconcileSessionStatuses(
        statuses: Map<String, SessionStatus>,
        baseline: SessionStatusBaseline,
    ) {
        val targetSessionIds = baseline.sessionIds + statuses.keys
        synchronized(sessionStatusLock) {
            _serverSessionStatuses.update { current ->
                val serverStatuses = current[baseline.serverId].orEmpty().toMutableMap()
                for (sessionId in targetSessionIds) {
                    if (sessionId in baseline.conflictingSessionIds) continue
                    val owners = _serverSessions.value.filterValues { sessionId in it }.keys
                    if (owners.any { it != baseline.serverId }) continue
                    if (sessionStatusRevisions[baseline.serverId to sessionId] != baseline.revisions[sessionId]) continue
                    serverStatuses[sessionId] = statuses[sessionId] ?: SessionStatus.Idle
                }
                if (serverStatuses.isEmpty()) current - baseline.serverId
                else current + (baseline.serverId to serverStatuses)
            }
        }
    }

    private fun recordSessionStatusChange(serverId: String, sessionId: String) {
        sessionStatusRevisions[serverId to sessionId] = nextSessionStatusRevision.incrementAndGet()
    }

    fun setActiveSessionId(sessionId: String?) {
        _activeSessionId.value = sessionId
    }

    fun clearActiveSessionId(sessionId: String) {
        _activeSessionId.update { activeSessionId ->
            if (activeSessionId == sessionId) null else activeSessionId
        }
    }

    /**
     * Mark the chat screen for (serverId, sessionId) as visible to the user.
     * Also mirrors the legacy global [activeSessionId] used by diagnostics.
     */
    fun setVisibleSession(serverId: String, sessionId: String) {
        _visibleSessionsByServer.update { it + (serverId to sessionId) }
        _activeSessionId.value = sessionId
    }

    /** Clear the visible mark for (serverId, sessionId) when its screen stops or is destroyed. */
    fun clearVisibleSession(serverId: String, sessionId: String) {
        _visibleSessionsByServer.update { current ->
            if (current[serverId] == sessionId) current - serverId else current
        }
        if (_activeSessionId.value == sessionId) {
            _activeSessionId.value = null
        }
    }

    /** True when the chat screen for this exact (serverId, sessionId) pair is visible. */
    fun isSessionVisible(serverId: String, sessionId: String): Boolean =
        _visibleSessionsByServer.value[serverId] == sessionId

    
    /**
     * Load messages for a session on one server.
     */
    fun setMessages(serverId: String, sessionId: String, messages: List<MessageWithParts>) {
        val nextInfos = messages.map { msg -> msg.info }
        _serverMessages.update { current ->
            val serverMessages = current[serverId].orEmpty()
            val existing = serverMessages[sessionId]
            if (existing == nextInfos) current else current + (serverId to (serverMessages + (sessionId to nextInfos)))
        }
        val partsMap = messages.associate { msg ->
            msg.info.id to msg.parts.filter { it.isRenderablePart() }
        }
        _serverParts.update { current ->
            val serverParts = current[serverId].orEmpty()
            if (partsMap.all { (id, parts) -> serverParts[id] == parts } &&
                partsMap.keys.all { it in serverParts } &&
                serverParts.keys.filter { id -> nextInfos.any { it.id == id } }.all { it in partsMap }
            ) {
                current
            } else {
                current + (serverId to (serverParts + partsMap))
            }
        }
    }

    /** Merge a REST history snapshot without replacing state that may have arrived live. */
    fun mergeMessages(serverId: String, sessionId: String, messages: List<MessageWithParts>) {
        _serverMessages.update { current ->
            val serverMessages = current[serverId].orEmpty()
            val liveById = serverMessages[sessionId].orEmpty().associateBy { it.id }
            val merged = buildMap {
                messages.forEach { put(it.info.id, it.info) }
                putAll(liveById)
            }.values.sortedWith(compareBy<Message> { it.time.created }.thenBy { it.id })
            current + (serverId to (serverMessages + (sessionId to merged)))
        }
        _serverParts.update { current ->
            val merged = current[serverId].orEmpty().toMutableMap()
            messages.forEach { message ->
                val liveParts = merged[message.info.id].orEmpty()
                val liveById = liveParts.associateBy { it.id }
                val snapshotPartIds = message.parts.asSequence().map { it.id }.toSet()
                merged[message.info.id] = buildList {
                    message.parts.filter { it.isRenderablePart() }.forEach { snapshotPart ->
                        add(liveById[snapshotPart.id] ?: snapshotPart)
                    }
                    liveParts.forEach { livePart ->
                        if (livePart.id !in snapshotPartIds) add(livePart)
                    }
                }
            }
            current + (serverId to merged)
        }
    }
    
    /**
     * Clear all state (used when ALL servers disconnect)
     */
    fun clearAll() {
        _serverSessions.value = emptyMap()
        _serverSessionDetails.value = emptyMap()
        _sessions.value = emptyList()
        synchronized(sessionStatusLock) {
            _serverSessionStatuses.value = emptyMap()
            sessionStatusRevisions.clear()
        }
        _activeSessionId.value = null
        _visibleSessionsByServer.value = emptyMap()
        _serverMessages.value = emptyMap()
        _serverParts.value = emptyMap()
        _sessionDiffs.value = emptyMap()
        _permissionsByServer.value = emptyMap()
        _questionsByServer.value = emptyMap()
        _todos.value = emptyMap()
        _vcsBranch.value = null
        _projectInfo.value = null
    }
    
    /**
     * Clear state for a single server.
     * Removes sessions belonging to that server and all associated data.
     * Other servers keep their own statuses, messages, and parts even when
     * they track the same session/message IDs.
     */
    fun clearForServer(serverId: String) {
        val sessionIds = _serverSessions.value[serverId] ?: emptySet()
        _permissionsByServer.update { it - serverId }
        _questionsByServer.update { it - serverId }
        _serverSessionDetails.update { it - serverId }
        _serverMessages.update { it - serverId }
        _serverParts.update { it - serverId }
        synchronized(sessionStatusLock) {
            // Invalidate any status baseline captured before this clear: recording a revision
            // change makes old baseline revisions stale, so a late snapshot cannot restore
            // state for a server that is no longer connected.
            val statusSessionIds = sessionIds + _serverSessionStatuses.value[serverId].orEmpty().keys
            statusSessionIds.forEach { recordSessionStatusChange(serverId, it) }
            _serverSessionStatuses.update { it - serverId }
        }
        _visibleSessionsByServer.update { it - serverId }
        if (sessionIds.isEmpty()) {
            _serverSessions.update { it - serverId }
            return
        }

        // Remove the server's session tracking
        _serverSessions.update { it - serverId }
        val sessionIdsOwnedElsewhere = _serverSessions.value.values.flatten().toSet()
        val orphanedSessionIds = sessionIds - sessionIdsOwnedElsewhere

        // Remove sessions
        _sessions.update { it.filter { s -> s.id !in orphanedSessionIds } }
        _sessionDiffs.update { it - orphanedSessionIds }
        _todos.update { it - orphanedSessionIds }

        if (_activeSessionId.value in orphanedSessionIds) {
            _activeSessionId.value = null
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "Clearing state for server $serverId (${sessionIds.size} sessions)")
    }
    
    // ============ Todo Events ============
    
    private fun handleTodoUpdated(event: SseEvent.TodoUpdated) {
        _todos.update { it + (event.sessionId to event.todos) }
    }
    
    // ============ VCS Events ============
    
    private fun handleVcsBranchUpdated(event: SseEvent.VcsBranchUpdated) {
        _vcsBranch.value = event.branch
    }
    
    // ============ Project Events ============
    
    private fun handleProjectUpdated(event: SseEvent.ProjectUpdated) {
        _projectInfo.value = event.info
    }
}
