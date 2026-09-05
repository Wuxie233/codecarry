package dev.wuxie233.codecarry.data.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

data class CodexPlanStep(val step: String, val status: String)
data class CodexTurnPlan(val explanation: String?, val steps: List<CodexPlanStep>) {
    companion object {
        fun fromJson(value: JsonObject) = CodexTurnPlan(
            value.controlString("explanation"),
            value.controlObjects("plan").map { CodexPlanStep(it.controlString("step").orEmpty(), it.controlString("status") ?: "unknown") },
        )
    }
}

data class CodexTokenUsageBreakdown(
    val totalTokens: Long = 0,
    val inputTokens: Long = 0,
    val cachedInputTokens: Long = 0,
    val cacheWriteInputTokens: Long = 0,
    val outputTokens: Long = 0,
    val reasoningOutputTokens: Long = 0,
) {
    companion object {
        fun fromJson(value: JsonObject) = CodexTokenUsageBreakdown(
            value.controlLong("totalTokens"), value.controlLong("inputTokens"),
            value.controlLong("cachedInputTokens"), value.controlLong("cacheWriteInputTokens"),
            value.controlLong("outputTokens"), value.controlLong("reasoningOutputTokens"),
        )
    }
}

data class CodexThreadTokenUsage(
    val turnId: String,
    val total: CodexTokenUsageBreakdown,
    val last: CodexTokenUsageBreakdown,
    val modelContextWindow: Long?,
) {
    companion object {
        fun fromJson(turnId: String, value: JsonObject) = CodexThreadTokenUsage(
            turnId, CodexTokenUsageBreakdown.fromJson(value["total"].objectOrEmpty()),
            CodexTokenUsageBreakdown.fromJson(value["last"].objectOrEmpty()),
            (value["modelContextWindow"] as? JsonPrimitive)?.longOrNull,
        )
    }
}

data class CodexFileChange(val path: String, val kind: String, val movePath: String?, val diff: String) {
    companion object {
        fun fromJson(value: JsonObject) = CodexFileChange(
            value.controlString("path").orEmpty(),
            value["kind"].objectOrEmpty().controlString("type") ?: "unknown",
            value["kind"].objectOrEmpty().controlString("move_path"),
            value.controlString("diff").orEmpty(),
        )
    }
}

data class CodexCollabAgentState(val status: String, val message: String?)
data class CodexCollabAgentCall(
    val tool: String,
    val senderThreadId: String,
    val receiverThreadIds: List<String>,
    val prompt: String?,
    val agentsStates: Map<String, CodexCollabAgentState>,
) {
    companion object {
        fun fromJson(value: JsonObject) = CodexCollabAgentCall(
            value.controlString("tool").orEmpty(), value.controlString("senderThreadId").orEmpty(),
            (value["receiverThreadIds"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            value.controlString("prompt"),
            value["agentsStates"].objectOrEmpty().mapNotNull { (key, element) ->
                (element as? JsonObject)?.let { key to CodexCollabAgentState(it.controlString("status") ?: "unknown", it.controlString("message")) }
            }.toMap(),
        )
    }
}

data class CodexSkillsResult(val skills: List<CodexSkill>, val warnings: List<String>)

data class CodexSkill(
    val name: String,
    val description: String,
    val shortDescription: String?,
    val path: String,
    val enabled: Boolean,
) {
    companion object {
        fun fromJson(value: JsonObject) = CodexSkill(
            value.controlString("name").orEmpty(), value.controlString("description").orEmpty(),
            value["interface"].objectOrEmpty().controlString("shortDescription") ?: value.controlString("shortDescription"),
            value.controlString("path").orEmpty(), (value["enabled"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }
}

data class CodexFileMatch(val root: String, val path: String, val fileName: String, val score: Long, val matchType: String) {
    // Resolve using the host's separators, never Android's local file APIs.
    val absolutePath: String get() = when {
        path.startsWith("/") || path.startsWith("\\\\") || Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(path) -> path
        else -> root.trimEnd('/', '\\') + (if ('\\' in root && '/' !in root) "\\" else "/") + path
    }
    companion object {
        fun fromJson(value: JsonObject) = CodexFileMatch(
            value.controlString("root").orEmpty(), value.controlString("path").orEmpty(),
            value.controlString("file_name").orEmpty(), value.controlLong("score"),
            value.controlString("match_type") ?: "unknown",
        )
    }
}

class CodexCapabilityUnavailableException(val method: String, cause: Throwable) :
    RuntimeException("Codex server does not support $method", cause)

internal fun JsonObject.controlObjects(key: String): List<JsonObject> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
private fun JsonObject.controlString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.controlLong(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0
