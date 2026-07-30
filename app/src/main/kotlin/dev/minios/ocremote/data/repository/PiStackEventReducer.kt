package dev.minios.ocremote.data.repository

import dev.minios.ocremote.data.api.PiStackEventCursorDto
import dev.minios.ocremote.data.api.PiStackEventDto
import dev.minios.ocremote.data.api.PiStackQuestionDto
import dev.minios.ocremote.data.api.PiStackSessionDto
import dev.minios.ocremote.data.api.PiStackSessionState
import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.MessageWithParts
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.model.SseEvent
import dev.minios.ocremote.domain.model.TimeInfo
import dev.minios.ocremote.domain.model.ToolState
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class PiStackCursor(
    val generation: String,
    val eventId: String?,
    val sequence: Long,
)

internal sealed interface PiStackEventResult {
    data class Applied(val event: PiStackEventDto) : PiStackEventResult
    data object IgnoredDuplicate : PiStackEventResult
    data class ResyncRequired(val generation: String) : PiStackEventResult
}

internal class PiStackCursorGuard {
    private val cursors = mutableMapOf<String, PiStackCursor>()

    @Synchronized
    fun cursor(serverId: String): PiStackCursor? = cursors[serverId]

    @Synchronized
    fun installSnapshot(serverId: String, cursor: PiStackEventCursorDto) {
        cursors[serverId] = PiStackCursor(cursor.generation, cursor.eventId, cursor.sequence)
    }

    @Synchronized
    fun evaluate(serverId: String, event: PiStackEventDto): PiStackEventResult {
        val cursor = cursors[serverId] ?: return PiStackEventResult.ResyncRequired(event.generation)
        if (event.generation != cursor.generation) return PiStackEventResult.ResyncRequired(event.generation)
        if (event.sequence <= cursor.sequence) return PiStackEventResult.IgnoredDuplicate
        if (event.sequence != cursor.sequence + 1) return PiStackEventResult.ResyncRequired(event.generation)
        return PiStackEventResult.Applied(event)
    }

    @Synchronized
    fun advance(serverId: String, event: PiStackEventDto) {
        val cursor = cursors[serverId]
        check(cursor?.generation == event.generation && event.sequence == cursor.sequence + 1) {
            "Pi Stack cursor changed before event commit"
        }
        cursors[serverId] = PiStackCursor(event.generation, event.eventId, event.sequence)
    }

    @Synchronized
    fun clear(serverId: String) {
        cursors.remove(serverId)
    }

    @Synchronized
    fun clearAll() {
        cursors.clear()
    }
}

@Serializable
private data class StructuredMessagePayload(val message: StructuredMessageDto)

@Serializable
private data class MessageDeltaPayload(val messageId: String, val partId: String, val delta: String)

@Serializable
private data class ToolPayload(val messageId: String, val part: StructuredToolPartDto)

@Serializable
private data class StructuredMessageDto(
    val id: String,
    val sessionId: String,
    val promptId: String? = null,
    val role: String,
    val status: String,
    val parts: List<StructuredPartDto> = emptyList(),
    val createdAt: String? = null,
    val completedAt: String? = null,
)

@Serializable
private data class StructuredPartDto(
    val id: String,
    val type: String,
    val text: String = "",
    val toolCallId: String = "",
    val toolName: String = "",
    val state: String = "pending",
    val input: JsonElement? = null,
    val output: JsonElement? = null,
    val error: String? = null,
)

@Serializable
private data class StructuredToolPartDto(
    val id: String,
    val type: String = "tool",
    val toolCallId: String,
    val toolName: String,
    val state: String,
    val input: JsonElement? = null,
    val output: JsonElement? = null,
    val error: String? = null,
)

internal fun PiStackSessionDto.toSession(): Session = Session(
    id = id,
    projectId = projectId,
    directory = cwd,
    parentId = parentId,
    title = title,
    time = Session.Time(
        created = createdAt.toEpochMillis(),
        updated = updatedAt.toEpochMillis(),
        archived = endedAt?.toEpochMillis(),
    ),
)

internal fun PiStackSessionState.toSessionStatus(): SessionStatus = when (this) {
    PiStackSessionState.Busy, PiStackSessionState.Retry -> SessionStatus.Busy
    PiStackSessionState.Idle,
    PiStackSessionState.AwaitingCommand,
    PiStackSessionState.AwaitingSkip,
    PiStackSessionState.Ended,
    PiStackSessionState.Error,
    PiStackSessionState.Unknown -> SessionStatus.Idle
}

internal fun PiStackQuestionDto.toQuestionAsked(): SseEvent.QuestionAsked = SseEvent.QuestionAsked(
    id = id,
    sessionId = sessionId,
    questions = listOf(
        SseEvent.QuestionAsked.Question(
            question = payload.prompt,
            multiple = payload.kind == "multi_select",
            custom = payload.allowFreeformInput || payload.kind == "free_text",
            options = payload.options.map { option ->
                SseEvent.QuestionAsked.Option(option.label, option.description.orEmpty())
            },
        )
    ),
)

internal fun decodePiStackMessage(json: Json, event: PiStackEventDto): MessageWithParts =
    json.decodeFromJsonElement(StructuredMessagePayload.serializer(), event.payload).message.toMessageWithParts()

internal fun decodePiStackMessageDelta(json: Json, event: PiStackEventDto): Triple<String, String, String> =
    json.decodeFromJsonElement(MessageDeltaPayload.serializer(), event.payload).let {
        Triple(it.messageId, it.partId, it.delta)
    }

internal fun decodePiStackTool(json: Json, event: PiStackEventDto): Pair<String, Part.Tool> =
    json.decodeFromJsonElement(ToolPayload.serializer(), event.payload).let { payload ->
        payload.messageId to payload.part.toPart(event.scope.sessionId.orEmpty(), payload.messageId)
    }

private fun StructuredMessageDto.toMessageWithParts(): MessageWithParts {
    val created = createdAt.toEpochMillis()
    val completed = completedAt?.toEpochMillis()
    val message = if (role == "user") {
        Message.User(id = id, sessionId = sessionId, time = TimeInfo(created, completed))
    } else {
        Message.Assistant(
            id = id,
            sessionId = sessionId,
            time = TimeInfo(created, completed),
            finish = if (status == "streaming") null else status,
        )
    }
    return MessageWithParts(message, parts.mapNotNull { it.toPart(sessionId, id) })
}

private fun StructuredPartDto.toPart(sessionId: String, messageId: String): Part? = when (type) {
    "text" -> Part.Text(id = id, sessionId = sessionId, messageId = messageId, text = text)
    "tool" -> StructuredToolPartDto(id, type, toolCallId, toolName, state, input, output, error)
        .toPart(sessionId, messageId)
    else -> null
}

private fun StructuredToolPartDto.toPart(sessionId: String, messageId: String): Part.Tool = Part.Tool(
    id = id,
    sessionId = sessionId,
    messageId = messageId,
    callId = toolCallId,
    tool = toolName,
    state = when (state) {
        "running" -> ToolState.Running(input = input.asObject(), output = output.asDisplayText())
        "completed" -> ToolState.Completed(input = input.asObject(), output = output.asDisplayText())
        "error" -> ToolState.Error(input = input.asObject(), error = error.orEmpty())
        else -> ToolState.Pending(input = input.asObject())
    },
)

private fun JsonElement?.asObject(): Map<String, JsonElement> = (this as? JsonObject).orEmpty()

private fun JsonElement?.asDisplayText(): String = when (this) {
    null -> ""
    is JsonPrimitive -> contentOrNull.orEmpty()
    else -> toString()
}

private fun String?.toEpochMillis(): Long = this?.let {
    runCatching { Instant.parse(it).toEpochMilli() }.getOrDefault(0L)
} ?: 0L
