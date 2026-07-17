package dev.minios.ocremote.ui.screens.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.ui.screens.sessions.components.ActiveConversationItem
import dev.minios.ocremote.ui.screens.sessions.components.ActiveConversationsBanner
import dev.minios.ocremote.ui.screens.sessions.components.ConversationStatus
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ActiveConversationsBannerLayoutTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun manyActiveConversationsKeepProjectListReachable() {
        var projectClicked = false
        val conversations = List(12) { index ->
            ActiveConversationItem(
                sessionId = "session-$index",
                directory = "/workspace/project-$index",
                title = "Active conversation $index",
                projectName = "Project $index",
                status = ConversationStatus.BUSY,
                pendingCount = 0,
                updatedAt = System.currentTimeMillis(),
            )
        }

        rule.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .width(420.dp)
                        .height(700.dp),
                ) {
                    Column(Modifier.fillMaxSize()) {
                        ActiveConversationsBanner(
                            items = conversations,
                            onClick = { _, _ -> },
                            modifier = Modifier.testTag("active-conversations"),
                        )
                        Text(
                            text = "Project list target",
                            modifier = Modifier
                                .testTag("project-list-target")
                                .clickable { projectClicked = true },
                        )
                    }
                }
            }
        }

        rule.onNodeWithTag("project-list-target").assertIsDisplayed().performClick()
        assertTrue(projectClicked)
        rule.onNodeWithText("Active conversation 11").assertIsNotDisplayed()
        repeat(8) {
            rule.onNodeWithTag("active-conversations").performTouchInput { swipeLeft() }
        }
        rule.onNodeWithText("Active conversation 11").assertIsDisplayed()
        rule.onNodeWithTag("project-list-target").assertIsDisplayed()
    }
}
