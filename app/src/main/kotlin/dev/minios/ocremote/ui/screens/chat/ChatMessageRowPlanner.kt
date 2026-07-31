package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.domain.model.Part

internal enum class ChatMessageSegmentPosition {
    Single,
    First,
    Middle,
    Last,
}

internal data class PlannedMarkdownMessageChunk(
    val chunk: MarkdownMessageChunk,
    val math: List<MarkdownMathSegment.Math>,
)

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
        val chunkIndex: Int,
        val markdown: PlannedMarkdownMessageChunk,
        override val position: ChatMessageSegmentPosition,
    ) : ChatMessageRow {
        override val key: String =
            "message:${chatMessage.message.id}:part:$partId:part-$partIndex:type-text-chunk:chunk-$chunkIndex"
        val showsSteps: Boolean get() = position == ChatMessageSegmentPosition.First
    }
}

internal fun planChatMessageRows(messages: List<ChatMessage>): List<ChatMessageRow> {
    return messages.flatMapIndexed { messageIndex, chatMessage ->
        planChatMessageRows(chatMessage, messageIndex)
    }
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

private fun planChatMessageRows(chatMessage: ChatMessage, messageIndex: Int): List<ChatMessageRow> {
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
    val normalizedMarkdown = preserveRawHtmlPayload(textPart.text)
    val (placeholderMarkdown, math) = buildPlaceholderMarkdown(normalizedMarkdown)
    val chunks = planMarkdownMessageChunks(placeholderMarkdown)
    if (chunks.size == 1) return listOf(ChatMessageRow.Whole(chatMessage, messageIndex))

    return chunks.mapIndexed { chunkIndex, chunk ->
        ChatMessageRow.TextChunk(
            chatMessage = chatMessage,
            sourceMessageIndex = messageIndex,
            partIndex = target.index,
            partId = textPart.id,
            chunkIndex = chunkIndex,
            markdown = PlannedMarkdownMessageChunk(chunk = chunk, math = math),
            position = chunkPosition(chunkIndex, chunks.lastIndex),
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
