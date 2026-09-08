package dev.wuxie233.codecarry.ui.screens.chat

import dev.wuxie233.codecarry.data.codex.CodexThreadItem
import dev.wuxie233.codecarry.data.codex.CodexTurn
import dev.wuxie233.codecarry.domain.model.Message
import dev.wuxie233.codecarry.domain.model.Part
import dev.wuxie233.codecarry.domain.model.ToolState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Snapshot export is independent of composed rows, disclosure state and selection registrars. */
internal data class ConversationDocument(val messages: List<ConversationDocumentMessage>) {
    fun toMarkdown(): String = messages.filter { it.markdown.isNotBlank() }
        .joinToString("\n\n---\n\n") { "## ${it.role}\n\n${it.markdown}" }
}

internal data class ConversationDocumentMessage(val role: String, val markdown: String)

internal fun chatConversationDocument(messages: List<ChatMessage>): ConversationDocument =
    ConversationDocument(messages.map { message ->
        val body = message.parts.mapNotNull(::exportChatPart).toMutableList()
        (message.message as? Message.Assistant)?.error?.message?.takeIf(String::isNotBlank)
            ?.let { body += "[Error] $it" }
        ConversationDocumentMessage(message.message.role, body.joinToString("\n\n"))
    })

internal fun codexConversationDocument(turns: List<CodexTurn>): ConversationDocument =
    ConversationDocument(turns.flatMap { turn ->
        turn.items.map { item ->
            ConversationDocumentMessage(
                when (item.type) { "userMessage" -> "user"; "agentMessage" -> "assistant"; else -> item.type },
                codexItemMarkdown(item),
            )
        } + listOfNotNull(turn.error?.let { error ->
            val message = (error as? JsonObject)?.get("message") as? JsonPrimitive
            ConversationDocumentMessage("error", message?.contentOrNull ?: error.toString())
        })
    })

private fun exportChatPart(part: Part): String? = when (part) {
    is Part.Text -> part.text.takeUnless { part.ignored == true }
    is Part.Reasoning -> "[Reasoning]\n${part.text}"
    is Part.Tool -> {
        val output = when (val state = part.state) {
            is ToolState.Completed -> state.output
            is ToolState.Running -> state.output
            is ToolState.Error -> state.error
            is ToolState.Pending -> "[Pending]"
        }
        "[Tool: ${part.tool}]\n${exportFence(output)}"
    }
    is Part.File -> "[Attachment: ${part.filename ?: part.mime}]"
    is Part.Patch -> "[Files]\n${part.files.joinToString("\n") }"
    is Part.Subtask -> listOfNotNull("[Subagent: ${part.agent.orEmpty()}]", part.description, part.prompt).joinToString("\n")
    is Part.Retry -> "[Retry ${part.attempt}] ${part.errorMessage}"
    is Part.Abort -> "[Stopped] ${part.reason}"
    is Part.Permission -> part.message
    is Part.Question -> part.question
    is Part.Agent -> "[Agent: ${part.name}]"
    is Part.Compaction -> "[Context compacted]"
    else -> null
}

internal fun codexItemMarkdown(item: CodexThreadItem): String = buildList {
    if (item.type == "reasoning") {
        val summary = item.reasoningSummary.joinToString("\n\n").ifBlank { item.text.orEmpty() }
        val content = item.reasoningContent.joinToString("\n\n")
        add(summary)
        if (content != summary) add(content)
    } else item.text?.let(::add)
    item.command?.let { add(exportFence(it)) }
    item.output?.let { add(exportFence(it)) }
    item.fileChanges.forEach { add("[File: ${it.path}]\n${exportFence(it.diff)}") }
    item.collabAgentCall?.let { call ->
        call.prompt?.let(::add)
        call.receiverThreadIds.distinct().forEach { id ->
            val state = call.agentsStates[id]
            add("[Subagent: $id${state?.status?.let { ": $it" }.orEmpty()}]")
            state?.message?.let(::add)
        }
    }
    // Binary data URLs and opaque wire payloads never belong in a readable transcript.
    (item.raw["content"] as? JsonArray).orEmpty().forEach { value ->
        val type = ((value as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
        if (type == "image" || type == "localImage") add("[Image]")
    }
    if (item.type == "subAgentActivity") {
        val id = (item.raw["agentThreadId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        add("[Subagent: $id${item.status?.let { ": $it" }.orEmpty()}]")
    }
    if (item.type == "contextCompaction") add("[Context compacted]")
}.filter(String::isNotBlank).joinToString("\n\n")

private fun exportFence(text: String): String {
    val longestRun = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
    val fence = "`".repeat(maxOf(3, longestRun + 1))
    return "$fence\n$text\n$fence"
}
