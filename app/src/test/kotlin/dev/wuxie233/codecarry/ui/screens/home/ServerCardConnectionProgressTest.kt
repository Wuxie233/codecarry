package dev.wuxie233.codecarry.ui.screens.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.wuxie233.codecarry.domain.model.ConnectionPhase
import dev.wuxie233.codecarry.domain.model.ServerConfig
import dev.wuxie233.codecarry.domain.model.ServerType
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

    @Test
    fun `compact OpenCode row shows one real connection phase and disconnect`() {
        compose.setContent {
            MaterialTheme {
                OpenCodeServerRow(
                    server = ServerConfig(
                        id = "server",
                        type = ServerType.OPENCODE,
                        url = "http://example.test:4096",
                        name = "Build server",
                    ),
                    isConnected = false,
                    isConnecting = true,
                    connectionPhase = ConnectionPhase.RestoringActivity,
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

        compose.onAllNodesWithText("Restoring task status…").assertCountEquals(1)
        compose.onNodeWithText("Build server").assertExists()
    }
}
