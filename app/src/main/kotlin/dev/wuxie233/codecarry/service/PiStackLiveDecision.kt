package dev.wuxie233.codecarry.service

import dev.wuxie233.codecarry.data.api.PiStackEventDto
import dev.wuxie233.codecarry.data.api.PiStackQuestionResolutionDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal data class PiStackCompletionDecision(
    val eventKey: String,
    val sessionId: String,
)

@Serializable
private data class OperationSettledPayload(
    val operationId: String,
    val outcome: String,
)

internal fun decidePiStackCompletion(json: Json, event: PiStackEventDto): PiStackCompletionDecision? {
    if (event.type != "operation.settled") return null
    val sessionId = event.scope.sessionId ?: return null
    val payload = runCatching {
        json.decodeFromJsonElement(OperationSettledPayload.serializer(), event.payload)
    }.getOrNull() ?: return null
    if (payload.outcome != "completed") return null
    return PiStackCompletionDecision(
        eventKey = "${event.generation}:${payload.operationId}:completed",
        sessionId = sessionId,
    )
}

internal fun shouldRemovePiStackQuestion(result: PiStackQuestionResolutionDto): Boolean = when (result.kind) {
    "already_replied", "already_rejected", "expired", "stale_question" -> true
    "delivery_pending", "delivery_in_progress" -> false
    else -> false
}

internal class PiStackResyncRequired(message: String) : IllegalStateException(message)
