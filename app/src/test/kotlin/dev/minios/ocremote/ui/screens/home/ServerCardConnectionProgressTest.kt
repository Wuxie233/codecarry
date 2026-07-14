package dev.minios.ocremote.ui.screens.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.minios.ocremote.domain.model.ConnectionPhase
import dev.minios.ocremote.domain.model.ServerConfig
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ServerCardConnectionProgressTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `connecting card shows current work and delayed reassurance`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                ServerCard(
                    server = ServerConfig(id = "server", url = "http://example.test:4096"),
                    isConnected = false,
                    isConnecting = true,
                    connectionPhase = ConnectionPhase.SyncingSessions,
                    connectionError = null,
                    showServerSettings = false,
                    onConnect = {},
                    onDisconnect = {},
                    onOpenSessions = {},
                    onServerSettings = {},
                    onEdit = {},
                    onDelete = {},
                )
            }
        }

        compose.onAllNodesWithText("Syncing sessions…").assertCountEquals(1)
        compose.onNodeWithText("Disconnect").assertExists()
        compose.mainClock.advanceTimeBy(8_100)
        compose.waitForIdle()
        compose.onNodeWithText("Still working. Large workspaces or many sessions can take longer.")
            .assertExists()
    }
}
