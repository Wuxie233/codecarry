package dev.minios.ocremote.ui.screens.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HomeRecentWorkPlacementTest {

    @Test
    fun `recent work belongs to the OpenCode session control surface`() {
        val homeSource = File("src/main/kotlin/dev/minios/ocremote/ui/screens/home/HomeScreen.kt").readText()
        val sessionSource = File("src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt").readText()

        assertFalse(homeSource.contains("SessionRecentWork"))
        assertFalse(homeSource.contains("recentWork"))
        assertTrue(sessionSource.contains("SessionRecentWork("))
    }
}
