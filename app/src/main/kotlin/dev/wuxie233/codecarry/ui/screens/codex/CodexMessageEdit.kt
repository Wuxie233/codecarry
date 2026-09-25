package dev.wuxie233.codecarry.ui.screens.codex

import dev.wuxie233.codecarry.data.codex.CodexThread
import dev.wuxie233.codecarry.data.codex.CodexUserInput
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** A staged edit. Nothing on the server changes until the user confirms sending. */
data class CodexMessageEdit(
    val itemId: String,
    val turnId: String,
    val removedTurnCount: Int,
    val originalTurnIds: List<String>,
    val retainedTurnIds: List<String>,
    val originalText: String,
    val attachments: List<CodexComposerAttachment>,
)

/** Rollback counts whole turns; steering messages cannot be edited individually. */
internal fun planCodexMessageEdit(thread: CodexThread, itemId: String): CodexMessageEdit? {
    if (thread.turns.any { codexTurnIsRunning(it.status) }) return null
    val index = thread.turns.indexOfFirst { turn -> turn.items.any { it.id == itemId } }
    if (index < 0) return null
    val turn = thread.turns[index]
    if (turn.status !in setOf("completed", "interrupted", "failed", "cancelled", "canceled")) return null
    val message = turn.items.firstOrNull { it.type == "userMessage" } ?: return null
    if (message.id != itemId) return null
    // Keep every non-text wire input verbatim, including daemon-local image paths.
    // An unknown input must not silently disappear during an edit.
    val content = message.raw["content"] as? JsonArray ?: return null
    val parts = content.map { it as? JsonObject ?: return null }
    if (parts.any { it.string("type") !in setOf("text", "image", "localImage", "skill", "mention") }) return null
    val attachments = parts.filter { it.string("type") != "text" }.mapIndexed { attachmentIndex, part ->
        CodexComposerAttachment(
            id = "edit:$itemId:$attachmentIndex",
            label = part.string("name") ?: part.string("path")?.substringAfterLast('/')
                ?: "", // The Compose attachment chip supplies its localized image label.
            input = CodexUserInput.Raw(part),
        )
    }
    val text = parts.filter { it.string("type") == "text" }.joinToString("\n") { it.string("text").orEmpty() }
    if (text.isBlank() && attachments.isEmpty()) return null
    return CodexMessageEdit(itemId, turn.id, thread.turns.size - index,
        thread.turns.map { it.id }, thread.turns.take(index).map { it.id }, text, attachments)
}

internal fun appendCodexQuote(draft: String, text: String): String {
    if (text.isBlank()) return draft
    val quote = text.trim().lineSequence().joinToString("\n") { "> $it" }
    return (if (draft.isBlank()) "" else draft.trimEnd() + "\n\n") + quote + "\n\n"
}

/** SavedState is for small metadata only; image bytes and large inputs stay in memory. */
internal fun persistableCodexAttachment(attachment: CodexComposerAttachment): String? {
    val input = attachment.input.toJson()
    if (input.string("url")?.startsWith("data:", ignoreCase = true) == true) return null
    val serialized = buildJsonObject {
        put("id", attachment.id)
        put("label", attachment.label)
        put("input", input)
    }.toString()
    return serialized.takeIf { it.toByteArray(Charsets.UTF_8).size <= 16 * 1024 }
}

internal fun restoreCodexAttachment(serialized: String): CodexComposerAttachment? = runCatching {
    val value = Json.parseToJsonElement(serialized).jsonObject
    CodexComposerAttachment(
        id = requireNotNull(value.string("id")),
        label = value.string("label").orEmpty(),
        input = CodexUserInput.Raw(value.getValue("input").jsonObject),
    )
}.getOrNull()
