package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSubagentModelTest {
    @Test
    fun `only direct children are returned and newest comes first`() {
        val sessions = listOf(
            session("direct-old", "root", "Old", updated = 10),
            session("direct-new", "root", "New", updated = 30),
            session("grandchild", "direct-new", "Nested", updated = 40),
            session("other", "another-root", "Other", updated = 50),
        )

        val result = buildDirectChatSubagents("root", sessions, emptyMap(), emptyMap(), emptyMap())

        assertEquals(listOf("direct-new", "direct-old"), result.map { it.id })
        assertTrue(result.all { it.activity == ChatSubagentActivity.Completed })
    }

    @Test
    fun `busy and retry statuses are running while idle is history`() {
        val sessions = listOf(
            session("busy", "root", "Build UI", updated = 30),
            session("retry", "root", "Run tests", updated = 20),
            session("idle", "root", "Write docs", updated = 10),
        )
        val statuses = mapOf(
            "busy" to SessionStatus.Busy,
            "retry" to SessionStatus.Retry(attempt = 2, message = "again", next = 0),
        )

        val result = buildDirectChatSubagents("root", sessions, statuses, emptyMap(), emptyMap())

        assertEquals(ChatSubagentActivity.Running, result.first { it.id == "busy" }.activity)
        assertEquals(ChatSubagentActivity.Retrying, result.first { it.id == "retry" }.activity)
        assertEquals(listOf("idle"), filterChatSubagentHistory(result, "docs").map { it.id })
        assertTrue(filterChatSubagentHistory(result, "Build").isEmpty())
    }

    @Test
    fun `history search matches title only ignoring case`() {
        val items = listOf(
            ChatSubagentItem("one", "Fix Login", "/not/a/login/match", 2, ChatSubagentActivity.Completed),
            ChatSubagentItem("two", "Docs", "/work", 1, ChatSubagentActivity.Completed),
        )

        assertEquals(listOf("one"), filterChatSubagentHistory(items, "LOGIN").map { it.id })
        assertTrue(filterChatSubagentHistory(items, "work").isEmpty())
    }

    @Test
    fun `children outside the current server are excluded even with matching parent id`() {
        val sessions = listOf(
            session("local-child", "root", "Local", updated = 20),
            session("foreign-child", "root", "Foreign", updated = 30),
        )

        val result = buildDirectChatSubagents(
            parentSessionId = "root",
            sessions = sessions,
            statuses = mapOf("foreign-child" to SessionStatus.Busy),
            questions = emptyMap(),
            permissions = emptyMap(),
            allowedSessionIds = setOf("root", "local-child"),
        )

        assertEquals(listOf("local-child"), result.map { it.id })
    }

    private fun session(id: String, parentId: String, title: String, updated: Long) = Session(
        id = id,
        parentId = parentId,
        title = title,
        directory = "/repo/$id",
        time = Session.Time(created = updated - 1, updated = updated),
    )
}
