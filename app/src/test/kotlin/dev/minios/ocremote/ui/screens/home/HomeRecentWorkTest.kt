package dev.minios.ocremote.ui.screens.home

import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.model.ServerType
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRecentWorkTest {

    @Test
    fun `recent work contains only current root OpenCode sessions in update order`() {
        val openCode = ServerConfig(id = "open", url = "https://open.test", name = "Open")
        val codex = ServerConfig(id = "codex", type = ServerType.CODEX, url = "wss://codex.test")
        val sessions = listOf(
            session("older", 10, directory = "/work/alpha"),
            session("newer", 30, directory = "/work/beta"),
            session("child", 40, parentId = "newer"),
            session("archived", 50, archived = 50),
            session("codex-session", 60),
        )

        val result = buildHomeRecentWork(
            servers = listOf(openCode, codex),
            sessions = sessions,
            serverSessions = mapOf(
                openCode.id to setOf("older", "newer", "child", "archived"),
                codex.id to setOf("codex-session"),
            ),
            statuses = mapOf("child" to SessionStatus.Busy),
        )

        assertEquals(listOf("newer", "older"), result.map(HomeRecentWorkItem::sessionId))
        assertTrue(result.first().status is SessionStatus.Busy)
        assertEquals("Open", result.first().serverName)
    }

    @Test
    fun `recent work honors compact limit and shortens long paths`() {
        val server = ServerConfig(id = "server", url = "https://open.test")
        val sessions = (1L..8L).map { session("session-$it", it) }

        val result = buildHomeRecentWork(
            servers = listOf(server),
            sessions = sessions,
            serverSessions = mapOf(server.id to sessions.map(Session::id).toSet()),
            statuses = emptyMap(),
        )

        assertEquals(6, result.size)
        assertEquals("session-8", result.first().sessionId)
        assertEquals(".../team/project", shortHomeDirectory("/home/team/project/"))
        assertEquals("project", shortHomeDirectory("project"))
    }

    @Test
    fun `child status aggregation stays inside each server session set`() {
        val local = ServerConfig(id = "local", url = "https://local.test", name = "Local")
        val foreign = ServerConfig(
            id = "foreign",
            type = ServerType.CODEX,
            url = "wss://foreign.test",
        )
        val sessions = listOf(
            session("root", 10),
            session("foreign-child", 20, parentId = "root"),
        )

        val result = buildHomeRecentWork(
            servers = listOf(local, foreign),
            sessions = sessions,
            serverSessions = mapOf(
                local.id to setOf("root"),
                foreign.id to setOf("foreign-child"),
            ),
            statuses = mapOf("foreign-child" to SessionStatus.Busy),
        )

        assertEquals(listOf("root"), result.map(HomeRecentWorkItem::sessionId))
        assertEquals(SessionStatus.Idle, result.single().status)
    }

    private fun session(
        id: String,
        updated: Long,
        directory: String = "/work/project",
        parentId: String? = null,
        archived: Long? = null,
    ) = Session(
        id = id,
        directory = directory,
        parentId = parentId,
        title = "Title $id",
        time = Session.Time(updated = updated, archived = archived),
    )
}
