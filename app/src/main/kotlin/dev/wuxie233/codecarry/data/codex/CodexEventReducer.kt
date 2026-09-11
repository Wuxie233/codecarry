package dev.wuxie233.codecarry.data.codex

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

data class CodexEventState(
    val resetGeneration: Long = 0,
    val turnPlans: Map<String, Map<String, CodexTurnPlan>> = emptyMap(),
    val turnDiffs: Map<String, Map<String, String>> = emptyMap(),
    val turnTokenUsage: Map<String, Map<String, CodexThreadTokenUsage>> = emptyMap(),
    val tokenUsage: Map<String, CodexThreadTokenUsage> = emptyMap(),
    val threads: Map<String, CodexThread> = emptyMap(),
    val goals: Map<String, CodexGoal> = emptyMap(),
    val knownGoalThreadIds: Set<String> = emptySet(),
    val archivedThreadIds: Set<String> = emptySet(),
    val threadFailures: Map<String, CodexFailure> = emptyMap(),
    val turnFailures: Map<String, Map<String, CodexFailure>> = emptyMap(),
)

class CodexEventReducer(
    initialThreads: List<CodexThread> = emptyList(),
) {
    private val _state = MutableStateFlow(
        CodexEventState(threads = initialThreads.associateBy(CodexThread::id)),
    )
    val state: StateFlow<CodexEventState> = _state.asStateFlow()

    /** A fast catalog page is partial evidence; only a complete repair may remove omissions. */
    fun mergeThreadPage(threads: List<CodexThread>, archived: Boolean, baseline: CodexEventState) {
        _state.update { current ->
            if (current.resetGeneration != baseline.resetGeneration) return@update current
            val merged = current.threads.toMutableMap()
            val archivedIds = current.archivedThreadIds.toMutableSet()
            threads.forEach { incoming ->
                val id = incoming.id
                // A page started before a live deletion must not resurrect that thread.
                if (id in baseline.threads && id !in current.threads) return@forEach
                val live = current.threads[id]
                merged[id] = live?.mergeMetadata(incoming, baseline.threads[id]) ?: incoming
                val archiveChanged = (id in baseline.archivedThreadIds) != (id in current.archivedThreadIds)
                val createdDuringLoad = id !in baseline.threads && live != null
                if (!archiveChanged && !createdDuringLoad) {
                    if (archived) archivedIds.add(id) else archivedIds.remove(id)
                }
            }
            current.copy(threads = merged, archivedThreadIds = archivedIds)
        }
    }

    fun reconcileThreads(
        activeThreads: List<CodexThread>,
        archivedThreads: List<CodexThread>,
        baseline: CodexEventState,
    ) {
        val incoming = (activeThreads + archivedThreads).associateBy(CodexThread::id)
        val incomingArchivedIds = archivedThreads.mapTo(mutableSetOf(), CodexThread::id)
        _state.update { current ->
            val deletedDuringLoad = baseline.threads.keys - current.threads.keys
            val createdDuringLoad = current.threads.keys - baseline.threads.keys
            val finalIds = (incoming.keys - deletedDuringLoad) + createdDuringLoad
            val archiveChangesDuringLoad = (baseline.threads.keys + current.threads.keys).filterTo(mutableSetOf()) { id ->
                (id in baseline.archivedThreadIds) != (id in current.archivedThreadIds)
            }
            val reconciledThreads = finalIds.associateWith { id ->
                val fresh = incoming[id]
                val live = current.threads[id]
                when {
                    fresh != null && live != null -> live.mergeMetadata(fresh, baseline.threads[id])
                    fresh != null -> fresh
                    else -> requireNotNull(live)
                }
            }
            val reconciledArchivedIds = finalIds.filterTo(mutableSetOf()) { id ->
                if (id in archiveChangesDuringLoad || id in createdDuringLoad) {
                    id in current.archivedThreadIds
                } else {
                    id in incomingArchivedIds
                }
            }
            current.copy(
                threads = reconciledThreads,
                archivedThreadIds = reconciledArchivedIds,
                threadFailures = current.threadFailures.filterKeys { it in finalIds },
                turnFailures = current.turnFailures.filterKeys { it in finalIds },
                turnPlans = current.turnPlans.filterKeys { it in finalIds },
                turnDiffs = current.turnDiffs.filterKeys { it in finalIds },
                turnTokenUsage = current.turnTokenUsage.filterKeys { it in finalIds },
                tokenUsage = current.tokenUsage.filterKeys { it in finalIds },
            )
        }
    }

    fun upsertThread(thread: CodexThread) = upsertThreadSnapshot(thread, _state.value.threads[thread.id])

    fun upsertThreadSnapshot(thread: CodexThread, baseline: CodexThread?) {
        _state.update { current ->
            val merged = current.threads[thread.id]?.mergeMetadata(thread, baseline) ?: thread
            current.copy(
                threads = current.threads + (thread.id to merged),
                threadFailures = current.retireSettledThreadRetry(merged, thread.turns),
            )
        }
    }

    /** A command receipt may precede its event, but must never roll back live state. */
    fun acceptTurnStart(threadId: String, turn: CodexTurn) {
        _state.update { current ->
            val thread = current.threads[threadId] ?: return@update current
            if (thread.turns.any { it.id == turn.id }) return@update current
            current.copy(threads = current.threads + (threadId to thread.copy(
                turns = thread.turns + turn,
                status = if (turn.status == "inProgress") CodexThreadStatus("active") else thread.status,
            )))
        }
    }

    /** These notification-only values cannot be recovered from a thread/resume snapshot.
     * Invalidate before the request so newer notifications during resume remain visible. */
    fun invalidateThreadControlState(threadId: String) {
        _state.update { current -> current.copy(
            turnPlans = current.turnPlans - threadId,
            turnDiffs = current.turnDiffs - threadId,
            turnTokenUsage = current.turnTokenUsage - threadId,
            tokenUsage = current.tokenUsage - threadId,
        ) }
    }

    fun upsertThreadAuthoritative(thread: CodexThread) {
        _state.update { current ->
            current.copy(
                threads = current.threads + (thread.id to thread),
                threadFailures = current.retireSettledThreadRetry(thread, thread.turns),
            )
        }
    }

    fun removeThread(threadId: String) {
        _state.update { current ->
            current.copy(
                threads = current.threads - threadId,
                goals = current.goals - threadId,
                knownGoalThreadIds = current.knownGoalThreadIds - threadId,
                archivedThreadIds = current.archivedThreadIds - threadId,
                threadFailures = current.threadFailures - threadId,
                turnFailures = current.turnFailures - threadId,
                turnPlans = current.turnPlans - threadId,
                turnDiffs = current.turnDiffs - threadId,
                turnTokenUsage = current.turnTokenUsage - threadId,
                tokenUsage = current.tokenUsage - threadId,
            )
        }
    }

    fun process(notification: CodexNotification) {
        when (notification.method) {
            "turn/plan/updated", "turn/diff/updated", "thread/tokenUsage/updated" -> updateControlState(notification)
            "item/fileChange/patchUpdated" -> {
                val itemId = notification.itemId ?: return
                updateItem(notification.threadId, notification.turnId, itemId, "fileChange") { item ->
                    item.copy(fileChanges = notification.params.controlObjects("changes").map(CodexFileChange::fromJson))
                }
            }
            "thread/started" -> notification.thread?.let(::upsertThread)
            "thread/status/changed" -> updateThread(notification.threadId) { thread ->
                val status = notification.params["status"] ?: return@updateThread thread
                thread.copy(status = CodexThreadStatus.fromJson(status))
            }
            "thread/name/updated" -> updateThread(notification.threadId) { thread ->
                thread.copy(name = notification.params.string("threadName"))
            }
            "thread/archived" -> notification.threadId?.let { threadId ->
                _state.update { current ->
                    current.copy(archivedThreadIds = current.archivedThreadIds + threadId)
                }
            }
            "thread/unarchived" -> notification.threadId?.let { threadId ->
                _state.update { current ->
                    current.copy(archivedThreadIds = current.archivedThreadIds - threadId)
                }
            }
            "thread/deleted" -> notification.threadId?.let(::removeThread)
            "turn/started" -> notification.turn?.let { turn ->
                notification.threadId?.let(::clearThreadError)
                updateThread(notification.threadId) { thread -> thread.upsertTurn(turn, authoritative = false) }
            }
            "turn/completed" -> notification.turn?.let { turn ->
                val threadId = notification.threadId ?: return
                _state.update { current ->
                    val thread = (current.threads[threadId] ?: CodexThread(id = threadId, hasMetadata = false))
                        .upsertTurn(turn, authoritative = false)
                    current.copy(
                        threads = current.threads + (threadId to thread),
                        threadFailures = current.retireSettledThreadRetry(thread, listOf(turn)),
                    )
                }
            }
            "item/started" -> notification.item?.let { item ->
                updateTurn(notification.threadId, notification.turnId) { turn ->
                    turn.upsertItem(item, authoritative = false)
                }
            }
            "item/completed" -> notification.item?.let { item ->
                updateTurn(notification.threadId, notification.turnId) { turn ->
                    turn.upsertItem(item, authoritative = true)
                }
            }
            "item/agentMessage/delta", "item/plan/delta" -> appendTextDelta(notification)
            "item/reasoning/summaryTextDelta" -> appendReasoningDelta(
                notification = notification,
                indexKey = "summaryIndex",
                summary = true,
            )
            "item/reasoning/textDelta" -> appendReasoningDelta(
                notification = notification,
                indexKey = "contentIndex",
                summary = false,
            )
            "item/commandExecution/outputDelta", "command/exec/outputDelta" -> appendOutputDelta(notification)
            "thread/goal/updated" -> notification.goal?.let { goal ->
                _state.update { current ->
                    current.copy(
                        goals = current.goals + (goal.threadId to goal),
                        knownGoalThreadIds = current.knownGoalThreadIds + goal.threadId,
                    )
                }
            }
            "thread/goal/cleared" -> notification.threadId?.let { threadId ->
                _state.update { current ->
                    current.copy(
                        goals = current.goals - threadId,
                        knownGoalThreadIds = current.knownGoalThreadIds + threadId,
                    )
                }
            }
            "error" -> notification.threadId?.let { threadId ->
                _state.update { current ->
                    val failure = CodexFailure.fromNotification(threadId, notification.turnId, notification.params)
                    if (failure.turnId == null) {
                        current.copy(threadFailures = current.threadFailures + (threadId to failure))
                    } else {
                        current.copy(turnFailures = current.turnFailures + (threadId to
                            (current.turnFailures[threadId].orEmpty() + (failure.turnId to failure))))
                    }
                }
            }
        }
    }

    private fun updateControlState(notification: CodexNotification) {
        val threadId = notification.threadId ?: return
        val turnId = notification.turnId ?: return
        _state.update { current ->
            val withThread = if (threadId in current.threads) current else current.copy(
                threads = current.threads + (threadId to CodexThread(id = threadId, hasMetadata = false)),
            )
            when (notification.method) {
                "turn/plan/updated" -> withThread.copy(turnPlans = current.turnPlans + (threadId to
                    (current.turnPlans[threadId].orEmpty() + (turnId to CodexTurnPlan.fromJson(notification.params)))))
                "turn/diff/updated" -> {
                    val diff = notification.params.string("diff") ?: return@update current
                    withThread.copy(turnDiffs = current.turnDiffs + (threadId to
                        (current.turnDiffs[threadId].orEmpty() + (turnId to diff))))
                }
                else -> {
                    val value = notification.params["tokenUsage"] as? JsonObject ?: return@update current
                    val usage = CodexThreadTokenUsage.fromJson(turnId, value)
                    withThread.copy(
                        turnTokenUsage = current.turnTokenUsage + (threadId to
                            (current.turnTokenUsage[threadId].orEmpty() + (turnId to usage))),
                        tokenUsage = current.tokenUsage + (threadId to usage),
                    )
                }
            }
        }
    }

    fun clear() {
        _state.update { CodexEventState(resetGeneration = it.resetGeneration + 1) }
    }

    private fun clearThreadError(threadId: String) {
        _state.update { current ->
            if (current.threadFailures[threadId]?.willRetry == true) {
                current.copy(threadFailures = current.threadFailures - threadId)
            } else current
        }
    }

    private fun appendTextDelta(notification: CodexNotification) {
        val itemId = notification.itemId ?: return
        val delta = notification.delta ?: return
        val type = if (notification.method == "item/plan/delta") "plan" else "agentMessage"
        updateItem(notification.threadId, notification.turnId, itemId, type) { item ->
            item.copy(text = item.text.orEmpty() + delta)
        }
    }

    private fun appendOutputDelta(notification: CodexNotification) {
        val itemId = notification.itemId ?: return
        val delta = notification.delta ?: return
        updateItem(notification.threadId, notification.turnId, itemId, "commandExecution") { item ->
            item.copy(output = item.output.orEmpty() + delta)
        }
    }

    private fun appendReasoningDelta(
        notification: CodexNotification,
        indexKey: String,
        summary: Boolean,
    ) {
        val itemId = notification.itemId ?: return
        val delta = notification.delta ?: return
        val index = (notification.params[indexKey] as? JsonPrimitive)?.intOrNull ?: 0
        updateItem(notification.threadId, notification.turnId, itemId, "reasoning") { item ->
            val updatedParts = (if (summary) item.reasoningSummary else item.reasoningContent)
                .appendAt(index, delta)
            val summaries = if (summary) updatedParts else item.reasoningSummary
            val content = if (summary) item.reasoningContent else updatedParts
            item.copy(
                text = (summaries + content).filter(String::isNotEmpty).joinToString("\n"),
                reasoningSummary = summaries,
                reasoningContent = content,
            )
        }
    }

    private fun updateItem(
        threadId: String?,
        turnId: String?,
        itemId: String,
        type: String,
        transform: (CodexThreadItem) -> CodexThreadItem,
    ) {
        updateTurn(threadId, turnId) { turn ->
            val existing = turn.items.firstOrNull { item -> item.id == itemId }
                ?: CodexThreadItem(id = itemId, type = type)
            turn.upsertItem(transform(existing), authoritative = true)
        }
    }

    private fun updateTurn(
        threadId: String?,
        turnId: String?,
        transform: (CodexTurn) -> CodexTurn,
    ) {
        val resolvedTurnId = turnId ?: return
        updateThread(threadId) { thread ->
            val existing = thread.turns.firstOrNull { turn -> turn.id == resolvedTurnId }
                ?: CodexTurn(id = resolvedTurnId, status = "inProgress")
            thread.upsertTurn(transform(existing), authoritative = true)
        }
    }

    private fun updateThread(
        threadId: String?,
        transform: (CodexThread) -> CodexThread,
    ) {
        val resolvedThreadId = threadId ?: return
        _state.update { current ->
            val existing = current.threads[resolvedThreadId] ?: CodexThread(id = resolvedThreadId, hasMetadata = false)
            current.copy(threads = current.threads + (resolvedThreadId to transform(existing)))
        }
    }
}

/** Only fresh terminal evidence with no remaining running turn retires an unscoped retry. */
private fun CodexEventState.retireSettledThreadRetry(
    thread: CodexThread,
    evidence: List<CodexTurn>,
): Map<String, CodexFailure> = if (
    threadFailures[thread.id]?.willRetry == true &&
    evidence.any { it.status in terminalTurnStatuses && it.id == thread.turns.lastOrNull()?.id } &&
    thread.turns.none { it.status == "inProgress" }
) threadFailures - thread.id else threadFailures

private fun CodexThread.mergeMetadata(incoming: CodexThread, baseline: CodexThread?): CodexThread = incoming.copy(
    name = if (name != baseline?.name) name else incoming.name,
    status = if (status != (baseline?.status ?: CodexThreadStatus()) || turns.any { live ->
        live.status == "inProgress" && baseline?.turns.orEmpty().none { it.id == live.id } &&
            incoming.turns.none { it.id == live.id }
    }) status else if (incoming.status.type == "active" && incoming.turns.isNotEmpty() &&
        incoming.turns.filter { it.status == "inProgress" }.let { running ->
            running.isNotEmpty() && running.all { stale ->
                turns.any { it.id == stale.id && it.status in terminalTurnStatuses }
            }
        }
    ) status else incoming.status,
    turns = turns.mergeOrderedSnapshot(incoming.turns, CodexTurn::id) { live, snapshot ->
        live.mergeSnapshot(snapshot)
    },
)

private fun CodexTurn.mergeSnapshot(incoming: CodexTurn): CodexTurn = incoming.copy(
    status = if (status in terminalTurnStatuses && incoming.status !in terminalTurnStatuses) status else incoming.status,
    error = if (status in terminalTurnStatuses && incoming.status !in terminalTurnStatuses) error else incoming.error,
    completedAt = completedAt ?: incoming.completedAt,
    durationMs = durationMs ?: incoming.durationMs,
    items = items.mergeOrderedSnapshot(incoming.items, CodexThreadItem::id) { live, snapshot ->
        live.mergeSnapshot(snapshot)
    },
)

/** Full history supplies ordering; live-only entries arrived after that snapshot. */
private fun <T, K> List<T>.mergeOrderedSnapshot(
    incoming: List<T>,
    key: (T) -> K,
    merge: (T, T) -> T,
): List<T> {
    val remaining = associateByTo(linkedMapOf(), key)
    return incoming.map { snapshot ->
        remaining.remove(key(snapshot))?.let { live -> merge(live, snapshot) } ?: snapshot
    } + remaining.values
}

private val terminalTurnStatuses = setOf("completed", "failed", "interrupted")

private fun CodexThreadItem.mergeSnapshot(incoming: CodexThreadItem): CodexThreadItem = incoming.copy(
    raw = if (type == "imageGeneration" && advancesCompletionOf(incoming)) raw else incoming.raw,
    extra = if (type == "imageGeneration" && advancesCompletionOf(incoming)) extra else incoming.extra,
    status = if (advancesCompletionOf(incoming)) status else incoming.status,
    fileChanges = if (incoming.advancesCompletionOf(this)) incoming.fileChanges else fileChanges.ifEmpty { incoming.fileChanges },
    collabAgentCall = if (incoming.advancesCompletionOf(this)) incoming.collabAgentCall else collabAgentCall ?: incoming.collabAgentCall,
    text = existingStreamValue(text, incoming.text),
    output = existingStreamValue(output, incoming.output),
    reasoningSummary = reasoningSummary.mergeStreamParts(incoming.reasoningSummary),
    reasoningContent = reasoningContent.mergeStreamParts(incoming.reasoningContent),
)

private fun CodexThreadItem.advancesCompletionOf(existing: CodexThreadItem): Boolean {
    val terminal = setOf("completed", "failed", "declined", "interrupted")
    return status in terminal && existing.status !in terminal
}

private fun CodexThread.upsertTurn(turn: CodexTurn, authoritative: Boolean): CodexThread {
    val existing = turns.firstOrNull { candidate -> candidate.id == turn.id }
    val resolved = if (authoritative || existing == null) turn else existing.mergeStarted(turn)
    return copy(turns = turns.upsertBy(CodexTurn::id, resolved))
}

private fun CodexTurn.mergeStarted(incoming: CodexTurn): CodexTurn = incoming.copy(
    status = if (status in terminalTurnStatuses && incoming.status !in terminalTurnStatuses) status else incoming.status,
    error = if (status in terminalTurnStatuses && incoming.status !in terminalTurnStatuses) error else incoming.error,
    items = incoming.items.fold(items) { current, item ->
        val existing = current.firstOrNull { candidate -> candidate.id == item.id }
        current.upsertBy(CodexThreadItem::id, existing?.mergeStarted(item) ?: item)
    },
)

private fun CodexTurn.upsertItem(item: CodexThreadItem, authoritative: Boolean): CodexTurn {
    val existing = items.firstOrNull { candidate -> candidate.id == item.id }
    val resolved = if (authoritative || existing == null) item else existing.mergeStarted(item)
    return copy(items = items.upsertBy(CodexThreadItem::id, resolved))
}

private fun CodexThreadItem.mergeStarted(incoming: CodexThreadItem): CodexThreadItem = incoming.copy(
    fileChanges = fileChanges.ifEmpty { incoming.fileChanges },
    collabAgentCall = collabAgentCall ?: incoming.collabAgentCall,
    text = incoming.text?.takeIf(String::isNotEmpty) ?: text,
    output = incoming.output?.takeIf(String::isNotEmpty) ?: output,
    reasoningSummary = incoming.reasoningSummary.ifEmpty { reasoningSummary },
    reasoningContent = incoming.reasoningContent.ifEmpty { reasoningContent },
)

private fun <T, K> List<T>.upsertBy(key: (T) -> K, value: T): List<T> {
    val index = indexOfFirst { candidate -> key(candidate) == key(value) }
    return if (index < 0) this + value else toMutableList().apply { this[index] = value }
}

private fun List<String>.appendAt(index: Int, delta: String): List<String> {
    val safeIndex = index.coerceAtLeast(0)
    val mutable = toMutableList()
    while (mutable.size <= safeIndex) mutable += ""
    mutable[safeIndex] = mutable[safeIndex] + delta
    return mutable
}

private fun existingStreamValue(existing: String?, incoming: String?): String? = when {
    incoming.isNullOrEmpty() -> existing
    existing.isNullOrEmpty() -> incoming
    existing.startsWith(incoming) -> existing
    else -> incoming
}

private fun List<String>.mergeStreamParts(incoming: List<String>): List<String> {
    if (incoming.isEmpty()) return this
    val merged = toMutableList()
    incoming.forEachIndexed { index, value ->
        while (merged.size <= index) merged += ""
        merged[index] = existingStreamValue(merged[index], value).orEmpty()
    }
    return merged
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
