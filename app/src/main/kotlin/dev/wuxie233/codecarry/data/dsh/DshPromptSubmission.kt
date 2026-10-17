package dev.wuxie233.codecarry.data.dsh

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Returns true only when a prompt was submitted; a handled command does not start a turn. */
suspend fun DshApiClient.submitPrompt(
    connection: DshConnection,
    address: DshSessionAddress,
    mode: String,
    content: JsonArray,
): Boolean {
    if (isDshSlashCommand(content)) {
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
        is DshSessionAddress.Session -> sessionPrompt(connection, address.sessionId, mode, content)
        is DshSessionAddress.Subagent -> subagentPrompt(
            connection, address.parentSessionId, address.childSessionId, content, mode,
        )
    }
    return true
}

class DshCommandNotExecutedException : IllegalStateException("Command not executed")
