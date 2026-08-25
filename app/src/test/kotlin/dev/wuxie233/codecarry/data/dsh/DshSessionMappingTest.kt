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
    fun `approval maps host rpcId as permission request id`() {
        val asked = mapDshApproval(
            DshPendingApproval(
                rpcId = "host-rpc",
                sessionId = "s1",
                approvalId = "a1",
                toolName = "bash",
                reason = "sandbox",
            ),
        )
        assertEquals("host-rpc", asked.id)
        assertEquals("bash", asked.permission)
    }
}
