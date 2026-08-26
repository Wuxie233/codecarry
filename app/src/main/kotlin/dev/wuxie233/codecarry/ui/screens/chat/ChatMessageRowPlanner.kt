package dev.wuxie233.codecarry.ui.screens.chat

import dev.wuxie233.codecarry.domain.model.Part
import dev.wuxie233.codecarry.domain.model.ToolState
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal enum class ChatMessageSegmentPosition {
    Single,
    First,
    Middle,
    Last,
}

internal class ChatMessageRowPlanningState {
    private val plansByPart = mutableMapOf<ChatMarkdownPartIdentity, MarkdownRenderPlan>()

    internal fun previous(messageId: String, partId: String): MarkdownRenderPlan? =
        plansByPart[ChatMarkdownPartIdentity(messageId, partId)]

    internal fun update(messageId: String, partId: String, plan: MarkdownRenderPlan) {
        plansByPart[ChatMarkdownPartIdentity(messageId, partId)] = plan
    }

    internal fun retain(parts: Set<ChatMarkdownPartIdentity>) {
        plansByPart.keys.retainAll(parts)
    }
}

internal data class ChatMarkdownPartIdentity(val messageId: String, val partId: String)

internal data class ChatAutoFollowTarget(
    val messageId: String?,
    val partId: String?,
    val contentLength: Int,
    val contentHash: Int,
    val rowCount: Int,
    val lastRowKey: String?,
)

internal sealed interface ChatMessageRow {
    val chatMessage: ChatMessage
    val sourceMessageIndex: Int
    val key: String
    val position: ChatMessageSegmentPosition

    data class Whole(
        override val chatMessage: ChatMessage,
        override val sourceMessageIndex: Int,
    ) : ChatMessageRow {
        override val key: String = "message:${chatMessage.message.id}:whole"
        override val position: ChatMessageSegmentPosition = ChatMessageSegmentPosition.Single
    }

    data class Think(
        override val chatMessage: ChatMessage,
        override val sourceMessageIndex: Int,
        val partIndex: Int,
        val part: Part.Reasoning,
    ) : ChatMessageRow {
        override val key: String = "message:${chatMessage.message.id}:think:${part.id}"
        override val position: ChatMessageSegmentPosition = ChatMessageSegmentPosition.Single
    }

    data class Skill(
        override val chatMessage: ChatMessage,
        override val sourceMessageIndex: Int,
        val partIndex: Int,
        val part: Part.Tool,
    ) : ChatMessageRow {
        override val key: String = "message:${chatMessage.message.id}:skill:${part.id}"
        override val position: ChatMessageSegmentPosition = ChatMessageSegmentPosition.Single
    }

    data class Tool(
        override val chatMessage: ChatMessage,
        override val sourceMessageIndex: Int,
        val partIndex: Int,
        val part: Part.Tool,
    ) : ChatMessageRow {
        override val key: String = "message:${chatMessage.message.id}:tool:${part.id}"
        override val position: ChatMessageSegmentPosition = ChatMessageSegmentPosition.Single
    }

    data class Content(
        override val chatMessage: ChatMessage,
        override val sourceMessageIndex: Int,
        val partIndex: Int,
        val part: Part,
    ) : ChatMessageRow {
        override val key: String = "message:${chatMessage.message.id}:content:${part.id}"
        override val position: ChatMessageSegmentPosition = ChatMessageSegmentPosition.Single
    }

    data class TextChunk(
        override val chatMessage: ChatMessage,
        override val sourceMessageIndex: Int,
        val partIndex: Int,
        val partId: String,
        val blockIndex: Int,
        val markdown: MarkdownRenderBlock,
        override val position: ChatMessageSegmentPosition,
    ) : ChatMessageRow {
        override val key: String =
            "message:${chatMessage.message.id}:part:$partId:part-$partIndex:type-markdown-block:${markdown.key}"
        val showsSteps: Boolean get() = false
    }
}

internal fun planChatMessageRows(
    messages: List<ChatMessage>,
    planningState: ChatMessageRowPlanningState? = null,
): List<ChatMessageRow> {
    val activeParts = mutableSetOf<ChatMarkdownPartIdentity>()
    val rows = messages.flatMapIndexed { messageIndex, chatMessage ->
        planChatMessageRows(chatMessage, messageIndex, planningState, activeParts)
    }
    planningState?.retain(activeParts)
    return rows
}

internal fun timelineIndexForMessage(
    rows: List<ChatMessageRow>,
    sourceMessageIndex: Int,
    hasOlderMessages: Boolean,
    hasRoster: Boolean,
): Int {
    val rowIndex = rows.indexOfFirst { it.sourceMessageIndex == sourceMessageIndex }.coerceAtLeast(0)
    return timelineLeadingItemCount(hasOlderMessages, hasRoster) + rowIndex
}

internal fun pendingTimelineStartIndex(
    rows: List<ChatMessageRow>,
    hasOlderMessages: Boolean,
    hasRoster: Boolean,
    hasRevertBanner: Boolean,
): Int {
    return timelineLeadingItemCount(hasOlderMessages, hasRoster) +
        rows.size +
        (if (hasRevertBanner) 1 else 0)
}

internal fun chatAutoFollowTarget(
    messages: List<ChatMessage>,
    rows: List<ChatMessageRow>,
): ChatAutoFollowTarget {
    val target = messages.asReversed().firstNotNullOfOrNull { chatMessage ->
        chatMessage.parts.asReversed().firstNotNullOfOrNull { part ->
            val content = when (part) {
                is Part.Text -> part.text.takeIf {
                    it.isNotBlank() && part.synthetic != true && part.ignored != true
                }
                is Part.Reasoning -> part.text.takeIf(String::isNotBlank)
                else -> null
            }
            content?.let { Triple(chatMessage.message.id, part.id, it) }
        }
    }
    return ChatAutoFollowTarget(
        messageId = target?.first,
        partId = target?.second,
        contentLength = target?.third?.length ?: 0,
        contentHash = target?.third?.hashCode() ?: 0,
        rowCount = rows.size,
        lastRowKey = rows.lastOrNull()?.key,
    )
}

internal fun timelineLeadingItemCount(hasOlderMessages: Boolean, hasRoster: Boolean): Int {
    return (if (hasOlderMessages) 1 else 0) + (if (hasRoster) 1 else 0)
}

internal fun skillRowName(part: Part.Tool): String {
    val fromInput = toolInput(part)["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (fromInput.isNotEmpty()) return fromInput
    val raw = when (val state = part.state) {
        is ToolState.Pending -> state.raw
        else -> null
    }
    if (!raw.isNullOrBlank()) {
        val parsed = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject }
            .getOrNull()
            ?.get("name")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
        if (!parsed.isNullOrEmpty()) return parsed
        val firstLine = raw.lineSequence().firstOrNull().orEmpty().trim()
        if (firstLine.isNotEmpty()) return firstLine
    }
    return part.callId.ifBlank { part.id }
}

internal fun toolResultText(part: Part.Tool): String {
    return when (val state = part.state) {
        is ToolState.Completed -> state.output
        is ToolState.Error -> state.error
        is ToolState.Running -> state.output
        is ToolState.Pending -> ""
    }
}

private fun toolInput(part: Part.Tool): Map<String, kotlinx.serialization.json.JsonElement> {
    return when (val state = part.state) {
        is ToolState.Pending -> state.input
        is ToolState.Running -> state.input
        is ToolState.Completed -> state.input
        is ToolState.Error -> state.input
    }
}

private fun planChatMessageRows(
    chatMessage: ChatMessage,
    messageIndex: Int,
    planningState: ChatMessageRowPlanningState?,
    activeParts: MutableSet<ChatMarkdownPartIdentity>,
): List<ChatMessageRow> {
    if (!chatMessage.isAssistant) return listOf(ChatMessageRow.Whole(chatMessage, messageIndex))
    if (!hasIndependentProcessParts(chatMessage)) {
        return planTextOnlyRows(chatMessage, messageIndex, planningState, activeParts)
    }
    return chatMessage.parts.flatMapIndexed { partIndex, part ->
        when {
            part is Part.Reasoning && part.text.isNotBlank() -> {
                listOf(ChatMessageRow.Think(chatMessage, messageIndex, partIndex, part))
            }
            part is Part.Tool && part.tool == "todoread" -> emptyList()
            part is Part.Tool && part.tool == "skill" -> {
                listOf(ChatMessageRow.Skill(chatMessage, messageIndex, partIndex, part))
            }
            part is Part.Tool -> {
                listOf(ChatMessageRow.Tool(chatMessage, messageIndex, partIndex, part))
            }
            part is Part.Text && isRenderableText(part) -> {
                planTextPartRows(
                    chatMessage = chatMessage,
                    messageIndex = messageIndex,
                    partIndex = partIndex,
                    textPart = part,
                    planningState = planningState,
                    activeParts = activeParts,
                    allowWhole = false,
                )
            }
            isContentPart(part) -> {
                listOf(ChatMessageRow.Content(chatMessage, messageIndex, partIndex, part))
            }
            else -> emptyList()
        }
    }.ifEmpty { listOf(ChatMessageRow.Whole(chatMessage, messageIndex)) }
}

private fun planTextOnlyRows(
    chatMessage: ChatMessage,
    messageIndex: Int,
    planningState: ChatMessageRowPlanningState?,
    activeParts: MutableSet<ChatMarkdownPartIdentity>,
): List<ChatMessageRow> {
    val renderableText = chatMessage.parts.withIndex().filter { (_, part) ->
        part is Part.Text && isRenderableText(part)
    }
    if (renderableText.size != 1) return listOf(ChatMessageRow.Whole(chatMessage, messageIndex))
    val target = renderableText.single()
    return planTextPartRows(
        chatMessage = chatMessage,
        messageIndex = messageIndex,
        partIndex = target.index,
        textPart = target.value as Part.Text,
        planningState = planningState,
        activeParts = activeParts,
        allowWhole = true,
    )
}

private fun planTextPartRows(
    chatMessage: ChatMessage,
    messageIndex: Int,
    partIndex: Int,
    textPart: Part.Text,
    planningState: ChatMessageRowPlanningState?,
    activeParts: MutableSet<ChatMarkdownPartIdentity>,
    allowWhole: Boolean,
): List<ChatMessageRow> {
    val partIdentity = ChatMarkdownPartIdentity(chatMessage.message.id, textPart.id)
    activeParts += partIdentity
    val planned = planStreamingMarkdown(
        source = textPart.text,
        previous = planningState?.previous(partIdentity.messageId, partIdentity.partId),
    )
    val plan = (planned as? MarkdownStreamingPlanResult.Success)?.plan
        ?: return if (allowWhole) {
            listOf(ChatMessageRow.Whole(chatMessage, messageIndex))
        } else {
            emptyList()
        }
    planningState?.update(partIdentity.messageId, partIdentity.partId, plan)
    if (allowWhole && plan.blocks.size == 1) {
        return listOf(ChatMessageRow.Whole(chatMessage, messageIndex))
    }
    return plan.blocks.mapIndexed { blockIndex, block ->
        ChatMessageRow.TextChunk(
            chatMessage = chatMessage,
            sourceMessageIndex = messageIndex,
            partIndex = partIndex,
            partId = textPart.id,
            blockIndex = blockIndex,
            markdown = block,
            position = if (plan.blocks.size == 1) {
                ChatMessageSegmentPosition.Single
            } else {
                chunkPosition(blockIndex, plan.blocks.lastIndex)
            },
        )
    }
}

private fun hasIndependentProcessParts(chatMessage: ChatMessage): Boolean {
    return chatMessage.parts.any { part ->
        (part is Part.Reasoning && part.text.isNotBlank()) ||
            (part is Part.Tool && part.tool != "todoread") ||
            isContentPart(part)
    }
}

private fun isRenderableText(part: Part.Text): Boolean {
    return part.text.isNotBlank() && part.synthetic != true && part.ignored != true
}

private fun isContentPart(part: Part): Boolean {
    return part is Part.File ||
        part is Part.Patch ||
        part is Part.Permission ||
        part is Part.Question ||
        part is Part.Abort ||
        part is Part.Retry
}

private fun chunkPosition(index: Int, lastIndex: Int): ChatMessageSegmentPosition {
    return when (index) {
        0 -> ChatMessageSegmentPosition.First
        lastIndex -> ChatMessageSegmentPosition.Last
        else -> ChatMessageSegmentPosition.Middle
    }
}
