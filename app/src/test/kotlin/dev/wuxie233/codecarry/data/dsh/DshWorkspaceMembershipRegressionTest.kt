package dev.wuxie233.codecarry.data.dsh

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class DshWorkspaceMembershipRegressionTest {
    @Test fun `assigned sessions with historical no-repo cwd stay in their real workspaces`() {
        val state = DshEventState(
            sessions = mapOf(
                "codecarry-chat" to DshSessionSnapshot(sessionId = "codecarry-chat", blank = false, cwd = "/root/.dsh/no-repo"),
                "harness-chat" to DshSessionSnapshot(sessionId = "harness-chat", blank = false, cwd = "/root/.dsh/no-repo"),
            ),
            workspaceOrder = listOf("codecarry", "harness"),
            workspaces = mapOf(
                "codecarry" to workspace("codecarry", "/root/CODE/codecarry", "CodeCarry", "codecarry-chat"),
                "harness" to workspace("harness", "/root/CODE/deepseek-harness", "DSH", "harness-chat"),
            ),
        )
        val sessions = mapDshEventStateToSessions(state).sessions.associateBy { it.id }
        assertEquals("/root/CODE/codecarry", sessions.getValue("codecarry-chat").directory)
        assertEquals("/root/CODE/deepseek-harness", sessions.getValue("harness-chat").directory)
    }

    @Test fun `workspace metadata keeps host names instead of reconstructing directory labels`() {
        val state = DshEventState(
            sessions = mapOf("s" to DshSessionSnapshot(sessionId = "s", blank = false, cwd = "/repo/subfolder")),
            workspaces = mapOf("w" to workspace("w", "/repo", "我的工作区", "s")),
        )
        val mapped = mapDshEventStateToSessions(state)
        assertEquals("/repo", mapped.sessions.single().directory)
        assertEquals("w", mapped.projects.single().id)
        assertEquals("我的工作区", mapped.projects.single().displayName)
        assertEquals("/repo", mapped.projects.single().worktree)
    }

    @Test fun `truly unassigned and no-repo sessions retain their own groups`() {
        val state = DshEventState(
            sessions = mapOf(
                "no-repo" to DshSessionSnapshot(sessionId = "no-repo", blank = false, cwd = "/root/.dsh/no-repo"),
                "unassigned" to DshSessionSnapshot(sessionId = "unassigned", blank = false, cwd = "/unassigned"),
            ),
            workspaces = mapOf("no-repo-workspace" to workspace("no-repo-workspace", "/root/.dsh/no-repo", "No Repo", "no-repo")),
        )
        val mapped = mapDshEventStateToSessions(state)
        assertEquals(setOf("/root/.dsh/no-repo", "/unassigned"), mapped.sessions.map { it.directory }.toSet())
        assertEquals(listOf("No Repo", "unassigned"), mapped.projects.map { it.displayName })
    }

    @Test fun `child sessions do not manufacture additional project groups`() {
        val state = DshEventState(sessions = mapOf(
            "child" to DshSessionSnapshot(sessionId = "child", blank = false, origin = "subagent", cwd = "/child-only"),
        ))
        assertEquals(emptyList<Any>(), mapDshEventStateToSessions(state).projects)
    }

    private fun workspace(id: String, path: String, title: String, sessionId: String) = buildJsonObject {
        put("workspaceId", id); put("path", path); put("title", title)
        put("sessionIds", buildJsonArray { add(JsonPrimitive(sessionId)) })
    }
}
