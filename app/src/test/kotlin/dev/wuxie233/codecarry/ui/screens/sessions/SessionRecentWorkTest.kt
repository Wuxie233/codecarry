package dev.wuxie233.codecarry.ui.screens.sessions

import dev.wuxie233.codecarry.domain.model.Session
import dev.wuxie233.codecarry.domain.model.SessionStatus
import dev.wuxie233.codecarry.ui.screens.sessions.components.shortSessionDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRecentWorkTest {

    @Test
    fun `recent work contains newest non-archived roots with effective status`() {
        val older = session("older", updated = 10)
        val newer = session("newer", updated = 30)
        val archived = session("archived", updated = 40, archivedAt = 40)

        val result = buildSessionRecentWork(
            rootSessions = listOf(older, newer, archived),
            effectiveStatuses = mapOf("newer" to SessionStatus.Busy),
        )

        assertEquals(listOf("newer", "older"), result.map(SessionRecentWorkItem::sessionId))
        assertTrue(result.first().status is SessionStatus.Busy)
    }

    @Test
    fun `recent work enforces compact limit and shortens paths`() {
        val sessions = (1L..8L).map { session("session-$it", updated = it) }

        val result = buildSessionRecentWork(sessions, effectiveStatuses = emptyMap())

        assertEquals(6, result.size)
        assertEquals("session-8", result.first().sessionId)
        assertEquals(".../team/project", shortSessionDirectory("/home/team/project/"))
        assertEquals("project", shortSessionDirectory("project"))
    }

    private fun session(id: String, updated: Long, archivedAt: Long? = null) = Session(
        id = id,
        directory = "/work/$id",
        title = "Title $id",
        time = Session.Time(updated = updated, archived = archivedAt),
    )
}
