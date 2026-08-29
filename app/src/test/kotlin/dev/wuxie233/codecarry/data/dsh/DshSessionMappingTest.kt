package dev.wuxie233.codecarry.data.dsh

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshSessionMappingTest {
    @Test
    fun `workspace grouping maps directory title and archive bit`() {
        val state = DshEventState(
            sessions = mapOf(
                "s1" to DshSessionSnapshot(
                    sessionId = "s1",
                    blank = false,
                    running = true,
                    cwd = "/root/CODE/oc-remote",
                    projections = mapOf("title" to (4L to JsonPrimitive("Native chat"))),
                ),
                "blank" to DshSessionSnapshot(sessionId = "blank", blank = true),
            ),
            archivedSessionIds = setOf("s1"),
            workspaceOrder = listOf("w1"),
            workspaces = mapOf(
                "w1" to buildJsonObject {
                    put("workspaceId", "w1")
                    put("path", "/root/CODE/oc-remote")
                    put("title", "oc-remote")
                    put("sessionIds", buildJsonArray { add(JsonPrimitive("s1")) })
                },
            ),
        )
        val mapped = mapDshEventStateToSessions(state)
        assertEquals(1, mapped.sessions.size)
        val session = mapped.sessions.single()
        assertEquals("s1", session.id)
        assertEquals("/root/CODE/oc-remote", session.directory)
        assertEquals("Native chat", session.title)
        assertTrue(session.isArchived)
        assertTrue(mapped.statuses.getValue("s1") is dev.wuxie233.codecarry.domain.model.SessionStatus.Busy)
        assertNull(mapped.sessions.find { it.id == "blank" })
    }

    @Test
    fun `approval maps waterfall eventId as permission request id`() {
        val asked = mapDshApproval(
            DshPendingApproval(
                eventId = "event-1",
                sessionId = "s1",
                toolName = "bash",
                reason = "sandbox",
            ),
        )
        assertEquals("event-1", asked.id)
        assertEquals("bash", asked.permission)
    }

    @Test
    fun `single-select custom text is exclusive of selected labels`() {
        val questions = listOf(
            DshQuestionItem(
                id = "target",
                question = "Choose one",
                options = listOf(DshQuestionOption("Code"), DshQuestionOption("Docs")),
            ),
        )
        val answer = dshQuestionAnswer(questions, listOf(listOf("Release notes")))
        assertEquals("target", answer.answers.single().id)
        assertTrue(answer.answers.single().selected.isEmpty())
        assertEquals("Release notes", answer.answers.single().custom)
    }

    @Test
    fun `multi-select keeps labels and custom together`() {
        val questions = listOf(
            DshQuestionItem(
                id = "signals",
                question = "Pick",
                multiSelect = true,
                options = listOf(DshQuestionOption("Code"), DshQuestionOption("Docs")),
            ),
        )
        val answer = dshQuestionAnswer(questions, listOf(listOf("Code", "Docs", "Release notes")))
        assertEquals(listOf("Code", "Docs"), answer.answers.single().selected)
        assertEquals("Release notes", answer.answers.single().custom)
    }
}
