package dev.minios.ocremote.ui.navigation

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
}
