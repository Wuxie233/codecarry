package dev.minios.ocremote.ui.screens.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatHeaderLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun compactHeaderShowsContextAndKeepsEveryActionTouchSized() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    ChatHeader(
                        title = "Investigate streaming response recovery",
                        context = "/srv/workspaces/client/apps/oc-remote-android",
                        backendLabel = "OpenCode",
                        statusLabel = "Working…",
                        usageSummary = "12.4k tokens",
                        canStop = true,
                        showSubagents = true,
                        runningSubagentCount = 2,
                        showTerminal = true,
                        showOverflow = true,
                        onNavigateBack = {},
                        onStop = {},
                        onToggleSubagents = {},
                        onOpenTerminal = {},
                        onOpenOverflow = {},
                        overflowMenu = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Investigate streaming respo…").assertIsDisplayed()
        compose.onNodeWithText(
            "/srv/workspac…remote-android · OpenCode · Working… · 12.4k tokens",
        ).assertIsDisplayed()
        listOf("Back", "Stop", "More options")
            .forEach { description ->
                compose.onNodeWithContentDescription(description)
                    .assertIsDisplayed()
                    .assertWidthIsAtLeast(48.dp)
            }
    }
}
