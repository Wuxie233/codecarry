package dev.minios.ocremote.ui.screens.sessions

import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.ui.screens.sessions.components.SubagentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionListViewModelActiveSubagentsTest {

    @Test
    fun `default mode only shows busy and retry subagents`() {
        val parent = session(id = "parent", title = "Parent", updated = 100)
        val busy = session(id = "busy", parentId = parent.id, title = "Busy child", updated = 300)
        val retry = session(id = "retry", parentId = parent.id, title = "Retry child", updated = 200)
        val idle = session(id = "idle", parentId = parent.id, title = "Idle child", updated = 400)

        val items = buildActiveSubagents(
            sessions = listOf(parent, busy, retry, idle),
            statuses = mapOf(
                busy.id to SessionStatus.Busy,
                retry.id to SessionStatus.Retry(1, "retrying", 0L),
                idle.id to SessionStatus.Idle,
            ),
            showHistoricalSubagents = false,
        )

        assertEquals(listOf("busy", "retry"), items.map { it.sessionId })
        assertEquals(listOf(SubagentStatus.BUSY, SubagentStatus.RETRY), items.map { it.status })
    }

    @Test
    fun `historical mode also shows idle subagents and still excludes archived ones`() {
        val parent = session(id = "parent", title = "Parent", updated = 100)
        val idle = session(id = "idle", parentId = parent.id, title = "Idle child", updated = 500)
        val busy = session(id = "busy", parentId = parent.id, title = "Busy child", updated = 300)
        val archivedIdle = session(id = "archived", parentId = parent.id, title = "Archived child", updated = 700, archived = 1L)

        val items = buildActiveSubagents(
            sessions = listOf(parent, idle, busy, archivedIdle),
            statuses = mapOf(
                idle.id to SessionStatus.Idle,
                busy.id to SessionStatus.Busy,
                archivedIdle.id to SessionStatus.Idle,
            ),
            showHistoricalSubagents = true,
        )

        assertEquals(listOf("idle", "busy"), items.map { it.sessionId })
        assertEquals(listOf(SubagentStatus.IDLE, SubagentStatus.BUSY), items.map { it.status })
        assertTrue(items.none { it.sessionId == "archived" })
    }

    private fun session(
        id: String,
        parentId: String? = null,
        title: String? = null,
        updated: Long,
        archived: Long? = null,
    ) = Session(
        id = id,
        slug = id,
        projectId = "p1",
        directory = "/root/CODE/demo",
        parentId = parentId,
        title = title,
        version = "1.0.0",
        time = Session.Time(created = updated - 10, updated = updated, archived = archived),
    )
}
