package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.data.api.PiStackMessagePageDto
import dev.minios.ocremote.data.api.PiStackStructuredMessageDto
import dev.minios.ocremote.data.api.PiStackStructuredPartDto
import dev.minios.ocremote.data.api.PiStackToolState
import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.MessageWithParts
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.TimeInfo
import dev.minios.ocremote.domain.model.ToolState
import java.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class PiStackChatHistoryPage(
    val items: List<MessageWithParts>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

internal data class PiStackChatHistoryState(
    val messages: List<MessageWithParts> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

internal fun PiStackMessagePageDto.toChatHistoryPage(): PiStackChatHistoryPage = PiStackChatHistoryPage(
    items = items.map(PiStackStructuredMessageDto::toMessageWithParts),
    nextCursor = nextCursor,
    hasMore = hasMore,
)

private fun PiStackStructuredMessageDto.toMessageWithParts(): MessageWithParts {
    val created = createdAt.toEpochMillis()
    val completed = completedAt.toEpochMillisOrNull()
    val message = if (role == "user") {
        Message.User(id = id, sessionId = sessionId, time = TimeInfo(created, completed))
    } else {
        Message.Assistant(
            id = id,
            sessionId = sessionId,
            time = TimeInfo(created, completed),
            finish = status.takeUnless { it == "streaming" },
        )
    }
    return MessageWithParts(
        info = message,
        parts = parts.mapNotNull { part -> part.toPart(sessionId, id) },
    )
}

private fun PiStackStructuredPartDto.toPart(sessionId: String, messageId: String): Part? = when (this) {
    is PiStackStructuredPartDto.Text -> Part.Text(id, sessionId, messageId, text)
    is PiStackStructuredPartDto.Tool -> Part.Tool(
        id = id,
        sessionId = sessionId,
        messageId = messageId,
        callId = toolCallId,
        tool = toolName,
        state = when (stateKind) {
            PiStackToolState.Pending, PiStackToolState.Unknown -> ToolState.Pending(input.asObject())
            PiStackToolState.Running -> ToolState.Running(input.asObject(), output.asDisplayText())
            PiStackToolState.Completed -> ToolState.Completed(input.asObject(), output.asDisplayText())
            PiStackToolState.Error -> ToolState.Error(input.asObject(), error.orEmpty())
        },
    )
    is PiStackStructuredPartDto.Unknown -> null
}

internal fun mergePiStackLatestHistory(
    page: PiStackChatHistoryPage,
    liveMessages: List<MessageWithParts>,
): PiStackChatHistoryState = PiStackChatHistoryState(
    messages = mergePiStackMessages(page.items, liveMessages),
    nextCursor = page.nextCursor,
    hasMore = page.hasMore,
)

internal fun mergePiStackOlderHistory(
    current: PiStackChatHistoryState,
    page: PiStackChatHistoryPage,
): PiStackChatHistoryState = PiStackChatHistoryState(
    messages = mergePiStackMessages(page.items, current.messages),
    nextCursor = page.nextCursor,
    hasMore = page.hasMore,
)

private fun mergePiStackMessages(
    historical: List<MessageWithParts>,
    authoritativeCurrent: List<MessageWithParts>,
): List<MessageWithParts> {
    val byId = LinkedHashMap<String, MessageWithParts>(historical.size + authoritativeCurrent.size)
    historical.forEach { message -> byId[message.info.id] = message }
    authoritativeCurrent.forEach { message -> byId[message.info.id] = message }
    return byId.values.sortedWith(
        compareBy<MessageWithParts> { it.info.time.created }
            .thenBy { it.info.id },
    )
}

private fun JsonElement?.asObject(): Map<String, JsonElement> = (this as? JsonObject).orEmpty()

private fun JsonElement?.asDisplayText(): String = when (this) {
    null -> ""
    is JsonPrimitive -> contentOrNull.orEmpty()
    else -> toString()
}.take(64 * 1024)

private fun String?.toEpochMillis(): Long = this?.let { value ->
    runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
} ?: 0L

private fun String?.toEpochMillisOrNull(): Long? = this?.let { value ->
    runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}
