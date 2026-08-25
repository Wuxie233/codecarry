package dev.wuxie233.codecarry.ui.navigation

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenRouteTest {
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
    fun `dsh host surfaces route keeps server identity`() {
        val route = Screen.DshHostSurfaces.createRoute(
            serverUrl = "http://192.168.1.8:3080",
            username = "",
            password = "",
            serverName = "DSH",
            serverId = "dsh-1",
        )
        val arguments = route.substringAfter('?')
            .split('&')
            .associate { argument ->
                val (key, value) = argument.split('=', limit = 2)
                key to URLDecoder.decode(value, StandardCharsets.UTF_8.name())
            }
        assertEquals("dsh-1", arguments["serverId"])
        assertEquals("http://192.168.1.8:3080", arguments["serverUrl"])
        assertTrue(route.startsWith("dsh_host_surfaces?"))
    }
}
