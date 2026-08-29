package dev.wuxie233.codecarry.data.dsh

import dev.wuxie233.codecarry.domain.model.Session
import dev.wuxie233.codecarry.domain.model.SessionStatus
import dev.wuxie233.codecarry.domain.model.SseEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class DshMappedSessions(
    val sessions: List<Session>,
    val statuses: Map<String, SessionStatus>,
)

fun mapDshEventStateToSessions(state: DshEventState): DshMappedSessions {
    val membership = linkedMapOf<String, String>()
    val workspaceById = linkedMapOf<String, JsonObject>()
    val order = state.workspaceOrder.ifEmpty { state.workspaces.keys.toList() }
    order.forEach { workspaceId ->
        val workspace = state.workspaces[workspaceId] ?: return@forEach
        workspaceById[workspaceId] = workspace
        dshWorkspaceSessionIds(workspace).forEach { sessionId ->
            membership.putIfAbsent(sessionId, workspaceId)
        }
    }
    state.sessions.keys.forEach { sessionId ->
        membership.putIfAbsent(sessionId, "")
    }

    val sessions = membership.keys.filter { sessionId ->
        state.sessions[sessionId]?.blank != true
    }.map { sessionId ->
        val snapshot = state.sessions[sessionId]
        val workspaceId = membership[sessionId].orEmpty()
        val workspace = workspaceById[workspaceId]
        val directory = snapshot?.cwd?.takeIf { it.isNotBlank() }
            ?: workspace?.let(::dshWorkspacePath).orEmpty()
        val title = snapshot?.let { dshProjectionTitle(it.projections) }
            ?: workspace?.let(::dshWorkspaceTitle)
        val created = parseInstant(workspace?.get("createdAt"))
            ?: snapshot?.events?.minOfOrNull { it.time }
            ?: 0L
        val updated = snapshot?.events?.maxOfOrNull { it.time }
            ?: parseInstant(workspace?.get("updatedAt"))
            ?: created
        Session(
            id = sessionId,
            directory = directory,
            parentId = snapshot?.parentSessionId,
            title = title,
            time = Session.Time(
                created = created,
                updated = updated,
                archived = if (sessionId in state.archivedSessionIds) updated.coerceAtLeast(1L) else null,
            ),
        )
    }
    val statuses = state.sessions.mapValues { (_, snapshot) ->
        if (snapshot.running) SessionStatus.Busy else SessionStatus.Idle
    }
    return DshMappedSessions(sessions = sessions, statuses = statuses)
}

fun mapDshApproval(approval: DshPendingApproval): SseEvent.PermissionAsked =
    SseEvent.PermissionAsked(
        id = approval.eventId,
        sessionId = approval.sessionId,
        permission = approval.toolName,
        patterns = listOfNotNull(approval.reason, approval.callId),
    )

fun mapDshQuestion(question: DshPendingQuestion): SseEvent.QuestionAsked =
    SseEvent.QuestionAsked(
        id = question.eventId,
        sessionId = question.sessionId,
        questions = question.questions.map { item ->
            SseEvent.QuestionAsked.Question(
                header = item.header.orEmpty(),
                question = item.question,
                multiple = item.multiSelect,
                custom = true,
                options = item.options.map { option ->
                    SseEvent.QuestionAsked.Option(
                        label = option.label,
                        description = option.description.orEmpty(),
                    )
                },
            )
        },
    )

fun dshQuestionAnswer(
    questions: List<DshQuestionItem>,
    answers: List<List<String>>,
): DshQuestionAnswer = DshQuestionAnswer(
    answers = questions.mapIndexed { index, question ->
        val labels = question.options.mapTo(mutableSetOf()) { it.label }
        val raw = answers.getOrNull(index).orEmpty()
        val selected = raw.filter { it in labels }.distinct()
        val custom = raw.firstOrNull { it !in labels }?.trim()?.takeIf { it.isNotEmpty() }
        val exclusiveCustom = custom != null && !question.multiSelect
        DshQuestionAnswerItem(
            id = question.id,
            selected = if (exclusiveCustom) emptyList() else selected,
            custom = custom,
        )
    },
)

private fun parseInstant(value: kotlinx.serialization.json.JsonElement?): Long? {
    val raw = (value as? JsonPrimitive)?.contentOrNull ?: return null
    raw.toLongOrNull()?.let { return it }
    return runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()
}
