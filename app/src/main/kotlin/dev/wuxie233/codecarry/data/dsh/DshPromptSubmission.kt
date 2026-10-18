package dev.wuxie233.codecarry.data.dsh

import dev.wuxie233.codecarry.data.api.PromptPart
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Returns true only when a prompt was submitted; a handled command does not start a turn. */
suspend fun DshApiClient.submitPrompt(
    connection: DshConnection,
    address: DshSessionAddress,
    mode: String,
    content: JsonArray,
    explicitSkillPrompt: Boolean = false,
    requestId: String = DshRpc.mintRequestId(),
): Boolean {
    if (!explicitSkillPrompt && isDshSlashCommand(content)) {
        val agentId = when (address) {
            is DshSessionAddress.Session -> address.sessionId
            is DshSessionAddress.Subagent -> address.childSessionId
        }
        val command = commandExecute(connection, agentId, content.single().jsonObject.getValue("text").jsonPrimitive.content)
        if (command != null) {
            if (command.result.kind != "success") throw DshCommandNotExecutedException()
            return false
        }
    }
    when (address) {
        is DshSessionAddress.Session -> sessionPrompt(connection, address.sessionId, mode, content, requestId = requestId)
        is DshSessionAddress.Subagent -> subagentPrompt(
            connection, address.parentSessionId, address.childSessionId, content, mode, requestId = requestId,
        )
    }
    return true
}

class DshCommandNotExecutedException : IllegalStateException("Command not executed")

private val skillNamePattern = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")

/** Host tool-skill's whitespace-bounded gesture grammar. */
fun isDshSkillName(name: String): Boolean = name.matches(skillNamePattern)

/** Preserve human text and attachments; the additional text block carries only gestures. */
fun dshSkillPromptParts(
    parts: List<PromptPart>,
    names: List<String>,
): List<PromptPart> {
    require(names.all(::isDshSkillName))
    if (names.isEmpty()) return parts.toList()
    return parts + PromptPart(
        type = "text", text = names.distinct().joinToString(" ") { "/$it" },
    )
}
