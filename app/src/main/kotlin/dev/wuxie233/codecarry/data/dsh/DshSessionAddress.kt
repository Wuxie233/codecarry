package dev.wuxie233.codecarry.data.dsh

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Host `SessionAddress` for `session/follow` and `session/page`.
 * Ordinary chats stay `{ kind: "session", sessionId }`. A child whose
 * origin is `subagent` must send the durable parent address; Host
 * rejects `kind: "session"` with `session/agent-busy`.
 */
sealed interface DshSessionAddress {
    fun toJson(): JsonObject

    data class Session(val sessionId: String) : DshSessionAddress {
        override fun toJson(): JsonObject = buildJsonObject {
            put("kind", "session")
            put("sessionId", sessionId)
        }
    }

    data class Subagent(
        val parentSessionId: String,
        val childSessionId: String,
        val mode: String,
    ) : DshSessionAddress {
        override fun toJson(): JsonObject = buildJsonObject {
            put("kind", "subagent")
            put("parentSessionId", parentSessionId)
            put("childSessionId", childSessionId)
            put("mode", mode)
        }
    }
}

/**
 * Address for this snapshot's history stream, or `null` when origin is
 * subagent and parent is still unknown — callers must wait rather than
 * emit `{ kind: "session" }`.
 */
fun DshSessionSnapshot.historyAddress(): DshSessionAddress? {
    if (origin == "subagent") {
        val parent = parentSessionId?.takeIf { it.isNotBlank() } ?: return null
        return DshSessionAddress.Subagent(
            parentSessionId = parent,
            childSessionId = sessionId,
            mode = subagentMode(),
        )
    }
    if (!listed) return null
    return DshSessionAddress.Session(sessionId)
}

private fun DshSessionSnapshot.subagentMode(): String {
    val projection = projections["subagent"]?.second as? JsonObject ?: return "continuable"
    val mode = projection["mode"]?.jsonPrimitive?.contentOrNull
    return if (mode == "one-shot" || mode == "continuable") mode else "continuable"
}
