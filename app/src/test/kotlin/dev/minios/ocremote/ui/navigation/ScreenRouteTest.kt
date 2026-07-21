package dev.minios.ocremote.ui.navigation

import dev.minios.ocremote.domain.model.ServerType
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenRouteTest {
    @Test
    fun `pi roundtable routes do not include bearer token`() {
        val secret = "pi-secret-token"
        val center = Screen.RoundtableCenter.createRoute(serverId = "server-1")
        val casting = Screen.RoundtableCasting.createRoute(serverId = "server-1", castingId = "casting-1")
        val summary = Screen.RoundtableSummary.createRoute(serverId = "server-1", roundtableId = "round-1")
        val personas = Screen.PersonaLibrary.createRoute(serverId = "server-1")

        listOf(center, casting, summary, personas).forEach { route ->
            assertFalse(route, route.contains(secret))
            assertFalse(route, route.contains("token=", ignoreCase = true))
            assertTrue(route, route.contains("serverId="))
        }
    }

    @Test
    fun `Codex routes contain only server and thread identity`() {
        val secret = "codex-secret-token"

        val route = Screen.CodexChat.createRoute("server-1", "thread-1")

        assertTrue(route.contains("serverId=server-1"))
        assertTrue(route.contains("threadId=thread-1"))
        assertFalse(route.contains(secret))
        assertFalse(route.contains("token=", ignoreCase = true))
        assertFalse(route.contains("serverUrl=", ignoreCase = true))
    }

    @Test
    fun `child chat route keeps current server and exact child identity`() {
        val route = Screen.Chat.createRoute(
            serverUrl = "https://example.test",
            username = "user",
            password = "password",
            serverName = "Current server",
            serverId = "server-current",
            sessionId = "child-session",
            directory = "/workspace/child project",
        )
        val arguments = route.substringAfter('?')
            .split('&')
            .associate { argument ->
                val (key, value) = argument.split('=', limit = 2)
                key to URLDecoder.decode(value, StandardCharsets.UTF_8.name())
            }

        assertEquals("server-current", arguments["serverId"])
        assertEquals("child-session", arguments["sessionId"])
        assertEquals("/workspace/child project", arguments["directory"])
    }

    @Test
    fun `opening a child session does not reuse the parent chat entry`() {
        assertFalse(shouldLaunchSingleTopChat("parent-session", "child-session"))
        assertTrue(shouldLaunchSingleTopChat("child-session", "child-session"))
    }

    @Test
    fun `Pi Stack session list route preserves backend type without exposing token metadata`() {
        val route = Screen.SessionList.createRoute(
            serverUrl = "https://pi.example.test",
            username = "",
            password = "secret",
            serverName = "Pi Stack",
            serverId = "pi-server",
            serverType = ServerType.PI_STACK.name,
        )

        assertTrue(route.contains("serverType=PI_STACK"))
        assertFalse(route.contains("token=", ignoreCase = true))
    }
}
