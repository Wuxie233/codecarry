package dev.minios.ocremote.data.repository

import android.util.Log
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.domain.model.*
import dev.minios.ocremote.domain.transport.PiTransportEvent
import dev.minios.ocremote.domain.transport.TransportEvent
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
 * Supports multiple servers simultaneously. Most session state is keyed by sessionId,
 * while pending user actions retain server ownership because server-local session IDs
 * are not safe cross-server join keys.
 * 
 * Similar to the event-reducer.ts in the WebUI.
 */
@Singleton
class EventReducer @Inject constructor() {

    private val sessionStatusRevisions = java.util.concurrent.ConcurrentHashMap<String, Long>()
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
    
    private val _sessionStatuses = MutableStateFlow<Map<String, SessionStatus>>(emptyMap())
    val sessionStatuses: StateFlow<Map<String, SessionStatus>> = _sessionStatuses.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()
    
    private val _messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap()) // sessionId -> messages
    val messages: StateFlow<Map<String, List<Message>>> = _messages.asStateFlow()
    
    private val _parts = MutableStateFlow<Map<String, List<Part>>>(emptyMap()) // messageId -> parts
    val parts: StateFlow<Map<String, List<Part>>> = _parts.asStateFlow()
    
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

    private val _serverRoundtables = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    private val _roundtables = MutableStateFlow<Map<String, Roundtable>>(emptyMap())
    val roundtables: StateFlow<Map<String, Roundtable>> = _roundtables.asStateFlow()

    private val _roundtableMessages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    val roundtableMessages: StateFlow<Map<String, List<Message>>> = _roundtableMessages.asStateFlow()

    private val _roundtableParts = MutableStateFlow<Map<String, List<Part>>>(emptyMap())
    val roundtableParts: StateFlow<Map<String, List<Part>>> = _roundtableParts.asStateFlow()

    private val _roundtableEvents = MutableStateFlow<Map<String, List<PiTransportEvent>>>(emptyMap())
    val roundtableEvents: StateFlow<Map<String, List<PiTransportEvent>>> = _roundtableEvents.asStateFlow()

    private val piTurnInfo = mutableMapOf<String, PiTurnInfo>()
    private val processedPiEvents = mutableSetOf<PiEventKey>()
    
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
            
            is SseEvent.MessageUpdated -> handleMessageUpdated(event)
            is SseEvent.MessageRemoved -> handleMessageRemoved(event)
            
            is SseEvent.MessagePartUpdated -> handleMessagePartUpdated(event)
            is SseEvent.MessagePartDelta -> handleMessagePartDelta(event)
            is SseEvent.MessagePartRemoved -> handleMessagePartRemoved(event)
            
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
            is TransportEvent.Pi -> processPiEvent(event.event, serverId)
        }
    }

    private fun processPiEvent(event: PiTransportEvent, serverId: String) {
        val roundtableId = event.envelope.roundId
        val eventKey = PiEventKey(roundtableId, event.envelope.eventId)
        if (!processedPiEvents.add(eventKey)) return
        trackRoundtable(serverId, roundtableId)
        appendRoundtableEvent(roundtableId, event)

        when (event) {
            is PiTransportEvent.RoundStart -> updateRoundtable(
                event = event,
                status = Roundtable.Status.Running,
                topic = event.topic,
                rosterSummary = event.participantIds.joinToString(", "),
                incrementRound = true,
            )
            is PiTransportEvent.AgentTurnStart -> handlePiAgentTurnStart(event)
            is PiTransportEvent.MessageDelta -> handlePiMessageDelta(event)
            is PiTransportEvent.MessageEnd -> handlePiMessageEnd(event)
            is PiTransportEvent.ModeratorSynthesis -> handlePiModeratorSynthesis(event)
            is PiTransportEvent.AgentRetry -> updateRoundtable(event, Roundtable.Status.Running)
            is PiTransportEvent.AgentFallback -> updateRoundtable(event, Roundtable.Status.Running)
            is PiTransportEvent.AgentError -> updateRoundtable(event, Roundtable.Status.Error)
            is PiTransportEvent.AwaitingSkip -> updateRoundtable(event, Roundtable.Status.AwaitingSkip)
            is PiTransportEvent.AwaitingCommand -> updateRoundtable(event, Roundtable.Status.AwaitingCommand)
            is PiTransportEvent.RoundEnd -> updateRoundtable(
                event = event,
                status = Roundtable.Status.Completed,
                completedAt = event.envelope.ts,
            )
            is PiTransportEvent.Error -> updateRoundtable(event, Roundtable.Status.Error)
        }
    }

    private fun trackRoundtable(serverId: String, roundtableId: String) {
        _serverRoundtables.update { current ->
            val existing = current[serverId] ?: emptySet()
            current + (serverId to (existing + roundtableId))
        }
    }

    private fun appendRoundtableEvent(roundtableId: String, event: PiTransportEvent) {
        _roundtableEvents.update { current ->
            val events = (current[roundtableId].orEmpty() + event)
                .sortedWith(compareBy<PiTransportEvent> { it.envelope.sequence }.thenBy { it.envelope.eventId })
            current + (roundtableId to events)
        }
    }

    private fun updateRoundtable(
        event: PiTransportEvent,
        status: Roundtable.Status,
        topic: String? = null,
        rosterSummary: String? = null,
        incrementRound: Boolean = false,
        completedAt: String? = null,
    ) {
        val roundtableId = event.envelope.roundId
        _roundtables.update { current ->
            val existing = current[roundtableId]
            val time = existing?.time ?: Roundtable.Time(created = event.envelope.ts)
            val updated = (existing ?: Roundtable(id = roundtableId)).copy(
                topic = topic ?: existing?.topic,
                status = status,
                roundCount = if (incrementRound) (existing?.roundCount ?: 0) + 1 else existing?.roundCount ?: 0,
                rosterSummary = rosterSummary ?: existing?.rosterSummary,
                time = time.copy(updated = event.envelope.ts, completed = completedAt ?: time.completed),
            )
            current + (roundtableId to updated)
        }
    }

    private fun handlePiAgentTurnStart(event: PiTransportEvent.AgentTurnStart) {
        updateRoundtable(event, Roundtable.Status.Running)
        val turnId = event.envelope.turnId ?: return
        piTurnInfo[turnId] = PiTurnInfo(
            roundtableId = event.envelope.roundId,
            turnId = turnId,
            providerId = event.providerId,
            modelId = event.model,
            author = event.envelope.author,
            actionTag = event.actionTag,
            startedSequence = event.envelope.sequence,
        )
        ensurePiMessage(event.envelope.roundId, turnId, event.envelope.sequence, event.envelope.author)
        ensurePiTextPart(event.envelope.roundId, turnId)
    }

    private fun handlePiMessageDelta(event: PiTransportEvent.MessageDelta) {
        updateRoundtable(event, Roundtable.Status.Running)
        val turnId = event.envelope.turnId ?: return
        ensurePiMessage(event.envelope.roundId, turnId, event.envelope.sequence, event.envelope.author)
        ensurePiTextPart(event.envelope.roundId, turnId)
        _roundtableParts.update { current ->
            appendPartDelta(current, messageId = turnId, partId = piTextPartId(turnId), delta = event.chunk)
        }
    }

    private fun handlePiMessageEnd(event: PiTransportEvent.MessageEnd) {
        updateRoundtable(event, Roundtable.Status.Running)
        val turnId = event.envelope.turnId ?: return
        ensurePiMessage(event.envelope.roundId, turnId, event.envelope.sequence, event.envelope.author, finish = event.finishReason)
        ensurePiTextPart(event.envelope.roundId, turnId)
        _roundtableParts.update { current ->
            upsertPart(
                current,
                Part.Text(
                    id = piTextPartId(turnId),
                    sessionId = event.envelope.roundId,
                    messageId = turnId,
                    text = event.assembledText,
                )
            )
        }
        _roundtableMessages.update { current ->
            upsertMessage(current, event.envelope.roundId, piAssistantMessage(event.envelope.roundId, turnId, event.envelope.sequence, event.envelope.author, finish = event.finishReason))
        }
    }

    private fun handlePiModeratorSynthesis(event: PiTransportEvent.ModeratorSynthesis) {
        updateRoundtable(event, Roundtable.Status.Running)
        val messageId = event.envelope.turnId ?: "moderator-${event.envelope.eventId}"
        _roundtableMessages.update { current ->
            upsertMessage(current, event.envelope.roundId, piAssistantMessage(event.envelope.roundId, messageId, event.envelope.sequence, event.envelope.author, finish = "stop"))
        }
        _roundtableParts.update { current ->
            upsertPart(
                current,
                Part.Text(
                    id = piTextPartId(messageId),
                    sessionId = event.envelope.roundId,
                    messageId = messageId,
                    text = event.markdownBody,
                )
            )
        }
    }

    private fun ensurePiMessage(roundtableId: String, turnId: String, sequence: Long, author: dev.minios.ocremote.domain.transport.PiAuthor, finish: String? = null) {
        _roundtableMessages.update { current ->
            val exists = current[roundtableId].orEmpty().any { message -> message.id == turnId }
            if (exists && finish == null) current else upsertMessage(current, roundtableId, piAssistantMessage(roundtableId, turnId, sequence, author, finish))
        }
    }

    private fun ensurePiTextPart(roundtableId: String, turnId: String) {
        _roundtableParts.update { current ->
            val partId = piTextPartId(turnId)
            val exists = current[turnId].orEmpty().any { part -> part.id == partId }
            if (exists) current else upsertPart(
                current,
                Part.Text(
                    id = partId,
                    sessionId = roundtableId,
                    messageId = turnId,
                )
            )
        }
    }

    private fun piAssistantMessage(
        roundtableId: String,
        turnId: String,
        sequence: Long,
        author: dev.minios.ocremote.domain.transport.PiAuthor,
        finish: String? = null,
    ): Message.Assistant {
        val turn = piTurnInfo[turnId]
        return Message.Assistant(
            id = turnId,
            sessionId = roundtableId,
            time = TimeInfo(created = turn?.startedSequence ?: sequence, completed = if (finish == null) null else sequence),
            modelId = turn?.modelId,
            providerId = turn?.providerId,
            finish = finish,
            senderId = author.id,
            senderName = author.name,
            mbti = author.mbti,
            senderRole = author.role,
            colorSeed = author.colorSeed,
            actionTag = turn?.actionTag,
        )
    }

    private fun piTextPartId(turnId: String): String = "$turnId-text"
    
    // ============ Server Events ============
    
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
            recordSessionStatusChange(event.info.id)
            _sessionStatuses.update { current ->
                if (event.info.id in current) current else current + (event.info.id to SessionStatus.Idle)
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
        val ownedByAnotherServer = _serverSessions.value.any { (ownerId, ids) ->
            ownerId != serverId && sessionId in ids
        }
        if (ownedByAnotherServer) return
        _sessions.update { it.filter { session -> session.id != sessionId } }
        synchronized(sessionStatusLock) {
            recordSessionStatusChange(sessionId)
            _sessionStatuses.update { it - sessionId }
        }
        _messages.update { it - sessionId }
        _sessionDiffs.update { it - sessionId }
    }
    
    private fun handleSessionStatus(event: SseEvent.SessionStatus, serverId: String) {
        trackSession(serverId, event.sessionId)
        synchronized(sessionStatusLock) {
            recordSessionStatusChange(event.sessionId)
            _sessionStatuses.update { it + (event.sessionId to event.status) }
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Session ${event.sessionId} status: ${event.status}")
    }
    
    private fun handleSessionIdle(event: SseEvent.SessionIdle, serverId: String) {
        trackSession(serverId, event.sessionId)
        synchronized(sessionStatusLock) {
            recordSessionStatusChange(event.sessionId)
            _sessionStatuses.update { it + (event.sessionId to SessionStatus.Idle) }
        }
    }
    
    private fun handleSessionDiff(event: SseEvent.SessionDiff) {
        _sessionDiffs.update { it + (event.sessionId to event.diff) }
    }
    
    private fun handleSessionError(event: SseEvent.SessionError) {
        Log.e(TAG, "Session ${event.sessionId} error: ${event.error}")
    }
    
    // ============ Message Events ============
    
    private fun handleMessageUpdated(event: SseEvent.MessageUpdated) {
        val sessionId = event.info.sessionId
        _messages.update { current -> upsertMessage(current, sessionId, event.info) }
    }
    
    private fun handleMessageRemoved(event: SseEvent.MessageRemoved) {
        _messages.update { current ->
            val sessionMessages = current[event.sessionId]?.filter { it.id != event.messageId }
            if (sessionMessages != null) {
                current + (event.sessionId to sessionMessages)
            } else {
                current
            }
        }
        _parts.update { it - event.messageId }
    }
    
    // ============ Part Events ============

    private fun Part.isRenderablePart(): Boolean {
        return this !is Part.Tool || (callId.isNotBlank() && tool.isNotBlank())
    }

    private fun handleMessagePartUpdated(event: SseEvent.MessagePartUpdated) {
        if (!event.part.isRenderablePart()) return

        _parts.update { current -> upsertPart(current, event.part) }
    }
    
    private fun handleMessagePartDelta(event: SseEvent.MessagePartDelta) {
        // Append text delta to existing part
        _parts.update { current -> appendPartDelta(current, event.messageId, event.partId, event.delta) }
    }

    private fun upsertMessage(
        current: Map<String, List<Message>>,
        conversationId: String,
        message: Message,
    ): Map<String, List<Message>> {
        val conversationMessages = current[conversationId]?.toMutableList() ?: mutableListOf()
        val existingIndex = conversationMessages.indexOfFirst { it.id == message.id }

        if (existingIndex >= 0) {
            conversationMessages[existingIndex] = message
        } else {
            conversationMessages.add(message)
            conversationMessages.sortBy { it.time.created }
        }

        return current + (conversationId to conversationMessages)
    }

    private fun upsertPart(current: Map<String, List<Part>>, part: Part): Map<String, List<Part>> {
        if (!part.isRenderablePart()) return current
        val messageParts = current[part.messageId]?.toMutableList() ?: mutableListOf()
        val existingIndex = messageParts.indexOfFirst { it.id == part.id }

        if (existingIndex >= 0) {
            messageParts[existingIndex] = part
        } else {
            messageParts.add(part)
        }

        return current + (part.messageId to messageParts)
    }

    private fun appendPartDelta(
        current: Map<String, List<Part>>,
        messageId: String,
        partId: String,
        delta: String,
    ): Map<String, List<Part>> {
        val messageParts = current[messageId]?.toMutableList() ?: return current
        val partIndex = messageParts.indexOfFirst { it.id == partId }

        if (partIndex < 0) return current

        val part = messageParts[partIndex]
        val updatedPart = when (part) {
            is Part.Text -> part.copy(text = part.text + delta)
            is Part.Reasoning -> part.copy(text = part.text + delta)
            else -> part
        }

        messageParts[partIndex] = updatedPart
        return current + (messageId to messageParts)
    }
    
    private fun handleMessagePartRemoved(event: SseEvent.MessagePartRemoved) {
        _parts.update { current ->
            val messageParts = current[event.messageId]?.filter { it.id != event.partId }
            if (messageParts != null) {
                current + (event.messageId to messageParts)
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
            val updated = if (questions.isEmpty()) serverQuestions - sessionId else serverQuestions + (sessionId to questions)
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
     * Manually update the session status.
     * Useful for optimistic updates (e.g. aborting a session).
     */
    fun updateSessionStatus(sessionId: String, status: SessionStatus) {
        synchronized(sessionStatusLock) {
            recordSessionStatusChange(sessionId)
            _sessionStatuses.update { it + (sessionId to status) }
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Manually updated session $sessionId status to $status")
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
                revisions = serverSessionIds.associateWith { sessionStatusRevisions[it] },
            )
        }
    }

    fun reconcileSessionStatuses(
        statuses: Map<String, SessionStatus>,
        baseline: SessionStatusBaseline,
    ) {
        val targetSessionIds = baseline.sessionIds + statuses.keys
        synchronized(sessionStatusLock) {
            _sessionStatuses.update { current ->
                val next = current.toMutableMap()
                for (sessionId in targetSessionIds) {
                    if (sessionId in baseline.conflictingSessionIds) continue
                    val owners = _serverSessions.value.filterValues { sessionId in it }.keys
                    if (owners.any { it != baseline.serverId }) continue
                    if (sessionStatusRevisions[sessionId] != baseline.revisions[sessionId]) continue
                    next[sessionId] = statuses[sessionId] ?: SessionStatus.Idle
                }
                next
            }
        }
    }

    private fun recordSessionStatusChange(sessionId: String) {
        sessionStatusRevisions[sessionId] = nextSessionStatusRevision.incrementAndGet()
    }

    fun setRoundtables(serverId: String, roundtables: List<Roundtable>) {
        val roundtableIds = roundtables.map { it.id }.toSet()
        _serverRoundtables.update { current ->
            val existing = current[serverId] ?: emptySet()
            current + (serverId to (existing + roundtableIds))
        }
        _roundtables.update { current ->
            current + roundtables.associateBy { it.id }
        }
    }

    fun appendRoundtableUserMessage(roundtableId: String, text: String, createdAt: Long = System.currentTimeMillis()) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        val messageId = "local-user-$createdAt-${trimmed.hashCode()}"
        _roundtableMessages.update { current ->
            upsertMessage(
                current,
                roundtableId,
                Message.User(
                    id = messageId,
                    sessionId = roundtableId,
                    time = TimeInfo(created = createdAt, completed = createdAt),
                ),
            )
        }
        _roundtableParts.update { current ->
            upsertPart(
                current,
                Part.Text(
                    id = "$messageId-text",
                    sessionId = roundtableId,
                    messageId = messageId,
                    text = trimmed,
                ),
            )
        }
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
     * Load messages for a session
     */
    fun setMessages(sessionId: String, messages: List<MessageWithParts>) {
        _messages.update { it + (sessionId to messages.map { msg -> msg.info }) }
        
        val partsMap = messages.associate { msg ->
            msg.info.id to msg.parts.filter { it.isRenderablePart() }
        }
        _parts.update { it + partsMap }
    }
    
    /**
     * Clear all state (used when ALL servers disconnect)
     */
    fun clearAll() {
        _serverSessions.value = emptyMap()
        _serverSessionDetails.value = emptyMap()
        _sessions.value = emptyList()
        synchronized(sessionStatusLock) {
            _sessionStatuses.value = emptyMap()
            sessionStatusRevisions.clear()
        }
        _activeSessionId.value = null
        _messages.value = emptyMap()
        _parts.value = emptyMap()
        _sessionDiffs.value = emptyMap()
        _permissionsByServer.value = emptyMap()
        _questionsByServer.value = emptyMap()
        _todos.value = emptyMap()
        _vcsBranch.value = null
        _projectInfo.value = null
        _serverRoundtables.value = emptyMap()
        _roundtables.value = emptyMap()
        _roundtableMessages.value = emptyMap()
        _roundtableParts.value = emptyMap()
        _roundtableEvents.value = emptyMap()
        piTurnInfo.clear()
        processedPiEvents.clear()
    }
    
    /**
     * Clear state for a single server.
     * Removes sessions belonging to that server and all associated data.
     */
    fun clearForServer(serverId: String) {
        val sessionIds = _serverSessions.value[serverId] ?: emptySet()
        val roundtableIds = _serverRoundtables.value[serverId] ?: emptySet()
        _permissionsByServer.update { it - serverId }
        _questionsByServer.update { it - serverId }
        _serverSessionDetails.update { it - serverId }
        if (sessionIds.isEmpty() && roundtableIds.isEmpty()) {
            _serverSessions.update { it - serverId }
            _serverRoundtables.update { it - serverId }
            return
        }
        
        // Remove the server's session tracking
        _serverSessions.update { it - serverId }
        _serverRoundtables.update { it - serverId }
        val sessionIdsOwnedElsewhere = _serverSessions.value.values.flatten().toSet()
        val orphanedSessionIds = sessionIds - sessionIdsOwnedElsewhere
        
        // Remove sessions
        _sessions.update { it.filter { s -> s.id !in orphanedSessionIds } }
        synchronized(sessionStatusLock) {
            orphanedSessionIds.forEach(::recordSessionStatusChange)
            _sessionStatuses.update { it - orphanedSessionIds }
        }
        _sessionDiffs.update { it - orphanedSessionIds }
        _todos.update { it - orphanedSessionIds }
        
        // Remove messages and their parts
        val messageIds = _messages.value
            .filterKeys { it in orphanedSessionIds }
            .values
            .flatten()
            .map { it.id }
            .toSet()
        _messages.update { it - orphanedSessionIds }
        _parts.update { it - messageIds }

        val roundtableMessageIds = _roundtableMessages.value
            .filterKeys { it in roundtableIds }
            .values
            .flatten()
            .map { it.id }
            .toSet()
        _roundtables.update { it - roundtableIds }
        _roundtableMessages.update { it - roundtableIds }
        _roundtableParts.update { it - roundtableMessageIds }
        _roundtableEvents.update { it - roundtableIds }
        piTurnInfo.keys.removeAll(roundtableMessageIds)
        processedPiEvents.removeAll { event -> event.roundtableId in roundtableIds }

        if (_activeSessionId.value in orphanedSessionIds) {
            _activeSessionId.value = null
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "Clearing state for server $serverId (${sessionIds.size} sessions, ${roundtableIds.size} roundtables)")
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

private data class PiTurnInfo(
    val roundtableId: String,
    val turnId: String,
    val providerId: String,
    val modelId: String,
    val author: dev.minios.ocremote.domain.transport.PiAuthor,
    val actionTag: String?,
    val startedSequence: Long,
)

private data class PiEventKey(
    val roundtableId: String,
    val eventId: Long,
)
