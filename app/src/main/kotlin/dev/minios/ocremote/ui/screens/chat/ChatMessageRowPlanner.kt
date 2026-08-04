package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.domain.model.Part

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
        val showsSteps: Boolean get() = position == ChatMessageSegmentPosition.First
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

private fun planChatMessageRows(
    chatMessage: ChatMessage,
    messageIndex: Int,
    planningState: ChatMessageRowPlanningState?,
    activeParts: MutableSet<ChatMarkdownPartIdentity>,
): List<ChatMessageRow> {
    if (!chatMessage.isAssistant) return listOf(ChatMessageRow.Whole(chatMessage, messageIndex))

    val renderableText = chatMessage.parts.withIndex().filter { (_, part) ->
        part is Part.Text && part.text.isNotBlank() && part.synthetic != true && part.ignored != true
    }
    if (renderableText.size != 1) return listOf(ChatMessageRow.Whole(chatMessage, messageIndex))

    val target = renderableText.single()
    val supportedParts = chatMessage.parts.all { part ->
        part === target.value || part is Part.Tool || part is Part.StepStart || part is Part.StepFinish
    }
    if (!supportedParts) return listOf(ChatMessageRow.Whole(chatMessage, messageIndex))

    val textPart = target.value as Part.Text
    val partIdentity = ChatMarkdownPartIdentity(chatMessage.message.id, textPart.id)
    activeParts += partIdentity
    val planned = planStreamingMarkdown(
        source = textPart.text,
        previous = planningState?.previous(partIdentity.messageId, partIdentity.partId),
    )
    val plan = (planned as? MarkdownStreamingPlanResult.Success)?.plan
        ?: return listOf(ChatMessageRow.Whole(chatMessage, messageIndex))
    planningState?.update(partIdentity.messageId, partIdentity.partId, plan)
    if (plan.blocks.size == 1) return listOf(ChatMessageRow.Whole(chatMessage, messageIndex))

    return plan.blocks.mapIndexed { blockIndex, block ->
        ChatMessageRow.TextChunk(
            chatMessage = chatMessage,
            sourceMessageIndex = messageIndex,
            partIndex = target.index,
            partId = textPart.id,
            blockIndex = blockIndex,
            markdown = block,
            position = chunkPosition(blockIndex, plan.blocks.lastIndex),
        )
    }
}

private fun chunkPosition(index: Int, lastIndex: Int): ChatMessageSegmentPosition {
    return when (index) {
        0 -> ChatMessageSegmentPosition.First
        lastIndex -> ChatMessageSegmentPosition.Last
        else -> ChatMessageSegmentPosition.Middle
    }
}
