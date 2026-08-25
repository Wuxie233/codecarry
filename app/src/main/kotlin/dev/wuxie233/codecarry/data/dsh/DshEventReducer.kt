package dev.wuxie233.codecarry.data.dsh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

data class DshPendingApproval(
    val rpcId: String,
    val sessionId: String,
    val approvalId: String,
    val toolName: String,
    val callId: String? = null,
    val reason: String? = null,
)

data class DshPendingQuestion(
    val rpcId: String,
    val sessionId: String,
    val questions: List<DshQuestionItem>,
)

data class DshSessionSnapshot(
    val sessionId: String,
    val blank: Boolean = true,
    val running: Boolean = false,
    val parentSessionId: String? = null,
    val origin: String? = null,
    val cwd: String? = null,
    val agentPreset: String? = null,
    val lastSeq: Long = -1,
    val events: List<DshSessionEvent> = emptyList(),
    val queue: List<DshQueuedInboxItem> = emptyList(),
    val jobs: List<DshJobView> = emptyList(),
    val projections: Map<String, Pair<Long, JsonElement?>> = emptyMap(),
    val error: String? = null,
)

data class DshEventState(
    val generation: Long = 0,
    val sessions: Map<String, DshSessionSnapshot> = emptyMap(),
    val archivedSessionIds: Set<String> = emptySet(),
    val hiddenWorkspaceIds: Set<String> = emptySet(),
    val workspaceOrder: List<String> = emptyList(),
    val workspaces: Map<String, JsonObject> = emptyMap(),
    val pendingApprovals: Map<String, DshPendingApproval> = emptyMap(),
    val pendingQuestions: Map<String, DshPendingQuestion> = emptyMap(),
) {
    fun pendingApprovalsFor(sessionId: String): List<DshPendingApproval> =
        pendingApprovals.values.filter { it.sessionId == sessionId }

    fun pendingQuestionsFor(sessionId: String): List<DshPendingQuestion> =
        pendingQuestions.values.filter { it.sessionId == sessionId }
}

class DshEventReducer(
    initial: DshEventState = DshEventState(),
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<DshEventState> = _state.asStateFlow()

    fun resetGeneration(generation: Long) {
        _state.value = DshEventState(generation = generation)
    }

    fun applyMux(rpcId: String, frame: DshMuxFrame) {
        _state.update { current ->
            when (frame) {
                is DshMuxFrame.SessionEvent -> current.updateSession(frame.sessionId) { session ->
                    val events = if (frame.event.seq > session.lastSeq) {
                        session.events + frame.event
                    } else {
                        session.events
                    }
                    session.copy(
                        lastSeq = maxOf(session.lastSeq, frame.event.seq),
                        events = events,
                    )
                }
                is DshMuxFrame.SessionSubscribed -> current.updateSession(frame.sessionId) { session ->
                    session.copy(lastSeq = maxOf(session.lastSeq, frame.lastSeq))
                }
                is DshMuxFrame.ApprovalRequested -> current.copy(
                    pendingApprovals = current.pendingApprovals + (rpcId to DshPendingApproval(
                        rpcId = rpcId,
                        sessionId = frame.sessionId,
                        approvalId = frame.approvalId,
                        toolName = frame.toolName,
                        callId = frame.callId,
                        reason = frame.reason,
                    )),
                )
                is DshMuxFrame.ApprovalResolved -> current.copy(
                    pendingApprovals = current.pendingApprovals.filterValues {
                        it.approvalId != frame.approvalId || it.sessionId != frame.sessionId
                    },
                )
                is DshMuxFrame.QuestionRequested -> current.copy(
                    pendingQuestions = current.pendingQuestions + (rpcId to DshPendingQuestion(
                        rpcId = rpcId,
                        sessionId = frame.sessionId,
                        questions = frame.questions,
                    )),
                )
                is DshMuxFrame.QuestionResolved -> current.copy(
                    pendingQuestions = current.pendingQuestions.filterKeys { it != frame.questionRpcId },
                )
                is DshMuxFrame.SessionQueue -> current.updateSession(frame.sessionId) { session ->
                    session.copy(queue = frame.items)
                }
                is DshMuxFrame.SessionJobs -> current.updateSession(frame.sessionId) { session ->
                    session.copy(jobs = frame.jobs)
                }
                is DshMuxFrame.SessionProjection -> current.updateSession(frame.sessionId) { session ->
                    val existing = session.projections[frame.key]
                    if (existing != null && existing.first > frame.seq) {
                        session
                    } else {
                        session.copy(projections = session.projections + (frame.key to (frame.seq to frame.value)))
                    }
                }
                is DshMuxFrame.StreamError, is DshMuxFrame.Unknown -> current
            }
        }
    }

    fun applyHost(frame: DshHostFrame) {
        _state.update { current ->
            when (frame) {
                is DshHostFrame.SessionAdded -> current.updateSession(frame.sessionId) { session ->
                    session.copy(
                        blank = frame.blank,
                        parentSessionId = frame.parentSessionId ?: session.parentSessionId,
                        origin = frame.origin ?: session.origin,
                        cwd = frame.cwd ?: session.cwd,
                        agentPreset = frame.agentPreset ?: session.agentPreset,
                    )
                }
                is DshHostFrame.SessionRemoved -> current.copy(sessions = current.sessions - frame.sessionId)
                is DshHostFrame.SessionStatus -> current.updateSession(frame.sessionId) { session ->
                    session.copy(
                        running = frame.running,
                        blank = if (frame.running) false else session.blank,
                    )
                }
                is DshHostFrame.AgentError -> current.updateSession(frame.sessionId) { session ->
                    session.copy(error = frame.message)
                }
                is DshHostFrame.WorkspaceChanged -> {
                    val workspaceId = frame.workspace["workspaceId"]
                        ?.let { (it as? JsonPrimitive)?.contentOrNull }
                        .orEmpty()
                    if (workspaceId.isBlank()) current else current.copy(
                        workspaces = current.workspaces + (workspaceId to frame.workspace),
                    )
                }
                is DshHostFrame.WorkspaceRemoved -> current.copy(
                    workspaces = current.workspaces - frame.workspaceId,
                    workspaceOrder = current.workspaceOrder - frame.workspaceId,
                )
                is DshHostFrame.WorkspaceOrderChanged -> current.copy(workspaceOrder = frame.workspaceIds)
                is DshHostFrame.ArchivedSessionsChanged -> current.copy(archivedSessionIds = frame.archivedSessionIds.toSet())
                is DshHostFrame.HiddenWorkspacesChanged -> current.copy(hiddenWorkspaceIds = frame.hiddenWorkspaceIds.toSet())
                is DshHostFrame.RemoteEvent, is DshHostFrame.StreamError, is DshHostFrame.Unknown -> current
            }
        }
    }

    fun clearPending() {
        _state.update { it.copy(pendingApprovals = emptyMap(), pendingQuestions = emptyMap()) }
    }

    fun mergeHistory(
        sessionId: String,
        events: List<DshSessionEvent>,
        projections: DshProjectionsBlock? = null,
        replace: Boolean = false,
    ) {
        _state.update { current ->
            current.updateSession(sessionId) { session ->
                val merged = if (replace) {
                    events.sortedBy { it.seq }
                } else {
                    (session.events + events)
                        .associateBy { it.seq }
                        .toSortedMap()
                        .values
                        .toList()
                }
                val nextProjections = if (projections == null) {
                    session.projections
                } else {
                    val asOf = projections.asOfSeq
                    val updated = session.projections.toMutableMap()
                    projections.values.forEach { (key, value) ->
                        val existing = updated[key]
                        if (existing == null || existing.first <= asOf) {
                            updated[key] = asOf to value
                        }
                    }
                    updated
                }
                session.copy(
                    lastSeq = maxOf(session.lastSeq, merged.maxOfOrNull { it.seq } ?: session.lastSeq),
                    events = merged,
                    projections = nextProjections,
                    blank = if (merged.any { it.type == "turn/start" }) false else session.blank,
                )
            }
        }
    }

    fun applySessionList(items: List<DshSessionSummary>) {
        _state.update { current ->
            var next = current
            items.forEach { item ->
                next = next.updateSession(item.sessionId) { session ->
                    val nextProjections = if (item.projections == null) {
                        session.projections
                    } else {
                        val asOf = item.projections.asOfSeq
                        val updated = session.projections.toMutableMap()
                        item.projections.values.forEach { (key, value) ->
                            val existing = updated[key]
                            if (existing == null || existing.first <= asOf) {
                                updated[key] = asOf to value
                            }
                        }
                        updated
                    }
                    session.copy(
                        blank = item.blank,
                        running = item.running,
                        parentSessionId = item.parentSessionId ?: session.parentSessionId,
                        origin = item.origin ?: session.origin,
                        cwd = item.cwd ?: session.cwd,
                        agentPreset = item.agentPreset ?: session.agentPreset,
                        projections = nextProjections,
                    )
                }
            }
            next
        }
    }

    fun applyWorkspaceList(value: DshWorkspaceListValue) {
        _state.update { current ->
            current.copy(
                workspaces = value.items.associate { item ->
                    item.workspaceId to buildJsonObject {
                        put("workspaceId", item.workspaceId)
                        put("path", item.path)
                        put("title", item.title)
                        put("createdAt", item.createdAt)
                        put("updatedAt", item.updatedAt)
                        put("sessionIds", buildJsonArray {
                            item.sessionIds.forEach { add(JsonPrimitive(it)) }
                        })
                    }
                },
                workspaceOrder = value.items.map { it.workspaceId },
                archivedSessionIds = value.archivedSessionIds.toSet(),
                hiddenWorkspaceIds = value.hiddenWorkspaceIds.toSet(),
            )
        }
    }

    private fun DshEventState.updateSession(
        sessionId: String,
        transform: (DshSessionSnapshot) -> DshSessionSnapshot,
    ): DshEventState {
        val current = sessions[sessionId] ?: DshSessionSnapshot(sessionId = sessionId)
        return copy(sessions = sessions + (sessionId to transform(current)))
    }
}
