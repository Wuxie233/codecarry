package dev.wuxie233.codecarry.ui.screens.sessions.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import dev.wuxie233.codecarry.domain.model.SessionStatus
import dev.wuxie233.codecarry.ui.screens.sessions.SessionRecentWorkItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionRecentWorkLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun recentWorkScrollsHorizontallyToLastSession() {
        val items = (1L..6L).map { index ->
            SessionRecentWorkItem(
                sessionId = "session-$index",
                title = "会话 $index",
                directory = "/work/project-$index",
                updatedAt = index,
                status = SessionStatus.Idle,
            )
        }

        compose.setContent {
            MaterialTheme {
                SessionRecentWork(items = items, onSessionClick = { _, _ -> })
            }
        }

        compose.onNodeWithTag("session_recent_work").performScrollToNode(hasText("会话 6"))
        compose.onNodeWithText("会话 6").assertExists()
    }
}
