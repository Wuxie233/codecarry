package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.model.SseEvent

enum class ChatSubagentActivity {
    Running,
    AwaitingReply,
    Retrying,
    Completed,
}

data class ChatSubagentItem(
    val id: String,
    val title: String,
    val directory: String,
    val updatedAt: Long,
    val activity: ChatSubagentActivity,
)

internal fun buildDirectChatSubagents(
    parentSessionId: String,
    sessions: List<Session>,
    statuses: Map<String, SessionStatus>,
    questions: Map<String, List<SseEvent.QuestionAsked>>,
    permissions: Map<String, List<SseEvent.PermissionAsked>>,
    allowedSessionIds: Set<String> = sessions.mapTo(mutableSetOf(), Session::id),
): List<ChatSubagentItem> = sessions
    .asSequence()
    .filter { session -> session.id in allowedSessionIds && session.parentId == parentSessionId }
    .map { session ->
        val status = statuses[session.id] ?: SessionStatus.Idle
        val activity = when {
            questions[session.id].orEmpty().isNotEmpty() || permissions[session.id].orEmpty().isNotEmpty() ->
                ChatSubagentActivity.AwaitingReply
            status is SessionStatus.Retry -> ChatSubagentActivity.Retrying
            status is SessionStatus.Busy -> ChatSubagentActivity.Running
            else -> ChatSubagentActivity.Completed
        }
        ChatSubagentItem(
            id = session.id,
            title = session.title?.takeIf { it.isNotBlank() }
                ?: session.slug.takeIf { it.isNotBlank() }
                ?: session.id,
            directory = session.directory,
            updatedAt = session.time.updated.takeIf { it > 0L } ?: session.time.created,
            activity = activity,
        )
    }
    .sortedByDescending(ChatSubagentItem::updatedAt)
    .toList()

internal fun filterChatSubagentHistory(
    items: List<ChatSubagentItem>,
    titleQuery: String,
): List<ChatSubagentItem> {
    val query = titleQuery.trim()
    return items.filter { item ->
        item.activity == ChatSubagentActivity.Completed &&
            (query.isEmpty() || item.title.contains(query, ignoreCase = true))
    }
}

internal fun ChatSubagentItem.isRunning(): Boolean = activity != ChatSubagentActivity.Completed
