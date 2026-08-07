package dev.wuxie233.codecarry.service

import dev.wuxie233.codecarry.data.api.PiStackEventDto
import dev.wuxie233.codecarry.data.api.PiStackEventScopeDto
import dev.wuxie233.codecarry.data.api.PiStackQuestionDto
import dev.wuxie233.codecarry.data.api.PiStackQuestionPayloadDto
import dev.wuxie233.codecarry.data.api.PiStackQuestionResolutionDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiStackLiveDecisionTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `only completed session operation creates background notification decision`() {
        val completed = event("operation.settled", """{"operationId":"operation-1","outcome":"completed"}""")
        val failed = event("operation.settled", """{"operationId":"operation-1","outcome":"failed"}""")

        assertEquals("generation-1:operation-1:completed", decidePiStackCompletion(json, completed)?.eventKey)
        assertEquals("session-1", decidePiStackCompletion(json, completed)?.sessionId)
        assertNull(decidePiStackCompletion(json, failed))
        assertNull(decidePiStackCompletion(json, event("notification.created", "{}")))
    }

    @Test
    fun `question remains pending during delivery and removes only for terminal results`() {
        assertFalse(shouldRemovePiStackQuestion(resolution("delivery_pending")))
        assertFalse(shouldRemovePiStackQuestion(resolution("delivery_in_progress")))
        assertTrue(shouldRemovePiStackQuestion(resolution("already_replied")))
        assertTrue(shouldRemovePiStackQuestion(resolution("already_rejected")))
        assertTrue(shouldRemovePiStackQuestion(resolution("expired")))
        assertTrue(shouldRemovePiStackQuestion(resolution("stale_question")))
    }

    private fun event(type: String, payload: String) = PiStackEventDto(
        protocolVersion = 1,
        generation = "generation-1",
        eventId = "event-1",
        sequence = 1,
        scope = PiStackEventScopeDto(sessionId = "session-1"),
        type = type,
        payload = json.parseToJsonElement(payload),
        ts = "2026-07-22T00:00:00Z",
    )

    private fun resolution(kind: String) = PiStackQuestionResolutionDto(
        kind = kind,
        question = PiStackQuestionDto(
            id = "question-1",
            sessionId = "session-1",
            projectId = "project-1",
            workerGeneration = "generation-1",
            kind = "free_text",
            payload = PiStackQuestionPayloadDto("free_text", "Continue?"),
            status = "pending",
            createdAt = "2026-07-22T00:00:00Z",
        ),
    )
}
