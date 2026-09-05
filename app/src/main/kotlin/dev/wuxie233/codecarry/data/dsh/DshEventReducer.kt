package dev.wuxie233.codecarry.data.dsh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class DshPendingApproval(
    val eventId: String,
    val sessionId: String,
    val toolName: String,
    val callId: String? = null,
    val reason: String? = null,
)

data class DshPendingQuestion(
    val eventId: String,
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
    /** Successful local selection until a newer authoritative projection arrives. */
    val presetSelectionReceipt: Pair<Long, String>? = null,
    val updatedAt: Long = 0,
    val lastSeq: Long = -1,
    /**
     * True after `session/follow` has reported a log cut. Default `lastSeq = -1`
     * is not a known empty-log cursor; Host `-1` is valid only after follow.
     */
    val historyOpened: Boolean = false,
    val events: List<DshSessionEvent> = emptyList(),
    val queue: List<DshQueuedInboxItem> = emptyList(),
    val jobs: List<DshJobView> = emptyList(),
    val projections: Map<String, Pair<Long, JsonElement?>> = emptyMap(),
    val error: String? = null,
    /** True after `session/list` or `api-session/added` supplied this identity. */
    val listed: Boolean = false,
) {
    /** Host projects the current preset; the header only describes creation. */
    val currentAgentPreset: String?
        get() {
            val projection = projections["agentPreset"]
            presetSelectionReceipt?.let { receipt ->
                if (projection == null || projection.first <= receipt.first) return receipt.second
            }
            return if (projection != null) {
                (projection.second as? JsonPrimitive)?.contentOrNull
            } else null
        }

    /** Inclusive `session/page` cut, or null when follow has not opened yet. */
    val pageThroughSeq: Long? get() = if (historyOpened) lastSeq else null
}

data class DshEventState(
    val generation: Long = 0,
    val eventsClientId: String? = null,
    val home: String? = null,
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

    /** Ignore command receipts from an obsolete connection generation. */
    fun applyPresetSelection(sessionId: String, presetId: String, generation: Long, observedSeq: Long = -1L) {
        _state.update { state ->
            val session = state.sessions[sessionId]
            if (state.generation != generation || session == null) state
            else state.copy(sessions = state.sessions + (sessionId to session.copy(
                agentPreset = presetId,
                presetSelectionReceipt = observedSeq to presetId,
            )))
        }
    }

    fun resetGeneration(generation: Long) {
        _state.value = DshEventState(generation = generation)
    }

    /** Fold one `$events` stream item: readiness, api-session emits, waterfalls. */
    fun applyEventsFrame(frame: DshEventsFrame) {
        when (frame) {
            is DshEventsFrame.Ready -> _state.update {
                it.copy(eventsClientId = frame.clientId, home = frame.home.ifBlank { it.home })
            }
            is DshEventsFrame.Emit -> applyEmit(frame.event, frame.args)
            is DshEventsFrame.Waterfall -> applyWaterfall(frame)
            is DshEventsFrame.CancelEvent -> _state.update {
                it.copy(
                    pendingApprovals = it.pendingApprovals - frame.eventId,
                    pendingQuestions = it.pendingQuestions - frame.eventId,
                )
            }
        }
    }

    private fun applyEmit(event: String, args: List<JsonElement>) {
        when (event) {
            "api-session/added" -> {
                val summary = args.firstOrNull()?.jsonObject ?: return
                applySessionList(listOf(parseSummary(summary)))
            }
            "api-session/removed" -> {
                val sessionId = args.firstOrNull()?.jsonPrimitive?.contentOrNull ?: return
                _state.update { it.copy(sessions = it.sessions - sessionId) }
            }
            "api-session/status" -> {
                val sessionId = args.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return
                val running = args.getOrNull(1)?.jsonPrimitive?.contentOrNull == "true"
                _state.update { current ->
                    current.updateSession(sessionId) { session ->
                        session.copy(
                            running = running,
                            blank = if (running) false else session.blank,
                        )
                    }
                }
            }
            "api-session/error" -> {
                val sessionId = args.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return
                val message = args.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: return
                _state.update { current ->
                    current.updateSession(sessionId) { it.copy(error = message) }
                }
            }
            "api-session/activity" -> {
                val sessionId = args.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return
                val updatedAt = args.getOrNull(1)?.jsonPrimitive?.longOrNull ?: return
                _state.update { current ->
                    current.updateSession(sessionId) { it.copy(updatedAt = updatedAt) }
                }
            }
            else -> Unit
        }
    }

    private fun applyWaterfall(frame: DshEventsFrame.Waterfall) {
        when (frame.event) {
            "approval/request" -> _state.update {
                it.copy(
                    pendingApprovals = it.pendingApprovals + (frame.eventId to DshPendingApproval(
                        eventId = frame.eventId,
                        sessionId = frame.agentId,
                        toolName = frame.request.strValue("toolName").orEmpty(),
                        callId = frame.request.strValue("callId"),
                        reason = frame.request.strValue("reason"),
                    )),
                )
            }
            "user-questions/request" -> _state.update {
                it.copy(
                    pendingQuestions = it.pendingQuestions + (frame.eventId to DshPendingQuestion(
                        eventId = frame.eventId,
                        sessionId = frame.agentId,
                        questions = parseQuestionItems(frame.request["questions"]),
                    )),
                )
            }
            else -> Unit
        }
    }

    /** Fold one `session/control` stream item. */
    fun applyControlFrame(frame: DshControlFrame) {
        when (frame) {
            is DshControlFrame.Baseline -> _state.update { current ->
                var next = current
                frame.queues.forEach { (sessionId, items) ->
                    next = next.updateSession(sessionId) { it.copy(queue = items) }
                }
                frame.jobs.forEach { (sessionId, jobs) ->
                    next = next.updateSession(sessionId) { it.copy(jobs = jobs) }
                }
                frame.projections.forEach { (sessionId, block) ->
                    next = applyProjections(next, sessionId, block)
                }
                next
            }
            is DshControlFrame.Queue -> _state.update { current ->
                current.updateSession(frame.sessionId) { it.copy(queue = frame.items) }
            }
            is DshControlFrame.Jobs -> _state.update { current ->
                current.updateSession(frame.sessionId) { it.copy(jobs = frame.jobs) }
            }
            is DshControlFrame.Projection -> _state.update { current ->
                current.updateSession(frame.sessionId) { session ->
                    val existing = session.projections[frame.key]
                    if (existing != null && existing.first > frame.seq) {
                        session
                    } else {
                        session.copy(
                            projections = session.projections +
                                (frame.key to Pair(frame.seq, frame.value)),
                        )
                    }
                }
            }
        }
    }

    /** Fold one `workspace/follow` stream item. */
    fun applyWorkspaceFrame(frame: DshWorkspaceFrame) {
        when (frame) {
            is DshWorkspaceFrame.Baseline -> applyWorkspaceList(frame.value)
            is DshWorkspaceFrame.Upsert -> _state.update { it.withWorkspace(frame.workspace) }
            is DshWorkspaceFrame.Remove -> _state.update {
                it.copy(
                    workspaces = it.workspaces - frame.workspaceId,
                    workspaceOrder = it.workspaceOrder - frame.workspaceId,
                )
            }
            is DshWorkspaceFrame.Order -> _state.update { it.copy(workspaceOrder = frame.workspaceIds) }
            is DshWorkspaceFrame.Archived -> _state.update {
                it.copy(archivedSessionIds = frame.archivedSessionIds.toSet())
            }
            is DshWorkspaceFrame.Hidden -> _state.update {
                it.copy(hiddenWorkspaceIds = frame.hiddenWorkspaceIds.toSet())
            }
        }
    }

    /** Apply one `session/follow` opening snapshot. */
    fun applyFollowSnapshot(sessionId: String, frame: DshFollowFrame.Snapshot) {
        mergeHistory(
            sessionId = sessionId,
            events = frame.records.map { it.event.toSessionEvent() },
            projections = frame.projections,
            replace = true,
        )
        _state.update { current ->
            current.updateSession(sessionId) { session ->
                val headerParent = frame.header?.strValue("parentSession")
                val headerOrigin = frame.header?.strValue("origin")
                session.copy(
                    lastSeq = maxOf(session.lastSeq, frame.cursor),
                    historyOpened = true,
                    parentSessionId = headerParent ?: session.parentSessionId,
                    origin = headerOrigin ?: session.origin,
                    listed = session.listed || headerOrigin != null || headerParent != null,
                )
            }
        }
    }

    /** Apply one live `session/follow` event item. */
    fun applyFollowEvent(sessionId: String, event: DshSessionEvent) {
        _state.update { current ->
            current.updateSession(sessionId) { session ->
                val events = if (event.seq > session.lastSeq) session.events + event else session.events
                session.copy(
                    lastSeq = maxOf(session.lastSeq, event.seq),
                    historyOpened = true,
                    events = events,
                    projections = mergePresetEvents(session.projections, listOf(event)),
                    blank = if (event.type == "turn/start") false else session.blank,
                )
            }
        }
    }

    fun clearPending() {
        _state.update { it.copy(pendingApprovals = emptyMap(), pendingQuestions = emptyMap()) }
    }

    fun removePendingQuestion(eventId: String) {
        _state.update { current ->
            if (eventId !in current.pendingQuestions) current
            else current.copy(pendingQuestions = current.pendingQuestions - eventId)
        }
    }

    fun removePendingApproval(eventId: String) {
        _state.update { current ->
            if (eventId !in current.pendingApprovals) current
            else current.copy(pendingApprovals = current.pendingApprovals - eventId)
        }
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
                            updated[key] = Pair(asOf, value)
                        }
                    }
                    updated
                }
                session.copy(
                    lastSeq = maxOf(session.lastSeq, merged.maxOfOrNull { it.seq } ?: session.lastSeq),
                    events = merged,
                    projections = mergePresetEvents(nextProjections, merged),
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
                                updated[key] = Pair(asOf, value)
                            }
                        }
                        updated
                    }
                    session.copy(
                        blank = item.blank,
                        running = item.running,
                        updatedAt = item.updatedAt,
                        parentSessionId = item.parentSessionId ?: session.parentSessionId,
                        origin = item.origin ?: session.origin,
                        cwd = item.cwd ?: session.cwd,
                        agentPreset = item.agentPreset ?: session.agentPreset,
                        projections = nextProjections,
                        listed = true,
                    )
                }
            }
            next
        }
    }

    fun applyWorkspaceList(value: DshWorkspaceListValue) {
        _state.update { current ->
            current.copy(
                workspaces = value.items.associate { item -> item.workspaceId to item.toJsonObject() },
                workspaceOrder = value.items.map { it.workspaceId },
                archivedSessionIds = value.archivedSessionIds.toSet(),
                hiddenWorkspaceIds = value.hiddenWorkspaceIds.toSet(),
            )
        }
    }

    fun applyWorkspace(view: DshWorkspaceView) {
        _state.update { it.withWorkspace(view) }
    }

    private fun mergePresetEvents(
        projections: Map<String, Pair<Long, JsonElement?>>,
        events: List<DshSessionEvent>,
    ): Map<String, Pair<Long, JsonElement?>> {
        val selected = events.filter { it.type == "agent-preset/selected" }
            .maxByOrNull { it.seq } ?: return projections
        val preset = (selected.data as? JsonObject)?.get("agentPreset") as? JsonPrimitive
            ?: return projections
        if (!preset.isString || preset.contentOrNull.isNullOrBlank()) return projections
        val existing = projections["agentPreset"]
        if (existing != null && existing.first >= selected.seq) return projections
        return projections + ("agentPreset" to (selected.seq to preset))
    }

    private fun applyProjections(
        state: DshEventState,
        sessionId: String,
        block: DshProjectionsBlock,
    ): DshEventState = state.updateSession(sessionId) { session ->
        val updated = session.projections.toMutableMap()
        block.values.forEach { (key, value) ->
            val existing = updated[key]
            if (existing == null || existing.first <= block.asOfSeq) {
                updated[key] = Pair(block.asOfSeq, value)
            }
        }
        session.copy(projections = updated)
    }

    private fun DshEventState.updateSession(
        sessionId: String,
        transform: (DshSessionSnapshot) -> DshSessionSnapshot,
    ): DshEventState {
        val current = sessions[sessionId] ?: DshSessionSnapshot(sessionId = sessionId)
        return copy(sessions = sessions + (sessionId to transform(current)))
    }

    private fun DshEventState.withWorkspace(view: DshWorkspaceView): DshEventState {
        val id = view.workspaceId
        return copy(
            workspaces = workspaces + (id to view.toJsonObject()),
            workspaceOrder = if (id in workspaceOrder) workspaceOrder else workspaceOrder + id,
        )
    }

    private fun JsonObject.strValue(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun parseSummary(obj: JsonObject): DshSessionSummary = DshSessionSummary(
        sessionId = obj.strValue("sessionId").orEmpty(),
        updatedAt = obj["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
        running = obj["running"]?.jsonPrimitive?.contentOrNull == "true",
        blank = obj["blank"]?.jsonPrimitive?.contentOrNull == "true",
        parentSessionId = obj.strValue("parentSessionId"),
        origin = obj.strValue("origin"),
        cwd = obj.strValue("cwd"),
        agentPreset = obj.strValue("agentPreset"),
        projections = runCatching {
            val block = obj["projections"]?.jsonObject ?: return@runCatching null
            DshProjectionsBlock(
                asOfSeq = block["asOfSeq"]?.jsonPrimitive?.longOrNull ?: 0L,
                values = block["values"] as? JsonObject ?: JsonObject(emptyMap()),
            )
        }.getOrNull(),
    )
}
