package dev.wuxie233.codecarry.data.codex

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Remote failures keep their original owner; a retry notification is not a failed turn. */
data class CodexFailure(
    val threadId: String,
    val turnId: String? = null,
    val message: String,
    val detail: String? = null,
    val code: String? = null,
    val willRetry: Boolean = false,
) {
    companion object {
        fun fromNotification(threadId: String, turnId: String?, params: JsonObject): CodexFailure =
            fromError(threadId, turnId, params["error"] ?: params).copy(
                willRetry = (params["willRetry"] as? JsonPrimitive)?.booleanOrNull == true,
            )

        fun fromError(threadId: String, turnId: String?, error: JsonElement): CodexFailure {
            val obj = error as? JsonObject
            fun text(key: String) = (obj?.get(key) as? JsonPrimitive)?.contentOrNull
            val info = obj?.get("codexErrorInfo")?.takeUnless { it == JsonNull }
            val code = text("code") ?: (info as? JsonPrimitive)?.contentOrNull
                ?: (info as? JsonObject)?.keys?.singleOrNull()
            return CodexFailure(
                threadId = threadId,
                turnId = turnId,
                message = text("message") ?: (error as? JsonPrimitive)?.contentOrNull.orEmpty(),
                detail = obj?.get("additionalDetails")?.takeUnless { it == JsonNull }?.let {
                    (it as? JsonPrimitive)?.contentOrNull ?: it.toString()
                } ?: obj?.get("details")?.takeUnless { it == JsonNull }?.let {
                    (it as? JsonPrimitive)?.contentOrNull ?: it.toString()
                },
                code = code,
            )
        }
    }
}

/** Snapshot errors survive reconnect; terminal snapshots retire transient retry notices. */
fun CodexEventState.failuresForThread(threadId: String): Map<String, CodexFailure> {
    val result = turnFailures[threadId].orEmpty().toMutableMap()
    threads[threadId]?.turns.orEmpty().forEach { turn ->
        if (turn.status in setOf("completed", "failed", "interrupted") && result[turn.id]?.willRetry == true) {
            result.remove(turn.id)
        }
        turn.error?.takeUnless { it == JsonNull }?.let { error ->
            result[turn.id] = CodexFailure.fromError(threadId, turn.id, error)
        }
    }
    return result
}
