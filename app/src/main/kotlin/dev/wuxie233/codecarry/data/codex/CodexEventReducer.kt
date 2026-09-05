package dev.wuxie233.codecarry.data.codex

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

data class CodexEventState(
    val threads: Map<String, CodexThread> = emptyMap(),
    val goals: Map<String, CodexGoal> = emptyMap(),
    val knownGoalThreadIds: Set<String> = emptySet(),
    val archivedThreadIds: Set<String> = emptySet(),
    val threadErrors: Map<String, String> = emptyMap(),
)

class CodexEventReducer(
    initialThreads: List<CodexThread> = emptyList(),
) {
    private val _state = MutableStateFlow(
        CodexEventState(threads = initialThreads.associateBy(CodexThread::id)),
    )
    val state: StateFlow<CodexEventState> = _state.asStateFlow()

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
                    fresh != null && live != null -> live.mergeMetadata(fresh)
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
                threadErrors = current.threadErrors.filterKeys { it in finalIds },
            )
        }
    }

    fun upsertThread(thread: CodexThread) {
        _state.update { current ->
            val merged = current.threads[thread.id]?.mergeMetadata(thread) ?: thread
            current.copy(threads = current.threads + (thread.id to merged))
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

    fun upsertThreadAuthoritative(thread: CodexThread) {
        _state.update { current ->
            current.copy(threads = current.threads + (thread.id to thread))
        }
    }

    fun removeThread(threadId: String) {
        _state.update { current ->
            current.copy(
                threads = current.threads - threadId,
                goals = current.goals - threadId,
                knownGoalThreadIds = current.knownGoalThreadIds - threadId,
                archivedThreadIds = current.archivedThreadIds - threadId,
                threadErrors = current.threadErrors - threadId,
            )
        }
    }

    fun process(notification: CodexNotification) {
        when (notification.method) {
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
                updateThread(notification.threadId) { thread ->
                    thread.upsertTurn(turn, authoritative = false)
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
                    current.copy(threadErrors = current.threadErrors + (threadId to notification.params.errorMessage()))
                }
            }
        }
    }

    fun clear() {
        _state.value = CodexEventState()
    }

    private fun clearThreadError(threadId: String) {
        _state.update { current -> current.copy(threadErrors = current.threadErrors - threadId) }
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
            val existing = current.threads[resolvedThreadId] ?: CodexThread(id = resolvedThreadId)
            current.copy(threads = current.threads + (resolvedThreadId to transform(existing)))
        }
    }
}

private fun CodexThread.mergeMetadata(incoming: CodexThread): CodexThread = incoming.copy(
    turns = if (incoming.turns.isEmpty()) turns else incoming.turns.fold(turns) { current, turn ->
        val existing = current.firstOrNull { candidate -> candidate.id == turn.id }
        current.upsertBy(CodexTurn::id, existing?.mergeSnapshot(turn) ?: turn)
    },
)

private fun CodexTurn.mergeSnapshot(incoming: CodexTurn): CodexTurn = incoming.copy(
    items = incoming.items.fold(items) { current, item ->
        val existing = current.firstOrNull { candidate -> candidate.id == item.id }
        current.upsertBy(CodexThreadItem::id, existing?.mergeSnapshot(item) ?: item)
    },
)

private fun CodexThreadItem.mergeSnapshot(incoming: CodexThreadItem): CodexThreadItem = incoming.copy(
    text = existingStreamValue(text, incoming.text),
    output = existingStreamValue(output, incoming.output),
    reasoningSummary = reasoningSummary.mergeStreamParts(incoming.reasoningSummary),
    reasoningContent = reasoningContent.mergeStreamParts(incoming.reasoningContent),
)

private fun CodexThread.upsertTurn(turn: CodexTurn, authoritative: Boolean): CodexThread {
    val existing = turns.firstOrNull { candidate -> candidate.id == turn.id }
    val resolved = if (authoritative || existing == null) turn else existing.mergeStarted(turn)
    return copy(turns = turns.upsertBy(CodexTurn::id, resolved))
}

private fun CodexTurn.mergeStarted(incoming: CodexTurn): CodexTurn = incoming.copy(
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

private fun JsonObject.errorMessage(): String {
    val error = this["error"] as? JsonObject
    return error?.string("message")
        ?: string("message")
        ?: (error?.get("details") as? JsonArray)?.joinToString("\n") { element -> element.toString() }
        ?: "Codex request failed"
}
